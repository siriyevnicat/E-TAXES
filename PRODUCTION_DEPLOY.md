# Railway Production Deploy — 6.8.5 Local Agent

## GitHub / Railway

Repo root-da `Dockerfile`, `build.gradle`, `gradlew`, `src` olmalıdır. Railway Dockerfile ilə iki JAR build edir:

- `/app/app.jar` — əsas web server
- `/app/agent/taxdata-agent.jar` — istifadəçinin bir dəfə endirib quraşdırdığı lokal agent

Railway image `eclipse-temurin` istifadə edir. Playwright/Chromium Docker image istifadə edilmir və serverdə browser binary-si yoxdur.

## Railway Variables

```text
SPRING_PROFILES_ACTIVE=prod
SUPABASE_DB_URL=...
SUPABASE_DB_USER=...
SUPABASE_DB_PASSWORD=...
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=...
```

Köhnə deployment-dən qalıbsa bunları silin:

```text
PLAYWRIGHT_BROWSERS_PATH
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD
PLAYWRIGHT_SKIP_BROWSER_GC
ETAXES_BROWSER_SERVER_BUNDLED
ETAXES_BROWSER_EXECUTABLE_PATH
ETAXES_PROXY_SERVER
ETAXES_PROXY_USERNAME
ETAXES_PROXY_PASSWORD
```

Server regionunun e‑Taxes-ə təsiri yoxdur, çünki e‑Taxes login və API trafiki Railway-dən deyil, lokal agent vasitəsilə istifadəçinin kompüterindən çıxır.

## İlk istifadə

İstifadəçi e‑Taxes bölməsində “Agent quraşdır” düyməsini basır, endirilən CMD faylını bir dəfə açıb icazə verir. Sonrakı girişlərdə agent Windows ilə birlikdə arxa planda başlayır.
