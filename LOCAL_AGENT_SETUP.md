## 6.8.5 — ASAN İmza form detection fix
- ASAN İmza login formu artıq yalnız `#phone/#userId` selector-ları ilə məhdudlaşmır.
- Açıq browser səhifələri və iframe-lər yoxlanılır; telefon/User ID sahələri atribut heuristikası ilə də tanınır.
- Lazım olduqda görünən `ASAN İmza` giriş seçimi avtomatik kliklənir.
- Form tapılmasa URL/frame/input diaqnostikası texniki gedişata yazılır və lokal `Downloads/TaxData/eTaxes/diagnostics` qovluğunda screenshot saxlanır.


## 6.8.5 — Administrator olmadan start
Quraşdırıcı Agent-i birbaşa Java prosesi kimi başladır. `start-agent.ps1` ayrıca child PowerShell kimi məcburi deyil; watchdog da birbaşa Java startını üstün tutur.
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


## 6.8.5 kritik Agent start düzəlişi
- Installer və watchdog `http://127.0.0.1:47631/api/status` yoxlamasına `X-TAXDATA-Agent: 1` göndərir. Əvvəl agent bu sorğunu 403 rədd etdiyi üçün sağlam proses də səhvən “başlamadı” sayılırdı.
- `/api/status` yalnız read-only health/status endpointi kimi xüsusi header olmadan da lokal loopback-da cavab verə bilir; digər `/api/*` əməliyyatları header tələb etməyə davam edir.
- Agent Java prosesi hidden `java.exe` ilə başladılır və `agent-stdout.log` / `agent-stderr.log` yaradılır. Start uğursuz olarsa installer son log sətirlərini göstərir.
# TaxData Local Agent — 6.8.5

1. Saytda Agent tələb olunan bölmədə `Bəli, quraşdırıcını endir` və ya `Bəli, agenti bərpa et` seçilir.
2. Brauzer `TaxData-Agent-Setup.cmd` faylını endirir. Brauzer təhlükəsizlik qaydasına görə CMD özü avtomatik açıla bilməz; istifadəçi onu bir dəfə açır.
3. Installer agent JAR, Java 21, start skripti, watchdog və lokal bərpa CMD-sini yoxlayır. Çatışmayan və ya bütövlük yoxlamasından keçməyən hissə yenidən hazırlanır/yüklənir.
4. JAR serverdəki ölçü və SHA-256 ilə yoxlanılır.
5. Lokal agent başladıqdan sonra `/api/status` `version=6.8.5` və `installComplete=true` qaytarmadan sayt `Agent hazırdır` göstərmir və e-Taxes əməliyyatını başlamır.

Daimi bərpa komponentləri:
- `%LOCALAPPDATA%\TaxData\Agent\taxdata-agent.jar`
- `%LOCALAPPDATA%\TaxData\Agent\start-agent.ps1`
- `%LOCALAPPDATA%\TaxData\Agent\.install-complete`
- `%LOCALAPPDATA%\TaxData\watch-agent.ps1`
- `%LOCALAPPDATA%\TaxData\Recovery\TaxData-Agent-Repair.cmd`

Watchdog agent/JAR/start/Java/repair CMD çatışmazlığını görərsə repair başladır. Bütün `%LOCALAPPDATA%\TaxData` qovluğu silinərsə lokal bərpa prosesi də fiziki olaraq silindiyi üçün sayt agentin yoxluğunu aşkarlayıb setup CMD-ni yenidən endirmək üçün bərpa modalı açır.

## Automatic updates from 6.8.5
The watchdog queries `/agent/manifest` approximately once per minute. If the server advertises a newer Agent, it waits for any active e-Taxes task to finish, downloads the latest signed-by-distribution bootstrap/JAR metadata from the configured TaxData origin, verifies the expected JAR SHA-256 during setup, and replaces the Agent using the existing rollback path. 6.7.3 installations need one final manual upgrade to 6.8.5 before this behavior is available.
