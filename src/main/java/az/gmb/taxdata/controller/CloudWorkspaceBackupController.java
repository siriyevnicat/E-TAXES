package az.gmb.taxdata.controller;

import az.gmb.taxdata.auth.AuthenticatedUser;
import az.gmb.taxdata.auth.CurrentUserContext;
import az.gmb.taxdata.service.CloudWorkspaceBackupService;
import az.gmb.taxdata.service.CompanyRegistryService;
import az.gmb.taxdata.service.StorageService;
import az.gmb.taxdata.service.WorkspaceDataService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/cloud-workspace")
public class CloudWorkspaceBackupController {
    private final CloudWorkspaceBackupService cloud;
    private final StorageService storage;
    private final WorkspaceDataService data;
    private final CompanyRegistryService companies;

    public CloudWorkspaceBackupController(CloudWorkspaceBackupService cloud, StorageService storage, WorkspaceDataService data, CompanyRegistryService companies) {
        this.cloud = cloud;
        this.storage = storage;
        this.data = data;
        this.companies = companies;
    }

    @GetMapping("/status")
    public Map<String,Object> status() {
        AuthenticatedUser user = CurrentUserContext.require();
        Optional<CloudWorkspaceBackupService.BackupInfo> latest = cloud.latestInfo(user.id());
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("enabled", cloud.isEnabled());
        out.put("retentionDays", cloud.retentionDays());
        out.put("keyManagement", "SUPABASE_VAULT_AUTO");
        if (!cloud.isEnabled() && !cloud.initializationError().isBlank()) out.put("initializationError", cloud.initializationError());
        out.put("hasBackup", latest.isPresent());
        out.put("latest", latest.orElse(null));
        out.put("checkedAt", Instant.now().toString());
        return out;
    }

    @GetMapping("/versions")
    public Map<String,Object> versions() {
        AuthenticatedUser user = CurrentUserContext.require();
        return Map.of("enabled", cloud.isEnabled(), "retentionDays", cloud.retentionDays(), "versions", cloud.list(user.id()));
    }

    @PostMapping("/backup")
    public Map<String,Object> backup(@RequestBody(required = false) Map<String,Object> body) throws Exception {
        AuthenticatedUser user = CurrentUserContext.require();
        ensureSessionWorkspace(user.workspaceId());
        String reason = body == null ? "auto" : String.valueOf(body.getOrDefault("reason", "auto"));
        boolean force = body != null && Boolean.TRUE.equals(body.get("forceNewVersion"));
        CloudWorkspaceBackupService.BackupInfo info = cloud.backup(user.id(), user.workspaceId(), reason, force);
        return Map.of("saved", true, "retentionDays", cloud.retentionDays(), "backup", info);
    }

    @PostMapping("/restore-latest")
    public Map<String,Object> restoreLatest() throws Exception {
        AuthenticatedUser user = CurrentUserContext.require();
        if (!storage.isSessionWorkspace(user.workspaceId())) throw new SecurityException("Lokal sessiya workspace-i tələb olunur.");
        CloudWorkspaceBackupService.BackupInfo info = cloud.restoreLatest(user.id(), user.workspaceId());
        var state = data.getState(user.workspaceId());
        companies.remigrateLegacyWorkspace(user.id(), user.workspaceId());
        int companyCount = companies.count(user.id(), user.workspaceId());
        storage.touchWorkspace(user.workspaceId());
        return Map.of("restored", true, "backup", info, "companyCount", companyCount, "companyStorage", "SQL", "invoiceCount", state.getInvoices().size(), "bytes", storage.workspaceSize(user.workspaceId()));
    }

    @GetMapping("/latest.zip")
    public ResponseEntity<byte[]> downloadLatest() {
        AuthenticatedUser user = CurrentUserContext.require();
        byte[] zip = cloud.downloadLatest(user.id());
        String file = "taxdata_" + user.username().replaceAll("[^A-Za-z0-9._-]", "_") + "_cloud_workspace.zip";
        String encoded = URLEncoder.encode(file, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-TAXDATA-Cloud-Retention-Days", Integer.toString(cloud.retentionDays()))
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zip.length)
                .body(zip);
    }

    private void ensureSessionWorkspace(String workspaceId) throws Exception {
        if (!storage.isSessionWorkspace(workspaceId) || !storage.workspaceExists(workspaceId))
            throw new IllegalStateException("Cloud backup üçün hazır lokal sessiya workspace-i yoxdur.");
        if (!Files.isRegularFile(storage.getWorkspaceDir(workspaceId).resolve("workspace-state.json")))
            throw new IllegalStateException("Cloud backup üçün workspace hələ inicializasiya olunmayıb.");
        storage.touchWorkspace(workspaceId);
    }
}
