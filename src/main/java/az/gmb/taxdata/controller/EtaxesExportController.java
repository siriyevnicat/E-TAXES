package az.gmb.taxdata.controller;

import az.gmb.taxdata.auth.AuthenticatedUser;
import az.gmb.taxdata.auth.CurrentUserContext;
import az.gmb.taxdata.model.EtaxesExportRequest;
import az.gmb.taxdata.model.EtaxesExportStatus;
import az.gmb.taxdata.service.EtaxesExportService;
import az.gmb.taxdata.auth.AuthService;
import az.gmb.taxdata.service.EtaxesAgentGrantService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/etaxes-export")
public class EtaxesExportController {
    private final EtaxesExportService service;
    private final AuthService auth;
    private final EtaxesAgentGrantService grants;
    public EtaxesExportController(EtaxesExportService service,AuthService auth,EtaxesAgentGrantService grants){this.service=service;this.auth=auth;this.grants=grants;}

    @PostMapping("/start")
    public EtaxesExportStatus start(@RequestBody EtaxesExportRequest request){
        throw new IllegalStateException("Production rejimində e-Taxes əməliyyatı TaxData Local Agent vasitəsilə istifadəçinin öz kompüterində aparılır. Agent quraşdırılmayıbsa e-Taxes bölməsində quraşdırın.");
    }


    @PostMapping("/grant")
    public EtaxesAgentGrantService.GrantResponse grant(@RequestBody Map<String,String> body){
        AuthenticatedUser u=CurrentUserContext.require();
        String tin=body==null?"":body.getOrDefault("tin","");
        String phone=body==null?"":body.getOrDefault("phone","");
        String userId=body==null?"":body.getOrDefault("userId","");
        return grants.issue(u,tin,phone,userId);
    }

    @PostMapping("/check-tin")
    public Map<String,Object> checkTin(@RequestBody Map<String,String> body){
        AuthenticatedUser u=CurrentUserContext.require();
        String tin=body==null?"":body.getOrDefault("tin","");
        auth.assertEtaxesTinAllowed(u,tin);
        return Map.of("allowed",true,"tin",tin.replaceAll("\\D",""));
    }

    @PostMapping("/confirm-tin")
    public Map<String,Object> confirmTin(@RequestBody Map<String,String> body){
        AuthenticatedUser u=CurrentUserContext.require();
        String tin=body==null?"":body.getOrDefault("tin","");
        auth.assertEtaxesTinAllowed(u,tin);
        auth.bindVerifiedEtaxesTin(u.id(),u.isAdmin(),tin);
        return Map.of("bound",!u.isAdmin(),"tin",tin.replaceAll("\\D",""));
    }

    @GetMapping("/active")
    public EtaxesExportStatus active(){
        AuthenticatedUser u=CurrentUserContext.require();
        return service.active(u.id());
    }

    @GetMapping("/{taskId}")
    public EtaxesExportStatus status(@PathVariable String taskId){
        AuthenticatedUser u=CurrentUserContext.require();
        return service.status(u.id(),taskId);
    }

    @PostMapping("/{taskId}/cancel")
    public Map<String,Object> cancel(@PathVariable String taskId){
        AuthenticatedUser u=CurrentUserContext.require();
        service.cancel(u.id(),taskId);
        return Map.of("cancelled",true);
    }
}
