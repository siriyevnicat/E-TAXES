# TaxData V7.1.3 — Production validation

## Release yoxlamaları
- Frontend JavaScript: `node --check` keçdi.
- HTML DOM ID audit: 307 ID, təkrar ID yoxdur.
- Alt sənəd sürətli tərəf seçimi: `executorCompanySearch` və `ordererCompanySearch` mövcuddur; gizli select dəyərləri köhnə generation məntiqi ilə uyğun saxlanılır.
- Alt sənəd hissəsində görünən müqavilə yarat/arxiv düymələri yoxdur; müqavilə №/tarix yalnız sənəd parametri kimi qalır.
- Şablon Studiyası: hüquqi/fiziki HF/QR/TT, aktiv Excel export, `.xlsx` drag-drop import, sayt redaktoru, hüceyrə mətnini silmə, dinamik mapping silmə/yerləşdirmə, undo, save/activate və standarta reset məntiqi mövcuddur.
- Şablon statusu həm xüsusi `.xlsx`, həm də yalnız xüsusi dinamik mapping olduqda fərdiləşdirilmiş vəziyyəti göstərir.
- Server `.xlsx` upload yoxlaması: format, 12 MB limit, real XSSFWorkbook açılışı, ən az 1 sheet, ilk sheet üçün 5000 sətir limiti.
- Excel şablonları Office ZIP konteyner bütövlüyü yoxlamasından keçdi.
- Fiziki QR-də sifarişçi imza bloku `Fiziki şəxs` məntiqindədir.
- Fiziki TT-də aşağı `Təhvil verdi / Təhvil aldı` hissəsində imzanın üstü boşdur.
- Göndərilən qaimə şablonu təmiz A:Y (25 sütun) strukturundadır və nümunə məhsul/şirkət datası saxlamır.
- Təlim MP4 faylları `ffprobe` yoxlamasından keçdi.
- Linux/macOS start skripti `bash -n` yoxlamasından keçdi.
- Admin access-window server tərəfdə login və hər API sessiya autentifikasiyasında yoxlanır; müddət dəyişəndə aktiv sessiyalar silinir.
- İstifadəçi üçün sağ-yuxarı canlı qalan müddət və daimi `Çıxış` düyməsi mövcuddur.

## Build qeydi
Bu iş mühitində Gradle wrapper üçün Gradle 8.14.4 distribution/dependency cache mövcud deyil və internet çıxışı bağlıdır. Buna görə tam Spring Boot `bootJar` build-i burada yekun icra olunmayıb. Java 21 compiler ilə dəyişdirilmiş Java fayllarında parser/sintaksis tipli xəta müşahidə edilməyib; tam dependency-aware build production CI/serverdə aşağıdakı əmrlə tamamlanmalıdır:

```bash
./gradlew clean bootJar -x test
```
