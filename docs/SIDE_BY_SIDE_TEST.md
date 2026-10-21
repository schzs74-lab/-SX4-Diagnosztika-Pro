# Külön telepíthető tesztváltozat

Ez az ág a build13 forrásából készít külön telepíthető változatot.

- Alkalmazás neve: **SX4 Diagnosztika Teszt**.
- Android alkalmazásazonosító: `hu.sx4.diagnosztika.teszt`.
- A régi alkalmazás azonosítója: `hu.sx4.diagnosztika`.
- A namespace és a Kotlin csomagnevek változatlanok; az Android telepítési azonosságát az applicationId adja.
- Az APK a GitHub Secretsben tárolt állandó kulccsal készül. Hiányzó kulccsal a CI leáll.
- A CI a csomagazonosítót, az alkalmazás nevét és az aláíró tanúsítványát a kész release APK-ból is ellenőrzi.

## Telepítés telefonon

A release APK-t új alkalmazásként telepítsd. A build5 eltávolítása vagy adatainak törlése nem szükséges. Az új alkalmazás önálló tárhelyet használ, a korábbi diagnosztikai mentések nem kerülnek át automatikusan.

Az új alkalmazás alapértelmezésben DEMO módban indul. A hálózat/adapter és az ECU-val való működés fizikai tesztje továbbra is szükséges; a CI fordítást, unit teszteket, lintet és a telepítési metaadatokat ellenőrzi. Eszközön való együtt telepítést a CI nem végzett.

A további tesztverziók megtartják ezt az applicationId-t és ugyanazt az aláírókulcsot. A verziókódot a CI futásszáma növeli.
