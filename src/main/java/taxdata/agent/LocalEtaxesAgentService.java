package taxdata.agent;

import az.gmb.taxdata.model.EtaxesExportRequest;
import az.gmb.taxdata.model.EtaxesExportStatus;
import az.gmb.taxdata.service.BrowserPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Service
public class LocalEtaxesAgentService {
    private static final String LOGIN_URL="https://new.e-taxes.gov.az/eportal/login/asan";
    private static final String API="https://new.e-taxes.gov.az/api/po/invoice/public/v2/invoice/";
    private static final String UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    private static final List<String> STATUSES=List.of("approved","onApproval","updateApproval","updateRequested","cancelRequested","approvedBySystem","onApprovalEdited","deactivated","cancelationRefused","correctionRefused");
    private static final List<String> TYPES=List.of("current","corrected");
    private static final List<String> KINDS=List.of("defaultInvoice","agent","resale","recycling","taxCodex163","taxCodex177_5","returnInvoice","returnByAgent","returnRecycled","exportNoteInvoice","exciseGoodsTransfer","advanceInvoice");
    private static final Map<String,String> STATUS_MAPPING=Map.ofEntries(
            Map.entry("approvedBySystem","Sistem tərəfindən təsdiqləndi"),Map.entry("approved","Təsdiqləndi"),
            Map.entry("cancelationRefused","Ləğvdən imtina / Təsdiqləndi"),Map.entry("correctionRefused","Düzəlişdən imtina / Təsdiqləndi"),
            Map.entry("cancelRequested","Ləğvə göndərildi"),Map.entry("onApproval","Təsdiq gözləyən"),Map.entry("updateRequested","Düzəlişə qaytarıldı"));
    private static final String[] HEADERS={"Qaimənin seriya nömrəsi","Qaimənin statusu","Invoys kodu","Qəbul edən vöen","Qəbul edən adı","Göndərən vöen","Göndərən adı","Malın (işin, xidmətin) adı","Malın (işin, xidmətin) kodu","Əmtəənin qlobal identifikasiya nömrəsi (GTIN)","Ölçü vahidi","Miqdarı, həcmi","Vahidinin satış qiyməti (manatla)","Cəmi məbləği(manatla) 6*7","Cəmi məbləği(manatla) 6*7","Aksiz dərəcəsi (%)","Aksiz məbləği (manatla)","ƏDV-yə 18 faiz dərəcə ilə cəlb edilən","ƏDV-dən azad olunan","ƏDV-yə \"0\" dərəcə ilə cəlb edilən","ƏDV məbləği 11*0.18","ƏDV-yə cəlb edilməyən","Yol vergisi (manatla)","Yekun məbləğ 11+16+17","Tarix","Saat","Qeyd","Qeyd 2"};

    private final BrowserPreferenceService browsers;
    private final ObjectMapper mapper;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String,Task> tasks=new ConcurrentHashMap<>();
    private volatile HttpClient http;

    @Value("${app.etaxes.otp-timeout-seconds:180}") private int otpTimeoutSeconds;
    @Value("${app.etaxes.navigation-timeout-seconds:60}") private int navigationTimeoutSeconds;
    @Value("${app.etaxes.element-timeout-seconds:60}") private int elementTimeoutSeconds;
    @Value("${app.etaxes.http-timeout-seconds:60}") private int httpTimeoutSeconds;
    @Value("${taxdata.agent.proxy.server:}") private String proxyServer;
    @Value("${taxdata.agent.proxy.username:}") private String proxyUsername;
    @Value("${taxdata.agent.proxy.password:}") private String proxyPassword;
    @Value("${taxdata.agent.allowed-origin:}") private String serverOrigin;

    public LocalEtaxesAgentService(BrowserPreferenceService browsers,ObjectMapper mapper){this.browsers=browsers;this.mapper=mapper;}

    public EtaxesExportStatus start(EtaxesExportRequest request){
        validate(request);validateGrant(request);cleanup();
        String id="LOCAL_ETX_"+UUID.randomUUID();EtaxesExportStatus s=new EtaxesExportStatus();s.setTaskId(id);s.setStatus("QUEUED");s.setPhase("Hazırlanır");s.setProgress(1);s.setMessage("Lokal e-Taxes agent hazırlanır...");s.setStartedAt(Instant.now());s.setImportIntoRegistry(request.isImportIntoRegistry());
        Task t=new Task(request,s);tasks.put(id,t);executor.submit(()->run(t));return snapshot(t);
    }
    public EtaxesExportStatus status(String id){Task t=require(id);return snapshot(t);}
    public boolean busy(){
        cleanup();
        return tasks.values().stream().anyMatch(t->{
            String s=t.status.getStatus();
            return s!=null&&!Set.of("COMPLETED","FAILED","CANCELLED").contains(s);
        });
    }
    public void cancel(String id){Task t=require(id);t.cancelled.set(true);log(t,"Dayandırma sorğusu göndərildi.");}
    public Download download(String id)throws Exception{Task t=require(id);if(t.file==null||!Files.isRegularFile(t.file))throw new IllegalStateException("Excel faylı hələ hazır deyil.");return new Download(t.file.getFileName().toString(),Files.readAllBytes(t.file));}

    private void run(Task t){
        try(Playwright pw=Playwright.create()){
            check(t);update(t,"LOGIN","e-Taxes istifadəçinin kompüterində açılır...",5);
            BrowserPreferenceService.BrowserChoice choice=browsers.resolveForLaunch();
            if(choice.executable()==null)throw new IllegalStateException("Lokal browser seçilməyib.");
            BrowserType type=pw.chromium();
            BrowserType.LaunchOptions launch=new BrowserType.LaunchOptions().setHeadless(true).setExecutablePath(choice.executable());
            launch.setArgs(List.of("--disable-dev-shm-usage"));
            configureProxy(launch,t);
            log(t,"Brauzer: "+choice.label()+" · "+choice.executable());
            Browser browser;
            try{browser=type.launch(launch);}catch(PlaywrightException e){
                throw new IllegalStateException("Seçilmiş brauzer açıla bilmədi. «Brauzer seç» ilə Chrome, Edge, Brave, Opera, Vivaldi və ya Chromium seçin.",e);
            }
            try(browser;BrowserContext context=browser.newContext(new Browser.NewContextOptions().setLocale("az-AZ").setTimezoneId("Asia/Baku").setViewportSize(1365,768).setExtraHTTPHeaders(Map.of("Accept-Language","az-AZ,az;q=0.9,en-US;q=0.8,en;q=0.7")))){
                Page page=context.newPage();LoginSession loginSession=login(page,t);page=loginSession.page();String token=loginSession.token();completeGrant(t.request);AtomicReference<String> tokenRef=new AtomicReference<>(token);
                check(t);update(t,"LIST","Qaimə siyahısı lokal şəbəkədən alınır...",22);List<String> ids=fetchIds(page,tokenRef,t);t.status.setInvoiceCount(ids.size());if(ids.isEmpty()){finishEmpty(t);return;}
                update(t,"DETAILS","Qaimələrin detalları alınır...",35);List<ExportRow> rows=fetchDetails(page,tokenRef,ids,t);if(rows.isEmpty())throw new IllegalStateException("Qaimə detalları alınmadı.");t.status.setRowCount(rows.size());
                update(t,"EXCEL","Excel faylı hazırlanır...",93);byte[] excel=buildExcel(rows);Path dir=exportDir();Files.createDirectories(dir);String fn="invoices_"+DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now())+".xlsx";Path out=unique(dir.resolve(fn));Files.write(out,excel,StandardOpenOption.CREATE_NEW);t.file=out;
                t.status.setFileName(out.getFileName().toString());t.status.setDownloadUrl("http://127.0.0.1:47631/api/tasks/"+t.status.getTaskId()+"/download");t.status.setDownloadReady(true);t.status.setStatus("COMPLETED");t.status.setPhase("Hazırdır");t.status.setProgress(100);t.status.setMessage("e-Taxes qaimələri istifadəçinin kompüterində uğurla hazırlandı.");t.status.setFinishedAt(Instant.now());log(t,"Excel hazırdır: "+out);
            }
        }catch(CancellationException e){t.status.setStatus("CANCELLED");t.status.setPhase("Dayandırıldı");t.status.setMessage("Proses dayandırıldı.");t.status.setFinishedAt(Instant.now());}
        catch(Throwable e){t.status.setStatus("FAILED");t.status.setPhase("Xəta");t.status.setMessage(cleanError(e));t.status.setFinishedAt(Instant.now());log(t,"Xəta: "+cleanError(e));}
        finally{clearSensitive(t.request);}
    }

    private LoginSession login(Page page,Task t){
        double nav=Math.max(15,navigationTimeoutSeconds)*1000.0, action=Math.max(15,elementTimeoutSeconds)*1000.0, auth=Math.max(30,otpTimeoutSeconds)*1000.0;page.setDefaultTimeout(action);page.setDefaultNavigationTimeout(nav);
        update(t,"LOGIN","ASAN İmza giriş səhifəsinə qoşulur...",6);Response response=null;try{response=page.navigate(LOGIN_URL,new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(nav));}catch(PlaywrightException e){log(t,"Navigation xəbərdarlığı: "+shortMsg(e));}
        if(response!=null&&(response.status()==401||response.status()==403))throw new IllegalStateException("e-Taxes giriş səhifəsi HTTP "+response.status()+" qaytardı. Sorğu bu kompüterin internetindən gedir; VPN/proxy varsa söndürüb yenidən yoxlayın.");
        check(t);update(t,"LOGIN_FORM","ASAN İmza giriş forması hazırlanır...",8);
        LoginTarget target=waitForLoginTarget(page,t,action);
        if(target==null){saveLoginDiagnostic(page,t);throw new IllegalStateException("ASAN İmza giriş forması e-Taxes səhifəsində avtomatik tapılmadı. TaxData diaqnostikası saxlanıldı; açılan e-Taxes pəncərəsində ASAN İmza bölməsinin tam yükləndiyini yoxlayın.");}
        page=target.page();
        try{
            target.phone().fill(t.request.getPhoneNumber().trim());
            target.user().fill(t.request.getUserId().trim());
            if(target.sign()!=null)target.sign().click(new Locator.ClickOptions().setNoWaitAfter(true).setTimeout(action));else target.user().press("Enter");
        }catch(PlaywrightException e){saveLoginDiagnostic(page,t);throw new IllegalStateException("ASAN İmza giriş forması tapıldı, amma doldurmaq və ya göndərmək mümkün olmadı. Açılan e-Taxes pəncərəsini yoxlayın.",e);}
        update(t,"OTP","ASAN İmza təsdiqini telefonda tamamlayın...",12);String tin=t.request.getTin().trim();
        try{page.waitForFunction("tin=>{const x=localStorage.getItem('aztax-jwt');if(x&&x!=='null'&&x!=='undefined')return true;return !!tin&&(((document.body&&document.body.innerText)||'').includes(tin));}",tin,new Page.WaitForFunctionOptions().setTimeout(auth));}
        catch(PlaywrightException e){String late=readJwt(page);if(!late.isBlank())return new LoginSession(late,page);throw authTimeout(page,tin,e);}
        check(t);String token=readJwt(page);if(!token.isBlank()){log(t,"e-Taxes sessiyası açıldı.");return new LoginSession(token,page);}
        update(t,"TIN","VÖEN seçilir...",17);Locator tinLoc=findTin(page,tin);if(tinLoc==null||tinLoc.count()==0)throw new IllegalStateException("Daxil etdiyiniz VÖEN e-Taxes sertifikat siyahısında tapılmadı.");clickTin(tinLoc.first());
        try{page.waitForFunction("()=>{const x=localStorage.getItem('aztax-jwt');return !!x&&x!=='null'&&x!=='undefined';}",null,new Page.WaitForFunctionOptions().setTimeout(auth));}catch(PlaywrightException e){throw new IllegalStateException("VÖEN seçildi, amma e-Taxes sessiyası tamamlanmadı.",e);}token=readJwt(page);if(token.isBlank())throw new IllegalStateException("e-Taxes sessiya tokeni alınmadı.");return new LoginSession(token,page);
    }

    private LoginTarget waitForLoginTarget(Page initial,Task t,double timeoutMs){
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos((long)Math.max(timeoutMs,30000));boolean entryClicked=false;int pass=0;
        while(System.nanoTime()<deadline){
            check(t);pass++;
            List<Page> pages=new ArrayList<>(initial.context().pages());
            for(int pi=pages.size()-1;pi>=0;pi--){Page p=pages.get(pi);if(p.isClosed())continue;for(Frame f:p.frames()){LoginTarget x=detectLoginTarget(p,f);if(x!=null){log(t,"ASAN İmza forması tapıldı · URL: "+safeUrl(p)+" · frame: "+safeFrameUrl(f));return x;}}}
            if(!entryClicked){entryClicked=clickAsanEntry(pages,t);if(entryClicked)log(t,"ASAN İmza giriş seçimi avtomatik açıldı.");}
            if(pass%10==0)log(t,"ASAN İmza forması gözlənilir... "+Math.max(1,(deadline-System.nanoTime())/1_000_000_000L)+" san.");
            try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}
        }
        logLoginDiagnostics(initial,t);return null;
    }

    private LoginTarget detectLoginTarget(Page page,Frame frame){
        try{
            Locator phone=firstVisible(frame,"input#phone, input[name='phone'], input[id*='phone' i], input[name*='phone' i], input[id*='mobile' i], input[name*='mobile' i], input[id*='msisdn' i], input[name*='msisdn' i], input[autocomplete='tel'], input[type='tel'], input[placeholder*='telefon' i], input[placeholder*='mobil' i], input[placeholder*='nömr' i]");
            if(phone==null)phone=heuristicInput(frame,true);
            Locator user=firstVisible(frame,"input#userId, input[name='userId'], input[id*='userId' i], input[name*='userId' i], input[id*='userid' i], input[name*='userid' i], input[autocomplete='username'], input[placeholder*='istifadəçi' i], input[placeholder*='user' i], input[placeholder*='ID' i]");
            if(user==null)user=heuristicInput(frame,false);
            if(phone==null||user==null)return null;
            Locator sign=firstVisible(frame,"#loginPageSignInButton, button[type='submit'], input[type='submit'], button:has-text('Daxil ol'), button:has-text('Giriş'), button:has-text('Davam et'), button:has-text('Təsdiq et')");
            return new LoginTarget(page,frame,phone,user,sign);
        }catch(Exception ignored){return null;}
    }

    private Locator firstVisible(Frame frame,String selector){try{Locator all=frame.locator(selector);int n=Math.min(all.count(),20);for(int i=0;i<n;i++){Locator x=all.nth(i);if(x.isVisible()&&x.isEnabled())return x;}}catch(Exception ignored){}return null;}

    private Locator heuristicInput(Frame frame,boolean phone){
        try{Locator all=frame.locator("input:not([type='hidden']):not([disabled])");int n=Math.min(all.count(),30),best=-1;Locator pick=null;for(int i=0;i<n;i++){Locator x=all.nth(i);if(!x.isVisible())continue;String meta=(attr(x,"id")+" "+attr(x,"name")+" "+attr(x,"placeholder")+" "+attr(x,"autocomplete")+" "+attr(x,"type")+" "+attr(x,"aria-label")).toLowerCase(Locale.ROOT);int score=0;if(phone){if(meta.contains("phone")||meta.contains("mobile")||meta.contains("msisdn")||meta.contains("telefon")||meta.contains("mobil")||meta.contains("nömr")||meta.contains("nomr"))score+=8;if(meta.contains("tel"))score+=4;}else{if(meta.contains("userid")||meta.contains("user id")||meta.contains("istifadəçi")||meta.contains("istifadeci"))score+=10;if(meta.contains("username"))score+=8;if(meta.contains("user"))score+=5;if(meta.contains(" id")||meta.startsWith("id "))score+=2;}if(meta.contains("password")||meta.contains("search"))score-=10;if(score>best){best=score;pick=x;}}return best>=4?pick:null;}catch(Exception ignored){return null;}
    }

    private String attr(Locator x,String name){try{String v=x.getAttribute(name);return v==null?"":v;}catch(Exception ignored){return "";}}

    private boolean clickAsanEntry(List<Page> pages,Task t){
        String selector="a[href*='/login/asan'], button:has-text('ASAN İmza'), a:has-text('ASAN İmza'), [role='button']:has-text('ASAN İmza'), button:has-text('ASAN Imza'), a:has-text('ASAN Imza')";
        for(Page p:pages){if(p.isClosed())continue;for(Frame f:p.frames()){try{Locator all=f.locator(selector);int n=Math.min(all.count(),10);for(int i=0;i<n;i++){Locator x=all.nth(i);if(x.isVisible()&&x.isEnabled()){x.click(new Locator.ClickOptions().setNoWaitAfter(true).setTimeout(5000));return true;}}}catch(Exception ignored){}}}return false;
    }

    private void logLoginDiagnostics(Page initial,Task t){
        try{List<Page> pages=new ArrayList<>(initial.context().pages());log(t,"ASAN diaqnostikası: "+pages.size()+" browser səhifəsi açıqdır.");for(Page p:pages){if(p.isClosed())continue;String title="";try{title=p.title();}catch(Exception ignored){}log(t,"Səhifə: "+safeUrl(p)+(title.isBlank()?"":" · "+title));for(Frame f:p.frames()){try{Locator inputs=f.locator("input:not([type='hidden'])");List<String> meta=new ArrayList<>();int n=Math.min(inputs.count(),8);for(int i=0;i<n;i++){Locator x=inputs.nth(i);if(!x.isVisible())continue;meta.add("id="+attr(x,"id")+", name="+attr(x,"name")+", type="+attr(x,"type")+", placeholder="+attr(x,"placeholder"));}if(!meta.isEmpty())log(t,"Frame "+safeFrameUrl(f)+" · inputlar: "+String.join(" | ",meta));}catch(Exception ignored){}}}}catch(Exception ignored){}
    }

    private void saveLoginDiagnostic(Page page,Task t){
        logLoginDiagnostics(page,t);try{Path dir=exportDir().resolve("diagnostics");Files.createDirectories(dir);String stamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());Path out=dir.resolve("asan-login-"+stamp+".png");page.screenshot(new Page.ScreenshotOptions().setPath(out).setFullPage(true));log(t,"ASAN diaqnostik şəkli: "+out);}catch(Exception e){log(t,"Diaqnostik şəkil saxlanmadı: "+shortMsg(e));}
    }

    private String safeUrl(Page p){try{return p.url()==null?"":p.url();}catch(Exception ignored){return "";}}
    private String safeFrameUrl(Frame f){try{return f.url()==null?"":f.url();}catch(Exception ignored){return "";}}

    private RuntimeException authTimeout(Page page,String tin,PlaywrightException e){String url=page.url()==null?"":page.url();if(url.contains("/login/asan/otp"))return new IllegalStateException("ASAN İmza təsdiqi tamamlanmadı. Telefonda sorğunu təsdiqləyin.",e);try{Locator x=findTin(page,tin);if(x!=null&&x.count()>0)return new IllegalStateException("VÖEN siyahısı göründü, amma seçim vaxtında tamamlanmadı.",e);}catch(Exception ignored){}return new IllegalStateException("e-Taxes giriş prosesi gözlənilən mərhələyə keçmədi. Açılan lokal browser pəncərəsini yoxlayın.",e);}
    private Locator findTin(Page p,String tin){Locator a=p.locator("text="+tin);if(a.count()>0)return a;Locator b=p.locator("span",new Page.LocatorOptions().setHasText(tin));return b.count()>0?b:null;}
    private void clickTin(Locator tin){for(String s:List.of("xpath=ancestor-or-self::*[self::li or self::button or @role='button'][1]","xpath=ancestor-or-self::*[@role='option' or @role='listitem'][1]")){try{Locator c=tin.locator(s);if(c.count()>0){c.first().click(new Locator.ClickOptions().setNoWaitAfter(true));return;}}catch(Exception ignored){}}tin.click(new Locator.ClickOptions().setNoWaitAfter(true));}
    private String readJwt(Page p){try{Object v=p.evaluate("()=>localStorage.getItem('aztax-jwt')");String x=v==null?"":String.valueOf(v).trim();return "null".equalsIgnoreCase(x)||"undefined".equalsIgnoreCase(x)?"":x;}catch(Exception e){return "";}}
    private String refresh(Page p,Task t,String old){check(t);try{p.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(Math.max(15,navigationTimeoutSeconds)*1000.0));}catch(Exception ignored){}try{p.waitForFunction("old=>{const x=localStorage.getItem('aztax-jwt');return !!x&&x!==old;}",old,new Page.WaitForFunctionOptions().setTimeout(Math.max(15,elementTimeoutSeconds)*1000.0));}catch(Exception e){String x=readJwt(p);if(x.isBlank()||Objects.equals(x,old))throw new IllegalStateException("e-Taxes sessiyası yenilənə bilmədi.",e);}return readJwt(p);}

    private List<String> fetchIds(Page p,AtomicReference<String> token,Task t)throws Exception{String endpoint=API+(isSent(t.request)?"find.outbox":"find.inbox");List<String> ids=new ArrayList<>();int offset=0,page=0;boolean more=true;while(more){check(t);HttpResult r=post(endpoint,token.get(),payload(t.request,offset)).join();if(r.status==401){token.set(refresh(p,t,token.get()));r=post(endpoint,token.get(),payload(t.request,offset)).join();}if(r.status<200||r.status>=300)throw new IllegalStateException("Qaimə siyahısı alınmadı (HTTP "+r.status+").");JsonNode arr=r.json.path("invoices");if(!arr.isArray()||arr.isEmpty())break;for(JsonNode n:arr){String id=text(n,"id");if(!id.isBlank())ids.add(id);}more=r.json.path("hasMore").asBoolean(false);offset+=200;page++;t.status.setProgress(Math.min(35,22+page));log(t,"Səhifə "+page+": ümumi "+ids.size()+" qaimə.");}return ids;}
    private Map<String,Object> payload(EtaxesExportRequest q,int offset){LinkedHashMap<String,Object>b=new LinkedHashMap<>();b.put("sortBy","creationDate");b.put("sortAsc",false);b.put("statuses",STATUSES);b.put("types",TYPES);b.put("kinds",KINDS);b.put("serialNumber",null);b.put("senderTin",null);b.put("senderName",null);b.put("productName",null);b.put("productCode",null);b.put("receiverTin",null);b.put("receiverName",null);b.put("creationDateFrom",apiDate(q.getStartDate()));b.put("creationDateTo",apiDate(q.getEndDate()));b.put("amountFrom",null);b.put("amountTo",null);b.put("offset",offset);b.put("maxCount",200);b.put("actionOwner",null);String c=digits(q.getContragentTin());if(!c.isBlank()){if(isSent(q))b.put("receiverTin",c);else b.put("senderTin",c);}return b;}
    private List<ExportRow> fetchDetails(Page p,AtomicReference<String> token,List<String> ids,Task t)throws Exception{
        int limit=Math.max(6,Math.min(32,t.request.getConcurrencyLimit()));
        AtomicInteger completed=new AtomicInteger();
        Map<String,DetailResult> results=fetchConcurrent(ids,token.get(),limit,t,completed,ids.size());
        List<String> unauthorized=ids.stream().filter(id->{DetailResult r=results.get(id);return r!=null&&r.status==401;}).toList();
        if(!unauthorized.isEmpty()){
            check(t);log(t,unauthorized.size()+" qaimə üçün sessiya yenilənir; yalnız həmin sorğular təkrar alınacaq.");
            token.set(refresh(p,t,token.get()));
            Map<String,DetailResult> retry=fetchConcurrent(unauthorized,token.get(),limit,t,new AtomicInteger(),unauthorized.size());
            results.putAll(retry);
        }
        List<ExportRow> rows=new ArrayList<>();
        int ok=0,failed=0;
        for(String id:ids){
            DetailResult r=results.get(id);
            if(r!=null&&r.status==200&&r.json!=null){rows.addAll(toRows(r.json));ok++;}
            else if(r!=null&&r.status==401)throw new IllegalStateException("e-Taxes sessiyasının vaxtı bitdi.");
            else failed++;
        }
        t.status.setProcessedInvoices(ids.size());
        t.status.setProgress(90);
        t.status.setMessage(ids.size()+" / "+ids.size()+" qaimə emal edildi · "+rows.size()+" sətir.");
        if(failed>0)log(t,"Detalı alınmayan qaimə sayı: "+failed+" · uğurlu: "+ok);
        return rows;
    }
    private Map<String,DetailResult> fetchConcurrent(List<String> ids,String token,int limit,Task t,AtomicInteger completed,int total)throws Exception{
        if(ids.isEmpty())return new LinkedHashMap<>();
        Semaphore gate=new Semaphore(limit);
        CompletionService<DetailResult> completion=new ExecutorCompletionService<>(executor);
        for(String id:ids){
            completion.submit(()->{
                gate.acquire();
                try{check(t);return detail(id,token);}
                finally{gate.release();}
            });
        }
        LinkedHashMap<String,DetailResult> out=new LinkedHashMap<>();
        for(int i=0;i<ids.size();i++){
            check(t);
            DetailResult r;
            try{r=completion.take().get();}
            catch(ExecutionException e){Throwable c=e.getCause();if(c instanceof Exception ex)throw ex;throw new IllegalStateException("Qaimə detalı alınarkən xəta.",c);}
            out.put(r.invoiceId,r);
            int done=completed.incrementAndGet();
            if(total>0){
                t.status.setProcessedInvoices(Math.min(total,done));
                t.status.setProgress(Math.min(90,35+(int)Math.round(done*55.0/total)));
                if(done==1||done==total||done%Math.max(5,limit)==0)t.status.setMessage(done+" / "+total+" qaimə paralel emal edilir...");
            }
        }
        return out;
    }
    private DetailResult detail(String id,String token){for(int i=1;i<=3;i++){try{HttpResult r=get(API+id+"?sourceSystem=avis",token).join();if(r.status==200||r.status==401||r.status==403||r.status==404||i==3)return new DetailResult(id,r.status,r.json);}catch(Exception e){if(i==3)return new DetailResult(id,599,null);}}return new DetailResult(id,599,null);}
    private List<ExportRow> toRows(JsonNode inv){List<ExportRow> out=new ArrayList<>();String serial=text(inv,"serialNumber"),raw=text(inv,"status"),status=STATUS_MAPPING.getOrDefault(raw,raw),rt=text(inv.path("receiver"),"tin"),rn=text(inv.path("receiver"),"name"),st=text(inv.path("sender"),"tin"),sn=text(inv.path("sender"),"name"),note=text(inv,"invoiceComment"),note2=text(inv,"invoiceComment2");String[]dt=splitDateTime(text(inv,"createdAt"));JsonNode items=inv.path("items");if(!items.isArray())return out;for(JsonNode it:items){BigDecimal cost=dec(it,"cost"),exc=dec(it,"excise"),road=dec(it,"roadTax"),vat18=dec(it,"vat18"),vat=vat18.multiply(new BigDecimal("0.18")).setScale(2,RoundingMode.HALF_UP),sum=cost.add(exc).setScale(2,RoundingMode.HALF_UP),fin=sum.add(vat).add(road).setScale(2,RoundingMode.HALF_UP);out.add(new ExportRow(List.of(serial,status,text(it,"itemId"),rt,rn,st,sn,text(it,"productName"),text(it.path("productGroup"),"code"),text(it,"barcode"),text(it,"unit"),dec(it,"quantity"),dec(it,"pricePerUnit"),cost,sum,dec(it,"exciseRate"),exc,vat18,dec(it,"vatFree"),dec(it,"vat0"),vat,dec(it,"exempt"),road,fin,dt[0],dt[1],note,note2)));}return out;}
    private byte[] buildExcel(List<ExportRow> rows)throws Exception{try(XSSFWorkbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){Sheet sh=wb.createSheet("Sheet1");CellStyle hs=wb.createCellStyle();Font f=wb.createFont();f.setBold(true);hs.setFont(f);hs.setWrapText(true);CellStyle ds=wb.createCellStyle();ds.setDataFormat(wb.createDataFormat().getFormat("0.00####"));Row hr=sh.createRow(0);for(int c=0;c<HEADERS.length;c++){Cell x=hr.createCell(c);x.setCellValue(HEADERS[c]);x.setCellStyle(hs);}int r=1;for(ExportRow er:rows){Row row=sh.createRow(r++);for(int c=0;c<er.values.size();c++){Object v=er.values.get(c);Cell x=row.createCell(c);if(v instanceof BigDecimal bd){x.setCellValue(bd.doubleValue());x.setCellStyle(ds);}else if(v!=null)x.setCellValue(String.valueOf(v));}}sh.createFreezePane(0,1);sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,r-1),0,HEADERS.length-1));for(int c=0;c<HEADERS.length;c++)sh.setColumnWidth(c,(switch(c){case 4,6,7,26,27->36;case 0,1,2->22;default->18;})*256);wb.write(out);return out.toByteArray();}}

    private CompletableFuture<HttpResult> post(String url,String token,Object body){try{String json=mapper.writeValueAsString(body);HttpRequest r=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(Math.max(10,httpTimeoutSeconds))).header("User-Agent",UA).header("Accept","application/json, text/plain, */*").header("Content-Type","application/json").header("x-authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8)).build();return httpClient().sendAsync(r,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(x->new HttpResult(x.statusCode(),parse(x.body())));}catch(Exception e){return CompletableFuture.failedFuture(e);}}
    private CompletableFuture<HttpResult> get(String url,String token){HttpRequest r=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(Math.max(10,httpTimeoutSeconds))).header("User-Agent",UA).header("Accept","application/json, text/plain, */*").header("x-authorization","Bearer "+token).GET().build();return httpClient().sendAsync(r,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(x->new HttpResult(x.statusCode(),parse(x.body())));}
    private HttpClient httpClient(){HttpClient c=http;if(c!=null)return c;synchronized(this){if(http!=null)return http;HttpClient.Builder b=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(25));String s=proxyServer==null?"":proxyServer.trim();if(!s.isBlank()){URI u=URI.create(s.contains("://")?s:"http://"+s);b.proxy(ProxySelector.of(new InetSocketAddress(u.getHost(),u.getPort()>0?u.getPort():80)));if(proxyUsername!=null&&!proxyUsername.isBlank()){String user=proxyUsername,pass=proxyPassword==null?"":proxyPassword;b.authenticator(new Authenticator(){@Override protected PasswordAuthentication getPasswordAuthentication(){return new PasswordAuthentication(user,pass.toCharArray());}});}}http=b.build();return http;}}
    private void configureProxy(BrowserType.LaunchOptions o,Task t){String s=proxyServer==null?"":proxyServer.trim();if(s.isBlank()){log(t,"Şəbəkə: istifadəçinin birbaşa internet bağlantısı.");return;}com.microsoft.playwright.options.Proxy p=new com.microsoft.playwright.options.Proxy(s);if(proxyUsername!=null&&!proxyUsername.isBlank())p.setUsername(proxyUsername);if(proxyPassword!=null&&!proxyPassword.isBlank())p.setPassword(proxyPassword);o.setProxy(p);log(t,"Şəbəkə: lokal agent proxy istifadə edir.");}

    private void validateGrant(EtaxesExportRequest r){
        String origin=serverOrigin==null?"":serverOrigin.trim();
        if(origin.isBlank())throw new IllegalStateException("TaxData Agent server ünvanı konfiqurasiya edilməyib. Agenti saytdan yenidən quraşdırın.");
        HttpResult x=serverGrantCall(origin+"/agent/grants/consume",r).join();
        if(x.status<200||x.status>=300)throw new IllegalStateException("TaxData lokal agent icazəsi server tərəfindən qəbul edilmədi. Sayt yeni icazəni avtomatik yeniləyib yenidən yoxlayacaq.");
    }
    private void completeGrant(EtaxesExportRequest r){
        String origin=serverOrigin==null?"":serverOrigin.trim();
        HttpResult x=serverGrantCall(origin+"/agent/grants/complete",r).join();
        if(x.status<200||x.status>=300)throw new IllegalStateException("VÖEN hesab bağlaması serverdə təsdiqlənmədi. Təhlükəsizlik üçün qaimə gətirmə dayandırıldı.");
    }
    private CompletableFuture<HttpResult> serverGrantCall(String url,EtaxesExportRequest r){
        try{
            String json=mapper.writeValueAsString(Map.of("grant",r.getAgentGrant(),"tin",digits(r.getTin()),"phone",digits(r.getPhoneNumber()),"userId",r.getUserId()==null?"":r.getUserId().trim()));
            HttpRequest q=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent","TaxData-Local-Agent/6.8.6").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8)).build();
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().sendAsync(q,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(x->new HttpResult(x.statusCode(),parse(x.body())));
        }catch(Exception e){return CompletableFuture.failedFuture(e);}
    }

    private JsonNode parse(String s){try{return mapper.readTree(s);}catch(Exception e){return mapper.createObjectNode();}}
    private Task require(String id){Task t=tasks.get(id);if(t==null)throw new IllegalArgumentException("Lokal e-Taxes tapşırığı tapılmadı.");return t;}
    private void cleanup(){Instant cut=Instant.now().minus(Duration.ofHours(2));tasks.entrySet().removeIf(e->e.getValue().status.getFinishedAt()!=null&&e.getValue().status.getFinishedAt().isBefore(cut));}
    private void finishEmpty(Task t){t.status.setStatus("COMPLETED");t.status.setPhase("Hazırdır");t.status.setProgress(100);t.status.setMessage("Seçilmiş filtrlərə uyğun qaimə tapılmadı.");t.status.setFinishedAt(Instant.now());}
    private void update(Task t,String phase,String message,int progress){t.status.setStatus("RUNNING");t.status.setPhase(phase);t.status.setMessage(message);t.status.setProgress(progress);log(t,message);}
    private void log(Task t,String m){String line=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now())+" · "+m;synchronized(t.status){List<String> l=t.status.getLogs();l.add(line);while(l.size()>120)l.remove(0);}}
    private EtaxesExportStatus snapshot(Task t){synchronized(t.status){EtaxesExportStatus s=t.status,n=new EtaxesExportStatus();n.setTaskId(s.getTaskId());n.setStatus(s.getStatus());n.setPhase(s.getPhase());n.setMessage(s.getMessage());n.setProgress(s.getProgress());n.setInvoiceCount(s.getInvoiceCount());n.setProcessedInvoices(s.getProcessedInvoices());n.setRowCount(s.getRowCount());n.setFileName(s.getFileName());n.setDownloadUrl(s.getDownloadUrl());n.setDownloadReady(s.isDownloadReady());n.setImportIntoRegistry(s.isImportIntoRegistry());n.setStartedAt(s.getStartedAt());n.setFinishedAt(s.getFinishedAt());n.setLogs(new ArrayList<>(s.getLogs()));return n;}}
    private void check(Task t){if(t.cancelled.get())throw new CancellationException();}
    private void validate(EtaxesExportRequest r){if(r==null)throw new IllegalArgumentException("Sorğu boşdur.");if(digits(r.getPhoneNumber()).length()<9)throw new IllegalArgumentException("Telefon nömrəsini düzgün yazın.");if(blank(r.getUserId()))throw new IllegalArgumentException("İstifadəçi ID boş ola bilməz.");if(digits(r.getTin()).length()!=10)throw new IllegalArgumentException("VÖEN 10 rəqəm olmalıdır.");if(blank(r.getAgentGrant()))throw new IllegalArgumentException("TaxData server icazəsi yoxdur. Səhifəni yeniləyib yenidən başlayın.");r.setConcurrencyLimit(Math.max(6,Math.min(32,r.getConcurrencyLimit())));r.setInvoiceType(isSent(r)?"sent":"received");}
    private void clearSensitive(EtaxesExportRequest r){if(r==null)return;r.setPhoneNumber("");r.setUserId("");r.setAgentGrant("");r.setTin("");r.setContragentTin("");}
    private boolean isSent(EtaxesExportRequest r){return "sent".equalsIgnoreCase(r.getInvoiceType());}
    private boolean blank(String s){return s==null||s.isBlank();}private String digits(String s){return s==null?"":s.replaceAll("\\D","");}
    private String text(JsonNode n,String f){if(n==null)return"";JsonNode x=n.get(f);return x==null||x.isNull()?"":x.asText("");}private BigDecimal dec(JsonNode n,String f){JsonNode x=n==null?null:n.get(f);if(x==null||x.isNull())return BigDecimal.ZERO;try{return x.isNumber()?x.decimalValue():new BigDecimal(x.asText().replace(",","."));}catch(Exception e){return BigDecimal.ZERO;}}
    private String apiDate(String x){if(blank(x))return null;String s=x.trim();try{return LocalDateTime.parse(s).format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));}catch(Exception ignored){}try{return OffsetDateTime.parse(s).toLocalDateTime().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));}catch(Exception ignored){}return s;}
    private String[] splitDateTime(String raw){if(blank(raw))return new String[]{"",""};String s=raw.trim();try{OffsetDateTime d=OffsetDateTime.parse(s);return new String[]{d.toLocalDate().toString(),d.toLocalTime().withNano(0).toString()};}catch(Exception ignored){}int i=s.indexOf('T');return i>0?new String[]{s.substring(0,i),s.substring(i+1).replace("Z","").split("[.+]")[0]}:new String[]{s,""};}
    private String cleanError(Throwable e){String m=e==null?"Naməlum xəta":e.getMessage();if(m==null||m.isBlank())m=e.getClass().getSimpleName();return m.replaceAll("\\s+"," ").trim();}private String shortMsg(Throwable e){String m=e==null?"":e.getMessage();return m==null?"":m.replaceAll("\\s+"," ").trim();}
    private Path exportDir(){Path downloads=Path.of(System.getProperty("user.home","."),"Downloads");if(!Files.isDirectory(downloads))downloads=Path.of(System.getProperty("user.home","."));return downloads.resolve("TaxData").resolve("eTaxes");}
    private Path unique(Path p){if(!Files.exists(p))return p;String n=p.getFileName().toString(),base=n.endsWith(".xlsx")?n.substring(0,n.length()-5):n;for(int i=2;i<1000;i++){Path x=p.getParent().resolve(base+"_"+i+".xlsx");if(!Files.exists(x))return x;}return p.getParent().resolve(base+"_"+UUID.randomUUID()+".xlsx");}
    @PreDestroy public void shutdown(){executor.shutdownNow();}

    public record Download(String fileName,byte[] bytes){}
    private record LoginSession(String token,Page page){}
    private record LoginTarget(Page page,Frame frame,Locator phone,Locator user,Locator sign){}
    private record HttpResult(int status,JsonNode json){}private record DetailResult(String invoiceId,int status,JsonNode json){}private record ExportRow(List<Object> values){}
    private static final class Task{final EtaxesExportRequest request;final EtaxesExportStatus status;final AtomicBoolean cancelled=new AtomicBoolean(false);volatile Path file;Task(EtaxesExportRequest r,EtaxesExportStatus s){request=r;status=s;}}
}
