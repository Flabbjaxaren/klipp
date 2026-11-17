# N1: filmens pixelstorlekar följer exporten

Gren `n1/pixel-units`. Inte i betan, inte pushat. Testbyggen `0.19.3-beta.1-designtest.163-n1` … `169-n1` (OnePlus och vivo);
164–169 lägger till testkrokar (bara testbyggen: `frame_probe`, `film_test`) och referensstorleken från projektet (165).

| Commit | Innehåll |
|---|---|
| 8ccd078 | N1: referensstorlek (originalets kortsida) genom kedjan; mask och skanneroskärpa skalas; kornoskärpan variansmatchas |
| 969c295 | GPU: maskens horisontella pass lagras som detalj när bilden är mindre än exporten (rättar kornfel före kodning) |
| 80d041e | Färgsidans stillbild: programmen nycklas även på referensstorleken |
| 7953b57 | GPU-tester (instrumenterade): skalade storlekar, korn före kodning, mask på korn, dragstillbild |
| 3341449 | Referensstorleken från projektets mediauppgifter (finns direkt vid kallstart); kodekprovet bara som reserv; en sen storlek bygger om spelarna en gång; frysta bildrutor = stillbildens storlek; förrenderingens nyckel |

N1 består av två delar med olika beläggsgrad – de redovisas var för sig.

## Referensstorlek – definition

Ordet "originalet" har betytt olika saker; här gäller:

- **Källfil**: klippets fil i projektet (den användaren importerade). Inte proxyn, inte nodcachens fil, inte en förrenderad bit.
- **Referensstorlek**: källfilens kortsida i pixlar, som projektet anger den (mediauppgiften från importen; kodekprovet
  bara om projektet saknar den). För en frusen bildruta: stillbildens egen kortsida (bilden görs ur källfilen och exporten
  kör filmen på den).
- **Arbetsbild**: bilden filmen faktiskt körs på. Exporten kör klippets effekter på källfilens avkodade rutor *före*
  anpassningen till sekvensens storlek – arbetsbild = källfil, oavsett projektets storlek (t.ex. 4K-klipp i 1080-projekt)
  och oavsett exportens upplösning (en 1080-export av ett 4K-projekt kör filmen i 4K och skalar sedan). Nodcachen likaså.
  Förhandsvisningen kör filmen på källfilen nedskalad till förhandsstorleken, eller på proxyn (som också kan skalas ned).
- **Skala** = arbetsbildens kortsida / referensstorleken. Export och nodcache: 1 (nodcachen loggar ref 0 – samma sak).
  Förhandsvisning 1080 av en 4K-källa: 0,5 – även via en 2160p- eller 1080p-proxy.
- Okänd referensstorlek (bara om projektet saknar storlek och provet ännu inte gett den): ingen nedskalning och skala 1 –
  exportens eget utseende – utom via proxy, där filmen tillfälligt körs oskalad på proxyns storlek. När storleken kommer
  byggs spelarna om en gång (testat, se nedan).

## Del A – skärpa: oskarp mask och skanneroskärpa (belagd)

σ · (bildens kortsida / originalets kortsida). Exporten kör filmen på originalet: skala exakt 1.

| Belägg | Före | Efter |
|---|---|---|
| CPU, halv storlek mot export↓: medelfel / mörk kant (avvikelse) | 0,641 / 13,38 | 0,275 / 0,37 |
| CPU, skanneroskärpa 1,5: medelfel | 2,057 | 0,198 |
| GPU (OnePlus, före kodning): GPU mot CPU i skala 0,5 | – | medel 0,0010, p99 0,0020; mörk kant 3,36 mot 3,50 |
| GPU: masken pixel för pixel mot CPU (3 och 5 vikter) | – | medel ≤ 0,0006; skärpningsenergi x och y 1,000 |
| GPU genom H.264 (förrenderad bit mot 4K-export↓): mörk kant | 5,26 (export 3,57) | 3,71 |
| Export: CPU (tre filmer) och 4K-export på GPU 161 / 162-n1 / 163-n1 | – | bitidentisk |

## Del B – kornoskärpan (variansmatchad – godkänd före kodning på OnePlus, inte färdigprövad)

En sub-pixel-oskärpa kan inte skalas rakt (σ/2 gav 25 % för starkt korn). Den väljs i stället så att kornets varians
efter oskärpa och mask blir exportens nedskalade. **Samma RMS betyder inte samma kornutseende** – därför mättes också
struktur.

Före kodning (FilmN1GpuTest, OnePlus 12, flyttal, fem toner × sex rutor, halv storlek mot full↓):

| Ton | RMS-kvot före → efter | Spektrumband (6) efter | Autokorrelation x/y efter (export↓) |
|---|---|---|---|
| 0,15 | 0,690 → 0,997 | 0,95–1,00 | 0,116/0,114 (0,138/0,140) |
| 0,30 | 0,715 → 1,007 | 0,95–1,03 | 0,111/0,112 (0,139/0,139) |
| 0,45 | 0,728 → 1,021 | 0,95–1,07 | 0,103/0,105 (0,140/0,138) |
| 0,60 | 0,740 → 1,016 | 0,97–1,05 | 0,111/0,112 (0,140/0,137) |
| 0,75 | 0,744 → 1,020 | 0,98–1,05 | 0,116/0,118 (0,138/0,137) |

Före-värdena för spektrum: högsta banden 0,23–0,31 (för slätt). Kravet 0,95–1,05 sattes före mätningen och har inte
ändrats.

**GPU-felet som hittades och rättades (969c295):** utan rättningen gav GPU:n 1,026–1,081 och riktat korn (0,070/0,126
vid 0,75) medan CPU:n gav 0,997–1,000. Utredningen: råkornet är vitt på båda (±0,001); en jämn bild utan korn förblir
jämn; masken ensam stämmer med CPU pixel för pixel; men masken på kornstora signaler med 3 vikter avvek (GPU mot
Klipps mask för hand på samma korn: 1,022/1,042, full skala 1,012/1,002). Det horisontella passet, lagrat som
halvflyttal, förlorade maskens svaga högpass. Efter rättningen: 1,021/1,003, symmetriskt.

**Kvarstående skillnader (redovisas, inte dolda):**
- Förhandsvisningens korn är något finare än exportens nedskalade: autokorrelation ≈ 0,11 mot 0,14 – även på CPU
  (0,118 mot 0,136). Variansmatchningen ger rätt styrka och nära rätt spektrum (±7 %), men inte exakt samma struktur.
- Genom H.264 (förrenderad bit 1080 mot 4K-export↓): kornkvot 1,065 (före N1 0,897). Kodningen höjer kvoten även före
  N1 (0,73 före kodning → 0,897 genom H.264), troligen för att 4K-exporten får färre bitar per pixel – en tolkning,
  inte bevisad.

## Vivo (V2562, Android 17, Adreno) – 2026-11-17

**Före kodning (gpuTest-paketet):** FilmGpuTest 4/4, FilmN1GpuTest 4/4, FilmDragStillTest 1/1, FilmFusedTest 2/3 – samma
befintliga `effectsOnly`-fel med exakt samma siffror som på OnePlus. Kornet, halv mot full↓, efter: 0,997 / 1,007 /
1,021 / 1,016 / 1,020 (toner 0,15–0,75), band 0,95–1,07, autokorrelation 0,10–0,12 mot 0,14 – samma som OnePlus.
(Vivons egen frysfunktion stoppar testprocessen efter 1–3 min – den saknar fönster; den hålls vaken med ett anrop var
5 s. Ett tidigare försök i 320 px gav 1,053 vid 0,60 – brus i en 160×96-bild, inte upprepat i 640 px.)

**Genom H.264 (förrenderad bit 1080 mot 4K-export↓):**

| Bygge | Mörk kant bit / export↓ | Kornkvot |
|---|---|---|
| 161 | 4,82 / 3,27 | 0,733 |
| 163-n1 | 3,83 / 3,27 | 0,817 |

Exporterna 161 och 163-n1: avkodade rutor identiska (framemd5). Kodningen drar kornkvoten åt olika håll på telefonerna
(OnePlus 1,065, vivo 0,817, båda ≈ 1,00 före kodning) – kodaren, inte N1, styr den skillnaden.

**Nodcache:** cache gjord av 161 3,0887; ny cache gjord av 163-n1 3,0887; befintlig cache som överlevt en
appuppdatering (ingen ny rendering i loggen, status "cached" direkt) 3,0887 – samma bild. Nyckeln och filmens JSON är
oförändrade sedan 161 (`NodeCache.kt` utan ändringar), så en 161-fil hittas efter uppdatering. En 161-fil kunde inte
bära över själva uppdateringen i provet: nodcachen **raderar filerna för klipp som inte finns i det öppna projektet**
(`NodeCache.sync`, "clips that no longer cache") – byte till användarens projekt raderade den. Befintligt beteende,
separat fråga.

**Live mot nodcache på vivons skärm – lokaliserat (2026-11-17).** Testbygge 164-n1 har en läskrok (`frame_probe`, bara
TEST_HOOKS) sist i spelarens kedja: bildrutan i flyttal före skärmens skalning. Samma kort, ruta, utsnitt och vy.

| Steg | Mått | Cache | Live |
|---|---|---|---|
| Storlekar | källa 3840×2160 (original, ingen proxy); filmens arbetsbild live 1920×1080 ref 2160; cachen 3840×2160 ref 0; utgående 1920×1080 (float); vy SurfaceView 1208×680, buffert 1208×680 | | |
| Utan film (kontroll) | visningsvägen: skärm = rutan box-skalad | – | 0,0 / 0,0 |
| Film utan korn och glare (masken) | rutor, medelskillnad 0,24 kodvärden (p99 0,81); mörk kant ruta / skärm | 4,03 / 3,0 | 3,85 / 3,0 |
| + glare (korn av) | medelskillnad 0,36; skärm | 3,0 | 3,0 |
| + korn (glare av), ruta 1,6 s | skärm | 3,27 | 2,63 |
| + allt (som tidigare), ruta 1,6 s | skärm | 3,09 | 2,66 |
| Korn, 8 rutor (1,20–1,48 s) | skärm, enkel min | 3,32 ± 0,20 | 3,23 ± 0,31 |
| Korn, 8 rutor | robust (bakgrundsband − mörkaste kolumn) | 3,78 ± 0,15 | 3,56 ± 0,21 |
| Korn, 8 rutor | kolumnerna vid kanten (e−1 / e) | 198,04 / 127,32 | 199,67 / 125,48 |

Plana fält (bakgrund, kvadrat, stapel, grått) lika inom 0,1–0,5 kodvärden mellan vägarna – ingen range- eller
överföringsskillnad. Box- och bilinjär skalning av rutorna till vyns storlek ger skärmens värden.

**Var skillnaden uppstår:** i filmeffektens utdata, och bara med korn. Visningsvägen bidrar inte. Det stora skärmvärdet
(2,66 mot 3,09) var kornbrus i ett mått som tar en enda bakgrundskolumn och den mörkaste av ~100 brusiga kolumner;
kornet är deterministiskt per ruta, därför upprepades det exakt. Över åtta rutor: 3,32 mot 3,23, inom spridningen.

**Det som är systematiskt:** kornoskärpan verkar på hela den korniga densiteten (även bilden). N1:s variansmatchade σ
(0,434 i halv storlek, exportens 0,65) är vald för bruset; på bilden mjukar den kanten ungefär hälften så mycket som
exporten nedskalad (CPU, 12 frön: kornets andel av kanten −3,5/+7,1 mot −7,5/+18,8; vivo: −3,5/+4,7 mot −6,7/+9,8).
Hela kanten med korn ligger ändå närmare exporten än före N1: 1,60 mot 7,44 kodvärden (CPU). N1 inför alltså inte felet –
det är en rest av approximationen, ~1,7 kodvärden i kantkolumnerna. Ingen parameter ändrad. Nytt test:
`FilmResolutionTest.theGrainBlursTheImageAsTheExportDoes` (vaktar att resten inte blir större än halva felet före N1).

Sidofynd: filmprogrammet byggdes en gång med ref 0 (oskalat) direkt efter appstart och 0,27 s senare med ref 2160 –
`filmRefOf` beror på `Perf.cachedCodec`, som kan saknas vid första bygget. Övergående här; inte utrett om det alltid
byggs om.

Mätskript (scratchpad `n1gpu/`): `probe.py` (fallen, storlekar, profiler, kontrollerad skalning), `multi.py` (8 rutor),
`profcmp.py` (kantprofiler, kornets andel).

## Nodcachen

Nodcachen renderar originalet i dess storlek (`NodeCache.kt` 300: `effects(null, clock)` utan referens → skala 1) och
efter cachepunkten körs bara noderna efter den live. Allt N1 ändrar är skala < 1; i skala 1 är beräkningen exakt den
gamla (CPU bitidentisk; GPU:s detaljläge bara vid skala < 1). Cachens nyckel och innehåll är därför oförändrade:
befintliga cachefiler är fortfarande rätt, ingen versionshöjning behövs. Spelad cache skalas ned efteråt – den visar
exportens utseende redan före N1; det är live-vägen som N1 rättar.

Prov (OnePlus, eget projekt "Klipp N1 nodecache", 4K-kort med film och nodcache, pausad bild via `adb screencap`):

| Bild | Mörk kant (skärmskala) |
|---|---|
| Cache renderad av 162-n1 | 3,3046 |
| Live, 162-n1 | 3,3736 |
| Cache renderad av 163-n1 | 3,3046 (identisk) |
| Live, 163-n1 | 3,3678 |

Cache gjord av 161: se vivo ovan. (Ett andra klipp med cache återanvände samma cachefil – nyckeln täcker hela den 4 s långa
filen – och räknas inte.)

## Dragförhandsvisningen (färgsidans stillbild)

`GlStill` kör uppspelningens program på en stillbild. Testet visade att den återanvände sitt filmprogram oavsett
referensstorlek (en annan klipps skala följde med) – rättat i 80d041e. Efter: stillbilden = uppspelningens film i samma
skala (medel 0,0013, p99 0,0021) och skiljer sig från den oskalade (0,0028).

## Kallstart och sen metadata (2026-11-17)

Före: `filmRefOf` läste `Perf.cachedCodec` – ett minnescache som är tomt vid kallstart och fylls i bakgrunden (proxyprovet,
nodcachen); när det fylldes byggdes inget om, så en kedja byggd utan storlek kunde stå kvar oskalad. Förhandsvisningens
nedskalning hade samma beroende.

Nu (`SourceSizes`): storleken kommer från projektets mediauppgifter vid varje uppdatering, innan spelarna byggs; provet
fyller bara luckor. En storlek som efterfrågats och saknats höjer en revision **en gång** när den kommer; den revisionen
ingår i huvudspelarens ombyggnadsnyckel och i övre lagrens nycklar (ett misslyckat prov ändrar inget, ingen ombyggnad per
ruta). Dragförhandsvisningen läser storleken vid varje drag. Förrenderingens nyckel innehåller referensen.

Fel som hittades under provet: första versionen höjde revisionen men huvudspelarens nyckel saknade den – spelaren
byggdes inte om. Rättat (nyckeln), och provat på telefonen med en testoperation som glömmer storleken och ger tillbaka den
efter 6 s: ombyggnad till ref 0, sedan till ref 2160.

| Prov (vivo 165-n1, OnePlus 168/169-n1) | Resultat |
|---|---|
| Kallstart (appen stoppad), 4K + 1080 på V1, 1080 i V2 | första bygget: 4K ref 2160 (skala 0,5), 1080 ref 1080 (1,0), övre lagret 1080 – ingen ref 0 |
| Projektbyte fram och tillbaka | samma |
| Proxy (eget 4K60-klipp, 2160p-proxy), varm och kallstart | arbetsbild 1920×1080, ref 2160 |
| Nodcache | renderar 3840×2160, skala 1 |
| Dragförhandsvisning (gamma-hjulet) | 4K-klippet ref 2160, 1080-klippet ref 1080 (stor stillbild och miniatyr) |
| Sen storlek (OnePlus, testoperation) | ref 0 medan okänd, ref 2160 efter 6 s – huvudspelaren ombyggd båda gångerna |
| Enhetstester `SourceSizesTest` | 4/4: storlek direkt, sen storlek bygger om en gång, misslyckat prov aldrig, kedjan följer |

## Kostnad (2026-11-17)

Samma bygge, material och parametrar; före N1 = samma kedja med referens 0 (testkrok). Inga skärminspelningar.

**Filmkedjans GPU-tid** (`FilmN1Benchmark`, 1920×1080, uppvärmning, 10 växlade blockpar, timer-queries; termisk status 0):

| | vivo före → N1 (ms/ruta) | skillnad per par | OnePlus före → N1 | skillnad per par |
|---|---|---|---|---|
| Standard (grain, glare) | 11,66 → 12,03 | +0,37 ± 0,18 | 14,49 → 14,77 | +0,28 ± 0,02 |
| Kraftig grain | 11,85 → 12,29 | +0,44 ± 0,20 | 14,57 → 15,27 | +0,71 ± 0,01 |
| Bara masken | 9,47 → 9,71 | +0,25 ± 0,18 | 12,24 → 12,49 | +0,26 ± 0,01 |

+2–5 %, belagt; huvuddelen i sista passet (detaljläget läser två texturer per vikt: vivo `final` 0,58 → 0,74 ms).
Inte optimerat.

**Verklig uppspelning** (OnePlus, 4K-kort ×3 med standardfilm, live – förrenderingen hållen och en filmvariant som ingen
förrenderad bit matchar, spåret bekräftar ref 0/2160 per uppspelning; 4 + 4 uppspelningar à 8 s, växlade):

| | rutplatser | sena | marginal till visning, median / p10 | kedjans takt |
|---|---|---|---|---|
| Före N1 | 572/572 | 1 | 42,0 ± 0,3 / 36,4 ms | 24,4–24,6 fps |
| N1 | 572/572 | 0 | 42,2 ± 0,3 / 36,5 ms | 24,5–24,6 fps |

Ingen skillnad i verklig uppspelning. Grafikminne (meminfo Graphics) 34–37 MB i båda, sprider sig mer mellan
uppspelningar än mellan lägena; N1 lägger inte till texturer. Termisk status 0 hela tiden, skal 31 → 39 °C.
(Två tidigare försök var ogiltiga och räknas inte: testväxeln byggde inte om spelaren, resp. läskroken var kvar påslagen.)

## Praktisk bildjämförelse (OnePlus, 2026-11-17)

Egen 4K-provbild (syntetisk: tre hudtoner med skuggning och porstruktur, mörker, fina texturer, hårda kanter), standardfilm
med normal och kraftig grain (mängd 2, storlek 1,5), sex rutor per väg. Referens: **exportens filmkedja före kodning**
(förhandsvisning full upplösning för sessionen, källfilen, filmen i 3840×2160 skala 1), nedskalad 2×2. Proxyer avstängda för
sessionen utom i proxykolumnen. Kornet = förändringen över tid (bilden står still).

| Fält (normal grain) | live N1: korn ×, struktur, detalj × | live före N1 | via proxy (N1) | förrenderat (H.264) | export (H.264)↓ |
|---|---|---|---|---|---|
| hud | 1,00, 0,12 (ref 0,14), 1,01 | 0,71, 0,42, 0,60 | 1,01, 0,13, 0,97 | korn 0,43 | korn 0,52 |
| mörker | 1,03, 0,10 (0,13), 1,04 | 0,83, 0,26, 0,77 | 1,06, 0,13, 0,97 | 0,25 | 0,35 |
| texturer | 1,00, 0,12 (0,14), 1,21; medel −5,8 | 0,73, 0,41, 0,64; medel −6,3 | 1,11, 0,14, 1,20 | 0,79 | 0,60 |
| kanter | 0,94, 0,07 (0,07), 1,00; medel −0,4 | 0,87, 0,20, 1,19; medel −3,2 | 1,00, 0,09, 1,00 | 0,42 | 0,23 |

Kraftig grain ger samma bild (live N1 korn ×0,91–1,01, detalj ×0,99–1,19). Bladen (beskärningar 1:1 och hela bilden i
telefonens visningsbredd 1080): scratchpad `n1gpu/cmp/op3/sheet_*.png`.

Slutsatser:
- Live med N1 ≈ exportens filmkedja i korn (styrka och struktur) och skärpa i hud, mörker och kanter; före N1 var kornet
  svagare och grövre och bilden mjukare, kanterna 3 kodvärden fel.
- **Kvarstår (före och efter N1):** fina texturer (1–3 px) blir 5,5–6,3 kodvärden mörkare i förhandsvisningen och har 20 %
  mer detaljenergi – filmen är olinjär och körs efter nedskalningen (medelvärdet av filmen ≠ filmen av medelvärdet).
- **Kodaren:** förrenderade bitar och exportfilen tappar mycket korn (×0,15–0,79 mot filmkedjan) – live visar mer korn än
  den färdiga H.264-filen kommer att ha. Det gällde före N1 också (då av en slump närmare). Separat fråga (exportens bitar).
- Exportfilen läses ~1,7 mörkare på Mac (ffmpeg tv→pc), känd mätartefakt.

## Gränser och verifierade skalor

Verifierat: skala 0,5 (4K-källa i 1080-förhandsvisning: CPU, GPU före kodning, telefonernas uppspelning, 2160p- och
1080p-proxy, dragstillbild), skala 1 (export bitidentisk, nodcache, 1080-källor). Inte verifierat: förhandsstorlek 720 (skala
1/3 av 4K) och 1440 (2/3), porträttmaterial, källor större än 4K, HDR, frysta bildrutor på telefon (ändringen är
kodgranskad och enhetstestad via kedjan, inte uppmätt), miniatyrer (320×180, skala 0,08 – bara stämplade, inte bedömda).

Kvarstående avvikelse (dokumenterad, inte rättad i detta pass): kornoskärpan är vald för kornets styrka; på bilden mjukar
den kanter ungefär hälften så mycket som exporten (rest ~1,7 kodvärden vid hårda kanter med korn; före N1 7,4), och
kornet är något finare (autokorrelation 0,11–0,12 mot 0,14). Ingen uppdelning av bild- och brusoskärpa.

## Tester

- Enhetstester (slutlig kod, ad94e73 + dok): 665 – 664 godkända, 0 misslyckade, 1 överhoppat (`FilmCpuRenderTest.renderCases`,
  sedan tidigare).
- Instrumenterade i `gpuTest`-paketet, OnePlus (169-n1): FilmGpuTest 4/4, FilmN1GpuTest 4/4, FilmDragStillTest 1/1,
  FilmFusedTest 2/3. Vivo (163-n1-koden): samma. **Baslinjefel:** `FilmFusedTest.effectsOnly` faller med samma siffror
  utan N1 (12 708 mot 12 484 värden utanför) – befintligt, separat uppgift.
- Kort regression (`tools/regress`, OnePlus 169-n1, R1, R3, R4, R5, upprepning 2): 28/30 i första körningen – de två
  missade var R4:s timing (stabilisering + nodcache + film; 4 resp. 7 saknade av 220, 12–15 sena), körningen som byggde
  R4:s tidslinje och nodcache från början. Omkörd två gånger, en med oskalad film (som före N1) och en med N1: 7/7 båda,
  0 saknade och 0 sena. Orsaken till första körningens fel är inte fastställd (inte upprepad; ingen skillnad mellan lägena).
- Vivo: inte omprovad efter 165-n1 (bortkopplad); OnePlus bar de sista proven.

## Kandidatunderlag

Klart för beslut: referensstorleken (definierad, kallstart och sen metadata lösta och provade), kostnaden (+2–5 % GPU i
filmkedjan, ingen skillnad i verklig uppspelning), bildjämförelsen (live N1 ≈ exportens filmkedja; före N1 tydligt sämre).
Kvar att väga in, utan rättning i detta pass: kornoskärpans rest vid kanter, något finare korn, fina texturer mörkare i
förhandsvisning (före och efter N1), kodarens kornförlust i export och förrenderat (separat fråga), ej verifierade skalor
(720/1440, porträtt, HDR, frysta bildrutor på telefon). Baslinjefel: `FilmFusedTest.effectsOnly`. Betan orörd.

## Intern kandidat 0.19.3-beta.2 (versionCode 99) – 2026-11-17

Del A och B godkända för en intern kandidat (inte den frysta betan 0.19.3-beta.1, som är orörd). N1-grenen sammanförd
i `design-test` (merge 366b3dd); kandidaten är `design-test` med N1, versionen höjd (f5ac1b3). Ingen ytterligare
kornmodell, kodekoptimering eller texturrättning.

**N1-specifikt fel hittat vid kandidatgranskningen och rättat (a1801f4):** testverktygen `frame_probe` och `film_test`
låg i bryggan även i release-bygget – i `listOfNotNull(...)` täckte villkoret `TEST_HOOKS` bara det första verktyget.
Bryggan är avstängd tills användaren slår på den, men `film_test` kunde ändra spelarens läge för sessionen. Kontroll i
det byggda paketet: release-dex saknar nu `frame_probe`, `film_test` och `export_latch`; testbygget har alla tre.

Byggt i ett rent worktree på 413c8e2 med nybyggd brygga (cargo ndk som i CI, inga hemsökvägar i `.so`), signerat med
release-nyckeln (cert SHA-256 58c86a6f…c182):

| Paket | versionName | SHA-256 |
|---|---|---|
| se.gendut.klipp | 0.19.3-beta.2 | 5413b834adaba80e660f0d442fcb903b541f7c356c6c12d1c5be677bf9a0dabb |
| se.gendut.klipp.designtest | 0.19.3-beta.2-designtest.171 | f4e8a14953b823f010b892b1ffd6710b53c76091a39f5ea4e06f056286e11fce |

Prov på kandidatkoden:
- Enhetstester (`testDebugUnitTest`, 413c8e2): 665 – 664 godkända, 0 misslyckade, 1 överhoppat (`FilmCpuRenderTest.renderCases`).
  En första körning (366b3dd, medan bryggan och APK:erna byggdes parallellt) föll på `ProjectsTest.withoutAFolderTheMenuOffersToChooseOne`
  (en Compose-nod hittades inte); klassen ensam 3/3 och hela sviten utan last godkänd två gånger – inte N1, troligen last.
- OnePlus, testbygget 171 installerat som uppdatering: kallstart → projektet med 4K-kort ger `ref 2160` från första
  programmet (ingen ref 0). Kort regression R1, R3, R4, R5: 33/33 (R4:s timing 0 saknade, 0 sena). Instrumenterade:
  FilmGpuTest 4/4, FilmN1GpuTest 4/4, FilmDragStillTest 1/1, FilmFusedTest 2/3 (`effectsOnly`, baslinjefelet, samma siffror
  12 708 mot 12 484).
- Release-APK:n själv är inte installerad på någon telefon (den skulle uppdatera användarens riktiga app) – kontrollerad
  statiskt: paket, version, signatur, inga testverktyg.
- Vivo: väntar (bortkopplad) – återställs först, sedan samma rökprov.

Inget pushat, ingen release.
