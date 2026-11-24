# Third-party software and data

Klipp as distributed (the app and this repository as a whole) is offered under the GNU General Public License,
version 3 only (`GPL-3.0-only`, `LICENSE`); the JNI bridge's own code (`rust/`) is `GPL-3.0-or-later` – see
`docs/licensing.md`. It contains or downloads the following. The complete, machine-readable list – every Rust crate and Maven
library with its exact version, origin and license files – is **`LICENSES/inventory.json`**; the license texts and
notices are in **`LICENSES/`** and ship inside the app (Settings → About → Licenses and source code). Open questions:
`docs/licensing-open-questions.md`.

## In the app's code and native libraries

| Component | Where | License | Changes |
|---|---|---|---|
| **Media3 effect, transformer, ExoPlayer** 1.11.1 – © The Android Open Source Project; Klipp's added files © The Klipp contributors | `third_party/media3-*` (classes.dex) | Apache-2.0 | Modified: 18 files (marked "Modified for Klipp") and 9 added (7 Klipp's own, 2 derived from Media3 files) – see each module's `KLIPP.md` |
| **whisper.cpp** 1.9.4 and **ggml** – © The ggml authors; YaRN code © Jeffrey Quesnelle and Bowen Peng | `third_party/whisper.cpp` → `libklipp_whisper.so` | MIT (`LICENSES/third-party/whisper.cpp/`) | Trimmed to the CPU backend; one added function – see `KLIPP.md` |
| **Cinematic RAW Studio Decoder** (cff9a90) – © The Cinematic RAW Studio Authors | `third_party/craw` → `libklipp_craw.so` | Apache-2.0 (`LICENSES/repo/third_party/craw/`) | Modified: 3 files (marked "Modified for Klipp") – see `third_party/craw/SOURCE.md` |
| **motioncam-decoder** (b931ec0, github.com/mirsadm/motioncam-decoder) – © MotionCam; with **JSON for Modern C++** 3.11.3 – © Niels Lohmann, and **SIMDe** headers – © Evan Nemerson and the SIMDe contributors | `third_party/motioncam-decoder` → `libklipp_mcraw.so` (MotionCam .mcraw import) | Apache-2.0; nlohmann/json and SIMDe MIT (`LICENSES/third-party/motioncam-decoder/`) | None to the source; trimmed to the library and the headers it uses – see `third_party/motioncam-decoder/KLIPP.md` |
| **FilmCraft** engine (`filmcraft-*`, pinned commit in `rust/Cargo.toml`) – © ArtCraft Team and the FilmCraft contributors | `libklipp_bridge.so` | MIT OR Apache-2.0 (+ `NOTICE`) | None |
| **craft-libs** (`craft-media`, `craft-docpath`) – © ArtCraft Team and the craft-libs contributors | `libklipp_bridge.so` | MIT OR Apache-2.0 (+ `NOTICE`) | None |
| **DeepFilterNet** (`deep_filter`, libDF) and its **DeepFilterNet3 model**, pinned commit in `rust/Cargo.toml` – © Hendrik Schröter and the DeepFilterNet contributors; inference with **tract** 0.21.4 (github.com/sonos/tract) – © the tract authors | `libklipp_bridge.so` (the model compiled in) – the rendered noise reduction and speech enhance | MIT OR Apache-2.0 (code and model; `LICENSES/third-party/rust/deep_filter-*/`) | None |
| **Fonts compiled into the engine**: Inter (Regular, Medium, SemiBold, Bold, Italic) © The Inter Project Authors, JetBrains Mono Regular © The JetBrains Mono Project Authors, Noto Serif Regular © The Noto Project Authors | `libklipp_bridge.so` (via filmcraft-text) | OFL-1.1 (`LICENSES/third-party/filmcraft/OFL-*.txt`) | None (byte-identical) |
| **Symphonia** 0.5.5 (13 crates) – © The Project Symphonia Developers | `libklipp_bridge.so` | **MPL-2.0** (`LICENSES/texts/MPL-2.0.txt`); source: crates.io and Klipp's source package | None |
| **ICU4X** crates (`icu_*`, `zerovec`, `yoke`, `tinystr` …) – © Unicode, Inc. | `libklipp_bridge.so` | Unicode-3.0 | None |
| **encoding_rs** 0.8.42 – © Mozilla Foundation | `libklipp_bridge.so` | (Apache-2.0 OR MIT) AND BSD-3-Clause (WHATWG data) | None |
| Other Rust crates (about 140: `serde_json`, `jni`, `zlib-rs` (Zlib), `memchr`, `image` …) | `libklipp_bridge.so` | MIT, Apache-2.0, BSD, Zlib, Unlicense – per crate in `LICENSES/third-party/rust/` | None |
| **FFmpeg** 9.0.2 (only the ProRes decoder, PCM decoders, MOV demuxer and MOV muxer) – © the FFmpeg developers; `jrevdct.c` © Thomas G. Lane (Independent JPEG Group) | built from the release tarball by `tools/ffmpeg/build.sh`, linked statically with Klipp's wrapper → `libklipp_prores.so` (ProRes import) | **LGPL-2.1-or-later** (`LICENSES/third-party/ffmpeg/`); no GPL or non-free parts configured. This software is based in part on the work of the Independent JPEG Group | None – the tarball is unmodified; the source package carries it and the build script, so the library can be rebuilt with a modified FFmpeg |
| **libc++** (Android NDK 29.0.14206865, LLVM) | `libc++_shared.so` | Apache-2.0 WITH LLVM-exception; legacy NCSA (`LICENSES/third-party/libc++/NOTICE.txt`) | None |
| **AndroidX / Jetpack Compose / Media3 common** (Maven), **Kotlin**, **kotlinx.coroutines**, **Guava** | classes.dex | Apache-2.0 | None |
| **Checker Framework qualifiers** 3.43.0 – © the Checker Framework developers | classes.dex | MIT (`LICENSES/third-party/maven/checker-qual-LICENSE.txt`) | None |
| **MediaPipe Tasks** (Vision, Core) 1.2.3 – © The MediaPipe Authors (Google LLC); with TensorFlow Lite, XNNPACK, Abseil and the other parts its `NOTICE` lists | `libmediapipe_tasks_jni.so`, classes.dex – face following | Apache-2.0 (`LICENSES/third-party/mediapipe/NOTICE`, `SOURCE.txt`) | Usage statistics to Google switched off in the build (the logger call rewritten to MediaPipe's no-op logger, the Firebase transport libraries left out) – `SOURCE.txt` |
| **Protocol Buffers Java Lite** 4.26.1 – © Google Inc.; **Flogger** 0.6 – © Google; **Checker Framework compat qualifiers** 2.5.3 (MediaPipe's dependencies) | classes.dex | BSD-3-Clause (`LICENSES/third-party/maven/protobuf-javalite-LICENSE.txt`); Apache-2.0; MIT (`checker-compat-qual-LICENSE.txt`) | None |
| **spektrafilm** (code) – © Andrea Volpato et al. | ported to `engine/Spektra.kt`, `engine/FilmFx.kt`, `engine/FilmGl.kt` | GPL v3 ("GPLv3"; its CITATION.cff says or-later; Klipp uses it under version 3) | Ported to Kotlin/GLSL |
| **OpenDRT** v1.1.0 – Jed Smith | ported to `engine/OpenDrt.kt` | GPL v3 ("License: GPLv3" in its notice; Klipp uses it under version 3) | Ported; SDR and its Rec.2100 HLG encoding, two inputs added |

## Data and fonts in the app (assets)

| Material | Where | License | Changes |
|---|---|---|---|
| **spektrafilm film profiles** → Klipp's film tables – spektrafilm by Andrea Volpato | `assets/spektrafilm` | **CC BY-SA 4.0** (`SPEKTRAFILM_LICENSE.txt`, unchanged) | Converted for Klipp by the Klipp contributors – `CHANGELOG.txt`. Some values come from data whose license is not stated (open questions A2) |
| **Title fonts**, 33 families from Google Fonts (google/fonts commit e7db6ca) – © their authors | `assets/fonts/<family>` | OFL-1.1 (`OFL.txt`); Roboto Slab and Permanent Marker Apache-2.0 (`LICENSE.txt`, `CHANGES.txt`) | 25 families subset to Latin (Inter, DM Sans and Archivo also pinned to one optical size/width). The 8 families with a Reserved Font Name (Abril Fatface, Dancing Script, Libre Baskerville, Lora, Merriweather, Playfair Display, Raleway, Righteous) are the unmodified original files – `docs/licensing-fonts.md` |
| **ONNX Runtime** 1.32.0 (`com.microsoft.onnxruntime:onnxruntime-android`) – © Microsoft Corporation | `libonnxruntime.so`, classes.dex – exact word edges | MIT (third-party notices in the ONNX Runtime repository's `ThirdPartyNotices.txt`) | None |
| **YuNet** face detector 2023mar (OpenCV model zoo) – © 2020 Shiqi Yu | `libklipp_bridge.so` (the model compiled in) – face following where MediaPipe can't run | MIT (`LICENSES/third-party/yunet/`) | Input size made dynamic (shape metadata only) |
| **MediaPipe Face Landmarker** model (BlazeFace short range, Face Mesh V2, Blendshape V2; float16/1, SHA-256 pinned) – © Google LLC | `assets/face_landmarker.task` – face following | Apache-2.0 (`LICENSES/third-party/mediapipe/SOURCE.txt`) | None |
| **Beat This!** small0 (ONNX) – © 2024 Institute of Computational Perception, JKU Linz | `assets/beat_this_small0.onnx` – beat markers | MIT (`LICENSES/third-party/beat-this/`) | None |
| **Silero VAD** v6.2 (ggml conversion, ggml-org/whisper-vad 9ffd54a) – © Silero Team | `assets/silero-vad-v6.2.0.bin` | MIT (`LICENSES/third-party/silero-vad/`) | None |

## Downloaded by the app when the user asks (not in the APK)

| Content | Source | License |
|---|---|---|
| Speech models **KB-Whisper** base/small/medium (ggml) | KBLab, National Library of Sweden – huggingface.co/KBLab, pinned revision + SHA-256 | Apache-2.0 (fine-tuned from OpenAI Whisper, MIT) |
| Word-edge model **wav2vec2 large VoxRex Swedish** (ONNX export by OpenVoiceOS) | KBLab, National Library of Sweden – huggingface.co/OpenVoiceOS/wav2vec2-large-voxrex-swedish-onnx, pinned revision + SHA-256 | CC0-1.0 |
| Speech models **Whisper** base/small/large-v3-turbo, base.en/small.en (ggml) | OpenAI, converted by ggml – huggingface.co/ggerganov/whisper.cpp, pinned revision + SHA-256 | MIT (© 2022 OpenAI) |
| Sound packs | Kenney (kenney.nl) – to be served as versioned, SHA-256-pinned files from Klipp's release `sounds-1` (`tools/sounds`); the old links stopped working | CC0 |
| Sounds | BigSoundBank (bigsoundbank.com), 75 fixed sounds – the same, prepared for `sounds-1` | CC0 |
| Sounds downloaded earlier from Freesound | kept on the phone; the Freesound search was removed from Klipp on 2026-11-18 | CC0 |
