package az.gmb.taxdata.controller;

import az.gmb.taxdata.auth.AuthService;
import az.gmb.taxdata.auth.SectionAccessService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AuthService auth;
    private final SectionAccessService sections;

    public AdminController(AuthService auth, SectionAccessService sections){
        this.auth=auth;
        this.sections=sections;
    }

    @GetMapping("/users") public Map<String,Object> users(){return Map.of("users",auth.adminUsers());}
    @PostMapping("/users/{id}/approve") public Map<String,Object> approve(@PathVariable String id){auth.approveUser(id);return Map.of("approved",true);}
    @PostMapping("/users/{id}/disable") public Map<String,Object> disable(@PathVariable String id){auth.disableUser(id);return Map.of("disabled",true);}
    @PostMapping("/users/{id}/enable") public Map<String,Object> enable(@PathVariable String id){auth.enableUser(id);return Map.of("enabled",true);}
    @PostMapping("/users/{id}/reset-devices") public Map<String,Object> reset(@PathVariable String id){auth.resetDevices(id);return Map.of("reset",true);}
    @PostMapping("/users/{id}/reset-etaxes-tin") public Map<String,Object> resetEtaxesTin(@PathVariable String id){auth.resetEtaxesTin(id);return Map.of("reset",true);}
    @PostMapping("/users/{uid}/devices/{did}/approve") public Map<String,Object> approveDevice(@PathVariable String uid,@PathVariable String did){auth.approveDevice(uid,did);return Map.of("approved",true);}
    @PostMapping("/users/{id}/access-window") public Map<String,Object> accessWindow(@PathVariable String id,@RequestBody Map<String,Object> body){return auth.setAccessWindow(id,parseInstant(body.get("start")),parseInstant(body.get("end")));}
    @PostMapping("/users/{id}/etaxes-profile") public Map<String,Object> etaxesProfile(@PathVariable String id,@RequestBody Map<String,Object> body){return auth.updateEtaxesProfile(id,String.valueOf(body.getOrDefault("phone","")),String.valueOf(body.getOrDefault("userId","")),String.valueOf(body.getOrDefault("tin","")));}
    @DeleteMapping("/users/{id}") public Map<String,Object> delete(@PathVariable String id){auth.deleteUser(id);return Map.of("deleted",true);}

    @GetMapping("/section-access")
    public Map<String,Object> sectionAccessOverview(){return sections.adminOverview();}

    @GetMapping("/users/{id}/sections")
    public Map<String,Object> userSections(@PathVariable String id){return sections.userAccess(id,false);}

    @PostMapping("/users/{id}/sections")
    public Map<String,Object> saveUserSections(@PathVariable String id,@RequestBody Map<String,Object> body){
        Object raw=body.get("allowedSections");
        List<String> keys=new ArrayList<>();
        if(raw instanceof Collection<?> c){for(Object v:c)if(v!=null)keys.add(String.valueOf(v));}
        return sections.setUserPermissions(id,keys);
    }

    @PostMapping("/section-requests/decision")
    public Map<String,Object> decideSectionRequest(@RequestBody Map<String,Object> body){
        String userId=String.valueOf(body.getOrDefault("userId","")).trim();
        String sectionKey=String.valueOf(body.getOrDefault("sectionKey","")).trim();
        boolean approve=Boolean.TRUE.equals(body.get("approve")) || "true".equalsIgnoreCase(String.valueOf(body.get("approve")));
        return sections.decideRequest(userId,sectionKey,approve);
    }

    @PostMapping("/settings/auto-approve-registration")
    public Map<String,Object> autoApproveRegistration(@RequestBody Map<String,Object> body){
        boolean enabled=Boolean.TRUE.equals(body.get("enabled")) || "true".equalsIgnoreCase(String.valueOf(body.get("enabled")));
        sections.setAutoApproveRegistration(enabled);
        return Map.of("saved",true,"enabled",enabled);
    }

    private Instant parseInstant(Object value){
        if(value==null)return null;
        String s=String.valueOf(value).trim();
        if(s.isEmpty()||"null".equalsIgnoreCase(s))return null;
        try{return Instant.parse(s);}catch(Exception e){throw new IllegalArgumentException("Tarix/saat formatı düzgün deyil: "+s);}
    }
}
