# TaxData Production Checklist

1. `APP_ADMIN_PASSWORD`, Supabase və digər secret-ləri yalnız environment variables vasitəsilə saxlayın.
2. Build: `./gradlew clean bootJar agentBootJar`.
3. Gözlənilən artefaktlar: `build/libs/taxdata-server.jar` və `build/libs/taxdata-agent.jar`.
4. Railway/Docker deploy-dan sonra `/actuator/health` endpoint-ini yoxlayın.
5. Windows kompüterdə ilk e‑Taxes istifadəsində **Agent quraşdır** → saytdakı **Bəli** → `TaxData-Agent-Setup.cmd` faylını bir dəfə açın.
6. Agent statusunun `TaxData Local Agent · 6.8.5` göstərdiyini yoxlayın.
7. Test VÖEN ilə ASAN İmza login, qaimə siyahısı, Excel export və registr import axınını yoxlayın.
8. Təlim mərkəzində video və bütün 8 bölmə təlimatının açıldığını yoxlayın.
