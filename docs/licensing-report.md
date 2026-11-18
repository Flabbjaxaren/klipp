# Licensarbetet – slutrapport för granskning

Gren `licensing` (lokal, inte pushad), utgår från `c993d4d` (= `main` på GitHub). Ingen APK-release, tagg eller extern
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
| Ljudpaket | versionssatta, SHA-256-låsta paket på Klipps release `sounds-1` förberedda (app-stöd, verktyg, instruktion); slås på per paket när låsningen fylls i | `tools/sounds/`, `SoundFx.HOSTED` |
| Talmodeller | låsta till revision + SHA-256 (samma filer); befintliga modeller behålls | `Transcribe.kt` |
| Media3 | 18 ändrade filer märkta; 7 egenförfattade tillägg Apache-2.0 med "The Klipp contributors"; 2 härledda behåller AOSP-upphovsrätt | `third_party/media3-*` |
| spektrafilm | licensfilen oförändrad (identisk med upstream), ny `CHANGELOG.txt` (revision, vad som omvandlats och hur, vad som inte kommer från profilerna) | `assets/spektrafilm/` |
| Källpaket | skript + instruktion + process; byggkontroll godkänd för v0.20.0-beta.1 | `tools/source-release/`, `docs/source-release.md` |
| Dokumentation | licensöversikt, typsnittsgranskning, öppna frågor, THIRD_PARTY.md, README | `docs/licensing*.md` |

## 2. Kontroller och resultat

RESULTS_PLACEHOLDER

## 3. Kräver ditt beslut

1. **Publicering**: pusha grenen `licensing` och slå ihop den i `main`; lägga källpaketet för v0.20.0-beta.1 som tillgång
   på den releasen. (Allt är förberett, inget gjort.)
2. **Ljudpaketen**: ladda ner originalen för hand (Kenney ×7, BigSoundBank ×75 – `tools/sounds/README.md`), bygga och
   publicera releasen `sounds-1`. Fram till dess: Kenney-paketen går inte att hämta (deras länkar dog i november),
   BigSoundBank hämtas som förut från `/UPLOAD/`.
3. **Klipps egen GPL-formulering**: hela appen kan bara erbjudas under GPL-3.0 (OpenDRT är "GPLv3", spektrafilm i
   huvudsak "GPLv3"). `rust/Cargo.toml` säger `GPL-3.0-or-later`, README "GPL v3.0". Välj: `GPL-3.0-only` överallt,
   eller "or later" för Klipps egen kod. Upphovsrättsraden är "the Klipp contributors" – ett namn om du vill.
4. **Ikonerna**: deras ursprung ("designskissen") är inte dokumenterat.

## 4. Kräver tillstånd eller juridisk granskning

- **spektrafilm** (utkast till fråga i `docs/licensing-open-questions.md`, A2): licensen för hanatos-spektradata och
  Schott KG3-spektrumet i tabellerna; om koden är "or later"; README:ns "no resale" mot licensfilens CC BY-SA.
- **Juridiskt**: Google Plays villkor mot GPL §10; kodekpatent (motorns egna H.264/HEVC/AAC/MP3 m.fl.); varumärken
  (Kodak, Fujifilm och filmnamnen, kamerornas log-namn, typsnittsnamn); om libc++ räknas som systembibliotek;
  konsumenträtt.

## 5. Checklista före betald Play-lansering

- [ ] Granska och slå ihop `licensing`; CI grön
- [ ] Ljudpaketen: originalen, `build_sound_assets.py`, låsningarna, releasen `sounds-1`, prov på telefon
- [ ] Svar från spektrafilm (A2) eller dokumenterat beslut
- [ ] GPL-formuleringen beslutad och rättad
- [ ] Juridisk genomgång (punkt 4)
- [ ] Per release: tagga och pusha först (källkodslänken pekar på commiten), rent bygge, `inventory.py check`,
      källpaket + byggkontroll, källpaketet på releasen
- [ ] Licensvyn provad på telefon i den release som ska ut
