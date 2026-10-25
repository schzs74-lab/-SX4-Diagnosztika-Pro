# Suzuki SX4 Diagnosztika Pro — Android prototípus

Céljármű: 2007 Suzuki SX4, M15A. A felhasználó visszajelzése szerint az eredeti GitHub Actions APK-fordítás és a telefonos telepítés sikerült, a DEMO és a P0420 mintakód megjelent. **Valódi ELM327 adapterrel és autóval még nincs igazolva.**

## Használat

- Alapértelmezett: **DEMO / SZIMULÁLT**, autókapcsolat nélkül.
- Valódi használat: álló jármű, adapter Wi-Fi hálózatához csatlakozás az Android beállításaiban; internet nélküli kapcsolat megtartása; DEMO kikapcsolása; csatlakozás.
- Az IPv4-cím és a port az adapter dokumentációja alapján állítandó. A `192.168.0.10:35000` csak alapérték, még nem igazolt.
- Az alkalmazás külön Wi-Fi-socketet használ, nem a mobiladatos alapútvonalat. A mobiladat bekapcsolva maradhat. Az alkalmazás nem kapcsolódik automatikusan egy adott SSID-hez: az Androidban választott Wi-Fi-nek kell az adapteréhez tartoznia.
- Az élőadat-kijelzés táblázatban mutatja minden PID utolsó érvényes értékét a saját korával (ms). A régi érték „ELAVULT” jelzést kap (gyors PID-eknél 4 s, lassúaknál 30 s után); hibás vagy hiányzó válasz után az adott PID „még nincs érvényes adat” állapotba kerül. Az értékek PID-enként eltérő időpontban készülnek, nem egyidejű mérések.
- Támogatott PID-ek: kapcsolódáskor a teljes támogatási láncot kiolvassa (0100 → 0120 → … → 01E0, amíg az ECU jelzi a következő oldalt). Csak a támogatottként jelölt PID-et kérdezi le.
- Lekérdezési terv (`LivePlan.kt`): gyors PID-ek minden körben (0C, 04, 11, 06, 14, 15, 24, 44, 0B, 10), lassúak körönként egy (05, 07, 03, 0D, 0F, 0E, 25, 43, 45, 2F, 42, 33, 46, 3C). Az elavulási határ PID-enként a saját frissítési idejéből és a mért lekérdezési időből számolódik.
- **Állapot és Freeze Frame olvasása** gomb (csak olvasás): readiness monitorok (KÉSZ / NEM KÉSZ, hibalámpa), OBD-szabvány, lambdaszondák helye, megtett út és idő égő hibalámpával / a törlés óta, adapterfeszültség (`ATRV`), valamint a 0. Freeze Frame kiváltó hibakódja és minden támogatott PID-je dekódolva (ismeretlen PID nyers hexában).
- Módváltás, kapcsolatvesztés és háttérbe kerülés érvényteleníti az adatokat, bontja a kapcsolatot. Visszatérés után új kapcsolódás kell. Háttérben nincs mérés. A képernyő elforgatása nem bontja a kapcsolatot.

## Hibakódtörlés és mentés

A 04 parancs elküldése előtt az alkalmazás frissen kiolvassa a fordulatszámot (010C) és – ha támogatott – a sebességet (010D). Csak 0 1/perc (leállított motor, ráadott gyújtás) és 0 km/h esetén küld 04-et; ha ez nem ellenőrizhető, a törlés tiltott.

A törlés előkészítése megállítja a mérést, frissen kiolvassa és ellenőrzi a tárolt hibakódokat. A saját belső tárhely `files/diagnostic-backups/` könyvtárába egyedi JSON-fájlt ír, lemezszinkronizálással és visszaolvasásos ellenőrzéssel. Kötelező Mode 03, kiegészítő Mode 07/0A, readiness (0101), Freeze Frame hibakód (020200) és támogatási maszk (020000) lekérdezések kerülnek bele, nyers válasszal és lekérdezésenkénti hibaállapottal. A `decodedStatus` mező a teljes állapotjelentést tartalmazza: dekódolt readiness, járműállapot és a 0. Freeze Frame összes támogatott PID-je. Egy nem támogatott Mode 0A például nem jelent sikeres adatmentést.

**A mentés mindig RÉSZLEGES (`complete: false`).** Csak a motorvezérlő szabványos OBD-adatait menti; más ECU-kat és gyártóspecifikus adatokat nem. A felület a sikeres fájlmentés után külön megerősítést kér a részleges mentés tudomásulvételével. Sikertelen kötelező kiolvasás, mentés vagy megszakadt kapcsolat esetén nem küld 04-et. A 04 pozitív visszaigazolását pontosan ellenőrzi; időtúllépés esetén az eredmény bizonytalan, ismételt kiolvasás szükséges, automatikus ismétlés nincs.

A mentés a belső alkalmazástárban marad, jelenleg nincs felhasználói exportáló felület. Fejlesztői debug APK esetén `adb shell run-as hu.sx4.diagnosztika ls files/diagnostic-backups` paranccsal ellenőrizhető. Az alkalmazás eltávolítása a fájlokat is törli. A mentés nem állítható vissza az ECU-ba. A törlés elveszíthet nem mentett Freeze Frame adatokat és visszaállíthatja a readiness monitorokat.

## Feldolgozás és korlátok

- Szigorú, tiszta Kotlin OBD-feldolgozó: fejléc nélküli CAF1, ELM hossz + sorszámozott sorok, 11/29 bites CAN ISO-TP egy- és többkeretes válaszok, forrásonkénti összerakással és sorszámellenőrzéssel.
- Több ECU hibakódjait összegyűjti. Több ECU-tól kapott PID-mérést/támogatási maszkot nem választ ki önkényesen: egyértelmű hibát jelez.
- CAN DTC-számlálót a kapcsolat során lekérdezett protokoll szerint kezeli. ISO/KWP esetén az adapter ATH0 által levágott fejléceire támaszkodik. Egyéb/gyártóspecifikus keretformátumok támogatása nincs igazolva.
- Csak szabványos motor-OBD olvasás és megerősített Mode 04. Nincs ECU-paraméterírás, emisszióellenőrzés-módosítás, ABS/SRS/BCM vagy gyártóspecifikus funkció.
- A 0100-ra adott érvényes válasz igazolja az ECU kommunikációját; az ATI önmagában csak az adaptert azonosítja.

## Fordítás és tesztek

Android SDK 35, JDK 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21. Minimum Android 8.0 (API 26), cél API 35. Gradle wrapper jelenleg nincs; telepített Gradle 8.9 vagy a workflow használható.

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace
```

A workflow `main` pushra, `main` célú pull requestre és kézzel futtatható. Artifacts: debug APK, JUnit- és lint-jelentések. APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug APK csak fejlesztői teszthez.

A JVM-tesztek a dekódolást és helyi TCP-szimulátorral a parancshatáridőt, töredezett választ, kapcsolatvesztést, azonnali bontást, inicializálási hibát és válaszhosszkorlátot ellenőrzik. Android Wi-Fi-útválasztást vagy valódi ECU-kompatibilitást nem igazolnak.

## Verziószám és APK-aláírás

- A verzió alapja a `version.properties` (`VERSION_NAME`, kézzel léptetendő). A `versionCode` CI-ben a GitHub Actions futásszáma, helyben a commitok száma; a megjelenített `versionName` pl. `0.2.0-build57`. Az alkalmazás fejléce mutatja a versionName-et, versionCode-ot, buildszámot, commitazonosítót és build-típust; a törlés előtti JSON-mentés is ezeket rögzíti.
- **Frissítés adatvesztés nélkül csak azonos aláírókulccsal lehetséges.** Titkok nélkül a CI minden futáskor ideiglenes debug-kulcsot használ, így az új APK nem telepíthető a régire. Egyszeri beállítás (a kulcsot és a jelszavakat biztonságos helyen meg kell őrizni, elvesztésük után csak eltávolítással lehet frissíteni):

```sh
keytool -genkeypair -v -keystore sx4-release.jks -alias sx4 -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 sx4-release.jks > sx4-release.jks.b64
```

  GitHub → Settings → Secrets and variables → Actions: `SX4_KEYSTORE_BASE64` (a .b64 fájl tartalma), `SX4_KEYSTORE_PASSWORD`, `SX4_KEY_ALIAS` (`sx4`), `SX4_KEY_PASSWORD`. Ezután a debug és a release APK is ezzel a kulccsal készül, az artifact neve tartalmazza a verziót.
- Az első, már állandó kulccsal aláírt APK telepítése előtt **egyszer** el kell távolítani a régi (ideiglenes kulcsú) alkalmazást; ez törli a belső mentéseket, ezért előtte érdemes azokat kimenteni (`adb shell run-as hu.sx4.diagnosztika ...`). Ettől kezdve minden új build frissítésként, adatmegőrzéssel települ.

Részletes audit és fejlesztési terv: [docs/CLAUDE_AUDIT.md](docs/CLAUDE_AUDIT.md). Az eredeti forrás korábbi auditja és a fizikai ellenőrzési terv: [docs/AUDIT.md](docs/AUDIT.md).
