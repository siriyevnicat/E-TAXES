## 2026-09-20 — eFP paket adı uyğunluğu + Excel oxunaqlılığı
- eFP ZIP daxilində XML fayl adları artıq yalnız ASCII təhlükəsiz simvollara çevrilir; `ə/ş/ğ/ü/ö/ç/ı` kimi hərflər transliterasiya olunur və boşluqlar `_` olur. Bu, köhnə e-Taxes ZIP parser-lərində Unicode fayl adı səbəbli rədd riskini aradan qaldırır.
- EFP Excel şablonunda mətnlər Wrap Text ilə saxlanılır, əsas sətirlər minimum 16 hündürlükdədir və C sütunu daha genişdir.


## 2026-09-19 — Agent login/relogin false-repair fix
- Hesabdan çıxış zamanı işləyən Agent monitor/recovery timer-ları artıq dayandırılır və açıq qalmış Agent modalı bağlanır.
- Yenidən giriş zamanı Agent UI vəziyyəti təmiz başlanır; əvvəlki sessiyanın `repair` vəziyyəti yeni sessiyaya daşınmır.
- Əvvəllər sağlam olduğu yadda saxlanan Agent qısa müddət cavab verməsə, sayt artıq avtomatik “Agent bərpa et” pəncərəsi açmır; watchdog üçün arxa planda gözləyir və yalnız düyməni göstərir.
- Agent status probe-u keçici localhost gecikmələrinə qarşı qısa retry ilə daha dayanıqlı edilib.
## V7.1.14 — EFP 12-ci sütun ƏDV yekunu + Excel oxunaqlılığı
- EDV li 01 importunda K sütununda məbləğ olub L:O ƏDV bölgüsü boş/0 qaldıqda K məbləği standart 18%-lik L sütununa avtomatik tamamlanır; P (ƏDV) və R (yekun) da uyğun hesablanır.
- EFP A:R redaktoruna canlı CƏMİ sətri əlavə edildi; 12-ci sütun (L) daxil olmaqla H/J/K/L/M/N/O/P/Q/R yekunları dərhal görünür.
- QAIME_1 paketində c4/c8 yekunları həmin düzəldilmiş ƏDV bölgüsündən hesablanır.
- Lokal e-Taxes Agent Excel çıxışlarında bütün mətn hüceyrələri Wrap Text ilə saxlanılır, sətir hündürlüyü mətnə görə artırılır, sütun enləri oxunaqlı ölçüdə qurulur və A4 landscape çap ayarları tətbiq olunur.
- Alt sənəd Excel-lərində universal wrap/row-height hesablamasına əlavə təhlükəsizlik payı verildi ki uzun mətnlər xanada gizlənməsin.
- Local Agent versiyası 6.8.7-ə qaldırıldı ki Excel format düzəlişləri mövcud agentlərə avtomatik yenilənsin.

## V7.1.7 — Son EDV li 01 şablonu ilə A:R 1:1 uyğunluq
- İstifadəçinin 04.09.2026 tarixində göndərdiyi `EDV li 01(1).xlsx` yoxlanıldı: şablon A:R üzrə 18 sütundur, əvvəlki A:Y/25 sütun deyil.
- Saytdakı “Yeni qaimə” redaktoru həmin 18 sütun və çoxsəviyyəli başlıqlarla yenidən quruldu.
- Göndərən/Qəbul edən/VÖEN/Əsas/Əlavə qeydlər Excel şablonundakı 1–4-cü sətirlərə uyğun yerləşdirildi.
- Sətir əlavə etmə, Enter naviqasiyası, Excel paste, hesablamalar və QAIME_1 xəritələnməsi A:R sırasına uyğunlaşdırıldı.
- Endirilən Excel nümunəsi istifadəçinin göndərdiyi son şablonun özüdür və yeni importer bu strukturu oxuyur.



## 7.1.6
- Göndərilən qaimənin “Yeni qaimə yarat” redaktoru tam Excel A:Y vərəqinə çevrildi.
- 25 sütun eyni ardıcıllıq və başlıqlarla bir cədvəldədir.
- Tam sətir əlavə etmə, A–H sinxronlaşdırma, Enter ilə aşağı keçid və Excel multi-cell paste əlavə edildi.
- V7.1.4 Railway compile fix və V7.1.5 eFP/QAIME_1 məntiqi saxlanıldı.
## 7.1.5
- Göndərilən qaimə redaktoru Excel A:Y sütunları ilə 1:1 uyğunlaşdırıldı.
- Məhsul sətrinə I:Y arasında bütün rəsmi sütunlar əlavə edildi.
- Obyekt, aksiz, ƏDV bölgüsü, yol vergisi və yekun dəyərlərin tam redaktəsi əlavə edildi.
- UI tərəfindən göndərilən tam rəsmi dəyərlər backend-də qorunur.
## 6.8.5 — ASAN İmza form detection fix
- ASAN İmza login formu artıq yalnız `#phone/#userId` selector-ları ilə məhdudlaşmır.
- Açıq browser səhifələri və iframe-lər yoxlanılır; telefon/User ID sahələri atribut heuristikası ilə də tanınır.
- Lazım olduqda görünən `ASAN İmza` giriş seçimi avtomatik kliklənir.
- Form tapılmasa URL/frame/input diaqnostikası texniki gedişata yazılır və lokal `Downloads/TaxData/eTaxes/diagnostics` qovluğunda screenshot saxlanır.

# TaxData 6.8.5

- Windows istifadəçi profilində boşluq olduqda Agent JAR yolunun parçalanması düzəldildi.
- Installer, start-agent və watchdog Java command line-larında JAR yolu explicit dırnaqla ötürülür.
- `Invalid or corrupt jarfile C:\\Users\\GMB` tipli saxta JAR xətası aradan qaldırıldı.


## 6.8.5 — Standard-user launch fix
- Standard user hesabında nested hidden PowerShell/Java startının bloklana bildiyi ssenari üçün Agent birbaşa installer prosesindən başladılır.
- Watchdog birbaşa Java startına keçir, PowerShell launcher fallback kimi saxlanılır.
- HKCU startup birbaşa Java command istifadə edir.
- Start diaqnostikasına `agent-launch.log` əlavə edildi.
## 6.8.5 — installComplete marker fix
- Windows PowerShell 5.1-in `.install-complete` faylina UTF-8 BOM elave etmesi sebebinden Agent islese de `version` komponenti false qala bilirdi.
- Marker artiq UTF-8 BOM-suz yazilir; Java health parser kohne BOM-lu markerleri de temizleyib oxuyur.
- `installComplete` alinmasa installer konkret `missing` ve `components` statusunu konsolda gosterir.

## 6.8.5 — Legacy local-agent takeover
- Köhnə lokal agent 47631 portunu tutursa yeni installer onu legacy agent kimi tanıyır, proses/watchdog-u dayandırır və portu boşaldır.
- Köhnə autostart və lokal qovluq yalnız yeni TaxData Agent JAR + Java 21 + start + watchdog + repair + health-check tam uğurlu olduqdan sonra silinir.
- 47631 portunu həqiqətən başqa proqram tutursa həmin proqram avtomatik dayandırılmır.

## 6.8.5 — Port 47631 self-healing
- Repair staging tamamlandıqdan sonra köhnə TaxData watchdog dayandırılır, 47631 portunun real owner PID-si müəyyən edilir və yalnız TaxData Agent kimi təsdiqlənən proses təhlükəsiz bağlanır.
- Port boşalmadan yeni Agent başladılmır. Portu başqa proqram tutursa proses avtomatik öldürülmür; PID/proses məlumatı göstərilir.
- Yeni Agent start/health-check keçməsə əvvəlki işlək quraşdırma rollback olunur.


## 6.8.5
- Agent start yoxlamasında kritik 403 problemi düzəldildi: installer və watchdog `/api/status` sorğularına `X-TAXDATA-Agent: 1` başlığını göndərir.
- `/api/status` loopback health endpointi müdafiə məqsədilə ayrıca read-only istisna edildi; digər agent API-ləri xüsusi header tələb etməyə davam edir.
- Agent start prosesi `java.exe` ilə hidden rejimdə başladılır və `agent-stdout.log` / `agent-stderr.log` fayllarına diaqnostika yazılır.
- Start health-check alınmasa installer logların son hissəsini göstərir; saxta “Agent başlamadı” rollback dövrü aradan qaldırıldı.
# TaxData 6.8.5

## 6.8.5 — Agent clean-repair + PowerShell HOME collision fix
- Agent bootstrap-dakı `$home` dəyişənləri tam çıxarıldı; Windows PowerShell-in qorunan `$HOME` dəyişəni ilə toqquşma yoxdur.
- “Agent bərpa et” hər dəfə serverdən təmiz Agent JAR endirir və SHA-256/ölçü yoxlamasından sonra commit edir.
- Köhnə `.installing-*` yarımçıq quraşdırma qovluqları yeni cəhddən əvvəl təmizlənir.
- Java 21/private JRE yalnız real `release` + native `java -version` yoxlaması keçərsə istifadə olunur; xarab/natamam JRE yenidən yüklənir.
- Yeni paket staging-də tam sağlam olmadan işlək agentə toxunulmur; commit sonrası health-check alınmasa əvvəlki versiya rollback edilir.

## 6.8.5 — Windows bootstrap download reliability
- Agent installer no longer depends on a single Invoke-WebRequest path.
- Localhost bootstrap downloads use 127.0.0.1 to avoid local proxy/name-resolution issues.
- curl.exe is tried first, then PowerShell is used as a fallback.
- The installer validates the downloaded bootstrap before execution and prints the exact failing URL/method.

- Windows portable JDK 21 yoxlaması `java -version` regexindən çıxarıldı; JDK `release` metadata + real `java.exe`/`javac.exe` smoke-test ilə təsdiqlənir.
- JDK namizədi uğursuz olarsa konkret versiya, yol və exit kodları CMD-də göstərilir.
- Windows arxitekturasının seçimi OS architecture əsasında sərtləşdirildi.
- Köhnə `.installing` JDK qovluqları avtomatik təmizlənir.


- Windows PowerShell-da avtomatik `$HOME` dəyişəni ilə toqquşan `$home` lokal dəyişənləri `$jdkHome` olaraq dəyişdirildi.
- Portable JDK 21 arxivinin açılması və yoxlanması artıq `HOME is read-only or constant` xətası vermir.
- Uğursuz `.installing` qovluğu `finally` mərhələsində təmizlənir və növbəti cəhd təmiz başlayır.

## 6.7.5 — Local 8080 automatic old-process recovery
- `START_WINDOWS.bat` checks port 8080 before building.
- If the listener is an older TaxData server (`taxdata-server.jar` / `TaxDataApplication`), it is stopped automatically.
- The script waits until port 8080 is actually released, then verifies Java and performs a clean `bootJar` build.
- Non-TaxData processes on port 8080 are never terminated automatically; their PID/process information is shown and startup stops safely.
- Both `taxdata-server.jar` and `taxdata-agent.jar` must exist before localhost is started.


## 6.6.8 · localhost:8080 agent distribution fix
- `bootRun` now depends on a verified `taxdata-agent.jar`.
- `START_WINDOWS.bat` performs a clean full `bootJar` build and launches the built server JAR instead of starting an incomplete dev server.
- Port 8080 is checked first so an older TaxData backend cannot silently answer with the previous HTTP 503.
- Local agent distribution resolves an explicit `taxdata.agent.jar` path before embedded/server fallbacks.
- Agent JAR build is rejected unless `AgentApplication.class` is actually present.

# TaxData dəyişiklik jurnalı

## 6.6.8 — Agent bootstrap 400 + self-contained server build
- `/agent/bootstrap` və `/agent/installer` çağırışlarından tam URL query parametri çıxarıldı.
- `taxdata-agent.jar` server `bootJar` faylının içinə embed edilir; ayrıca `/app/agent` faylı olmadıqda classpath-dən avtomatik bərpa olunur.
- Agent distribution resursu çatışmırsa yanlış HTTP 400 əvəzinə HTTP 503 + `AGENT_PACKAGE_UNAVAILABLE` qaytarılır.
- Agent JAR download-u diskdən stream edilir.
- Sayt serverin konkret xəta mesajını göstərir.

## 6.6.4 — Tam TaxData adlandırması + bölmə üzrə video təlimatlar
- Məhsulun köhnə adlandırmasına aid bütün mətn, cookie, header, localStorage, IndexedDB, temp qovluğu, agent endpoint-i, PowerShell və Java identifikatorları TaxData ilə əvəz edildi.
- Yeni brauzer açarları `taxdata.deviceId`, `taxdata_agent_etx_task`, `taxdata_agent_etx_tin` və `taxdata-local-first-v1` olaraq işləyir.
- Köhnə brauzer workspace-i itirilməsin deyə marka adı yazılmadan generic bir dəfəlik migration əlavə edildi: əvvəlki `*-local-first-v1` bazası tapılarsa TaxData bazasına köçürülüb köhnə baza silinir.
- Hər 1, 2, 2A, 3, 4, 5, 6 və 7-ci bölmədə `Təlimata bax` düyməsi var.
- Hər bölmənin öz ayrıca 1280×720 H.264 MP4 təlim videosu var. Video modalı düymənin basıldığı bölmənin videosunu avtomatik seçir və eyni bölmənin məqsəd/addım/yaxşı praktika mətnini göstərir.
- Müqavilə bölməsinin lokal Word arxivi yalnız `TaxData_Muqavileler` adı ilə göstərilir və istifadə olunur.

## 6.6.3 — Agent integrity + bərpa
- Sayt yalnız lokal `/api/status` cavabı gəlməsinə görə agenti hazır saymır. `installComplete=true` və düzgün versiya mütləqdir.
- Agent JAR, SHA-256 bütövlüyü, Java 21, Java yolu, start skripti, watchdog, `TaxData-Agent-Repair.cmd`, origin və tamamlama markerini yoxlayır.
- `%LOCALAPPDATA%\TaxData\Recovery\TaxData-Agent-Repair.cmd` daimi bərpa quraşdırıcısı kimi saxlanılır; silinsə watchdog serverdən yenidən yükləyir.
- Bütün lokal TaxData qovluğu silinibsə sayt agentin yoxluğunu aşkarlayır və bərpa modalı ilə `TaxData-Agent-Setup.cmd` faylını yenidən endirir.
- e‑Taxes agent grantları DB-backed token hash kimi saxlanılır və grant xətasında sayt bir dəfə yeni grant alıb yenidən cəhd edir.

# TaxData 6.6.2 — Dəyişikliklər

## 6.6.2 — Agent self-healing / automatic repair
- Agent JAR və Java 21 endirmələri hər cəhddə natamam faylı silərək 4 dəfəyədək avtomatik təkrar yoxlanır.
- CMD installer bütöv quraşdırmanı uğursuzluqdan sonra 3 dəfəyədək avtomatik yenidən başladır.
- `%LOCALAPPDATA%\TaxData\watch-agent.ps1` user-level watchdog əlavə edildi. Agent dayanarsa, JAR/Agent qovluğu silinərsə və ya start faylı itərsə, watchdog serverdən setup modulunu yenidən yükləyib agenti bərpa edir.
- Watchdog `Agent` qovluğundan kənarda saxlanılır; buna görə Agent qovluğunun silinməsi özü watchdog-u silmir.
- Installer və watchdog yarışmasın deyə install lock/guard mexanizmi əlavə edildi; köhnə lock 10 dəqiqədən sonra stale hesab edilərək təmizlənir.
- Sayt agentin əvvəllər aktiv olduğunu yadda saxlayır. Sonradan agent yoxa çıxsa, əvvəlcə “avtomatik bərpa edilir” vəziyyətini göstərir və watchdog-un bərpasını gözləyir; yalnız bərpa alınmasa “Təkrar cəhd et” göstərir.
- Əvvəlki işlək agent yenilənmə zamanı yenə transactional rollback ilə qorunur.

## Brend və adlandırma
- Məhsul adı istifadəçiyə görünən bütün əsas ekranlarda **TaxData** olaraq dəyişdirildi.
- Lokal agent, quraşdırıcı, JAR faylları, Downloads qovluğu və lokal sənəd qovluqları TaxData adı ilə yeniləndi.
- Köhnə 6.5.x agenti ilə problemsiz keçid üçün yalnız daxili uyğunluq identifikatorları saxlanılıb.

## Agent quraşdırma təsdiqi
- Quraşdırma təsdiqi CMD daxilindən çıxarılıb və saytın özündə **Bəli / Xeyr** modalına keçirilib.
- **Bəli** seçildikdə `TaxData-Agent-Setup.cmd` avtomatik endirilir.
- CMD daxilində artıq `B` / `X` seçimi yoxdur; fayl açılan kimi quraşdırma başlayır.
- Brauzer təhlükəsizliyi səbəbilə sayt endirilmiş `.cmd` faylını özü işə sala bilməz; istifadəçi onu Downloads-dan bir dəfə açır.
- Quraşdırmadan sonra sayt agentin aktivləşməsini avtomatik yoxlayır.

## 6.5.x → TaxData miqrasiyası
- Yeni installer köhnə agent autostart qeydini silir və köhnə agent prosesini dayandırır.
- Port 47631 konflikti yaranmadan TaxData Agent işə düşür.
- Mövcud istifadəçi workspace/device identifikatorları saxlanılıb ki, hesab və lokal məlumat davamlılığı pozulmasın.

## Təlim mərkəzi
- Header-ə **Təlim mərkəzi** əlavə edilib.
- Hər əsas bölməyə **Necə istifadə edilir?** düyməsi əlavə edilib.
- 1, 2, 2A, 3, 4, 5, 6 və 7-ci bölmələr üçün məqsəd, addımlar və yaxşı praktika təlimatı əlavə edilib.
- `training/taxdata-suretli-baslangic.mp4` daxilində 48 saniyəlik sürətli başlanğıc video təlimatı əlavə edilib.

## Production yoxlamaları
- JavaScript sintaksis yoxlaması: keçdi.
- HTML struktur və təkrarlanan ID yoxlaması: keçdi.
- Java mənbələrində sintaksis səviyyəli yoxlama: keçdi.
- Tam Gradle build bu iş mühitində internet olmadığı üçün `services.gradle.org` distributivini endirə bilmədi. Railway/Docker build mühitində internet olduqda `./gradlew clean bootJar agentBootJar` ilə build edilməlidir.

## 6.6.2 — Installer recovery / retry
- Agent və Java 21 əvvəlcə `%LOCALAPPDATA%\\TaxData\\Agent\\.installing` müvəqqəti sahəsinə yüklənir.
- JAR və ya Java yükləməsi/çıxarılması uğursuz olarsa həmin cəhdin yarımçıq faylları avtomatik silinir.
- Mövcud işlək agent bütün yeni fayllar doğrulanana qədər dəyişdirilmir.
- Yeni agent lokal `/api/status` sağlamlıq yoxlamasından keçməzsə əvvəlki JAR/JRE/startup faylları rollback edilir.
- Kompüterin sönməsi kimi sərt kəsilmədən qalan `.previous-*` rollback faylları növbəti cəhddə bərpa olunur.
- Sayt agenti 3 dəqiqəyə qədər yoxlayır; aktiv olmazsa modal `Təkrar cəhd et` rejiminə keçir.
- CMD installer xəta zamanı əlavə B/X və ya klaviatura təsdiqi istəmir.

## 6.6.8 — Railway agent package hardening
- Railway build is explicitly pinned to the root Dockerfile via `railway.toml`.
- Docker build now fails if `taxdata-agent.jar`, embedded agent distribution, or setup resources are missing.
- `bootJar` verifies the embedded agent artifact before a production server JAR is accepted.
- Server can extract the embedded agent directly from the running Spring Boot JAR ZIP if the classloader does not expose nested `.jar` resources.
- `/agent/health` validates that the agent binary is genuinely available.
- Railway health check uses `/agent/health`, preventing an agent-less deployment from becoming healthy.

## 6.7.5 — Lokal Java bootstrap
- `START_WINDOWS.bat` artıq sistemdə Java/JDK quraşdırılmasını tələb etmir.
- Java 21 JDK yoxdursa `%LOCALAPPDATA%\TaxData\BuildJdk21` daxilinə portable Eclipse Temurin JDK 21 avtomatik endirilir və yoxlanılır.
- Gradle build və lokal server həmin private JDK ilə işləyir; administrator hüququ tələb olunmur.
- Sistem Java-sı 21 deyilsə və ya yalnız JRE-dirsə, TaxData private JDK 21-ə avtomatik keçir.
- JDK endirməsi natamam olarsa fayl təmizlənir və 4 dəfə təkrar cəhd edilir; tam yoxlamadan sonra build başlayır.

### 6.7.5 — Windows Unicode JDK path fix
- Lokal build JDK yolu artıq ASCII state faylı ilə `START_WINDOWS.bat`-a ötürülmür.
- Kiril/Azərbaycan və digər Unicode Windows istifadəçi adlarında (`C:\\Users\\алим\\...` kimi) JDK yolu pozulmur.
- `START_WINDOWS.bat` private JDK-ni birbaşa `%LOCALAPPDATA%\\TaxData\\BuildJdk21` altında aşkar edir.
- Yeni portable JDK quraşdırmaları sabit `BuildJdk21\\bin\\java.exe` strukturuna normallaşdırılır; əvvəlki nested JDK strukturu da geriyə uyğun tapılır.

## 6.7.5 — Agent auto-update bridge
- Local Agent status now reports whether an e-Taxes task is active (`busy`).
- Watchdog checks `/agent/manifest` periodically and compares the server Agent version/hash with the installed version.
- From 6.7.5 onward, newer Agent versions are downloaded and installed automatically when the Agent is idle.
- Active e-Taxes work is not interrupted; update waits until the task is finished.
- After a successful self-update the old watchdog releases its mutex and starts the newly installed watchdog script.
- 6.7.3 -> 6.7.5 is a one-time manual bridge because 6.7.3 watchdog did not yet know how to query the server manifest.

## 6.7.5 — lokal `.env` yuklenmesi
- `START_WINDOWS.bat` serveri acarken `.env` ve `.env.local` fayllarini avtomatik yukleyir.
- `.env.local` `.env` deyerlerini override ede biler.
- Secret deyerler konsola yazilmir.
- `prod` profilinde vacib DB/admin environment deyisenleri catismirsa server yalniz default `dev/H2` ile sehven acilmir; konkret acar adlari ile dayanir.
- Real `.env` release ZIP/Docker image-a daxil edilmir; `.env.local.example` ve `.env.server.example` verilir.
- Railway ucun `.env` deyil, Railway Variables istifade olunur.
