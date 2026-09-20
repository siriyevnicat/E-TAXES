package az.gmb.taxdata.controller;

import az.gmb.taxdata.service.EtaxesAgentGrantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import taxdata.agent.AgentInstallationHealth;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@RestController
@RequestMapping("/agent")
public class AgentDistributionController {
    private static final String JAR_NAME="taxdata-agent.jar";
    private final EtaxesAgentGrantService grants;
    public AgentDistributionController(EtaxesAgentGrantService grants){this.grants=grants;}

    @PostMapping("/grants/consume")
    public ResponseEntity<?> consumeGrant(@RequestBody Map<String,String> body){
        boolean ok=grants.consume(body==null?"":body.get("grant"),body==null?"":body.get("tin"),body==null?"":body.get("phone"),body==null?"":body.get("userId"));
        return ok?ResponseEntity.ok(Map.of("allowed",true)):ResponseEntity.status(403).body(Map.of("allowed",false,"error","TaxData lokal agent icazəsi etibarsızdır və ya vaxtı bitib. Yeni icazə alınmalıdır."));
    }
    @PostMapping("/grants/complete")
    public ResponseEntity<?> completeGrant(@RequestBody Map<String,String> body){
        boolean ok=grants.complete(body==null?"":body.get("grant"),body==null?"":body.get("tin"),body==null?"":body.get("phone"),body==null?"":body.get("userId"));
        return ok?ResponseEntity.ok(Map.of("completed",true)):ResponseEntity.status(403).body(Map.of("completed",false,"error","TaxData lokal agent icazəsi tamamlanmadı."));
    }

    @GetMapping("/taxdata-agent.jar")
    public ResponseEntity<Resource> binary() throws Exception{
        Path p=agentJar();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+JAR_NAME)
                .header(HttpHeaders.CACHE_CONTROL,"no-store, max-age=0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(Files.size(p)).body(new FileSystemResource(p));
    }

    @GetMapping("/manifest")
    public Map<String,Object> manifest() throws Exception{
        Path p=agentJar();return Map.of("agent","TaxData Local Agent","version",AgentInstallationHealth.VERSION,"jarSize",Files.size(p),"jarSha256",sha256(p),"setupVersion",AgentInstallationHealth.VERSION);
    }

    @GetMapping("/health")
    public Map<String,Object> health() throws Exception{
        Path p=agentJar();
        return Map.of("status","UP","agent","TaxData Local Agent","version",AgentInstallationHealth.VERSION,"jarSize",Files.size(p));
    }

    // Extensionless endpoint is the production path. /setup.ps1 remains only as a compatibility alias.
    @GetMapping(value={"/bootstrap","/setup.ps1"},produces="text/plain;charset=UTF-8")
    public ResponseEntity<byte[]> setupPowerShell(HttpServletRequest req) throws Exception{
        Path p=agentJar();String base=publicBase(req);
        String text=template("/agent/setup.ps1")
                .replace("__TAXDATA_BASE__",base).replace("__TAXDATA_ORIGIN__",base)
                .replace("__AGENT_VERSION__",AgentInstallationHealth.VERSION).replace("__JAR_SHA256__",sha256(p)).replace("__JAR_SIZE__",String.valueOf(Files.size(p)));
        return binaryDownload(utf8Bom(text),"TaxData-Agent-Bootstrap.ps1",MediaType.parseMediaType("text/plain;charset=UTF-8"));
    }

    // Extensionless endpoint avoids proxy/WAF issues with executable script extensions.
    @GetMapping(value={"/installer","/setup.cmd"},produces="application/octet-stream")
    public ResponseEntity<byte[]> setupCmd(HttpServletRequest req) throws Exception{
        String base=publicBase(req);
        String text=template("/agent/setup.cmd").replace("__TAXDATA_BASE__",base).replace("__AGENT_VERSION__",AgentInstallationHealth.VERSION);
        return binaryDownload(text.getBytes(StandardCharsets.UTF_8),"TaxData-Agent-Setup.cmd",MediaType.APPLICATION_OCTET_STREAM);
    }

    private String template(String name){try(InputStream in=AgentDistributionController.class.getResourceAsStream(name)){if(in==null)throw new AgentPackageUnavailableException("TaxData Agent quraşdırma resursu server build-inə daxil edilməyib: "+name);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(AgentPackageUnavailableException e){throw e;}catch(Exception e){throw new AgentPackageUnavailableException("TaxData Agent quraşdırma resursunu oxumaq mümkün olmadı: "+name,e);}}

    /**
     * Prefer an external agent file when Docker has copied it to /app/agent.
     * If the platform only deploys taxdata-server.jar (for example a Nixpacks/Railway
     * build), fall back to the agent executable embedded inside the server Boot JAR.
     * The embedded resource is extracted once to the JVM temp directory so downloads
     * and SHA-256 checks can stream from disk instead of allocating the whole JAR.
     */
    private Path agentJar(){
        try{
            for(Path p:agentJarCandidates()){
                if(p!=null&&Files.isRegularFile(p)&&Files.size(p)>100_000)return p;
            }
            Path cache=Path.of(System.getProperty("java.io.tmpdir"),"taxdata-agent-dist",JAR_NAME);
            if(Files.isRegularFile(cache)&&Files.size(cache)>100_000)return cache;
            synchronized(AgentDistributionController.class){
                if(Files.isRegularFile(cache)&&Files.size(cache)>100_000)return cache;
                // First try the normal classpath resource. Some executable-JAR loaders do not expose
                // nested *.jar resources through getResourceAsStream(), so a direct ZIP fallback follows.
                try(InputStream in=AgentDistributionController.class.getResourceAsStream("/agent-dist/"+JAR_NAME)){
                    if(in!=null)return extractToCache(in,cache);
                }
                Path fromArchive=extractFromRunningServerJar(cache);
                if(fromArchive!=null)return fromArchive;
                throw new AgentPackageUnavailableException("TaxData Agent artefaktı tapılmadı. Lokal 8080 üçün serveri gradlew bootRun / START_WINDOWS.bat ilə başladın; bu əməliyyat agentBootJar faylını əvvəlcədən yaradır. Production-da isə agent artefaktı server build-inə daxil edilməlidir.");
            }
        }catch(AgentPackageUnavailableException e){throw e;}
        catch(Exception e){throw new AgentPackageUnavailableException("TaxData Agent server paketini hazırlamaq mümkün olmadı.",e);}
    }

    private Set<Path> agentJarCandidates(){
        LinkedHashSet<Path> out=new LinkedHashSet<>();
        addPathCandidate(out,System.getProperty("taxdata.agent.jar"));
        addPathCandidate(out,"/app/agent/taxdata-agent.jar");
        String userDir=System.getProperty("user.dir","");
        if(!userDir.isBlank())addPathCandidate(out,Path.of(userDir,"build","libs",JAR_NAME).toString());
        addPathCandidate(out,Path.of("build","libs",JAR_NAME).toAbsolutePath().toString());
        return out;
    }

    private void addPathCandidate(Set<Path> out,String raw){
        if(raw==null||raw.isBlank())return;
        try{out.add(Path.of(raw).toAbsolutePath().normalize());}catch(Exception ignored){}
    }

    private Path extractToCache(InputStream in,Path cache) throws Exception{
        Files.createDirectories(cache.getParent());
        Path tmp=cache.resolveSibling(JAR_NAME+".part");
        Files.copy(in,tmp,StandardCopyOption.REPLACE_EXISTING);
        if(Files.size(tmp)<=100_000){Files.deleteIfExists(tmp);throw new AgentPackageUnavailableException("TaxData Agent server paketində natamamdır.");}
        try{Files.move(tmp,cache,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,cache,StandardCopyOption.REPLACE_EXISTING);}
        return cache;
    }

    /**
     * Railway may launch only taxdata-server.jar. In that case open the running server JAR
     * as a ZIP and extract the embedded agent entry directly, bypassing classloader quirks.
     */
    private Path extractFromRunningServerJar(Path cache){
        for(Path outer:runningJarCandidates()){
            try{
                if(!Files.isRegularFile(outer)||Files.size(outer)<=100_000)continue;
                try(ZipFile zip=new ZipFile(outer.toFile())){
                    ZipEntry entry=zip.getEntry("BOOT-INF/classes/agent-dist/"+JAR_NAME);
                    if(entry==null)entry=zip.getEntry("agent-dist/"+JAR_NAME);
                    if(entry==null||entry.getSize()==0)continue;
                    try(InputStream in=zip.getInputStream(entry)){return extractToCache(in,cache);}
                }
            }catch(Exception ignored){}
        }
        return null;
    }

    private Set<Path> runningJarCandidates(){
        LinkedHashSet<Path> out=new LinkedHashSet<>();
        addJarCandidate(out,System.getProperty("taxdata.server.jar"));
        String cp=System.getProperty("java.class.path","");
        for(String part:cp.split(java.io.File.pathSeparator))addJarCandidate(out,part);
        String cmd=System.getProperty("sun.java.command","");
        if(!cmd.isBlank())addJarCandidate(out,cmd.trim().split("\\s+",2)[0]);
        try{
            String raw=AgentDistributionController.class.getProtectionDomain().getCodeSource().getLocation().toString();
            int end=raw.toLowerCase().indexOf(".jar");
            if(end>=0){
                String x=raw.substring(0,end+4).replaceFirst("^jar:nested:","").replaceFirst("^jar:file:","").replaceFirst("^file:","");
                addJarCandidate(out,URLDecoder.decode(x,StandardCharsets.UTF_8));
            }
        }catch(Exception ignored){}
        return out;
    }

    private void addJarCandidate(Set<Path> out,String raw){
        if(raw==null||raw.isBlank())return;
        try{
            String x=raw.trim();
            if((x.startsWith("\"")&&x.endsWith("\""))||(x.startsWith("'")&&x.endsWith("'")))x=x.substring(1,x.length()-1);
            Path p=Path.of(x).toAbsolutePath().normalize();
            if(p.getFileName()!=null&&p.getFileName().toString().toLowerCase().endsWith(".jar"))out.add(p);
        }catch(Exception ignored){}
    }

    private String sha256(Path p)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[]b=new byte[8192];for(int n;(n=in.read(b))>0;)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}

    private String normalizeOrigin(String raw){
        if(raw==null||raw.isBlank())return null;
        try{
            URI u=URI.create(raw.trim());
            String scheme=u.getScheme();
            if(scheme==null||(!scheme.equalsIgnoreCase("https")&&!scheme.equalsIgnoreCase("http")))return null;
            if(u.getHost()==null||u.getHost().isBlank()||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)return null;
            String path=u.getPath();if(path!=null&&!path.isBlank()&&!"/".equals(path))return null;
            return new URI(scheme.toLowerCase(),null,u.getHost(),u.getPort(),null,null,null).toString();
        }catch(Exception ignored){return null;}
    }
    private String publicBase(HttpServletRequest r){
        String proto=first(r.getHeader("X-Forwarded-Proto"),r.getScheme());
        String host=first(r.getHeader("X-Forwarded-Host"),r.getHeader("Host"));
        if(host==null||host.isBlank())host="localhost:8080";
        String candidate=(proto==null||proto.isBlank()?"https":proto)+"://"+host;
        String safe=normalizeOrigin(candidate);
        return safe!=null?safe:(r.getScheme()+"://"+r.getServerName()+(r.getServerPort()>0&&r.getServerPort()!=80&&r.getServerPort()!=443?":"+r.getServerPort():""));
    }
    private String first(String v,String fallback){if(v==null||v.isBlank())return fallback;int i=v.indexOf(',');return (i<0?v:v.substring(0,i)).trim().replace("\"","");}

    private byte[] utf8Bom(String value){
        byte[] text=value.getBytes(StandardCharsets.UTF_8);
        byte[] out=new byte[text.length+3];
        out[0]=(byte)0xEF;out[1]=(byte)0xBB;out[2]=(byte)0xBF;
        System.arraycopy(text,0,out,3,text.length);
        return out;
    }
    private ResponseEntity<byte[]> textDownload(String value,String file){return binaryDownload(value.getBytes(StandardCharsets.UTF_8),file,MediaType.parseMediaType("text/plain;charset=UTF-8"));}
    private ResponseEntity<byte[]> binaryDownload(byte[] bytes,String file,MediaType type){String fn=URLEncoder.encode(file,StandardCharsets.UTF_8).replace("+","%20");return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+fn).header(HttpHeaders.CACHE_CONTROL,"no-store, max-age=0").contentType(type).contentLength(bytes.length).body(bytes);}
}
