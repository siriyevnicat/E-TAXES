## 6.8.5 — Legacy local-agent takeover
- Köhnə lokal agent 47631 portunu tutursa yeni installer onu legacy agent kimi tanıyır, proses/watchdog-u dayandırır və portu boşaldır.
- Köhnə autostart və lokal qovluq yalnız yeni TaxData Agent JAR + Java 21 + start + watchdog + repair + health-check tam uğurlu olduqdan sonra silinir.
- 47631 portunu həqiqətən başqa proqram tutursa həmin proqram avtomatik dayandırılmır.

# Production 6.8.5 dəyişiklikləri

- Railway server browseri və Playwright browser image-ı tam çıxarıldı.
- e‑Taxes əməliyyatı TaxData Local Agent ilə istifadəçinin kompüterində işləyir.
- Browser install/download əmri yoxdur.
- Agent default uyğun browseri tapır; lazım olsa mövcud browser qovluğu seçilir.
- Agent bir dəfə user icazəsi ilə qurulur, sonra Windows açılışında arxa planda başlayır.
- Java 21 yoxdursa həmin ilkin icazə ilə yalnız agent üçün private JRE quraşdırılır; browser quraşdırılmır.
- Railway HTTPS səhifəsi üçün loopback CSP/CORS/Local Network Access dəstəyi əlavə edildi.
- e‑Taxes telefon/ASAN ID/JWT Railway serverinə göndərilmir.
- e‑Taxes API trafiki istifadəçinin öz IP-sindən gedir; Railway regionu e‑Taxes trafikinin çıxış ölkəsini müəyyən etmir.
- Adi istifadəçi üçün server tərəfindən qısaömürlü VÖEN grantı tələb olunur; agent grantı doğrulamadan başlamır.
- İlk uğurlu e‑Taxes autentifikasiyası VÖEN-i hesaba bağlayır; adi istifadəçi 1 VÖEN, admin limitsizdir.
