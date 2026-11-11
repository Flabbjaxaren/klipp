# Media3-rendering i Klipp: förhandsvisning och export

Kartläggning enligt PR 8.1 i `docs/reliability-plan.md`, gjord mot koden på `design-test` (0.18.52-designtest.15,
2026-11-11) och Media3 1.11.1. Alla sökvägar nedan är relativa till `app/src/main/java/se/gendut/klipp/` om inget
annat anges; radnummer gäller den här versionen och flyttar sig med koden.

Markeringar:

- **verifierat i koden** – beteendet går att läsa ut ur koden som den är nu;
- **endast mätt tidigare** – en mätning på telefon som koden bygger på eller nämner, men som inte går att kontrollera
  genom att läsa koden;
- **hypotes** – en möjlig förklaring som inte är bekräftad.

---

## 1. Översikt

Förhandsvisningen är inte en väg utan flera som ritas ovanpå varandra i programvyn (`RealMode.kt:203–246`):

| Väg | Vad den ritar | Motor | Tidskälla | Kod |
|---|---|---|---|---|
| Huvudspelaren | Bottenlagret (understa videolagret) och allt ljud | `CompositionPlayer` | Spelarens egen klocka (ljudet) | `engine/Playback.kt:545` `ProgramPlayback` |
| Övre lager | V2/V3 och övertoningslagren | En ljudlös `ExoPlayer` per lager i en `TextureView` | Huvudspelarens `currentPosition` under uppspelning, annars `state.playhead` | `UpperLayers.kt:103` `LayerVideo` |
| Skarvlagret | Första sekunden efter varje klippbyte i bottenlagret | Samma som övre lager | Samma som övre lager | `UpperLayers.kt:599` `Handoff` |
| Titlar och undertexter | Text ovanpå allt | Compose `Canvas` | `state.playhead` | `UpperLayers.kt:548` `OverlayText` |
| LivePreview | Stillbild medan ett reglage dras, vid scrubbning, innan spelaren visat en bild | `MediaMetadataRetriever` + AGSL/CPU | `state.playhead` | `LivePreview.kt:188` |
| Förrenderade bitar | Hela bilden (alla lager) för en bit av tidslinjen, spelas som fil i bottenlagret | Transformer → H.264-fil | Som huvudspelaren | `engine/RenderCache.kt` |
| Nodcache | Ett klipps bild till och med en vald nod, läses av huvudspelaren | Transformer → HEVC 10-bit/H.264-fil | Som huvudspelaren | `engine/NodeCache.kt` |
| Proxyfiler | Lättare kopia av tunga original, används av alla förhandsvisningsvägar | Transformer | – | `engine/Proxies.kt` |
| Export | Hela bilden och ljudet | `Transformer` med `LayerCompositor` och `TimedOverlay` | Transformerns tidsstämplar | `engine/Playback.kt:950` `Exporter` |

Gemensamt underlag: `LayerPlanner.plan` (`engine/Layers.kt:140`) gör en `LayerPlan` med lagren överst först, och
`CompositionBuilder` (`engine/Playback.kt:54`) gör en Media3-`Composition` av den. Huvudspelaren, de övre lagren,
förrenderingen och exporten utgår alla från samma planerare, men med olika flaggor (avsnitt 2). Frysta bildrutor
(`engine/FrameHolds.kt`) och baklängesklipp (`engine/Reversals.kt`) är filer som tas fram i bakgrunden och används av
alla vägar.

## 2. Gemensam plan och tidsmappning

**Lager.** Varje aktiverat videospår (V3, V2, V1) blir ett lager; en övergång mellan två klipp ger dessutom ett eget
lager `"<spår> transitions"` ovanför spåret (`engine/Layers.kt:190–277`). Det lagret innehåller *både* det inkommande
klippets början före skarven och det utgående klippets slut efter skarven (`handleSegs`, `engine/Layers.kt:334`), med
opacitet från en `Ramp`. Där filen tar slut fylls det ut med en frusen bildruta. Det finns alltså ingen särskild kod
för "övertoningens andra halva"; det är ett vanligt övre lager. Toningar till svart på bottenlagret ligger i
`Seg.ramp` och, när det bara finns ett lager, i `LayerPlan.blackFades` (`engine/Layers.kt:290`).

**Projekttid → källtid** (verifierat i koden):

- Huvudspelaren och exporten: varje segment blir ett `EditedMediaItem` som klipps till
  `[srcIn, srcIn + dur·|speed|]` (`clipped`, `engine/Playback.kt:175`) och läses `VIDEO_LEAD_US` = 1 ms tidigare
  (`:59`) så att en bildruta en bråkdels millisekund före starten inte tappas. Hastighet sätts med
  `setSpeed(constantSpeed)` på objektet (`:245`, `:327`). Mediets längd anges som minst klippets slut (`mediaUs`,
  `:168`). Luckor blir `addGap`. Frysta bildrutor blir bildobjekt med projektets bildfrekvens (`:316–321`).
- Övre lager: målpositionen i spelaren är `(t − seg.start)·speed·1000 + preMs` ms (`UpperLayers.kt:349`), och
  hastigheten sätts med `setPlaybackSpeed` (`:348`). Frysta bildrutor spelas som ett 0,1 s långt klipp som står
  pausat (`:153`, `:159`).
- Tidsberoende effekter (keyframes, följda fönster, stabilisering, masker) får rutans tid genom `TimeBase`
  (`engine/Layers.kt:381`): tidsstämpeln tolkas som kompositionstid, med kalibrering mot spelarens position
  (`hintUs`) och en tolerans på 1,5 s (`:431`). Kan tiden inte tolkas visas bilden utan effekten ("fail-open"). I de
  övre lagren används i stället `LayerClock` (`UpperLayers.kt:763`), se risk 6.

**Effektordning per klipp** (`engine/Playback.kt:315`): nedskalning till förhandsvisningsstorlek → avkodningsrättning
(bara nodcache) → keying → nodernas gradering (noder, OpenDRT med kamerans log-ingång, LUT, kurvor, film) →
stabilisering och rörelseoskärpa (`engine/Grade.kt:773–781`) → mask/beskärning (`LayerFxEffect`) → anpassning till
sekvensens format (`Presentation`) och, utan kompositor, rörelse och opacitet (`MotionMatrix`, `KeyframedOpacity`) →
övergångens geometri (`TransitionFxEffect`). Tidslinjens `Look` läggs på hela kompositionen, en gång (`:457`).

## 3. Huvudspelaren (`ProgramPlayback`)

**Vad den spelar.** `update()` bygger med `forceSingle = true` och `overlays = false` (`engine/Playback.kt:785`):
bara bottenlagret, utan titlar. Den läser video från proxy (`Proxies.videoUri`, `:783`) och ljud från originalen,
räknar i förhandsvisningsstorlek (`previewShort`, standard 1080, `engine/VideoPipeline.kt:56`), läser nodcachen där
den finns och byter in färdiga förrenderade bitar (`RenderCache.substitute`, `:786`). Gradernas värden läses live
varje bildruta (`liveGrades = true`, `LiveGrades.publish`, `:764`), så ett reglage kräver ingen ombyggnad.

**Ombyggnad.** En nyckel av allt som påverkar bilden (`keyOf`, `:727`, och `:762`: förrenderade filer, HDR-läge,
nodcache, förhandsvisningsstorlek, avkodningskalibrering, frysta bildrutor, baklängesfiler) avgör om kompositionen
byggs om. Samma nyckel: bara ljudkedjorna synkas och, om spelaren står still, flyttas den 1 ms (`nudge`, `:862`) för
att rita om. Ny nyckel: `setComposition` på samma spelare (`:813`), de gamla ljudkedjorna frigörs 3 s senare (`:780`).

**Tid, seek, play och pause** (verifierat i koden):

- Tidskällan är `player.currentPosition`. Uppspelningsloopen i `RealMode.kt:375–393` skriver den till
  `state.playhead` varje bildruta, och laddar om spelaren om positionen stått still i 180 bildrutor (`:391`).
- `seek()` (`engine/Playback.kt:907`) går via `CutSeek.ms` (`:502`): pausad och inom 0,3 bildrutor från en skarv
  hamnar den på skarven + 1 ms, så att bilden efter skarven visas. `inside()` (`:905`) håller positionen inom
  tidslinjens sista bildruta.
- `start()` (`:876`) flyttar en start som ligger mindre än `STALL_WINDOW` = 0,2 s före en kant till `PRE_ROLL` = 0,3 s
  före kanten (`CutSeek.startSec`, `:516–526`). Kanterna är klipp, ljudklipp, övergångar och förrenderade bitar
  (`edgesOf`, `:529`). JVM-test: `app/src/test/.../StartEdgeTest.kt`.

**Fel.** `onPlayerError` (`:559`) markerar spelaren som död; nästa omladdning byter till en ny `CompositionPlayer`
(`replacePlayer`, `:642`) och den gamla släpps när ytan flyttat. Timeout och runtime-check prövas om en gång
(`:582–588`); HDR-tonmappning byter väg (OpenGL ↔ avkodaren) och tetraedrisk LUT faller tillbaka på Media3:s
trilinjära vid avkodar- eller effektfel (`:589–603`).

**Resurser.** Spelaren skapas en gång (`newPlayer`, `:710`) med `ReadAheadLoadControl` (`engine/Perf.kt:28`),
`MeterSink` och `TenBitGlObjectsProvider` (10-bit RGBA_1010102-yta när inställningen är på). Varje `setComposition`
river och skapar Media3:s interna spelare, avkodare och GL-kontext. Programvyn är en `SurfaceView`, utom vid
blandningslägen, zoom och hörnplacering då den är en `TextureView` (`RealMode.kt:218`, `ProgramSurface` `:803`).
`release()` (`engine/Playback.kt:914`) släpper spelaren och sedan ljudkedjorna.

## 4. Övre lager (`UpperLayers`)

Media3:s `CompositionPlayer` kan inte visa flera videosekvenser i 1.11, så varje övre lager spelas av en egen
`ExoPlayer` (`UpperLayers.kt:112–121`): ljud avstängt, `SeekParameters.EXACT`, samma laddningskontroll som
huvudspelaren. Planen är samma som huvudspelarens (`previewPlan` + `RenderCache.substitute`, `:69–75`), så lagren är
överens om vad som är bottenlager och var förrenderat finns.

**Två spelare per lager.** Segment som ligger närmare än 1 s varandra delas mellan två spelare som turas om
(`Alternate.split`, `:625`); den lediga väntar pausad på nästa segments första bildruta. Ett övertoningslager med flera
övergångar har alltså två spelare.

**Tid och synk** (verifierat i koden): tiden är huvudspelarens `currentPosition` när det spelas, annars
`state.playhead` (`:222`). Lagret startar först när huvudspelarens position faktiskt rör sig (`advancing`, `:220`,
`run`, `:353`). Därefter spelar det på egen klocka; `LayerSync.decide` (`:648`) seekar bara när driften är över
`SEEK_MS` = 200 ms och senaste seek var mer än 1 s sedan (`:654`), med 120 ms försprång. Pausat seekas det till
exakt bildruta om felet är över 15 ms (`:652`). Drift och bildrutor per lager loggas i `SyncStats` (`:660`), och
visade bildrutor räknas på `TextureView.surfaceTexture.timestamp` (`:297`, `:678`).

**Seek, pause och ändringar i tidslinjen.** Ingen händelse skickas till lagren; loopen i `LaunchedEffect` (`:183`)
läser `state.playing`, `state.playhead` och huvudspelarens position varje bildruta och sover pausat tills någon av
dem ändras (`idle`, `:211`). En ändrad tidslinje ger en ny plan, nya segment och `setMediaItems` + `prepare`
(`:150–165`); spelaren behålls så länge lagrets namn är detsamma (`key(part.name)`, `:96`).

**Döljning och gamla bildrutor** (verifierat i koden): lagrets alfa är 0 när inget segment är aktivt, när den
senast ritade bildrutan hör till ett annat segment (`drawnIndex != active`), under förrullning och för skarvlagret när
det är pausat (`:424`). `drawnIndex` sätts av `onRenderedFirstFrame`, `onIsPlayingChanged(true)` och en
`onMediaItemTransition` som inte är en seek (`:137–143`). Utanför sina segment pausas spelaren och ställs på nästa
segments början (`:330–342`). `TextureView` är genomskinlig (`isOpaque = false`, `:452`), så ett lager utan bild visar
det undre, aldrig svart.

**Effekter, geometri och färg.** Klippets gradering, tidslinjens `Look` och nyckel/mask läggs som `setVideoEffects`
på lagrets spelare (`:387–401`), med nedskalning till förhandsvisningsstorlek först om det finns effekter (`:398`).
Placering, skala, rotation, opacitet och övergångens förflyttning, mask och oskärpa görs i Compose `graphicsLayer`
(`:421–448`); oskärpan är en AGSL-shader som bara finns från Android 13 (`:408`, `:445`). Blandningslägen blir
Compose `BlendMode` (`:426–428`, `:534`). Nodcachen och avkodningsrättningen används inte här.

**Täckning.** Ett ogenomskinligt lager som täcker ett undre klipp helt markerar det med `Occlusion.hide`
(`:314–329`, `engine/Occlusion.kt:25`); filmpasset för ett markerat klipp skickar då bilden vidare utan film
(`engine/FilmGl.kt:971`). Märket släpps 0,5 s (`LEAD_S`) innan klippet syns igen.

**Resurser.** En `ExoPlayer` (avkodare + egen GL-kontext när den har effekter) per `LayerVideo`, skapad med
`remember` och släppt i `onDispose` (`:122`). Spelaren förbereds direkt och ligger kvar pausad på nästa segment, så
den håller sin avkodare även när lagret inte syns. `TextureView` kopplas i `factory` och kopplas loss i `onRelease`
(`:451–455`).

## 5. Skarvlagret (`Handoff`)

`CompositionPlayer` kan inte förvärma nästa klipps avkodare. Skarvlagret (`UpperLayers.kt:599–617`) är därför ett
extra lager med segmentet `[skarv, skarv + 1 s]` för varje klipp i bottenlagret, spelat av en egen spelare som står
pausad på första bildrutan och tar över bilden vid skarven. Det ligger direkt ovanför huvudspelaren (`:93`).

- **Undantag** (verifierat i koden): tidslinjens start, segment med ramp (toning), med övergångsgeometri, frysta
  bildrutor och klipp med opacitet under 100 % (`:609–611`); stabiliserade klipp *och* klipp med rörelseoskärpa
  (`:81`, `:87`); HDR-original utan proxy, eftersom de tonmappas olika i de två spelarna (`:89`).
- **Förrullning:** segmentet startar 0,25 s tidigare, osynligt, där inget annat lager spelar (`HANDOFF_PREROLL_S`,
  `handoffPreroll`, `:472–495`). Blir huvudspelaren sen under en förrullning stängs den av för sessionen
  (`Preroll.off`, `:286–293`).
- **Slut:** objekten spelar 0,5 s förbi segmentet (`HANDOFF_ITEM_TAIL_S`, `:477`) så att nästa skarvs klipp aldrig
  blinkar fram.
- **Avlösning:** skarvlagret drar sig undan när huvudspelarens kedja har gått 4 bildrutor av det nya klippet
  (`Occlusion.mainFrames`), ingen sen bildruta kommit på 250 ms och inget annat lager just startar (`:259–278`).
  Medan det täcker hela bilden stängs huvudspelarens film av för det klippet (`Occlusion.hideMain`, `:304–311`);
  då väntar avlösningen tills huvudspelaren gått två bildrutor *med* film plus 150 ms (`HANDOFF_FILMED`,
  `HANDOFF_GRADED_MS`). Pausas det under avlösningen står lagret kvar upp till 1 s tills huvudspelaren ritat om sin
  bild med film (`:224–244`).
- **Förutsättning:** `Occlusion.mainFrames` räknas av den första nodens shader i huvudspelaren
  (`engine/Grade.kt:1540`). Ett klipp utan aktiv nod räknas aldrig, så där täcker skarvlagret hela sekunden.

## 6. Titlar och undertexter

Förhandsvisning: `OverlayText` (`UpperLayers.kt:548`) ritar titlar och undertexter i en Compose `Canvas` i halv
upplösning (`OverlayBitmaps`, `:581`), placerade med `placementOf` för `state.playhead`. Export: `TimedOverlay`
(`engine/Layers.kt:752`) som Media3-`OverlayEffect` i full projektstorlek, titlar som visas samtidigt i egna banor
(`overlayLanes`, `:742`), före exportens bildfrekvens och storlek (`engine/Playback.kt:459–474`).

## 7. LivePreview

Visas medan ett reglage, ett hjul eller en kurva dras, vid scrubbning, i trimvyn, innan huvudspelaren visat sin
första bild och medan en ny `Look` byggs in (`RealMode.kt:244`). Den ritar ett stillbildslager per spår med klippet
under spelhuvudet (`LivePreview.kt:204–206`):

- bilden kommer från `MediaMetadataRetriever` på proxyn (`Frames`, `:62`), vid scrubbning först en snabb
  nyckelbildruta eller en lågupplöst scrubbild (`:221–234`); källtiden hålls inom klippets in/ut (`:212`);
- graderingen görs i AGSL på GPU (`engine/GpuGrade.kt:42`); full film (korn, halation) finns inte där, så en nod med
  film räknas på CPU i 480 px bredd när ingen dras (`:45`, `LivePreview.kt:273–277`); medan något dras visas filmens
  färger bara som LUT (`filmColorOnly`);
- position, skala, rotation, opacitet och stabilisering ritas i Compose (`:346–364`); titlar i halv upplösning,
  undertexter med samma ritkod som exporten (`:366–376`);
- **saknas** (verifierat i koden): övergångar och övertoningar (bara klippet under spelhuvudet), toningar,
  blandningslägen, nyckel, mask och beskärning (`GpuGrade` har ingen `LayerFx`), keyframes ritas med värdet vid
  spelhuvudet.

Inaktuella resultat: avkodningen avbryts med sin `LaunchedEffect` (nyckel klipp + bildruta, `:226`); en gradering som
redan pågår avbryts aldrig, och en äldre graderad bild visas hellre än en ograderad (`:259–281`, `:343–345`).

## 8. Förrenderade bitar (`RenderCache`, grön linjal)

Tidslinjen delas vid varje kant i bitar på minst 0,25 s (`chunks`, `engine/RenderCache.kt:127`). Varje bit får en
SHA-1-nyckel av allt som påverkar bilden (lager, källor, ramper, övergångar, klippets bilddata, graderingens JSON),
plus `VERSION` = 4 (`:78`). En bit renderas med `CompositionBuilder.buildFull(range = a..b)` och `Exporter.transform`
(`:395–413`): alla lager genom `LayerCompositor`, från proxyfiler, i förhandsvisningsstorlek, utan `Look` och utan
titlar, som H.264 8-bit SDR med nyckelbildruta var 0,5 s och 1,5 × standardbithastighet (`renderSettings`, `:444`).
Rendering pågår bara när inget spelas (`canWork = { !state.playing }`, `App.kt:429`), och en färdig bit byts in först
när uppspelningen stoppats (`App.kt:432`).

Uppspelning: `substitute` (`:219`) tar bort alla lager inom färdiga bitar och lägger filen i bottenlagret; intilliggande
bitar sätts ihop till en fil utan omkodning (`joinRuns`, `:266`). `BlackFade` hoppar över förrenderade intervall
(`engine/Layers.kt:794`). Tidslinjens `Look` läggs på bitarna under uppspelning.

## 9. Nodcache och proxyfiler

**Nodcache** (`engine/NodeCache.kt`): bilden till och med den sista noden med cache påslagen renderas från originalet i
full upplösning, i flyttal, till HEVC Main10 (annars H.264 High) med mycket hög bithastighet, för klippets del av filen
± 2 s (`compute`, `:116`; rendering `:259–300`). Nyckeln innehåller nodernas värden och LUT-filernas innehåll
(`:129–131`). Bara huvudspelaren läser cachen (`engine/Playback.kt:271–280`); den rättas då med en uppmätt färgmatris
för telefonens avkodning (`DecodeCalibration`, `NodeCache.correction`, `:257`). Övre lager, skarvlagret, förrenderingen
och exporten räknar alltid live. Inte med gröna skärmen (nyckeln räknas på källbilden).

**Proxyfiler** (`engine/Proxies.kt`): tunga original (kort sida > vald proxystorlek, > 50 fps över 720p, HDR ≥ 720p,
`needs`, `:155`) kodas om i bakgrunden till 1080p (inställbart), 8-bit H.264 eller 10-bit HEVC; HDR tonmappas till SDR
när proxyn görs (`:236`). Huvudspelaren, de övre lagren, LivePreview, scrubbilder och förrenderingen använder proxyn;
exporten och nodcachen aldrig. Proxyfiler rättas inte med `DecodeCalibration`.

**Frysta bildrutor** (`engine/FrameHolds.kt:98–109`) tas ur originalet med `MediaMetadataRetriever` och sparas som
8-bit PNG, både för förhandsvisning och export.

## 10. Export (`Exporter`)

`export()` (`engine/Playback.kt:955`) väntar in frysta bildrutor och baklängesfiler, avgör HDR/10-bit med
`ExportPlan.resolve` (samma regler som dialogen, `engine/ExportPlan.kt`), och bygger med `CompositionBuilder.build`
från originalen: full upplösning, inga proxyfiler, ingen nodcache, fasta gradervärden (`liveGrades = false`), titlar
via `TimedOverlay`, och med fler än ett lager `LayerCompositor` (`engine/Layers.kt:684`). Med ett enda lager används
den enkla vägen (`MotionMatrix`, `KeyframedOpacity`, `BlackFade`). Sist kommer `FrameRateEffect`
(`engine/Export.kt`, rutnät med en bildruta per plats, konstant bildfrekvens) och storleksändring (`:468–474`).

Kompositorn (`LayerCompositor.getOverlaySettings`, `engine/Layers.kt:712`): ett övre lager utan segment eller med
otolkbar tid får alfa 0, bottenlagret visas alltid (`:718`). Övre lager får 0,2 s extra material efter klippets slut
(`UPPER_TAIL`, `engine/Playback.kt:64`, `:227–235`) eftersom Media3 fyller sista bildrutorna med svart när avkodaren
tappat B-rutor vid klippningen; kompositorn döljer överskottet.

Transformern skapas per export med `VideoPipeline.processorFactory(tenBit)` (`engine/Playback.kt:1090`),
`setEnableFallback(true)` och CPU-boost. Misslyckas HDR-tonmappningen görs ett nytt försök med den andra vägen;
misslyckas 10-bit görs ett nytt i 8-bit (`:996–1007`). Effektkedjorna (`FxRegistry`) är egna och frigörs efteråt.
Förhandsvisningens spelare pausas eller släpps inte under exporten, och förrenderingen kan köra samtidigt (se risk 9).

## 11. Färgkedjan per väg

| | Huvudspelaren | Övre lager / skarvlager | LivePreview | Förrenderat | Export |
|---|---|---|---|---|---|
| Källa | Proxy, nodcache eller original | Proxy eller original | Proxy via `MediaMetadataRetriever` (8-bit `Bitmap`) | Proxy | Original |
| Avkodning | Media3, HDR tonmappas enligt `hdrMode` | Media3 `ExoPlayer`, ingen egen HDR-hantering | Plattformens | Media3, `hdrMode` | Media3, HDR behålls eller tonmappas |
| Upplösning | Nedskalad till `previewShort` före effekter | Samma, bara när det finns effekter | Skärmstorlek, CPU-film 480 px | `previewShort` | Full |
| Noder, LUT, OpenDRT, film | Live, flyttal (RGBA16F) | Live, flyttal | AGSL; film som LUT eller CPU | Fasta värden, flyttal | Fasta värden, flyttal |
| Tidslinjens `Look` | På bottenlagret | **Per lager** | Per lager | På hela filen vid uppspelning | En gång på hela bilden |
| Blandning | – | Compose, 8-bit | Ingen | `KlippBlend` i kompositorn | `KlippBlend` i kompositorn |
| Utyta | 10-bit RGBA_1010102 om möjligt | `TextureView` (8-bit) | Compose | H.264 8-bit | 8- eller 10-bit, HDR |

Log-ingång (vivo Log, Apple Log, S-Log3 m.fl.) ligger i noderna, som OpenDRT-ingång eller LUT, bakad till en 3D-LUT
per nod (`engine/Grade.kt:466`, `engine/OpenDrt.kt`). Ingen väg gör en separat log-omvandling före noderna.

## 12. Den vendrade modulen media3-effect

`third_party/media3-effect` ersätter `media3-effect:1.11.1` i hela appen (även ExoPlayer och Transformer).
Klipp är beroende av:

- `KlippPrecision.highPrecision` – RGBA16F i alla texturpooler (`TexturePool.java:167`); global, gäller alla spelare,
  förrenderingen, nodcachen, proxyfilerna och exporten (`engine/VideoPipeline.kt:68–72`);
- `KlippPrecision.framesShown`, `framesLate`, `lastLateAt`, `lateTimes` – används av skarvlagret (`UpperLayers.kt:261`,
  `:288`) och uppspelningssammanfattningen (`:711–735`). Räknarna är globala för alla kedjor;
- `KlippBlend.Settings` – blandningslägen i kompositorn (`engine/Layers.kt:744`);
- `DefaultVideoFrameProcessor.Factory.klippGlObjectsProvider` och `MultipleInputVideoGraph` – 10-bit-ytor även i
  flerlagersexport;
- `KlippPriority` – låg GPU-prioritet för videokontexterna.

**Odokumenterat i `KLIPP.md`** (verifierat i koden): `FinalShaderProgramWrapper.java:483–487` visar en enstaka sen
bildruta i stället för att släppa den (`KlippPrecision.takeLateFrame`, högst en per 300 ms). Det påverkar vad som syns
vid klippbyten och bör stå med i listan över ändringar som görs om vid uppgradering.

## 13. Avsiktliga skillnader mellan förhandsvisning och export

- Proxyfiler i stället för original; nedskalning till `previewShort` före effekterna; nodcachen.
- Förrenderade bitar (8-bit H.264 i förhandsvisningsstorlek, tidslinjens `Look` läggs på efteråt).
- Övre lager i egna spelare och Compose i stället för Media3:s kompositor; titlar i halv upplösning.
- Skarvlagret och `Occlusion` (film hoppas över under täckta klipp) finns bara i förhandsvisningen.
- `FrameRateEffect`, `UPPER_TAIL` och HDR-export finns bara i exporten.
- LivePreview visar bara stillbilder utan övergångar, blandning och masker medan något dras.

## 14. Tidigare mätningar – status

| Påstående | Status |
|---|---|
| `CompositionPlayer.currentPosition` står still 30–45 ms åt gången under uppspelning | Att positionen står still i några bildrutor står i koden (`UpperLayers.kt:351–352`) och hanteras med `advancing`. Storleken 30–45 ms: **endast mätt tidigare**. |
| Lagrens jämnhet mäts med `surfaceTexture.timestamp`; avkodarens räknare säger inget med effekter | **Verifierat i koden** (`UpperLayers.kt:297`, kommentaren `:668–670`). Att avkodarräknarna är oanvändbara: **endast mätt tidigare**. |
| Tre filmkedjor samtidigt överbelastar GPU:n (`Occlusion.hideMain`) | Mekanismen **verifierad i koden** (`UpperLayers.kt:264–267`, `:304–311`, `engine/FilmGl.kt:971`). Själva överbelastningen: **endast mätt tidigare**. |
| Stabiliserade klipp undantas från skarvlagret, orsak okänd | **Verifierat i koden** (`UpperLayers.kt:81`, `:87`); gäller även rörelseoskärpa. Orsaken: se risk 6 (hypotes). |
| Start 1–4 bildrutor före ett stycke slut stod still; rättat i 0.18.52 | Rättningen **verifierad i koden** (`CutSeek.startSec`, `engine/Playback.kt:516`, används i `start()` `:886`), JVM-test finns (`StartEdgeTest`). Själva stoppet: **endast mätt tidigare**. |
| `CompositionPlayer` rensar aldrig ett fel, Klipp byter spelare | Bytet **verifierat i koden** (`engine/Playback.kt:561–564`, `:642`). Att Media3 inte rensar felet: **endast mätt tidigare**. |
| Ibland svart blink strax före en skarv (≈ 1 av 4 körningar) | **Endast mätt tidigare**, oförklarat. Se öppen fråga 1. |

## 15. Verifierade risker

1. **Tidslinjens `Look` läggs per lager i förhandsvisningen men på hela bilden i exporten.**
   `UpperLayers.kt:396` mot `engine/Playback.kt:457`. En icke-linjär LUT eller kontrast före en övertoning,
   halvgenomskinlighet eller ett blandningsläge ger en annan bild än efter. Test (8.3/8.4): övertoning och
   multiplicera-lager med en stark LUT som `Look`, förhandsvisning mot exportbild vid samma tid.
   **Åtgärdat 2026-11-11:** när något är staplat (ett övre lager eller en övergång) och telefonen har Android 13+
   läggs `Look` som en AGSL-effekt över huvudspelaren och de övre lagren tillsammans (`ProgramStack`,
   `GpuGrade.lookRenderEffect`), huvudspelaren byggs utan den (`Playback.lookOnScreen`) och titlarna ritas utanför
   (`ProgramTitles`), som i exporten. Mätt på vivo X300 Ultra, mitt i en övergång med förvalet Film: medelljushet
   17,0 i förhandsvisningen och 17,0 i exporten; utanför övergången 58,2 mot spelarens egen `Look` 59,0. Kvar:
   Android 12 och äldre behåller lager för lager; i läget är huvudbilden en `TextureView` (8-bit, ingen HDR-yta).
2. **Övre lager tål 200 ms drift innan de rättas.** `LayerSync.SEEK_MS` (`UpperLayers.kt:641`, `:654`): ett lager kan
   ligga flera bildrutor fel i upp till en sekund efter senaste seek, och felet visas bara i diagnostiken. Test (8.2):
   "Lång uppspelning (5 min)" och "Upprepad play/pause" med bildnummer i bilden, avläst via `surfaceTexture.timestamp`.
3. **Keyframes, toningar och titlar följer en position som står still.** `graphicsLayer` och `OverlayText` läser
   `state.playhead` (`UpperLayers.kt:423–437`, `:553`), som under uppspelning är `currentPosition`
   (`RealMode.kt:380`), medan lagrets video går på egen klocka. Animationer i övre lager och titlar rör sig därför i
   steg och kan ligga före eller efter videon. Test (8.3): keyframad position på V2 och en titel med rörelse, bild för
   bild mot exporten.
4. **Två implementationer av geometri i exporten.** Ett lager: `MotionMatrix`/`KeyframedOpacity`
   (`engine/Playback.kt:250–259`); flera lager: `motionSettings` i kompositorn (`engine/Layers.kt:666`). En övergång
   någonstans i projektet byter väg för hela exporten. I förhandsvisningen görs samma sak en tredje gång i Compose.
   Test (8.3): samma roterade och skalade klipp i ett projekt med och utan ett V2-klipp, i ett icke-kvadratiskt format.
5. **Bakgrundsjobb river spelare under uppspelning.** `NodeCache`, `FrameHolds` och `Reversals` anropar
   `playback.refresh` när en fil blir klar, även medan det spelas (`App.kt:376–378`), vilket ger en ny komposition
   mitt i uppspelningen. `Proxies.revision` laddar om de övre lagrens objekt direkt (`UpperLayers.kt:149–165`), medan
   huvudspelaren väntar tills uppspelningen stoppats (`RealMode.kt:360–361`). Nodcache- och proxykodningen pausas inte
   under uppspelning (ingen kontroll i `engine/NodeCache.kt` eller `engine/Proxies.kt`), till skillnad från
   `RenderCache`. Test (8.5): spela medan en proxy och en nodcache blir klara; räkna seeks och sena bildrutor.
6. **Skarvlagrets klocka uppdateras inte för nästa segment förrän det blir aktivt.** `LayerClock` sätts bara i den
   aktiva grenen (`UpperLayers.kt:350`); medan spelaren väntar pausad på nästa segment (`:330–342`) har effekterna redan
   bytts till det klippet (`cue`, `:339`, `:387`) men klockan har kvar föregående segments `srcIn` och `posSec`. Den
   väntande bildrutan och de första efter starten kan därför få stabilisering, följda fönster eller animerad mask för
   fel källtid. **Hypotes** för orsaken till stabiliseringsfelet; samma risk för V2/V3. Test (8.2): stabiliserat
   klipp bakom en skarv med undantaget i `:87` borttaget, jämför första sekunden mot exporten.
7. **Gamla bildrutor efter seek inom samma segment.** `drawnIndex` nollställs bara när en seek byter objekt
   (`UpperLayers.kt:142`). En seek inom samma segment låter föregående bild synas tills den nya ritats. Test (8.2):
   "Snabb scrubbning över gränser" och seek fram och tillbaka inom ett V2-klipp, pausat.
8. **Förrenderade bitar kan bli inaktuella eller färgfel.** Nyckeln innehåller graderingens JSON men inte LUT-filens
   innehåll (`engine/RenderCache.kt:166`, jämför `engine/NodeCache.kt:131`), inte filmmotorns version utöver
   `VERSION` (`:78`), inte proxy eller original och inte HDR-tonmappningens inställning. Bitarna rättas inte med
   `DecodeCalibration`, så en telefon som avkodar BT.709 som BT.601 ger en färgskiftning vid bitens kanter. Test (8.4):
   samma övergång live, via skarvlagret och förrenderad ("Samma bild vid samma tid"), samt byt LUT med samma namn.
9. **Avkodare och kodare delas utan policy.** Varje övre lager håller en förberedd `ExoPlayer` (två med `Alternate`),
   plus skarvlagret och huvudspelaren, även när lagret inte syns (`UpperLayers.kt:112`, `:330–342`). Under export
   pausas inte förhandsvisningen och förrenderingen kan köra samtidigt (`App.kt:429` kontrollerar bara uppspelning).
   Test (8.5): 1 ↔ 3 aktiva lager, export under pågående förrendering, på en telefon med få hårdvaruavkodare.
10. **HDR i övre lager.** Skarvlagret undantar HDR-original utan proxy (`UpperLayers.kt:89`), men V2/V3 och
    övertoningslager gör det inte och har ingen egen tonmappning. Test (8.4): HDR-original utan proxy (proxy av) på V2
    mot exporten.
11. **Övergångar ritas på tre sätt.** `TransitionFxEffect` i huvudspelaren och exporten, Compose `graphicsLayer` och
    AGSL i de övre lagren (oskärpan saknas under Android 13, `UpperLayers.kt:445`), och inte alls i LivePreview.
    Test (8.3): push och zoom med oskärpa, live mot export, på Android 13+ och äldre.
12. **Frysta bildrutor är 8-bit PNG från plattformens avkodning** (`engine/FrameHolds.kt:98–109`) bredvid levande
    video avkodad av Media3. Test (8.4): 10-bit- eller HDR-klipp där filen tar slut i en övertoning, jämför färgen
    på den frysta bildrutan mot rutan före.
13. **`KLIPP.md` är ofullständig** (avsnitt 12): bortglömd ändring vid en uppgradering ändrar beteendet vid
    klippbyten utan att något test fångar det. Test (8.4): lista i `KLIPP.md` med de regressionstester som ska köras.

## 16. Öppna frågor

1. **Svart blink före skarv.** Bottenlagret i förhandsvisningen får aldrig `UPPER_TAIL` (`engine/Playback.kt:230`
   gäller bara övre lager i flerlagerskomposition, och huvudspelaren bygger med `forceSingle`). Samma Media3-beteende
   som `UPPER_TAIL` finns för (sista rutorna svarta när B-rutor tappas) kan alltså nå skärmen före en skarv i
   bottenlagret. Hypotes; mät med bildnummer och `surfaceTexture.timestamp`/skärminspelning.
2. Ligger `SurfaceView` (huvudspelaren) och `TextureView` (övre lager) en vsync isär? De presenteras på olika vägar.
3. Är 200 ms rätt tröskel för `LayerSync`, eller ska den kopplas till en bildruta när lagret är stabilt?
4. Ska skarvlagret räkna huvudspelarens bildrutor även för ograderade klipp (i dag ingen `sawMain` utan aktiv nod)?
5. Ska nodcache, proxyfiler och baklängesfiler bytas in bara när uppspelningen står still, som förrenderingen?
6. Ska förrenderade bitar och proxyfiler rättas med `DecodeCalibration` som nodcachen?
7. Behövs en gemensam, testbar tidsmappning (projekttid → källtid) för `CompositionBuilder`, `LayerVideo` och
   `LayerClock`, i stället för tre?

## 17. Rättelser till planens PR 8

- Exporten använder `LayerCompositor` bara när det finns fler än ett lager; huvudspelaren i förhandsvisningen använder
  varken `LayerCompositor` eller `TimedOverlay`, men förrenderingen använder `LayerCompositor`.
- Det finns ingen särskild väg för "övertoningarnas andra halva": övergångarna är ett vanligt övre lager med båda
  klippens överhäng, spelat av två spelare (`Alternate`).
- Startstoppet har redan ett JVM-test för logiken (`StartEdgeTest`); det som saknas är ett test på telefon.
- Undantaget från skarvlagret gäller stabilisering *och* rörelseoskärpa, och HDR-original utan proxy.
- `KLIPP.md` dokumenterar inte alla ändringar i Media3 (`takeLateFrame`).

## Mätningar och åtgärder 2026-11-11 (vivo X300 Ultra, Android 17, Media3 1.11.1, testversion 18)

- **Färgkedjan i exporten (8.4).** Standard med 10-bitskällor: HEVC Main 10, `yuv420p10le`, BT.709 begränsat omfång,
  25 fps; i ett utsnitt 979 nivåer och 75 % av värdena inte multiplar av 4, alltså verklig 10-bit. "Bäst
  kompatibilitet": H.264 High, 8-bit, BT.709. HDR-export och HDR → SDR är inte provade (inget HDR-material i
  testprojektet).
- **Resurser (8.5).** 28 cykler med spela/pausa, fem seeks, färg-, ljud- och redigeringssidan, projektsidan och
  tillbaka, bakgrund och förgrund. Trådar 82 → 102 och grafikminne 114 → 176 MB under de första cirka 12 cyklerna, sedan
  oförändrat (102 trådar, 175–176 MB, PSS 580–620 MB) i 16 cykler: ingen fortsatt ökning efter uppvärmning. Antalet
  avkodare kunde inte räknas med `dumpsys media.resource_manager` och är omätt.
- **Avbruten export (8.5/PR 7).** Force-stop mitt i en export: ingen fil, dialog vid nästa start. Med appen i
  bakgrunden blir en 4K 10-bit-export klar som förgrundstjänst.
- **Risk 1 (Look per lager):** åtgärdad, se ovan.
- **Risk 5 (bakgrundsjobb river spelare):** nodcache, förrenderade bitar och proxyfiler byts nu in först när
  uppspelningen stoppats (`App.kt`, `UpperLayers.kt`); frysbilder och baklänges-filer byts fortfarande in direkt,
  eftersom bilden är fel tills de finns. Kodningen av proxyfiler och nodcache pausas fortfarande inte under uppspelning.
- **Förrenderade bitars nyckel:** innehåller nu varje LUT:s innehåll och om biten gjordes från proxyn (`RenderCache`,
  test `aNewLutUnderTheSameNameMakesTheChunksAgain`). Följd: befintliga förrenderade bitar görs om en gång.
- **Diagnostik (8.6):** långtryck på "Program" visar nu även Look-väg, visade/sent kastade/sent visade bildrutor och vad
  som syns vid spelhuvudet (förrenderat eller live, antal lager, proxy eller original per lager). Kraschspåret sparar
  filnamn (`lastPathSegment`), aldrig hela sökvägar; ett felmeddelande från systemet kan innehålla en sökväg.

**Inte gjort:** systematisk bildjämförelse förhandsvisning–export med syntetiska klipp på telefon (8.3, utöver Look-
mätningen), fem minuters driftmätning (testprojektet är 5,7 s), HDR-material, svagare telefoner. Hypoteserna om
skarvlagrets klocka (risk 6) och det svarta blinket före ett klipp är inte prövade.
