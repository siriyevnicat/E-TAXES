# Universal Excel Layout Fix

Bu versiyada sənəd yaradıldıqdan dərhal əvvəl bütün görünən Excel sheet-ləri üçün son layout yoxlaması aparılır.

- Mətn tipli dolu xanalarda Wrap Text aktiv edilir.
- Shrink To Fit söndürülür.
- Mövcud sətir hündürlüyü heç vaxt azaldılmır; yalnız mətn sığmırsa artırılır.
- 1, 2, 3, 4, 5, 6 və daha çox sətirə düşən mətn üçün minimum hündürlük hesablanır.
- Eyni sətirdə ən çox hündürlük tələb edən xana nəzərə alınır.
- Üfüqi merged xanaların bütün eni hesablanır.
- Şaquli merged xanalar üçün lazım olan ümumi hündürlük merge sahəsinin görünən sətirləri arasında bölünür.
- Gizli sheet və gizli sətirlərin şablon davranışı qorunur.
- Boş spacer sətirlərin mövcud hündürlüyü dəyişdirilmir.
- Template download zamanı da bütün görünən sheet-lər eyni yoxlamadan keçir.
- A4 çap eni əsas sənəd sheet-i üçün 1 səhifə enində saxlanılır; lazım olmadıqda şaquli sıxılma tətbiq edilmir.

Qeyd: build mühitində xarici internet olmadığı üçün Gradle wrapper distributivi yüklənə bilmədi; buna görə bu sessiyada tam Gradle compile icra olunmadı.
