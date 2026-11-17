# Rapport: exporten överlever att redigeringsskärmen stängs

Skriven 2026-11-17. Gren `design-test`, commits `d7de0fa..8a31902` (ingen release). Avsnitt 8: samma bygge på vivon.
Telefon: **OnePlus 12 (CPH2581), Android 16 (SDK 36), CPH2581_16.0.10.601(EX01)**, testpaketet
`se.gendut.klipp.designtest`. Slutligt telefonbygge: **0.19.2-designtest.161** (commit `13a92a4`, kod `ff5518b`).
Bygger på föregående pass (kopiering till Movies och återhämtning efter processdöd, commits `10ef11e..4ec8182`).
Verktygen beskrivs i `tools/regress/README.md`.

## 1. Reproducerat beteende före ändringen (bygge 158)

Testprojekt "Klipp endurance 156", sekvens **X2 lifecycle export**: 36 klipp à 7 s med filmnod, 252 s = 6 300 bilder
vid 25 fps. Exporten kodar i cirka 25 s, så varje händelse lades **under kodningen** (efter att förloppet nått ≥ 4 %),
inte efter ett snabbt avslutat jobb. Verktyg: `tools/regress/lifecycle_check.py` (logg i sessionen: `life158/`).

| Fall | Aktivitet | Process | Foreground-tjänst | Exporten | Sparat | Besked vid återkomst |
|---|---|---|---|---|---|---|
| HOME | kvar | kvar | kvar | fortsatte | 1 hel fil (252,000 s, 6 300 bilder, avkodas utan fel) | toast (i bakgrunden) |
| Back | kvar (appen till bakgrunden) | kvar | kvar | fortsatte | 1 hel fil | toast (i bakgrunden) |
| Skärmlås | kvar | kvar | kvar | fortsatte | 1 hel fil | toast (i bakgrunden) |
| **Svep bort i Senaste** | **förstörd** | kvar | **stoppad** | **avbruten** | **ingen fil** | "Export interrupted … No file was saved" |

Orsak: exportens coroutine startades i redigeringsskärmens `rememberCoroutineScope`. När aktiviteten förstördes
avbröts scopet och därmed Transformer-jobbet, trots att processen och tjänsten levde. Tjänsten höll bara processen; den
ägde inte jobbet. (Samma sak med `am stack remove`, som är vad ett svep gör.)

## 2. Ändringen och varför

- **Jobbet ägs av exportlivscykeln, inte av skärmen** (`d7de0fa`). Nytt `engine/ExportJobs.kt`. Skärmen begär en
  export (`ExportJobs.start`) med allt exporten läser fryst i förväg (ögonblicksbild, URI-tabell via
  `ProjectController.frozenUris()`, look, mix, graderingar, applikationskontext). `ExportService` startas som
  foreground-tjänst och kör jobbet i **sitt eget scope** först när `startForeground` har lyckats; tjänsten stoppar sig
  när jobbet har slutat.
- **Avbryt** på exportöverlägget (`exportCancel`) avbryter det verkliga jobbet: `transformer.cancel()` under kodning,
  kopieringen till Movies stoppas (kontroll var 64 KiB) och dess ofärdiga post raderas.
- **Androids tidsgräns** (`onTimeout`) avbryter jobbet och stoppar tjänsten direkt. Tjänsten som stoppas utifrån
  (`onDestroy` medan jobbet körs) avbryter också jobbet: ingen oskyddad bakgrundsexport.
- **Nekad foreground-start**: jobbet startas inte alls (varken vid `startForegroundService` eller `startForeground`).
  En dialog säger det med **Försök igen**. Ingen fil, ingen markering, ingen bakgrundsspärr.
- **Avbrottsgränsen**: före publicering ger avbrott "avbruten" och bortstädad post. Efter lyckad publicering (IS_PENDING
  = 0 och markeringen `published`) räknas exporten som sparad även om ett avbrott kommer efteråt; filen raderas aldrig.
  Kopieringen är inte längre `NonCancellable`; bara stegen efter publiceringen (markering, slutbesked, städning) är
  icke-suspenderande och kan inte avbrytas halvvägs.
- **Ett jobb i taget**: `start` vägrar ett andra medan ett körs (dubbeltryck, återkommande skärm). Sena förloppsanrop
  från ett avslutat jobb ändrar inget och lägger inte upp någon notis igen (jobb-id + fas).
- **Resultatbesked**: slutar jobbet medan en skärm är *resumed* visas det där (toast) och kvitteras. Annars blir det
  `ExportGuard.notice` (nu Compose-state) och visas som dialog nästa gång redigeraren syns: sparad, avbruten eller
  misslyckad med felet.
- **Testpunkter bara i testbygget, som byggkonfiguration**: `BuildConfig.TEST_HOOKS` är `true` endast för `designTest`
  (`false` i release och debug, kontrollerat i genererad `BuildConfig.java`). Bryggverktyget `export_latch` listas bara
  då. Testpunkter: håll kopieringen halvvägs, håll direkt efter publicering (`997875a`), neka nästa foreground-start,
  anropa `onTimeout`, låt kodaren fallera vid 5 % (`ff5518b`). `export_status` (bara läsning, alla byggen med bryggan
  på) visar jobbet, senaste slut, tjänst, markering och bakgrundsspärr.

## 3. Ägarskap och återanslutning

`ExportJobs` (processobjekt) håller status: `STARTING → RUNNING → SAVED | FAILED | CANCELLED | DENIED`, med förlopp och
skäl. `ExportService` äger körningen: jobbet lever i tjänstens scope och dör med tjänsten, aldrig med en aktivitet.
Redigeringsskärmen *observerar* `ExportJobs.status`: en ny skärm (efter svep, ny aktivitet i samma process) visar samma
jobb med förlopp och Avbryt, håller förhandsvisningen stilla medan jobbet körs och laddar om den efteråt.
`MainActivity.onResume/onPause` sätter `ExportJobs.watching`, som avgör toast eller dialog vid slutet.

## 4. Resultat per acceptanskriterium (bygge 161 om inget annat sägs)

### A. Fortsatt export – GODKÄNT på telefon

`lifecycle_check.py`, sekvens X2 (252 s, 6 300 bilder). Varje fall: samma jobb-id före och efter händelsen, fas
`running` och stigande förlopp (ingen omstart; jobb-id ökar med exakt ett per fall), exakt **en** ny fil, 252,000 s,
6 300 bilder, full FFmpeg-avkodning utan fel, ingen kvarlämnad pending-post, tjänsten borta efteråt.

| Fall | Aktivitet | Process / tjänst under jobbet | Besked vid återkomst |
|---|---|---|---|
| HOME | kvar | kvar / kvar | dialog "Export saved" |
| Back | kvar (appen i bakgrunden) | kvar / kvar | dialog "Export saved" |
| Skärmlås | kvar | kvar / kvar | dialog "Export saved" |
| Svep bort i Senaste (bara testappens kort; inga andra uppgifter försvann) | förstörd | kvar / kvar | dialog "Export saved" |
| `am stack remove` | förstörd | kvar / kvar | dialog "Export saved" |
| Ny skärm under kodningen | förstörd, sedan ny | kvar / kvar | samma jobb visas pågående med Avbryt; toast "Saved" vid slut |

Android dödade inte processen i något fall på denna telefon.

### B. Avbrott och fel

| Fall | Telefon (161) | Enhetstest |
|---|---|---|
| Avbryt under kodning | GODKÄNT: slut på 0,2 s, ingen fil, "cancelled (user)", bakgrundsarbete återupptaget | `theUsersCancelReachesTheJob` |
| Avbryt under kopiering | GODKÄNT: pending-post fanns, avbrottet släppte spärren, posten borttagen, ingen publicerad fil | `aCancelBeforePublishingIsCancelled`, `theCopyStopsAtACancel` |
| Avbrott efter lyckad publicering | – (inte provat på telefon) | `aCancelAfterPublishingKeepsItSaved` |
| Timeout | GODKÄNT via testpunkt (`onTimeout` anropas som Android gör): jobbet stoppat, ingen fil, "cancelled (timeout)" | `theServiceOwnsTheJobAndItsTimeLimitStopsIt` |
| Foreground-start nekas | GODKÄNT via testpunkt (samma undantagsklass vid samma anrop): dialog med Försök igen, ingen tjänst, ingen fil; Försök igen gav en hel fil | `aRefusedForegroundStartsNothing`, `aRefusalInTheServiceStartsNothing` |
| Processdöd under kodning | GODKÄNT (`export_check`): ingen fil, ingen notis, "Export interrupted" | `anExportCutOffIsReportedOnceAndItsFilesGo` |
| Processdöd under kopiering | GODKÄNT (`copy_check`): halvfilen borttagen vid nästa start, "Export interrupted" | `theProcessDyingInTheCopyLeavesNoHalfFile` |
| Processdöd direkt efter publicering | GODKÄNT (`copy_check`, testpunkt efter publicering): filen kvar, 56,000 s, avkodas utan fel, "Export saved" | `theProcessDyingRightAfterPublishingIsReportedAsSaved` |

### C. Resurser och samtidighet – GODKÄNT på telefon

Efter **sparad**, **avbruten** och **misslyckad** export (kodarfel via testpunkt vid 5 %), sekvens X1:
ingen kodare kvar i processen (resource manager: `c2.qti.avc.decoder` före och efter, ingen encoder), ingen exportnotis,
ingen exportmarkering, `BackgroundWork` släppt och "background work resumed" i spåret, fil bara för den sparade,
förhandsvisningen spelar igen (4 s: 106–110 rutor, högst 1 sen, 0 tappade). Dubbeltryck: en fil (se begränsningar).
Enhetstester: `oneJobAtATime`, `aLateProgressOfAnEndedJobChangesNothing`, `aJobRunsToSavedAndCleansUp`,
`anEndNobodyWatchedWaitsAsANotice`, `aFailureNobodyWatchedSaysTheError`, `theServiceStoppedFromOutsideStopsTheJob`.

Att bakgrundsjobb *startar* när det finns arbete efter en export visades i uthållighetskörningen (bygge 156, samma
`BackgroundWork.export(false)`-väg, nu anropad från jobbet); inte omprovat med eget fall i detta pass.

### D. Regressioner

| Svit | Bygge / kod | Godkända | Överhoppade | Misslyckade |
|---|---|---|---|---|
| Enhetstester `:app:testDebugUnitTest` | `1fa0d32` | 652 | 1 (`FilmCpuRenderTest.renderCases`, överhoppat sedan tidigare) | 0 |
| `tools/regress/selftest.py` | `1fa0d32` | 15 | 0 | 0 |
| `lifecycle_check.py` (14 fall) | 161 | 14 | 0 | 0 |
| `copy_check.py` | 161 | 3 | 0 | 0 |
| `export_check.py` | 161 | 3 | 0 | 0 |
| `regress.py --only R1,R3,R6 --repeat 2` (H.264) | 161 | 21 | 0 | 0 |

`export_check.py` fick en anpassning: en export som slutar i bakgrunden säger det nu med dialog, som verktyget stänger
(första körningen på 160 föll på att dialogen täckte Export-knappen – ett verktygsfel, inte ett appfel).

## 5. Bara enhetstestat respektive provat på telefon

- **Telefon (OnePlus, Android 16)**: alla livscykelfall, Avbryt under kodning och kopiering, timeout och nekad start
  via testpunkter, processdöd under kodning, under kopiering och direkt efter publicering, resurser efter
  sparad/avbruten/misslyckad export, dubbeltryck, R1/R3/R6.
- **Bara enhetstestat**: avbrott efter lyckad publicering, nekad `startForeground` inne i tjänsten, tjänsten stoppad
  utifrån, sena förloppsanrop, `start` som vägrar ett andra jobb, den förfrågan som avbryts innan tjänsten startat,
  städningen av cachens temporära filer (release-signerat bygge kan inte listas).
- **Inte provat alls**: Androids verkliga sextimmarsgräns och en verklig vägran från systemet (testpunkterna anropar
  samma metod respektive kastar samma undantagsklass på samma ställe); Android 10–15.

## 6. Kvarvarande begränsningar

- **Android 10–11**: tjänsttyp `dataSync` (mediaProcessing finns från 15), inga bakgrundsstartsbegränsningar och ingen
  `onTimeout` – nekad start och tidsgräns blir i praktiken inte aktuella där. Pending-poster hittas på Android 10 med
  `MediaStore.setIncludePending` (annan väg än 11+), **inte provat**. Back flyttar fortfarande appen till bakgrunden;
  även en avslutad aktivitet skulle nu lämna jobbet orört (samma väg som svep), men det är bara provat på 16.
- **Tillverkares processdödande**: vissa tillverkare dödar processen vid svep trots foreground-tjänst. Då gäller
  återhämtningen efter processdöd (avbruten eller sparad efter publiceringen), inte fortsatt export. På denna OnePlus
  med Android 16 lever processen.
- **Användaren stoppar appen från Androids lista över aktiva appar** (Android 13+): dödar processen – återhämtningsfallet,
  inte provat.
- **Dubbeltryck på telefon** nådde inte ett andra startförsök (dialogen stängdes av första trycket); vakten i `start` är
  enhetstestad.
- **Ett slutbesked som ingen såg** ligger i minnet. Dör processen innan användaren kommer tillbaka försvinner beskedet
  (filen finns kvar om den sparades). Ingen "klar"-notis visas när exporten slutar i bakgrunden; notisen tas bort.
- Exportens förloppsnotis kräver aviseringsbehörighet; foreground-tjänsten och exporten körs ändå utan den.
- Känt sedan tidigare, utanför detta pass: cirka en sen ruta per uppspelning i vissa projekt (orsak ej utredd).

## 7. Telefonens slutläge

Testappen (bygge 161) överst med användarens projekt "aktuellt" (Project 11 Nov 10:12) öppet på 00:00:05:01; projektets
ändringstid uppdaterades av sparningen vid projektbyte, inget redigerades i det. Bryggan avstängd (ingen socket, ingen
port-forward), stayon av, ingen exporttjänst eller notis, Movies/Klipp innehåller bara användarens fyra ursprungliga
filer (alla testexporter raderade via sina registrerade MediaStore-id). Användarens riktiga Klipp-app och dess uppgift
orörda; svepfallet kontrollerade att ingen annan uppgift försvann. Aviseringsbehörighet och andra telefoninställningar
oförändrade i detta pass. Testprojekten ("Klipp endurance 156" med sekvenserna X1/X2 m.fl.) ligger kvar i testappen.

## 8. Samma bygge på vivo X300 Ultra (V2562, Android 17, PD2547BF_EX_A_17.1.7.2.W20)

Bygge **0.19.2-designtest.161** installerat i testpaketet (uppgraderat från 146, inga data rensade). Inga ström-, sov-
eller väckningsknappar skickades (`lifecycle_check.py --no-power-keys`); skärmlåsfallet kördes därför inte på vivon.
Testprojekt: "Klipp regress" (sekvenserna X1/X2 lades till där) och ett nytt "Klipp tal 161".

| Prov | Resultat |
|---|---|
| Taligenkänning från början till slut (CPU-kontrollen ändrad i `e288734`) | GODKÄNT: biblioteket laddades, KB-Whisper base, 13 s svenskt syntetiskt tal → 29 ord på 1,7 s; av referensens 27 ord 26 rätt (versaler oräknade), "taligenkänningen" blev "talligen klänningen" (modellens fel, inte ett laddningsfel) |
| R1/R3/R6, `--repeat 2` | GODKÄNT 21/21 |
| Export efter att testappens uppgift tagits bort (`am stack remove`, det ett svep gör) | GODKÄNT: aktiviteten förstörd, processen och tjänsten kvar, samma jobb fortsatte, en fil 252,000 s / 6 300 bilder, avkodas utan fel, "Export saved" |
| Ny skärm under exporten | GODKÄNT: samma jobb visas pågående med Avbryt |
| Avbryt under kodning och tillbaka till redigeringen | GODKÄNT: slut på 0,1 s, ingen fil; efteråt ingen kodare kvar, inga extra codecs, förhandsvisningen spelar (105 rutor, 0 sena, 0 tappade) |
| Processdöd under kopieringen | GODKÄNT: halvfilen borta efter omstart, "Export interrupted" |
| Processdöd direkt efter publicering | GODKÄNT: filen kvar och hel (56,000 s), "Export saved" |

Svepet i Senaste-vyn gjordes **inte** på vivon: `KEYCODE_APP_SWITCH` öppnade där hemskärmen (användarens appikoner),
inte Senaste – verktyget hittade inget testkort och rörde ingenting. Uppgiften togs i stället bort med `am stack remove`;
det riktiga svepet är verifierat på OnePlus. På vivon låg en gammal aktivitetspost kvar i rotationshistoriken i
`dumpsys activity activities`, vilket först lästes som en levande aktivitet; verktyget läser nu uppgiftslistan
(`8a31902`), och aktiviteten var förstörd.

Vivons slutläge: testappen med sitt eget projekt "Project 14 Nov 09:44" öppet på 00:00:38:17, bryggan och stayon av,
ingen exporttjänst eller notis, inga testexporter kvar i Movies/Klipp, talprovsfilen borttagen; den riktiga Klipp-appen
och dess uppgift orörda. Aviseringsbehörigheten för testappen var redan beviljad sedan tidigare.

## 9. CI före push

`design-test` på GitHub hade varit röd sedan 2026-11-16 15:56 (före detta pass), av två skäl utanför exportändringen:
`lintRelease` stoppade på två HalfFloat-fel i `VideoStill.kt` (falsklarm: halvfloat skrivs som råa 16 bitar, nu
undertryckt med motivering), och `ProjectsTest.backupsGoToTheChosenFolderAndTheCardTellsHowItStands` föll i CI (kortet
laddas på `Dispatchers.IO`, testet frågade för tidigt; väntar nu). Lokalt efter rättningarna: ProjectsTest 8/8,
`lintRelease` 0 fel, `assembleRelease` klart. Release-APK:n innehåller inga testpunkter (`export_latch`, `deny_start`,
`fail_encode`, testpunkternas texter: 0 träffar i dex; i designTest finns de), bara läsverktyget `export_status`.
