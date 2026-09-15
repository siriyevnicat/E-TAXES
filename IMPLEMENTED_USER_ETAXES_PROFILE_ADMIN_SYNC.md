# TaxData V7.1.12 — İstifadəçi e‑Taxes profili və Admin sinxronizasiyası

## Tətbiq edilən dəyişikliklər

- Yeni istifadəçi qeydiyyatında ASAN İmza telefon nömrəsi, ASAN İmza İstifadəçi ID və VÖEN məcburidir.
- Bu məlumatlar `app_users` SQL cədvəlində `etaxes_phone`, `etaxes_user_id`, `etaxes_tin` sahələrində qalıcı saxlanılır.
- `profile_updated_at` profil dəyişikliyinin son vaxtını saxlayır.
- Daxili TaxData istifadəçi UUID-si dəyişdirilmir; adminin dəyişdirdiyi “İstifadəçi ID” ASAN İmza İstifadəçi ID-sidir.
- e‑Taxes ekranında Telefon, İstifadəçi ID və VÖEN profil məlumatlarından avtomatik doldurulur.
- Adi istifadəçi üçün bu üç sahə kilidlidir; dəyişiklik Admin paneldən edilir.
- Admin paneldə hər qeyri-admin istifadəçi üçün Telefon, İstifadəçi ID və VÖEN redaktə edilərək SQL-də qalıcı saxlanıla bilər.
- Açıq istifadəçi sessiyası `/api/auth/me` vasitəsilə 10 saniyəlik intervalda profil dəyişikliklərini alır.
- Admin dəyişiklik etdikdə yeni məlumatlar e‑Taxes formasına və brauzerin IndexedDB lokal profil metadata-sına tətbiq olunur.
- İstifadəçinin brauzeri bağlı/offline olarsa yeni profil növbəti girişdə SQL-dən alınaraq lokala yazılır.
- Köhnə lokal backup profil üçün source-of-truth deyil; serverdəki SQL profili üstünlük təşkil edir.

## Təhlükəsizlik

- Local Agent grant artıq Telefon + ASAN İmza İstifadəçi ID + VÖEN üçlüyünü serverdəki hesab profili ilə yoxlayır.
- UI sahələrinin `readOnly` olması tək müdafiə deyil; adi istifadəçi Developer Tools vasitəsilə dəyərləri dəyişsə də server grant vermir.
- Agent grant cədvəli telefon və ASAN ID-ni də saxlayaraq consume/complete mərhələsində eyni üçlüyü tələb edir.
- Local Agent protokol dəyişikliyinə görə agent versiyası `6.8.6` edilib ki, köhnə agent yenilənməyə məcbur olsun.

## Versiya

- TaxData UI: V7.1.12
- TaxData Local Agent: 6.8.6
