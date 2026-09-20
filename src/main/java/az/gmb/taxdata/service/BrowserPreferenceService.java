package az.gmb.taxdata.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class BrowserPreferenceService {
    private static final Set<String> EXECUTABLE_HINTS = Set.of(
            "chrome.exe","msedge.exe","brave.exe","chromium.exe","vivaldi.exe","opera.exe","launcher.exe","browser.exe",
            "google-chrome","google-chrome-stable","microsoft-edge","microsoft-edge-stable",
            "brave-browser","chromium","chromium-browser","vivaldi","opera"
    );

    @Value("${app.etaxes.browser.executable-path:}") private String configuredExecutablePath;
    @Value("${app.etaxes.browser.server-bundled:false}") private boolean serverBundled;

    public BrowserChoice resolveForLaunch() {
        Path configured = automationExecutable(configuredExecutablePath);
        if (configured != null) return fromPath(configured, "ENV/config");

        if (!isServerMode()) {
            Path saved = automationExecutable(readSavedPath());
            if (saved != null) return fromPath(saved, "İstifadəçi seçimi");

            Path defaultBrowser = detectOsDefaultBrowser();
            if (defaultBrowser != null && automationCompatible(defaultBrowser)) return fromPath(defaultBrowser, "Sistemin default brauzeri");

            Path detected = detectInstalledBrowser();
            if (detected != null) return fromPath(detected, "Avtomatik aşkarlanıb");
            throw new IllegalStateException("Uyğun lokal brauzer tapılmadı. e‑Taxes bölməsində «Brauzer seç» düyməsi ilə Chrome, Edge, Brave, Chromium, Opera və ya Vivaldi proqram faylını/qovluğunu seçin.");
        }

        if (serverBundled) {
            // Docker/Railway image browser-i əvvəlcədən ehtiva edir. Tətbiq runtime-da browser endirmir.
            return new BrowserChoice("chromium", null, "Server Chromium (image daxilində; runtime download yoxdur)", true, "SERVER_BUNDLED");
        }

        Path serverBrowser=detectInstalledBrowser();
        if(serverBrowser!=null)return fromPath(serverBrowser,"Serverdə quraşdırılmış brauzer");
        throw new IllegalStateException("Server rejimində browser istifadəsi deaktivdir. Production-da e‑Taxes TaxData Local Agent vasitəsilə istifadəçinin kompüterində işləyir.");
    }

    public Map<String,Object> status() {
        boolean server = isServerMode();
        boolean chooser = !server && !GraphicsEnvironment.isHeadless();
        try {
            BrowserChoice c = resolveForLaunch();
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("mode", server ? "SERVER" : "LOCAL");
            m.put("server", server);
            m.put("chooserSupported", chooser);
            m.put("engine", c.engine());
            m.put("label", c.label());
            m.put("source", c.source());
            m.put("bundled", c.bundled());
            m.put("path", c.executable()==null ? "" : c.executable().toString());
            m.put("runtimeDownload", false);
            return m;
        } catch (RuntimeException e) {
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("mode", server ? "SERVER" : "LOCAL");
            m.put("server", server);
            m.put("chooserSupported", chooser);
            m.put("engine", "");
            m.put("label", "Brauzer seçilməyib");
            m.put("source", "NONE");
            m.put("bundled", false);
            m.put("path", "");
            m.put("runtimeDownload", false);
            m.put("error", e.getMessage()==null?"Brauzer tapılmadı.":e.getMessage());
            return m;
        }
    }

    public Map<String,Object> chooseNativeBrowser() {
        if (isServerMode() || GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Brauzer qovluğu seçimi yalnız proqram istifadəçinin öz kompüterində lokal işlədikdə mümkündür. Railway serveri istifadəçinin kompüterindəki brauzer fayllarına çıxış edə bilməz.");
        }
        AtomicReference<Path> selected = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle("e‑Taxes üçün brauzer proqramını və ya brauzer qovluğunu seçin");
                    chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                    chooser.setAcceptAllFileFilterUsed(true);
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        selected.set(chooser.getSelectedFile().toPath().toAbsolutePath().normalize());
                    }
                } catch (RuntimeException e) { error.set(e); }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Brauzer seçim pəncərəsi açıla bilmədi.",e);
        }
        if (error.get()!=null) throw error.get();
        if (selected.get()==null) return status();
        Path executable = resolveSelection(selected.get());
        if (executable==null) throw new IllegalArgumentException("Seçilmiş fayl/qovluqda dəstəklənən brauzer tapılmadı. Chrome, Edge, Brave, Chromium, Opera və ya Vivaldi seçin.");
        persist(executable);
        return status();
    }

    public Map<String,Object> clearSavedBrowser() {
        try { Files.deleteIfExists(preferenceFile()); } catch (IOException ignored) {}
        return status();
    }

    private boolean isServerMode() {
        if (System.getenv("RAILWAY_ENVIRONMENT") != null || System.getenv("RAILWAY_PROJECT_ID") != null) return true;
        if (Files.exists(Path.of("/.dockerenv"))) return true;
        return serverBundled && GraphicsEnvironment.isHeadless() && !isWindows();
    }

    /** OS üzrə default HTTPS browser executable-i tapmağa çalışır. */
    private Path detectOsDefaultBrowser(){
        try{
            if(isWindows())return detectWindowsDefaultBrowser();
            if(isMac())return detectMacDefaultBrowser();
            return detectLinuxDefaultBrowser();
        }catch(Exception ignored){return null;}
    }

    private Path detectWindowsDefaultBrowser(){
        String progId=runAndRead(List.of("reg","query","HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\https\\UserChoice","/v","ProgId"));
        Matcher m=Pattern.compile("ProgId\\s+REG_SZ\\s+([^\\r\\n]+)",Pattern.CASE_INSENSITIVE).matcher(progId);
        if(!m.find())return null;
        String id=m.group(1).trim();
        String cmd=runAndRead(List.of("reg","query","HKCR\\"+id+"\\shell\\open\\command","/ve"));
        return executableFromCommand(cmd);
    }

    private Path detectLinuxDefaultBrowser(){
        String desktop=runAndRead(List.of("sh","-lc","xdg-settings get default-web-browser 2>/dev/null || xdg-mime query default x-scheme-handler/https 2>/dev/null")).trim();
        if(desktop.isBlank())return null;
        for(Path base:List.of(Path.of(System.getProperty("user.home","."),".local/share/applications"),Path.of("/usr/share/applications"),Path.of("/usr/local/share/applications"))){
            Path f=base.resolve(desktop);
            if(!Files.isRegularFile(f))continue;
            try(Stream<String> lines=Files.lines(f,StandardCharsets.UTF_8)){
                Optional<String> exec=lines.filter(x->x.startsWith("Exec=")).findFirst();
                if(exec.isPresent()){
                    String token=exec.get().substring(5).trim().replaceAll("%[uUfFdDnNickvm]","").trim();
                    Path p=executableFromCommand(token);
                    if(p!=null)return p;
                    String first=firstCommandToken(token);Path w=which(first);if(w!=null)return w;
                }
            }catch(IOException ignored){}
        }
        String baseName=desktop.toLowerCase(Locale.ROOT).replace(".desktop","");
        for(String n:new String[]{baseName,"google-chrome","microsoft-edge","brave-browser","chromium"}){Path p=which(n);if(p!=null)return p;}
        return null;
    }

    private Path detectMacDefaultBrowser(){
        // macOS LaunchServices-dən default HTTPS tətbiqinin .app yolunu alır.
        String script="POSIX path of (path to default application for URL \"https://www.example.com\")";
        String app=runAndRead(List.of("osascript","-e",script)).trim();
        if(app.isBlank())return null;
        Path appPath=Path.of(app);
        Path info=appPath.resolve("Contents/Info.plist");
        String exe=runAndRead(List.of("/usr/libexec/PlistBuddy","-c","Print :CFBundleExecutable",info.toString())).trim();
        return validExecutable(appPath.resolve("Contents/MacOS").resolve(exe));
    }

    private Path executableFromCommand(String command){
        if(command==null||command.isBlank())return null;
        Matcher q=Pattern.compile("\\\"([^\\\"]+\\.(?:exe|app))\\\"",Pattern.CASE_INSENSITIVE).matcher(command);
        if(q.find()){
            Path p=validExecutable(q.group(1));if(p!=null)return p;
        }
        Matcher exe=Pattern.compile("([A-Za-z]:\\\\[^\\r\\n]+?\\.(?:exe))",Pattern.CASE_INSENSITIVE).matcher(command);
        if(exe.find()){
            String raw=exe.group(1).replaceAll("\\s+(?:--?|/).*$","").trim();Path p=validExecutable(raw);if(p!=null)return p;
        }
        String first=firstCommandToken(command);if(first.isBlank())return null;
        Path p=validExecutable(first);if(p!=null)return p;
        return which(first);
    }

    private String firstCommandToken(String cmd){
        String x=cmd==null?"":cmd.trim();
        if(x.startsWith("\"")){int e=x.indexOf('"',1);return e>1?x.substring(1,e):"";}
        int sp=x.indexOf(' ');return sp>0?x.substring(0,sp):x;
    }

    private String runAndRead(List<String> command){
        try{
            Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
            String s=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            p.waitFor();return s;
        }catch(Exception e){return "";}
    }

    private Path detectInstalledBrowser() {
        List<Path> candidates = new ArrayList<>();
        if (isWindows()) {
            String local = System.getenv("LOCALAPPDATA"), pf = System.getenv("PROGRAMFILES"), pf86=System.getenv("PROGRAMFILES(X86)");
            add(candidates, local, "Google/Chrome/Application/chrome.exe");
            add(candidates, pf, "Google/Chrome/Application/chrome.exe");
            add(candidates, pf86, "Google/Chrome/Application/chrome.exe");
            add(candidates, local, "Microsoft/Edge/Application/msedge.exe");
            add(candidates, pf86, "Microsoft/Edge/Application/msedge.exe");
            add(candidates, pf, "Microsoft/Edge/Application/msedge.exe");
            add(candidates, local, "BraveSoftware/Brave-Browser/Application/brave.exe");
            add(candidates, pf, "BraveSoftware/Brave-Browser/Application/brave.exe");
            add(candidates, pf86, "BraveSoftware/Brave-Browser/Application/brave.exe");
            add(candidates, local, "Vivaldi/Application/vivaldi.exe");
            add(candidates, pf, "Vivaldi/Application/vivaldi.exe");
            add(candidates, local, "Programs/Opera/opera.exe");
            add(candidates, local, "Programs/Opera/launcher.exe");
            add(candidates, local, "Yandex/YandexBrowser/Application/browser.exe");
            add(candidates, local, "Chromium/Application/chrome.exe");
        } else if (isMac()) {
            candidates.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            candidates.add(Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
            candidates.add(Path.of("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"));
            candidates.add(Path.of("/Applications/Vivaldi.app/Contents/MacOS/Vivaldi"));
            candidates.add(Path.of("/Applications/Opera.app/Contents/MacOS/Opera"));
            candidates.add(Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"));
        } else {
            String[] names={"google-chrome","google-chrome-stable","microsoft-edge","microsoft-edge-stable","brave-browser","chromium","chromium-browser","vivaldi","opera"};
            for(String name:names){Path p=which(name);if(p!=null)candidates.add(p);}
        }
        for(Path p:candidates) if(validExecutable(p)!=null) return p.toAbsolutePath().normalize();
        return null;
    }

    private Path resolveSelection(Path selection) {
        if (Files.isRegularFile(selection)) return automationExecutable(selection);
        if (!Files.isDirectory(selection)) return null;
        try (Stream<Path> stream = Files.find(selection, 6, (p,a) -> a.isRegularFile() && isBrowserExecutableName(p.getFileName().toString()))) {
            return stream.limit(5000).filter(p->automationExecutable(p)!=null).findFirst().map(p->p.toAbsolutePath().normalize()).orElse(null);
        } catch (IOException e) { return null; }
    }

    private BrowserChoice fromPath(Path p,String source) {
        String n=p.getFileName().toString().toLowerCase(Locale.ROOT);
        String engine="chromium";
        String label;
        if(n.contains("msedge"))label="Microsoft Edge";
        else if(n.contains("brave"))label="Brave";
        else if(n.contains("vivaldi"))label="Vivaldi";
        else if(n.contains("opera")||n.contains("launcher"))label="Opera";
        else if(n.equals("browser.exe"))label="Chromium əsaslı default brauzer";
        else if(n.contains("chromium"))label="Chromium";
        else label="Google Chrome / Chromium əsaslı brauzer";
        return new BrowserChoice(engine,p,label,false,source);
    }

    private boolean automationCompatible(Path p){
        if(p==null)return false;
        String x=p.getFileName().toString().toLowerCase(Locale.ROOT);
        String full=p.toString().toLowerCase(Locale.ROOT);
        return !x.contains("firefox") && !full.contains("mozilla firefox");
    }
    private Path automationExecutable(String raw){Path p=validExecutable(raw);return automationCompatible(p)?p:null;}
    private Path automationExecutable(Path p){p=validExecutable(p);return automationCompatible(p)?p:null;}

    private Path validExecutable(String raw) { if(raw==null||raw.isBlank())return null; try{return validExecutable(Path.of(raw.trim()));}catch(Exception e){return null;} }
    private Path validExecutable(Path p) {
        if(p==null)return null;
        try { p=p.toAbsolutePath().normalize(); return Files.isRegularFile(p) && (isWindows() || Files.isExecutable(p)) ? p : null; }
        catch(Exception e){return null;}
    }
    private boolean isBrowserExecutableName(String n){String x=n.toLowerCase(Locale.ROOT);return EXECUTABLE_HINTS.contains(x) || x.contains("chrome") || x.contains("edge") || x.contains("brave") || x.contains("vivaldi") || x.contains("opera") || x.contains("yandex");}
    private void add(List<Path> c,String root,String rel){if(root!=null&&!root.isBlank())c.add(Path.of(root).resolve(rel));}
    private Path which(String name){if(name==null||name.isBlank())return null;try{String safe=name.replace("'","'\\''");Process p=new ProcessBuilder("sh","-lc","command -v '"+safe+"'").redirectErrorStream(true).start();String s=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();return p.waitFor()==0&&!s.isBlank()?validExecutable(Path.of(s)):null;}catch(Exception e){return null;}}
    private boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}
    private boolean isMac(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("mac");}

    private String readSavedPath(){try{return Files.exists(preferenceFile())?Files.readString(preferenceFile(),StandardCharsets.UTF_8).trim():"";}catch(IOException e){return "";}}
    private void persist(Path p){try{Files.createDirectories(preferenceFile().getParent());Files.writeString(preferenceFile(),p.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);}catch(IOException e){throw new IllegalStateException("Brauzer seçimi yadda saxlanmadı.",e);}}
    private Path preferenceFile(){return Path.of(System.getProperty("user.home","."),"TaxData","browser-path.txt");}

    public record BrowserChoice(String engine, Path executable, String label, boolean bundled, String source) {}
}
