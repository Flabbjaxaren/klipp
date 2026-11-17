# N1: filmens pixelstorlekar följer exporten

Gren `n1/pixel-units`. Inte i betan, inte pushat. Testbygge `0.19.3-beta.1-designtest.163-n1` (OnePlus och vivo); 164-n1 = 163-n1 + läskroken (bara testbyggen).

| Commit | Innehåll |
|---|---|
| 8ccd078 | N1: referensstorlek (originalets kortsida) genom kedjan; mask och skanneroskärpa skalas; kornoskärpan variansmatchas |
| 969c295 | GPU: maskens horisontella pass lagras som detalj när bilden är mindre än exporten (rättar kornfel före kodning) |
| 80d041e | Färgsidans stillbild: programmen nycklas även på referensstorleken |
| 7953b57 | GPU-tester (instrumenterade): skalade storlekar, korn före kodning, mask på korn, dragstillbild |

N1 består av två delar med olika beläggsgrad – de redovisas var för sig.

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

## Tester

- Enhetstester på grenen: 660, 659 godkända, 0 misslyckade, 1 överhoppat (sedan tidigare).
- Instrumenterade i `gpuTest`-paketet (eget app-id, installeras och tas bort av gradle), OnePlus: FilmGpuTest 4/4,
  FilmN1GpuTest 4/4, FilmDragStillTest 1/1. FilmFusedTest 2/3 – `effectsOnly` faller **även utan N1** (samma siffror
  på `design-test`: 12 708 mot 12 484 värden utanför) – ett befintligt, marginellt villkor, separat uppgift.

## Inte klart

1. Kornoskärpans verkan på bilden i halv storlek (rest ~1,7 kodvärden vid kanter) – godtas eller delas oskärpan för bild och brus.
2. Kornets struktur (autokorrelation 0,11 mot 0,14) – godtas eller kräver en modell som matchar spektrum, inte bara
   varians.
3. Beslut om N1 tas in i en ny kandidat, helt eller bara del A.
