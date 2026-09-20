## V7.1.7 — Son EDV li 01 şablonu ilə A:R 1:1 uyğunluq
- İstifadəçinin 04.09.2026 tarixində göndərdiyi `EDV li 01(1).xlsx` yoxlanıldı: şablon A:R üzrə 18 sütundur, əvvəlki A:Y/25 sütun deyil.
- Saytdakı “Yeni qaimə” redaktoru həmin 18 sütun və çoxsəviyyəli başlıqlarla yenidən quruldu.
- Göndərən/Qəbul edən/VÖEN/Əsas/Əlavə qeydlər Excel şablonundakı 1–4-cü sətirlərə uyğun yerləşdirildi.
- Sətir əlavə etmə, Enter naviqasiyası, Excel paste, hesablamalar və QAIME_1 xəritələnməsi A:R sırasına uyğunlaşdırıldı.
- Endirilən Excel nümunəsi istifadəçinin göndərdiyi son şablonun özüdür və yeni importer bu strukturu oxuyur.

# TaxData V7.1.6 Production

## V7.1.6 — Excel A:Y tam vərəq redaktoru

“Yeni qaimə yarat” artıq A:Y 25 sütunu eyni Excel görünüşündə, eyni ardıcıllıqda və tam sətir əlavə etmə məntiqi ilə göstərir. A–H sənəd məlumatları qaimənin bütün sətirlərində sinxron saxlanılır, I–Y məhsul sahələri isə ayrıca redaktə olunur. Excel multi-cell paste və Enter ilə aşağı keçid dəstəklənir.

Railway compile fix və QAIME_1/version 304 paket məntiqi qorunub.
