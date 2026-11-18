# Licensarbetet – slutrapport för granskning

Gren `licensing`, utgår från `c993d4d`; granskas och slås ihop via en PR mot `main`. Ingen APK-release, tagg eller extern
kontakt har gjorts. Tekniska kontroller är inte en juridisk bedömning.

## 1. Vad som har gjorts

| Område | Resultat | Var |
|---|---|---|
| Inventarium | 357 komponenter (55 handgranskade, 116 Maven, 186 Rust – varav 164 i APK:n) med version, ursprung, licens, upphovsrätt, ändringar, plats och status | `LICENSES/inventory.json`, `tools/licenses/` |
| Licenstexter och notiser | GPL, Apache, MPL, alla Rust-paketens egna licensfiler, FilmCraft/craft-libs NOTICE, motorns typsnitt (OFL), whisper.cpp, libc++ (LLVM), Silero VAD, OpenAI Whisper, Checker Framework – följer med i APK:n | `LICENSES/` → `assets/licenses/` |
| CI-kontroll | stoppar en ny crate, ett nytt Maven-bibliotek eller en ny tillgång utan granskad post, och ett inaktuellt inventarium | `.github/workflows/build.yml` |
| Licensvy | Inställningar → Om → Licenser och källkod (utan utvecklarläge): version, bygge, commit, GPL-notis och användarnas rättigheter, hela GPL-texten offline, källkodslänk till exakt commit, alla komponenter med licens/upphovsrätt/notiser, spektrafilm-avsnitt, nedladdningar | `AboutUi.kt` |
| Typsnitt | de 8 med reserverat namn är nu originalfilerna, oförändrade; Merriweather ritas med samma optiska storlek som förut | `docs/licensing-fonts.md` |
| Freesound | sökningen borttagen; tidigare hämtade ljud listas och fungerar | `SoundFxUi.kt`, `SoundFx.kt` |
| GPL | helheten erbjuds under GPLv3 (`GPL-3.0-only`); bryggans uttryckliga `GPL-3.0-or-later` kvar; uppströms "GPLv3" tolkas inte som only eller or-later | `docs/licensing.md`, README, THIRD_PARTY |
| Originaltexter | alla licens- och notisfiler jämförda med originalen (gnu.org, apache.org, mozilla.org, creativecommons.org, uppströms repon vid låst version, Cargo-källorna, NDK, jar): identiska; CI kontrollerar kopiorna | `inventory.py check` |
| Ljudpaket | nya nedladdningar från Kenneys döda länkar och BigSoundBanks ljudmapp avstängda, med besked i ljudbiblioteket; hämtade ljud och projekt kvar. Versionssatta, SHA-256-låsta paket på Klipps release `sounds-1` förberedda: verktyget kontrollerar återdistributionsrätten (Kenneys CC0-License.txt, BigSoundBanks CC0-märkning per ljud) och packar CC0-texten och krediteringar med | `tools/sounds/`, `SoundFx.HOSTED` |
| Talmodeller | låsta till revision + SHA-256 (samma filer); befintliga modeller behålls | `Transcribe.kt` |
| Media3 | 18 ändrade filer märkta; 7 egenförfattade tillägg Apache-2.0 med "The Klipp contributors"; 2 härledda behåller AOSP-upphovsrätt | `third_party/media3-*` |
| spektrafilm | licensfilen oförändrad (identisk med upstream), ny `CHANGELOG.txt` (revision, vad som omvandlats och hur, vad som inte kommer från profilerna) | `assets/spektrafilm/` |
| Källpaket | skript + instruktion + process; byggkontroll godkänd för v0.20.0-beta.1 | `tools/source-release/`, `docs/source-release.md` |
| Dokumentation | licensöversikt, typsnittsgranskning, öppna frågor, THIRD_PARTY.md, README | `docs/licensing*.md` |

## 2. Kontroller och resultat

| Kontroll | Resultat |
|---|---|
| Enhetstester | 738 tester, 0 fel, 1 överhoppat (`FilmCpuRenderTest.renderCases`, som tidigare); lint (release) godkänt |
| `inventory.py check` | godkänd; provad negativt: en ny ogranskad tillgång och en ny biblioteksversion stoppas |
| Release-APK:ns innehåll | 373 licensfiler i `assets/licenses/`; alla 522 notiser som inventariet nämner finns i APK:n; originaltypsnitten och Merriweathers axlar med; inga Freesound-adresser eller -nycklar i koden; `BuildConfig.GIT_COMMIT` = byggets commit (med `-dirty` när arbetskopian har ändringar) |
| Källkodslänkar | `…/tree/<commit>` ger 200 för en pushad commit och 404 för en opushad – taggen måste pushas före bygget (står i processen); `…/releases` och `…/releases/tag/v0.20.0-beta.1` ger 200 |
| Typsnitt, dator | gamla och nya filer renderade (latin, vikt 400/700, regular/italic): sju familjer pixelidentiska, Merriweather avrundningsskillnader (1 px på 72 px i fet) |
| Typsnitt, telefon (vivo) | samma projekt med titel (fet) och undertext (normal) i alla åtta typsnitt, före (181) och efter (182): 0 pixlar som skiljer sig märkbart i programmonitorn för alla åtta (Merriweather högst 9/255 i kantutjämning) |
| Licensvyn, telefon (vivo) | Om → Licenser och källkod: version, bygge, commit, GPL-notis, källkodslänk, hela GPL-texten (scrollad till slutet), en Rust-komponent och dess licens, spektrafilm-avsnittet och CHANGELOG.txt – i normal och 1,3× text. Hittade och rättade: undersidor öppnades nedscrollade, lång rubrik på två rader, notiser med hård radbrytning (nu omflödade, bara blanksteg), "–" som version, crates länkade till registrets index. Kontrollerat igen med 183. Engelska på telefonen; svenska och 1,3× även i Robolectric |
| Källpaket v0.20.0-beta.1 | byggt ur paketet i tom mapp (Rust offline med tom Cargo-cache) → 0.20.0-beta.1, versionskod 100; libc++ och androidx-biblioteket identiska med releasen, bryggan skiljer sig i inbäddade källsökvägar, whisper i inbäddad commit-sträng. `MISSING.txt` förklarar de fyra Maven-artefakterna utan källkod (tre BOM, en tom jar) |
| Telefonens utgångsläge | vivon återställd: textstorlek 1,0, "håll vaken" 15, bryggan av, testappens tidigare projekt öppet (oförändrat: klipp och 27 undertexter), hemskärmen; testpaketet är nu 183 (var 181); den installerade Klipp 0.19.3-beta.2 orörd |

## 3. Kräver ditt beslut

1. **Ljudpaketen**: lägg originalen i `~/AndroidStudioProjects/Klipp-builds/sounds-originals/` (Kenney ×7 zip,
   BigSoundBank ×75 ogg – `tools/sounds/README.md`); därefter byggs, kontrolleras och låses paketen. Publicering av
   `sounds-1` kräver ditt godkännande.
2. **Kontakt** om spektrafilms övriga data: utkasten i `docs/licensing-open-questions.md` (A2) – skickas av dig, eller inte.
3. **Ikonerna**: deras ursprung ("designskissen") är inte dokumenterat.

## 4. Kräver tillstånd eller juridisk granskning

- **spektrafilm** (utkast till fråga i `docs/licensing-open-questions.md`, A2): licensen för hanatos-spektradata och
  Schott KG3-spektrumet i tabellerna; om koden är "or later"; README:ns "no resale" mot licensfilens CC BY-SA.
- **Juridiskt**: Google Plays villkor mot GPL §10; kodekpatent (motorns egna H.264/HEVC/AAC/MP3 m.fl.); varumärken
  (Kodak, Fujifilm och filmnamnen, kamerornas log-namn, typsnittsnamn); om libc++ räknas som systembibliotek;
  konsumenträtt.

## 5. Checklista före betald Play-lansering

- [ ] Granska och slå ihop `licensing`; CI grön
- [ ] Ljudpaketen: originalen, `build_sound_assets.py`, låsningarna, releasen `sounds-1` (godkännande), prov på telefon
- [ ] Svar från spektrafilm (A2) eller dokumenterat beslut
- [ ] Juridisk genomgång (punkt 4)
- [ ] Per release: tagga och pusha först (källkodslänken pekar på commiten), rent bygge, `inventory.py check`,
      källpaket + byggkontroll, källpaketet på releasen
- [ ] Licensvyn provad på telefon i den release som ska ut
