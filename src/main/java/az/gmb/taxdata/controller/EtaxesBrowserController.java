package az.gmb.taxdata.controller;

import az.gmb.taxdata.service.BrowserPreferenceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/etaxes-browser")
public class EtaxesBrowserController {
    private final BrowserPreferenceService browsers;
    public EtaxesBrowserController(BrowserPreferenceService browsers){this.browsers=browsers;}

    @GetMapping("/status") public Map<String,Object> status(){return browsers.status();}
    @PostMapping("/select") public Map<String,Object> select(){return browsers.chooseNativeBrowser();}
    @PostMapping("/clear") public Map<String,Object> clear(){return browsers.clearSavedBrowser();}
}
