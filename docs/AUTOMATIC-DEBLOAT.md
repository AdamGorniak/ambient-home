# Automatyczne profile / Automatic profiles

## Polski

Szybkie czyszczenie dobiera pakiety samodzielnie i wymaga jednego potwierdzenia operacji. Nie wymaga od użytkownika znajomości nazw pakietów. Tryb ręczny znajduje się w Zaawansowanych i nigdy nie rozszerza automatycznego profilu.

### Profil początkowy 0.5.1

| Warunek | Dokładna wartość |
|---|---|
| Producent / model | Xiaomi / MiTV-MOOR2 |
| Firmware fingerprint | `Xiaomi/flipped/venom:11/RTM5.220609.003/2674:user/release-keys` |
| Jedyny pakiet automatyczny | `com.miui.tv.analytics` |
| versionCode | `2023080917` |

Podstawa: lokalny audyt urządzenia i obserwacja działającego TV z tym pakietem już wyłączonym przed instalacją Ambient. Audyt wykazał oddzielną aplikację systemową MiTVBiAnalytics, UID 10047. Nie jest to nowy test wyłączenia/przywrócenia przez Ambient ani dowód braku wpływu na każdą funkcję. Profil pozostaje eksperymentalny.

`com.xiaomi.statistic` jest celowo wykluczony: w audycie miał UID 1000. Pakiety treści/rekomendacji, demo, galerie i aplikacje użytkowe nie są wybierane automatycznie.

Każda inna kombinacja modelu/builda oznacza brak automatycznych zmian. Inna wersja pakietu, aktywna ochrona, systemowy UID, brak potwierdzenia aplikacji systemowej, wcześniejsze wyłączenie albo aktywny wpis dziennika wykluczają pozycję. Wersja i ochrona są sprawdzane ponownie przed poleceniem. Błąd odczytu przerywa operację. Nie ma dopasowania po samej marce ani awaryjnego wyboru po nazwie pakietu.

Na telewizorze już poddanym debloatowi wynik może wynosić zero zmian. Nie oznacza to, że urządzenie jest pozbawione całego bloatware. Wyłączenie jest lokalne dla użytkownika, bez usuwania APK i danych; stan początkowy trafia do dziennika. Automatyczne testy obejmują także odrzucenie niewłaściwego firmware, wersji pakietu, systemowego UID i prób obejścia przez tryb ręczny.

Nowe profile wymagają osobnego audytu i udokumentowanych obserwacji zgodności. Nie należy rozszerzać tego profilu na inne modele przez usunięcie warunków identyfikacji.

## English

Quick cleanup selects packages automatically and asks for one operation confirmation. Users do not have to identify package names. Manual tools live under Advanced and cannot broaden automatic selection.

The initial 0.5.1 profile matches **only** the exact manufacturer, model, firmware fingerprint and package version in the table above. Its sole target is Xiaomi's standalone analytics app, `com.miui.tv.analytics`.

Evidence: a local device audit and observation of a working TV where this package was already disabled before Ambient installation. The audit identified the system MiTVBiAnalytics app with UID 10047. This is **not** a fresh Ambient disable/restore hardware test or proof that every function is unaffected. The profile remains experimental.

`com.xiaomi.statistic` is excluded because the audit found UID 1000. Content/recommendation packages, demo mode, galleries and user-facing apps are not selected automatically.

Unknown device/build combinations produce no automatic changes. A different package version, active protection, core UID, unconfirmed system-app status, an already disabled package or an outstanding journal entry excludes the package. Version and protections are rechecked before mutation; read failures stop the operation. There is no brand-only or package-name-only fallback, including in manual mode.

Previously debloated devices may show zero additional changes. This does not mean all bloatware is absent. Operations disable for the current user, retain APK/data and journal the original state. Automated tests cover wrong firmware/version, core UIDs and manual-mode bypass attempts.

Additional profiles require separate audits and documented compatibility observations. Do not extend this profile to other devices by removing identity checks.
