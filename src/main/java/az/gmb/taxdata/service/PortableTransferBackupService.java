package az.gmb.taxdata.service;

import az.gmb.taxdata.auth.AuthenticatedUser;
import az.gmb.taxdata.model.CompanyInfo;
import az.gmb.taxdata.model.WorkspaceState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Password-encrypted, portable user transfer backup.
 *
 * Contents:
 *  - complete current local-first workspace ZIP (invoices, contracts, generated docs, EFP and workspace settings)
 *  - the current user's SQL company registry snapshot
 *  - a read-only SQL account/profile + section-access snapshot for audit/visibility
 *  - current standard application templates so the transfer package is self-describing
 *  - browser-safe preferences supplied by the client (currently active page)
 *
 * Security-sensitive server configuration, password hashes, auth sessions, device hashes,
 * agent grants, cloud-backup encryption keys and database credentials are deliberately never exported.
 * Admin-controlled account/security state is never restored from a user-provided transfer file.
 */
@Service
public class PortableTransferBackupService {
    private static final byte[] MAGIC = new byte[]{'T','D','T','B','1'};
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int DEFAULT_PBKDF2_ITERATIONS = 210_000;
    private static final int MAX_ENTRIES = 80;
    private static final String FORMAT = "TAXDATA_PORTABLE_TRANSFER";
    private static final int FORMAT_VERSION = 1;

    private static final List<String> TEMPLATE_RESOURCES = List.of(
            "excel-templates/hesab_faktura_faktiki.xlsx",
            "excel-templates/qiymet_razilasma_faktiki.xlsx",
            "excel-templates/qiymet_razilasma_fiziki.xlsx",
            "excel-templates/tehvil_teslim_faktiki.xlsx",
            "excel-templates/tehvil_teslim_fiziki.xlsx",
            "excel-templates/efp_qaime_paketleme_sablonu.xlsx",
            "contract-templates/muqavile_faktiki.docx"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WorkspaceArchiveService archive;
    private final WorkspaceDataService workspaceData;
    private final StorageService storage;
    private final CompanyRegistryService companies;
    private final SecureRandom random = new SecureRandom();
    private final long maxEncryptedBytes;
    private final long maxInnerBytes;
    private final int pbkdf2Iterations;

    public PortableTransferBackupService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            WorkspaceArchiveService archive,
            WorkspaceDataService workspaceData,
            StorageService storage,
            CompanyRegistryService companies,
            @Value("${app.transfer-backup.max-encrypted-bytes:314572800}") long maxEncryptedBytes,
            @Value("${app.transfer-backup.max-inner-bytes:419430400}") long maxInnerBytes,
            @Value("${app.transfer-backup.pbkdf2-iterations:210000}") int pbkdf2Iterations) {
        this.jdbc = jdbc;
        this.json = json;
        this.archive = archive;
        this.workspaceData = workspaceData;
        this.storage = storage;
        this.companies = companies;
        this.maxEncryptedBytes = Math.max(16 * 1024 * 1024L, maxEncryptedBytes);
        this.maxInnerBytes = Math.max(32 * 1024 * 1024L, maxInnerBytes);
        this.pbkdf2Iterations = Math.max(DEFAULT_PBKDF2_ITERATIONS, pbkdf2Iterations);
    }

    public byte[] exportTransfer(AuthenticatedUser user, Map<String, Object> browserSettings, char[] password) throws Exception {
        requireUser(user);
        validatePassword(password);
        ensureWorkspace(user.workspaceId());

        byte[] workspaceZip = archive.exportWorkspace(user.workspaceId());
        List<CompanyInfo> companyRows = companies.list(user.id(), user.workspaceId());
        Map<String,Object> accountSnapshot = accountSnapshot(user.id());
        Map<String,Object> accessSnapshot = sectionAccessSnapshot(user.id());
        Map<String,Object> safeBrowserSettings = sanitizeBrowserSettings(browserSettings);

        byte[] inner = buildInnerZip(user, workspaceZip, companyRows, accountSnapshot, accessSnapshot, safeBrowserSettings);
        Arrays.fill(workspaceZip, (byte) 0);
        if (inner.length > maxInnerBytes) {
            Arrays.fill(inner, (byte) 0);
            throw new IllegalArgumentException("Tam transfer backup çox böyükdür. Limit: " + maxInnerBytes + " bayt.");
        }
        try {
            byte[] encrypted = encrypt(inner, password);
            if (encrypted.length > maxEncryptedBytes) {
                Arrays.fill(encrypted, (byte) 0);
                throw new IllegalArgumentException("Şifrələnmiş transfer backup çox böyükdür. Limit: " + maxEncryptedBytes + " bayt.");
            }
            return encrypted;
        } finally {
            Arrays.fill(inner, (byte) 0);
            Arrays.fill(password, '\0');
        }
    }

    public RestoreResult restoreTransfer(AuthenticatedUser user, byte[] encryptedFile, char[] password) throws Exception {
        requireUser(user);
        validatePassword(password);
        if (encryptedFile == null || encryptedFile.length == 0) throw new IllegalArgumentException("Transfer backup faylı boşdur.");
        if (encryptedFile.length > maxEncryptedBytes) throw new IllegalArgumentException("Transfer backup faylı icazə verilən ölçüdən böyükdür.");

        byte[] inner = null;
        byte[] previousWorkspace = null;
        List<CompanyInfo> previousCompanies = List.of();
        boolean hadWorkspace = false;
        try {
            inner = decrypt(encryptedFile, password);
            if (inner.length > maxInnerBytes) throw new IllegalArgumentException("Transfer backup açıldıqda ölçü limiti keçir.");
            Map<String, byte[]> entries = readInnerZip(inner);

            Map<String,Object> manifest = readJsonMap(required(entries, "manifest.json"));
            validateManifest(manifest);
            byte[] workspaceZip = required(entries, "workspace/workspace.zip");
            String expectedWorkspaceHash = str(manifest.get("workspaceSha256"));
            if (expectedWorkspaceHash.isBlank() || !expectedWorkspaceHash.equalsIgnoreCase(sha256(workspaceZip)))
                throw new IllegalArgumentException("Transfer backup workspace yoxlama cəmi uyğun gəlmir; fayl zədələnib.");
            List<CompanyInfo> companyRows = json.readValue(required(entries, "postgres/company_registry.json"), new TypeReference<List<CompanyInfo>>(){});
            Map<String,String> companyIdMap = rekeyCompanies(companyRows);
            Map<String,Object> browserSettings = entries.containsKey("settings/browser-settings.json")
                    ? sanitizeBrowserSettings(readJsonMap(entries.get("settings/browser-settings.json"))) : Map.of();

            // Keep a rollback copy. Full transfer must be all-or-nothing from the user's perspective.
            hadWorkspace = storage.workspaceExists(user.workspaceId()) && workspaceDataFileExists(user.workspaceId());
            if (hadWorkspace) previousWorkspace = archive.exportWorkspace(user.workspaceId());
            previousCompanies = companies.list(user.id(), user.workspaceId());

            try {
                storage.deleteWorkspace(user.workspaceId());
                try (ByteArrayInputStream in = new ByteArrayInputStream(workspaceZip)) {
                    archive.importWorkspace(user.workspaceId(), in);
                }
                WorkspaceState importedState = workspaceData.getState(user.workspaceId());
                remapWorkspaceCompanyIds(importedState, companyIdMap);
                // Company registry is SQL-authoritative. Never allow an old workspace copy to override SQL.
                importedState.setCompanies(new ArrayList<>());
                // Standard templates are application resources. Old per-machine template pointers are intentionally cleared.
                importedState.setActiveTemplates(new LinkedHashMap<>());
                importedState.setTemplateMappings(new LinkedHashMap<>());
                importedState.setContractTemplateUploadId("");
                workspaceData.saveState(user.workspaceId(), importedState);

                int companyCount = companies.replaceAllFromTransfer(user.id(), user.workspaceId(), companyRows);
                storage.deleteUploadsByPrefix(user.workspaceId(), "companies-");
                storage.touchWorkspace(user.workspaceId());

                String sourceUserId = str(manifest.get("sourceUserId"));
                String sourceUsername = str(manifest.get("sourceUsername"));
                boolean sameAccount = user.id().equals(sourceUserId);
                return new RestoreResult(
                        true,
                        sourceUsername,
                        sameAccount,
                        companyCount,
                        importedState.getInvoices().size(),
                        storage.workspaceSize(user.workspaceId()),
                        browserSettings,
                        "SQL rekvizitləri və lokal workspace bərpa edildi. Cari server hesab profili və təhlükəsizlik icazələri serverdəki son vəziyyətdə saxlanıldı."
                );
            } catch (Exception restoreError) {
                rollback(user, hadWorkspace, previousWorkspace, previousCompanies, restoreError);
                throw restoreError;
            }
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IllegalArgumentException("Transfer backup parolu yanlışdır və ya fayl dəyişdirilib/zədələnib.");
        } finally {
            if (inner != null) Arrays.fill(inner, (byte) 0);
            if (previousWorkspace != null) Arrays.fill(previousWorkspace, (byte) 0);
            Arrays.fill(password, '\0');
        }
    }

    private byte[] buildInnerZip(AuthenticatedUser user,
                                 byte[] workspaceZip,
                                 List<CompanyInfo> companyRows,
                                 Map<String,Object> accountSnapshot,
                                 Map<String,Object> accessSnapshot,
                                 Map<String,Object> browserSettings) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64 * 1024, workspaceZip.length + 64 * 1024));
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(bytes))) {
            zip.setLevel(Deflater.BEST_SPEED);
            Map<String,Object> manifest = new LinkedHashMap<>();
            manifest.put("format", FORMAT);
            manifest.put("formatVersion", FORMAT_VERSION);
            manifest.put("appVersion", "7.1.14");
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("sourceUserId", user.id());
            manifest.put("sourceUsername", user.username());
            manifest.put("companyCount", companyRows.size());
            manifest.put("workspaceSha256", sha256(workspaceZip));
            manifest.put("templateMode", "STANDARD_EMBEDDED_ONLY");
            manifest.put("securityExcluded", List.of(
                    "database credentials", "password hashes", "auth sessions", "device hashes",
                    "agent grants", "cloud backup ciphertext/keys", "server-global settings"));
            manifest.put("restorePolicy", "Business/workspace data restore; account role/access/profile remain server-authoritative");

            putJson(zip, "manifest.json", manifest);
            putBytes(zip, "workspace/workspace.zip", workspaceZip);
            putJson(zip, "postgres/company_registry.json", companyRows);
            putJson(zip, "postgres/account_profile_snapshot.json", accountSnapshot);
            putJson(zip, "postgres/section_access_snapshot.json", accessSnapshot);
            putJson(zip, "settings/browser-settings.json", browserSettings);

            for (String resource : TEMPLATE_RESOURCES) {
                ClassPathResource cp = new ClassPathResource(resource);
                if (!cp.exists()) continue;
                try (InputStream in = cp.getInputStream()) {
                    putBytes(zip, "templates/" + resource, in.readAllBytes());
                }
            }
            putBytes(zip, "README_AZ.txt", ("""
                    TaxData Tam Transfer Backup\n\n
                    Bu paket parolla AES-256-GCM ilə qorunur.\n
                    Bərpa olunanlar: lokal workspace, qaimələr, müqavilələr, yaradılmış sənədlər, EFP faylları,\n
                    workspace ayarları və istifadəçiyə aid SQL rekvizit bazası. Standart şablonların nüsxəsi paketdədir.\n\n
                    Təhlükəsizlik səbəbilə DB parolu, password hash, sessiya/token, cihaz hash-i və admin icazələri daşınmır.\n
                    Yeni kompüterdə cari TaxData proqramı və server bağlantısı olmalıdır; bərpa cari giriş etmiş hesaba tətbiq edilir.\n
                    """).getBytes(StandardCharsets.UTF_8));
            zip.finish();
        }
        return bytes.toByteArray();
    }

    private void rollback(AuthenticatedUser user, boolean hadWorkspace, byte[] previousWorkspace,
                          List<CompanyInfo> previousCompanies, Exception original) {
        try {
            storage.deleteWorkspace(user.workspaceId());
            if (hadWorkspace && previousWorkspace != null) {
                try (ByteArrayInputStream in = new ByteArrayInputStream(previousWorkspace)) {
                    archive.importWorkspace(user.workspaceId(), in);
                }
            }
            companies.replaceAllFromTransfer(user.id(), user.workspaceId(), previousCompanies);
        } catch (Exception rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }


    private Map<String,String> rekeyCompanies(List<CompanyInfo> rows) {
        Map<String,String> mapping = new LinkedHashMap<>();
        if (rows == null) return mapping;
        for (CompanyInfo c : rows) {
            if (c == null) continue;
            String oldId = str(c.getId());
            String newId = "USER_" + UUID.randomUUID();
            if (!oldId.isBlank()) mapping.put(oldId, newId);
            c.setId(newId);
        }
        return mapping;
    }

    private void remapWorkspaceCompanyIds(WorkspaceState state, Map<String,String> mapping) {
        if (state == null || mapping == null || mapping.isEmpty()) return;
        state.getContracts().forEach(r -> {
            r.setExecutorCompanyId(mapping.getOrDefault(str(r.getExecutorCompanyId()), r.getExecutorCompanyId()));
            r.setCustomerCompanyId(mapping.getOrDefault(str(r.getCustomerCompanyId()), r.getCustomerCompanyId()));
        });
        state.getGeneratedDocuments().forEach(r -> {
            r.setExecutorCompanyId(mapping.getOrDefault(str(r.getExecutorCompanyId()), r.getExecutorCompanyId()));
            r.setOrdererCompanyId(mapping.getOrDefault(str(r.getOrdererCompanyId()), r.getOrdererCompanyId()));
        });
    }

    private Map<String,Object> accountSnapshot(String userId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                SELECT id,username,whatsapp,user_role,user_status,workspace_id,
                       etaxes_phone,etaxes_user_id,etaxes_tin,profile_updated_at,
                       access_start_at,access_end_at,created_at,approved_at,last_login_at
                  FROM app_users WHERE id=?
                """, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("İstifadəçi profili tapılmadı.");
        Map<String,Object> out = normalizeSqlMap(rows.get(0));
        out.put("restoreMode", "READ_ONLY_SNAPSHOT_SERVER_AUTHORITATIVE");
        return out;
    }

    private Map<String,Object> sectionAccessSnapshot(String userId) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("permissions", normalizeSqlRows(jdbc.queryForList(
                "SELECT section_key,granted_at FROM user_section_permissions WHERE user_id=? ORDER BY section_key", userId)));
        out.put("requests", normalizeSqlRows(jdbc.queryForList(
                "SELECT section_key,request_status,requested_at,decided_at FROM user_section_requests WHERE user_id=? ORDER BY section_key", userId)));
        out.put("restoreMode", "NOT_RESTORED_SECURITY_STATE");
        return out;
    }

    private List<Map<String,Object>> normalizeSqlRows(List<Map<String,Object>> rows) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> row : rows) out.add(normalizeSqlMap(row));
        return out;
    }

    private Map<String,Object> normalizeSqlMap(Map<String,Object> row) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<String,Object> e : row.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Timestamp t) v = t.toInstant().toString();
            else if (v instanceof java.util.Date d) v = d.toInstant().toString();
            out.put(e.getKey(), v);
        }
        return out;
    }

    private Map<String,Object> sanitizeBrowserSettings(Map<String,Object> raw) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        String page = str(raw.get("activePage"));
        if (page.matches("[A-Za-z0-9._-]{1,40}")) out.put("activePage", page);
        return out;
    }

    private byte[] encrypt(byte[] plaintext, char[] password) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        SecretKeySpec key = derive(password, salt, pbkdf2Iterations);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(MAGIC);
        byte[] encrypted = cipher.doFinal(plaintext);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(encrypted.length + 64);
             DataOutputStream data = new DataOutputStream(out)) {
            data.write(MAGIC);
            data.writeInt(pbkdf2Iterations);
            data.writeByte(salt.length);
            data.write(salt);
            data.writeByte(nonce.length);
            data.write(nonce);
            data.writeInt(encrypted.length);
            data.write(encrypted);
            data.flush();
            Arrays.fill(encrypted, (byte) 0);
            return out.toByteArray();
        } finally {
            Arrays.fill(key.getEncoded(), (byte) 0);
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    private byte[] decrypt(byte[] file, char[] password) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(file))) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("Bu fayl TaxData Tam Transfer Backup formatında deyil.");
            int iterations = in.readInt();
            if (iterations < DEFAULT_PBKDF2_ITERATIONS || iterations > 2_000_000) throw new IllegalArgumentException("Transfer backup KDF parametri etibarsızdır.");
            int saltLen = in.readUnsignedByte();
            if (saltLen != SALT_BYTES) throw new IllegalArgumentException("Transfer backup salt ölçüsü etibarsızdır.");
            byte[] salt = in.readNBytes(saltLen);
            int nonceLen = in.readUnsignedByte();
            if (nonceLen != NONCE_BYTES) throw new IllegalArgumentException("Transfer backup nonce ölçüsü etibarsızdır.");
            byte[] nonce = in.readNBytes(nonceLen);
            int encryptedLen = in.readInt();
            if (encryptedLen <= 16 || encryptedLen > maxEncryptedBytes || encryptedLen != in.available())
                throw new IllegalArgumentException("Transfer backup ölçüsü/strukturu etibarsızdır.");
            byte[] encrypted = in.readNBytes(encryptedLen);
            SecretKeySpec key = derive(password, salt, iterations);
            try {
                Cipher cipher = Cipher.getInstance(CIPHER);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
                cipher.updateAAD(MAGIC);
                return cipher.doFinal(encrypted);
            } finally {
                Arrays.fill(key.getEncoded(), (byte) 0);
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(nonce, (byte) 0);
                Arrays.fill(encrypted, (byte) 0);
            }
        }
    }

    private SecretKeySpec derive(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try { return new SecretKeySpec(key, "AES"); }
            finally { Arrays.fill(key, (byte) 0); }
        } finally {
            spec.clearPassword();
        }
    }

    private Map<String,byte[]> readInnerZip(byte[] zipBytes) throws IOException {
        Map<String,byte[]> out = new LinkedHashMap<>();
        long total = 0L;
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new ByteArrayInputStream(zipBytes)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++count > MAX_ENTRIES) throw new IllegalArgumentException("Transfer backup-da həddindən artıq çox fayl var.");
                String name = entry.getName().replace('\\','/');
                if (name.startsWith("/") || name.contains("../") || name.contains("\\"))
                    throw new IllegalArgumentException("Transfer backup-da təhlükəli fayl yolu var.");
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = zin.read(buffer)) != -1) {
                    total += n;
                    if (total > maxInnerBytes) throw new IllegalArgumentException("Transfer backup açıldıqda ölçü limiti keçir.");
                    b.write(buffer, 0, n);
                }
                if (out.containsKey(name)) throw new IllegalArgumentException("Transfer backup-da təkrar fayl adı var: " + name);
                out.put(name, b.toByteArray());
            }
        }
        return out;
    }

    private void validateManifest(Map<String,Object> m) {
        if (!FORMAT.equals(str(m.get("format")))) throw new IllegalArgumentException("Transfer backup manifest formatı düzgün deyil.");
        int version;
        try { version = Integer.parseInt(str(m.get("formatVersion"))); }
        catch (Exception e) { throw new IllegalArgumentException("Transfer backup versiyası oxunmadı."); }
        if (version != FORMAT_VERSION) throw new IllegalArgumentException("Bu transfer backup versiyası dəstəklənmir: " + version);
    }

    private void ensureWorkspace(String workspaceId) throws IOException {
        WorkspaceState state = workspaceData.getState(workspaceId);
        if (state == null || !workspaceDataFileExists(workspaceId))
            throw new IllegalStateException("Tam transfer backup üçün lokal workspace hazır deyil.");
        storage.touchWorkspace(workspaceId);
    }

    private boolean workspaceDataFileExists(String workspaceId) {
        try { return java.nio.file.Files.isRegularFile(storage.getWorkspaceDir(workspaceId).resolve("workspace-state.json")); }
        catch (Exception e) { return false; }
    }

    private void requireUser(AuthenticatedUser user) {
        if (user == null || user.id() == null || user.id().isBlank()) throw new SecurityException("Aktiv istifadəçi tələb olunur.");
        if (!storage.isSessionWorkspace(user.workspaceId())) throw new SecurityException("Tam transfer üçün lokal sessiya workspace-i tələb olunur.");
    }

    private void validatePassword(char[] password) {
        if (password == null || password.length < 10) throw new IllegalArgumentException("Transfer backup parolu ən azı 10 simvol olmalıdır.");
        if (password.length > 200) throw new IllegalArgumentException("Transfer backup parolu çox uzundur.");
    }

    private Map<String,Object> readJsonMap(byte[] bytes) throws IOException {
        return json.readValue(bytes, new TypeReference<Map<String,Object>>(){});
    }

    private byte[] required(Map<String,byte[]> entries, String name) {
        byte[] b = entries.get(name);
        if (b == null || b.length == 0) throw new IllegalArgumentException("Transfer backup natamamdır: " + name + " yoxdur.");
        return b;
    }

    private void putJson(ZipOutputStream zip, String name, Object value) throws IOException {
        putBytes(zip, name, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    private void putBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String str(Object x) { return x == null ? "" : String.valueOf(x).trim(); }

    public record RestoreResult(
            boolean restored,
            String sourceUsername,
            boolean sameAccount,
            int companyCount,
            int invoiceCount,
            long workspaceBytes,
            Map<String,Object> browserSettings,
            String message) {}
}
