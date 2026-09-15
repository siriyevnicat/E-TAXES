package az.gmb.taxdata.service;

import az.gmb.taxdata.model.CompanyImportResult;
import az.gmb.taxdata.model.CompanyInfo;
import az.gmb.taxdata.model.WorkspaceState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent company requisites registry.
 *
 * Company records live in SQL, not in the temporary/local-first workspace. Every
 * query is scoped by the authenticated user id, so one account cannot read or
 * mutate another account's requisites even when a record id is guessed.
 *
 * Legacy workspace company rows are migrated once on access and then removed
 * from workspace-state.json. This also makes old browser/cloud backups upgrade
 * themselves safely after restore.
 */
@Service
public class CompanyRegistryService {
    private final JdbcTemplate jdbc;
    private final WorkspaceDataService workspace;
    private final StorageService storage;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final Set<String> migrationChecked = ConcurrentHashMap.newKeySet();

    public CompanyRegistryService(JdbcTemplate jdbc, WorkspaceDataService workspace, StorageService storage) {
        this.jdbc = jdbc;
        this.workspace = workspace;
        this.storage = storage;
    }

    public List<CompanyInfo> list(String userId, String workspaceId) throws IOException {
        requireUser(userId);
        ensureSchema();
        migrateLegacyWorkspace(userId, workspaceId);
        return jdbc.query("""
                SELECT id,role,entity_type,company,director,voen,address,bank_name,bank_code,bank_swift,
                       bank_voen,bank_account,correspondent_account,handover_no,invoice_no,price_protocol_no,
                       contract_no,contract_date
                  FROM company_registry
                 WHERE owner_user_id=?
                 ORDER BY LOWER(company), id
                """, this::map, userId);
    }

    public int count(String userId, String workspaceId) throws IOException {
        requireUser(userId);
        ensureSchema();
        migrateLegacyWorkspace(userId, workspaceId);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM company_registry WHERE owner_user_id=?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    public CompanyInfo get(String userId, String workspaceId, String id) throws IOException {
        requireUser(userId);
        ensureSchema();
        migrateLegacyWorkspace(userId, workspaceId);
        List<CompanyInfo> rows = jdbc.query("""
                SELECT id,role,entity_type,company,director,voen,address,bank_name,bank_code,bank_swift,
                       bank_voen,bank_account,correspondent_account,handover_no,invoice_no,price_protocol_no,
                       contract_no,contract_date
                  FROM company_registry WHERE owner_user_id=? AND id=?
                """, this::map, userId, id);
        return rows.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Şirkət tapılmadı: " + id));
    }

    public CompanyImportResult importCompanies(String userId, String workspaceId, Collection<CompanyInfo> incoming) throws IOException {
        requireUser(userId);
        ensureSchema();
        migrateLegacyWorkspace(userId, workspaceId);
        List<CompanyInfo> before = listWithoutMigration(userId);
        Set<String> identities = new HashSet<>();
        for (CompanyInfo c : before) identities.add(identityHash(c));

        int parsed = incoming == null ? 0 : incoming.size();
        int added = 0, skipped = 0;
        if (incoming != null) {
            for (CompanyInfo c : incoming) {
                validate(c);
                String identity = identityHash(c);
                if (identity.isBlank() || identities.contains(identity)) { skipped++; continue; }
                if (c.getId() == null || c.getId().isBlank()) c.setId("USER_" + UUID.randomUUID());
                try {
                    insert(userId, c, identity);
                    identities.add(identity);
                    added++;
                } catch (DuplicateKeyException e) {
                    skipped++;
                }
            }
        }
        return new CompanyImportResult(parsed, added, skipped, listWithoutMigration(userId));
    }

    public CompanyInfo save(String userId, String workspaceId, CompanyInfo company) throws IOException {
        requireUser(userId);
        ensureSchema();
        migrateLegacyWorkspace(userId, workspaceId);
        validate(company);
        String identity = identityHash(company);
        Instant now = Instant.now();

        if (company.getId() != null && !company.getId().isBlank()) {
            List<String> conflict = jdbc.query("""
                    SELECT id FROM company_registry
                     WHERE owner_user_id=? AND identity_hash=? AND id<>?
                    """, (rs, rowNum) -> rs.getString(1), userId, identity, company.getId());
            if (!conflict.isEmpty()) throw new IllegalArgumentException("Bu VÖEN / şirkət artıq başqa rekvizit qeydində mövcuddur.");

            int updated = jdbc.update("""
                    UPDATE company_registry SET identity_hash=?,role=?,entity_type=?,company=?,director=?,voen=?,address=?,
                           bank_name=?,bank_code=?,bank_swift=?,bank_voen=?,bank_account=?,correspondent_account=?,handover_no=?,
                           invoice_no=?,price_protocol_no=?,contract_no=?,contract_date=?,updated_at=?
                     WHERE id=? AND owner_user_id=?
                    """, argsForUpdate(identity, company, now, userId));
            if (updated > 0) return company;
        }

        company.setId("USER_" + UUID.randomUUID());
        try {
            insert(userId, company, identity);
            return company;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Bu şirkət / fiziki şəxs artıq bazada mövcuddur.");
        }
    }

    public void delete(String userId, String workspaceId, String id) throws IOException {
        requireUser(userId);
        ensureSchema();
        migrateLegacyWorkspace(userId, workspaceId);
        int removed = jdbc.update("DELETE FROM company_registry WHERE id=? AND owner_user_id=?", id, userId);
        if (removed == 0) throw new IllegalArgumentException("Şirkət tapılmadı.");
    }

    /**
     * Full-transfer restore: replace this user's SQL company registry with the portable snapshot.
     * IDs are preserved intentionally because workspace contracts/archive records reference them.
     * The primary key is (owner_user_id,id), so the same portable IDs may safely exist for another account.
     */
    @Transactional
    public synchronized int replaceAllFromTransfer(String userId, String workspaceId, Collection<CompanyInfo> incoming) throws IOException {
        requireUser(userId);
        ensureSchema();
        // Validate the whole snapshot before deleting anything so a malformed transfer cannot wipe current data.
        List<CompanyInfo> rows = incoming == null ? List.of() : new ArrayList<>(incoming);
        Set<String> ids = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (CompanyInfo c : rows) {
            validate(c);
            if (c.getId() == null || c.getId().isBlank()) c.setId("USER_" + UUID.randomUUID());
            if (!ids.add(c.getId())) throw new IllegalArgumentException("Transfer backup-da təkrar rekvizit ID-si var: " + c.getId());
            String identity = identityHash(c);
            if (identity.isBlank() || !identities.add(identity))
                throw new IllegalArgumentException("Transfer backup-da təkrar VÖEN / şirkət rekviziti var: " + c.getCompany());
        }

        jdbc.update("DELETE FROM company_registry WHERE owner_user_id=?", userId);
        for (CompanyInfo c : rows) insert(userId, c, identityHash(c));
        migrationChecked.add(userId + "|" + workspaceId);
        return rows.size();
    }

    /** Migrates and removes any companies restored from an older local workspace backup. */
    public synchronized int migrateLegacyWorkspace(String userId, String workspaceId) throws IOException {
        if (workspaceId == null || workspaceId.isBlank()) return 0;
        String migrationKey = userId + "|" + workspaceId;
        if (!migrationChecked.add(migrationKey)) return 0;
        try {
            WorkspaceState state = workspace.getState(workspaceId);
            List<CompanyInfo> legacy = new ArrayList<>(state.getCompanies());
            if (legacy.isEmpty()) {
                storage.deleteUploadsByPrefix(workspaceId, "companies-");
                return 0;
            }

            Set<String> identities = new HashSet<>();
            for (CompanyInfo c : listWithoutMigration(userId)) identities.add(identityHash(c));
            int migrated = 0;
            for (CompanyInfo c : legacy) {
                if (c == null || c.getCompany() == null || c.getCompany().isBlank()) continue;
                String identity = identityHash(c);
                if (identity.isBlank() || identities.contains(identity)) continue;
                if (c.getId() == null || c.getId().isBlank()) c.setId("USER_" + UUID.randomUUID());
                try {
                    insert(userId, c, identity);
                    identities.add(identity);
                    migrated++;
                } catch (DuplicateKeyException ignored) {
                    // Another request may have migrated the same row concurrently.
                }
            }
            state.setCompanies(new ArrayList<>());
            workspace.saveState(workspaceId, state);
            storage.deleteUploadsByPrefix(workspaceId, "companies-");
            return migrated;
        } catch (IOException | RuntimeException e) {
            migrationChecked.remove(migrationKey);
            throw e;
        }
    }

    /** Force a legacy check after a workspace ZIP/cloud restore. */
    public synchronized int remigrateLegacyWorkspace(String userId, String workspaceId) throws IOException {
        migrationChecked.remove(userId + "|" + workspaceId);
        return migrateLegacyWorkspace(userId, workspaceId);
    }

    private List<CompanyInfo> listWithoutMigration(String userId) {
        ensureSchema();
        return jdbc.query("""
                SELECT id,role,entity_type,company,director,voen,address,bank_name,bank_code,bank_swift,
                       bank_voen,bank_account,correspondent_account,handover_no,invoice_no,price_protocol_no,
                       contract_no,contract_date
                  FROM company_registry WHERE owner_user_id=? ORDER BY LOWER(company), id
                """, this::map, userId);
    }

    private void insert(String userId, CompanyInfo c, String identity) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO company_registry(
                    id,owner_user_id,identity_hash,role,entity_type,company,director,voen,address,bank_name,bank_code,
                    bank_swift,bank_voen,bank_account,correspondent_account,handover_no,invoice_no,price_protocol_no,
                    contract_no,contract_date,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                c.getId(), userId, identity, safe(c.getRole()), c.getEntityType(), c.getCompany().trim(), safe(c.getDirector()),
                safe(c.getVoen()), safe(c.getAddress()), safe(c.getBankName()), safe(c.getBankCode()), safe(c.getBankSwift()),
                safe(c.getBankVoen()), safe(c.getBankAccount()), safe(c.getCorrespondentAccount()), safe(c.getHandoverNo()),
                safe(c.getInvoiceNo()), safe(c.getPriceProtocolNo()), safe(c.getContractNo()), safe(c.getContractDate()), ts(now), ts(now));
    }

    private Object[] argsForUpdate(String identity, CompanyInfo c, Instant now, String userId) {
        return new Object[]{identity, safe(c.getRole()), c.getEntityType(), c.getCompany().trim(), safe(c.getDirector()), safe(c.getVoen()),
                safe(c.getAddress()), safe(c.getBankName()), safe(c.getBankCode()), safe(c.getBankSwift()), safe(c.getBankVoen()),
                safe(c.getBankAccount()), safe(c.getCorrespondentAccount()), safe(c.getHandoverNo()), safe(c.getInvoiceNo()),
                safe(c.getPriceProtocolNo()), safe(c.getContractNo()), safe(c.getContractDate()), ts(now), c.getId(), userId};
    }

    private CompanyInfo map(ResultSet rs, int rowNum) throws SQLException {
        CompanyInfo c = new CompanyInfo();
        c.setId(rs.getString("id"));
        c.setRole(rs.getString("role"));
        c.setEntityType(rs.getString("entity_type"));
        c.setCompany(rs.getString("company"));
        c.setDirector(rs.getString("director"));
        c.setVoen(rs.getString("voen"));
        c.setAddress(rs.getString("address"));
        c.setBankName(rs.getString("bank_name"));
        c.setBankCode(rs.getString("bank_code"));
        c.setBankSwift(rs.getString("bank_swift"));
        c.setBankVoen(rs.getString("bank_voen"));
        c.setBankAccount(rs.getString("bank_account"));
        c.setCorrespondentAccount(rs.getString("correspondent_account"));
        c.setHandoverNo(rs.getString("handover_no"));
        c.setInvoiceNo(rs.getString("invoice_no"));
        c.setPriceProtocolNo(rs.getString("price_protocol_no"));
        c.setContractNo(rs.getString("contract_no"));
        c.setContractDate(rs.getString("contract_date"));
        return c;
    }

    private void ensureSchema() {
        if (initialized.get()) return;
        synchronized (initialized) {
            if (initialized.get()) return;
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS company_registry (
                      id VARCHAR(80) NOT NULL,
                      owner_user_id VARCHAR(36) NOT NULL,
                      identity_hash VARCHAR(64) NOT NULL,
                      role VARCHAR(160),
                      entity_type VARCHAR(20) NOT NULL,
                      company VARCHAR(600) NOT NULL,
                      director VARCHAR(600),
                      voen VARCHAR(40),
                      address TEXT,
                      bank_name VARCHAR(600),
                      bank_code VARCHAR(120),
                      bank_swift VARCHAR(120),
                      bank_voen VARCHAR(40),
                      bank_account VARCHAR(200),
                      correspondent_account VARCHAR(200),
                      handover_no VARCHAR(160),
                      invoice_no VARCHAR(160),
                      price_protocol_no VARCHAR(160),
                      contract_no VARCHAR(160),
                      contract_date VARCHAR(80),
                      created_at TIMESTAMP NOT NULL,
                      updated_at TIMESTAMP NOT NULL,
                      CONSTRAINT pk_company_registry PRIMARY KEY(owner_user_id, id),
                      CONSTRAINT fk_company_registry_owner FOREIGN KEY(owner_user_id) REFERENCES app_users(id) ON DELETE CASCADE,
                      CONSTRAINT uq_company_registry_owner_identity UNIQUE(owner_user_id, identity_hash)
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_company_registry_owner_name ON company_registry(owner_user_id, company)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_company_registry_owner_voen ON company_registry(owner_user_id, voen)");
            initialized.set(true);
        }
    }

    private void validate(CompanyInfo c) {
        if (c == null || c.getCompany() == null || c.getCompany().isBlank())
            throw new IllegalArgumentException("Şirkət / fiziki şəxs adı boş ola bilməz.");
        if (c.getCompany().length() > 600) throw new IllegalArgumentException("Şirkət adı çox uzundur.");
        if (safe(c.getVoen()).length() > 40) throw new IllegalArgumentException("VÖEN formatı düzgün deyil.");
    }

    private String identityHash(CompanyInfo c) {
        if (c == null) return "";
        String voen = safe(c.getVoen()).replaceAll("\\D", "");
        String raw = !voen.isBlank() ? "voen:" + voen : "name:" + normalizeKey(c.getCompany());
        if (raw.endsWith(":")) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Şirkət identifikatoru hesablana bilmədi.", e);
        }
    }

    private String normalizeKey(String s) {
        return safe(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9əöüğışç]", "");
    }
    private void requireUser(String userId) { if (userId == null || userId.isBlank()) throw new SecurityException("İstifadəçi identifikatoru tələb olunur."); }
    private Timestamp ts(Instant x) { return Timestamp.from(x); }
    private String safe(String s) { return s == null ? "" : s.trim(); }
}
