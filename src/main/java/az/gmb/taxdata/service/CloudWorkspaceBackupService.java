package az.gmb.taxdata.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Short-retention disaster-recovery backup for the local-first workspace.
 *
 * The browser/local folder remain the primary copy. This service stores an
 * application-level AES-256-GCM encrypted copy of the workspace ZIP in the
 * PostgreSQL database for a short, configurable retention period.
 */
@Service
public class CloudWorkspaceBackupService {
    private static final Logger log = LoggerFactory.getLogger(CloudWorkspaceBackupService.class);
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final JdbcTemplate jdbc;
    private final WorkspaceArchiveService archive;
    private final boolean configuredEnabled;
    private volatile boolean operational;
    private volatile String initializationError = "";
    private final int retentionDays;
    private final int maxVersionsPerUser;
    private final long maxZipBytes;
    private final Duration autoReplaceWindow;
    private volatile byte[] key = new byte[0];
    private final SupabaseVaultKeyService vaultKeyService;
    private final SecureRandom random = new SecureRandom();

    public CloudWorkspaceBackupService(
            JdbcTemplate jdbc,
            WorkspaceArchiveService archive,
            @Value("${app.cloud-backup.enabled:false}") boolean enabled,
            @Value("${app.cloud-backup.retention-days:3}") int retentionDays,
            @Value("${app.cloud-backup.max-versions-per-user:96}") int maxVersionsPerUser,
            @Value("${app.cloud-backup.max-zip-bytes:104857600}") long maxZipBytes,
            @Value("${app.cloud-backup.auto-replace-minutes:60}") int autoReplaceMinutes,
            SupabaseVaultKeyService vaultKeyService) {
        this.jdbc = jdbc;
        this.archive = archive;
        this.vaultKeyService = vaultKeyService;
        this.configuredEnabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
        this.maxVersionsPerUser = Math.max(3, maxVersionsPerUser);
        this.maxZipBytes = Math.max(1024 * 1024L, maxZipBytes);
        this.autoReplaceWindow = Duration.ofMinutes(Math.max(5, autoReplaceMinutes));
    }

    @PostConstruct
    public void init() {
        if (!configuredEnabled) return;
        try {
            this.key = vaultKeyService.loadOrCreateAes256Key();
            jdbc.execute("""
            CREATE TABLE IF NOT EXISTS workspace_cloud_backups (
              id VARCHAR(36) PRIMARY KEY,
              user_id VARCHAR(36) NOT NULL,
              created_at TIMESTAMP NOT NULL,
              updated_at TIMESTAMP NOT NULL,
              expires_at TIMESTAMP NOT NULL,
              reason VARCHAR(80),
              zip_size_bytes BIGINT NOT NULL,
              encrypted_size_bytes BIGINT NOT NULL,
              sha256 VARCHAR(64) NOT NULL,
              nonce BYTEA NOT NULL,
              encrypted_zip BYTEA NOT NULL
            )
        """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_workspace_cloud_backups_user_created ON workspace_cloud_backups(user_id, created_at DESC)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_workspace_cloud_backups_expiry ON workspace_cloud_backups(expires_at)");
            this.operational = true;
            cleanupExpired();
            log.info("Cloud backup aktivdir. AES master key Supabase Vault secret '{}' vasitəsilə idarə olunur.", vaultKeyService.secretName());
        } catch (Exception e) {
            this.operational = false;
            this.initializationError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Cloud backup inicializasiya edilə bilmədi; əsas tətbiq işləməyə davam edəcək. Səbəb: {}", initializationError, e);
        }
    }

    public boolean isEnabled() { return configuredEnabled && operational; }
    public String initializationError() { return initializationError; }
    public int retentionDays() { return retentionDays; }

    public BackupInfo backup(String userId, String workspaceId, String reason, boolean forceNewVersion) throws IOException {
        requireEnabled();
        byte[] zip = archive.exportWorkspace(workspaceId);
        if (!containsWorkspaceState(zip)) {
            Arrays.fill(zip, (byte) 0);
            throw new IllegalStateException("Cloud backup yaradılmadı: workspace hələ tam inicializasiya olunmayıb.");
        }
        if (zip.length > maxZipBytes) {
            throw new IllegalArgumentException("Workspace cloud backup üçün çox böyükdür: " + zip.length + " bayt. Limit: " + maxZipBytes + " bayt.");
        }
        String sha = sha256(zip);
        Instant now = Instant.now();
        Instant expires = now.plus(Duration.ofDays(retentionDays));

        Map<String,Object> latest = latestRow(userId).orElse(null);
        if (latest != null && sha.equals(String.valueOf(latest.get("sha256")))) {
            String id = String.valueOf(latest.get("id"));
            jdbc.update("UPDATE workspace_cloud_backups SET updated_at=?, expires_at=?, reason=? WHERE id=?",
                    ts(now), ts(expires), trimReason(reason), id);
            Arrays.fill(zip, (byte) 0);
            cleanupForUser(userId);
            return infoById(id).orElseThrow();
        }

        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] encrypted = encrypt(zip, nonce);

        boolean replaceLatest = !forceNewVersion && latest != null && isAutoReason(reason)
                && isAutoReason(String.valueOf(latest.getOrDefault("reason", "")))
                && Duration.between(instant(latest.get("created_at")), now).compareTo(autoReplaceWindow) < 0;

        String id;
        if (replaceLatest) {
            id = String.valueOf(latest.get("id"));
            jdbc.update("""
                UPDATE workspace_cloud_backups
                   SET updated_at=?, expires_at=?, reason=?, zip_size_bytes=?, encrypted_size_bytes=?, sha256=?, nonce=?, encrypted_zip=?
                 WHERE id=? AND user_id=?
                """, ts(now), ts(expires), trimReason(reason), (long) zip.length, (long) encrypted.length,
                    sha, nonce, encrypted, id, userId);
        } else {
            id = UUID.randomUUID().toString();
            jdbc.update("""
                INSERT INTO workspace_cloud_backups
                  (id,user_id,created_at,updated_at,expires_at,reason,zip_size_bytes,encrypted_size_bytes,sha256,nonce,encrypted_zip)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, id, userId, ts(now), ts(now), ts(expires), trimReason(reason),
                    (long) zip.length, (long) encrypted.length, sha, nonce, encrypted);
        }

        Arrays.fill(zip, (byte) 0);
        cleanupForUser(userId);
        return infoById(id).orElseThrow();
    }

    public Optional<BackupInfo> latestInfo(String userId) {
        if (!isEnabled()) return Optional.empty();
        return latestRow(userId).map(this::toInfo);
    }

    public List<BackupInfo> list(String userId) {
        if (!isEnabled()) return List.of();
        return jdbc.queryForList("""
                SELECT id,created_at,updated_at,expires_at,reason,zip_size_bytes,encrypted_size_bytes,sha256
                  FROM workspace_cloud_backups
                 WHERE user_id=? AND expires_at>?
                 ORDER BY created_at DESC
                """, userId, ts(Instant.now())).stream().map(this::toInfo).toList();
    }

    public byte[] downloadLatest(String userId) {
        requireEnabled();
        Map<String,Object> row = latestRowWithPayload(userId)
                .orElseThrow(() -> new IllegalArgumentException("Bu istifadəçi üçün aktiv cloud backup tapılmadı."));
        return decryptRow(row);
    }

    public BackupInfo restoreLatest(String userId, String workspaceId) throws IOException {
        byte[] zip = downloadLatest(userId);
        try (ByteArrayInputStream in = new ByteArrayInputStream(zip)) {
            archive.importWorkspace(workspaceId, in);
        } finally {
            Arrays.fill(zip, (byte) 0);
        }
        return latestInfo(userId).orElseThrow();
    }

    public void deleteAllForUser(String userId) {
        if (!isEnabled()) return;
        jdbc.update("DELETE FROM workspace_cloud_backups WHERE user_id=?", userId);
    }

    @Scheduled(fixedDelayString = "${app.cloud-backup.cleanup-ms:3600000}")
    public void cleanupExpired() {
        if (!isEnabled()) return;
        jdbc.update("DELETE FROM workspace_cloud_backups WHERE expires_at<=?", ts(Instant.now()));
    }

    private void cleanupForUser(String userId) {
        cleanupExpired();
        List<String> ids = jdbc.query("""
                SELECT id FROM workspace_cloud_backups
                 WHERE user_id=?
                 ORDER BY created_at DESC
                 OFFSET ?
                """, (rs, rowNum) -> rs.getString(1), userId, maxVersionsPerUser);
        if (!ids.isEmpty()) {
            jdbc.batchUpdate("DELETE FROM workspace_cloud_backups WHERE id=? AND user_id=?",
                    ids, ids.size(), (ps, id) -> { ps.setString(1, id); ps.setString(2, userId); });
        }
    }

    private Optional<Map<String,Object>> latestRow(String userId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT id,created_at,updated_at,expires_at,reason,zip_size_bytes,encrypted_size_bytes,sha256
                  FROM workspace_cloud_backups
                 WHERE user_id=? AND expires_at>?
                 ORDER BY created_at DESC
                 LIMIT 1
                """, userId, ts(Instant.now()));
        return rows.stream().findFirst();
    }

    private Optional<Map<String,Object>> latestRowWithPayload(String userId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT * FROM workspace_cloud_backups
                 WHERE user_id=? AND expires_at>?
                 ORDER BY created_at DESC
                 LIMIT 1
                """, userId, ts(Instant.now()));
        return rows.stream().findFirst();
    }

    private Optional<BackupInfo> infoById(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT id,created_at,updated_at,expires_at,reason,zip_size_bytes,encrypted_size_bytes,sha256
                  FROM workspace_cloud_backups WHERE id=?
                """, id);
        return rows.stream().findFirst().map(this::toInfo);
    }

    private BackupInfo toInfo(Map<String,Object> row) {
        return new BackupInfo(
                String.valueOf(row.get("id")),
                instant(row.get("created_at")),
                instant(row.get("updated_at")),
                instant(row.get("expires_at")),
                String.valueOf(row.getOrDefault("reason", "")),
                longValue(row.get("zip_size_bytes")),
                longValue(row.get("encrypted_size_bytes")),
                String.valueOf(row.get("sha256"))
        );
    }

    private byte[] decryptRow(Map<String,Object> row) {
        byte[] nonce = (byte[]) row.get("nonce");
        byte[] encrypted = (byte[]) row.get("encrypted_zip");
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] zip = cipher.doFinal(encrypted);
            String expected = String.valueOf(row.get("sha256"));
            String actual = sha256(zip);
            if (!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII), actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                Arrays.fill(zip, (byte) 0);
                throw new IllegalStateException("Cloud backup bütövlük yoxlamasından keçmədi.");
            }
            return zip;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cloud backup açıla bilmədi.", e);
        }
    }

    private byte[] encrypt(byte[] plain, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return cipher.doFinal(plain);
        } catch (Exception e) {
            throw new IllegalStateException("Cloud backup şifrələnə bilmədi.", e);
        }
    }

    private boolean containsWorkspaceState(byte[] zip) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.isDirectory() && "workspace-state.json".equals(e.getName().replace('\\','/'))) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private void requireEnabled() {
        if (!configuredEnabled) throw new IllegalStateException("Cloud backup bu mühitdə aktiv deyil.");
        if (!operational) {
            String detail = initializationError == null || initializationError.isBlank() ? "Supabase Vault inicializasiya edilməyib." : initializationError;
            throw new IllegalStateException("Cloud backup hazır deyil: " + detail);
        }
    }
    private boolean isAutoReason(String reason) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        return r.isBlank() || r.contains("auto") || r.contains("mutation") || r.contains("dəyişiklik")
                || r.contains("pagehide") || r.contains("login-sync") || r.startsWith("/api/");
    }
    private String trimReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        return r.length() > 80 ? r.substring(0,80) : r;
    }
    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { return 0L; }
    }
    private Instant instant(Object value) {
        if (value == null) return Instant.EPOCH;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.util.Date d) return d.toInstant();
        if (value instanceof Instant i) return i;
        try { return Instant.parse(String.valueOf(value)); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(String.valueOf(value)).toInstant(); } catch (Exception ignored) {}
        return Instant.EPOCH;
    }
    private Timestamp ts(Instant value) { return Timestamp.from(value); }

    public record BackupInfo(String id, Instant createdAt, Instant updatedAt, Instant expiresAt,
                             String reason, long zipSizeBytes, long encryptedSizeBytes, String sha256) {}
}
