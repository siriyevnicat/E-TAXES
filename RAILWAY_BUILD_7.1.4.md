# Railway build fix — TaxData V7.1.4

## Düzəldilən konkret xəta
Railway Java compile mərhələsində `OutgoingInvoiceService.java:91` sətrində lambda daxilində `h` dəyişəni istifadə edilirdi. `h` əvvəl `null`, sonra isə header map ilə təyin olunduğuna görə Java onu **effectively final** hesab etmirdi və build dayanırdı.

Köhnə kod:
```java
boolean officialValues = List.of(...).stream().anyMatch(k -> !text(row, h, k, fmt, ev).isBlank());
```

Yeni kod lambda istifadə etmir və eyni məntiqi adi dövr ilə icra edir:
```java
boolean officialValues = false;
for (String officialKey : List.of(...)) {
    if (!text(row, h, officialKey, fmt, ev).isBlank()) {
        officialValues = true;
        break;
    }
}
```

Bu dəyişiklik eFP Excel import məntiqini dəyişmir; yalnız Java compile xətasını aradan qaldırır.

## Yoxlamalar
- Xətalı lambda layihədən çıxarılıb.
- Bütün Java mənbələrində `effectively final` / lambda-capture tipli eyni xəta üçün statik javac yoxlaması aparılıb; əlavə belə xəta aşkarlanmayıb.
- ZIP root-da `Dockerfile`, `build.gradle`, `gradlew`, `src/` və `railway.toml` saxlanılıb.
- Tam Gradle build lokal mühitdə internet/DNS olmadığı üçün dependency yüklənməsi mərhələsinə keçə bilmir. Railway logunda isə Gradle image və dependency şəbəkəsi işləyir; ona görə bu paket həmin konkret compile bloklayıcısını düzəldir.
