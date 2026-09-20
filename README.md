# TaxData 7.1.2 · Production UX, axtarışlı tərəf seçimi və Şablon Studiyası

Bu paketdə tətbiqin əsas interfeysi V7.1 production quruluşuna keçirilib: bir bölmə-bir ekran menyusu, admin tərəfindən istifadə başlanğıc/bitmə vaxtı, istifadəçidə sağ yuxarıda canlı qalan müddət, görünən çıxış düyməsi, yeni sənəd ön baxışı və bloklanmayan çap axını əlavə edilib. Müqavilə və alt-sənəd arxivləri frontend naviqasiyasından çıxarılıb; köhnə backend məlumatları uyğunluq üçün saxlanılır. Fiziki şəxs QR/TT şablonları və göndərilən qaimə eFP Excel/XML strukturu verilmiş nümunələrə uyğunlaşdırılıb.


## V7.1 əlavə production yenilikləri
- Alt sənədlərdə **İcraçı / Satıcı** və **Sifarişçi / Alıcı** seçimləri ad, VÖEN, rol, direktor və ünvan üzrə axtarışlı sürətli seçim sahəsinə çevrilib. Klaviatura ilə ↑/↓/Enter və təmizləmə dəstəyi var.
- Alt sənəd səhifəsində müqavilə yaratma/arxiv düymələri çıxarılıb; yalnız sənədin mətnində istifadə olunan Müqavilə № və Müqavilə tarixi parametrləri saxlanılıb.
- **Şablonlar** bölməsi tam Şablon Studiyasına çevrilib: hüquqi/fiziki tip ayrıca, HF/QR/TT ayrıca idarə olunur; faktiki aktiv Excel export, drag-drop Excel import, saytda hüceyrə mətni redaktəsi/silinməsi, dinamik sahələrin sürüklə-burax yerləşdirilməsi/silinməsi, yadda saxlama və ayrıca standarta qaytarma mövcuddur.
- Qalan istifadə müddəti sağ yuxarıda canlı görünür və Çıxış düyməsi daimi saxlanılır.

**Qeyd:** TaxData Local Agent ayrıca komponentdir və onun uyğunluq baseline versiyası 6.8.5 olaraq qalır. Tətbiqin V7.1 olması Agent protokol versiyasını dəyişdirmir.

---

## 6.8.5 — ASAN İmza form detection fix
- ASAN İmza login formu artıq yalnız `#phone/#userId` selector-ları ilə məhdudlaşmır.
- Açıq browser səhifələri və iframe-lər yoxlanılır; telefon/User ID sahələri atribut heuristikası ilə də tanınır.
- Lazım olduqda görünən `ASAN İmza` giriş seçimi avtomatik kliklənir.
- Form tapılmasa URL/frame/input diaqnostikası texniki gedişata yazılır və lokal `Downloads/TaxData/eTaxes/diagnostics` qovluğunda screenshot saxlanır.


## 6.8.5 — Standart istifadəçi üçün birbaşa Agent startı
- Quraşdırma Agent-i artıq ikinci gizli PowerShell vasitəsilə yox, birbaşa `java.exe` prosesi kimi başladır.
- Watchdog da marker-dən Java/origin məlumatını oxuyub Agent-i birbaşa başladır; PowerShell start skripti yalnız fallback-dır.
- HKCU autostart Agent üçün birbaşa Java command istifadə edir; administrator hüququ tələb olunmur.
- TaxData və private Temurin fayllarında cari istifadəçi səviyyəsində `Unblock-File` tətbiq olunur.
- Start alınmasa `agent-launch.log`, `agent-stdout.log`, `agent-stderr.log` real səbəbi göstərir.
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
# TaxData — E‑Qaimə və Sənəd Platforması · Production 6.8.5

Bu production versiyada Railway serverində browser binary-si yoxdur və runtime-da heç bir browser yüklənmir. e‑Taxes əməliyyatı istifadəçinin öz Windows kompüterində TaxData Local Agent vasitəsilə aparılır.

## e‑Taxes necə işləyir

1. İstifadəçi e‑Taxes bölməsində bir dəfə **Agent quraşdır** düyməsini basır.
2. Saytdakı **Bəli / Xeyr** pəncərəsi quraşdırma icazəsini alır; **Bəli** seçiləndə `TaxData-Agent-Setup.cmd` avtomatik endirilir və fayl açıldıqda əlavə B/X sorğusu olmadan agenti `%LOCALAPPDATA%\TaxData\Agent` altına user-level qurur. Administrator hüququ tələb olunmur.
3. Java 21 kompüterdə yoxdursa həmin ilkin icazə çərçivəsində yalnız agent üçün private Java runtime qurulur. **Browser heç vaxt yüklənmir.**
4. Agent Windows açılışında arxa planda başlayır (`HKCU Run`).
5. Agent əvvəl sistemin default Chromium-əsaslı browserini tapır; uyğun deyilsə kompüterdə quraşdırılmış Chrome, Edge, Brave, Chromium, Opera və ya Vivaldi-ni axtarır. Tapmasa istifadəçi bir dəfə browser `.exe` faylını/qovluğunu seçir və seçim yadda saxlanılır.
6. Railway səhifəsi lokal agentə yalnız `127.0.0.1` üzərindən qoşulur. Müasir browser ilk dəfə loopback/local network icazəsi göstərə bilər; istifadəçi icazə verdikdən sonra sonrakı istifadələr avtomatikdir.
7. ASAN İmza girişi, e‑Taxes sessiyası və e‑Taxes API sorğuları istifadəçinin öz kompüterindən və öz internet IP-sindən gedir. Telefon, ASAN istifadəçi ID-si və e‑Taxes JWT Railway serverinə göndərilmir.
8. Excel lokal olaraq `Downloads\TaxData\eTaxes` qovluğuna yazılır. “Registrə əlavə et” seçilibsə hazır Excel istifadəçinin aktiv Railway sessiyasına upload edilir.

## VÖEN məhdudiyyəti

Adi istifadəçi üçün hər e‑Taxes tapşırığından əvvəl Railway qısaömürlü, VÖEN-ə bağlı agent icazəsi yaradır. Lokal agent həmin icazəni serverdə doğrulamadan prosesə başlamır. İlk uğurlu e‑Taxes autentifikasiyasından sonra VÖEN serverdə həmin hesaba bağlanır. Sonrakı başqa VÖEN cəhdləri backend-də bloklanır. Admin limitsizdir və admin panelindən istifadəçinin VÖEN bağını sıfırlaya bilər.

## Browser prinsipi

Playwright yalnız agentin avtomatlaşdırma kitabxanasıdır. Browser binary-si JAR-a daxil edilmir, Railway image-a daxil edilmir və installer tərəfindən yüklənmir. Lokal agent yalnız kompüterdə artıq mövcud Chromium-əsaslı browser executable-ını işə salır.

## Agentin özünü bərpa etməsi

Production 6.8.5-də lokal agent üçün user-level watchdog var. Agent sonradan silinsə və ya dayansa watchdog lokal statusu aşkarlayır, `/agent/bootstrap` bərpa modulunu TaxData serverindən yenidən yükləyir, natamam faylları təmizləyir, JAR/private Java 21 komponentlərini bərpa edir və agenti yenidən başladır. Watchdog `Agent` qovluğundan kənarda saxlanılır ki, `Agent` qovluğunun silinməsi bərpa mexanizmini özü ilə silməsin.

## Bölmə üzrə video təlimatlar

Production menyusunda görünən 1, 2, 2A, 3, 4 və 5-ci iş bölmələri üçün **Təlimata bax** düyməsi var; **Digər** menyusunda isə ayrıca backup/təlim/parol videosu mövcuddur. Müqavilə və alt-sənəd arxiv bölmələri production frontend-də göstərilmir. Videolar `src/main/resources/static/training/` daxilində 1280×720 H.264/MP4 formatında saxlanılır.

Legacy müqavilə məlumatları backend uyğunluğu üçün saxlanılır; production frontend naviqasiyasında göstərilmir.

### Lokal `.env`
`START_WINDOWS.bat` layihe kokundeki `.env` ve `.env.local` fayllarini avtomatik yukleyir. Real parollar release ZIP-e daxil edilmir. Lokal production testi ucun `.env.server.example` faylini `.env` kimi kopyalayin ve real deyerleri yazin. Railway-da eyni acarlar Variables bolmesinde verilir.


## V7.1.9 — Supabase Vault auto key

Production cloud backup üçün istifadəçi/admin ayrıca encryption key yaratmır. Backend ilk startup-da Supabase PostgreSQL/Vault daxilində 32-bayt AES-256 master key yaradır və sonrakı backup-larda eyni Vault secret-dən istifadə edir. `CLOUD_BACKUP_ENCRYPTION_KEY` Railway variable artıq tələb olunmur. Ətraflı: `SUPABASE_VAULT_AUTO_KEY_7.1.9.md`.


## Tam Transfer Backup (.tdbackup) — V7.1.13
`07.01 Backup və lokal yaddaş` bölməsində parolla AES-256-GCM şifrələnmiş tam transfer yaradılıb başqa kompüterdə bərpa edilə bilər. Paket workspace iş datasını, istifadəçiyə aid SQL rekvizit snapshot-unu və faktiki standart şablon nüsxələrini daşıyır. DB parolları, login password hash-ları, session/device/token və admin təhlükəsizlik icazələri transferə daxil edilmir.
