# TaxData V7.1.8 — 3 qat production backup

## Saxlama modeli
1. IndexedDB — istifadəçinin brauzerində master workspace ZIP.
2. Seçilmiş lokal qovluq — cari ZIP + son 10 versiya.
3. Supabase PostgreSQL — AES-256-GCM ilə şifrələnmiş workspace ZIP, 3 günlük TTL.

## Yeni kompüter
Təsdiqlənmiş cihazda login zamanı IndexedDB backup tapılmazsa ən son aktiv PostgreSQL backup avtomatik bərpa edilir və yeni kompüterin IndexedDB-sinə yazılır. Sonra production üçün lokal backup qovluğu seçimi tələb olunur.

## Cloud retention
- Default TTL: 3 gün
- Auto backup replace window: 60 dəqiqə
- Max versions/user: 96
- Eyni ZIP: SHA-256 deduplikasiya
- Logout/manual: ayrıca qorunan backup cəhdi
- Müddəti bitmiş sətirlər saatlıq cleanup ilə silinir

## Məxfilik
PostgreSQL `encrypted_zip` sütununda açıq ZIP saxlanmır. AES-256-GCM istifadə olunur. V7.1.9-dan etibarən AES-256 master key Supabase PostgreSQL daxilində avtomatik yaradılır və Supabase Vault-da şifrəli saxlanılır; istifadəçi və operator ayrıca açar yaratmır.

## Railway / Supabase ENV
```
CLOUD_BACKUP_ENABLED=true
CLOUD_BACKUP_RETENTION_DAYS=3
CLOUD_BACKUP_MAX_VERSIONS=96
CLOUD_BACKUP_MAX_ZIP_BYTES=104857600
CLOUD_BACKUP_AUTO_REPLACE_MINUTES=60
```

## Paylaşma
`Cloud ZIP endir / paylaş` normal workspace ZIP endirir. Faylı başqa təsdiqlənmiş istifadəçi/kompüter `Lokal backup bərpa et` ilə aça bilər.
