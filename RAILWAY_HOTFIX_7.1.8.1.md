> **Tarixi qeyd:** Bu sənəd V7.1.8.x üçündür. V7.1.9-da manual `CLOUD_BACKUP_ENCRYPTION_KEY` ləğv edilib; açar Supabase Vault-da avtomatik yaradılır.

# Railway startup hotfix 7.1.8.1

Problem: V7.1.8 production profile defaulted CLOUD_BACKUP_ENABLED to true. If CLOUD_BACKUP_ENCRYPTION_KEY was not configured yet, ProductionConfigGuard intentionally aborted application startup.

Fix: Production default is now CLOUD_BACKUP_ENABLED=false. Existing deployments start normally without the new secret. To enable encrypted 3-day cloud backup, configure CLOUD_BACKUP_ENCRYPTION_KEY (Base64, exactly 32 bytes) and CLOUD_BACKUP_ENABLED=true in Railway Variables.

This preserves strict validation once cloud backup is explicitly enabled.
