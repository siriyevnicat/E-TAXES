package az.gmb.taxdata.service;

import az.gmb.taxdata.auth.AuthService;
import az.gmb.taxdata.auth.AuthenticatedUser;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class EtaxesAgentGrantService {
    private static final Duration TTL=Duration.ofMinutes(10);
    private final SecureRandom random=new SecureRandom();
    private final AuthService auth;
    private final JdbcTemplate jdbc;

    public EtaxesAgentGrantService(AuthService auth,JdbcTemplate jdbc){this.auth=auth;this.jdbc=jdbc;}

    @PostConstruct
    public void init(){
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS etaxes_agent_grants (
                token_hash VARCHAR(64) PRIMARY KEY,
                user_id VARCHAR(100) NOT NULL,
                is_admin BOOLEAN NOT NULL,
                phone VARCHAR(32),
                asan_user_id VARCHAR(32),
                tin VARCHAR(10) NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                consumed BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL
            )
        """);
        try{jdbc.execute("ALTER TABLE etaxes_agent_grants ADD COLUMN IF NOT EXISTS phone VARCHAR(32)");}catch(Exception ignored){}
        try{jdbc.execute("ALTER TABLE etaxes_agent_grants ADD COLUMN IF NOT EXISTS asan_user_id VARCHAR(32)");}catch(Exception ignored){}
        try{jdbc.execute("CREATE INDEX IF NOT EXISTS idx_etaxes_agent_grants_exp ON etaxes_agent_grants(expires_at)");}catch(Exception ignored){}
        cleanup();
    }

    public GrantResponse issue(AuthenticatedUser user,String rawTin,String rawPhone,String rawAsanUserId){
        auth.assertEtaxesTinAllowed(user,rawTin);
        cleanup();
        String tin=digits(rawTin),phone=phoneDigits(rawPhone),asanUserId=userIdDigits(rawAsanUserId);
        if(!user.isAdmin()){
            if(user.etaxesPhone()==null||user.etaxesPhone().isBlank()||user.etaxesUserId()==null||user.etaxesUserId().isBlank()||user.etaxesTin()==null||user.etaxesTin().isBlank())throw new IllegalArgumentException("Bu istifadəçinin e‑Taxes profili tam deyil. Admin Telefon, İstifadəçi ID və VÖEN məlumatlarını tamamlamalıdır.");
            String expectedPhone=phoneDigits(user.etaxesPhone()), expectedUserId=userIdDigits(user.etaxesUserId());
            if(!expectedPhone.equals(phone)||!expectedUserId.equals(asanUserId)||!digits(user.etaxesTin()).equals(tin))throw new IllegalArgumentException("e‑Taxes məlumatları admin tərəfindən saxlanılan istifadəçi profilinə uyğun deyil.");
        }
        String token=randomToken(),hash=hash(token);
        Instant now=Instant.now(),expires=now.plus(TTL);
        jdbc.update("INSERT INTO etaxes_agent_grants(token_hash,user_id,is_admin,phone,asan_user_id,tin,expires_at,consumed,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                hash,user.id(),user.isAdmin(),phone,asanUserId,tin,Timestamp.from(expires),false,Timestamp.from(now));
        return new GrantResponse(token,tin,TTL.toSeconds());
    }

    public boolean consume(String token,String rawTin,String rawPhone,String rawAsanUserId){
        cleanup();
        String h=hash(clean(token)),tin=digits(rawTin),phone=phoneDigits(rawPhone),asanUserId=userIdDigits(rawAsanUserId);
        int changed=jdbc.update("UPDATE etaxes_agent_grants SET consumed=TRUE WHERE token_hash=? AND tin=? AND phone=? AND asan_user_id=? AND consumed=FALSE AND expires_at>?",
                h,tin,phone,asanUserId,Timestamp.from(Instant.now()));
        return changed==1;
    }

    public boolean complete(String token,String rawTin,String rawPhone,String rawAsanUserId){
        cleanup();
        String h=hash(clean(token)),tin=digits(rawTin),phone=phoneDigits(rawPhone),asanUserId=userIdDigits(rawAsanUserId);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT user_id,is_admin,tin,expires_at,consumed FROM etaxes_agent_grants WHERE token_hash=? AND tin=? AND phone=? AND asan_user_id=?",h,tin,phone,asanUserId);
        if(rows.isEmpty())return false;
        Map<String,Object> g=rows.get(0);
        Object exp=g.get("expires_at");
        Instant expires=exp instanceof Timestamp ts?ts.toInstant():Instant.EPOCH;
        boolean consumed=Boolean.TRUE.equals(g.get("consumed"));
        if(!consumed||expires.isBefore(Instant.now())){jdbc.update("DELETE FROM etaxes_agent_grants WHERE token_hash=?",h);return false;}
        String userId=String.valueOf(g.get("user_id"));
        boolean admin=Boolean.TRUE.equals(g.get("is_admin"));
        auth.bindVerifiedEtaxesTin(userId,admin,tin);
        jdbc.update("DELETE FROM etaxes_agent_grants WHERE token_hash=?",h);
        return true;
    }

    public void revoke(String token){if(token!=null&&!token.isBlank())jdbc.update("DELETE FROM etaxes_agent_grants WHERE token_hash=?",hash(clean(token)));}
    private void cleanup(){jdbc.update("DELETE FROM etaxes_agent_grants WHERE expires_at < ?",Timestamp.from(Instant.now()));}
    private String clean(String s){return s==null?"":s.trim();}
    private String digits(String s){String d=s==null?"":s.replaceAll("\\D","");if(d.length()!=10)throw new IllegalArgumentException("VÖEN 10 rəqəm olmalıdır.");return d;}
    private String phoneDigits(String s){String d=s==null?"":s.replaceAll("\\D","");if(d.length()<9||d.length()>15)throw new IllegalArgumentException("ASAN İmza telefon nömrəsi 9-15 rəqəm olmalıdır.");return d;}
    private String userIdDigits(String s){String raw=s==null?"":s.trim();if(!raw.matches("\\d{1,20}"))throw new IllegalArgumentException("ASAN İmza İstifadəçi ID yalnız rəqəmlərdən ibarət olmalıdır.");return raw;}
    private String randomToken(){byte[]b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private String hash(String s){try{MessageDigest md=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(md.digest((s==null?"":s).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}

    public record GrantResponse(String grant,String tin,long expiresInSeconds){}
}
