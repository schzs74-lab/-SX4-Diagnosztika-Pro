# Suzuki SX4 Diagnosztika Pro — Android prototípus 0.1

## Mi készült el?
- Natív Kotlin/Android Studio projekt, magyar képernyőkkel.
- DEMO mód; nem kapcsolódik az autóhoz, nem valós mérési adat.
- Wi-Fi ELM327 TCP kapcsolat módosítható IP/port paraméterekkel (alap: 192.168.0.10:35000; utóbbi még nem igazolt).
- ELM inicializálás (ATZ, ATE0, ATL0, ATS0, ATH0, ATSP0, ATI).
- OBD2 PID 0100 lekérdezés; alapvető támogatott élő adatok ciklikus lekérdezése.
- Tárolt hibakódok szabványos Mode 03 olvasása; Mode 04 törlés külön figyelmeztetéssel.

## Fordítás/telepítés
1. Telepítsd az Android Studiót számítógépre.
2. Open → válaszd az `SX4_Diagnosztika_Pro` mappát.
3. Hagyd a Gradle szinkronizálást végigfutni internetkapcsolattal, Android SDK 35 és JDK 17 szükséges.
4. Build → Build APK(s), vagy csatlakoztatott telefonon Run.
5. APK tipikus helye: app/build/outputs/apk/debug/app-debug.apk.

## Használat
- Alapértelmezett a DEMO kapcsoló bekapcsolva.
- Valódi autóhoz: WiFi_OBDII hálózat, kapcsolat megtartása internet nélkül; DEMO kikapcsolása, Csatlakozás.
- A portot és TCP elérhetőséget éles próbával igazolni kell.
- Álló járművel végezz diagnosztikát, ne vezetés közben kezeld a telefont.

## Még NINCS kész
- Nem készült APK: ezen a környezeten nincs Android SDK/Gradle build.
- Nem teszteltük fizikai adapterrel vagy Suzuki ECU-val.
- Nem teljes a többkeretes/ISO-TP DTC dekódolás; ezen a prototípuson csak alap válaszok támogatottak.
- Nincsenek grafikonok, naplózás, Freeze Frame mentés, Mode 06 vagy gyártóspecifikus ABS/SRS/BCM funkciók.
- Éles hibakódtörlés előtt a Freeze Frame mentése még hiányzik; emiatt az alkalmazás jelenlegi változata csak gondos mérlegeléssel, tesztelésre alkalmas.
- Gyártói beállítás-írás és emissziófelügyelet-módosítás nincs implementálva.

## Következő teendők
Teszt a Wi-Fi adapterrel, válaszok validálása; Wi-Fi Network bind megvalósítása Androidon (mobiladat melletti helyes routing), PID-támogatás bővítése, Freeze Frame napló, stabil DTC dekóder.

## GitHub Actions: APK készítése Android telefonról
A tároló gyökerében legyen az `app` mappa, a két Gradle `.kts` fájl, és a `.github/workflows/android-apk.yml` fájl.
A `main` ágra feltöltve a GitHub Actions automatikusan megkísérli a debug APK fordítását. A `Actions` -> `Android APK fordítás` -> sikeres futás -> `Artifacts` alatt tölthető le a ZIP-be csomagolt `app-debug.apk`.
**Ez a debug APK csak tesztelésre való.** A build GitHubon még nem futott le, és autóval nem volt tesztelve. Az APK telepítését Android kérésére engedélyezni kell az adott forráshoz.
