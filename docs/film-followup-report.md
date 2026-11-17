# Uppföljning efter spektrafilm-granskningen

2026-11-17. Gren `design-test` (lokal, inte pushad). Betan (`main` = `beta/0.19.3-beta.1` = 435b454) är orörd.
N1 ligger på egen gren, se `docs/n1-pixel-units.md`. Kornets tidskontrakt är ett förslag: `docs/grain-time-contract.md`.

## 1. Testmiljön (d38f6c8)

Homebrews Python 3.14.2 med numpy 2.2.6 skriver över en levande array i `a - b` från cirka 100 000 element (temporär
elision mot 3.14:s referensräkning) – avkodade bilder i regressionsverktygen kunde förstöras tyst.

- `regress.py` (importeras av alla verktyg) provar detta vid start och vägrar köra med en sådan numpy.
- Verktygen startas som `./<verktyg>.py`; shebangen kör dem med uv i Python 3.13 och numpy 2.3.5.
- Kontroll: `./selftest.py` alla kontroller rätt; den gamla miljön vägrar med förklaring. CI (Ubuntus numpy) klarar provet.
- **Omkontroll av tidigare godkännanden:** R1/R3/R6 på bygge 161 (OnePlus) körda om i den korrekta miljön: 21/21, samma
  utfall som tidigare.

## 2. Verktygens filmnod (7d6a427)

Verktygen graderade med `{"film": {}}`; utan `print` lästes det som en negativfilm utan papper och filmen var tyst av.
R4, A1, uthållighetspasset och export-/livscykelkontrollerna har alltså körts **utan film**.

- `regress.FILM` = noden som appen sparar den (film och papper); `set_film()` misslyckas om appen rapporterar att den
  inte kunde ta noden; sekvenser som verktygen återanvänder får filmen på nytt.

## 3. Ogiltiga filmnoder i appen (aa6dd9b)

- En saknad `print`-nyckel betyder nu standardpapperet (`JSONObject.isNull` är sant även för en nyckel som saknas).
- `Film.valid()` när noden läses: en negativfilm utan känt papper får filmens målpapper (annars standardpapperet), en
  okänd film standardfilmen; rättningen noteras i spåret. Giltiga noder och diafilm utan papper är oförändrade.
- `FilmValidationTest` (5 tester); SpektraTest, FilmFxTest och FilmEditorTest oförändrat gröna.

## 4. Uthållighetspass med aktiv film

OnePlus 12 (CPH2581, Android 16), testbygge 0.19.2-designtest.161 (betans appkod), projektet "Klipp endurance 156",
sekvensen "E1 endurance" (push-övergångar, film med nodcache på två klipp, V2 och V3), 22,0 minuter i en process:
75 cykler (sökningar, 8 s uppspelning med cut trace, V3-flytt var tredje cykel), 12 exporter. Filmen kontrollerad som
tagen (inget "spektrafilm:"-fel); exporterna tar 5,5–6,0 s mot 3,6 s i passet utan film.

| Mått | Värde |
|---|---|
| Kall start (cykel 0) | PSS 419 MB, native 151, grafik 11, 97 trådar, 3 codecs |
| PSS, uppvärmt | median 388 MB (274–476), trend −10,7 MB/10 min; tvåminutersmedianer 394, 399, 422, 411, 358, 383, 345, 397, 355, 381, 396 |
| Native | median 238 MB (174–297), trend −5,8 MB/10 min |
| Grafik | median 18 MB (12–36), trend −1,6 MB/10 min |
| Trådar | median 92 (85–105), trend −1,4/10 min |
| Codecs | 2 i vila, högst 4 |
| Värme | status 0 hela tiden; skal högst 35,4 °C, CPU högst 49,4 °C |
| Rutor | 74 uppspelningar, 13 684 rutplatser, 59 sena (0,72 per uppspelning uppvärmt; de första fem: 3, 5, 0, 7, 4), 0 tappade |
| Fel | 0 krascher; 29 loggfel utöver plattformsbrus – klassade nedan |
| Efter export | bakgrundsarbetet släppt varje gång; alla 12 testexporter borttagna |

**Slutsats (snäv):** ingen observerad fortgående resursökning under 22 minuter på OnePlus i denna testsekvens. Det är
inte ett generellt belägg för att läckor saknas (andra sekvenser, längre tid, vivon och andra telefoner är inte prövade).
Att de sena rutorna i början sammanfaller med att film och nodcache värms upp är en rimlig förklaring, men **inte
belagd** – passet sparade ingen trace; det kräver ett pass med cut trace eller Perfetto.

**Loggfelen (29) per cykel:**

| Rad | Cykel | Klass |
|---|---|---|
| BufferQueue "has been abandoned" ×2, MediaCodec `UNKNOWN_ERROR` i STARTED, "Invalid to call at Released state" | 1 (kall start, filmen nyss satt, spelaren byggs om) | **återhämtat fel**: cykelns uppspelning gick (169 rutplatser, 0 tappade); sannolikt avkodarens yta som avvecklades under ombyggnad – orsaken inte fastställd utan trace |
| MPEG4Writer "Stop() called but track is not started" ×9 | 1, 3, 4, 6 | förväntad avveckling: förrenderings- och nodcachejobb som avbryts (följer inte exporterna) |
| MediaCodec "Released by resource manager" ×2 | 12, 18 (exportcykler) | förväntat och återhämtat: exporten får kodaren, förhandsvisningen tar tillbaka sin |
| C2AllocatorGralloc "handle is not compatible" ×2 | 6, 30 (exportcykler) | plattformens kodare; ingen följd observerad (exporterna sparade och giltiga) – kvarvarande låg risk |
| perf_hint ×2 | 4, 36 | plattform: prestandaledtrådar för trådarna kunde inte sättas; ingen funktion beror på dem |
| OplusViewMirrorManager | spridda | ColorOS-gränssnitt, ingen betydelse |

Råmaterial: scratchpad `end/film161/` (samples.jsonl, events.log, errors.log, run.log).

## 5. Separata uppgifter som hittades

1. `FilmFusedTest.effectsOnly` faller på `design-test` utan N1 (12 708 mot 12 484 värden utanför CPU:ns marginal) –
   ett befintligt, marginellt villkor; testet eller den sammanslagna vägen behöver ses över.
2. Bryggans `screenshot` fångar inte videoytan på OnePlus (programvyn blir svart); `adb screencap` gör det.
3. Projektbyte avbryter en pågående nodcacherendering, som börjar om när projektet öppnas igen – väntat, men kostsamt
   för 4K (≈ 25 s per 4 s).
