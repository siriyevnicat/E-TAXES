# TaxData V7.1.13 — Tam Transfer Backup

Bu versiyada başqa kompüterə təhlükəsiz keçid üçün `.tdbackup` formatında parolla şifrələnmiş Tam Transfer Backup əlavə edildi.

## Backup-a daxil olanlar
- cari lokal-first workspace ZIP-i: qaimələr, göndərilən qaimələr, müqavilələr, yaradılmış alt sənədlər, EFP paketləri və workspace ayarları;
- istifadəçinin PostgreSQL `company_registry` rekvizit bazası;
- PostgreSQL hesab/e-Taxes profilinin və bölmə icazələrinin audit snapshot-u;
- proqramın faktiki standart Excel/Word şablonlarının nüsxəsi;
- daşına bilən brauzer ayarı (`activePage`).

## Təhlükəsizlik
- paket AES-256-GCM ilə şifrələnir;
- açar istifadəçinin verdiyi paroldan PBKDF2-HMAC-SHA256 ilə yaradılır (minimum 210,000 iterasiya);
- parol serverdə və backup daxilində saxlanmır;
- PostgreSQL host/user/password, istifadəçi password hash-ları, session tokenləri, device hash-ları, e-Taxes agent grant tokenləri, Vault/cloud encryption key-ləri və server-global setting-lər export edilmir;
- account role/status, adminın verdiyi bölmə icazələri və e-Taxes profilinin serverdəki cari dəyərləri transfer faylından geri yazılmır. Bunlarda PostgreSQL server həmişə əsas mənbədir.

## Bərpa davranışı
- cari workspace və cari hesabın SQL rekvizitləri backup ilə əvəz edilir;
- rekvizit ID-ləri yeni hesab üçün yenidən yaradılır və workspace daxilində müqavilə/arxiv referensləri avtomatik remap olunur;
- custom/per-machine template göstəriciləri sıfırlanır, proqram yenə daxili faktiki standart şablonlardan istifadə edir;
- əməliyyat zamanı xəta çıxarsa əvvəlki workspace və SQL rekvizitləri rollback edilməyə çalışılır;
- bərpadan sonra yeni lokal IndexedDB snapshot yaradılır və cloud backup yenilənir.

## UI
`07.01 Backup və lokal yaddaş` bölməsinə:
- `Tam Transfer Backup yarat`
- `Tam transfer seç`
- `Tam Transfer bərpa et`
əməliyyatları əlavə edildi.
