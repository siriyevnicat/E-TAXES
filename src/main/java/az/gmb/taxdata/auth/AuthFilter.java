package az.gmb.taxdata.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(2)
public class AuthFilter extends OncePerRequestFilter {
    public static final String SESSION_COOKIE="TAXDATA_SESSION", DEVICE_COOKIE="TAXDATA_DEVICE";
    private final AuthService auth; private final SectionAccessService sections; private final ObjectMapper mapper;
    public AuthFilter(AuthService auth,SectionAccessService sections,ObjectMapper mapper){this.auth=auth;this.sections=sections;this.mapper=mapper;}
    @Override protected boolean shouldNotFilter(HttpServletRequest r){String p=r.getRequestURI();boolean protectedPath=p.startsWith("/api/")||p.startsWith("/training/");return !protectedPath || p.equals("/api/auth/register") || p.equals("/api/auth/login") || p.equals("/api/auth/public-info");}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        String token=cookie(req,SESSION_COOKIE),device=cookie(req,DEVICE_COOKIE);
        var authenticated=auth.authenticate(token,device).orElse(null);
        if(authenticated==null){res.setStatus(401);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getWriter(),Map.of("error","Giriş tələb olunur.","code","AUTH_REQUIRED"));return;}
        if(req.getRequestURI().startsWith("/api/admin/")&&!authenticated.isAdmin()){res.setStatus(403);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getWriter(),Map.of("error","Admin icazəsi tələb olunur.","code","ADMIN_REQUIRED"));return;}

        String requiredSection=sections.sectionForRequest(req.getRequestURI(),req.getMethod());
        if(requiredSection!=null && !sections.isRequestAllowed(authenticated,req.getRequestURI(),req.getMethod())){
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("error","Bu bölmə üçün giriş icazəniz yoxdur. ‘Bölmə icazələri’ pəncərəsindən adminə istək göndərə bilərsiniz.");
            body.put("code","SECTION_ACCESS_REQUIRED");
            body.put("sectionKey",requiredSection);
            res.setStatus(403);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getWriter(),body);return;
        }

        // Session workspace is temporary/local-first for invoices and generated work files.
        // Company requisites are account-scoped SQL data and are intentionally not stored here.
        AuthenticatedUser sessionUser=auth.sessionUser(authenticated,token);
        try{CurrentUserContext.set(sessionUser);chain.doFilter(req,res);}finally{CurrentUserContext.clear();}
    }
    public static String cookie(HttpServletRequest r,String name){if(r.getCookies()==null)return"";for(Cookie c:r.getCookies())if(name.equals(c.getName()))return c.getValue();return"";}
}
