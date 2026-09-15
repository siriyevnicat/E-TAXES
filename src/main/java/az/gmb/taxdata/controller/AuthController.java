package az.gmb.taxdata.controller;

import az.gmb.taxdata.auth.*;
import az.gmb.taxdata.service.StorageService;
import az.gmb.taxdata.service.CloudWorkspaceBackupService;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth; private final SectionAccessService sections; private final AuthRateLimitService limits; private final StorageService storage; private final CloudWorkspaceBackupService cloud; private final String whatsappUrl; private final int sessionDays;
    public AuthController(AuthService auth,SectionAccessService sections,AuthRateLimitService limits,StorageService storage,CloudWorkspaceBackupService cloud,@Value("${app.whatsapp.url:}")String whatsappUrl,@Value("${app.auth.session-days:7}")int sessionDays){this.auth=auth;this.sections=sections;this.limits=limits;this.storage=storage;this.cloud=cloud;this.whatsappUrl=whatsappUrl;this.sessionDays=sessionDays;}

    @GetMapping("/public-info") public Map<String,Object> publicInfo(){boolean auto=auth.autoApproveRegistrationEnabled();return Map.of("whatsappUrl",whatsappUrl,"registrationApprovalRequired",!auto,"autoApproveRegistration",auto,"deviceApprovalRequired",true,"localFirstStorage",true,"cloudDisasterRecovery",cloud.isEnabled(),"cloudRetentionDays",cloud.retentionDays());}

    @PostMapping("/register") public ResponseEntity<?> register(@RequestBody Map<String,String>b,HttpServletRequest req){String ip=clientIp(req);if(!limits.allow("register",ip,5,3600))return ResponseEntity.status(429).body(Map.of("error","Çox sayda qeydiyyat cəhdi. Bir qədər sonra yenidən yoxlayın.","code","RATE_LIMIT"));try{return ResponseEntity.ok(auth.register(b.get("username"),b.get("whatsapp"),b.get("password"),b.get("deviceId"),req.getHeader("User-Agent"),b.get("etaxesPhone"),b.get("etaxesUserId"),b.get("etaxesTin")));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage(),"code","REGISTER_ERROR"));}}

    @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Map<String,String>b,HttpServletRequest req,HttpServletResponse res){String key=clientIp(req)+":"+String.valueOf(b.get("username")).toLowerCase(Locale.ROOT);if(!limits.allow("login",key,20,900))return ResponseEntity.status(429).body(Map.of("error","Çox sayda giriş cəhdi. Bir qədər sonra yenidən yoxlayın.","code","RATE_LIMIT"));AuthService.LoginResult r;try{r=auth.login(b.get("username"),b.get("password"),b.get("deviceId"),req.getHeader("User-Agent"));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage(),"code","LOGIN_ERROR"));}if(!r.success())return ResponseEntity.status(r.httpStatus()).body(Map.of("error",r.message(),"code",r.code()));int cookieSeconds=sessionDays*86400; if(r.user().accessEndAt()!=null&&!r.user().isAdmin()){long left=Duration.between(Instant.now(),r.user().accessEndAt()).toSeconds();cookieSeconds=(int)Math.max(1,Math.min((long)cookieSeconds,left));} setCookie(res,AuthFilter.SESSION_COOKIE,r.token(),true,cookieSeconds,req);setCookie(res,AuthFilter.DEVICE_COOKIE,b.get("deviceId"),true,cookieSeconds,req);AuthenticatedUser sessionUser=auth.sessionUser(r.user(),r.token());return ResponseEntity.ok(Map.of("user",sessionUser,"sectionAccess",sections.userAccess(sessionUser.id(),sessionUser.isAdmin()),"message",r.message(),"localFirstStorage",true));}

    @GetMapping("/me") public Map<String,Object> me(){AuthenticatedUser u=CurrentUserContext.require();return Map.of("authenticated",true,"user",u,"sectionAccess",sections.userAccess(u.id(),u.isAdmin()),"localFirstStorage",true);}

    @GetMapping("/section-access") public Map<String,Object> sectionAccess(){AuthenticatedUser u=CurrentUserContext.require();return sections.userAccess(u.id(),u.isAdmin());}
    @PostMapping("/section-access/request") public Map<String,Object> requestSectionAccess(@RequestBody Map<String,Object>b){AuthenticatedUser u=CurrentUserContext.require();if(u.isAdmin())return Map.of("requested",false,"message","Admin bütün bölmələrə avtomatik girişə malikdir.");String key=String.valueOf(b.getOrDefault("sectionKey","")).trim();return sections.requestAccess(u.id(),key);}

    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest req,HttpServletResponse res){String token=AuthFilter.cookie(req,AuthFilter.SESSION_COOKIE);boolean cloudSaved=false;String cloudError="";try{AuthenticatedUser u=CurrentUserContext.require();String ws=u.workspaceId();if(cloud.isEnabled()&&storage.isSessionWorkspace(ws)&&storage.workspaceExists(ws)){cloud.backup(u.id(),ws,"logout",true);cloudSaved=true;}}catch(Exception e){cloudError=e.getMessage()==null?"Cloud backup alınmadı.":e.getMessage();}try{if(token!=null&&!token.isBlank())storage.deleteWorkspace(auth.sessionWorkspaceId(token));}catch(Exception ignored){}auth.logout(token);clearCookie(res,AuthFilter.SESSION_COOKIE,req);clearCookie(res,AuthFilter.DEVICE_COOKIE,req);Map<String,Object> out=new LinkedHashMap<>();out.put("loggedOut",true);out.put("temporaryWorkspaceDeleted",true);out.put("cloudBackupSaved",cloudSaved);if(!cloudError.isBlank())out.put("cloudBackupError",cloudError);return out;}
    @PostMapping("/change-password") public Map<String,Object> changePassword(@RequestBody Map<String,String>b,HttpServletRequest req,HttpServletResponse res){AuthenticatedUser u=CurrentUserContext.require();String token=AuthFilter.cookie(req,AuthFilter.SESSION_COOKIE);try{if(cloud.isEnabled()&&storage.isSessionWorkspace(u.workspaceId())&&storage.workspaceExists(u.workspaceId()))cloud.backup(u.id(),u.workspaceId(),"password-change",true);}catch(Exception ignored){}auth.changePassword(u.id(),b.get("oldPassword"),b.get("newPassword"));try{if(token!=null&&!token.isBlank())storage.deleteWorkspace(auth.sessionWorkspaceId(token));}catch(Exception ignored){}clearCookie(res,AuthFilter.SESSION_COOKIE,req);return Map.of("changed",true,"message","Parol dəyişdirildi. Yenidən giriş edin.");}


    private String clientIp(HttpServletRequest req){String x=req.getHeader("X-Forwarded-For");if(x!=null&&!x.isBlank()){int c=x.indexOf(',');return (c<0?x:x.substring(0,c)).trim();}return req.getRemoteAddr();}

    private void setCookie(HttpServletResponse res,String name,String value,boolean httpOnly,int maxAge,HttpServletRequest req){ResponseCookie c=ResponseCookie.from(name,value).httpOnly(httpOnly).secure(isSecure(req)).sameSite("Lax").path("/").maxAge(Duration.ofSeconds(maxAge)).build();res.addHeader(HttpHeaders.SET_COOKIE,c.toString());}
    private void clearCookie(HttpServletResponse res,String name,HttpServletRequest req){ResponseCookie c=ResponseCookie.from(name,"").httpOnly(true).secure(isSecure(req)).sameSite("Lax").path("/").maxAge(Duration.ZERO).build();res.addHeader(HttpHeaders.SET_COOKIE,c.toString());}
    private boolean isSecure(HttpServletRequest req){return req.isSecure()||"https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto"));}
}
