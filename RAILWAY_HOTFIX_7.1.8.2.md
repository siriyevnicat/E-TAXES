> **Tarixi qeyd:** Bu sənəd V7.1.8.x üçündür. V7.1.9-da manual `CLOUD_BACKUP_ENCRYPTION_KEY` ləğv edilib; açar Supabase Vault-da avtomatik yaradılır.

# Railway Hotfix 7.1.8.2

Problem: `application-prod.properties` used `app.cloud-backup.encryption-key=${CLOUD_BACKUP_ENCRYPTION_KEY}` without an empty default. Spring resolves property placeholders before `ProductionConfigGuard` can inspect `CLOUD_BACKUP_ENABLED`, so an unset encryption-key variable crashed startup even when cloud backup was disabled.

Fix: production now uses `app.cloud-backup.encryption-key=${CLOUD_BACKUP_ENCRYPTION_KEY:}`. With no cloud variables configured, the application starts with cloud backup disabled. To enable the 3-day encrypted backup, configure both `CLOUD_BACKUP_ENABLED=true` and a valid Base64 32-byte `CLOUD_BACKUP_ENCRYPTION_KEY`.
