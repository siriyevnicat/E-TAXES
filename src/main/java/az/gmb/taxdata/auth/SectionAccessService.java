package az.gmb.taxdata.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class SectionAccessService {
    public static final String REQUEST_PENDING = "PENDING";
    public static final String REQUEST_APPROVED = "APPROVED";
    public static final String REQUEST_REJECTED = "REJECTED";
    public static final String SETTING_AUTO_APPROVE_REGISTRATION = "auto_approve_registration";
    private static final String SETTING_PERMISSIONS_INITIALIZED = "section_permissions_initialized_v1";

    public record SectionDef(String key, String label) {}

    private static final List<SectionDef> SECTIONS = List.of(
            new SectionDef("01", "01 Rekvizitlər"),
            new SectionDef("02", "02 Qaimə registri"),
            new SectionDef("03", "03 e-Taxes"),
            new SectionDef("04", "04 Alt sənədlər"),
            new SectionDef("05.01", "05.01 Sənəd şablonları"),
            new SectionDef("05.02", "05.02 EFP Qaimə Paketləmə Şablonu"),
            new SectionDef("06", "06 EFP Qaimə Paketləmə"),
            new SectionDef("07.01", "07.01 Backup və lokal yaddaş"),
            new SectionDef("07.02", "07.02 Təlim mərkəzi"),
            new SectionDef("07.03", "07.03 Parolu dəyiş")
    );

    private final JdbcTemplate jdbc;

    public SectionAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initTables() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS app_settings (
              setting_key VARCHAR(120) PRIMARY KEY,
              setting_value VARCHAR(500),
              updated_at TIMESTAMP NOT NULL
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS user_section_permissions (
              user_id VARCHAR(36) NOT NULL,
              section_key VARCHAR(20) NOT NULL,
              granted_at TIMESTAMP NOT NULL,
              PRIMARY KEY(user_id, section_key)
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS user_section_requests (
              user_id VARCHAR(36) NOT NULL,
              section_key VARCHAR(20) NOT NULL,
              request_status VARCHAR(20) NOT NULL,
              requested_at TIMESTAMP NOT NULL,
              decided_at TIMESTAMP,
              PRIMARY KEY(user_id, section_key)
            )
        """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_section_permissions_user ON user_section_permissions(user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_section_requests_status ON user_section_requests(request_status)");
    }

    /**
     * Feature ilk dəfə production-a çıxanda mövcud istifadəçilərin əvvəlki girişləri itmir.
     * Bu migration yalnız bir dəfə işləyir. Sonradan qeydiyyatdan keçən USER hesabları
     * admin icazəsi verilənədək bölmə icazəsiz başlayır.
     */
    public synchronized void initializeLegacyUsers() {
        if (setting(SETTING_PERMISSIONS_INITIALIZED).isPresent()) return;
        List<String> userIds = jdbc.queryForList("SELECT id FROM app_users WHERE user_role<>'ADMIN'", String.class);
        Instant now = Instant.now();
        for (String userId : userIds) {
            for (SectionDef section : SECTIONS) {
                grantIfMissing(userId, section.key(), now);
            }
        }
        putSetting(SETTING_PERMISSIONS_INITIALIZED, "true");
    }

    public List<SectionDef> sections() {
        return SECTIONS;
    }

    public boolean isKnownSection(String key) {
        if (key == null) return false;
        return SECTIONS.stream().anyMatch(s -> s.key().equals(key));
    }

    public boolean hasAccess(String userId, String sectionKey) {
        if (!isKnownSection(sectionKey)) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_section_permissions WHERE user_id=? AND section_key=?",
                Integer.class, userId, sectionKey);
        return count != null && count > 0;
    }

    public boolean hasAccess(AuthenticatedUser user, String sectionKey) {
        return user != null && (user.isAdmin() || hasAccess(user.id(), sectionKey));
    }

    public Map<String, Object> userAccess(String userId, boolean admin) {
        Set<String> allowed = admin ? allKeys() : new LinkedHashSet<>(jdbc.queryForList(
                "SELECT section_key FROM user_section_permissions WHERE user_id=? ORDER BY section_key", String.class, userId));
        Map<String, String> requests = new HashMap<>();
        if (!admin) {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT section_key,request_status FROM user_section_requests WHERE user_id=?", userId)) {
                requests.put(String.valueOf(row.get("section_key")), String.valueOf(row.get("request_status")));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int pending = 0;
        for (SectionDef def : SECTIONS) {
            String status = requests.getOrDefault(def.key(), "");
            if (REQUEST_PENDING.equals(status)) pending++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", def.key());
            row.put("label", def.label());
            row.put("allowed", admin || allowed.contains(def.key()));
            row.put("requestStatus", status);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sections", rows);
        out.put("allowedSections", new ArrayList<>(allowed));
        out.put("pendingCount", pending);
        return out;
    }

    public Map<String, Object> requestAccess(String userId, String sectionKey) {
        validateSection(sectionKey);
        if (hasAccess(userId, sectionKey)) {
            return Map.of("requested", false, "alreadyAllowed", true, "sectionKey", sectionKey,
                    "message", "Bu bölmə üçün artıq icazəniz var.");
        }
        Instant now = Instant.now();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_section_requests WHERE user_id=? AND section_key=?",
                Integer.class, userId, sectionKey);
        if (count != null && count > 0) {
            jdbc.update("UPDATE user_section_requests SET request_status=?,requested_at=?,decided_at=NULL WHERE user_id=? AND section_key=?",
                    REQUEST_PENDING, ts(now), userId, sectionKey);
        } else {
            jdbc.update("INSERT INTO user_section_requests(user_id,section_key,request_status,requested_at) VALUES(?,?,?,?)",
                    userId, sectionKey, REQUEST_PENDING, ts(now));
        }
        return Map.of("requested", true, "sectionKey", sectionKey,
                "message", "Bölmə üçün icazə istəyi adminə göndərildi.");
    }

    public Map<String, Object> setUserPermissions(String userId, Collection<String> requestedKeys) {
        requireNonAdminUser(userId);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (requestedKeys != null) {
            for (String key : requestedKeys) {
                validateSection(key);
                keys.add(key);
            }
        }
        jdbc.update("DELETE FROM user_section_permissions WHERE user_id=?", userId);
        Instant now = Instant.now();
        for (String key : keys) grantIfMissing(userId, key, now);

        // Gözləyən sorğular adminin checkbox qərarı ilə sinxronlaşır.
        List<Map<String, Object>> reqs = jdbc.queryForList(
                "SELECT section_key,request_status FROM user_section_requests WHERE user_id=?", userId);
        for (Map<String, Object> req : reqs) {
            String key = String.valueOf(req.get("section_key"));
            String status = String.valueOf(req.get("request_status"));
            if (REQUEST_PENDING.equals(status) || REQUEST_APPROVED.equals(status)) {
                String next = keys.contains(key) ? REQUEST_APPROVED : REQUEST_REJECTED;
                jdbc.update("UPDATE user_section_requests SET request_status=?,decided_at=? WHERE user_id=? AND section_key=?",
                        next, ts(now), userId, key);
            }
        }
        return userAccess(userId, false);
    }

    public Map<String, Object> decideRequest(String userId, String sectionKey, boolean approve) {
        requireNonAdminUser(userId);
        validateSection(sectionKey);
        Instant now = Instant.now();
        if (approve) grantIfMissing(userId, sectionKey, now);
        else jdbc.update("DELETE FROM user_section_permissions WHERE user_id=? AND section_key=?", userId, sectionKey);

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_section_requests WHERE user_id=? AND section_key=?",
                Integer.class, userId, sectionKey);
        String status = approve ? REQUEST_APPROVED : REQUEST_REJECTED;
        if (count != null && count > 0) {
            jdbc.update("UPDATE user_section_requests SET request_status=?,decided_at=? WHERE user_id=? AND section_key=?",
                    status, ts(now), userId, sectionKey);
        } else {
            jdbc.update("INSERT INTO user_section_requests(user_id,section_key,request_status,requested_at,decided_at) VALUES(?,?,?,?,?)",
                    userId, sectionKey, status, ts(now), ts(now));
        }
        return userAccess(userId, false);
    }

    public Map<String, Object> adminOverview() {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
            SELECT r.user_id,r.section_key,r.requested_at,u.username,u.whatsapp
            FROM user_section_requests r
            JOIN app_users u ON u.id=r.user_id
            WHERE r.request_status=?
            ORDER BY r.requested_at ASC
        """, REQUEST_PENDING)) {
            Map<String, Object> x = new LinkedHashMap<>();
            String key = String.valueOf(row.get("section_key"));
            x.put("userId", row.get("user_id"));
            x.put("username", row.get("username"));
            x.put("whatsapp", row.get("whatsapp"));
            x.put("sectionKey", key);
            x.put("sectionLabel", labelFor(key));
            Object requestedAt = row.get("requested_at");
            x.put("requestedAt", requestedAt == null ? null : String.valueOf(requestedAt));
            pending.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sections", SECTIONS);
        out.put("autoApproveRegistration", autoApproveRegistration());
        out.put("pendingRequests", pending);
        out.put("pendingCount", pending.size());
        return out;
    }

    public boolean autoApproveRegistration() {
        return setting(SETTING_AUTO_APPROVE_REGISTRATION)
                .map(v -> "true".equalsIgnoreCase(v) || "1".equals(v))
                .orElse(false);
    }

    public void setAutoApproveRegistration(boolean enabled) {
        putSetting(SETTING_AUTO_APPROVE_REGISTRATION, Boolean.toString(enabled));
    }

    public void deleteUserData(String userId) {
        jdbc.update("DELETE FROM user_section_requests WHERE user_id=?", userId);
        jdbc.update("DELETE FROM user_section_permissions WHERE user_id=?", userId);
    }


    public boolean isRequestAllowed(AuthenticatedUser user, String path, String method) {
        if (user == null) return false;
        if (user.isAdmin()) return true;
        String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        // Ortaq oxuma endpoint-ləri bir neçə məhsul bölməsinin daxili asılılığıdır.
        // Menyuda Rekvizitlər/Qaimə registri gizli qalsa da 04 və 06 öz işi üçün bazanı oxuya bilər.
        if ("GET".equals(m) && "/api/companies/defaults".equals(path)) {
            return hasAccess(user.id(), "01") || hasAccess(user.id(), "04") || hasAccess(user.id(), "06");
        }
        if ("GET".equals(m) && path != null && path.startsWith("/api/invoices")) {
            return hasAccess(user.id(), "02") || hasAccess(user.id(), "04");
        }
        String required = sectionForRequest(path, method);
        return required == null || hasAccess(user.id(), required);
    }

    public String sectionForRequest(String path, String method) {
        if (path == null) return null;
        if (path.startsWith("/training/")) return "07.02";
        if (path.startsWith("/api/companies")) return "01";
        if (path.startsWith("/api/invoices")) return "02";
        if (path.startsWith("/api/etaxes-export") || path.startsWith("/api/etaxes-browser")) return "03";
        if (path.startsWith("/api/contracts") || path.startsWith("/api/document-archive") ||
                path.startsWith("/api/generate") || path.startsWith("/api/print/") || path.startsWith("/api/output/")) return "04";
        if (path.startsWith("/api/templates")) return "05.01";
        if (path.equals("/api/outgoing/template")) return "05.02";
        if (path.startsWith("/api/outgoing")) return "06";
        if (path.equals("/api/workspace/export") || path.equals("/api/workspace/import")) return "07.01";
        if (path.equals("/api/auth/change-password")) return "07.03";
        return null;
    }

    private String labelFor(String key) {
        return SECTIONS.stream().filter(s -> s.key().equals(key)).map(SectionDef::label).findFirst().orElse(key);
    }

    private Set<String> allKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (SectionDef section : SECTIONS) keys.add(section.key());
        return keys;
    }

    private void validateSection(String key) {
        if (!isKnownSection(key)) throw new IllegalArgumentException("Naməlum bölmə: " + key);
    }

    private void requireNonAdminUser(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_role FROM app_users WHERE id=?", userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("İstifadəçi tapılmadı.");
        if ("ADMIN".equalsIgnoreCase(String.valueOf(rows.get(0).get("user_role"))))
            throw new IllegalArgumentException("Admin bütün bölmələrə avtomatik girişə malikdir.");
    }

    private void grantIfMissing(String userId, String key, Instant now) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM user_section_permissions WHERE user_id=? AND section_key=?",
                Integer.class, userId, key);
        if (n == null || n == 0) {
            jdbc.update("INSERT INTO user_section_permissions(user_id,section_key,granted_at) VALUES(?,?,?)",
                    userId, key, ts(now));
        }
    }

    private Optional<String> setting(String key) {
        List<String> values = jdbc.queryForList("SELECT setting_value FROM app_settings WHERE setting_key=?", String.class, key);
        return values.stream().findFirst();
    }

    private void putSetting(String key, String value) {
        Instant now = Instant.now();
        if (setting(key).isPresent()) {
            jdbc.update("UPDATE app_settings SET setting_value=?,updated_at=? WHERE setting_key=?", value, ts(now), key);
        } else {
            jdbc.update("INSERT INTO app_settings(setting_key,setting_value,updated_at) VALUES(?,?,?)", key, value, ts(now));
        }
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
