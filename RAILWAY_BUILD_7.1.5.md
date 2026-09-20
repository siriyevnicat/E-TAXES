# Railway build · V7.1.5

Bu buraxılış V7.1.4 compile fix üzərində qurulub və Java backend məntiqini dəyişmir. Göndərilən qaimə redaktoru Excel nümunəsindəki A:Y sütunları ilə tam uyğunlaşdırılıb.

## Dəyişiklik
- Qaimə başlığı: A, B, C, D, E, F, G, H sahələri UI-də xəritələnib.
- Məhsul sətri: I:Y arasında 17 rəsmi Excel sütununun hamısı əl ilə redaktə edilə bilir.
- O/R/W/Y təhlükəsiz avtomatik hesab köməkçisi əlavə olunub; aksiz və ƏDV bölgüsü əl ilə də verilə bilər.
- Obyektin adı və kodu save/API/XML axınına UI-dən daxil edilir.
- Manual yaradılan qaimələr `officialFields=true` ilə saxlanır ki, tam Excel sütun dəyərləri serverdə dəyişdirilməsin.
- Excel nümunəsi təmiz A:Y şablonu ilə yenilənib.

Railway build əmri əvvəlki kimi Dockerfile üzərindən `gradle --no-daemon clean bootJar -x test` istifadə edir.
