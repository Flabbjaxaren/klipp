# Third-party software and data

Klipp itself is licensed under the GNU General Public License v3.0 (`LICENSE`; see `docs/licensing.md` for "only" vs
"or later"). It contains or downloads the following. The complete, machine-readable list – every Rust crate and Maven
library with its exact version, origin and license files – is **`LICENSES/inventory.json`**; the license texts and
notices are in **`LICENSES/`** and ship inside the app (Settings → About → Licenses and source code). Open questions:
`docs/licensing-open-questions.md`.

## In the app's code and native libraries

| Component | Where | License | Changes |
|---|---|---|---|
| **Media3 effect, transformer, ExoPlayer** 1.11.1 – © The Android Open Source Project | `third_party/media3-*` (classes.dex) | Apache-2.0 | Modified: 18 files (marked "Modified for Klipp") and 8 added – see each module's `KLIPP.md` |
| **whisper.cpp** 1.9.4 and **ggml** – © The ggml authors; YaRN code © Jeffrey Quesnelle and Bowen Peng | `third_party/whisper.cpp` → `libklipp_whisper.so` | MIT (`LICENSES/third-party/whisper.cpp/`) | Trimmed to the CPU backend; one added function – see `KLIPP.md` |
| **FilmCraft** engine (`filmcraft-*`, pinned commit in `rust/Cargo.toml`) – © ArtCraft Team and the FilmCraft contributors | `libklipp_bridge.so` | MIT OR Apache-2.0 (+ `NOTICE`) | None |
| **craft-libs** (`craft-media`, `craft-docpath`) – © ArtCraft Team and the craft-libs contributors | `libklipp_bridge.so` | MIT OR Apache-2.0 (+ `NOTICE`) | None |
| **Fonts compiled into the engine**: Inter (Regular, Medium, SemiBold, Bold, Italic) © The Inter Project Authors, JetBrains Mono Regular © The JetBrains Mono Project Authors, Noto Serif Regular © The Noto Project Authors | `libklipp_bridge.so` (via filmcraft-text) | OFL-1.1 (`LICENSES/third-party/filmcraft/OFL-*.txt`) | None (byte-identical) |
| **Symphonia** 0.5.5 (13 crates) – © The Project Symphonia Developers | `libklipp_bridge.so` | **MPL-2.0** (`LICENSES/texts/MPL-2.0.txt`); source: crates.io and Klipp's source package | None |
| **ICU4X** crates (`icu_*`, `zerovec`, `yoke`, `tinystr` …) – © Unicode, Inc. | `libklipp_bridge.so` | Unicode-3.0 | None |
| **encoding_rs** 0.8.42 – © Mozilla Foundation | `libklipp_bridge.so` | (Apache-2.0 OR MIT) AND BSD-3-Clause (WHATWG data) | None |
| Other Rust crates (about 90: `serde_json`, `jni`, `zlib-rs` (Zlib), `memchr`, `image` …) | `libklipp_bridge.so` | MIT, Apache-2.0, BSD, Zlib, Unlicense – per crate in `LICENSES/third-party/rust/` | None |
| **libc++** (Android NDK 29.0.14206865, LLVM) | `libc++_shared.so` | Apache-2.0 WITH LLVM-exception; legacy NCSA (`LICENSES/third-party/libc++/NOTICE.txt`) | None |
| **AndroidX / Jetpack Compose / Media3 common** (Maven), **Kotlin**, **kotlinx.coroutines**, **Guava** | classes.dex | Apache-2.0 | None |
| **Checker Framework qualifiers** 3.43.0 – © the Checker Framework developers | classes.dex | MIT (`LICENSES/third-party/maven/checker-qual-LICENSE.txt`) | None |
| **spektrafilm** (code) – © Andrea Volpato et al. | ported to `engine/Spektra.kt`, `engine/FilmFx.kt`, `engine/FilmGl.kt` | GPL-3.0 (version: see `docs/licensing.md`) | Ported to Kotlin/GLSL |
| **OpenDRT** v1.1.0 – Jed Smith | ported to `engine/OpenDrt.kt` | GPL-3.0 (version 3 only) | Ported; SDR only, two inputs added |

## Data and fonts in the app (assets)

| Material | Where | License | Changes |
|---|---|---|---|
| **spektrafilm film profiles** → Klipp's film tables – spektrafilm by Andrea Volpato | `assets/spektrafilm` | **CC BY-SA 4.0** (`SPEKTRAFILM_LICENSE.txt`, unchanged) | Converted for Klipp by the Klipp contributors – `CHANGELOG.txt`. Some values come from data whose license is not stated (open questions A2) |
| **Title fonts**, 33 families from Google Fonts (google/fonts commit e7db6ca) – © their authors | `assets/fonts/<family>` | OFL-1.1 (`OFL.txt`); Roboto Slab and Permanent Marker Apache-2.0 (`LICENSE.txt`, `CHANGES.txt`) | All subset to Latin; Inter, DM Sans, Archivo and Merriweather pinned to one optical size/width. **8 families have Reserved Font Names** – see `docs/licensing-fonts.md` and open questions A1 |
| **Silero VAD** v6.2 (ggml conversion, ggml-org/whisper-vad 9ffd54a) – © Silero Team | `assets/silero-vad-v6.2.0.bin` | MIT (`LICENSES/third-party/silero-vad/`) | None |

## Downloaded by the app when the user asks (not in the APK)

| Content | Source | License |
|---|---|---|
| Speech models **KB-Whisper** base/small/medium (ggml) | KBLab, National Library of Sweden – huggingface.co/KBLab, pinned revision + SHA-256 | Apache-2.0 (fine-tuned from OpenAI Whisper, MIT) |
| Speech models **Whisper** base/small/large-v3-turbo, base.en/small.en (ggml) | OpenAI, converted by ggml – huggingface.co/ggerganov/whisper.cpp, pinned revision + SHA-256 | MIT (© 2022 OpenAI) |
| Sound packs | Kenney (kenney.nl) | CC0 – the download URLs currently fail (open questions A4) |
| Sounds | BigSoundBank (bigsoundbank.com), 75 fixed sounds | CC0 |
| Sound search | Freesound (freesound.org) – CC0 sounds only (license checked per sound), the user's own API key | Sounds CC0; **API use in a paid app not cleared** (open questions A3) |
