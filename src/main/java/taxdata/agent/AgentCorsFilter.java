package taxdata.agent;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentCorsFilter extends OncePerRequestFilter {
    @Value("${taxdata.agent.allowed-origin:}") private String allowedOrigin;

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String origin=req.getHeader("Origin");
        if(origin!=null&&!origin.isBlank()){
            if(!originAllowed(origin)){
                res.setStatus(403);res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"Bu sayt TaxData Agent ilə əlaqə üçün təsdiqlənməyib. Agenti TaxData saytından yenidən quraşdırın.\"}");return;
            }
            res.setHeader("Access-Control-Allow-Origin",origin);
            res.setHeader("Vary","Origin");
            res.setHeader("Access-Control-Allow-Methods","GET,POST,OPTIONS");
            res.setHeader("Access-Control-Allow-Headers","Content-Type,X-TAXDATA-Agent");
            res.setHeader("Access-Control-Allow-Private-Network","true");
            res.setHeader("Access-Control-Max-Age","600");
        }
        res.setHeader("Cache-Control","no-store");
        if("OPTIONS".equalsIgnoreCase(req.getMethod())){res.setStatus(204);return;}
        boolean statusEndpoint="/api/status".equals(req.getRequestURI());
        if(req.getRequestURI().startsWith("/api/") && !statusEndpoint && !"1".equals(req.getHeader("X-TAXDATA-Agent"))){
            res.setStatus(403);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"error\":\"TaxData Agent sorğu başlığı yoxdur.\"}");return;
        }
        chain.doFilter(req,res);
    }

    private boolean originAllowed(String origin){
        try{
            URI u=URI.create(origin);String host=u.getHost()==null?"":u.getHost().toLowerCase(Locale.ROOT);
            if(host.equals("localhost")||host.equals("127.0.0.1"))return true;
            String configured=allowedOrigin==null?"":allowedOrigin.trim();
            return !configured.isBlank()&&configured.equalsIgnoreCase(origin);
        }catch(Exception e){return false;}
    }
}
