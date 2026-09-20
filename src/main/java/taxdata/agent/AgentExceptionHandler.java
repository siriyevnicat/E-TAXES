package taxdata.agent;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.concurrent.CompletionException;

@RestControllerAdvice
public class AgentExceptionHandler {
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Map<String,Object>> handle(Throwable error){
        Throwable x=error;
        while((x instanceof CompletionException) && x.getCause()!=null)x=x.getCause();
        String m=x.getMessage();if(m==null||m.isBlank())m=x.getClass().getSimpleName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error",m.replaceAll("\\s+"," ").trim()));
    }
}
