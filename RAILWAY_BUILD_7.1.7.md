# TaxData V7.1.7 — Latest EDV li 01 A:R template alignment

- Yeni qaimə redaktoru istifadəçinin son `EDV li 01(1).xlsx` şablonuna uyğun A:R / 18 sütun quruluşuna keçirildi.
- Göndərən, Qəbul edən, VÖEN, Əsas və Əlavə qeydlər Excel-dəki yuxarı sətirlər kimi redaktorda göstərilir.
- Məhsul sütunları 1–18 başlıqları və qruplaşdırılmış header quruluşu ilə eyni ardıcıllıqdadır.
- “Sətir əlavə et” yeni 18 sütunlu sətr yaradır; Enter və Excel paste dəstəyi A:R-ə uyğunlaşdırılıb.
- `/api/outgoing/template` artıq istifadəçinin göndərdiyi son şablonun özünü qaytarır.
- Excel import parser-i bu yeni tək-qaimə şablonunu birbaşa oxuyur və köhnə formatla geriyə uyğunluq saxlanılır.
- QAIME_1 / version 304 XML xəritələnməsi yeni sütun sırasına uyğun saxlanılıb.
