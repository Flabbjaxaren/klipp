# Rapport: pålitlighetsplanen (PR 1–8) – genomfört och slutsatser

Skriven 2026-11-11 för den som skrev planen. Gren `design-test` (pushad), commits `747a845..2773653`.
Detaljer: `docs/reliability-plan.md` (plan + status), `docs/media3-rendering.md` (renderingsvägar, risker, mätningar).
Testat på **vivo X300 Ultra, Android 17** (OnePlus 12 var inte inkopplad under den här delen). Enhetstester: **523, 0 fel**
(Robolectric). Ingen fysisk test på svagare telefoner eller Android < 13.

## 1. Avvikelser mellan planen och koden (hittade vid kartläggningen)

- Exportknappen spärrades aldrig, men exporten **bytte tyst** codec, bitdjup och HDR; sammanfattningen visade det valda,
  inte det gjorda. `EncoderCaps.sizeSupported` tolkade "vet ej" som "nej".
- Exportera projekt kunde **förstöra en befintlig fil** (skrev över med `"wt"`, raderade vid fel).
- Versionshistorikens återställning skapar **redan** ett nytt projekt (planens antagande var fel).
- Rutnätets projektkort hade redan ⋮; bara det stora "senast öppnat"-kortet saknade den.
- Raderingsgesten hade redan skylt och haptik. Tips fanns men kunde inte återställas.
- Export körde utan foreground service; processdöd gav ingen information.
- PR 8: det finns fler renderingsvägar än tre (skarvlager `Handoff`, övergångslager med två alternerande spelare,
  förrenderade bitar, nodcache, proxy). Diagnostikvy och `third_party/media3-effect/KLIPP.md` fanns redan.

## 2. Genomfört per PR

**PR 1 – Exportplan.** `engine/ExportPlan.kt`: `ExportPlan.resolve(...)` används av både dialog och export.
`EncoderInfo` med `Support.YES/NO/UNKNOWN`; okänt blockerar aldrig. Fel blockerar Exportera; storlek som bara annan
codec klarar = varning + knapp "Använd HEVC"; sammanfattningen visar effektiv codec/bitdjup. `remember(codec)`-buggen
borta. Tester: `ExportPlanTest`, `ExportDialogTest`.

**PR 2 – Projektåtgärder.** ⋮ på senast öppnat-kortet (48 dp, TalkBack-text), egna menyrader för säkerhetskopia /
projektfil / versioner, text om att versioner inte skyddar mot avinstallation. Hittat och rättat: projektsidan öppnades
förbi det stora kortet (rutnätet höll kvar raden "Alla projekt"). Test: `ProjectsTest`.

**PR 3 – Säkerhetskopior.** Först rättning: befintlig fil ersätts bara av färdigt, kontrollerat paket.
`ProjectPackage.verify` (manifest, storlekar, zip-slutpost; avkortad fil upptäcks). Manifest får `complete`/`missing`.
`engine/Backups.kt`: mapp via SAF (`OpenDocumentTree`), **ny fil per kopia** (namn + tid), inga namnbyten antas,
misslyckad kopia raderas, tidigare rörs aldrig. Status på kortet (tid / ändrad sedan / aldrig) via innehållsfingeravtryck
(inte spartid). Tappad mappbehörighet → "välj igen". Ofullständigt paket sägs vid import.
Tester: `ProjectPackageTest`, `BackupsTest`, `ProjectsTest`. **På vivo:** kopia 1,3 GB, zip verifierad på datorn,
återställd som nytt projekt med identisk tidslinje som spelade från paketets filer; borttagen mapp gav rätt fel.

**PR 4 / 8.2 – Tidsmappning.** Gemensam `Seg.sourceAt` för förhandsvisningens lager och exporten.
`PreviewExportTimingTest` (trelagersprojekt, bildruta för bildruta; fångar avsiktligt fel). Hittat och rättat:
Float-spelhuvudet gjorde att ett övre lager började en bildruta för sent (`playheadSec`).

**PR 5 – Projektstart och förval.** Nytt projekt: namn + Som klippet / Stående / Liggande, resten under Avancerat
(inget borttaget, inget tappas vid byte). Exportförval Bäst kompatibilitet / Mindre fil / Hög kvalitet (högsta nivån,
enligt användarens beslut) / Anpassat, med uppskattad storlek, via `ExportPlan`.

**PR 6 – Gester.** Inställningar → Hjälp: alla gester med motsvarande knapp, "Visa tipsen igen". Test: `SettingsHelpTest`.

**PR 7 – Livscykel (delvis).** `ExportService` (foreground, `mediaProcessing`/`dataSync`, avisering med förlopp),
`ExportGuard`: avbruten export visas i dialog vid nästa start (kontrolleras en gång per process – aktiviteten kan komma
tillbaka med sparat tillstånd), temporärfiler rensas. **På vivo:** utan tjänsten bromsades exporten i bakgrunden och
10-bitsförsöket fick muxerfel; med den blev 4K 10-bit HEVC klar på 12 s i bakgrunden; force-stop → dialog, ingen fil.
Ej gjort: låg lagring, indragen medieåtkomst, skadad fil, VFR, blandad SDR/HDR, testmatris.

**PR 8 – Media3.**
- 8.1 `docs/media3-rendering.md` (alla vägar, 13 verifierade risker). KLIPP.md kompletterad (sen bildruta visas i
  stället för att kastas).
- **Look på hela bilden** (användarens beslut): förhandsvisningen lade tidslinjens Look per lager, exporten på hela
  bilden. Nu, Android 13+ och när något är staplat: huvudspelare + övre lager under en AGSL-`RenderEffect`
  (`ProgramStack`, `GpuGrade.lookRenderEffect`), huvudspelaren byggs utan Look, titlar ritas utanför (som exporten).
  Mätt: mitt i övergång samma medelljushet som exporten (17,0 / 17,0).
- Bakgrundsfiler som bara gör uppspelningen lättare (nodcache, förrenderat, proxy) byts in när uppspelningen stoppar;
  frysbilder/baklänges direkt (bilden är fel utan dem).
- `RenderCache`-nyckeln innehåller LUT-innehåll och proxy/original (test `RenderCacheTest`).
- Diagnostikvyn: Look-väg, visade/sena bildrutor, vad som syns vid spelhuvudet (förrenderat/live, proxy/original).
- 8.4: export 10-bit = HEVC Main 10, BT.709, **verklig** 10-bit (979 nivåer, 75 % ej multiplar av 4); kompatibel =
  H.264 High 8-bit BT.709. HDR ej testat (inget material).
- 8.5: 28 cykler (spela/sök/sidor/projekt/bakgrund): trådar och grafikminne ökar ~12 cykler, sedan konstant
  (102 trådar, ~176 MB, PSS 580–620 MB). Antal avkodare omätt.
- **8.2/8.3 med bildrutekodade klipp** (`tools/framecode/`, generator + avläsare för skärmbild, skärminspelning och
  export). Fyra fel hittade och rättade:
  1. Pausat där ett klipp börjar på ett *övre* spår visade huvudbilden nästa bildruta (`CutSeek` tog snitt från alla spår;
     nu bara huvudlagrets, `CutSeek.cutsOf(bottom)`).
  2. Övre lagrets sista bildruta visade bildrutan före (objekt slutade exakt vid segmentet; nu dold svans 0,2 s som exporten).
  3. Omritning knuffade 1 ms framåt från en bildrutas egen tid → nästa bildruta (övre lagrets första ruta, huvudbilden).
     Nu bakåt och tillbaka (`Repaint`).
  4. **Exporten upprepade var femte bildruta medan ett övre lager var tomt**: Media3 fyller luckor i fast 30 fps
     (`SequenceAssetLoader.BLANK_IMAGE_FRAME_RATE`). Nu svart bild i projektets bildfrekvens (`videoGap`, `BlankFrame`).
     Efter: alla 6000 bildrutor i båda lagren rätt.
  Mätt utan fel: pausat exakt rätt bildruta på 17 tider inkl. kanter och snitt; 230 s uppspelning: lagren inom en
  bildruta, ingen växande drift; övre lagret tappar inga bildrutor.

## 3. Kvarstående fel och risker

- **Snitt i huvudlagret under uppspelning (reproducerbart, ej rättat):** sista bildrutan före snittet visas ~80–100 ms,
  nästa klipps första bara 15–35 ms, dess andra saknas. Skarvlagret visar 24/25 bildrutor. Ett försök att föra lagrens
  klocka framåt mellan huvudspelarens positionsuppdateringar gav inte bättre resultat (spelarna turades om) och togs bort.
  Mätmetod: `tools/framecode` + `screenrecord`, se `docs/media3-rendering.md`.
- Det tidigare rapporterade svarta blinket syntes inte (inga svarta bildrutor i tre inspelningar).
- Övre lager rättas först vid >200 ms drift under uppspelning (`LayerSync.SEEK_MS`); fungerar på vivo, omätt på svagare.
- Proxy- och nodcachekodning pausas inte under uppspelning; ingen policy för avkodare när förhandsvisning och export krockar.
- Ej testat: HDR, Android ≤ 12 (Look-läget finns bara från 13), svagare telefoner/Mali, trelagersprojekt på telefon,
  geometri/overlays med separata toleranser, stabiliserade klipp vid skarvar (risk 6, hypotes om skarvlagrets klocka).

## 4. Slutsats om arkitekturen

**Det finns ingen gemensam klocka.** Huvudspelaren (CompositionPlayer) spelar bottenlagret; varje övre lager är en egen
ExoPlayer som följer huvudspelarens rapporterade position och bara rättas med sökningar. Pausat är det nu exakt;
spelande håller det inom en bildruta på vivo, men för att spelarna går lika fort, inte för att de är låsta.

Jag har **inte** kommit fram till att dagens väg är den bästa. Varje skillnad mellan förhandsvisning och export har
behövt hittas och lappas för sig (de fyra felen ovan, Look-effekten), och felet vid snitt är en direkt följd av två
spelare som turas om. Alternativet är att förhandsvisningen komponerar lagren i CompositionPlayer som exporten:
en klocka, samma väg, och skarvlager/förrullning/rättningar blir onödiga – risken är prestanda med flera 4K-lager och
film samt hur moget Media3 1.11:s stöd är i förhandsvisning.

**Rekommendation:** ingen ombyggnad på antaganden. Gör en prototyp på egen gren och mät med samma bildrutekodade tester
plus bildfrekvens/tappade bildrutor med 2–3 4K-lager och film på vivo och OnePlus 12. Klarar den sig – byt väg. Annars
vet vi varför dagens väg behövs, och felet vid snitt rättas inom den.

## 5. Beslut tagna av användaren under arbetet

- Look ska ligga på hela bilden (som exporten) → genomfört.
- "Hög kvalitet" = högsta bithastighetsnivån → genomfört.
