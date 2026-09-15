package az.gmb.taxdata.controller;

import az.gmb.taxdata.auth.AuthenticatedUser;
import az.gmb.taxdata.auth.CurrentUserContext;
import az.gmb.taxdata.auth.SectionAccessService;
import az.gmb.taxdata.service.PortableTransferBackupService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/transfer-backup")
public class PortableTransferBackupController {
    private final PortableTransferBackupService transfer;
    private final SectionAccessService sections;

    public PortableTransferBackupController(PortableTransferBackupService transfer, SectionAccessService sections) {
        this.transfer = transfer;
        this.sections = sections;
    }

    @PostMapping(value="/export", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> export(@RequestBody Map<String,Object> body) throws Exception {
        AuthenticatedUser user = CurrentUserContext.require();
        requireBackupAccess(user);
        String password = String.valueOf(body.getOrDefault("password", ""));
        Map<String,Object> browserSettings = map(body.get("browserSettings"));
        byte[] file = transfer.exportTransfer(user, browserSettings, password.toCharArray());
        String safeUser = user.username().replaceAll("[^A-Za-z0-9._-]", "_");
        String stamp = Instant.now().toString().replace(":", "-").replace(".", "-");
        String name = "taxdata_" + safeUser + "_tam_transfer_" + stamp + ".tdbackup";
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-TAXDATA-Transfer-Format", "TDTB1-AES256-GCM")
                .header("Cache-Control", "no-store")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length)
                .body(file);
    }

    @PostMapping(value="/restore", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> restore(@RequestPart("file") MultipartFile file,
                                      @RequestParam("password") String password) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Tam transfer backup faylı seçilməyib.");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".tdbackup")) throw new IllegalArgumentException("Tam transfer faylı .tdbackup formatında olmalıdır.");
        AuthenticatedUser user = CurrentUserContext.require();
        requireBackupAccess(user);
        var result = transfer.restoreTransfer(user, file.getBytes(), password.toCharArray());
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("restored", result.restored());
        out.put("sourceUsername", result.sourceUsername());
        out.put("sameAccount", result.sameAccount());
        out.put("companyCount", result.companyCount());
        out.put("invoiceCount", result.invoiceCount());
        out.put("workspaceBytes", result.workspaceBytes());
        out.put("browserSettings", result.browserSettings());
        out.put("profileStorage", "POSTGRESQL_SERVER_AUTHORITATIVE");
        out.put("templateMode", "STANDARD_EMBEDDED_ONLY");
        out.put("message", result.message());
        return out;
    }

    private void requireBackupAccess(AuthenticatedUser user) {
        if (!sections.hasAccess(user, "07.01")) throw new SecurityException("Backup və lokal yaddaş bölməsi üçün icazəniz yoxdur.");
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> map(Object value) {
        if (!(value instanceof Map<?,?> raw)) return Map.of();
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> e : raw.entrySet()) if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }
}
