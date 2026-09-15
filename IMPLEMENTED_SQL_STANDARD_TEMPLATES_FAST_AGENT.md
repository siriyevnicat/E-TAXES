# TaxData — SQL rekvizitləri, vahid şablonlar və sürətli Local Agent

## 1. Şirkət rekvizitləri SQL-də
- Yeni `company_registry` PostgreSQL/H2 cədvəli yaradılır.
- Hər qeyd `owner_user_id` ilə istifadəçiyə bağlanır və bütün CRUD sorğuları həmin user id ilə scope olunur.
- `(owner_user_id, id)` composite primary key, `(owner_user_id, identity_hash)` unique constraint və owner/name/VOEN indeksləri var.
- `owner_user_id -> app_users(id)` foreign key ilə referensial bütövlük qorunur.
- VÖEN (yoxdursa normallaşdırılmış ad) SHA-256 identity hash ilə duplicate nəzarətində istifadə olunur.
- Köhnə `workspace-state.json` şirkət qeydləri ilk girişdə SQL-ə miqrasiya edilir və lokaldan silinir.
- Köhnə `companies-*` upload qovluqları təmizlənir.
- Yeni şirkət Excel importu yalnız parse müddətində müvəqqəti saxlanır və SQL importundan sonra dərhal silinir.
- Alt sənəd və müqavilə generasiyası rekvizitləri yalnız SQL registry-dən götürür; client-in göndərdiyi company object və köhnə company upload id authoritative deyil.

## 2. Təhlükəsizlik
- Production PostgreSQL üçün `sslmode=require`, `verify-ca` və ya `verify-full` məcburidir; TLS olmadan startup dayandırılır.
- SQL sorğuları `JdbcTemplate` parametrli query-lərlə işləyir.
- Rekvizit CRUD və sənəd generasiyası authenticated `CurrentUserContext.id()` ilə scope olunur.
- Mövcud sistemdə BCrypt(12), session token hash, device approval, login lockout, HttpOnly/SameSite cookie, Same-Origin mutating API filter, CSP/HSTS/no-store qorunmaları qalır.
- DB credential-lar source code-a yazılmır; production ENV-dən gəlir.

## 3. Vahid faktiki şablon rejimi
- HF / QR / TT üçün custom upload və custom mapping generation-dan çıxarılıb.
- Bütün kompüterlər eyni `src/main/resources/excel-templates/` faktiki şablonlarını istifadə edir.
- Köhnə active template/mapping məlumatları bootstrap-da təmizlənir; `template-*` və `contract-template-*` upload qovluqları silinir.
- Müqavilə də yalnız `contract-templates/muqavile_faktiki.docx` istifadə edir.
- UI-dan şablon import/redaktə/reset axını çıxarılıb; yalnız faktiki standartı endirmək qalır.

## 4. Wrap Text və sətir hündürlüyü
`DocumentGeneratorService.stabilizeTextAndRows(...)` həm standart şablon endiriləndə, həm də sənəd generasiyasından əvvəl işləyir:
- mətn hüceyrələrində Wrap Text aktiv edilir;
- Shrink To Fit söndürülür;
- mövcud row height azaldılmır;
- mətn neçə sətir tələb edirsə minimum hündürlük hesablanıb artırılır;
- horizontal və vertical merged cell-lər ayrıca nəzərə alınır;
- nəticə Excel faylının özündə yazıldığı üçün kompüterdən asılılıq azalır.

## 5. Local Agent sürət optimizasiyası
- Virtual thread executor saxlanılıb.
- Köhnə batch-barrier yanaşması çıxarılıb: əvvəl 10/20 sorğunun hamısının bitməsini gözləyib sonra növbəti batch-a keçirdi.
- Yeni model `ExecutorCompletionService + Semaphore` ilə 6–32 bounded parallel detail fetch edir.
- Default concurrency 20-dir; UI 6/10/16/20/24/28/32 seçimləri verir.
- Cavablar bitdiyi anda toplanır; bir gecikən request növbəti sorğuların başlamasını bloklamır.
- 401 olarsa bütün batch deyil, yalnız 401 qaytaran invoice ID-lər token refresh-dən sonra təkrar götürülür.

## Yoxlamalar
- `node --check src/main/resources/static/app.js` — OK.
- `git diff --check` — OK.
- `CompanyRegistryService` minimal dependency stub-ları ilə JDK 21 compile — OK.
- Tam `./gradlew compileJava` bu analiz mühitində `services.gradle.org` xarici şəbəkə çıxışı bağlı olduğuna görə Gradle 8.14.4 distribution endirə bilmədi; bu repository source xətası deyil, build environment network məhdudiyyətidir.

## Təhvil-Təslim — “Təhvil verdi” imzaüstü sahəsi
- Aşağı imza blokunda “Təhvil verdi:” ilə “İmza__________” arasındakı sahə hüquqi/fiziki şəxs fərq etmədən həmişə boş saxlanılır.
- Direktorun, səlahiyyətli şəxsin və ya fiziki şəxsin adı bu sahəyə avtomatik yazılmır.
- Standart `tehvil_teslim_faktiki.xlsx` və `tehvil_teslim_fiziki.xlsx` şablonlarında həmin xana boşdur; generator da artıq onu doldurmur.
