package az.gmb.taxdata.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.Comparator;

@Service
public class SessionWorkspaceCleanupService {
    private final StorageService storage;
    private final long maxAgeHours;
    public SessionWorkspaceCleanupService(StorageService storage,@Value("${app.storage.temp-session-max-hours:24}")long maxAgeHours){this.storage=storage;this.maxAgeHours=Math.max(1,maxAgeHours);}

    @PostConstruct public void startupCleanup(){cleanup();}

    @Scheduled(fixedDelayString="${app.storage.temp-session-cleanup-ms:3600000}")
    public void cleanup(){
        Path root=storage.getSessionWorkspacesDir(); if(!Files.isDirectory(root))return;
        Instant cutoff=Instant.now().minus(Duration.ofHours(maxAgeHours));
        try(var s=Files.list(root)){
            for(Path p:s.filter(Files::isDirectory).toList()){
                try{if(Files.getLastModifiedTime(p).toInstant().isBefore(cutoff))deleteRecursive(p);}catch(Exception ignored){}
            }
        }catch(Exception ignored){}
    }
    private void deleteRecursive(Path p)throws IOException{try(var s=Files.walk(p)){for(Path x:s.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(x);}}
}
