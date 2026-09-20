# TaxData V7.1.9 — Supabase Vault auto key

Cloud backup üçün artıq `CLOUD_BACKUP_ENCRYPTION_KEY` tələb olunmur.

Production startup zamanı backend:
1. Supabase-də `supabase_vault` və `pgcrypto` extension-larını idempotent şəkildə yoxlayır/aktivləşdirir.
2. `vault.decrypted_secrets` içində `taxdata_cloud_backup_master_key_v1` adlı secret-i axtarır.
3. Secret yoxdursa PostgreSQL daxilində `extensions.gen_random_bytes(32)` ilə 32 bayt təsadüfi AES-256 açarı yaradır və `vault.create_secret(...)` ilə birbaşa Vault-a yazır.
4. Sonrakı startup-larda eyni secret istifadə olunur.
5. Açar brauzerə, istifadəçi kompüterinə və source code-a verilmir.

Production-da cloud backup default aktivdir (`CLOUD_BACKUP_ENABLED` verilməsə `true`). Vault inicializasiya uğursuz olsa əsas sayt çökmür; cloud backup qeyri-operational qalır və status endpoint səbəbi göstərir.

3 günlük retention, SHA-256 bütövlük yoxlaması, AES-256-GCM workspace ZIP şifrələməsi və istifadəçi üzrə backup ayrılığı saxlanılır.
