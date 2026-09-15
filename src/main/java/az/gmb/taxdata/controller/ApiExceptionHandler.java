package az.gmb.taxdata.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, NoSuchFileException.class})
    public ResponseEntity<Map<String,Object>> badRequest(Exception e){
        return ResponseEntity.badRequest().body(Map.of("time",Instant.now().toString(),"error",safe(e.getMessage(),"Sorğu düzgün deyil.")));
    }
    @ExceptionHandler(AgentPackageUnavailableException.class)
    public ResponseEntity<Map<String,Object>> agentUnavailable(AgentPackageUnavailableException e){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("time",Instant.now().toString(),"error",safe(e.getMessage(),"TaxData Agent server paketi hazır deyil."),"code","AGENT_PACKAGE_UNAVAILABLE"));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String,Object>> notFound(NoResourceFoundException e){
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("time",Instant.now().toString(),"error","Resurs tapılmadı."));
    }
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String,Object>> unauthorized(SecurityException e){
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("time",Instant.now().toString(),"error",safe(e.getMessage(),"Giriş tələb olunur.")));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> general(Exception e){
        String id=UUID.randomUUID().toString();
        log.error("Unhandled API error id={}",id,e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("time",Instant.now().toString(),"error","Server xətası baş verdi.","errorId",id));
    }
    private String safe(String v,String fallback){return v==null||v.isBlank()?fallback:v;}
}
