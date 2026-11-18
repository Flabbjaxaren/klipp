# Licensing – open questions

What could not be settled from the files themselves (2026-11-18). Each item names the exact files, how Klipp uses them
and what is missing. Nothing here has been sent to anyone; the drafts are for the owner to decide on. Technical checks
are not legal clearance – items marked **legal** need a lawyer's view before a paid release.

## A. Needs the copyright holder's permission or a decision

### A1. Title fonts with a Reserved Font Name – resolved 2026-11-18

The eight families now ship as the unmodified original files (see `docs/licensing-fonts.md`, decision at the top).

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

### A3. Freesound API in a paid app – resolved 2026-11-18

The Freesound search was removed from Klipp. Sounds a user downloaded from Freesound before stay listed and usable
(CC0, with their author and license kept beside them). No question to Freesound is needed now.

### A4. Kenney and BigSoundBank downloads – prepared, needs the originals and a release

- Kenney (CC0, License.txt in each zip): the old zip URLs stopped working in 2026-11. BigSoundBank (CC0 on every sound
  page): the app fetches `/UPLOAD/`, which the site excludes for automated access.
- Prepared: versioned packs on Klipp's own GitHub release `sounds-1`, each pinned by size and SHA-256 in the app
  (`SoundFx.HOSTED`, `tools/sounds/`). A pack switches to its hosted file only when it has a pin; until then it uses
  its old source, and packs already on phones are kept.
- Left: the originals have to be downloaded **by hand** (kenney.nl's robots.txt disallows AI agents, BigSoundBank's
  disallows `/UPLOAD/` for all robots), then `tools/sounds/build_sound_assets.py`, the pins, and publishing the release –
  all need the owner's approval. Sound 0448 records an iPhone camera shutter (small risk); 2479: check by ear that
  Klipp's cut has no music.

### A5. Klipp's own license wording (checked again 2026-11-18)

- What the notices say: OpenDRT.dctl "License: GPLv3" (no other version statement in its repository); spektrafilm:
  LICENSE (GPL v3 text), pyproject.toml "GPLv3", README "GPLv3 licensed", only CITATION.cff "GPL-3.0-or-later", no file
  headers; Klipp: README/THIRD_PARTY "GPL v3.0", `rust/Cargo.toml` `GPL-3.0-or-later`, no file headers. Everything else
  is permissive. So the app as a whole can only be offered under GPL-3.0; no third-party license was changed. Decide: make Cargo.toml `GPL-3.0-only`, or state that
  Klipp's own code is "or later" while the whole is v3.
- Copyright line: the app and docs say "© the Klipp contributors". If a named holder is wanted, decide the name.

### A6. Files Klipp added inside the Media3 modules – resolved 2026-11-18

Compared line by line with Media3 1.11.1: KlippBlend, KlippFrameFill, KlippPrecision, KlippPriority, KlippYuv,
KlippAudio and KlippPreroll share no code with Media3 (KlippFrameFill only the interface's method signatures and the
exceptions they declare) – they carry "Copyright 2026 The Klipp contributors", Apache-2.0. KlippSilenceMediaSource
(46 lines from SilenceMediaSource and others) and the YUV shader (29 of 41 lines from Media3's shaders) are derived: they
keep the Android Open Source Project's copyright, with a "Modified for Klipp" notice.

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
- Maven artifacts on the release classpath without a published source jar (4): `androidx.compose:compose-bom`,
  `kotlinx-coroutines-bom`, `kotlinx-serialization-bom` are bills of materials (POM only, no jar: version numbers, no
  code); `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` is a jar with only META-INF (no
  classes). Nothing of them is in the APK, so there is no source to provide; the source package's MISSING.txt says so.

