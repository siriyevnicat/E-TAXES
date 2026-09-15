# TaxData V7.1.2 · yekun production dəyişiklikləri

## V7.1.2 · Fiziki şəxs QR imza düzəlişi
- Fiziki şəxs Satıcı və ya Sifarişçi olduqda QR imza blokunda rol/şirkət/direktor təkrarı aradan qaldırılıb.
- Fiziki tərəf yalnız `Fiziki şəxs: Ad Soyad Ata adı` və imza xətti ilə göstərilir.
- Fiziki QR Excel standart şablonunda təkrarlanan `Sifarişçi / Fiziki şəxs` başlıqları təmizlənib.



## V7.1.2 · Alt sənəd UX və Şablon Studiyası
- Alt sənədlərdə icraçı/sifarişçi seçimi axtarışlı combobox-a çevrilib; ad, VÖEN, rol, direktor və ünvan üzrə sürətli filtr, klaviatura seçimi və təmizləmə var.
- Alt sənəd hissəsində müqavilə yaratma və müqavilələrə bax düymələri frontend-dən çıxarılıb. Müqavilə № və tarixi sənəd parametri kimi qalır.
- Şablon Studiyasında hüquqi/fiziki tip üzrə HF, QR və TT ayrıca idarə olunur. Aktiv faktiki Excel-i endirmək, Excel-də dəyişib drag-drop import etmək, saytda statik hüceyrə mətnini dəyişmək/silmək, dinamik sahələri yerləşdirmək/silmək, düzəlişləri yadda saxlamaq və yalnız seçilmiş sənəd/tipi standarta qaytarmaq mümkündür.
- Şablon statusları “Standart / Xüsusi aktiv / Xüsusi xəritə” kimi görünür; həm aktiv `.xlsx`, həm də yalnız dinamik mapping fərdiləşdirməsi düzgün göstərilir və workspace backup-a daxildir.
- Təlim materialları yeni axtarışlı tərəf seçimi və Şablon Studiyası axınına uyğun yenilənib.

## İstifadəçi və admin
- Admin hər standart istifadəçi üçün giriş başlanğıc və bitmə tarix-saatı təyin edə bilir.
- Müddət dəyişəndə əvvəlki sessiyalar bağlanır və yeni qayda növbəti girişdən tətbiq olunur.
- Standart istifadəçidə sağ yuxarıda qalan müddət saniyə-saniyə göstərilir; son 24 saat xəbərdarlıq vəziyyətidir.
- Serverdə müddət/admin dəyişikliyi 60 saniyədə bir yenidən yoxlanır.
- Müddət bitdikdə sessiya bağlanır.
- Çıxış düyməsi sağ yuxarıda daim görünür; lokal backup xətası olsa belə server logout sorğusu ayrıca icra olunur.

## Frontend / UX
- Sol menyu ilə bir bölmə-bir ekran production görünüşü.
- Şablonlar açılan menyudadır; Backup/lokal yaddaş, Təlim və Parol əməliyyatları “Digər” altında toplanıb.
- Mobil görünüşdə Şablonlar və Digər üçün birbaşa menyu düymələri saxlanılıb.
- Müqavilə yaratma/arxiv və alt-sənəd arxivi frontend naviqasiyasından gizlədilib.
- Təlim mərkəzindən köhnə müqavilə/arxiv bölmələri çıxarılıb; 1, 2, 2A, 3, 4, 5 bölmələrinin videoları V7.1.2 sol menyu və sağ-yuxarı müddət/çıxış quruluşuna uyğun yenidən hazırlanıb.
- “Digər: Backup, Təlim və Parol” üçün ayrıca V7.1.2 təlim videosu əlavə edilib.

## Sənədlər və çap
- Tam sənəd ön baxışı backend HTML-ni təhlükəsiz Blob iframe-də göstərir; köhnə iframe bloklanması aradan qaldırılıb.
- Çap pəncərəsi istifadəçi klikinin içində əvvəlcədən açılır, sonra hazırlanmış çap URL-nə yönləndirilir; beləliklə async əməliyyatdan sonra popup-blocker problemi minimuma endirilir.
- HF/TT/QR nüsxə sayları saxlanılır və eyni çap paketinə ötürülür.

## Fiziki şəxs şablonları
- Qiymətlərin Razılaşdırılması Protokolunda sifarişçi imza hissəsi yalnız “Fiziki şəxs” məntiqindədir.
- Təhvil-Təslim aktında aşağı “Təhvil verdi / Təhvil aldı” hissəsində imzanın üstündə şəxsin adı yazılmır; sahə boş saxlanılır.

## eFP / göndərilən qaimələr
- Təmiz Excel şablonu nümunə qaimə sütunları əsasında A:Y strukturu ilə saxlanılıb.
- QAIME_1 / version 304 XML-də c1–c17 məhsul sahələri və yekun c1–c10 strukturu verilmiş nümunələrlə uyğunlaşdırılıb.
- eFP ZIP paketində qaimə XML-ləri saxlanılır.
- Rəsmi XSD faylı təqdim edilmədiyi üçün bu paket “rəsmi XSD ilə laborator validasiya olunub” iddiası etmir; struktur istifadəçi tərəfindən verilmiş nümunələrə əsaslanır.

## Yoxlamalar
- Frontend JavaScript sintaksisi: `node --check` keçib.
- HTML ID-ləri: təkrar ID yoxdur; literal JS ID istinadlarında itkin element yoxdur.
- Dəyişdirilmiş XLSX şablonları ZIP/Office konteyner bütövlüyü yoxlamasından keçib.
- Linux/macOS start skriptinin shell sintaksisi yoxlanıb.
- Dəyişdirilmiş auth Java sinifləri ayrıca sintaksis yoxlamasından keçib.
- Tam Gradle build bu iş mühitində Gradle wrapper/dependency-ləri internetdən endirə bilmədiyi üçün burada icra edilməyib; production serverdə/deploy pipeline-da `./gradlew clean bootJar -x test` ilə build edilməlidir.
