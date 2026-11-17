# N1: filmens pixelstorlekar följer exporten

Gren `n1/pixel-units`. Inte i betan, inte pushat. Testbygge `0.19.3-beta.1-designtest.163-n1` (OnePlus).

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
- Vivo: **inte provad** (inte ansluten under passet).

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

Inte provat: en cache renderad av ett bygge före N1 (161) – OnePlus kan inte nedgraderas utan avinstallation; vivon har
161 och provet görs där. (Ett andra klipp med cache återanvände samma cachefil – nyckeln täcker hela den 4 s långa
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

1. Vivo: GPU-testerna, N1 före/efter och nodcache från bygge 161.
2. Kornets struktur (autokorrelation 0,11 mot 0,14) – godtas eller kräver en modell som matchar spektrum, inte bara
   varians.
3. Beslut om N1 tas in i en ny kandidat, helt eller bara del A.
