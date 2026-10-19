# Forrásaudit és ellenőrzési határok

Auditált alap: `a51fbf22c4b979fa8b4c37aa1fc13e905bc47ac1` (main, 2026-10-19). A tároló teljes, kilencfájlos tartalma átolvasva, beleértve a README-t, manifestet, összes Kotlin- és Gradle-fájlt, valamint a workflow-t. Nem volt AGENTS.md a tárolóban.

## Bizonyítható hibák az eredeti forrásban

| Súlyosság | Fájl, eredeti hely | Bizonyíték és következmény |
|---|---|---|
| Magas | MainActivity.kt, startReading / worker | A singleThreadExecutor egy feladatában `while(measuring.get())` fut; minden más worker-feladat, így a DTC-olvasás és bontás mögötte vár. |
| Magas | MainActivity.kt onDestroy; Elm327.kt disconnect/command/connect | A főszál szinkronizált disconnectet hív, miközben command/connect ugyanazt a monitort fogja. Főszál blokkolható. Connect/init kivételágban nincs garantált socketlezárás. |
| Magas | Elm327.kt connect; MainActivity.kt; manifest | Alap `Socket()` és alapértelmezett útvonal, nincs Android Network-kiválasztás vagy Wi-Fi-socket; mobiladat mellett a TCP rossz hálózatra kerülhet. |
| Magas | Elm327.kt Obd.bytesFor/dtcs | `firstOrNull` csak egy sort választ; ISO-TP, számozott ELM-sorok és további ECU-k elvesznek. Tetszőleges nemhex karaktereket töröl, hiányzó válasz üres sikernek tűnhet. |
| Magas | MainActivity.kt törlés | `SharedPreferences.apply()` nem ad tartós mentési visszaigazolást; csak Mode 03 mentés, Freeze Frame nélkül. `contains("44")` nem érvényes pozitív Mode 04 ellenőrzés. A meglévő figyelmeztetés és megerősítés önmagában helyes szándék, de a mentés garanciája hiányzik. |
| Közepes | Elm327.kt Obd.dtcs | A nibble-eket decimális interpoláció fűzi össze; `43 0A BC` hibásan P0101112, nem P0ABC. |
| Közepes | Elm327.kt command | A socket olvasási timeout minden bájtnál újraindul; a teljes parancsidő korlátlan. 8192 karakternél prompt nélkül visszatér. A megkésett válasz következő parancshoz keveredhet. |
| Közepes | MainActivity.kt DEMO, show, életciklus | A kapcsoló nem törli a régi adatokat vagy kapcsolatot; régi feladatok frissíthetik az új mód vagy megszűnt Activity kijelzését. Csak onDestroy állítja le a mérést. |
| Közepes | MainActivity.kt mérési képletek | A második bájt hiányát nullával pótolja; a fordulatot egészre csonkolja; O2 trim FF esetén nincs „nem elérhető” kezelés. A többi alkalmazott alapképlet megfelelő. |
| Közepes | workflow, app/build.gradle.kts, README | Nincs célzott teszt és PR-trigger; a README az elkészült APK-t még el nem készültnek állítja. A targetSdk 28 elavult; a javítás 35-re emeli és kezeli a rendszerfelületi inseteket. |

A céljármű pontos OBD-protokollja, az adapter IP-je/portja, a támogatott PID-ek és az ELM klón megfelelősége nem állapítható meg ebből a forrásból. A hiányzó teljes Freeze Frame-mentés a javított változatban is ismert korlát, nem teljesített mentési állítás.

## Javítási elvek

Wi-Fi-socket `Network.socketFactory` útján, INTERNET-képesség megkövetelése nélkül, folyamatglobális hálózatkötés nélkül. A hálózatkéréshez CHANGE_NETWORK_STATE engedély. onLost és onStop bont és érvénytelenít. Teljes parancshatáridő, watchdog, promptkövetelmény, hibánál bontás, automatikus parancsismétlés nélkül. Egy soros I/O-munkasorban rövid, egy-PID-es időzített lekérdezések, munkamenet- és mérésgenerációval. A bontás nem vár I/O-zárra.

A törlés csak friss, értelmezhető kötelező DTC-olvasás, egyedi részleges mentés, fájlszinkronizálás/visszaellenőrzés és külön felhasználói jóváhagyás után történhet. A kiegészítő adatok hiánya külön állapotként kerül a mentésbe. Teljes Freeze Frame-támogatást nem állítunk.

## Szoftveresen ellenőrizhető

- JVM: hexadecimális DTC-k mind a négy családban; CAN számláló és padding; több válaszadó; ELM sorszámok és hossz; 11/29 bites ISO-TP, közbeszőtt ECU-k, hiányos/sorrendhibás keretek; PID-maszk bithelyei és további oldalak; mérési képletek és hiányzó bájtok; pontos Mode 04 igazolás.
- Helyi TCP-szimulátor: inicializálás, töredezett válasz, EOF, teljes határidő lassan érkező bájtok mellett, bontás olvasás közben, túl hosszú válasz, tiltott parancsok.
- Android lint és debug APK-fordítás: fordíthatóság és statikus platformellenőrzések. A sikeres build nem bizonyít fizikai adapteres működést.
- A megerősítési felület, fájlmentés és Activity-viselkedés forrásellenőrzött; eszközös/instrumentációs automatizált tesztjük még nincs.

## Valódi telefon + adapter + autó szükséges

1. Android 8–15/újabb, különösen Android 15 a targetSdk 35 miatt: képernyő-insetek, olvashatóság, engedélyek, részleges mentés megerősítése és megszakítása. Mobiladat BE, adapter Wi-Fi internet nélkül: helyes útvonal, maradó mobilinternet. Ellenőrizni a tényleges IP/port értéket, hibás cím és port visszajelzését.
2. Gyújtás ON, álló jármű: ELM inicializálás, 0100 és a tényleges ATDPN. Rögzíteni az adapter típusát/firmware-ét és a nyers válaszokat. ELM klón ATCAF1/ATDPN kompatibilitás és ECU-válaszidők.
3. Támogatott PID-ek ellenőrzése referenciaolvasóval; hideg/meleg hűtővíz, alapjárati fordulat, STFT/LTFT, O2-jelek és az FF trim kezelés összevetése. A nem támogatott mérés ne váljon nullává.
4. Élőadat mellett DTC-olvasás, mérés stop/start, Wi-Fi ki/be, adapter kihúzása, háttérbe küldés, forgatás és Activity újralétrehozás. Ne legyen ANR, régi DEMO-kijelzés vagy automatikus újracsatlakozás; a bontás legyen azonnali. A helyi teszt nem igazolja a telefon gyártói Wi-Fi-energiakezelését.
5. Valós DTC- és Freeze Frame-válaszok összevetése megbízható diagnosztikával, különösen az adott ISO/KWP/CAN formátum és több ECU esetén. A mentés hiányzó lekérdezéseinek és `complete: false` mezőjének ellenőrzése. A belső tárhelyen a fájl létezzen és visszaolvasható legyen.
6. Törlést először szimulátoron: Mégsem, módváltás, kapcsolatvesztés és mentési hiba ne küldjön 04-et; pozitív gomb után pontosan egyszer küldjön. Autós törlés csak a teljes külső diagnosztikai mentés után és kifejezett döntéssel; a művelet readiness/Freeze Frame következményeit dokumentálni kell. Ne hozzunk létre hibát és ne változtassunk ECU-paramétert teszt céljából.

A munkát külön PR tartalmazza; main ágba automatikus összeolvasztás nincs.
