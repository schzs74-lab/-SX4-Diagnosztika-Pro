# SX4 Diagnosztika Pro – műszaki és fejlesztési audit

Jármű: Suzuki SX4, 2007, M15A 1.5 benzinmotor · Wi-Fi ELM327-kompatibilis adapter (alapérték `192.168.0.10:35000`, nem igazolt) · Android, magyar felület.

Dátum: 2026-10-19 · Vizsgált állapot:

| Ág / PR | Commit | CI |
|---|---|---|
| `main` | `a51fbf2` | 3. futás sikeres (az 1–2. futás SDK-telepítési hiba miatt bukott) |
| `fix/obd-wifi-lifecycle-safety` = PR #1 (Codex) | `fc02ac3` | 4. futás sikeres: 12 OBD + 7 TCP teszt, lint 0 hiba / 45 figyelmeztetés |
| `claude/sx4-diagnostic-audit-f2gn35` (ez az ág, PR #1-re épül) | lásd PR | lásd PR |

**Módszer és korlátok.** A teljes forrást soronként átnéztem (main és PR #1), és a PR #1 tiszta Kotlin részeit (Obd, Elm327, tesztek) helyben JVM-en fordítottam és futtattam. Néhány gyanús esetet célzott próbatesztekkel igazoltam. Android SDK-t ebben a környezetben nem lehetett letölteni, ezért az Android-fordítást és a lintet a PR CI-ja ellenőrzi. **Fizikai adapterrel, telefonnal vagy autóval semmi nem volt tesztelve.** Az M15A-ra vonatkozó állítások ezért kifejezetten feltételezések, amelyeket a valós `ATDPN`, `0100`/`0120` és nyers válaszok igazolnak vagy cáfolnak.

Súlyossági skála: **kritikus** – adatvesztés, téves diagnózis vagy a fő funkció használhatatlansága; **magas** – gyakori hibás működés valós autón; **közepes** – korlátozott vagy pontatlan működés; **alacsony** – karbantarthatóság, kényelem.

---

## 1. Forráskód és korábbi javítások

### 1.1 Architektúra röviden

| Réteg | main | PR #1 |
|---|---|---|
| Transzport | `Elm327`: nyers `Socket()`, `@Synchronized` minden metóduson | `Elm327`: külön I/O- és állapotzár, teljes parancs-határidő watchdoggal, parancs-engedélylista, bontás I/O-zár nélkül |
| Protokoll | `Obd` (3 függvény, `firstOrNull` sorválasztás) | `Obd.kt`: szigorú ELM/ISO-TP feldolgozó, több ECU, CAN DTC-számláló, pontos 04-visszaigazolás |
| Hálózat | alapértelmezett útvonal | `ConnectivityManager.requestNetwork(TRANSPORT_WIFI, INTERNET nélkül)` + `Network.socketFactory` |
| Ütemezés | egyetlen végtelen ciklus a single-thread executoron | egy-PID-es, 200 ms-onként ütemezett feladatok, munkamenet- és mérésgenerációval |
| UI / életciklus | minden egy `Activity`-ben, kódból épített nézetek | ugyanez; `onStop` bont, `onDestroy` zár, elavult feladatok nem írhatnak a felületre |

A PR #1 a main legsúlyosabb hibáit helyesen kezeli. A kód viszont továbbra is egyetlen `Activity`-ben él (nincs ViewModel, Service, Repository), ezért háttérben futó naplózás, forgatás vagy képernyő-kikapcsolás mellett nem tud működni (lásd N6, N13).

### 1.2 A main ág hibái – a PR #1 állapota

A Codex `docs/AUDIT.md` megállapításait egyenként ellenőriztem, mind helytállók. Röviden, a PR #1-beli állapottal:

| # | Súlyosság | Fájl | Hiba | PR #1 |
|---|---|---|---|---|
| M1 | kritikus | `MainActivity.kt` `startReading` | A `while(measuring)` ciklus elfoglalja az egyetlen workert: alatta DTC-olvasás, törlés és bontás sorban áll | ✅ egy-PID-es ütemezett feladatok |
| M2 | magas | `Elm327.kt` `connect` | Nyers `Socket()` az alapértelmezett hálózaton. Internet nélküli adapter-Wi-Fi mellett az Android a mobiladatot teheti alapértelmezetté, a forgalom rossz interfészre kerül vagy a socket megszűnik | ✅ Wi-Fi-hez kötött socket |
| M3 | magas | `Elm327.kt` `Obd.dtcs` | A nibble-eket decimálisan fűzi össze: `43 0A BC` → `P010112`, nem `P0ABC`. A hibakódok egy része hibásan jelenik meg | ✅ `%X` formázás, teszttel |
| M4 | magas | `MainActivity.kt` törlés | A 04 sikerét `contains("44")` dönti el. A mentés `SharedPreferences.apply()`, tartóssági garancia nélkül | ✅ pontos `44` minden válaszadótól; szinkronizált és visszaolvasott JSON |
| M5 | magas | `MainActivity.onDestroy`, `Elm327` | A főszál `@Synchronized disconnect()`-et hív, miközben a worker blokkoló olvasásban tartja ugyanazt a monitort → ANR veszély | ✅ a bontás nem vár zárra |
| M6 | közepes | `Elm327.command` | Az időkorlát bájtonként újraindul (lassan csepegő válasz végtelen). 8192 karakternél prompt nélkül tér vissza → a válaszok elcsúszhatnak | ✅ teljes határidő, prompt kötelező |
| M7 | közepes | `Obd.bytesFor` | Csak az első illeszkedő sort nézi; a többsoros vagy több ECU-s válasz elveszik; a hiányzó válasz üres sikernek látszik | ✅ szigorú feldolgozó |
| M8 | közepes | `MainActivity` mérés | Hiányzó 2. bájt helyett 0; RPM egészre csonkolva; O2 trim `FF` = „nem használt” nincs kezelve | ✅ |
| M9 | közepes | `app/build.gradle.kts` | `targetSdk 28` | ✅ 35 + insetek |

### 1.3 A PR #1 után is fennálló vagy új hibák

Az „ebben az ágban” oszlop jelzi, mi készült el ebben a PR-ban (`claude/sx4-diagnostic-audit-f2gn35`).

#### N1 – Kritikus: minden CI-APK más aláírással készül, és mindegyik versionCode-ja 1
- **Ok, fájl:** `app/build.gradle.kts` (`versionCode = 1`, `versionName = "0.1.0"`, nincs `signingConfig`), `.github/workflows/android-apk.yml` (`assembleDebug`). Az AGP a debug-kulcsot a `~/.android/debug.keystore` fájlból veszi, és ha nincs, létrehozza. A GitHub-runner minden futáskor tiszta, így minden APK nagy valószínűséggel más kulccsal készül.
- **Következmény:** az új APK nem telepíthető a régire („Az alkalmazás nincs telepítve” / ütköző csomag). Csak eltávolítás után telepíthető, ami törli a `files/diagnostic-backups/` mentéseket. A felület minden változatban „0.1”-et mutat, így nem lehet tudni, melyik build fut.
- **Javítás (elkészült):** `version.properties` + automatikus `versionCode` (CI futásszám) + `versionName` (`0.2.0-buildN`) + `BuildConfig.GIT_SHA/BUILD_NUMBER`, a felületen és a mentésben is. Állandó aláírókulcs GitHub Secretsből; titkok nélkül a workflow figyelmeztet. Részletek: 5. fejezet.
- **Tesztelés:** két egymást követő CI-artifacton `apksigner verify --print-certs` → azonos SHA-256 tanúsítvány; `aapt dump badging` → növekvő versionCode; telefonon frissítéses telepítés után a mentések megmaradnak.
- **Előny:** adatvesztés nélküli frissítés, visszakereshető buildek.

#### N2 – Magas: K-line „BUS INIT: ...OK” sorra NullPointerException
- **Ok, fájl:** `Obd.kt` `messages()`. Csak a pontosan `BUS INIT: OK` sort hagyja ki. Az ELM327 valójában `BUS INIT: ...OK` formát ír ISO 9141-2 / ISO 14230-4 inicializáláskor (rögzített protokollnál vagy a K-line munkamenet újraindulásakor). Ez a sor a „tömör CAN-fejléc” ágba kerül, a fejléc-regex nem illeszkedik, és a `!!` NPE-t dob. Ugyanez történik `LV RESET`, `BUS BUSY`, `ACT ALERT` és minden más ismeretlen szöveges sor esetén. **Próbateszttel igazolva.**
- **Miért fontos az SX4-nél:** a 2007-es európai SX4 M15A várhatóan K-line protokollú (ISO 9141-2 vagy KWP2000; ezt az `ATDPN` igazolja). Ha a csatlakozás gyorsítására rögzítjük a protokollt (`ATSP4/5`), *minden* első kérés így kezdődik, és a kapcsolódás rejtélyes `null` hibával bukik.
- **Javítás (elkészült):** a `BUS INIT…OK` sort kihagyja, a `…ERROR` változatot elutasítja. A `!!` helyett értelmes hibaüzenet, és bővült a szöveges státuszok listája.
- **Tesztelés:** `ObdTest.kLineBusInitAndAdapterStatusLines` (NPE nem fordulhat elő); valós autón `ATSP5` vagy `ATSP4` után `0100`.
- **Előny:** K-line autón megbízható kapcsolódás és érthető hibaüzenet.

#### N3 – Magas: a hibakódtörlés nem ellenőrzi, hogy a motor áll-e
- **Ok, fájl:** `MainActivity.prepareClear`. A SAE J1979 szerint a Mode 04 „gyújtás ráadva, motor leállítva” állapotra való. Járó motornál az ECU elutasíthatja, vagy törölheti a readiness-állapotot működés közben. Mozgó járműnél a törlés biztonsági és diagnosztikai szempontból is elfogadhatatlan.
- **Javítás (elkészült):** közvetlenül a 04 előtt friss `010C` és (ha támogatott) `010D` olvasás. Csak 0 1/perc és 0 km/h esetén küldi el a parancsot. Ha az érték nem ellenőrizhető, a törlés tiltott, és az üzenet kimondja, hogy a 04 nem lett elküldve (`Obd.clearBlockReason`).
- **Tesztelés:** `ObdTest.clearRequiresStoppedEngineAndStationaryVehicle`; szimulátoron: járó motor (`41 0C 0C 30`) → nincs 04. Javasolt Robolectric-teszt a teljes folyamatra (6. fejezet).
- **Előny:** kizárja a vezetés közbeni vagy járó motoros véletlen törlést.

#### N4 – Közepes: kapcsolódáskor küldött adapter-üdvözlés elcsúsztatja az összes választ
- **Ok, fájl:** `Elm327.connect`. Egyes Wi-Fi-klónok a TCP-kapcsolat elfogadásakor azonnal `ELM327 v1.5\r\r>`-t küldenek. Ezt az `ATZ` válaszaként olvassa be, a valódi `ATZ`-válasz az `ATE0`-hoz kerül, és az `OK`-ellenőrzés hibát dob. A kapcsolódás minden alkalommal „Inicializálás sikertelen (ATE0)” üzenettel bukik.
- **Javítás (elkészült):** `drainUntilQuiet()`: kapcsolódás után legfeljebb 1,5 s-ig, 300 ms csendig kiolvassa a bemenetet.
- **Tesztelés:** `Elm327Test.bannerSentOnAcceptDoesNotShiftReplies` (a javítás nélkül bukik, ellenőrizve).
- **Előny:** több adaptertípussal is kompatibilis.

#### N5 – Közepes: egyetlen időtúllépés lebontja a teljes munkamenetet, nincs vezérelt újracsatlakozás
- **Ok, fájl:** `Elm327.command` (a watchdog bezárja a socketet), `MainActivity.scheduleReading`. Ha a K-line ECU egyszer lassú, vagy a Wi-Fi rövid ideig akad, a felhasználónak kézzel kell újracsatlakoznia.
- **Javasolt javítás:** időtúllépéskor előbb `\r` küldése (az ELM bármely karakterre megszakít, `STOPPED` + prompt), legfeljebb 1 s várás a promptra, és csak ennek hiányában bontás. Ezután *csak olvasási* munkamenetben automatikus újracsatlakozás exponenciális várakozással (1, 2, 4 s; legfeljebb 3 kísérlet), a felületen „ÚJRACSATLAKOZÁS” jelzéssel. **A 04-et és minden jövőbeli írást tilos automatikusan megismételni.**
- **Tesztelés:** TCP-szimulátor: egy válasz kimarad, majd a következő rendben jön → a munkamenet él. A szimulátor bezárja a kapcsolatot → 3 kísérlet, aztán leáll.
- **Előny:** vezetés közbeni naplózásnál nem szakad meg a mérés.

#### N6 – Közepes: háttérben vagy kikapcsolt képernyőnél nincs mérés
- **Ok, fájl:** `MainActivity.onStop` → `resetSession`. Biztonságos alapállapot, de menet közbeni naplózásra (P0420-diagnosztika, trimek terhelés alatt) alkalmatlan.
- **Javasolt javítás:** `DiagnosticSessionService` előtérszolgáltatás `foregroundServiceType="connectedDevice"` típussal. Android 14+ alatt ehhez `FOREGROUND_SERVICE` és `FOREGROUND_SERVICE_CONNECTED_DEVICE` engedély kell, és a meglévő `CHANGE_NETWORK_STATE` a típushoz elfogadott előfeltétel. Ehhez állandó értesítés, `WifiManager.WifiLock` (alacsony késleltetésű mód) és rövid `PARTIAL_WAKE_LOCK` csak naplózás alatt. Az Activity csak megjeleníti a szolgáltatás `StateFlow`-ját.
- **Tesztelés:** készüléken: képernyő ki 10 percre, a napló folyamatos; Doze teszt `adb shell dumpsys deviceidle force-idle` paranccsal.

#### N7 – Közepes: az élőadat-kijelzés egyszerre csak egy értéket mutatott, elavulásjelzés nélkül
- **Ok, fájl:** `MainActivity.scheduleReading`. Minden PID felülírta az egész szöveget.
- **Javítás (elkészült):** PID-enként utolsó érvényes érték és kora (ms), határérték fölött „ELAVULT”. Hibás vagy hiányzó válasznál az érték törlődik, nem marad régi szám.
- **Előny:** egyszerre látható RPM, trim és O2; a régi adat nem tűnik frissnek.

#### N8 – Közepes: hiányzó alapvető PID-ek, csak az első támogatási oldal
- **Ok, fájl:** `MainActivity.items`, `Elm327.connect` (csak `0100`). Nem olvasott terhelés (04), MAP (0B), MAF (10), gyújtás (0E), szívott levegő (0F). Hiányzik a `0120/0140/0160…` lánc, a 03 (üzemanyag-rendszer nyitott/zárt kör) és a 13 (O2-szondák helye).
- **Javítás:** a 04, 0B, 0E, 0F, 10 PID elkészült, tesztelt képletekkel. **Javasolt még:** támogatási lánc követése (a 0x20, 0x40… bit jelzi a következő oldalt), PID 03 és 13 kezelése, 0x42 modulfeszültség vagy `ATRV`.

#### N9 – Közepes: a kódban fix 200 ms szünet és egyenletes körforgás
- **Ok:** K-line-on egy kérés kb. 100–300 ms (P2/P3 időzítés). 13 PID egyenletes körforgásával az RPM 4–6 s-onként frissülne.
- **Javítás (elkészült):** `LivePlan`: a gyors PID-ek minden körben, a lassúak körönként egy; PID-enként külön elavulási határ.
- **Javasolt még:** mért kéréskésés kijelzése; `ATAT1/ATAT2` adaptív időzítés és `ATST` finomhangolás mérés alapján; CAN-en több PID egy kérésben (pl. `010C0D11`, legfeljebb 6) és válaszszám-utótag (`010C1`); a 200 ms helyett 20–50 ms, ha a klón bírja.

#### N10 – Közepes: bármely Wi-Fi hálózatot elfogad
- **Ok, fájl:** `MainActivity.connectWifi`. Ha a telefon az otthoni Wi-Fi-n van, oda csatlakozik `192.168.0.10`-re, és ott egy idegen eszköznek küldhet ELM-parancsot.
- **Javasolt javítás:** `onLinkPropertiesChanged`-ben ellenőrizni, hogy a hálózat valamelyik `LinkAddress`-e ugyanabban az alhálózatban van-e, mint az adapter IP-je. Opcionálisan a felhasználó által jóváhagyott SSID (Android 10+ alatt `WifiNetworkSpecifier`, ami rendszerszintű párosítási párbeszédet ad).

#### N11 – Közepes: több válaszadó K-line-on, fejléc nélkül
- **Ok:** `ATH0` mellett nem látszik, melyik ECU válaszolt. Automata váltós SX4-nél a TCM is válaszolhat a 01/03 kérésekre. A PR #1 helyesen „Több ECU válaszolt” hibát ad, de így a mérés használhatatlanná válik.
- **Javasolt javítás:** kapcsolódáskor egyszer `ATH1` mellett `0100`, majd a válaszadó címek rögzítése (K-line: a 3. fejlécbájt; CAN: 7E8/7E9). Élő adatnál a motorvezérlő válaszának kiszűrése cím alapján (szabványos ELM327-funkció, nem gyártóspecifikus).

#### N12 – Alacsony: „Software caused connection abort” – lásd 1.4

#### N13 – Alacsony (elkészült): a képernyő elforgatása bontotta a kapcsolatot
- Forgatáskor az Activity újraindult (`onStop` → bontás). Most a manifestben `configChanges` van beállítva. Hosszú távon ViewModel + Service a helyes megoldás.

#### N14 – Alacsony: karbantarthatóság
- Kódból épített felület, beégetett magyar szövegek (lint: 42× `SetTextI18n`), nincs alkalmazásikon, nincs `dataExtractionRules`. `Elm327.supported` és `canProtocol` bontás után is megmarad (ártalmatlan, de félrevezető). Javaslat: `strings.xml`, ViewBinding vagy Compose, ikon, `dataExtractionRules` a mentések szándékos kizárásával vagy bevonásával.

#### N15 – Alacsony: inicializálási sorrend
- `ATZ` egyes Wi-Fi-klónokon 1–2 s-ig tart vagy bontja a TCP-t. Javaslat: `ATWS` (meleg újraindítás), és csak hiba esetén `ATZ`. Az utolsó sikeres `ATDPN` mentése és következő kapcsolódáskor `ATSPA5` / `ATSPA3` (automatikus keresés, de előbb a mentettet próbálja) → gyorsabb K-line kapcsolódás.

### 1.4 „Software caused connection abort” – lehetséges okok

A hibaüzenet az `ECONNABORTED` rendszerhiba: a **telefon saját hálózati rétege** szakította meg a kapcsolatot, nem az adapter (az adapter oldali bontás „Connection reset” vagy EOF lenne). Valószínűségi sorrendben, a main ágra vonatkoztatva:

1. **Hálózatváltás az internet nélküli adapter-Wi-Fi miatt (legvalószínűbb).** A main nyers `Socket()`-et használt. Amikor az Android a Wi-Fi-t „nincs internet” állapotúnak minősíti és a mobiladatot teszi alapértelmezetté, illetve amikor a Wi-Fi újracsatlakozik, a rendszer a régi hálózat élő TCP-socketjeit erőszakkal megszünteti (netd socket-destroy). Az eredmény pontosan ez a hiba. *PR #1: kezelve* (Wi-Fi-hez kötött socket és `onLost` bontás).
2. **Háttérbe kerülés vagy kikapcsolt képernyő.** Doze, akkumulátor-optimalizálás vagy háttér-hálózatkorlátozás alatt az Android megszakíthatja az alkalmazás socketjeit. A main nem kezelte az `onStop`-ot. *PR #1: tudatosan bont `onStop`-nál; a tartós megoldás az N6 szerinti előtérszolgáltatás.*
3. **Az adapter Wi-Fi-je eltűnik:** gyújtás le, gyenge tápfeszültség indításkor, vagy a telefon átvált egy ismert (otthoni) Wi-Fi-re. *PR #1: `onLost` kezeli.* Javasolt: az N10 alhálózat-ellenőrzés.
4. **Saját bezárás olvasás közben.** A main `onDestroy`-ban bezárta a socketet, miközben a worker olvasott. Ez Androidon jellemzően „Socket closed” üzenetet ad, ritkábban ECONNABORTED-et.

**Megkülönböztetés:** naplózni kell a kivétel osztályát és üzenetét, az időbélyeget, az aktív és az alapértelmezett hálózatot (`cm.activeNetwork`, a kötött `Network`), az `onLost` / `onCapabilitiesChanged` eseményeket és az Activity életciklusát. Ha a hiba közvetlenül egy `onLost` vagy alapértelmezett-hálózat-váltás után jön, az 1. vagy 3. ok áll fenn; ha `onStop` után, a 2.

---

## 2. Professzionális élőadat-diagnosztika

### 2.1 Suzuki M15A kompatibilitás – mit ne feltételezzünk

- **Protokoll:** 2007-es európai SX4-nél K-line (ISO 9141-2 vagy ISO 14230-4 KWP) valószínű, de nem garantált. Mindig az `ATDPN` dönt, a programnak minden ágon működnie kell.
- **K-line sávszélesség:** reálisan 3–8 PID-válasz/s összesen. A „valós idejű” grafikon itt 1–4 Hz-et jelent csatornánként. Ezt a felületnek ki kell mondania.
- **PID-támogatás:** csak a `0100/0120/0140…` maszkban jelölt PID kérdezhető. Jellemzően várható (nem igazolt): 01, 03, 04, 05, 06, 07, 0B vagy 10, 0C, 0D, 0E, 0F, 11, 13, 14, 15, 1C. Nem várható: 0A üzemanyagnyomás, 24–2B széles sávú lambda, 3C katalizátor-hőmérséklet, Mode 0A (állandó DTC – csak 2010 után kötelező), gyakran Mode 09 VIN.
- **Szívócső-nyomás vagy légtömegáram:** a motor vagy MAP-, vagy MAF-szenzoros; a 0B vagy 10 bit dönti el, a program mindkettőt kezeli.
- **Előszonda típusa:** keskeny sávú szondánál a 14-es PID 0–1 V-ot ad. Széles sávú (A/F) szondánál a 24/34-es PID-ek érvényesek, és a 14-es feszültség-értelmezés téves lenne. Ezért a 13-as (és 1D) PID-del ellenőrizni kell, mely szondák léteznek.
- **Mode 06:** nem CAN-protokollon a TID/CID tartalom és a skálázás **gyártóspecifikus**. Suzuki-dokumentáció nélkül csak nyers értéket és ECU által adott min/max határt szabad mutatni, „gyártói értelmezés szükséges” jelzéssel.

### 2.2 Konkrét fejlesztési javaslatok

| Terület | Javaslat |
|---|---|
| Fordulatszám, hőmérséklet, terhelés, fojtószelep | ✅ 0C, 05, 04, 11, 0F, 0E kész. Javasolt: a 03 (zárt/nyitott kör) folyamatos kijelzése, mert a trimek és az O2 csak zárt körben értelmezhetők. Hidegindítás: a hűtőfolyadék-görbe tempója termosztáthibára utal (lassú felmelegedés, P0128). |
| STFT/LTFT | ✅ B1 kész. Javasolt: összesített trim (STFT+LTFT) kiemelése; határok: ±10% figyelmeztetés, ±20% hiba (ECU-függő, csak tájékoztató). Mérés alapjáraton és 2500 1/percen összevetve (lásd 3. fejezet). |
| Lambdaszondák | ✅ B1S1 és B1S2 feszültség, trim `FF` = nem használt. Javasolt: a 13-as PID-del a szondák helyének ellenőrzése; S1 kapcsolási számláló (0,45 V átmenetek/perc), S2 szórása és tartománya. |
| Többcsatornás grafikon | Saját, könnyű `Canvas`-alapú nézet gyűrűpufferrel (5–10 perc csatornánként), 2–4 csatorna közös időtengelyen, csatornánkénti skálával. Pontok csak a tényleges mintavételi időpontokban; hiányzó mintánál megszakadt vonal, nem interpoláció. Előre beállított nézet: „O2 S1 + S2”, „STFT + LTFT + RPM”. Külső, nem karbantartott chart-könyvtár (pl. JitPack) helyett saját nézet vagy Compose Canvas. |
| PID-támogatás felismerése | Lánc: `0100 → 0120 → 0140…`, amíg a 32. bit be van állítva. Eredmény mentése VIN vagy CALID (ha van) + protokoll kulccsal. A felület külön mutatja a „nem támogatott” és a „támogatott, de most nem válaszolt” állapotot. |
| Frissítési sebesség | ✅ prioritásos `LivePlan`. Javasolt: felhasználói „fókusz” mód (pl. csak 14+15+0C → max. Hz), mért kérés/s kijelzése, adaptív időzítés, CAN-en multi-PID kérés. |
| Naplózás, CSV-export | Formátum: `t_ms_monoton, t_utc_iso, pid, nyers_hex, érték, mértékegység, állapot(ok/hiányzik/elavult/hiba), késés_ms`. Hosszú („tidy”) sorok, nem széles táblázat, mert a PID-ek eltérő időpontban készülnek. Fejléc-megjegyzésben build, VIN/CALID, protokoll, adapter ATI. Export Storage Access Framework-kel (`ACTION_CREATE_DOCUMENT`), tárhely-engedély nélkül. Írás pufferelve, kötegenként `flush`. |
| Hibás, hiányzó, elavult adatok | ✅ elavulásjelzés, hiba után üres érték. Javasolt még: fizikai tartomány-ellenőrzés (pl. hűtőfolyadék −40…130 °C, RPM 0…8000, O2 0…1,1 V), „beragadt” érték felismerése (N mintán át bitre azonos érték járó motornál), és a 03-as PID alapján „nyitott kör – a trim nem értelmezhető” jelzés. |

---

## 3. P0420 diagnosztikai modul (terv)

**Cél:** elkülöníteni a valódi katalizátor-hatásfokcsökkenést a szondahibától, a fals levegőtől és a kipufogószivárgástól. A modul **kizárólag olvas és elemez**, nem módosít ECU-t. Kibocsátásvezérlés kikapcsolását, a szondajel szimulálását vagy hamisítását (pl. „O2-szimulátor”, távtartó-javaslat) **nem tartalmaz és nem javasol**.

### 3.1 Adatforrások
1. **Mode 03 / 07:** P0420 tárolt vagy függő; kísérő kódok (P0130–P0141 szonda, P0171/P0172 keverék, P0300–P0304 gyújtáskimaradás, P0128 termosztát). Gyújtáskimaradás mellett a P0420-at **nem szabad katalizátorhibaként értékelni**, mert az el nem égett keverék károsítja és meghamisítja a katalizátor-tesztet.
2. **Freeze Frame (Mode 02):** a 020200-ban szereplő DTC és az összes támogatott FF-PID (0200 maszk szerint): RPM, terhelés, hűtőfolyadék, sebesség, trimek, 03-as üzemállapot. Kérdés, amire válaszol: milyen körülmények között állította be az ECU a hibát.
3. **Mode 01 élő:** 03, 05, 06, 07, 0C, 04, 14, 15 (+0B/10).
4. **Mode 06:** a katalizátor-monitor teszteredménye és határai. K-line-on gyártóspecifikus, ezért csak nyers TID/érték/min/max megjelenítés, a „megfelelt / nem felelt meg” csak akkor, ha az ECU maga adja a határt.
5. **Mode 01 PID 01 (readiness):** a katalizátor-monitor lefutott-e a törlés óta.

### 3.2 Mérési protokoll (vezetett lépések az alkalmazásban)
1. Előfeltételek, automatikusan ellenőrizve: hűtőfolyadék ≥ 80 °C, PID 03 = zárt kör, nincs aktív gyújtáskimaradás-kód, legalább 10 perc üzemmeleg katalizátor.
2. **Alapjárat 60 s**, majd **2500 1/perc 60 s** (álló jármű, egy segítő tartja a gázpedált – nem vezetés közben kezelendő). Fókusz mód: csak 14, 15, 06, 07, 0C.
3. Minden szakaszra számított mutatók:
   - S1: 0,45 V-átmenetek száma percenként, min/max, átlag.
   - S2: szórás, min/max, átlag, átmenetek száma.
   - **Kapcsolási arány** = S2-átmenetek / S1-átmenetek; **amplitúdóarány** = S2 szórás / S1 szórás.
   - Összesített trim alapjáraton és 2500 1/percen.
   - Mintavételi minőség: tényleges Hz csatornánként; ha < 1 Hz, a modul „korlátozott pontosság” figyelmeztetést ad (K-line-on a szonda kapcsolása 0,5–3 Hz körül van, ezért a számláló alulbecsülhet; az arányok és a szórás robusztusabbak).

### 3.3 Döntési szabályok (tájékoztató, nem végleges diagnózis)

| Minta | Valószínű ok | Következő lépés |
|---|---|---|
| S1 gyorsan kapcsol, S2 stabil 0,55–0,8 V, kis szórás; nincs P0420 | Egészséges katalizátor | – |
| S2 közel azonos ütemben kapcsol, mint S1 (kapcsolási arány > ~0,6, amplitúdóarány > ~0,5), trimek normálisak, szivárgás nincs | **Katalizátor hatásfoka csökkent** | Kipufogó szemrevételezése, Mode 06 nyers adat, csak ezután katalizátorcsere |
| S1 lassú (kevés átmenet 2500-on), kis amplitúdó (pl. 0,3–0,6 V között), S2 normális | **Elöregedett előszonda** | Szondafűtés-kódok, szonda-válaszidő; cserelépés a katalizátor előtt |
| S2 tartósan alacsony (< 0,3 V) vagy szabálytalanul ugrál, miközben S1 rendben van | **Kipufogószivárgás az utószonda előtt** (beszívott levegő) vagy hibás utószonda | Füstteszt / hallgatózás hideg motornál, tömítések és flexibilis cső |
| Összesített trim alapjáraton > +10%, de 2500-on közel 0 felé csökken | **Fals levegő** (vákuumszivárgás) | Szívócső, PCV, fékrásegítő cső; füstteszt |
| Összesített trim minden fordulaton > +10% | Üzemanyag-ellátás vagy légmennyiség-mérés (MAF/MAP) hibája | Üzemanyagnyomás külső mérése, MAF/MAP ellenőrzése |
| Trim < −10%, S1 tartósan magas | Dús keverék (befecskendező, nyomás, EVAP) – a katalizátort is károsítja | Okfeltárás a P0420 előtt |
| Gyújtáskimaradás-kód vagy -tünet | A katalizátor-teszt nem értékelhető | Előbb a gyújtás vagy keverék javítása |

### 3.4 Magyar nyelvű hibakeresési útmutató (az alkalmazásban megjelenítendő)
1. Olvasd ki és **mentsd** az összes hibakódot és a Freeze Frame-et. Ne törölj, amíg a diagnózis nem kész.
2. Ha gyújtáskimaradás vagy keverékhiba-kód is van, azt javítsd először.
3. Melegítsd üzemmelegre az autót, és futtasd a vezetett mérést (alapjárat + 2500 1/perc).
4. Értékeld a trimeket: alapjáraton magas, fordulattal csökkenő pozitív trim fals levegőre utal.
5. Hasonlítsd össze az elő- és utószondát a fenti táblázat szerint.
6. Kipufogószivárgás gyanújánál hideg motornál ellenőrizd a csonktömítést, a flexibilis csövet és a szondamenetek környékét.
7. Csak a fentiek kizárása után gyanakodj a katalizátorra. A csere után a readiness-monitor lefutását egy vegyes vezetési ciklus után ellenőrizd.
8. A modul eredménye **tájékoztató**: a végső döntéshez szakszerviz-mérés (pl. kipufogógáz-elemzés) ajánlott.

### 3.5 Megvalósítási váz
- `p0420/CatalystSession` (adatgyűjtés a meglévő Elm327-en, csak engedélyezett 01/02/03/06/07 parancsokkal; a `06xx` minta engedélylistába vétele szükséges).
- `p0420/SignalStats` (tiszta Kotlin: átmenetszám, szórás, arányok) – egységtesztekkel, szintetikus jelekkel (egészséges, elöregedett, szivárgó minta).
- `p0420/Assessment` (a 3.3 szabályai, magyar indoklással és bizonyossági szinttel).
- Az eredmény JSON + CSV-ként menthető és megosztható szervizzel.

---

## 4. Szervizszintű funkciók

### 4.1 Mi valósítható meg szabványos ELM327-tel (SAE J1979 / ISO 15031-5)

| Funkció | Mód | M15A 2007 – várható | Kockázat |
|---|---|---|---|
| Élő adatok | 01 | igen, maszk szerint | olvasás |
| Freeze Frame | 02 | igen | olvasás |
| Tárolt / függő DTC | 03 / 07 | igen | olvasás |
| DTC-törlés | 04 | igen | **állapotot módosít** – kapukkal (N3, mentés, megerősítés) |
| O2-szonda monitor (nem CAN) | 05 | lehetséges | olvasás |
| Fedélzeti tesztek eredménye | 06 | lehetséges, gyártóspecifikus értelmezés | olvasás |
| Fedélzeti rendszer vezérlése (pl. EVAP-teszt) | 08 | ritkán támogatott | **aktív** – nem javasolt bevezetni dokumentáció nélkül |
| VIN, CALID, CVN | 09 | bizonytalan | olvasás |
| Állandó DTC | 0A | nem várható (2010 előtti) | olvasás |
| Akkumulátorfeszültség | `ATRV` | adapterfüggő | olvasás |

### 4.2 Mihez kell gyártóspecifikus interfész vagy dokumentáció
ABS, SRS (légzsák), BCM, műszerfal, immobilizer és kulcsprogramozás, alapjárati vagy fojtószelep-tanulás nullázása, befecskendező-, üzemanyag-szivattyú- és ventilátor-aktívtesztek, ECU-paraméterek vagy kódolás. Ezekhez a Suzuki gyári diagnosztikája (SDT / SDT-II) vagy azzal egyenértékű, dokumentált protokollú eszköz kell. A KWP2000 vagy UDS szolgáltatások (pl. `$21`, `$30`, `$31`, `$3B`) azonosítói Suzukinál **nem nyilvánosan dokumentáltak**. Ezért ez az audit **nem javasol konkrét gyártóspecifikus parancsot**. Fórumokból vagy visszafejtésből származó azonosítók használata ECU-sérülést, immobilizer-zárolást vagy hibás működést okozhat.

Gyakorlati alternatíva: az M15A-nál sok „tanulás” a szervizkönyv szerinti eljárás (pl. akkumulátor-lekötés utáni alapjárati tanulási menet). Az alkalmazás ezt **útmutatóként** jelenítheti meg, ECU-parancs nélkül.

### 4.3 Biztonságos keretrendszer jövőbeli aktív funkciókhoz (terv, nem implementálandó most)
1. **Képességnyilvántartás:** minden művelet egy `ServiceProcedure` leíró: azonosító, *dokumentációs forrás* (szervizkönyv-fejezet vagy szabványpont), érintett ECU-cím, pontos kérés- és válaszminta, időkorlát, „visszaállás” lépés (`returnControlToECU`), érvényességi kör (VIN-tartomány, CALID-lista). Forrás nélkül a leíró nem fordul le (build-időben ellenőrzött).
2. **Három jogosultsági szint:** OLVASÁS (alap) → TÖRLÉS (04, meglévő kapuk) → AKTÍV/ADAPTÁCIÓ (alapértelmezésben kikapcsolva, csak fejlesztői beállítással és külön build-flaggel).
3. **Előfeltétel-kapuk** minden aktív művelet előtt friss méréssel: sebesség 0; motor állapota az eljárás szerint; akkumulátorfeszültség ≥ 12,2 V (`ATRV` vagy 0x42); nincs kapcsolatvesztés az elmúlt N s-ban; az előzetes állapot mentve.
4. **Kétlépcsős megerősítés** a művelet magyar nyelvű leírásával és következményeivel; időkorlátos végrehajtás, automatikus visszaállás megszakításkor; **soha nincs automatikus ismétlés**.
5. **Auditnapló** (időbélyeg, build, VIN, kérés és válasz nyersen) minden állapotmódosító műveletről.
6. **Szimulátor-először:** minden eljárás csak akkor engedélyezhető, ha a TCP-szimulátoron a sikeres, az elutasított (`7F xx 22/31/33`) és a megszakított ágra van teszt.
7. A jelenlegi `Elm327.command` engedélylistája marad az egyetlen kapu. Új parancs csak kódellenőrzéssel kerülhet bele, és a `writeCommandsAreRejected` teszt bővül.

---

## 5. Verziókezelés és APK-frissítés (elkészült)

| Igény | Megoldás |
|---|---|
| Automatikus verziószám | `version.properties` → `VERSION_NAME=0.2.0` (szemantikus, kézzel léptetve kiadáskor). Kiadáskor git tag `v0.2.0` ajánlott. |
| Növekvő `versionCode` | CI-ben `APP_BUILD_NUMBER = github.run_number`. Minden futás nagyobb az előzőnél (ágtól függetlenül), és ≥ 2, tehát nagyobb a korábbi APK `1` értékénél. Helyben a commitok száma. |
| Valódi `versionName` | `0.2.0-build<N>` |
| Commit és buildszám kijelzése | `BuildConfig.GIT_SHA` (PR-nál a valódi head commit, nem a GitHub ideiglenes merge commitja), `BUILD_NUMBER`, `BUILD_TYPE`; a fejlécben és a törlés előtti JSON-mentésben is. Helyi, nem commitolt módosításnál `-dirty` utótag. |
| GitHub Actions APK | A meglévő workflow tesztekkel és linttel; az APK fájlneve tartalmazza a verziót; release APK, ha van kulcs. |
| Kompatibilis aláírás | `SX4_KEYSTORE_BASE64`, `SX4_KEYSTORE_PASSWORD`, `SX4_KEY_ALIAS`, `SX4_KEY_PASSWORD` titkok; a debug és a release is ezzel a kulccsal készül. Titkok nélkül figyelmeztetés a CI-ban. A kulcsot a tárolóba commitolni tilos (`.gitignore` kizárja). |
| Adatok megőrzése | Azonos `applicationId` + azonos aláírás + nagyobb vagy egyenlő `versionCode` → az Android frissítésként telepít, a `files/` megmarad. |

**Teendő a tulajdonosnak (egyszeri):** a README-ben leírt `keytool` paranccsal kulcs létrehozása és a 4 titok felvétele. A jelenleg telepített (ideiglenes kulcsú) alkalmazást **egyszer** el kell távolítani az első stabil kulcsú APK előtt. Ez az egy lépés elkerülhetetlenül törli a belső mentéseket, ezért előtte érdemes kimenteni őket. A kulcsot és a jelszavakat legalább két biztonságos helyen meg kell őrizni.

Ajánlott következő lépés: `release` eseményre (tag push) külön workflow, ami GitHub Release-t hoz létre az aláírt APK-val és SHA-256 ellenőrzőösszeggel.

---

## 6. Tesztelés

### 6.1 Meglévő és új automatizált tesztek (JVM)

| Csomag | Tesztek | Lefedés |
|---|---|---|
| `ObdTest` (PR #1: 12, új: 4) | 16 | hex DTC, CAN-számláló, ISO-TP, több ECU, PID-maszk, képletek, 04-visszaigazolás; **új:** K-line `BUS INIT`, szöveges státuszok NPE nélkül, többsoros K-line DTC, új PID-képletek, törlési előfeltétel |
| `Elm327Test` (PR #1: 7, új: 1) – TCP-szimulátor | 8 | inicializálás, töredezett válasz, EOF, teljes határidő, bontás olvasás közben, túl hosszú válasz, tiltott parancsok; **új:** üdvözlőszöveg kapcsolódáskor |
| `LivePlanTest` (új) | 4 | prioritásos sorrend, csak támogatott PID, minden PID-hez dekóder, elavulási határok |

Helyben futtatva: **28/28 sikeres** (JVM, Kotlin 2.0.21). Az Android-fordítást és a lintet a PR CI ellenőrzi.

### 6.2 Javasolt további tesztek

1. **ELM327 TCP-szimulátor kiterjesztése** (`test/.../sim/ElmSimulator.kt`): forgatókönyv-alapú (YAML/JSON) szimulátor beállítható protokollal (K-line késleltetés 50–300 ms, CAN), `SEARCHING...`, `BUS INIT: ...OK`, többsoros DTC, két ECU, véletlen `NO DATA`, csonka válasz, prompt nélküli válasz, félig nyitott kapcsolat (nincs FIN, nincs válasz), RST, kapcsolódáskori üdvözlés. Valódi autóról rögzített nyers naplók visszajátszása regressziós tesztként.
2. **Hibás és hiányos válaszok:** tulajdonságalapú (property-based) teszt véletlen bájtfolyammal: a feldolgozó vagy érvényes eredményt, vagy `IllegalArgumentException`/`IllegalStateException`-t adhat, *soha* NPE-t, indexhibát vagy végtelen ciklust.
3. **Kapcsolatmegszakadás és újracsatlakozás:** (N5 után) a szimulátor bont → pontosan 3 kísérlet a várakozási idővel; a 04 soha nem ismétlődik.
4. **Wi-Fi-újracsatlakozás:** a hálózati réteg kiemelése egy `NetworkProvider` interfész mögé; Robolectric `ShadowConnectivityManager`-rel `onAvailable` → `onLost` → `onAvailable`; ellenőrzés: régi generáció nem ír a felületre, a socket bezárva.
5. **Hibakódtörlés biztonsági feltételei:** a törlési folyamat kiemelése egy tiszta `ClearController` állapotgépbe (Idle → Backup → Confirm → Precheck → Sent → Verified/Failed). Tesztek: Mégsem → nincs 04; módváltás vagy bontás a párbeszéd alatt → nincs 04; mentési hiba → nincs 04; járó motor vagy mozgás → nincs 04; dupla kattintás → pontosan egy 04; időtúllépés → „bizonytalan”, nincs ismétlés.
6. **Android életciklus és UI:** Robolectric (JVM-en, CI-ban gyors) `ActivityScenario`-val: `onStop` bont, forgatás nem bont, DEMO kapcsoló törli a valódi adatokat. Espresso-teszt emulátoron (`reactivecircus/android-emulator-runner`), szimulátorral a hoszton (`10.0.2.2`).
7. **Valós ellenőrzési jegyzőkönyv:** a Codex `docs/AUDIT.md` fizikai ellenőrzési listája kiegészítve: `ATDPN`, `ATI`, `0100`/`0120`, `0113` nyers válasz rögzítése; mért kérés/s alapjáraton.

---

## 7. Prioritásos fejlesztési terv

| Prioritás | Tétel | Állapot |
|---|---|---|
| **P0** | PR #1 átnézése és összeolvasztása (a main kritikus hibái) | felhasználói döntés |
| **P0** | N1 állandó aláírás + automatikus verzió (titkok felvétele) | ✅ kód kész; a titkokat a tulajdonosnak kell felvennie |
| **P0** | N2 K-line `BUS INIT` NPE | ✅ |
| **P0** | N3 törlés csak álló motornál és járműnél | ✅ |
| **P1** | Első valós autós próba: nyers `ATI/ATDPN/0100/0120/0113/03` napló mentése és megosztása | következő lépés |
| **P1** | N4 üdvözlőszöveg, N7 élőadat-tábla, N8 új PID-ek, N9 prioritásos lekérdezés, N13 forgatás | ✅ |
| **P1** | N11 válaszadó ECU szűrése (`ATH1`), támogatási lánc (0120…), PID 03/13 | javasolt |
| **P1** | N5 kíméletes időtúllépés-kezelés + olvasási újracsatlakozás | javasolt |
| **P2** | N6 előtérszolgáltatás + ViewModel; CSV-napló + SAF-export | javasolt |
| **P2** | Többcsatornás grafikon | javasolt |
| **P2** | P0420 modul (3. fejezet), Freeze Frame teljes kiolvasás, Mode 06 nyers megjelenítés | javasolt |
| **P2** | `ClearController` kiemelése + Robolectric tesztek | javasolt |
| **P3** | N10 alhálózat-ellenőrzés, N14 erőforrások/ikon, N15 `ATWS` + protokoll-memória, release-workflow | javasolt |
| **–** | ECU-írás, aktív tesztek, adaptációk | **nem kezdődik el** dokumentált protokoll és a 4.3 keretrendszer nélkül |

---

## 8. Az öt leghatásosabb fejlesztés

1. **Állandó aláírás és automatikus versionCode/versionName (N1)** – végre frissíthető az alkalmazás adatvesztés nélkül, és látszik, melyik build fut.
2. **A PR #1 transzport-, hálózat- és életciklus-javításai, kiegészítve a K-line `BUS INIT` javítással (M1–M5, N2)** – megszünteti a „Software caused connection abort” legvalószínűbb okát, a blokkolt workert, és a K-line autón jelentkező kapcsolódási hibát.
3. **Biztonságos törlés: friss mentés + álló motor és jármű ellenőrzése + pontos visszaigazolás (M4, N3)** – nem lehet véletlenül vagy menet közben törölni, és a törlés előtti állapot visszakereshető.
4. **Valós idejű, megbízható élőadat: prioritásos lekérdezés, elavulásjelzés, támogatási lánc és ECU-szűrés, majd előtérszolgáltatás CSV-naplóval (N5–N9, N11)** – K-line sebesség mellett is használható, nem mutat régi vagy hamis adatot, és menet közben is naplóz.
5. **P0420 diagnosztikai modul a két szonda, a trimek, a Freeze Frame és a Mode 06 együttes elemzésével (3. fejezet)** – a katalizátor, a szonda, a fals levegő és a kipufogószivárgás elkülönítése, így elkerülhető a felesleges katalizátorcsere.
