# Ambient Home Universal 0.5.1-beta1

Natywny launcher Android TV / Google TV: fotograficzne tło, zegar, pogoda, przewijany dock i opcjonalny Debloat wykonywany bezpośrednio z APK przez lokalne ADB. Kotlin i standardowe widoki Androida, bez reklam, analityki i frameworka UI.

**Beta, nie obietnica zgodności ze wszystkimi telewizorami.** Min SDK 26 (Android 8), target/compile SDK 35. Brak bibliotek natywnych — jedno APK dla różnych architektur. Testy sprzętowe wykonano na Xiaomi MiTV-MOOR2 / Android 11. Samsung Tizen, LG webOS i Apple tvOS nie obsługują tego APK. Instalacja nie wykonuje debloatu i nie zmienia automatycznie HOME.

## Launcher

- 28 fotografii offline, w tym 21 astro z ESO. Domyślnie zmiana co 15 minut; dostępne 5/15/30/60 minut, raz dziennie i stała tapeta. Albumy astro, natura, wszystkie i własne.
- Pierwsza konfiguracja wymaga samodzielnego wyboru miejscowości pogody. Brak domyślnej lokalizacji autora, GPS i zapytań pogodowych przed wyborem miasta.
- Przewijany dock, nieruchome Aplikacje/Ustawienia po prawej, podpisy tylko pod ikonami. Długie OK: natychmiastowe przesuwanie, lewo/prawo: pozycja, OK: zapis, Wstecz: anulowanie. Menu: nazwa i widoczność. Ukryte ikony pozostają w siatce wszystkich aplikacji.
- Automatyczne ukrywanie docka, RAM i transfer TV oraz CPU aplikacji jako oznaczony wariant zastępczy, gdy CPU całego TV jest niedostępny. Zadania cykliczne są zatrzymywane poza ekranem launchera.
- Źródła TV odczytywane z TvInputManager, bez identyfikatorów konkretnego Xiaomi. Nazwy i kolejność edytowalne lokalnie. Brak wejść w TV boxie lub niedostępne API producenta daje komunikat i skrót ustawień zamiast fikcyjnych portów.
- Ustawienia HOME otwierają systemowy wybór launchera. Opcjonalny helper dostępności rozpoznaje okna innych zainstalowanych launcherów, pomijając setup/wizard/recovery. Odbiera metadane okien, bez treści ekranu i filtrowania klawiszy. Nie jest aktywowany przy instalacji; OEM może ograniczać jego skuteczność. Nie nadawaj mu uprawnień tylko do używania Debloatu — Debloat go nie wymaga.

## Debloat bez podłączonego komputera

Ustawienia → **Debloat · narzędzia ADB**.

1. W opcjach programisty TV włącz debugowanie i klasyczne sieciowe ADB TCP. Zwykle jest to port 5555.
2. W menu wybierz Połącz / sprawdź ADB. Zaakceptuj klucz RSA Ambient na ekranie TV. Jeżeli pytanie RSA przerwie pierwszą próbę, po akceptacji ponów sprawdzenie.
3. Wybierz **Szybkie czyszczenie · dobór automatyczny**. APK samo dobierze pakiety z profilu zgodnego z modelem, firmware i wersją pakietu. Jedno potwierdzenie uruchamia plan, bez zaznaczania pakietów. Brak profilu oznacza brak automatycznych zmian. Można pobrać kopię planu i raportu.
4. Przywróć zmiany Ambient odtwarza tylko pozycje zapisane przez ten moduł. Można przywrócić jedną zmianę lub wszystkie własne zmiany.

Po autoryzacji operacje wykonuje samo APK przez 127.0.0.1 lub własny adres TV. Nie potrzebuje stale działającego komputera, HA, Shizuku, roota ani zewnętrznego serwera. Aplikacja nie uruchamia adbd i nie potrafi sama włączyć wyłączonego debugowania. **Jeśli firmware nie udostępnia sieciowego ADB z menu, może być potrzebne jednorazowe przygotowanie przez PC.** Parowanie TLS kodem/QR nowego Wireless debugging nie jest obsługiwane w tej wersji. Przy zamkniętym ADB sam launcher nadal działa.

Nie ma cyklicznego debloatu, automatycznego działania po restarcie ani samoczynnego ubijania procesów. Port ADB może wyłączyć się po restarcie TV. Klucz RSA przechowywany jest wyłącznie w prywatnych danych aplikacji; nie trafia do raportu. Automatyczne backupy i transfer danych aplikacji są wyłączone, aby nie przenosić klucza i dziennika na inny TV.

## Zakres i zabezpieczenia Debloatu

- Automatyczny profil jest ograniczony do jednego pakietu analityki na dokładnym modelu/buildzie/wersji; patrz [opis profilu](AUTOMATIC-DEBLOAT.md). Narzędzia indywidualne znajdują się w menu Zaawansowane. Tryb ręczny nie rozszerza automatu.
- Pełna lista systemowa jest odczytowa, dopóki użytkownik nie wybierze pakietu i nie potwierdzi zmiany. Nieopisane pakiety wymagają osobnego włączenia trybu ręcznego. Chronione składniki nadal są niedostępne do wyłączenia.
- Ochrona obejmuje własny launcher, inne launchery wykryte jako HOME, setup/recovery, główne usługi Google i platformy, instalator, sprzęt/sieć, aktywne usługi dostępności (w tym QuickBars/Button Mapper), administratorów i dostawców wejść TV. Pakiety z podstawowym współdzielonym UID systemowym są odrzucane również w trybie ręcznym. Dodatkowe zależności producenta mogą nie być znane.
- Operacja to **pm disable-user dla użytkownika aplikacji**, bez uninstall, pm clear, zmian partycji lub factory reset. Wyłączona aplikacja i powiązane funkcje przestają działać, a dane i APK pozostają na urządzeniu.
- Stan DEFAULT (0) lub ENABLED (1) jest zapisywany atomowo przed poleceniem. Pakiety wyłączone już wcześniej nie są dodawane do przywracania. Przywracanie używa odpowiednio pm default-state lub pm enable.
- Zerwanie połączenia zostawia wpis planned/uncertain, aby można było sprawdzić i przywrócić zmianę. Błąd planu zbiorczego zatrzymuje dalsze pozycje; wykonane zmiany pozostają zapisane. Nie jest to atomowa transakcja całego planu.
- Dziennik zawiera ostatnią zmianę dla pary pakiet/użytkownik i jest powiązany z lokalną tożsamością urządzenia. Uszkodzony lub obcy dziennik jest odrzucany. Nie ma importu cudzych profili do wykonania ani konsoli dowolnych poleceń shell.

## Raport i powrót

Przed zmianami użyj Kopia planu i raportu lub Dziennik i kopia raportu → Pobierz kopię przez LAN. Tymczasowy adres z losowym tokenem pozwala pobrać JSON z nazwami pakietów, stanem początkowym i poleceniami odzyskiwania. Udostępnia tylko raport, bez klucza ADB. Pobieranie jest HTTP w zaufanej sieci lokalnej, bez chmury. Zamknięcie okna lub opuszczenie aplikacji wyłącza serwer.

Nie usuwaj danych ani nie odinstalowuj Ambient przed przywróceniem zmian lub pobraniem kopii raportu. Po utracie aplikacji nadal można świadomie wykonać zapisane polecenia z komputera przez autoryzowane ADB, na tym samym TV i użytkowniku. Gdy TV straci sieć lub system nie uruchamia się poprawnie, zwykły mechanizm powrotu przez LAN może nie być dostępny. Nie ma gwarancji odwrócenia każdej awarii.

Wcześniejszy debloat CleanMyTV/tvOS/innych narzędzi nie trafia do dziennika Ambient i nie jest automatycznie odtwarzany. Brak dodatkowych zmian w automatycznym profilu nie oznacza braku całego bloatware.

## Własne zdjęcia i prywatność

Wybór plików przez systemowy selektor dokumentów, gdy jest dostępny. Na TV z atrapą DocumentsStub uruchamia się jawny tymczasowy transfer LAN z telefonu/komputera. Do 100 własnych zdjęć, limit 40 MB na plik, ograniczone dekodowanie, orientacja EXIF i lokalne JPEG do 1920×1080 z miniaturami. Oryginał nie jest modyfikowany. Bez szerokiego dostępu do całej pamięci.

Pogoda: Open-Meteo, geokodowanie GeoNames; po wyborze miasta HTTPS zawiera nazwę/współrzędne. Fotografie są offline. ADB i transfery są lokalne i uruchamiane na żądanie. Raport zawiera model/build, nazwy wybranych pakietów i skrót identyfikatora wiążącego dziennik z urządzeniem — sprawdź raport przed publicznym udostępnieniem.

## Budowanie i testy

JDK 17, Android SDK 35 / Build Tools 34.0.0, Gradle Wrapper 8.9, AGP 8.7.3, Kotlin 2.0.21. Ustaw SDK w własnym local.properties; plik nie należy do repozytorium.

    ./gradlew assembleRelease lintRelease testDebugUnitTest

Wynik release: app/build/outputs/apk/release/app-release-unsigned.apk. Podpisz swoim stabilnym kluczem. Dostarczone APK beta jest podpisane dotychczasowym lokalnym kluczem deweloperskim projektu; klucz nie jest częścią źródeł. Przed pierwszym publicznym stabilnym wydaniem ustal własny klucz wydawcy. Zmiana podpisu wymaga migracji/ponownej instalacji, więc najpierw przywróć debloat i zachowaj raport.

Release używa applicationId pl.ambienthome.tv i może aktualizować wcześniejszy Ambient przy zgodnym podpisie. Wariant debug ma osobny identyfikator pl.ambienthome.tv.universaltest i nie rejestruje HOME, aby testy nie zastępowały codziennego launchera. Można go otworzyć jawnie przez ADB:

    adb shell am start -n pl.ambienthome.tv.universaltest/pl.ambienthome.tv.MainActivity

Testy obejmują transport ADB/RSA, ochronę pakietów, zapis przed zmianą, awarie zapisu/połączenia, dokładne przywracanie i zatrzymanie planu po błędzie. Testy modelowe nie zastępują testów sprzętu danego producenta. W tej sesji test sprzętowy Debloatu jest odczytowy; nie wykonywano wyłączeń systemowych na TV użytkownika.

## Licencje

Kod aplikacji: MIT. Fotografie nie są objęte licencją kodu. Źródła/autorzy/licencje każdego zdjęcia: app/src/main/assets/wallpapers.json oraz PHOTO-SOURCES.txt. ESO: CC BY 4.0 i pełny kredyt widoczny na ekranie; Unsplash: Unsplash License. Dopasowanie/kadrowanie do ekranu, bez generowania AI. Gradle Wrapper: Apache-2.0. JUnit: zależność wyłącznie testowa.

Źródła techniczne: protokół ADB i polecenia Package Manager w AOSP, dokumentacja Android Developers. Implementacja nie zawiera kodu debloatera tvOS ani jego skryptów automatycznego nadawania uprawnień.
