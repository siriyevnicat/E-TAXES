# TaxData 6.8.5 · localhost:8080 yoxlaması

Windows-da lokal yoxlama üçün `START_WINDOWS.bat` istifadə edin.

Skript:
1. 8080 portunda köhnə proses qalıb-qalmadığını yoxlayır.
2. `clean bootJar` ilə server və `taxdata-agent.jar` artefaktını birlikdə build edir.
3. Build zamanı agent server JAR-ın içinə daxil edilməyibsə build dayandırılır.
4. Yalnız tam build alınandan sonra `taxdata-server.jar` 8080-də başladılır.

Server açıldıqdan sonra əvvəlcə bu endpointi yoxlayın:

`http://localhost:8080/agent/health`

Cavab `status=UP`, `version=6.8.5` və `jarSize` göstərməlidir. Bundan sonra saytdakı Agent bərpa/quraşdırma düyməsini yoxlayın.

Əgər 8080-də əvvəlki versiya işləyirsə, brauzer yeni faylları açsa belə backend köhnə 503 cavabını verə bilər. Buna görə köhnə Java/Gradle prosesini bağlamadan yeni lokal testi başlamayın.


### 6.8.5: köhnə 8080 prosesi
`START_WINDOWS.bat` 8080 portunda əvvəlki TaxData prosesini görərsə onu avtomatik dayandırır və port boşaldıqdan sonra yeni build-i başladır. Başqa proqram 8080-i tutursa təhlükəsizlik üçün həmin proses bağlanmır.

Əl ilə təcili bağlama lazım olarsa (məsələn əvvəlki 6.6.8 paketində):
```bat
taskkill /PID 14088 /F
```
Sonra yeni paketdə `START_WINDOWS.bat` açın.

## Java tələb olunmur (6.8.5)
`START_WINDOWS.bat` sistem Java-sına bağlı deyil. Java 21 JDK tapılmadıqda TaxData portable JDK 21-i `%LOCALAPPDATA%\TaxData\BuildJdk21` qovluğuna avtomatik endirir, `JAVA_HOME`/`PATH` dəyərlərini yalnız həmin CMD sessiyası üçün qurur və build-i davam etdirir. Administrator icazəsi tələb olunmur.

## `.env` lokal startda avtomatik yuklenir

TaxData 6.8.5-den etibaren `START_WINDOWS.bat` serveri acmazdan evvel layihe kokundeki `.env`, sonra `.env.local` faylini oxuyur. `.env.local` eyni acar ucun `.env` deyerini override ede biler. Secret deyerler CMD-de cap edilmir.

- Lokal production testi ucun movcud `.env` faylinizi TaxData qovlugunun kokunde saxlayin.
- `.env` yoxdursa `.env.server.example` faylini `.env` kimi kopyalayib real deyerleri yazin.
- Railway `.env` faylini image-a daxil etmir; production secretleri Railway Variables bolmesinde saxlanmalidir.
- `SPRING_PROFILES_ACTIVE=prod` olduqda DB/admin ucun vacib environment deyisenleri yoxdursa server qesden baslamir ve catismayan acarlarin adini gosterir.
