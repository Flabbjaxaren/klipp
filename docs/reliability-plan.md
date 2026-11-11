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
