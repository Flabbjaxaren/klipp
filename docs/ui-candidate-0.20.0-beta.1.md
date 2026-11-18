# UI-kandidat 0.20.0-beta.1 (versionCode 100) – testrapport

Intern kandidat för menyomläggningen på grenen `ui/menu-architecture`, ovanpå den interna kandidaten 0.19.3-beta.2.
Ingen push, tagg eller publicering. Den frysta betan 0.19.3-beta.1 (435b454, gren och tagg) och beta.2 är orörda.

| | |
|---|---|
| Commit | `a9be050` (träd i `COMMIT` i byggmappen) |
| Release | `se.gendut.klipp` 0.20.0-beta.1, versionCode 100 – SHA-256 `af0e3096b4f0c58a1f9b4f436cf7d587a572cdd5d265fe8a2d2467250384905c` |
| Testpaket | `se.gendut.klipp.designtest` 0.20.0-beta.1-designtest.181, versionCode 100 – SHA-256 `9fad901a9a619fdee3b79c79f5010a55ccad94bf0b529d2a4793d5e2f0caf00c` |
| Signatur | release-nyckeln, APK Signature Scheme v2, cert SHA-256 `58c86a6f…c182` (CN=Klipp) – båda paketen |
| Motorbrygga | byggd i rent worktree med cargo ndk som i CI; identisk med beta.2 (as-built `2100199c…`, i APK `b4dcfce8…`) |
| Lagring | `~/AndroidStudioProjects/Klipp-builds/0.20.0-beta.1-ui-internal/` (APK:er, mappningar, brygga, MANIFEST.md, SHA256SUMS, smoke/) |

Versionsval: menyomläggningen ändrar hur appen används (tre lägen, nya ark, Tillbaka lager för lager), därför en ny
minor-version i stället för 0.19.3-beta.3. versionCode 100 är den första lediga över 99.

## Ändringar sedan 180-ui

| # | Ändring | Commit |
|---|---|---|
| 1 | **Fel syns i aktivt fönster.** Varje ark (Lägg till-arken, ljudbiblioteket) visar meddelanden överst i arket, varje dialog som en rad i texten. Bara det översta fönstret visar (editorn < ark < dialog); arket och det som skrivits stannar kvar. Samma text två gånger visas en gång; ett fönster som öppnas efter ett meddelande visar det inte; när ett ark stängs under ett meddelande visar editorn det inte igen. Meddelanden läses upp (live region). | `86fcf04` |
| 2 | **Inga osynliga titlar på V4+.** Platsen väljs före skapandet: ovanför bilden vid spelhuvudet, lägsta av V1–V3 som är ledigt under hela titelns längd och inte låst; spåret anges till `graphics.newText` (som vägrar ett upptaget spår – inget skrivs över). Utan plats skapas inget och skälet visas (V3 upptaget vid spelhuvudet / inget ledigt spår under titelns längd / det lediga spåret låst). Titelarkets text och arket ligger kvar tills titeln finns på tidslinjen. Ingen motorändring. | `81888fe` |
| 4 | **Låg skärm.** Under 480 dp skärmhöjd (liggande) rullar ett högt ark som en enda kolumn (rubrik, flikar, innehåll, knapp) – annars blev innehållet en strimma och textfältet fick ingen plats med tangentbordet uppe. | `1281250` |
| – | Undertextrad vid slutet av föregående (från 180-ui): starten skickas som rutans tick. | `9236499` |

## Prov

### Enhetstester och verktyg

- `testDebugUnitTest` (1281250 – därefter bara versionshöjningen): **728 tester, 727 godkända, 0 misslyckade, 1 överhoppat**
  (`FilmCpuRenderTest.renderCases`, som tidigare). Nya: `MessagesTest` (avvisning direkt i trycket, asynkront fel,
  samma meddelande två gånger, gammalt meddelande och nytt fönster, dialog), `TitlePlaceTest` (inkl. 4 000 slumpade
  lägen: aldrig över ett klipp, alltid V1–V3, aldrig under bilden), `LayoutReachTest`.
- `selftest.py`: alla kontroller beter sig.

### Samma slutbygge (181) på båda telefonerna

| Prov | OnePlus (CPH2581, 360 dp bred) | vivo (V2562) |
|---|---|---|
| Titel: text först, mallbyte behåller texten, läggs på synligt spår | V2 vid 0,6 s, "Maja Lind" | samma |
| Titel utan plats (V1–V3 upptagna vid spelhuvudet) | skälet i arket; inget skapat, inget nytt spår; arket öppet och texten kvar; meddelandet borta efter sin tid | samma |
| Undertexter direkt efter varandra från 3,2 s (5,2 och 7,2 är Float under rutan) | 3,2–5,2 · 5,2–7,2 · 7,2–9,2; spelhuvudet 9,2 | samma |
| Rad inne i en undertext | knappen avstängd med förklaring, inget tillagt, texten kvar | samma |
| Transkriptionsklippning med ångra | **oprövat** (ingen internetanslutning för talmodellen, se nedan) | "är ett" borttaget: rutan sa 00:00:00:07–00:00:00:16 (0,4 s), V1+A1, senare flyttas tidigare; 0,36 s kortare, ett ångra-steg, ångra återställer |
| Tillbaka lager för lager (17 tryck) | 17/17 som tidigare | 17/17 |
| Export genom UI:t: start → HOME, start → BACK, återkomst | båda: exporten fortsätter, fil hel (6 300 rutor, inga avkodningsfel), dialogen "Export saved" vid återkomst | samma |
| Kort regression R1–R6 | **49/49** | **48/49** – se nedan |

### Layout

- Robolectric, text 1,3×: editorn i liggande läge (lägg till-raden, lägena, ångra, inställningar, ett meddelande),
  redigeringsraden i sin liggande bredd med transkriptionsväxeln, titel- och undertextarken (Lägg till, Skriv själv) i
  liggande och vid 360 dp – synliga, tryckbara, inom fönstret; en avvisning syns i arket.
- OnePlus är 360 dp bred: alla telefonprov där körde i 360 dp.
- OnePlus liggande (tillfällig rotation, återställd): exportdialogens Export, titelarkets Lägg till (efter rullning i
  det kompakta arket) och Skriv själv:s knapp nåbara. Exportdialogens alternativ rullar i en låg ruta.

### Release-paketet själv

- Statiskt: release-dex saknar `frame_probe`, `film_test`, `export_latch`, `TEST_HOOKS` (testpaketet har dem).
- I en ny, tom emulator (Android 15 arm64; skapad för provet och borttagen) – ingen telefoninstallation rörd: bryggan
  av som standard (ingen socket); påslagen 20 verktyg, inga testverktyg, anrop till dem avvisas ("no tool …");
  projekt, import, film, uppspelning (82 rutor), export 100/100 rutor 1920×1080 25p; inga krascher, inga fel i loggen.

## Oprövat

- **Transkriptionsklippning på OnePlus.** Telefonen saknar internet och talmodellen ligger i appens interna lagring (går
  inte att lägga dit på ett release-signerat paket). En tillfällig proxy via datorn nekades (telefonens globala
  proxyinställning) – inte gjort. Provat på vivo.
- **Text 1,3× på telefon.** Bara i Robolectric (systemets textstorlek på telefonerna ändrades inte).
- **Exportdialogen i Robolectric vid 360 dp.** Ett textfält i en dialog vid den bredden gör att Robolectrics Compose
  aldrig blir stilla – även ett bart `BasicTextField` i en `AlertDialog`, även före dagens ändringar. Testmiljön, inte
  appen; exportdialogen provades i stället på OnePlus (360 dp, stående och liggande).
- **vivo R4 – en sen ruta.** I R1–R6 en sen ruta i R4 (första uppspelningen, 1,9 ms); omkörning: första uppspelningen
  0 sena, efter ombyggnad en sen (9,1 ms). Beta.2 (171) hade 0 sena i R4 på vivon samma morgon (2 körningar). Fler
  upprepningar avbröts (telefonen behövdes); nedgradering för A/B går inte utan avinstallation. Oklart om det är
  vivons kända enstaka millisekundmissar (tidigare t.ex. 34/35 med 0,1 ms) eller något i UI-grenen. OnePlus: 0 sena.
  Export, förrendering, pausade bilder och identiteter är rätt på båda.
- **vivons återställning bekräftades inte helt:** telefonen kopplades ur under återställningen – projektet öppnades
  igen (spelhuvud 0, Redigera), men att bryggan stängdes av och "håll vaken" återställdes hann inte bekräftas.
- GPU-filmproven kördes inte på 181 (filmkedjan är oförändrad sedan beta.2, där de kördes på båda telefonerna).

## Kända baslinjefel (oförändrade)

- `FilmFusedTest.effectsOnly` (12 708 mot 12 484) – sedan tidigare, båda telefonerna.
- `FilmCpuRenderTest.renderCases` överhoppat.
- F6 för **befintliga** titlar: en titel som redan ligger på V4+ (gjord av ett äldre bygge) syns fortfarande inte – nya
  titlar hamnar inte där längre. Ingen flytt av gamla klipp ingår.
- Ett meddelande som kommer precis när ett ark öppnas visas inte (varken i arket eller bakom) – medvetet, för att aldrig
  visa inaktuella meddelanden.

Inget pushat, ingen tagg, ingen release.
