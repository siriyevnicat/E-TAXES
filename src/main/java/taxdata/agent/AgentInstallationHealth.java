package taxdata.agent;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Component
public class AgentInstallationHealth {
    public static final String VERSION="6.8.6";

    public Health inspect(){
        Map<String,Boolean> c=new LinkedHashMap<>();
        List<String> issues=new ArrayList<>();
        Path root=taxDataRoot(),agent=root.resolve("Agent"),recovery=root.resolve("Recovery");
        Path jar=agent.resolve("taxdata-agent.jar"),start=agent.resolve("start-agent.ps1"),marker=agent.resolve(".install-complete");
        Path watchdog=root.resolve("watch-agent.ps1"),repairCmd=recovery.resolve("TaxData-Agent-Repair.cmd");
        Map<String,String> p=new LinkedHashMap<>();
        boolean markerOk=false,versionOk=false,hashOk=false,javaPathOk=false,originOk=false;
        try{
            if(Files.isRegularFile(marker)){
                for(String line:Files.readAllLines(marker,StandardCharsets.UTF_8)){int i=line.indexOf('=');if(i>0){String key=line.substring(0,i).replace("\uFEFF","").trim();p.put(key,line.substring(i+1).trim());}}
                markerOk=true;
                versionOk=VERSION.equals(p.getOrDefault("version",""));
                String expectedHash=p.getOrDefault("jarSha256","").trim();
                hashOk=!expectedHash.isBlank()&&Files.isRegularFile(jar)&&Files.size(jar)>100_000&&expectedHash.equalsIgnoreCase(sha256(jar));
                String jp=p.getOrDefault("javaPath","").trim();
                javaPathOk=!jp.isBlank()&&Files.isRegularFile(Path.of(jp));
                String expectedOrigin=p.getOrDefault("origin","").trim();
                String actualOrigin=System.getProperty("taxdata.agent.allowed-origin","").trim();
                originOk=!expectedOrigin.isBlank()&&!actualOrigin.isBlank()&&expectedOrigin.equalsIgnoreCase(actualOrigin);
            }
        }catch(Exception ignored){}
        boolean java21=Runtime.version().feature()>=21;
        boolean jarOk=Files.isRegularFile(jar)&&safeSize(jar)>100_000;
        boolean startOk=Files.isRegularFile(start)&&safeSize(start)>80;
        boolean watchdogOk=Files.isRegularFile(watchdog)&&safeSize(watchdog)>500;
        boolean repairOk=Files.isRegularFile(repairCmd)&&safeSize(repairCmd)>500;
        c.put("marker",markerOk);c.put("version",versionOk);c.put("jar",jarOk);c.put("jarIntegrity",hashOk);c.put("java21",java21);c.put("javaPath",javaPathOk);c.put("startScript",startOk);c.put("watchdog",watchdogOk);c.put("repairInstaller",repairOk);c.put("origin",originOk);
        c.forEach((k,v)->{if(!v)issues.add(k);});
        boolean complete=c.values().stream().allMatch(Boolean::booleanValue);
        return new Health(complete,c,List.copyOf(issues));
    }

    private Path taxDataRoot(){String local=System.getenv("LOCALAPPDATA");if(local==null||local.isBlank())local=System.getProperty("user.home",".");return Path.of(local,"TaxData").toAbsolutePath().normalize();}
    private long safeSize(Path p){try{return Files.size(p);}catch(Exception e){return 0;}}
    private String sha256(Path p)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[]b=new byte[8192];for(int n;(n=in.read(b))>0;)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}
    public record Health(boolean complete,Map<String,Boolean> components,List<String> issues){}
}
