# N1: filmens pixelstorlekar följer exporten

Gren `n1/pixel-units` (från `design-test` aa6dd9b): 8ccd078 (ändringen), testbygge `0.19.3-beta.1-designtest.162-n1`.
Inte i betan, inte pushat.

## Problem

Den oskarpa masken (σ 0,7 px), skanneroskärpan och kornoskärpan (0,65 px) angavs i pixlar av den bild filmen körde på.
Exporten kör filmen på originalet (ingen nedskalning före graderingen); uppspelning och förrendering på förhandsvisningen
(1080 kortsida). I ett 4K-projekt fick förhandsvisningen därför bredare skärpningskanter och svagare korn än exporten.

## Ändring

- `FilmFx.Frame(s, w, h, refShort)`: `refShort` = kortsidan hos klippets original (filmens indata i exporten);
  `pxScale = min(w, h) / refShort`.
- Oskarp mask och skanneroskärpa: σ · pxScale.
- Kornoskärpan: den σ som ger samma kornstyrka som exporten nedskalad (variansmatchning genom oskärpan och masken;
  en sub-pixel-oskärpa kan inte skalas rakt – dess få vikter oskärper knappt, och nedskalningen jämnar själv ut).
- Referensen följer kompositionen (`CompositionBuilder.filmRefOf`: originalets probade storlek) → `Grade.effects` →
  `FilmEffect` → `FilmFx`; även övre lager och färgsidans stillbild. Okänd storlek: skala 1 (som förut).
- I exportens egen storlek är skalan exakt 1. `RenderCache` VERSION 18 → 19; nodcachen renderar originalet (skala 1)
  och behåller sina filer.
- Glarens brus per pixel lämnas (0,024 kodvärden i exporten, 0,039 i förhandsvisningen – osynligt).

## Resultat

### CPU (`FilmResolutionTest`, Klipps `FilmFx`, 768×432 mot 384×216, kodvärden ×255)

| Fall (halv storlek mot full nedskalad) | Före | Efter |
|---|---|---|
| Standard, korn och glare av: medelfel / p99 | 0,641 / 11,89 | **0,275 / 5,98** |
| Mörk kant vid ljus kant, avvikelse från exporten | 13,38 | **0,37** |
| Skanneroskärpa 1,5: medelfel / p99 | 2,057 / 15,17 | **0,198 / 3,00** |
| Kornets RMS, kvot mot exporten | 0,713 | **1,000** |
| Kornets RMS utan oskarp mask | 0,661 | **1,001** |
| Exporten i sin egen storlek (tre filmer, korn och glare på) | – | **bitidentisk** |

Krav i testet (gröna): medelfel ≤ 0,3, mörk kant inom 1 kodvärde, kornkvot 0,95–1,05, exporten bitidentisk.

### GPU (OnePlus 12, Android 16; testbygge 161 före, 162-n1 efter)

Eget projekt "Klipp N1 4K" (3840×2160, 25 fps), syntetiskt 4K-testkort med Portra 400 / Endura (standard),
`render_diag op=render` 1,0–2,0 s: förrenderad bit (1080) mot export (4K) nedskalad till 1080 (area), ruta 1,6 s.
Kornets mönster skiljer mellan bit och export (fröet, se kornkontraktet), så strukturella mått: mörk kant i medel över
~600 rader, gallrets amplitud, kornets RMS på jämn grå yta (minus 15×15-medel). Skript:
scratchpad `n1gpu/n1_gpu.py`.

| Mått | Före (161) | Efter (162-n1) | Export (referens) |
|---|---|---|---|
| Mörk kant vid ljus kant | 5,26 | **3,82** | 3,57 |
| Gallrets amplitud | 58,67 | 58,48 | 58,95 |
| Kornets RMS (kvot) | 1,38 (0,897) | 1,65 (**1,072**) | 1,53 |
| 4K-export 161 mot 162-n1 | – | **max skillnad 0** (identisk) | – |
| Renderingstid bit / export | 988 / 3186 ms | 979 / 3265 ms | – |

Kornkvoten på GPU mäts genom H.264 (biten kodas i 1080 med annan bithastighet än 4K-exporten); före kodning är den
1,000 (CPU). Instrumenterade GPU-tester (`FilmGpuTest`, `FilmFusedTest`) kördes inte: de kräver ett debug-signerat
testpaket på telefonen.

### Regressioner

Hela enhetssviten på grenen: 660 tester, 659 godkända, 0 misslyckade, 1 överhoppat (`FilmCpuRenderTest.renderCases`,
sedan tidigare).

## Kvar

- Förhandsvisning och export har fortfarande olika kornmönster (fröet) – se `docs/grain-time-contract.md` (förslag).
- Export i en annan storlek än originalets: filmen körs då fortfarande på originalet (som i dag) – oförändrat.
- Om originalets storlek inte är probad än skalas inget i förhandsvisningen (som förut) tills den är det.
