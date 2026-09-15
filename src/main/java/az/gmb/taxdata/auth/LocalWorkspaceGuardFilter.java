package az.gmb.taxdata.auth;

import az.gmb.taxdata.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

@Component
@Order(3)
public class LocalWorkspaceGuardFilter extends OncePerRequestFilter {
    private final StorageService storage; private final ObjectMapper mapper;
    public LocalWorkspaceGuardFilter(StorageService storage,ObjectMapper mapper){this.storage=storage;this.mapper=mapper;}
    @Override protected boolean shouldNotFilter(HttpServletRequest r){String p=r.getRequestURI();return !p.startsWith("/api/")||p.startsWith("/api/auth/")||p.startsWith("/api/admin/")||p.startsWith("/api/local-workspace/")||p.startsWith("/api/cloud-workspace/");}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        AuthenticatedUser u=CurrentUserContext.get();
        if(u==null){chain.doFilter(req,res);return;}
        String ws=u.workspaceId();
        boolean ready=storage.isSessionWorkspace(ws)&&storage.workspaceExists(ws)&&Files.isRegularFile(storage.getWorkspaceDir(ws).resolve("workspace-state.json"));
        if(!ready){res.setStatus(428);res.setContentType("application/json;charset=UTF-8");mapper.writeValue(res.getWriter(),Map.of("error","Bu login sessiyasının müvəqqəti workspace-i yoxdur. Lokal backup bu kompüterdən bərpa edilməlidir.","code","LOCAL_WORKSPACE_REQUIRED"));return;}
        storage.touchWorkspace(ws);chain.doFilter(req,res);
    }
}
