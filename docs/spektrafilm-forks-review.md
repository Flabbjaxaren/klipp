# Granskning: spektrafilm-vidareutvecklingar och Klipps filmkedja

Skriven 2026-11-17. Ingen appkod, inga tabeller och ingen betakandidat ändrade. Experimenten ligger på egna lokala
grenar (`exp/grain-seed-check`, `exp/film-resolution`), inget pushat.

## 1. Granskat

| Projekt | Gren / commit | Ursprung |
|---|---|---|
| Klipp | `design-test` 34ed12f (filmkoden = beta 0.19.3-beta.1, 435b454) | spektrafilm `3bb2c2d` (`assets/spektrafilm/SOURCE.txt`, tabeller från `tools/spektrafilm/export_tables.py`) |
| andreavolpato/spektrafilm (original) | main `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc` (2026-06-07); dev `6cd00c8d4f30b5b550f50f4bbd3753c9f2a48507` (06-10); experimental `28bf883e1672e884307edc75852549376e13644e` (07-08); couplers-tuning-iterations `a273e49b4243d19d7447df39c2b9880e9ec35a66` (05-13) | – (main oförändrad sedan Klipps källa; dev/experimental utgår från main~1 83278a5) |
| JamesQiu2005/SpektraLab | main `f61769b6779632497cb9c457945326c8de45436e` (2026-11-16) | ingen git-fork; 20 av 28 profiler blob-identiska med upstream ad5c8d2 (= oförändrade i 3bb2c2d), 8 papper med tillagt `edr_tone_map`; paritet mot en **privat** spektrafilm-fork |
| natcelian/spektrafilm.exe ("The Dyeworks") | main `2b2d5d72edff0e95a5c75a741204dcba1c4c676e` (2026-11-08) | anger både 3bb2c2d och experimental 28bf883; **motorns källkod finns inte i repot** (bara README, NOTICE, bilder, byggskript, patchmappar) |
| turbasvin/spektrafilm-rs | main `9dd59b0380194b93686aaa230a8bb9680aa270a4` (2026-06-15) | färgprofiler blob-identiska med upstream ad5c8d2 (samma som Klipp); s/v-profiler från upstream dev |
| HaaaappyDay/spektrafilm (Android) | main `be9dc011ba621f8d0cb9fdd4ca813cb8b705b61b` (2026-11-15) | git-fork av 3bb2c2d, 90 egna commits, **modellen orörd** (`git diff 3bb2c2d HEAD -- src/spektrafilm` tom); appen applicerar bara en fast 33³-LUT per pixel |

Klipps kedja (CPU `FilmFx`, GPU `FilmGl` i GLSL ES 3 på RGBA16F, AGSL bara för dragförhandsvisningen): exponering via
CAT16-anpassad matris och tc-LUT → highlight boost (referens = bilden nedskalad till ≤128 px) → kamerans oskärpor
(diffusionsfilter, lins, scatter, halation) som NNLS-anpassad Gauss-pyramid mot kärnans MTF (YvV för stora σ) →
densitetskurvor och DIR-couplers med diffusion → korn (Poisson per lager och kanal med underlager; frö från
presentationstiden) → kopiering via 33³-LUT, papperskurva → skanning via 33³-LUT → glare, skanneroskärpa, oskarp mask.

## 2. Rangordnade fynd

### 2.1 Rekommenderas att prova nu

**N1. Pixelbaserade parametrar ger annan skärpa och annat korn i förhandsvisningen än i exporten**

| Fält | Innehåll |
|---|---|
| Fynd | Oskarp mask (σ 0,7 px), skanneroskärpa och kornoskärpans 0,65 px anges i pixlar av den renderade bilden. Förhandsvisning och förrendering renderar filmen i förhandsvisningsstorlek (standard 1080 på kortsidan), exporten i sekvensens. I ett 4K-projekt får förhandsvisningen därför dubbelt så bred mask och kornoskärpa i förhållande till bilden. |
| Källa | Idé: SpektraLab `engine/src/pipeline/pipeline.cpp` `Pipeline::unsharp_sigma()`, `tier_ratio()`, `node_glare()` (σ i pixlar vid full upplösning × renderstorlek / full storlek). Klipp: `FilmFx.Frame.unsharp`, `scanBlur`, `grainSigmaPx`; `FilmGl.configure`. |
| Skillnad mot Klipp | SpektraLab skalar pixelparametrarna med upplösningen; Klipp gör det inte. |
| Nytta | Förhandsvisning = export (prioritet 2). |
| Belägg | **Egen mätning** (experiment 2 nedan): halv upplösning mot nedskalad full – mörk kant vid ljus kant 15,6 mot 2,2 kodvärden, kornets RMS 29 % lägre; med σ skalad 2,6 mot 2,2. |
| Licens | Bara idén (SpektraLab GPL-3.0); egen implementation. |
| Svårighet | Låg (CPU och GPU: σ · renderstorlek / sekvensstorlek). |
| Risk | Ingen för export i sekvensens storlek (oförändrad). Förhandsvisning och förrenderade bitar ändras (blir lika exporten) – cacheversion måste höjas. Export i annan storlek än sekvensen ändras; beteendet ska bestämmas och dokumenteras. |
| Verifiering | Se avsnitt 6. |

**N2. Kornets frö följer renderingens start och väg, inte rutan**

| Fält | Innehåll |
|---|---|
| Fynd | `FilmGl`: `seed = (presentationTimeUs / 1000) · 1103515245 + 12345`; presentationstiden är relativ till kompositionens början. Förrenderade bitar (intervall med förrullning), exporten (från 0) och nodcachen (klippt källklipp) har olika tidsbaser. CPU (`FilmFx.Rng`, en splitmix-ström genom bilden) och GPU (PCG-hash per pixel) ger dessutom olika mönster. |
| Källa | Egen kodläsning och mätning. Idé för samma generator på CPU och GPU: SpektraLab `engine/src/shaders/spk_common.h` (`Rng`, nyckel = pixel, ström, frö) och `tests/gpu_vulkan_grain.cpp` (GPU = CPU bit för bit). |
| Skillnad mot Klipp | Se fynd. Övriga projekt är sämre för video (originalets korn är statiskt eller oseedat i numba; SpektraLab drar nytt korn vid varje rendering; Dyeworks stänger av kornet i förhandsvisningen). |
| Nytta | Samma ruta får samma korn i förrendering, uppspelning, paus och export i samma upplösning; pixelexakta tester med korn på (CPU mot GPU). |
| Belägg | **Egen mätning** på telefon (experiment 1): samma bit två gånger 1,000; bitar från 1,0 s mot 2,0 s −0,004; exporter från 1,0 s mot 2,0 s +0,003; förrenderad bit mot export +0,004. |
| Licens | Egen implementation (PCG finns redan i Klipp). |
| Svårighet | Medel: fröet ska komma från tidslinje- eller källtid (källklipp + källrutans tid, så att även nodcachen stämmer) i stället för kompositionens presentationstid; CPU-vägen ska använda samma hash. |
| Risk | Kornets mönster ändras (statistiken inte); cacheversion höjs; ingen profilfråga. |
| Verifiering | `grain_seed_check.py`: alla par för samma ruta ≥ 0,95, nästa ruta ≈ 0; nytt JVM/GPU-test: CPU-korn = GPU-korn per pixel. |

**N3. Skarpare referenstester (inget utseende ändras)**

| Fält | Innehåll |
|---|---|
| Fynd | (a) Tät kontroll av den bakade färg-LUT:en mot hela kedjan: tusentals punkter (hörn, gråskala, gitter, slumpfärger), ΔE00 medel/p95/max och de sämsta punkterna. (b) Färgvägen GPU mot CPU med ≤ 2–3 kodvärden och GPU-identitet (renderer, highp-bitar) i loggen; udda bildbredd. (c) Kornstatistik på GPU: medel, σ och brusspektrum per tonband mot CPU, samma frö = bitidentiskt. (d) Exportkontroller: alla tc-LUT-värden ändliga; kornets underlager summerar till totalkurvan. |
| Källa | (a) Android-forken `scripts/verify_android_color.py` `approximate()`; (b) `android/.../render/GlFilmRendererTest.kt`, `AssetAndColorTest.kt`; (c) Dyeworks `raw_comparison/analyze_grain.py` (metod; deras testkod finns inte); (d) original 8e54f3b `utils/gamut_compression.py::_ray_polygon_distance` (NaN när en stråle träffar ett hörn), Dyeworks f6b66dc `_reconcile_grain_sublayers`. |
| Skillnad mot Klipp | `FilmLutParityTest`: 6 färger, tolerans 0,03 (≈ 7,7 kodvärden). GPU-testerna har lös tolerans och kör utan korn. Kornstatistik bara på CPU. |
| Nytta | Fångar fel som i dag ryms i lösa toleranser. |
| Belägg | Kodgranskning. Egen kontroll: Klipps 32 tabellfiler (3 505 572 halvflyttal) är ändliga; underlagren summerar till ≤ 0,0052 för alla 20 filmer (Dyeworks-granskningen). |
| Licens | Metoder; kod från Android-forken är GPL-3.0 (förenlig). |
| Svårighet | Låg. |
| Risk | Testerna kan avslöja verkliga avvikelser som då måste förklaras. |
| Verifiering | Testerna själva; trösklar sätts efter första mätningen, aldrig genom att uppdatera referenser automatiskt. |

### 2.2 Lovande men kräver separat experiment

| Fynd | Källa | Skillnad mot Klipp / nytta | Belägg | Licens | Svårighet / risk | Verifiering |
|---|---|---|---|---|---|---|
| **Preflash** (förexponering av papperet) | original 3bb2c2d `runtime/stages/printing.py` `_compute_raw_preflash`; trogna portar: spektrafilm-rs `core/src/enlarger.rs compute_preflash_raw`, SpektraLab `core/printing.cpp` | saknas i Klipp; `L' = log10(10^L + pf)` efter kopierings-LUT:en, pf beräknas i exporten | rs-test `preflash_raw_matches_python_reference`, SpektraLab paritetsfall | GPL-3.0 (original) | låg / av som standard | `reference_params.py` med preflash > 0, FilmFxParamsTest |
| **Skannerns svart-/vitkorrigering inbakad i skann-LUT:en** | spektrafilm-rs `core/src/color_reference.rs` (skalning av XYZ med `clip(m·Y+q)/Y`) | löser Klipps kända hinder ("kräver XYZ före gamutkomprimeringen") vid bakningen | rs-test `print_bw_correction_matches_python` | GPL-3.0 | medel / av som standard upstream | FilmLutParityTest, nya testvektorer |
| **Pyramidens rippel** (binomial nedskalning, B-spline-uppskalning) | SpektraLab `engine/src/pipeline/lens.cpp`, `shaders/lens.metal`, `core/diffusion_psf.cpp` | Klipp: 2×2-medel och bilinjär uppskalning; punktens svar kan variera med subpixelläget – kan förklara de få kodvärdena vid +6 EV-högdagrar | deras mätning 11–15 % → 2–4 %; ej mätt i Klipp | GPL-3.0 | medel / precision | flytta en ljuspunkt över 2^k subpixellägen, jämför med analytisk PSF |
| **Kornets skevhet** för 6 ≤ μ < 30 | SpektraLab `spk_common.h` `poisson()` (PTRS) | Klipp: avrundad normalfördelning från μ = 6 (skevhet 0,18–0,41 förloras) | svagt (deras eget test kan inte se det) | GPL-3.0 | låg–medel / kostnad | skevhetskontroll i `grainMatchesSpektrafilmStatistically` |
| **LUT-sampling i flyttal** (manuell trilinjär, RGBA32F) | Android-forken `film.frag sampleFilm` | Klipp: `GL_LINEAR` på RGBA16F (`FilmGl.tex3`) – hårdvarans vikter har begränsad precision | forkens Mali-mätning (max 1 kod) | teknik | medel / 8 läsningar per LUT | 10-bitars ramp genom kopierings- och skann-LUT, CPU-trilinjär som referens, bildtid |
| **Originalets experimental-paket**: vitbalans per kanal (e301791), profiler omjusterade (1986503), kornstyrka från RMS-granularitet (437c8e6), Langmuir-couplers (2ea9724), gamutknän (8e54f3b, 4260c7b) | original `experimental` | avsiktliga utseendeförändringar (t.ex. 2383 Dmax 3,80/3,29/3,02 → 3,38/3,47/3,20); hör ihop och måste tas som paket | upstreams egna tester kontrollerar formler, inte mot filmdata | GPL-3.0 / CC BY-SA 4.0 | hög / utseende | kräver modellversion i projekten (se 5) |
| Multiplikativ densitetsskärpning (ffbee22, b3e65de), CineStill-halation (SpektraLab `halation_psf.hpp`), negativets nyans (SpektraLab `negative_hue`) | se källa | nya utseenden – bara som separata alternativ | – | GPL-3.0 | medel / utseende | separat demonstration |

### 2.3 Inte relevant, redan implementerat eller för riskabelt

- **The Dyeworks:** motorn (wgpu, tester, prestandamätningar) finns inte i repot; inga påståenden går att kontrollera,
  och binärernas källkod är inte publicerad. Dess egna stammar har oklart ursprung.
- **spektrafilm-rs GPU-väg:** medelvärdesbildad σ, Gauss i stället för exponentialsvans, normalapproximerat korn,
  256 px radiegräns, kantpixel i stället för spegling, arctic-vägen troligen fel (saknar per-kanal-normalisering) –
  sämre än Klipp. Korn utan underlager även på CPU.
- **Android-forkens rendering:** bara färg-LUT, inga rumsliga effekter eller korn; tiling/återläsning per pixel
  gäller inte Media3-effekter. Filmikonerna är varumärken.
- Spektral beräkning per pixel (81 våglängder), Vulkan/Metal/wgpu/CUDA-körtider, numpys MT19937-paritet,
  diskcheckpoints – inte för Klipps GLES-kedja.
- Arctic-uppsampling som standard (återställd upstream 0f690da), `couplers-tuning-iterations` (aldrig sammanslagen,
  3× couplersoskärpa), automatisk exponering per ruta (pumpar i video).
- Originalets NaN-fel i gamutkomprimeringen: Klipps tabeller är ändliga (kontrollerat), men testet i N3 skyddar framtida
  exporter.

## 3. Vad Klipp redan gör lika bra eller bättre

- **Korn:** Poisson med exakt uttunning och underlager (rs tappar underlagren), frö per ruta (originalet statiskt,
  SpektraLab nytt vid varje rendering). Brist: N2.
- **Halation och scatter:** per kanal med exponentialsvans och YvV-överföringsfunktion; NNLS-pyramid med speglade
  kanter. Ingen fork har en bättre lösning för mobil GPU.
- **Highlight boost:** referens från bilden nedskalad till ≤ 128 px är stabil i video (alla andra använder bildens
  maxvärde).
- **Paritet:** `SpektraTest.everyStageMatchesSpektrafilm` jämför fem mellansteg (log_e_film, cmy_film, log_e_print,
  cmy_print, rgb_out) för 6 inställningar × 259 färger; helbildskontroll mot spektrafilm medel 0,07 kodvärden.
  Ingen fork jämför sin app mot modellens mellansteg i kod som körs automatiskt.
- **Couplers:** mängden begränsad till 1 (SpektraLab tillåter 4, där referensen blir icke-monoton över ≈ 1,74).
- **Profiler:** samma färgprofiler som spektrafilm-rs och SpektraLab (blob-identiska med upstream).

## 4. Experiment

### Experiment 1 – kornets determinism (telefon)

Gren `exp/grain-seed-check` (b889995), `tools/regress/grain_seed_check.py`. OnePlus 12 (CPH2581, Android 16), testbygge
0.19.2-designtest.161 (release-likt), eget projekt "Klipp grain determinism" 1280×720 25 fps, jämnt grått klipp, film
Portra 400/Endura, format 8 mm, kornstorlek 4. `render_diag op=render` som förrenderad bit och som export i samma
storlek (720p). Samma tidslinjeruta (2,4 s); korn = luma minus 15×15-medel; Pearson-korrelation. Metoden kontrollerad
först (självkorrelation 1,000, oberoende brus 0,000).

| Par (samma ruta) | Korrelation |
|---|---|
| Samma bit renderad två gånger | +1,0000 |
| Bitar som börjar vid 1,0 s respektive 2,0 s | −0,0036 |
| Exporter som börjar vid 1,0 s respektive 2,0 s | +0,0029 |
| Bit mot export, båda från 1,0 s | −0,0012 |
| Bit mot export, båda från 2,0 s | +0,0041 |
| Nästa ruta i samma rendering (oberoende per definition) | +0,0707 |

Slutsats: repeterbart för exakt samma rendering, annars oberoende. Kornets RMS genom H.264 jämförs inte (kodaren ger
en fils första rutor få bitar: 4,2–8,2 beroende på läge i filen). Uppspelningens pausade bild och nodcachen mättes inte
(nodcachen ligger i appens interna filer och kan inte hämtas från release-signerat testbygge).

### Experiment 2 – utseende mot renderupplösning (JVM)

Gren `exp/film-resolution` (0227ad2), `FilmResolutionExperimentTest`. Klipps CPU-kedja (`FilmFx`), testkort och jämn
grå yta, full storlek 768×432 mot halv 384×216; halv rendering jämförs med den fulla nedskalad 2×2. Kodvärden ×255.
Kontroll: den handlagda oskarpa masken återger Klipps exakt (max 0,000).

| Fall | Medelfel | p99 | Max | Mörk kant halv / full↓ |
|---|---|---|---|---|
| Standard (σ 0,7 px) | 0,641 | 11,89 | 28,35 | 15,57 / 2,19 |
| Utan oskarp mask | 0,099 | 1,47 | 13,72 | 1,62 / 1,63 |
| Utan mask, halation och scatter | 0,129 | 2,71 | 15,70 | 1,77 / 1,64 |
| Mask med σ skalad till 0,35 px | 0,275 | 5,98 | 23,80 | 2,57 / 2,19 |

| Korn, jämn grå, RMS (G), medel av 4 rutor | Halv | Full↓ | Kvot |
|---|---|---|---|
| Standard | 0,539 | 0,755 | 0,713 |
| Utan oskarp mask | 0,421 | 0,636 | 0,661 |
| Utan mask och kornoskärpa 0 | 0,936 | 0,941 | 0,995 |

Slutsats: den oskarpa masken och kornoskärpan i pixlar förklarar skillnaden; rumsliga effekter i µm skalar rätt.

### Mätmiljöns fel (påverkar inte resultaten ovan)

Homebrews Python 3.14.2 med numpy 2.2.6 skriver över `a` i `d = a - b` för arrayer från cirka 100 000 element inne i
funktioner (upprepbart; Python 3.13 med numpy 2.3.5 gör det inte). Experiment 1 räknades om i den korrekta miljön med
identiskt resultat; experiment 2 är Kotlin.

## 5. Licenser och projektkompatibilitet

| Del | Licens | Anmärkning |
|---|---|---|
| Originalets kod | GPL-3.0 (`LICENSE`); `pyproject.toml` "GPLv3", `CITATION.cff` "GPL-3.0-or-later" | "only" eller "or later" otydligt; förenligt med Klipp |
| Profiler och LUT:ar | CC BY-SA 4.0, förord v1.1 (`SPEKTRAFILM_LICENSE.txt`) | Klipp levererar den och attribuerar redan |
| Spektraltabellen i originalet (`irradiance_xy_tc.npy`) | ingen uttalad licens | används inte längre: Klipps tc-LUT:ar bygger sedan 2026-11-19 på Klipps egna spektraltabeller (`tools/spectra`) |
| Filterspektra (`data/filters/*`) | tillverkarnas mätdata utan licens | Klipp levererar härledda TH-KG3 och dikroiska filter; beslut 2026-11-19: KG3 räknas till spektrafilms data och licens |
| Testbilder i originalet (`img/test`, `img/targets`) | ingen licens | ej återanvändbara |
| SpektraLab | GPL-3.0(-or-later); egna profiler utan licens; `spklib` som binär | inget att ta in utöver idéer |
| The Dyeworks | GPL-3.0, men källkoden finns inte; egna stammar oklara | inget att ta in |
| spektrafilm-rs | GPL-3.0; arctic-basfilerna utan ursprung; saknar CC BY-SA-filen | inget att ta in utöver testvektorer (siffror) |
| Android-forken | GPL-3.0 (rotens `LICENSE`); presets CC BY-SA 4.0; ikoner = varumärken | testmetoder återanvändbara |

För de rekommenderade fynden: N1 och N2 är egna implementationer av idéer, N3 metoder (eventuell kod från
Android-forken är GPL-3.0). Preflash portas från originalets GPL-3.0-kod. Inga nya data eller tillgångar tas in.

**Projektkompatibilitet:** N1 ändrar inte exporten i sekvensens storlek; förhandsvisning och förrenderade bitar
ändras och `RenderCache`/`NodeCache` `VERSION` måste höjas – en ny cachenyckel gör att gamla renderingar inte visas,
men den bevarar inget gammalt utseende. N2 ändrar kornets mönster men inte dess statistik. Originalets
experimental-paket ändrar utseendet på sparade projekt och kräver en modellversion i filmnodens JSON (som `v` i dag)
och båda tabelluppsättningarna, så att gamla projekt kan behålla 3bb2c2d. Inget av detta förs in i betakandidaten
utan uttryckligt godkännande.

## 6. Rekommenderad nästa implementation: N1

Pixelbaserade filmparametrar (oskarp mask, skanneroskärpa, kornoskärpans pixeldel) skalas med renderstorlek /
sekvensstorlek, likadant i `FilmFx` och `FilmGl`.

Acceptanskriterier:
1. Vid sekvensens storlek är CPU-resultatet bitidentiskt med i dag (`SpektraTest`, `FilmFxParamsTest`, `FilmFxTest`,
   `FilmCpuRenderTest` oförändrade och gröna).
2. Halv storlek mot nedskalad full (experiment 2 som test): mörk kant inom 1 kodvärde av full↓, medelfel ≤ 0,3,
   kornets RMS-kvot 0,95–1,05.
3. GPU-vägen samma skalning (`FilmGpuTest`, `FilmFusedTest`); på telefon förrenderad bit (1080) mot export (4K,
   nedskalad) inom samma toleranser, mätt med hög bithastighet.
4. `RenderCache`/`NodeCache` `VERSION` höjd; exporten av ett 1080p-projekt oförändrad (jämför fil före/efter).
5. Ingen ändring av betakandidaten utan godkännande.

## 7. Separata uppgifter som hittades (utanför granskningen)

1. **Mätmiljön:** regressionsverktygen körs med Homebrews Python 3.14 + numpy 2.2.6, som kan skriva över arrayer
   (se 4). Lås verktygen till en miljö som stöds och kontrollera tidigare lokala analyser. CI:s självtest (korrekt
   miljö) gick igenom.
2. **Verktygens filmnod:** `endurance.py` och `lifecycle_check.py` sätter filmen med `{}`. Utan `print` blir det en
   negativfilm utan papper, filmen stängs av – deras "film"-sekvenser har körts utan film.
3. **Appen:** en negativfilm med `print = null` ger ett nullpekarfel i `FilmFx.warm`; filmen stängs av tyst
   (bara en rad i loggen). Bara nåbart via JSON/bryggan, men bör ge besked eller falla tillbaka.
4. ~~Spektraltabellens licens~~ – löst: Klipp använder egna spektraltabeller (`tools/spectra`).
