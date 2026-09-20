package taxdata.agent;

import az.gmb.taxdata.model.EtaxesExportRequest;
import az.gmb.taxdata.model.EtaxesExportStatus;
import az.gmb.taxdata.service.BrowserPreferenceService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AgentController {
    private final LocalEtaxesAgentService etaxes;
    private final BrowserPreferenceService browsers;
    private final AgentInstallationHealth health;
    public AgentController(LocalEtaxesAgentService etaxes, BrowserPreferenceService browsers,AgentInstallationHealth health){this.etaxes=etaxes;this.browsers=browsers;this.health=health;}

    @GetMapping("/status") public Map<String,Object> status(){
        AgentInstallationHealth.Health h=health.inspect();
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("ok",true);m.put("ready",h.complete());m.put("installComplete",h.complete());m.put("agent","TaxData Local Agent");m.put("version",AgentInstallationHealth.VERSION);
        m.put("components",h.components());m.put("missing",h.issues());m.put("busy",etaxes.busy());m.put("browser",browsers.status());m.put("browserDownload",false);m.put("execution","LOCAL_PC");return m;
    }
    @PostMapping("/browser/select") public Map<String,Object> selectBrowser(){requireComplete();return browsers.chooseNativeBrowser();}
    @PostMapping("/browser/auto") public Map<String,Object> autoBrowser(){requireComplete();return browsers.clearSavedBrowser();}
    @PostMapping("/tasks") public EtaxesExportStatus start(@RequestBody EtaxesExportRequest request){requireComplete();return etaxes.start(request);}
    @GetMapping("/tasks/{id}") public EtaxesExportStatus task(@PathVariable String id){return etaxes.status(id);}
    @PostMapping("/tasks/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id){etaxes.cancel(id);return Map.of("cancelled",true);}
    @GetMapping("/tasks/{id}/download") public ResponseEntity<ByteArrayResource> download(@PathVariable String id) throws Exception{
        LocalEtaxesAgentService.Download d=etaxes.download(id);String enc=URLEncoder.encode(d.fileName(),StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+enc)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(d.bytes().length).body(new ByteArrayResource(d.bytes()));
    }
    private void requireComplete(){AgentInstallationHealth.Health h=health.inspect();if(!h.complete())throw new IllegalStateException("TaxData Agent quraşdırması tamamlanmayıb. Çatışmayan komponentlər: "+String.join(", ",h.issues())+". Saytdakı Agent bərpa düyməsi ilə tamamlayın.");}
}
