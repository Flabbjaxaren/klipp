# Licensing – open questions

What could not be settled from the files themselves (2026-11-18). Each item names the exact files, how Klipp uses them
and what is missing. Nothing here has been sent to anyone; the drafts are for the owner to decide on. Technical checks
are not legal clearance – items marked **legal** need a lawyer's view before a paid release.

## A. Needs the copyright holder's permission or a decision

### A1. Title fonts with a Reserved Font Name – resolved 2026-11-18

The eight families now ship as the unmodified original files (see `docs/licensing-fonts.md`, decision at the top).

### A2. spektrafilm – data beside the profiles, and the license version – resolved 2026-11-19

- **Spectral upsampling – resolved 2026-11-19.** The film tables `film/*.tc.bin` are made from Klipp's own spectral
  upsampling tables (`tools/spectra`, written from the published method, Jakob & Hanika 2019, with the CIE 1931
  observer and the film's illuminant as spektrafilm defines them). spektrafilm's bundled spectral table is not used.
- **KG3 – resolved by the owner's decision 2026-11-19.** `index.json` → `enlargerIlluminant` is a 3400 K black body
  through the Schott KG3 heat-absorbing glass transmission (upstream `data/filters/heat_absorbing/schott/KG3.csv`, a
  digitised curve whose `info.txt` names newport.com's heat-absorbing glass page; no separate license is stated for it).
  The curve is distributed by spektrafilm as part of its data, so Klipp treats it as covered by spektrafilm's licenses
  and distributes the derived table on spektrafilm's terms (`SPEKTRAFILM_LICENSE.txt`). No contact is planned. The source
  file is not shipped; only the derived table. `neutralFilters` in `index.json` are computed by spektrafilm.
- Not needed for Klipp's distribution, but unconfirmed: whether spektrafilm's code is meant as version 3 only or "or
  later" (`LICENSE`/`pyproject.toml` "GPLv3", `CITATION.cff` `GPL-3.0-or-later`); Klipp uses it under version 3. And the README speaks of the LUTs as "commercial use, free share, no resale" while
  SPEKTRAFILM_LICENSE.txt grants CC BY-SA 4.0; Klipp follows the license file and does not sell the LUTs separately.

### A3. Freesound API in a paid app – resolved 2026-11-18

The Freesound search was removed from Klipp. Sounds a user downloaded from Freesound before stay listed and usable
(CC0, with their author and license kept beside them). No question to Freesound is needed now.

### A4. Kenney and BigSoundBank sounds – resolved 2026-11-19

The eleven packs come with the app (`app/src/main/assets/sounds/*-sounds-1.zip`, SHA-256-pinned in `SoundFx.BUNDLED`,
`tools/sounds/sounds-1.json`): the publishers' originals, downloaded by hand, CC0, packed with their license and credits.
A pack is installed into the sound library from the app's own file, offline; nothing is fetched from kenney.nl or
bigsoundbank.com, and no `sounds-1` release is needed. Sound 0448 records an iPhone camera shutter (small risk); 2479:
check by ear that Klipp's cut has no music.

### A5. Klipp's own license wording – resolved 2026-11-18

The distribution as a whole is offered under GPLv3 (`GPL-3.0-only`); the explicit `GPL-3.0-or-later` of the JNI
bridge's own code (`rust/Cargo.toml`) stays. Upstream notices were read as they are (OpenDRT "License: GPLv3";
spektrafilm "GPLv3" in LICENSE/pyproject/README, `GPL-3.0-or-later` in CITATION.cff) – that wording alone is not
taken to settle "only" or "or later" for those projects; both permit version 3. No third-party license was changed
(`docs/licensing.md`). Copyright line: "© the Klipp contributors" (a named holder can be added later).

### A6. Files Klipp added inside the Media3 modules – resolved 2026-11-18

Compared line by line with Media3 1.11.1: KlippBlend, KlippFrameFill, KlippPrecision, KlippPriority, KlippYuv,
KlippAudio and KlippPreroll share no code with Media3 (KlippFrameFill only the interface's method signatures and the
exceptions they declare) – they carry "Copyright 2026 The Klipp contributors", Apache-2.0. KlippSilenceMediaSource
(46 lines from SilenceMediaSource and others) and the YUV shader (29 of 41 lines from Media3's shaders) are derived: they
keep the Android Open Source Project's copyright, with a "Modified for Klipp" notice.

### A7. Line icons – resolved 2026-11-19

- `app/src/main/java/se/gendut/klipp/Icons.kt` says the icons are "the same shapes as in the design mockup". The owner
  recalls that the mockup and its icons were drawn by Claude (Anthropic's AI assistant) for Klipp. They were already in
  the project's first version (0.16.1); no mockup file is kept.
- Checked 2026-11-19: all 83 icons rasterised and compared with Lucide 0.460.0 (1 539 icons), Feather 4.29.2 (287) and
  Tabler 3.24.0 (4 905 outline icons). No icon reuses a library's path data: the path strings are written differently
  (own coordinates and constructions). The only near-identical shapes are basic geometry that any 24 × 24 line icon
  set draws the same way (plus, ×, chevrons, a straight divider, an isometric cube, a half-filled circle, crop marks).
  The rest are at most loosely similar (the nearest match for each icon is in the 0.4–0.9 overlap range, and the higher
  ones are the same object drawn differently, e.g. a folder or an eye).

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

