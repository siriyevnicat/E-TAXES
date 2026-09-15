package az.gmb.taxdata.controller;

import az.gmb.taxdata.auth.CurrentUserContext;
import az.gmb.taxdata.model.WorkspaceState;
import az.gmb.taxdata.service.StorageService;
import az.gmb.taxdata.service.WorkspaceArchiveService;
import az.gmb.taxdata.service.WorkspaceDataService;
import az.gmb.taxdata.service.CompanyRegistryService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/local-workspace")
public class LocalWorkspaceController {
    private final StorageService storage;
    private final WorkspaceArchiveService archive;
    private final WorkspaceDataService data;
    private final CompanyRegistryService companies;

    public LocalWorkspaceController(StorageService storage, WorkspaceArchiveService archive, WorkspaceDataService data, CompanyRegistryService companies) {
        this.storage = storage; this.archive = archive; this.data = data; this.companies = companies;
    }

    private String ws() {
        String w = CurrentUserContext.require().workspaceId();
        if (!storage.isSessionWorkspace(w)) throw new SecurityException("Lokal sessiya workspace-i tələb olunur.");
        return w;
    }

    @GetMapping("/status")
    public Map<String,Object> status() throws IOException {
        String w=ws(); boolean exists=storage.workspaceExists(w);
        boolean initialized=exists && Files.isRegularFile(storage.getWorkspaceDir(w).resolve("workspace-state.json"));
        long bytes=initialized?storage.workspaceSize(w):0L;
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("initialized",initialized); out.put("bytes",bytes); out.put("workspace",w);
        out.put("storageMode","BROWSER_LOCAL_FIRST"); out.put("serverPersistence","TEMPORARY_SESSION_ONLY");
        out.put("checkedAt",Instant.now().toString()); return out;
    }

    @PostMapping("/initialize")
    public Map<String,Object> initialize() throws IOException {
        String w=ws(); WorkspaceState s=data.getState(w); int companyCount=companies.count(CurrentUserContext.require().id(),w); storage.touchWorkspace(w);
        return Map.of("initialized",true,"companyCount",companyCount,"companyStorage","SQL","invoiceCount",s.getInvoices().size(),"bytes",storage.workspaceSize(w));
    }

    @PostMapping(value="/restore",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> restore(@RequestPart("file") MultipartFile file) throws IOException {
        if(file==null||file.isEmpty()) throw new IllegalArgumentException("Lokal workspace backup seçilməyib.");
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("workspace.zip").toLowerCase(Locale.ROOT);
        if(!name.endsWith(".zip")) throw new IllegalArgumentException("Workspace backup ZIP formatında olmalıdır.");
        String w=ws();
        storage.deleteWorkspace(w);
        try(InputStream in=file.getInputStream()){archive.importWorkspace(w,in);}
        WorkspaceState s=data.getState(w); companies.remigrateLegacyWorkspace(CurrentUserContext.require().id(),w); int companyCount=companies.count(CurrentUserContext.require().id(),w); storage.touchWorkspace(w);
        return Map.of("restored",true,"companyCount",companyCount,"companyStorage","SQL","invoiceCount",s.getInvoices().size(),"bytes",storage.workspaceSize(w));
    }

    @GetMapping("/snapshot")
    public ResponseEntity<StreamingResponseBody> snapshot() throws IOException {
        String w=ws(); data.getState(w); storage.touchWorkspace(w);
        String encoded=URLEncoder.encode("taxdata-local-workspace-backup.zip", StandardCharsets.UTF_8).replace("+","%20");
        StreamingResponseBody body=out -> archive.writeWorkspaceZip(w,out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+encoded)
                .header("X-TAXDATA-Storage-Mode","browser-local-first")
                .header("X-TAXDATA-Workspace-Bytes",Long.toString(storage.workspaceSize(w)))
                .contentType(MediaType.parseMediaType("application/zip")).body(body);
    }

    @DeleteMapping("/temporary-session")
    public Map<String,Object> clearTemporarySession() throws IOException {
        String w=ws(); storage.deleteWorkspace(w); return Map.of("cleared",true);
    }
}
