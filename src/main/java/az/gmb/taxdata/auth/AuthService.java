package az.gmb.taxdata.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class AuthService {
    public static final String STATUS_PENDING="PENDING", STATUS_APPROVED="APPROVED", STATUS_DISABLED="DISABLED";
    public static final String DEVICE_PENDING="PENDING", DEVICE_APPROVED="APPROVED", DEVICE_REVOKED="REVOKED";

    private final JdbcTemplate jdbc;
    private final SectionAccessService sectionAccess;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final String adminUsername, adminPassword, adminWhatsapp;
    private final boolean syncAdminPasswordOnStartup;
    private final int sessionDays, maxLoginFailures, lockMinutes;

    public AuthService(JdbcTemplate jdbc, SectionAccessService sectionAccess,
                       @Value("${app.admin.username:admin}") String adminUsername,
                       @Value("${app.admin.password:}") String adminPassword,
                       @Value("${app.admin.whatsapp:}") String adminWhatsapp,
                       @Value("${app.admin.sync-password-on-startup:false}") boolean syncAdminPasswordOnStartup,
                       @Value("${app.auth.session-days:7}") int sessionDays,
                       @Value("${app.auth.max-login-failures:8}") int maxLoginFailures,
                       @Value("${app.auth.lock-minutes:15}") int lockMinutes) {
        this.jdbc=jdbc;
        this.sectionAccess=sectionAccess;
        this.adminUsername=adminUsername;
        this.adminPassword=adminPassword;
        this.adminWhatsapp=adminWhatsapp;
        this.syncAdminPasswordOnStartup=syncAdminPasswordOnStartup;
        this.sessionDays=Math.max(1,sessionDays);
        this.maxLoginFailures=Math.max(3,maxLoginFailures);
        this.lockMinutes=Math.max(1,lockMinutes);
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS app_users (
              id VARCHAR(36) PRIMARY KEY,
              username VARCHAR(80) NOT NULL UNIQUE,
              whatsapp VARCHAR(40) NOT NULL,
              password_hash VARCHAR(120) NOT NULL,
              user_role VARCHAR(20) NOT NULL,
              user_status VARCHAR(20) NOT NULL,
              workspace_id VARCHAR(80) NOT NULL UNIQUE,
              created_at TIMESTAMP NOT NULL,
              approved_at TIMESTAMP,
              last_login_at TIMESTAMP,
              failed_login_count INTEGER NOT NULL DEFAULT 0,
              locked_until TIMESTAMP,
              etaxes_phone VARCHAR(32),
              etaxes_user_id VARCHAR(32),
              etaxes_tin VARCHAR(10),
              profile_updated_at TIMESTAMP,
              access_start_at TIMESTAMP,
              access_end_at TIMESTAMP
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS user_devices (
              id VARCHAR(36) PRIMARY KEY,
              user_id VARCHAR(36) NOT NULL,
              device_hash VARCHAR(64) NOT NULL,
              user_agent_hash VARCHAR(64) NOT NULL,
              device_label VARCHAR(180),
              registration_device BOOLEAN NOT NULL DEFAULT FALSE,
              device_status VARCHAR(20) NOT NULL,
              created_at TIMESTAMP NOT NULL,
              approved_at TIMESTAMP,
              last_seen_at TIMESTAMP,
              UNIQUE(user_id, device_hash)
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS auth_sessions (
              token_hash VARCHAR(64) PRIMARY KEY,
              user_id VARCHAR(36) NOT NULL,
              device_hash VARCHAR(64) NOT NULL,
              created_at TIMESTAMP NOT NULL,
              expires_at TIMESTAMP NOT NULL,
              last_seen_at TIMESTAMP
            )
        """);
        try { jdbc.execute("ALTER TABLE user_devices ADD COLUMN IF NOT EXISTS registration_device BOOLEAN DEFAULT FALSE"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS failed_login_count INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS etaxes_phone VARCHAR(32)"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS etaxes_user_id VARCHAR(32)"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS etaxes_tin VARCHAR(10)"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS profile_updated_at TIMESTAMP"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS access_start_at TIMESTAMP"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS access_end_at TIMESTAMP"); } catch (Exception ignored) {}
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_sessions_user ON auth_sessions(user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_devices_user ON user_devices(user_id)");
        bootstrapAdmin();
        sectionAccess.initializeLegacyUsers();
        cleanupSessions();
    }

    private void bootstrapAdmin() {
        List<Map<String,Object>> admins=jdbc.queryForList("SELECT id,username,password_hash FROM app_users WHERE user_role='ADMIN' ORDER BY created_at ASC");
        if(!admins.isEmpty()){
            if(syncAdminPasswordOnStartup){
                Map<String,Object> admin=admins.get(0);
                String id=String.valueOf(admin.get("id"));
                String currentHash=String.valueOf(admin.get("password_hash"));
                if(!bcrypt.matches(adminPassword,currentHash)){
                    jdbc.update("UPDATE app_users SET password_hash=?,failed_login_count=0,locked_until=NULL WHERE id=?",bcrypt.encode(adminPassword),id);
                    jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",id);
                    System.out.println("[TaxData] Bootstrap admin parolu environment dəyəri ilə sinxronlaşdırıldı.");
                }
            }
            return;
        }
        String id=UUID.randomUUID().toString(), ws=workspaceId(id);
        Instant now=Instant.now();
        jdbc.update("INSERT INTO app_users(id,username,whatsapp,password_hash,user_role,user_status,workspace_id,created_at,approved_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id,normalizeUsername(adminUsername),normalizeWhatsappOptional(adminWhatsapp),bcrypt.encode(adminPassword),"ADMIN",STATUS_APPROVED,ws,ts(now),ts(now));
        System.out.println("[TaxData] Bootstrap admin yaradıldı: "+adminUsername+". Production-da APP_ADMIN_PASSWORD mütləq güclü saxlanılmalıdır.");
    }

    public Map<String,Object> register(String username,String whatsapp,String password,String deviceId,String userAgent,String etaxesPhone,String etaxesUserId,String etaxesTin) {
        String u=normalizeUsername(username), wa=normalizeWhatsapp(whatsapp);
        String phone=normalizeEtaxesPhone(etaxesPhone), asanUserId=normalizeEtaxesUserId(etaxesUserId), tin=normalizeTin(etaxesTin);
        validatePassword(password); validateDevice(deviceId);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE LOWER(username)=LOWER(?)",Integer.class,u);
        if(count!=null && count>0) throw new IllegalArgumentException("Bu username artıq qeydiyyatdan keçib.");
        String id=UUID.randomUUID().toString(), ws=workspaceId(id);
        Instant now=Instant.now();
        boolean autoApprove=sectionAccess.autoApproveRegistration();
        String initialStatus=autoApprove?STATUS_APPROVED:STATUS_PENDING;
        jdbc.update("INSERT INTO app_users(id,username,whatsapp,password_hash,user_role,user_status,workspace_id,created_at,approved_at,etaxes_phone,etaxes_user_id,etaxes_tin,profile_updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,u,wa,bcrypt.encode(password),"USER",initialStatus,ws,ts(now),autoApprove?ts(now):null,phone,asanUserId,tin,ts(now));
        upsertPendingDevice(id,deviceId,userAgent,true);
        if(autoApprove){
            List<Map<String,Object>> devices=jdbc.queryForList("SELECT id FROM user_devices WHERE user_id=? AND device_status=? ORDER BY registration_device DESC, created_at ASC",id,DEVICE_PENDING);
            if(!devices.isEmpty()) approveDevice(id,String.valueOf(devices.get(0).get("id")));
        }
        String message=autoApprove
                ? "Qeydiyyat tamamlandı və avtomatik aktiv edildi. Bölmə icazələrini sistemə daxil olduqdan sonra istəyə bilərsiniz."
                : "Qeydiyyat tamamlandı. Admin təsdiqindən sonra yalnız qeydiyyat etdiyiniz cihazdan giriş mümkün olacaq.";
        return Map.of("registered",true,"status",initialStatus,"username",u,"autoApproved",autoApprove,"message",message);
    }

    public LoginResult login(String username,String password,String deviceId,String userAgent) {
        String u=normalizeUsername(username); validateDevice(deviceId);
        Map<String,Object> row=findUserByUsername(u).orElse(null);
        if(row==null) return LoginResult.fail(401,"LOGIN_INVALID","Username və ya parol yanlışdır.");
        String userId=String.valueOf(row.get("id"));
        Instant now=Instant.now();
        Instant locked=instant(row.get("locked_until"));
        if(locked!=null && now.isBefore(locked)) return LoginResult.fail(429,"LOGIN_LOCKED","Çox sayda uğursuz giriş cəhdi olub. Bir qədər sonra yenidən yoxlayın.");
        if(!bcrypt.matches(password,String.valueOf(row.get("password_hash")))) {
            recordLoginFailure(userId,row);
            return LoginResult.fail(401,"LOGIN_INVALID","Username və ya parol yanlışdır.");
        }
        jdbc.update("UPDATE app_users SET failed_login_count=0,locked_until=NULL WHERE id=?",userId);
        String status=value(row,"user_status","status"), role=value(row,"user_role","role");
        if(STATUS_DISABLED.equals(status)) return LoginResult.fail(403,"USER_DISABLED","Hesab admin tərəfindən deaktiv edilib.");
        if(STATUS_PENDING.equals(status)) {
            upsertPendingDevice(userId,deviceId,userAgent,false);
            return LoginResult.fail(403,"USER_PENDING","Qeydiyyat admin təsdiqi gözləyir.");
        }
        if(!"ADMIN".equalsIgnoreCase(role)) {
            Instant start=instant(row.get("access_start_at")), end=instant(row.get("access_end_at"));
            if(start!=null && now.isBefore(start)) return LoginResult.fail(403,"ACCESS_NOT_STARTED","İstifadə müddətiniz hələ başlamayıb. Başlama vaxtı: "+start+".");
            if(end!=null && !now.isBefore(end)) return LoginResult.fail(403,"ACCESS_EXPIRED","İstifadə müddətiniz bitib. Admin ilə əlaqə saxlayın.");
        }
        String dh=hash(deviceId), uah=hash(userAgent==null?"":userAgent);
        if(!"ADMIN".equalsIgnoreCase(role) && !approvedDevice(userId,dh,uah)) {
            upsertPendingDevice(userId,deviceId,userAgent,false);
            return LoginResult.fail(403,"DEVICE_PENDING","Bu cihaz təsdiqlənməyib. Admin yeni cihazı təsdiqləyənədək giriş bağlıdır.");
        }

        Instant exp=now.plus(Duration.ofDays(sessionDays));
        Instant accessEnd=instant(row.get("access_end_at"));
        if(!"ADMIN".equalsIgnoreCase(role) && accessEnd!=null && accessEnd.isBefore(exp)) exp=accessEnd;
        String token=randomToken(), th=hash(token);
        jdbc.update("DELETE FROM auth_sessions WHERE user_id=? AND device_hash=?",userId,dh);
        jdbc.update("INSERT INTO auth_sessions(token_hash,user_id,device_hash,created_at,expires_at,last_seen_at) VALUES(?,?,?,?,?,?)",th,userId,dh,ts(now),ts(exp),ts(now));
        jdbc.update("UPDATE app_users SET last_login_at=?,failed_login_count=0,locked_until=NULL WHERE id=?",ts(now),userId);
        jdbc.update("UPDATE user_devices SET last_seen_at=? WHERE user_id=? AND device_hash=?",ts(now),userId,dh);
        return LoginResult.ok(token,toAuthUser(row));
    }

    public Optional<AuthenticatedUser> authenticate(String rawToken,String rawDeviceId) {
        if(blank(rawToken)||blank(rawDeviceId)) return Optional.empty();
        String th=hash(rawToken), dh=hash(rawDeviceId);
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT u.id,u.username,u.whatsapp,u.user_role AS role,u.user_status AS status,u.workspace_id,u.etaxes_phone,u.etaxes_user_id,u.etaxes_tin,
                   u.profile_updated_at,u.access_start_at,u.access_end_at,s.expires_at,s.device_hash
            FROM auth_sessions s JOIN app_users u ON u.id=s.user_id WHERE s.token_hash=?
        """,th);
        if(rows.isEmpty()) return Optional.empty();
        Map<String,Object> r=rows.get(0);
        Instant now=Instant.now(), ex=instant(r.get("expires_at"));
        boolean invalid=ex==null || !now.isBefore(ex) || STATUS_DISABLED.equals(String.valueOf(r.get("status"))) || !dh.equals(String.valueOf(r.get("device_hash")));
        if(!"ADMIN".equalsIgnoreCase(String.valueOf(r.get("role")))) {
            Instant start=instant(r.get("access_start_at")), end=instant(r.get("access_end_at"));
            invalid = invalid || (start!=null && now.isBefore(start)) || (end!=null && !now.isBefore(end));
        }
        if(invalid){ jdbc.update("DELETE FROM auth_sessions WHERE token_hash=?",th); return Optional.empty(); }
        if(!"ADMIN".equalsIgnoreCase(String.valueOf(r.get("role")))) {
            Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM user_devices WHERE user_id=? AND device_hash=? AND device_status=?",Integer.class,String.valueOf(r.get("id")),dh,DEVICE_APPROVED);
            if(n==null||n==0){ jdbc.update("DELETE FROM auth_sessions WHERE token_hash=?",th); return Optional.empty(); }
        }
        jdbc.update("UPDATE auth_sessions SET last_seen_at=? WHERE token_hash=?",ts(now),th);
        return Optional.of(toAuthUser(r));
    }

    public void logout(String rawToken) { if(!blank(rawToken)) jdbc.update("DELETE FROM auth_sessions WHERE token_hash=?",hash(rawToken)); }

    public List<Map<String,Object>> adminUsers() {
        List<Map<String,Object>> users=jdbc.queryForList("SELECT id,username,whatsapp,user_role AS role,user_status AS status,workspace_id,etaxes_phone,etaxes_user_id,etaxes_tin,profile_updated_at,access_start_at,access_end_at,created_at,approved_at,last_login_at FROM app_users ORDER BY created_at DESC");
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> u:users){
            Map<String,Object>x=new LinkedHashMap<>(u);
            Instant start=instant(u.get("access_start_at")), end=instant(u.get("access_end_at"));
            x.remove("access_start_at"); x.remove("access_end_at");
            x.put("accessStartAt", start==null?null:start.toString());
            x.put("accessEndAt", end==null?null:end.toString());
            x.put("devices",jdbc.queryForList("SELECT id,device_status AS status,device_label,registration_device,created_at,approved_at,last_seen_at FROM user_devices WHERE user_id=? ORDER BY created_at DESC",u.get("id")));
            x.put("sectionAccess",sectionAccess.userAccess(String.valueOf(u.get("id")),"ADMIN".equalsIgnoreCase(String.valueOf(u.get("role")))));
            out.add(x);
        }
        return out;
    }

    public Map<String,Object> setAccessWindow(String id,Instant start,Instant end){
        requireNonAdmin(id);
        if(start!=null && end!=null && !end.isAfter(start)) throw new IllegalArgumentException("Bitmə vaxtı başlama vaxtından sonra olmalıdır.");
        jdbc.update("UPDATE app_users SET access_start_at=?,access_end_at=? WHERE id=?",start==null?null:ts(start),end==null?null:ts(end),id);
        // Müddət dəyişəndə köhnə sessiyanın əvvəlki qayda ilə davam etməməsi üçün sessiyalar bağlanır.
        jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",id);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("saved",true);
        result.put("accessStartAt",start==null?null:start.toString());
        result.put("accessEndAt",end==null?null:end.toString());
        result.put("message","İstifadə müddəti yadda saxlanıldı. İstifadəçi yenidən giriş etməlidir.");
        return result;
    }

    public void approveUser(String id) {
        requireUser(id); Instant now=Instant.now();
        jdbc.update("UPDATE app_users SET user_status=?,approved_at=? WHERE id=? AND user_role<>'ADMIN'",STATUS_APPROVED,ts(now),id);
        List<Map<String,Object>> devices=jdbc.queryForList("SELECT id FROM user_devices WHERE user_id=? AND device_status=? ORDER BY registration_device DESC, created_at ASC",id,DEVICE_PENDING);
        if(!devices.isEmpty()) approveDevice(id,String.valueOf(devices.get(0).get("id")));
    }
    public void disableUser(String id){requireNonAdmin(id);jdbc.update("UPDATE app_users SET user_status=? WHERE id=?",STATUS_DISABLED,id);jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",id);}
    public void enableUser(String id){requireNonAdmin(id);jdbc.update("UPDATE app_users SET user_status=? WHERE id=?",STATUS_APPROVED,id);}
    public void deleteUser(String id){requireNonAdmin(id);jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",id);jdbc.update("DELETE FROM user_devices WHERE user_id=?",id);sectionAccess.deleteUserData(id);try{jdbc.update("DELETE FROM workspace_cloud_backups WHERE user_id=?",id);}catch(Exception ignored){}jdbc.update("DELETE FROM app_users WHERE id=?",id);}
    public void resetDevices(String id){requireNonAdmin(id);jdbc.update("UPDATE user_devices SET device_status=? WHERE user_id=?",DEVICE_REVOKED,id);jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",id);}
    public void approveDevice(String userId,String deviceRecordId){requireNonAdmin(userId);Instant now=Instant.now();jdbc.update("UPDATE user_devices SET device_status=? WHERE user_id=?",DEVICE_REVOKED,userId);jdbc.update("UPDATE user_devices SET device_status=?,approved_at=? WHERE id=? AND user_id=?",DEVICE_APPROVED,ts(now),deviceRecordId,userId);jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",userId);}

    public void assertEtaxesTinAllowed(AuthenticatedUser user,String rawTin){
        if(user==null)throw new IllegalArgumentException("İstifadəçi sessiyası tapılmadı.");
        String tin=normalizeTin(rawTin);
        if(user.isAdmin())return;
        Map<String,Object> row=findUserById(user.id()).orElseThrow(()->new IllegalArgumentException("İstifadəçi tapılmadı."));
        String bound=value(row,"etaxes_tin").trim();
        if(!bound.isBlank()&&!bound.equals(tin)) throw new IllegalArgumentException("Bu hesab e‑Taxes üçün artıq "+bound+" VÖEN-inə bağlanıb. Adi istifadəçi yalnız bir VÖEN-dən qaimə gətirə bilər.");
    }
    public void bindVerifiedEtaxesTin(String userId,boolean admin,String rawTin){
        if(admin)return;
        String tin=normalizeTin(rawTin); requireNonAdmin(userId);
        jdbc.update("UPDATE app_users SET etaxes_tin=?,profile_updated_at=? WHERE id=? AND (etaxes_tin IS NULL OR TRIM(etaxes_tin)='')",tin,ts(Instant.now()),userId);
        String bound=String.valueOf(jdbc.queryForObject("SELECT COALESCE(etaxes_tin,'') FROM app_users WHERE id=?",String.class,userId)).trim();
        if(!tin.equals(bound))throw new IllegalStateException("Bu istifadəçi artıq başqa VÖEN-ə bağlanıb: "+bound+".");
    }
    public void resetEtaxesTin(String id){requireNonAdmin(id);jdbc.update("UPDATE app_users SET etaxes_tin=NULL,profile_updated_at=? WHERE id=?",ts(Instant.now()),id);}

    public Map<String,Object> updateEtaxesProfile(String id,String rawPhone,String rawUserId,String rawTin){
        requireNonAdmin(id);
        String phone=normalizeEtaxesPhone(rawPhone), userId=normalizeEtaxesUserId(rawUserId), tin=normalizeTin(rawTin);
        Instant now=Instant.now();
        jdbc.update("UPDATE app_users SET etaxes_phone=?,etaxes_user_id=?,etaxes_tin=?,profile_updated_at=? WHERE id=?",phone,userId,tin,ts(now),id);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("saved",true);
        out.put("etaxesPhone",phone);
        out.put("etaxesUserId",userId);
        out.put("etaxesTin",tin);
        out.put("profileUpdatedAt",now.toString());
        out.put("message","e‑Taxes profili qalıcı olaraq yadda saxlanıldı. Aktiv istifadəçi brauzeri növbəti sinxronizasiyada avtomatik yenilənəcək.");
        return out;
    }

    public boolean autoApproveRegistrationEnabled(){return sectionAccess.autoApproveRegistration();}

    public void changePassword(String userId,String oldPassword,String newPassword){
        Map<String,Object>u=findUserById(userId).orElseThrow(()->new IllegalArgumentException("İstifadəçi tapılmadı."));
        if(!bcrypt.matches(oldPassword,String.valueOf(u.get("password_hash"))))throw new IllegalArgumentException("Cari parol yanlışdır.");
        validatePassword(newPassword);
        jdbc.update("UPDATE app_users SET password_hash=? WHERE id=?",bcrypt.encode(newPassword),userId);
        jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",userId);
    }

    private void recordLoginFailure(String userId,Map<String,Object> row){
        int count=0; Object v=row.get("failed_login_count");
        if(v instanceof Number n)count=n.intValue(); else try{count=Integer.parseInt(String.valueOf(v));}catch(Exception ignored){}
        count++;
        if(count>=maxLoginFailures) jdbc.update("UPDATE app_users SET failed_login_count=0,locked_until=? WHERE id=?",ts(Instant.now().plus(Duration.ofMinutes(lockMinutes))),userId);
        else jdbc.update("UPDATE app_users SET failed_login_count=? WHERE id=?",count,userId);
    }

    private void upsertPendingDevice(String userId,String deviceId,String userAgent,boolean registration){
        String dh=hash(deviceId),uah=hash(userAgent==null?"":userAgent),label=deviceLabel(userAgent);
        List<Map<String,Object>> r=jdbc.queryForList("SELECT id,device_status AS status FROM user_devices WHERE user_id=? AND device_hash=?",userId,dh);
        if(r.isEmpty()) jdbc.update("INSERT INTO user_devices(id,user_id,device_hash,user_agent_hash,device_label,registration_device,device_status,created_at) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),userId,dh,uah,label,registration,DEVICE_PENDING,ts(Instant.now()));
        else if(DEVICE_REVOKED.equals(String.valueOf(r.get(0).get("status")))) jdbc.update("UPDATE user_devices SET device_status=?,user_agent_hash=?,device_label=?,registration_device=?,created_at=? WHERE id=?",DEVICE_PENDING,uah,label,registration,ts(Instant.now()),r.get(0).get("id"));
    }
    private boolean approvedDevice(String uid,String dh,String uah){Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM user_devices WHERE user_id=? AND device_hash=? AND device_status=?",Integer.class,uid,dh,DEVICE_APPROVED);return n!=null&&n>0;}
    private Optional<Map<String,Object>> findUserByUsername(String u){List<Map<String,Object>>r=jdbc.queryForList("SELECT * FROM app_users WHERE LOWER(username)=LOWER(?)",u);return r.stream().findFirst();}
    private Optional<Map<String,Object>> findUserById(String id){List<Map<String,Object>>r=jdbc.queryForList("SELECT * FROM app_users WHERE id=?",id);return r.stream().findFirst();}
    private void requireUser(String id){if(findUserById(id).isEmpty())throw new IllegalArgumentException("İstifadəçi tapılmadı.");}
    private void requireNonAdmin(String id){Map<String,Object>u=findUserById(id).orElseThrow(()->new IllegalArgumentException("İstifadəçi tapılmadı."));if("ADMIN".equalsIgnoreCase(value(u,"user_role","role")))throw new IllegalArgumentException("Admin hesabına bu əməliyyat tətbiq edilmir.");}
    private AuthenticatedUser toAuthUser(Map<String,Object>r){return new AuthenticatedUser(String.valueOf(r.get("id")),String.valueOf(r.get("username")),String.valueOf(r.get("whatsapp")),value(r,"role","user_role"),String.valueOf(r.get("workspace_id")),value(r,"etaxes_phone"),value(r,"etaxes_user_id"),value(r,"etaxes_tin"),instant(r.get("profile_updated_at")),instant(r.get("access_start_at")),instant(r.get("access_end_at")));}
    private String workspaceId(String id){return "user_"+id.replace("-","");}
    private String normalizeUsername(String v){String s=v==null?"":v.trim();if(!s.matches("[A-Za-z0-9_.@-]{3,80}"))throw new IllegalArgumentException("Username 3-80 simvol olmalı, hərf/rəqəm və . _ - @ istifadə edilə bilər.");return s.toLowerCase(Locale.ROOT);}
    private String normalizeWhatsapp(String v){String s=v==null?"":v.replaceAll("[^0-9+]","");if(s.length()<7||s.length()>20)throw new IllegalArgumentException("WhatsApp nömrəsi düzgün yazılmalıdır.");return s;}
    private String normalizeWhatsappOptional(String v){return v==null||v.isBlank()?"":normalizeWhatsapp(v);}
    private String normalizeEtaxesPhone(String v){String s=v==null?"":v.replaceAll("\\D","");if(s.length()<9||s.length()>15)throw new IllegalArgumentException("ASAN İmza telefon nömrəsi 9-15 rəqəm olmalıdır.");return s;}
    private String normalizeEtaxesUserId(String v){String s=v==null?"":v.replaceAll("\\D","");if(s.isBlank()||s.length()>20)throw new IllegalArgumentException("ASAN İmza İstifadəçi ID yalnız rəqəmlərdən ibarət olmalıdır.");return s;}
    private String normalizeTin(String v){String s=v==null?"":v.replaceAll("\\D","");if(s.length()!=10)throw new IllegalArgumentException("VÖEN 10 rəqəm olmalıdır.");return s;}
    private void validatePassword(String p){if(p==null||p.length()<10)throw new IllegalArgumentException("Parol ən azı 10 simvol olmalıdır.");if(p.length()>100)throw new IllegalArgumentException("Parol çox uzundur.");boolean a=p.chars().anyMatch(Character::isLetter), d=p.chars().anyMatch(Character::isDigit);if(!a||!d)throw new IllegalArgumentException("Parolda ən azı bir hərf və bir rəqəm olmalıdır.");}
    private void validateDevice(String d){if(d==null||d.length()<20||d.length()>200)throw new IllegalArgumentException("Cihaz açarı yaradılmadı. Brauzerdə localStorage aktiv olmalıdır.");}
    private String deviceLabel(String ua){String s=ua==null?"Naməlum cihaz":ua;return s.length()>170?s.substring(0,170):s;}
    private String randomToken(){byte[]b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public String sessionWorkspaceId(String rawToken){String h=hash(rawToken);return "sess_"+h.substring(0,Math.min(48,h.length()));}
    public AuthenticatedUser sessionUser(AuthenticatedUser user,String rawToken){if(user==null)return null;return new AuthenticatedUser(user.id(),user.username(),user.whatsapp(),user.role(),sessionWorkspaceId(rawToken),user.etaxesPhone(),user.etaxesUserId(),user.etaxesTin(),user.profileUpdatedAt(),user.accessStartAt(),user.accessEndAt());}
    public String hash(String s){try{MessageDigest md=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(md.digest((s==null?"":s).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    @Scheduled(fixedDelayString="${app.auth.session-cleanup-ms:3600000}")
    public void cleanupSessions(){jdbc.update("DELETE FROM auth_sessions WHERE expires_at < ?",ts(Instant.now()));}
    private String value(Map<String,Object> r,String...keys){for(String k:keys){Object v=r.get(k);if(v!=null)return String.valueOf(v);}return "";}
    private boolean blank(String s){return s==null||s.isBlank();}
    private Timestamp ts(Instant i){return Timestamp.from(i);}
    private Instant instant(Object v){
        if(v==null)return null;
        if(v instanceof Timestamp t)return t.toInstant();
        if(v instanceof java.util.Date d)return d.toInstant();
        if(v instanceof Instant i)return i;
        try{return Instant.parse(String.valueOf(v));}catch(Exception ignored){}
        try{return Timestamp.valueOf(String.valueOf(v)).toInstant();}catch(Exception ignored){}
        return null;
    }

    public record LoginResult(boolean success,int httpStatus,String code,String message,String token,AuthenticatedUser user){
        static LoginResult ok(String t,AuthenticatedUser u){return new LoginResult(true,200,"OK","Giriş uğurludur.",t,u);}
        static LoginResult fail(int s,String c,String m){return new LoginResult(false,s,c,m,null,null);}
    }
}
