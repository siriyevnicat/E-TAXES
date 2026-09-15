# Railway build fix — TaxData V7.1.3

Bu paket Railway üçün ayrıca sərtləşdirilib.

## Dəyişikliklər

1. ZIP-in içində `Dockerfile`, `build.gradle`, `gradlew`, `src` və `railway.toml` birbaşa kökdədir. Railway Dockerfile-i source root-da görür.
2. Build mərhələsi `gradle:8.14.4-jdk21` rəsmi image-dən istifadə edir. Buna görə build zamanı Gradle wrapper-in `services.gradle.org`-dan distribution endirməsi tələb olunmur.
3. `bootJar` daxilində lokal agent `bootInf` copy-spec ilə dəqiq `BOOT-INF/classes/agent-dist/taxdata-agent.jar` yoluna yerləşdirilir.
4. Healthcheck `/agent/health` əvəzinə standart `/actuator/health` istifadə edir. Bu endpoint agent faylının ayrıca diskdə mövcudluğundan asılı deyil.
5. Docker build iki artefaktı və server JAR daxilində agent/setup resurslarını build mərhələsində yoxlayır.

## Railway service variables

Production start üçün aşağıdakılar tələb olunur:

- `SPRING_PROFILES_ACTIVE=prod` (Dockerfile özü verir)
- `SUPABASE_DB_URL`
- `SUPABASE_DB_USER`
- `SUPABASE_DB_PASSWORD`
- `APP_ADMIN_PASSWORD`
- istəyə görə `APP_ADMIN_USERNAME`

`PORT` Railway tərəfindən verilir və tətbiq `server.port=${PORT:8080}` ilə avtomatik istifadə edir.

## GitHub / Railway root

Repository-də bu fayllar birbaşa root-da olmalıdır:

- `Dockerfile`
- `build.gradle`
- `settings.gradle`
- `gradlew`
- `gradle/`
- `src/`

Bu V7.1.3 ZIP elə bu formada hazırlanıb; ayrıca `TaxData/` üst qovluğu yoxdur.
