package taxdata.agent;

import az.gmb.taxdata.service.BrowserPreferenceService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.Map;

@SpringBootConfiguration
@EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@Import({BrowserPreferenceService.class, LocalEtaxesAgentService.class, AgentController.class, AgentInstallationHealth.class, AgentCorsFilter.class, AgentExceptionHandler.class})
public class AgentApplication {
    public static void main(String[] args) {
        System.setProperty("server.address",System.getProperty("server.address","127.0.0.1"));
        System.setProperty("server.port",System.getProperty("server.port","47631"));
        System.setProperty("server.error.include-message","always");
        SpringApplication app = new SpringApplication(AgentApplication.class);
        app.setAdditionalProfiles("agent");
        Map<String,Object> defaults = new HashMap<>();
        defaults.put("server.address", "127.0.0.1");
        defaults.put("server.port", "47631");
        defaults.put("spring.main.headless", "false");
        defaults.put("spring.application.name", "TaxData Local Agent");
        defaults.put("server.error.include-message", "always");
        defaults.put("logging.level.root", "WARN");
        defaults.put("app.etaxes.browser.server-bundled", "false");
        defaults.put("app.etaxes.browser.headless", "false");
        app.setDefaultProperties(defaults);
        app.run(args);
    }
}
