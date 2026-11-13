# Utvecklingsplan för Klipp – pålitlighet (version 2)

Version 2 av planen (med PR 8 om Media3), kontrollerad mot koden på `design-test` (0.18.52-designtest.11, 2026-11-11). Ändringar mot
version 1 är markerade **[Ändrat]** eller **[Nytt]**.

## Mål

Gör befintliga redigeringsfunktioner **pålitligare, tryggare och enklare att använda** innan nya kreativa funktioner
byggs. Prioritera:

1. Korrekt och tydlig export.
2. Skydd mot förlorade projekt.
3. Överensstämmelse mellan förhandsvisning och exporterad video.
4. Enklare projektstart och upptäckbara kontroller.

## Arbetsregler

- Verifiera varje problem mot aktuell kod innan det åtgärdas (kartläggningen nedan är en ögonblicksbild).
- Återanvänd befintlig projektpaketering (`.klipp`), versionshistorik, exportlogik och UI-komponenter.
- Bakåtkompatibilitet med befintliga projekt och paket.
- Ingen molntjänst, telemetri eller automatisk nätverkstrafik.
- Nya texter på engelska (`values/`) och svenska (`values-sv/`), via `stringResource`/`Str.t`; testerna körs på svenska.
- En PR per avsnitt. Inga stora arkitekturomskrivningar utan reproducerat problem.
- Redovisa körda tester och skilj automatiska tester (Robolectric/roborazzi), instrumenterade GPU-tester
  (`app/src/androidTest`, kräver telefon) och manuell verifiering på telefon.
- **[Nytt]** Repot är publikt: inga personliga filmer, nycklar eller e-postadresser i commits; nämn inte andra
  redigeringsprogram som förebild i kod eller commits.

---

## Kartläggning (gjord)

### Sparande, versionshistorik, paketering

| Vad | Var | Hur |
|---|---|---|
| Autospar | `engine/ProjectController.kt` `scheduleSave()` / `save()` / `saveLocked()` | Fördröjt efter varje redigering; under `editLock` (aldrig ett halvt ångra-steg). Motorn skriver `.fcproj` (`file.save`), appen skriver `.media.json` med `writeAtomic(…, bak = true)` och tumnagel. |
| Spara vid bakgrund | `MainActivity.onStop()` → `controller.saveNow()` | Sparar direkt när appen lämnas. |
| Versionshistorik | `ProjectController.keepVersion()` (≈ rad 1048), `versions()`, `restoreVersion()` | En kopia högst var `VERSION_EVERY_MS` under redigering, de 20 senaste (`VERSIONS_KEPT`). Ligger i appens interna lagring. |
| Projektpaket | `engine/ProjectPackage.kt` (`write`, `read`, `relinked`), `ProjectController.exportProject()` / `importProject()` | Zip med manifest (`format`), projekt, sidfil, tumnagel och valfritt media. Import skapar alltid ett **nytt** projekt med media uppackat i appen. |
| UI | `ProjectsScreen.kt` | Långtryck på en projektbild öppnar meny: Öppna, Byt namn, Duplicera, Exportera projekt (med/utan media), Versioner, Radera. Rutnätets kort har en ⋮-knapp; det stora kortet (senast öppnat) saknar den. |

### Export

| Vad | Var | Hur |
|---|---|---|
| Dialog | `ExportUi.kt` `ExportDialog` / `ExportOptions` | Varningar för uppskalning och storlek som inte stöds visas som text; **exportknappen spärras aldrig**. |
| Start | `App.kt` `runExport()` → `engine/Playback.kt` `Exporter.export()` → `exportWith()` → `exportOnce()` | Normaliserar tyst: HDR blir HEVC eller SDR; 10-bit väljer HEVC/AV1 (`VideoPipeline.tenBitCodec`) och faller tillbaka till 8-bit om det misslyckas; en storlek codec:en inte klarar ger **byte till annan codec** (`exportOnce`, ≈ rad 1023); encoder-fallback är på (`setEnableFallback(true)`). |
| Hårdvarustöd | `engine/Export.kt` `EncoderCaps` | `sizeSupported` returnerar `false` både vid "nej" och vid fel/okänt (`getOrDefault(false)`). |
| Fil | `Exporter.exportTo` | Skrivs först till `cacheDir`, sedan till Filmer/Klipp; temporärfilen tas bort i `finally`. |

### Bakgrund och processdöd

- Export körs i Compose-scope i `App.kt` (`scope.launch`), **utan foreground service** (ingen service i manifestet).
  Rotation och liknande startar inte om aktiviteten (`configChanges`), men om Android dödar processen i bakgrunden
  försvinner exporten utan meddelande. Nästa start säger ingenting om det.
- Projektet är säkert till senaste autospar och sparas vid `onStop`.

### Befintliga tester

- Export: `ExportSettingsTest`, `ExportBitrateTest`, `ExportSizeCheckTest` (enbart "ändrades storleken").
- Paket: `ProjectPackageTest` (rundtur med media, bara projekt, sökvägar inom mediemappen, inte ett paket, filnamn).
- Projektsidan: `ProjectsTest` (lista/öppna/skapa/meny via långtryck, sökning, smal telefon).
- Rendering: `EngineContractTest`, `LayerSyncTest`, `OverlayRenderTest`, `RenderCacheTest`, `FrameTimeTest`,
  `FilmCpuRenderTest`; GPU på telefon: `androidTest/GlHarness.kt`, `GradeGpuTest`, `FilmGpuTest`, `GpuGradeStillTest`.
- Livscykel/processdöd: **inga**.

### Avvikelser från version 1

1. `remember` utan codec-nyckel stämmer (`ExportUi.kt:89`), men knappen spärras inte heller av andra skäl.
   Huvudproblemet är snarare att exporten **byter codec, bitdjup och HDR tyst** och att sammanfattningen visar det
   valda, inte det som faktiskt exporteras.
2. `EncoderCaps.sizeSupported` behandlar okänt stöd som "nej" – precis det planen varnar för.
3. "Återställning till nytt projekt" finns redan, både för import av paket och för versionshistoriken
   (`restoreVersion` skapar ett nytt projekt). *(Rättat efter genomläsning av koden: version 2 påstod först att
   versionshistoriken skrev över projektet.)*
4. Exportera projekt öppnar med `"wt"` på en fil användaren valt. Väljs en befintlig säkerhetskopia skrivs den
   över direkt, och vid fel raderas filen (`deleteDocument`) – **en tidigare fungerande kopia kan förstöras**. Det
   är ett skarpt fel och flyttas in i PR 3.
5. Tips finns (`Tips.kt`, sparas som `tip_<nyckel>` i `AppPrefs`) men kan inte återställas från Inställningar.
6. Nytt projekt har redan "Som första klippet" och format, men visar bildfrekvens, samplingsfrekvens, kanaler och
   exportinställningar direkt i dialogen.
7. Exportdialogen har redan kvalitetsnivåer (`ExportPresets`: låg/standard/hög/max + anpassad), men bara för
   bithastighet.

---

## PR 1 – Exportdialogens tillstånd och validering

**P1 · liten–medel**

1. `remember(s.codec) { VideoPipeline.tenBitCodec(s.codec) }`, och se över övriga `remember` i exportflödet
   (`custom` i kvalitetsfliken ska följa `s.bitrateMbps`).
2. **[Ändrat]** En ren, testbar funktion `ExportPlan.resolve(settings, project, caps): ExportPlan` med
   - `errors` (blockerar), `warnings` (tillåter), och
   - **effektiva inställningar**: den codec, det bitdjup, HDR/SDR och den storlek som faktiskt används, efter samma
     regler som `exportWith`/`exportOnce` tillämpar i dag.
3. `exportWith`/`exportOnce` och dialogen anropar båda `resolve`; exporten tar sina beslut från planen i stället för
   egna kopior av reglerna. De automatiska fallbackarna *under* exporten (10-bit → 8-bit om kodaren fallerar,
   tonmappning på andra vägen) behålls, men rapporteras i resultatmeddelandet.
4. **[Ändrat]** `caps` är ett gränssnitt runt `EncoderCaps` med tre svar: `YES`, `NO`, `UNKNOWN`. `sizeSupported`
   ska skilja ett fel/undantag (`UNKNOWN`) från ett nekande. `UNKNOWN` blockerar inte och ger en varning.
5. Blockerande fel är bara det som säkert inte går: ingen kodare alls för någon codec i den storleken, HDR utan
   HDR-kodare när HDR är tvingat, tomt projekt. En storlek som valt codec inte klarar men en annan gör blir en
   **varning med knapp** ("Använd HEVC").
6. Sammanfattningsraden visar den **effektiva** codec:en och bitdjupet (t.ex. "H.264 → HEVC 10-bit").

**Acceptans:** codecbyte uppdaterar bitdjup och förklaringar direkt; kända omöjliga kombinationer spärrar
Exportera; uppskalning är en varning; dialog och export kan inte bedöma samma inställningar olika; okänt stöd är
inte ett nej.

**Tester:** enhetstester för `resolve` med falska `caps` (YES/NO/UNKNOWN, H.264 10-bit, HDR, 4K på svag kodare);
Compose-test för codecbyte → bitdjupets hint, felmeddelande och spärrad `exp:go`.

---

## PR 2 – Synliga projektåtgärder och säkerhetsstatus

**P1 · liten**

1. ⋮-knapp (minst 44 dp, `contentDescription` "Fler val för <namn>") på senast öppnade-kortet och på varje
   projektkort i `ProjectsScreen.kt`. Den öppnar samma meny som långtryck; långtryck finns kvar.
2. Knappen konsumerar trycket (öppnar inte projektet).
3. Menyn får tydliga rader: **Säkerhetskopiera (med media)**, **Exportera bara projektfilen**, **Versionshistorik**.
4. En kort rad i menyn/versionsdialogen: *Versionshistoriken ligger i appen och försvinner om appen avinstalleras
   eller appdata rensas. Säkerhetskopiera för att skydda projektet.*

**Tester:** Compose-test för ⋮ (öppnar meny, öppnar inte projekt), menyraderna, långtryck kvar, `contentDescription`.

---

## PR 3 – Säker manuell säkerhetskopiering och återställning

**P1 · medel–stor**

**[Nytt] Först, eget litet fel:** exportera projekt får aldrig skriva över eller radera en befintlig fil vid fel.
Skriv alltid till ett **nytt** dokument; radera bara det dokument som just skapats av den misslyckade körningen.

1. Välj säkerhetskopieringsmapp via `OpenDocumentTree`; behåll behörigheten med `takePersistableUriPermission`.
2. **Säkerhetskopiera nu** (med media) skriver till ett nytt dokument med tidsstämpel i namnet
   (`<projekt>_<åååå-mm-dd_hhmm>.klipp`). **Inga namnbyten eller atomiska operationer antas**; den förra kopian lämnas
   orörd. Gamla kopior rensas aldrig automatiskt i första versionen.
3. Verifiering efter skrivning: läs tillbaka paketet genom SAF och kontrollera manifestet, att alla filer i manifestet
   finns och att storlekarna stämmer (`ProjectPackage.verify`). Först då sparas "senast lyckade kopia" (tid, dokument-
   URI, projektets `modified`) i projektets sidfil.
4. Misslyckas eller avbryts kopieringen: radera det nya dokumentet om leverantören tillåter det; annars döps det
   till `.ofullständig` om möjligt och nämns i felmeddelandet.
5. Saknade mediefiler: kopian skrivs men märks **ofullständig** i manifestet (`missing: [...]`) och i UI.
6. Projektkortet visar: *Säkerhetskopierat 11 nov 13:20* / *Ändrat sedan senaste kopian* / *Aldrig
   säkerhetskopierat*.
7. Indragen mappbehörighet → begripligt fel + knapp "Välj mapp igen".
8. Återställning = befintlig import (alltid nytt projekt), liksom versionshistorikens återställning.

**Tester:** paketrundtur (finns) + skadat paket, trunkerat paket, saknade media (`missing` i manifest), skrivfel mitt i,
verifiering som upptäcker fel storlek, import av äldre `format`. På telefon: SAF-flödet mot intern lagring och mot ett
SD-kort/USB-minne om det finns.

---

## PR 4 – Regressionstester för förhandsvisning mot export

**P1 · stor, uppdelningsbar (4a, 4b, 4c)**

**[Ändrat] Uppdelning efter vad som går att köra var:**

- **4a – på datorn (Robolectric):** kompositionens struktur och tider (`CompositionBuilder`, `LayerSyncTest`,
  `EngineContractTest` utbyggda): lagrens start/slut, övergångars tidsfönster, overlays (titel/undertext) vid
  klippgräns, ljudprocessorer per objekt, effektiv samplingsfrekvens och kanaler. Billigt och fångar timingfel.
- **4b – på telefon (instrumenterat, `androidTest` + `GlHarness`):** genererade testprojekt (ffmpeg-klipp med
  bildrutenummer och färgfält, ljud med pulser) exporteras; exporten avkodas och jämförs bild för bild mot en
  förhandsvisningsbild från samma tid. Fall: ett klipp, två överlappande lager, tre lager med olika start, övergång,
  titel + undertext vid gräns, animerad position/opacitet, mask, färgjustering, ljudsynk.
- **4c – kontroller av filen:** längd, faktisk bildfrekvens och pts-luckor (`ffprobe`-motsvarighet via
  `MediaExtractor`), ljudets kanaler/frekvens och synkpulsens förskjutning (tolerans 1 bildruta).

Toleranser dokumenteras per fall (t.ex. medel-ΔE och max andel avvikande pixlar efter nedskalning). Vid fel sparas
referens, export och differensbild. Ingen renderingsomskrivning i PR 4; varje hittad skillnad blir en egen PR med
regressionstest.

---

## PR 5 – Enklare projektstart och exportval

**P2 · medel**

- **Nytt projekt:** namn + format (**Som första klippet**, **Stående**, **Liggande**) + Skapa. Bildfrekvens,
  samplingsfrekvens, kanaler, egna mått och exportinställningar under **Avancerat**. Allt som finns i dag behålls.
- **Export:** förval **Bäst kompatibilitet** (H.264 8-bit, SDR, standardkvalitet), **Mindre fil** (HEVC om kodaren
  finns, annars lägre bithastighet – storleksuppskattningen visas och ska vara mindre), **Hög kvalitet**,
  **Anpassat**. **[Ändrat]** Förvalen bygger på befintliga `ExportPresets`-nivåer och går genom `ExportPlan.resolve`
  från PR 1. Ändras en enskild inställning visas Anpassat.

**Acceptans:** standardprojekt utan tekniska val; inget tas bort; byte enkel/avancerad tappar inget; förval ändrar
aldrig redigeringen.

---

## PR 6 – Upptäckbara gester och tydlig radering

**P2 · medel**

1. Inventera `Tips.kt` (knife, out, edge, scrub, undo …) och den nya designens dock innan något nytt byggs. Dela,
   Radera och Ångra/Gör om finns redan som knappar i dockan och ångrapillen – verifiera att all grundredigering går
   utan gester (också i 360 dp).
2. Dra-ut-radering har redan röd ton när den är "laddad" (`doom` i `Timeline.kt`); komplettera med en tydlig etikett
   ("Släpp för att radera") och haptik vid gränsen.
3. **[Nytt]** "Visa tips igen" i Inställningar (rensar `tip_*`), och en gestöversikt under Inställningar → Hjälp.

**Acceptans:** grundredigering utan flerfingergester; tips blockerar inte tidslinjen och kommer inte tillbaka efter
avfärdning (förrän de återställs); radering går att ångra; trimprecisionen oförändrad.

---

## PR 7 – Livscykel och verkliga enheter

**P2 · medel–stor**

1. **[Nytt, förs fram]** Export i en **foreground service** med avisering och förlopp, så att Android inte dödar en
   export när appen lämnas. Om processen ändå dör: spara "export pågick" i preferenser och visa vid nästa start
   *Exporten av <namn> avbröts* (inget falskt "Sparat"). Automatisk återupptagning ingår inte.
2. Test: projektet återkommer efter processdöd (`adb shell am kill` i bakgrund) med senaste autospar.
3. Låg lagring (fyll cache), indragen medieåtkomst, skadad mediefil, variabel bildfrekvens, blandad SDR/HDR.
4. Manuell testmatris: vivo X300 Ultra, OnePlus 12, och när det finns: telefon med lite RAM, Mali-GPU, Android 10/11.

---

## PR 8 – Verifiera och stabilisera Media3: förhandsvisning, lager och export

**P1 · stor – delas upp i mindre PR:er (8.1–8.6 nedan kan var och en bli en PR)**

### Bakgrund

README:n beskriver tre renderingsvägar: förhandsvisningens huvudspelare (bottenlagret) med `UpperLayers` (egna,
ljudlösa ExoPlayer i TextureView för övre lager), `LivePreview` (stillbild medan ett reglage dras) och exporten
(Transformer med `LayerCompositor` och `TimedOverlay`). Det är en **risk** för skillnader i timing, komposition och
färg, inte ett bekräftat fel.

### Kontrollerat mot koden (2026-11-11) **[Nytt]**

- **Fler vägar än tre.** Förhandsvisningens huvudspelare är en `CompositionPlayer`. Utöver de tre ovan finns:
  - **klippskarvens lager** (`Handoff`, `UpperLayers.kt:599`), som ritar sekunden efter en skarv;
  - övertoningarnas andra halva i ett eget lager;
  - **förrenderade bitar** (`RenderCache`, grön linjal);
  - **nodcache** (`NodeCache`);
  - **proxyfiler** (`Proxies`).

  Alla ska med i kartläggningen och i testmatrisen. En övergång kan till exempel visas live, via skarvlagret eller
  förrenderad.
- **Diagnostik finns redan.** Långtryck på "Program" (`RealMode.kt:265`) visar uppspelningsväg, lager och tider
  (`playback.diagnostics()`, `Proxies.describe()`), och `SyncStats` (`UpperLayers.kt:659`) mäter drift per lager.
  Punkt 6 bygger ut den vyn. Den visas inte på färgsidan i den nya designen.
- **Media3-ändringarna är dokumenterade** i `third_party/media3-effect/KLIPP.md`: RGBA16F-texturer, 10-bit-ytor i
  flerlagersexport, räknare för visade och sena bildrutor, blandningslägen och låg GPU-prioritet. Punkt 4 lägger
  till en lista över vilka regressionstester som ska köras före en uppgradering.
- **Tidigare mätningar som styr metoden:**
  - `CompositionPlayer.currentPosition` står still 30–45 ms åt gången under uppspelning.
  - Avkodarens räknare säger inget när effekter används. Synk mäts i stället på bildrutor som når lagrets
    TextureView (`surfaceTexture.timestamp`).
  - Skärminspelning fungerar på vivon men hänger sig på OnePlus 12.
- **Kända öppna avvikelser att reproducera först:**
  - Ibland ett svart blink strax före en skarv (ungefär 1 körning av 4, oförklarat).
  - Stabiliserade klipp såg fel ut i skarvlagret (det är kringgånget i dag, orsaken okänd), och samma fel kan finnas
    för andra effekter per bildruta: följda fönster och animerade masker.
  - Spelaren behåller fel för gott (kringgånget genom att spelaren byts ut).
  - Spelaren kunde stå still när uppspelningen startade några bildrutor före en gräns (rättat i 0.18.52; behöver
    ett regressionstest).
  - Tre filmkedjor samtidigt överbelastar GPU:n.

### Avgränsning

- Verifiera mot aktuell kod och Media3 1.11.1 först. Ingen generell Media3-uppgradering eller större
  renderingsomskrivning utan dokumenterat behov.
- Bevara Klipps modifierade effektkedja (`third_party/media3-effect`) och flyttalspipelinen.
- Avsiktligt sänkt proxykvalitet är inte ett renderingsfel.
- Testprojekt och GPU-harness kommer från PR 4. PR 8 handlar om Media3-integrationen, körning på telefon och
  rättningar.

### 8.1 Kartlägg renderingsvägarna → `docs/media3-rendering.md`

Gå igenom `UpperLayers.kt`, `LivePreview.kt` och berörda filer i `engine/` (Playback/CompositionBuilder,
`LayerCompositor`, `TimedOverlay`, `Layers.kt`, `RenderCache`, `NodeCache`, `Proxies`). Dokumentera per väg:

- tidskällan;
- hur projekttid mappas till källtid efter trimning och hastighet;
- hur seek, play, pause och ändringar i tidslinjen når de övre lagren;
- hur inaktiva lager döljs och gamla bildrutor hindras från att ligga kvar;
- hur effekter, geometri och färg hanteras;
- när spelare, avkodare, ytor och GPU-resurser skapas och frigörs;
- vilka skillnader som är avsiktliga.

Leverans: en kort beskrivning och en lista över verifierade risker med kodhänvisningar.

### 8.2 Tidsmappning och lagersynk

Använd syntetiska klipp med bildnummer, tidskod och färgväxlingar, och ljud med pulser.

| Scenario | Kontroll |
|---|---|
| Två och tre överlappande lager | Rätt bildruta och lagerordning |
| Trimmad början | Rätt mappning projekttid → källtid |
| Start och slut för övre lager | Ingen för tidig visning, ingen kvarhängande bildruta |
| Upprepad play/pause | Ingen ackumulerad drift |
| Seek framåt och bakåt | Alla aktiva lager når rätt position |
| Snabb scrubbning över gränser | Gamla seek-resultat skriver inte över nyare |
| Blandade bildfrekvenser | Dokumenterat, konsekvent bildruteval |
| Lång uppspelning (5 min) | Ingen växande drift mellan lager och ljud |
| **[Nytt]** Övergång live, via skarvlagret och förrenderad | Samma bild vid samma tid |
| **[Nytt]** Start 1–4 bildrutor före en gräns | Uppspelningen kommer igång (regression från 0.18.52) |

Krav:

- En gemensam, testbar tidsmappning där det går.
- Skilj på begärd position, rapporterad position och faktiskt visad bildruta. Visad bildruta mäts med
  TextureView-tidsstämplar eller bildnummer i bilden, inte med `currentPosition`.
- Inga kontinuerliga korrigerande seeks.
- Ett definierat beteende när ett övre lager buffrar.
- Inaktuella asynkrona resultat får inte skriva över ett senare läge.

### 8.3 Förhandsvisning mot export

Bygger på PR 4:s projekt. Kontrollera:

- lagerordning, opacitet och blandningslägen;
- placering, skalning, beskärning och rotation;
- övergångar;
- titlar och undertexter;
- keyframes;
- masker och färg;
- växlingen mellan `LivePreview` och uppspelning.

Ta bilder före, vid och efter gränser och mitt i animationer.

Mätkrav:

- Bildfångsten måste ta med TextureViews, övre lager och overlays. En Compose-skärmbild räcker inte.
  **[Nytt]** Förslag: skärminspelning eller `SurfaceControl`-fångst på vivon, och läsning av lagrens egna ytor via
  appens agentbrygga (avstängd som standard). Metoden och dess färgkonverteringar dokumenteras.
- Jämför mot avkodade exportbilder vid samma presentationstid.
- Rapportera timingfel, geometriska fel och färgfel var för sig, och spara referens-, faktisk och differensbild vid
  fel.

### 8.4 Färgkedjan och den modifierade Media3-koden

Testa:

- SDR Rec.709 8-bit;
- 10-bit;
- HDR → SDR;
- HDR-export där det stöds;
- log med vald inputtransform (vivo Log, Apple Log);
- färgjustering med flera lager;
- proxy mot original.

Leta efter:

- dubbla transformer eller dubbel tonmappning;
- klippning eller kvantisering i flyttalskedjan;
- skillnader mellan `LivePreview`, uppspelning och export;
- utfilens codec, bitdjup och färgmetadata (läses med `MediaExtractor`/`MediaFormat`).

SDR-skärmbilder räcker inte för HDR: använd `GlHarness` och numeriska referenser, och skriv ner vad som kräver HDR-skärm.
Lägg i `KLIPP.md` en lista över vilka tester som ska köras före en Media3-uppgradering.

### 8.5 Resurser och fel

Upprepa: öppna och stäng projekt, 1 ↔ 3 aktiva lager, play/pause/seek, bakgrund och tillbaka, återskapad yta eller
aktivitet, starta och avbryt export. Kontrollera:

- att spelare, avkodare, ytor och GPU-resurser frigörs;
- att inget ljud fortsätter från en gammal spelare;
- att minnet inte växer stadigt efter uppvärmning;
- att avkodarfel ger en tydlig återkoppling;
- att ett fel aldrig ändrar redigeringen.

Mät hur förhandsvisning och export samsas om hårdvaruavkodarna, och inför en tydlig policy om telefonen inte klarar
båda. **[Nytt]** Ett förslag att mäta mot: pausa och frigör förhandsvisningens spelare medan exporten pågår, som en
foreground-export i PR 7 ändå kräver.

### 8.6 Lokal diagnostik

Bygg ut den befintliga vyn (långtryck på "Program") med:

- projekttid och läge;
- aktiva lager med begärd källtid, rapporterad position och buffring;
- visade och sena bildrutor (`KlippPrecision.framesShown`/`framesLate`);
- codec och renderingsväg (live, skarvlager eller förrenderad);
- proxy eller original, och färginformation.

Diagnostiken är lokal och avstängd som standard. Den loggar inte transkript, medieinnehåll eller fullständiga
sökvägar. **[Nytt]** Kontrollera också att `CrashReport`-spåret följer samma regel.

### Acceptanskriterier

- [ ] Renderingsvägar (inklusive skarvlager, förrenderat, nodcache och proxy), tidsmappning och resursägarskap är
      dokumenterade.
- [ ] Automatiska tester täcker tidsmappning och lageraktivering vid klippgränser.
- [ ] Förhandsvisning och export är jämförda på telefon för minst två- och trelagersprojekt.
- [ ] Efter avslutad seek och stabiliserad rendering ligger bildvalet inom en projektbildruta i de deterministiska
      fallen.
- [ ] Ingen växande drift under fem minuter på deklarerad testkonfiguration.
- [ ] Inga gamla bildrutor eller seek-resultat efter att ett nytt läge har stabiliserats.
- [ ] Geometri, overlays och färg jämförs med dokumenterade, separata toleranser.
- [ ] Ingen stadig resursökning vid upprepade projekt- och livscykelbyten.
- [ ] Varje rättat fel har ett regressionstest där det är praktiskt.
- [ ] Begränsningar på svagare telefoner redovisas öppet. Tester som inte körts markeras inte som godkända.

### Leverans

1. Kartläggning och testplan.
2. Reproducerbara testprojekt.
3. Tester och avgränsade rättningar.
4. Resultat per telefon, Android-version och Media3-version.
5. Kvarvarande avvikelser med reproduktionssteg.
6. Arkitekturrekommendation **bara om mätningarna motiverar den**.

---

## Leverans per PR

1. Vad som ändrats och varför. 2. Uppfyllda acceptanskriterier. 3. Körda tester med resultat. 4. Vad som kräver
telefon. 5. Kända begränsningar och risker.

## Ordning

Kartläggningen är gjord (ovan). **[Ändrat]** Börja med **PR 3:s första punkt** (exportera projekt kan förstöra en
befintlig fil) som en liten separat rättning, sedan **PR 1** och **PR 2**, därefter resten av PR 3, PR 7:s
foreground-export, **PR 4 och PR 8 ihop** (8.1 kartläggningen kan göras direkt; 8.2–8.4 använder PR 4:s testprojekt
och harness; 8.5 samordnas med foreground-exporten), och sist PR 5–6.

---

## Status 2026-11-11 (genomfört på `design-test`)

| Del | Status | Tester |
|---|---|---|
| Säker projektexport (PR 3 punkt 1) | Klar: befintlig fil ersätts bara av ett färdigt, kontrollerat paket; varje paket läses tillbaka (`ProjectPackage.verify`, zip-slutpost, storlekar). | `ProjectPackageTest` |
| PR 1 Exportplan | Klar: `ExportPlan.resolve` delas av dialog och export; Ja/Nej/Vet ej; spärrad knapp; "Använd HEVC"; sammanfattningen visar det som görs. | `ExportPlanTest`, `ExportDialogTest` |
| PR 2 Projektåtgärder | Klar: ⋮ på senast öppnat-kortet, egna menyrader, text om att versioner inte är säkerhetskopior. Hittat och rättat: sidan öppnades förbi det stora kortet. | `ProjectsTest` |
| PR 3 Säkerhetskopior | Klar: mapp via SAF, ny fil per kopia, verifiering, status på kortet, tappad mapp, ofullständigt paket vid import. | `BackupsTest`, `ProjectsTest`, `ProjectPackageTest`; på vivo X300 Ultra: kopia 1,3 GB, återställd identisk tidslinje, borttagen mapp ger rätt fel |
| PR 7 Export i förgrunden | Klar: foreground service (mediaProcessing/dataSync), avisering, avbruten export visas i dialog vid nästa start, temporärfiler rensas. | `ExportGuardTest`; på vivo: 4K 10-bit HEVC klar i bakgrunden, force-stop → dialog, ingen fil |
| PR 4a / 8.2 (dator) | Klar: gemensam `Seg.sourceAt`, bildruta för bildruta på trelagersprojekt. Hittat och rättat: Float-spelhuvudet gjorde att ett övre lager började en bildruta för sent i förhandsvisningen. | `PreviewExportTimingTest` (fångar avsiktligt fel) |
| PR 8.1 Kartläggning | Klar: `docs/media3-rendering.md` (13 verifierade risker). | – |
| PR 5 Projektstart och förval | Klar. | `ProjectsTest`, `ExportPlanTest` |
| PR 6 Gester | Klar: Hjälp i Inställningar med knappalternativ, "Visa tipsen igen". Raderingsgesten hade redan skylt och haptik. | `SettingsHelpTest` |
| PR 4b/4c, 8.3–8.6 på telefon | **Inte gjort**: bildjämförelse förhandsvisning–export på telefon, färgkedjan, resurscykler, utbyggd diagnostik. | – |
| Look på hela bilden (beslut 2026-11-11) | Klar: förhandsvisningen lägger tidslinjens Look på hela den staplade bilden (Android 13+), titlar utanför, som exporten. | På vivo: mitt i en övergång samma medelljushet som exporten (17,0 / 17,0) |
| PR 8.2/8.3 bildruta för bildruta (kodade klipp) | Klar: fyra fel rättade (pausad bild vid övre klippstart, övre lagrets första och sista bildruta, exportens upprepning var femte bildruta); export 6000/6000 rätt; ingen drift på 230 s. Kvar: en bildruta hålls och en hoppas över vid snitt i huvudlagret under uppspelning. | `PreviewExportTimingTest`, `tools/framecode`; på vivo |
| PR 8.7 Ojämna snitt (2026-11-12) | Klar: orsaken dokumenterad med logg + bildnummer (huvudspelaren står ~130 ms vid objektbytet; skarvlagret blev synligt först efter). Skarvlagret visas nu från sin egen bildruta. 20+20 omgångar utan och med film: inga hoppade bildrutor. Övergångar: whip-strecket rättat, zoom/rotera/vertikal whip/toningar/ljusläcka fryser inte längre vid snittet. | `LayerSyncTest`, `TransitionsTest`, `HandoffTest`; på vivo, se `docs/media3-rendering.md` |
| Segmentklockan, risk 6 (2026-11-12) | Klar: lagerspelarens bildrutetider berodde på vad som spelats innan (Media3 nollställer inte renderarens start vid sökning till annat objekt); nu omstart från känd punkt per objektbyte. Stabiliserat klipp i skarvlagret och som andra segment på V2: ≤2 px (före ±28 px). Undantaget för stabiliserade klipp borttaget; rörelseoskärpa kvar bakom diagnosflagga. | på vivo (skakande kodat klipp), regression snitt + zoom |
| Resurssamordning, risk 9 (2026-11-12) | Klar: export pausar/avbryter proxy, nodcache och förrendering och släpper förhandsvisningens avkodare; allt återupptas efteråt. | `BackgroundWorkTest`; på vivo: export mitt i förrendering, alla bildrutor rätt |
| Spela/pausa (2026-11-12) | Klar: bilden efter paus låg 1–3 bildrutor före spelhuvudet (1/15 rätt); nu söks pausat till spelhuvudets bildruta (15/15, 6/6 med V2). Även snitt förrenderat → live: 10/10 rena. | på vivo |
| Bildfrekvenser 25/30/29,97 (2026-11-12) | Klar: lagerspelarnas bildrutor kom upp till en bildruta för tidigt (TextureView); nu högst 35 ms före tiden. 30 fps: upprepad bildruta vid avlösning 8/10 → 0/10; 25 fps: 10/10 rena. | på vivo |
| 10-bit mot 8-bitsreferens (2026-11-12) | Klar: 80 nivåer i 10-bit, 21–25 i 8-bit; med kontrast 124 mot 42. | på vivo, gradientklipp |
| Look rumsligt/per kanal (2026-11-12) | Klar: kraftigt olinjär Look lika i förhandsvisning och export (2–3,5 per kanal lågpassat), även staplat. | på vivo |
| HDR i övre lager, risk 10 (2026-11-12) | Rättat: V2 dog vid byte HDR-original → SDR-proxy (Media3 tar utfärg från första filen); lagerkedjan ger nu alltid SDR (OpenGL-tonmappning som exporten). Kvar: djupaste skuggor ljusare i exporten. Nya fynd: hack i förrenderad fil vid bitgräns, två förrenderingsarbetare, slut på avkodare i lång session. | på vivo, syntetiskt HLG |
| Förrenderad uppspelning (2026-11-12) | Rättat: hack och bildrutor i fel ordning vid bitgränser kom från två samtidiga sammanfogningar av samma fil; nu en i taget. 8/8 jämna. | på vivo |
| Övre lager i takt med huvudbilden (2026-11-12) | Rättat: V2 låg 0–2 bildrutor efter V1 (samma bildruta 42 % med Medias standard); egen gräns för övre lager (65 ms) → 84 %. Skarvlagret oförändrat (35 ms). | på vivo, V1/V2 ur samma skärmbild |
| V2:s första bildruta vid start (2026-11-12) | Rättat: V2 hoppade över bildrutan efter den pausade i 4/6 starter – spelaren lämnade ut två bildrutor inom 3 ms (tröskeln 65 ms är längre än en bildruta) och TextureView visar bara den sista; `EarlyRamp` håller dem en bildrutetid isär efter start. 0/8 hopp, stadigt läge oförändrat. | `EarlyRampTest`; på vivo (inspelning) och OnePlus (spårlogg, 10/10), bildkodade klipp V1/V2 |
| Nytt tomt projekt (2026-11-12) | Rättat: ett nytt projekt utan sekvens visade förra projektets textremsor, markörer och övergångar (`refresh` uppdaterade dem bara när en sekvens fanns); nu töms de. | på OnePlus |
| Whip mellan hela APV-klipp (2026-11-12) | Rättat: övergångens frysbilder (första/sista bildrutan där filen tar slut) togs med MediaMetadataRetriever, som inte kan avkoda APV – de saknades, den inkommande bilden syntes spegelvänd under hela övergången, och varje misslyckat försök byggde om uppspelningen (~70 gånger/s tills Android dödade appen, "too many binders"). Nu tas bilden från proxyfilen när originalet inte går, och ett misslyckande ger ingen ombyggnad. Kvar (separat, äldre): start mindre än ~0,5 s före ett snitt mellan dessa 4K 120 fps APV-klipp står still ("buffering without loading"), även utan övergång. | på OnePlus, användarens APV-klipp |
| Övergångar bara förrenderade (2026-11-12) | Ombyggt: förhandsvisningen sätter aldrig ihop en övergång mellan två klipp live (lagren drev isär med tunga filer: dubbel och spegelvänd). Övergångar är rena snitt tills deras förrenderade fil (exportens egen bild) är klar; alla renderas automatiskt när uppspelningen står still, närmast spelhuvudet först (~2 s per whip på OnePlus). Skarvlagret ritar inga övergångar och spelar inga tunga original utan proxy. Kvar: APV-original 1,9 Gbit/s utan proxy i normal hastighet tar slut på minnet på OnePlus (mjukvaruavkodning). | `PlayPlanTest`, `HandoffTest`; OnePlus, användarens APV-klipp |
| Frysbild vid automatisk förrendering (2026-11-13) | Rättat: appen kraschade med "could not save the freeze frame" – bakgrundsuttaget och förrenderingens väntan tog samma frysbild samtidigt till en gemensam temporär fil. Egen temporär fil per uttag, en bild i taget, och ett fel i en förrendering fäller bara den. | vivo: 12 snabba längdändringar av en whip mellan hela klipp + nytt helt APV-klipp med whip, ingen krasch |
| Start strax före ett snitt med tunga filer (2026-11-13) | Rättat: med 4K-APV i 25 % (proxy) stod uppspelningen still ~1 s när den startade 0,20–0,22 s före ett snitt (5/5 på vivo; OnePlus upp till ~0,5 s). Spärren gäller nu 0,5 s före en kant och startar 0,6 s före (stegar tillbaka över korta bitar i rad); stoppvakten ingriper efter 0,3 s i stället för 1–1,5 s. 10/10 starter utan stopp, 33–36 fps. | `StartEdgeTest`; vivo, användarens APV-projekt |
| Start direkt efter ombyggnad, APV-original (2026-11-13) | Delvis: med proxy av och 4K/120-APV i 25 % stod spelaren still för gott (READY, "spelar", en bildruta ut, omladdning var 3:e s gav samma) när Play trycktes inom ~0,1 s efter en ombyggnad (3/3 vid 6,2 s). Play väntar nu på första bildrutan efter en ombyggnad (högst 1,5 s): 6/6. Kvar: start vid 5,0 s i samma klipp fastnar ändå ofta (inte ljudet – samma med A1 av; inte avkodarbrist – bara APV + AAC öppna; en sökning en bildruta fram hjälper inte). Med proxy på: inga stopp. | vivo, användarens APV-projekt, proxy av |
| Hack efter snitt i slow motion (2026-11-13) | Rättat för snitt: klippbyteslagret visades först när dess vy höll en bildruta som började på snittet – med bildrutor utanför huvudspelarens raster (förrenderad bit i 25 fps, sedan 4K 120 fps i 25 % = 30 fps) kom den upp till en bildruta efter snittet, och huvudspelarens sista bildruta stod 80–97 ms. Nu visas lagret från den bildruta som täcker snittet (`EditPointFrame`): 0 stopp i 4 av 5, 68 ms i den femte. Efter en förrenderad whip kvar 67–85 ms: lagrets första bildruta ligger bara 5 ms innehåll efter filens sista (25 → 30 fps), en inbyggd skarv; exporten (25 fps rakt igenom) har den inte. | `EditPointFrameTest`; vivo, inspelning, användarens APV-projekt |
| Förhandsvisningen i tidslinjens bildtakt (2026-11-13) | Rättat: förhandsvisningen visade varje avkodad bildruta (4K 120 fps i 25 % = 30 bilder/s i ett 25p-projekt), exporten en per 25-ruta. Nu samma regel i alla tre (`FrameGrid`): huvudspelaren (`FrameRateEffect` med objektens starter – kedjan löper över objekt, exportens byggs om per objekt) och lagren (`LayerGridEffect`: väljer först med oförändrade tider så att gradering, stabilisering, masker och nyckelrutor läser bildens egen källtid, flyttar till rutan sist). Spårat på vivon: huvudspelarens bilder 0 utanför rastret; lagrets på rutor från snittet. Efter en förrenderad whip: skarven 67 ms (en skärmuppdatering mer än normalt), 3 av 5 utan stopp över 70 ms. | `FrameGridTest` (exportens bildföljd: hastighet 1,0/0,25, start mellan rutor, trimmad in-punkt, ojämna telefontider, sökning, nytt segment), `GridEffectFramesTest` (Media3:s kontrakt för bortvalda bildrutor) |
| Rastret: lagret synligt vid snittet, fasregel (2026-11-13, testversion 72–73) | Rättat: förrullningens sista bildruta (oflyttad) och snittets rasterbildruta (flyttad dit) släpptes för samma skärmuppdatering, och lagret läste den första som vyns bild – klippbyteslagret syntes en bildruta sent och huvudspelarens filbyte (130 ms, två rutor överhoppade) syntes efter användarens whip. Förrullning nu minst en halv ruta före segmentets första; uppslaget tar den senast släppta. Lagret hoppar över rastret bara när klippets bilder redan ligger på det (`FrameGrid.onGrid`: hastighet 1, samma takt, start och in-punkt i fas). Kvar, inte åtgärdat (ingen ny generell timingändring före visuell prövning): whippens början (huvudspelaren byter till den förrenderade filen, ingen täckning, ~85 ms i 3 av 5); efter whippens slut står den första lagerbilden ibland en skärmuppdatering för länge (65–82 ms mot 50) fast släpptiderna är jämna. | `FrameGridTest`; vivo: spårning (release-tider), inspelning; V2 med stabilisering, spårad mask, keyframad position, sökning och segmentbyte: klockan läste bara behållna bilders egna tider (281/281, 83/83, 43/43), 0 bilder utanför rastret |
| Rastret och klippslut i uppspelning (2026-11-13, testversion 74–77) | Rättat: Media3:s videosink avslutar ett klipp först när en bild med tid ≥ klippets sista avkodade bild kommer ut ur effekterna (PlaybackVideoGraphWrapper, finalFramePresentationTimeUs) och väntar på det innan avkodaren byts (proxyns HEVC → förrenderad AVC). Rastret kunde välja bort eller flytta tidigare just den sista bilden: spelaren stod still 0,7–1,1 s före en whip (och ett lager kunde fastna i buffring). Ett klipps sista bilder (inom 1,5 källbilder från slutet) väljs nu aldrig bort och flyttas aldrig tidigare, inte heller förbi nästa klipps start (`FrameGrid.nearEnd/lastFrameAt`); en håll-en-bild-lösning fungerar inte i uppspelning – effektkedjan får aldrig klippslutsignalen. Även: ett övergångsfönster blir en egen förrenderad bit också när övergången visar en bild i taget (whip in i ett 98 s klipp väntade på hela klippet), och klippbyteslagret täcker inte förrenderade bitar (det väntade på ett klipps bilder där och täckte hela biten). Kvar: ~82 ms vid whippens början och strax efter landningen i 3 av 5. | `FrameGridTest`, `HandoffTest`, `RenderCacheTest`, `TransitionsTest`; vivo, inspelning |
| Övergångar i tidslinjens bildtakt (2026-11-13, testversion 80) | Rättat: övergången ritas per bild, och 120 fps i 25 % ger 30 bilder/s – i en 60 fps-tidslinje gick en push/whip i 30. Export och förrendering ger nu övergångens bit en bild per ruta inom fönstret (`KlippFrameFill`, upprepar källbilden före). Mätt på vivon (60 fps-projekt, push 6,23–6,83 s): bitarna 20 resp. 16 bilder (60 fps); inspelning: 55–65 bildbyten/s i övergången, intervall 15–18 ms utan stopp, 30/s i klippen runt (källans takt). | `FrameGridTest`, `GridEffectFramesTest`; vivo, inspelning |
| Förrenderade bitars gränser (2026-11-13, testversion 82–88) | Rättat, verifierat med bildnummerkodade källor (render_diag, avkodade filer): (1) en bit som var renderingens sista tappade sin sista bild om den skickades precis före indatats slut (spårad till kodaren, inte i filen) – bitar renderas nu en ruta förbi slutet och trimmas utan omkodning; (2) en bit som började mellan två rutor fick eget raster (muxern lägger filens första bild på 0) och en bild för mycket på nästa bits start – bitkanter avrundas uppåt till rutor, `FrameRateEffect` räknar rutor i tidslinjens tid (`originUs`), och en bits första bild fästs på dess första ruta (`StartPinPlanner`); (3) ett övergångsfönster är en bit (en skarv vid snittet gav en nyckelbild där och en skärmuppdatering för länge). Resultat: 25 och 60 fps – varje ruta i exakt en bit, sammanfogning rätt längd och följd, övergångsbiten lika exporten bild för bild utom fönstrets första ruta när källan saknar bild där (visar nästa källbild). Kvar: källbildsvalet räknas från strömmens första bild – en bit som börjar mitt i ett klipp väljer ibland en källbild senare än exporten (30 fps-källa i 25p: 3 av 5 rutor); föreslagen rättning: välj per ruta källbilden som täcker rutans källtid (oberoende av var renderingen börjar). Exporten fyller inte rutor utanför övergångar: 30 fps-innehåll i 60p ger en fil med ojämn bildtakt (hållna bilder). | `FrameGridTest`, `RenderCacheTest`; vivo |
| Klippslut och rastret (2026-11-13, testversion 83) | Rättat: Media3 jämför i kompositionens tidsbas (µs) utgående bildtid mot klippets sista avkodade bildtid; ett klipps sista bilder (inom 1,5 källbilder) visades på egen tid utanför rastret (APV-proxy 119,94 fps: 6,197 och 6,230 s, den senare 3 ms före en förrenderad whips första bild). Nu: egen ruta om den inte är tidigare än bilden, annars nästa; ryms ingen ruta före nästa klipps start blir den en ren slutbild 1 µs före nästa klipps start (ersätts direkt, aldrig nästa klipps första ruta). Ingen återkomst av 0,7–1,1 s-stoppen (10 körningar över HEVC→AVC-bytet). | `FrameGridTest`; vivo, spårning |
| A/B: filöverlämning vid whip (2026-11-13) | Mätt (5+5 körningar per variant, inspelning + spårning, bildidentitet via kodade bilder eller pixelmatchning mot den sammanhängande filen B): med användarens APV-material hackade A (levande → förrenderad → levande) vid snittet (+16–19 ms, varje körning) och vid fönstrets start; B (en sammanhängande fil) var jämn (+0–9). Efter rättningarna: snittet i nivå med B (+1–8). Kvar: fönstrets slut (förrenderad → levande) 0–32 ms i 3 av 5 (B 0–9); inspelningsmåttet är opålitligt utanför fönstret för 25 %-material (för små skillnader mellan bilder) – spårningens släpptider är jämna där. |
| Källbildsval oberoende av renderingsintervall (fel 6, 2026-11-13, testversion 89–90) | Rättat: regeln räknade från strömmens första bild, så en förrenderad bit som började mitt i ett klipp valde en annan källbild på sina första rutor än exporten. Nu en regel (`SourceSampler`) för export, förrendering, huvudspelare och lager: källtid → tidslinjetid τ via klippets placering, in-punkt och hastighet; en bild hör till första rutan vid eller efter τ (1 ms läsförsprång), rutan visar den första bild som hör till den, en ruta utan egen bild håller bilden före. Beslutet beror bara på bildens och föregående bilds τ (ingen gissad bildtid; verifierat för de testade VFR-fallen: 119,94 fps med jitter, 25/30-blandning, långt mellanrum i enhetstest). Delar läses från före sin start (förrullning 2,5 källbilder + en ruta, högst 1 s); bilder före delens start behålls osynliga (`KlippFrameFill.UNSEEN`) så att delens första rutor håller samma bild som helheten. Verifierat på vivo med bildnummerkodade källor (hel rendering, export från original, delar från 6–7 punkter, 5–6 bitar i följd, cachens bit): 30 fps på 25p (S25) och 120 fps i 25 % på 60p med whip (S60w) identiska per ruta i alla filer; användarens 119,94 fps APV (kopia, bildjämförelse) likaså. Uppspelning över whippen: inga stopp (största intervall 41–46 ms). Begränsningar: se raderna nedan om seek, förrullning och VFR; enhetstesternas modell täcker inte kompositören (ett fel i testversion 89 – det övre lagrets efterspel tappades, 0,2 s – hittades först på telefonen). | på vivo |
| A/B övergångens slut, bygge 90 (2026-11-13) | Mätt om efter rättningen av källbildsvalet (ny B-referens renderad med bygge 90; 5+5 körningar, användarens 119,94 fps APV-material i kopian, 2,6 s från 5,2333 över whippen 6,2333–6,8333). Överskott = observerad hålltid − förväntad enligt referensens bildföljd (avsiktligt upprepade källbilder räknas bort). Spårning (huvudkedjans och klippbyteslagrets presentationstider): A och B lika, högst +8 ms per zon (skärmens uppdateringsfrekvens under mätningen registrerades inte – ingen jämförelse med en uppdatering). Inspelning: snittet in och övergångens slut A ≤ +8 ms (som B); mitt i whippen A +16 ms vid 6,4167 i 2 av 5 (syns inte i spårningen), efter whippen enstaka +15–24 ms följda av lika stort minus (en enstaka sen presentation, ingen ackumulerad hålltid; bildidentifieringen i slow motion är osäker, MAD 4,5). Det tidigare stoppet (+16–19 ms varje körning vid snittet, 0–32 ms vid slutet) återkommer inte. Bedömning: A = B inom mätosäkerheten; utredningen avslutad, ingen överlämningsrättning. | på vivo |
| Pausad seek visar nästa rutas bild (2026-11-13, rättat för huvudspelaren, testversion 93) | Var: pausad förhandsvisning visade vid ruta k exportens bild för ruta k+1 (Media3 visar efter sökning första bild med tid ≥ positionen; regeln visar den första bild som hör till rutan). Nu (`PausedPick`, `PausedSeek`): spelhuvudets ruta, avkodarens sökposition och bildens egen tid hålls isär; spelaren söks före rutan och huvudspelarens bildtaktseffekt kör `SourceSampler`-regeln över bilderna utan att visa någon tills rutans bild är känd – en egen bild visas direkt, en hållen ruta får en andra, exakt sökning. Skanningen går aldrig in i föregående klipp i spelaren (Media3 avslutar ett klipp först när en bild ≥ dess sista avkodade lämnat effekterna); högst 3 bakåtsteg (3 rutor, 0,5 s, 2 s) plus klippets start inom 4 s, högst 6 sökningar, 2 s tidsgräns, sedan definierat reservbeteende (första bild vid spelhuvudet, loggat). Generation per begäran: en äldre sökning ersätter aldrig en senare. Media3:s scrubbing-läge stängs av under valet (i det körs en sökning först när den förra visat en bild – valet visar ingen förrän den rätta: spelaren stod). Släppta bilder under väntan ber inte om nästa (annars avkodades klippet till slutet). Appens stillbild ligger kvar tills valet är klart. Spelhuvudet flyttas aldrig; start av uppspelning söker till spelhuvudet. Verifierat på vivo mot avkodad helrendering (direkt och efter 5 s): S25 (30 fps och 120 fps i 25 %, trimmade, 25p) 21 av 22 positioner inkl. de nio tidigare, projektstart, sista rutan, klippgräns inne i förrenderad bit och bitstart; S60w (60p, varannan ruta hållen) 13 av 15; VFR-glapp 0,6 s 9 av 9; snabba följder fram/bak (0,05 och 0,3 s mellanrum) rätt; uppspelning från pausad position startar vid spelhuvudet (83–93 ms). Avvikelser som kvarstår (andra orsaker, registrerade nedan): första rutorna i ett levande klipp efter en förrenderad bit (S25 4,28; S60w 6,3167/6,3333); lager V2/V3 (ej rättade); bottenlagret under ett övre lager i exporten. Gräns: ett klipps första bild när klippet börjar mellan två ms (60p-bitar) kan inte nås med en sökning – pausat visas då klippets andra bild (mätt rätt vid 5,6833 i S60w, troligen för att bilderna är lika). | delvis |
| Levande klipp efter förrenderad bit börjar en källbild sent (2026-11-13, rättat i testversion 96) | Var: uppspelningens upplägg skar klippet vid bitens slut, så källbilden mellan föregående rutgräns och bitens slut – exportens bild för bitens slutruta – lästes aldrig (S25 4,28, S60w 6,3167–6,3333, i uppspelning och paus). Nu (`RenderCache.liveAfterChunks`): klippet börjar strax efter den punkt från vilken en källbild hör till bitens slutruta (föregående ruta + 1 ms + 2 µs), in-punkten flyttas lika mycket (alla bilder behåller sin tid), biten spelar lika mycket kortare (dess sista bild ligger på rutan före och står kvar). Starten är en skärning (`Seg.cut`): klippets första bild fästs inte på rutan (huvudspelaren och lagren) – en ruta utan egen bild håller bitens sista bild som exporten. Verifierat på vivo: pausat 14 positioner kring bitslut (25p och 60p) lika exporten; uppspelning över bitens slut (3 körningar, inspelning mot exporten): rutorna 100–120 i ordning, ruta 107 med. Samtidigt sett: ruta 116 hoppades över – klippbyteslagrets klocka saknade läsförsprångets förskjutning (rad nedan). | på vivo |
| Lagrens klocka och läsförsprånget (2026-11-13, rättat i testversion 97) | Var: huvudspelaren och exporten läser varje klipp 1 ms av filen tidigt, så deras bildtider ligger 1 ms / hastighet efter tidslinjetiden (4 ms vid 25 %); lagrens klocka (`LayerClock.timelineUs`) räknade utan den. En källbild exakt på en rutgräns hamnade då på olika rutor: 120 fps i 25 % på 25p – klippbyteslagret hoppade över var femte källbild som exporten visar (ruta 116 i S25 efter biten). Nu i samma tidsbas. Verifierat på vivo: uppspelning S25 3,08–5,3 s (3 körningar) – rutorna 95–132 i ordning, var och en 2–3 inspelade bilder; S60w över whippen (2 körningar) i exportens mönster. | på vivo |
| Exporten med flera lager: bottenlagret följer inte regeln (2026-11-13, registrerat) | Mätt (SV2: V1 fcA60 på 25p, V2 fcB30 från 1,36 s): vid 0,8 s visar exporten en annan V1-bild än regeln (och den pausade förhandsvisningen). I exporten med kompositör kommer bildtaktsregeln efter kompositören och ser det översta lagrets bildtider; bottenlagrets bild väljs av kompositörens parning. Förslag: rastrets urval per lager före kompositören (som `FillPlanner` redan gör i övergångsfönster), så att varje lager får regelns bild per ruta. | registrerat |
| Pausad bild i lager V2/V3 (2026-11-13, registrerat) | Mätt (SV2): V2 visar pausat nästa rutas bild (samma fel som huvudspelaren hade). Inte rättat: lagren spelas av egna ExoPlayer-spelare med egen synk (`LayerSync.decide` söker om när positionen avviker > 15 ms – en sökning före rutan skulle tolkas som drift). Förslag: samma `PausedPick` i `LayerGridEffect.select` (lagrets tidslinjetid via `LayerClock`), och `LayerSync` som jämför med begärd ruta i stället för spelarens position medan ett val pågår. | registrerat |
| Förrullning och VFR (2026-11-13, rättat i testversion 95) | Var: förrullningen räknades från filens nominella bildtakt (2,5 källbilder + en ruta, högst 1 s) – en del som började i ett långt glapp i en VFR-fil visade bilden efter glappet för tidigt (8 rutor), och en del helt inom en hållen bild fick ingen bild alls. Nu: förrullningen från klippens verkliga bildtider (`PreRollRule`, provtabellen ur den fil renderingen läser – proxyn för cachebitar, originalet för export – utan avkodning): delen läses från bilden före den bild helheten visar på delens första ruta; ett glapp saknar bilder och kostar inget att läsa igenom. Högst 8 steg bakåt mellan nyckelbilder och 30 s; ej läsbart, baklänges eller frysbild: den gamla uppskattningen. En del helt inom en hållen bild: startfästningen visar den osynligt behållna bilden från delens första ruta när dess klipp tar slut efter delens start. Delens lässtart läggs på en ruta: det övre lagrets tomrum ger kompositörens primära bilder från lässtarten, och utanför rastret parade kompositören bottenlagret med andra tider än helheten (en del från 1,52 s i S25 visade bilden före på varje ruta – upptäckt på telefonen, inte i enhetstesternas modell). Cacheversionen höjd. Verifierat på vivo: VFR-glapp 0,6 s (Sgap, 25p) – delar från 9 punkter före, i och efter glappet och 6 bitar i följd varav tre helt inom den hållna bilden; S25 och S60w omkörda; alla filer identiska med helrenderingen per ruta; cachens fönsterbit likaså. Enhetstest: glapp, del helt inom hållen bild (glapp och 30 fps i 5 %). Påstående: verifierat för de testade VFR-fallen. | på vivo |
| Saknad bild i push vid rendering (2026-11-13, rättat i testversion 94) | Var: i 2–4 av 8 renderingar av S25 från 0,4 s saknades det utgående lagrets bild under pushen från 3,96–4,04 s till fönstrets slut (avtagande med dess yta), samma rutor varje gång felet kom. Spårat (kompositörens, slutstegets och fyllnadens spår): avkodning, rasterurval, fyllnad och kompositörens val var lika i bra och dåliga körningar; förlustpunkten var lagrets sista effektsteg (Media3 FinalShaderProgramWrapper). Det övre lagrets tomrum var en svart bild på 16 px; när övergångsbiten (1280×720) kom byggdes texturpoolen om medan kompositören ännu höll tomrummets bild – dess kvarvarande tidsstämpel frigjorde sedan en textur som kompositören använde, och en senare bild ritades i den (tre bilder i kön med två texturer, endast i dåliga körningar). Tidsberoende: bara när kompositören inte hunnit släppa tomrumsbilden. Produktionscachen: förrullningen (sedan testversion 89) lägger tomrum först i varje fönsterbit, samma villkor – felet är inte observerat i en cachefil, men villkoret fanns. Rättat: tomrummet får lagrets bildstorlek (samma anpassning som klippen), poolen byggs aldrig om; cacheversionen höjd (gamla bitar renderas om). Verifierat: 12 renderingar från 0,4 s identiska och lika helrenderingen, 10 av fönsterbiten likaså, cachens nya fönsterbit lika, ingen kö med tre bilder. | på vivo |
| Exportens bildtakt: CFR eller VFR (2026-11-13, förslag) | Mätt (testversion 90): exporten lägger varje bild på en ruta men fyller inte tomma rutor. 30 bilder/s i 60p ger VFR ≤ 60 (S60w: 379 bilder för 720 rutor, varje tidsstämpel på en ruta; användarens material 365 för 691); 25p från 30 fps ger CFR (200/200). Förslag, inte gjort: en 60p-export ska vara CFR – fyll tomma rutor med föregående bild i exportens sista led (samma `KlippFrameFill`/kö som övergångarna, en planerare som fyller alla rutor upp till kompositionens slut) och ange bildtakten i filen; kostar kodning av upprepade bilder (små P-bilder). Förrenderade bitar kan förbli VFR (spelaren håller bilden). Egen commit efter beslut. | förslag |
| Prototyp en spelare (2026-11-12) | Gjord på grenen `single-player-prototype`: Media3 1.11.1 spelar inte flera lager i `CompositionPlayer` (flerinmatningsgrafen ger inga bildrutor, inte ens för en minimal komposition). Se `docs/single-player-prototype.md`. | på vivo |
| Prototyp en spelare, Media3 1.12.0-beta01 (2026-11-12) | Avslutad: två sekvenser i `CompositionPlayer` fastnar efter varje sökning – även i Googles omodifierade demo; en sekvens fungerar. Media3-fel; grenen ligger kvar lokalt, prövas igen vid nästa Media3-version. Skarpa bygget behåller lagerspelarna. | på vivo, demo + prototyp |
