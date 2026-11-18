# Licensing – open questions

What could not be settled from the files themselves (2026-11-18). Each item names the exact files, how Klipp uses them
and what is missing. Nothing here has been sent to anyone; the drafts are for the owner to decide on. Technical checks
are not legal clearance – items marked **legal** need a lawyer's view before a paid release.

## A. Needs the copyright holder's permission or a decision

### A1. Title fonts with a Reserved Font Name, shipped modified (8 families, 13 files)

- Files: `app/src/main/assets/fonts/{AbrilFatface,DancingScript,LibreBaskerville,Lora,Merriweather,PlayfairDisplay,Raleway,Righteous}/`.
- Use: title and caption fonts; the family name is the picker label and the font id saved in projects.
- Problem: every bundled font is a Latin subset of the google/fonts file (Merriweather also pinned to one optical
  size and width). OFL-1.1 condition 3: a Modified Version may not use a Reserved Font Name without written permission.
  These eight declare RFNs and still carry them (internal names, picker, projects).
- Options per family (details, sizes and the look of existing projects: `docs/licensing-fonts.md`): ship the
  unmodified upstream file (about +0.6 MiB for seven of them; Merriweather +7 MiB and needs `opsz 72, wdth 100` set in
  code to look the same), rename the subset and map old saved names, or ask for permission.
- Holders: TypeTogether (Abril Fatface); Impallari Type (Dancing Script, Libre Baskerville, Raleway); Cyreal (Lora);
  Sorkin Type (Merriweather); Claus Eggers Sørensen (Playfair Display); Astigmatic (Righteous).
- Draft request (not sent):
  > Klipp is a GPL-3.0 video editor for Android (github.com/Flabbjaxaren/klipp) that may be sold on Google Play. It
  > bundles <family> under the OFL as a title font, subset to Latin characters with fontTools (no outline or metric
  > changes) to keep the app small. Would you grant written permission for this subset to keep the Reserved Font
  > Name "<RFN>"? The OFL text and your copyright notice ship with it unchanged.

### A2. spektrafilm – data beside the profiles, and the license version

- Files: `app/src/main/assets/spektrafilm/film/*.tc.bin` (encode the hanatos2025 spectral-upsampling data, upstream
  `src/spektrafilm/data/luts/spectral_upsampling/irradiance_xy_tc.npy`, "Thanks to hanatos for providing luts");
  `index.json` → `enlargerIlluminant` (a black body through the Schott KG3 heat-absorbing glass spectrum, upstream
  `data/filters/heat_absorbing/schott/KG3.csv`, whose `info.txt` names Newport's web page as the source) and
  `neutralFilters` (computed by spektrafilm).
- Missing: a license statement for the hanatos data and for the Schott/Newport spectrum (manufacturer data). The
  SPEKTRAFILM_LICENSE (CC BY-SA 4.0) covers "the profiles … and all direct derivatives"; it does not say it covers these
  files, and GPL-3.0 (the repository's license) may not be the hanatos or Schott data's license. Klipp's CHANGELOG.txt
  claims nothing for them.
- Also unclear: spektrafilm's code license version – `LICENSE` and `pyproject.toml` say GPL v3, `CITATION.cff` says
  `GPL-3.0-or-later`. And the README speaks of the LUTs as "commercial use, free share, no resale" while
  SPEKTRAFILM_LICENSE.txt grants CC BY-SA 4.0; Klipp follows the license file and does not sell the LUTs separately.
- Draft question to Andrea Volpato (GitHub Discussions, not sent):
  > Klipp (GPL-3.0 Android video editor, may be sold) ports spektrafilm's pipeline and ships tables computed from your
  > profiles (revision 3bb2c2d), with SPEKTRAFILM_LICENSE.txt unchanged and a CHANGELOG.txt. Three questions: (1) Are the
  > hanatos2025 upsampling data (irradiance_xy_tc.npy) and the KG3 filter spectrum under a license we should name, or
  > who should we ask? (2) Is the code GPL-3.0-only or -or-later (CITATION.cff says or-later)? (3) Is a paid app that
  > ships these tables, credited and under CC BY-SA, within what you intend?
- And to hanatos (vkdt), if Andrea doesn't know: the license of the upsampling coefficient data.

### A3. Freesound API in a paid app

- Code: `engine/SoundFx.kt` `search`/`fetch` – text search filtered to CC0, HQ MP3 previews, each user's own API key.
- The sounds are CC0, but the API has its own terms: "Terms for commercial use of the API will be negotiated on a case
  by case basis with UPF" (freesound.org/help/tos_api §3, read 2026-11-18); "Freesound API usage is free for
  non-commercial use, but it can also be licensed for being used in commercial applications" (help/developers).
  Nothing says a user's own key makes a paid app's use non-commercial.
- Options: ask Freesound (draft below), or leave the Freesound search out of the Play build until they answer. Not
  changed: the feature works as before; THIRD_PARTY.md no longer says it is cleared.
- Draft (not sent):
  > Klipp is an open-source (GPL-3.0) Android video editor that may be sold on Google Play. Its optional sound search
  > uses your API with each user's own key (Klipp ships none): it searches CC0 sounds only and downloads the HQ preview
  > of a sound the user picks, for their own video. 1) Is that allowed if the app is sold, or is it commercial use that
  > needs an agreement with UPF? 2) Is one key per user fine, or would you prefer one Klipp credential or OAuth2?
  > 3) How would you like Freesound and the authors credited in the app?

### A4. Kenney and BigSoundBank downloads

- Kenney (`SoundFx.kt`, 7 zip URLs): CC0 (License.txt in each zip), but the hard-coded URLs stopped working in
  2026-11 (kenney.nl redirects them to the asset pages; the app saves the HTML and reports "no sounds in the pack").
  **This is a broken feature**, not a license problem. Option: host the CC0 zips on Klipp's GitHub release with fixed
  SHA-256 (CC0 allows it) – a decision, not done.
- BigSoundBank (75 fixed sounds): every sound page says CC0. The app fetches `bigsoundbank.com/UPLOAD/<id>.ogg`, which
  the site's robots.txt and ai.txt exclude for automated access. Options: self-host the clips (CC0) with hashes, or ask.
  Sound 0448 is a recording of an iPhone camera shutter (small trademark/sound-mark risk); sound 2479 (applause after a
  band): check by ear that Klipp's cut has no music.

### A5. Klipp's own license wording

- `README.md` / `THIRD_PARTY.md`: "GPL v3.0"; `rust/Cargo.toml`: `GPL-3.0-or-later`. The OpenDRT port grants version 3
  only, so the app as a whole is GPL-3.0 (docs/licensing.md). Decide: make Cargo.toml `GPL-3.0-only`, or state that
  Klipp's own code is "or later" while the whole is v3.
- Copyright line: the app and docs say "© the Klipp contributors". If a named holder is wanted, decide the name.

### A6. Files Klipp added inside the Media3 modules

- `third_party/media3-effect/…/Klipp{FrameFill,Precision,Priority,Yuv}.java`,
  `third_party/media3-transformer/…/Klipp{Audio,Preroll,SilenceMediaSource}.java` state no license;
  `KlippBlend.java` and `fragment_shader_klipp_sdr_external_yuv_es3.glsl` say Apache-2.0 "like the rest of the module".
  Decide the license of the others (Apache-2.0 for consistency, or GPL-3.0 like Klipp) and add a header.

### A7. Line icons

- `app/src/main/java/se/gendut/klipp/Icons.kt` says the icons are "the same shapes as in the design mockup". No match
  with an icon library was found (they are simple path data), but where the mockup's shapes came from is not recorded.

## B. Legal questions (not answered by technical checks)

- **Google Play and the GPL**: Play's distribution terms and the GPL's "no further restrictions" (§10); Corresponding
  Source for Play users (§6(d) via GitHub, docs/source-release.md); the app's license screen states the users' rights.
- **Codec patents**: the engine contains its own H.264, HEVC, AAC, AC-3, MP3 and other decoders/encoders
  (filmcraft-h264, -h264enc, -hevc, -aac, -ac3, -mp3enc …); Android's own codecs are used for most playback/export.
  Patent licensing for a paid distribution is not covered by any of the copyright licenses here.
- **Trademarks**: film and paper names (Kodak, Fujifilm, Portra, Velvia, Vision3 …), camera log names (Apple Log,
  vivo Log, S-Log3, V-Log, LogC …), font names with trademark notices (name ID 7), Kenney's name. Used to identify
  what is emulated or supported; OFL and CC licenses grant no trademark rights.
- **libc++_shared.so**: shipped with the APK. Whether it is a "System Library" under GPL-3.0 §1 (then its source is
  not part of the Corresponding Source) – it is assumed so; its license notice ships either way.
- **Consumer law** (refunds, withdrawal for digital content), taxes, Play policies: out of scope here.
- **Test data in the repository**: `app/src/test/resources/spektrafilm_*` and `opendrt_ref.txt` are outputs of
  spektrafilm/OpenDRT runs on synthetic images, distributed with the GPL repository (not in the APK).

## C. Recorded, no action needed now

- Downloaded speech models: now pinned to a Hugging Face revision and checked by SHA-256 after download. Models already
  on a phone are kept as they are (they were checked by size when downloaded); nothing is deleted.
- Silero VAD and the FilmCraft engine's seven fonts were bundled without their notices before 2026-11-18; both now ship.
