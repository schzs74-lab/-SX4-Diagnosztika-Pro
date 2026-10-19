# Suzuki SX4 Diagnosztika Pro — Android prototípus 0.1.0

Céljármű: 2007 Suzuki SX4, M15A. A felhasználó visszajelzése szerint az eredeti GitHub Actions APK-fordítás és a telefonos telepítés sikerült, a DEMO és a P0420 mintakód megjelent. **Valódi ELM327 adapterrel és autóval még nincs igazolva.**

## Használat

- Alapértelmezett: **DEMO / SZIMULÁLT**, autókapcsolat nélkül.
- Valódi használat: álló jármű, adapter Wi-Fi hálózatához csatlakozás az Android beállításaiban; internet nélküli kapcsolat megtartása; DEMO kikapcsolása; csatlakozás.
- Az IPv4-cím és a port az adapter dokumentációja alapján állítandó. A `192.168.0.10:35000` csak alapérték, még nem igazolt.
- Az alkalmazás külön Wi-Fi-socketet használ, nem a mobiladatos alapútvonalat. A mobiladat bekapcsolva maradhat. Az alkalmazás nem kapcsolódik automatikusan egy adott SSID-hez: az Androidban választott Wi-Fi-nek kell az adapteréhez tartoznia.
- Az élőadat-kijelzés egyenként mutatja az aktuálisan befejezett PID-lekérdezést. Nem állítja, hogy több, eltérő időben olvasott érték egyidejű mérés volna. A támogatott kijelzési PID-ek: 05, 06, 07, 0C, 0D, 11, 14, 15; ezek mind az első támogatási oldalon vannak.
- Módváltás, kapcsolatvesztés és háttérbe kerülés érvényteleníti az adatokat, bontja a kapcsolatot. Visszatérés után új kapcsolódás kell. Háttérben nincs mérés.

## Hibakódtörlés és mentés

A törlés előkészítése megállítja a mérést, frissen kiolvassa és ellenőrzi a tárolt hibakódokat. A saját belső tárhely `files/diagnostic-backups/` könyvtárába egyedi JSON-fájlt ír, lemezszinkronizálással és visszaolvasásos ellenőrzéssel. Kötelező Mode 03, kiegészítő Mode 07/0A, readiness (0101), Freeze Frame hibakód (020200) és támogatási maszk (020000) lekérdezések kerülnek bele, nyers válasszal és lekérdezésenkénti hibaállapottal. Egy nem támogatott Mode 0A például nem jelent sikeres adatmentést.

**A mentés mindig RÉSZLEGES (`complete: false`).** Nem menti el a teljes Freeze Frame-et, az összes ECU-t vagy az összes diagnosztikai adatot. A felület a sikeres fájlmentés után külön megerősítést kér a részleges mentés tudomásulvételével. Sikertelen kötelező kiolvasás, mentés vagy megszakadt kapcsolat esetén nem küld 04-et. A 04 pozitív visszaigazolását pontosan ellenőrzi; időtúllépés esetén az eredmény bizonytalan, ismételt kiolvasás szükséges, automatikus ismétlés nincs.

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

Az eredeti forrás auditja és a fizikai ellenőrzési terv: [docs/AUDIT.md](docs/AUDIT.md).
