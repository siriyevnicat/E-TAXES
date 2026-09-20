package az.gmb.taxdata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

@Component
@Order(1)
public class SameOriginFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final ObjectMapper mapper;
    public SameOriginFilter(ObjectMapper mapper){ this.mapper = mapper; }

    @Override protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/") || !MUTATING.contains(req.getMethod());
    }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String origin = req.getHeader("Origin");
        String fetchSite = req.getHeader("Sec-Fetch-Site");
        if (origin != null && !origin.isBlank()) {
            if (!sameOrigin(req, origin)) { reject(res); return; }
        } else if (fetchSite != null && !(fetchSite.equals("same-origin") || fetchSite.equals("same-site") || fetchSite.equals("none"))) {
            reject(res); return;
        }
        chain.doFilter(req,res);
    }

    private boolean sameOrigin(HttpServletRequest req, String origin) {
        try {
            URI uri = URI.create(origin);
            String scheme = forwarded(req,"X-Forwarded-Proto",req.getScheme());
            String host = forwarded(req,"X-Forwarded-Host",req.getHeader("Host"));
            if (host == null) return false;
            String expected = scheme + "://" + host;
            return expected.equalsIgnoreCase(uri.getScheme()+"://"+uri.getAuthority());
        } catch (Exception e) { return false; }
    }

    private String forwarded(HttpServletRequest req,String header,String fallback){
        String v=req.getHeader(header); if(v==null||v.isBlank()) return fallback; int comma=v.indexOf(','); return (comma<0?v:v.substring(0,comma)).trim();
    }
    private void reject(HttpServletResponse res)throws IOException{
        res.setStatus(403);res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getWriter(), Map.of("error","Sorğu təhlükəsizlik səbəbilə rədd edildi.","code","ORIGIN_REJECTED"));
    }
}
