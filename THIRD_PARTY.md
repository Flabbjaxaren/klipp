# Third-party software and data

Klipp itself is licensed under the GNU General Public License v3.0 (`LICENSE`). It contains or uses the following.

## Included in this repository

| Component | Where | License | Changes |
|---|---|---|---|
| **Media3 effect** 1.11.1 – © The Android Open Source Project | `third_party/media3-effect` | Apache-2.0 (`third_party/media3-effect/LICENSE`) | Modified: floating point through the video chain, blend modes, diagnostics – see `third_party/media3-effect/KLIPP.md` |
| **whisper.cpp** 1.9.4 and **ggml** – © The ggml authors | `third_party/whisper.cpp` | MIT (`third_party/whisper.cpp/LICENSE`) | Trimmed to the CPU backend – see `third_party/whisper.cpp/KLIPP.md` |
| **spektrafilm** – © Andrea Volpato et al. | ported to `engine/Spektra.kt`; tables in `app/src/main/assets/spektrafilm` | GPL-3.0; film profiles and the tables/LUTs that encode them CC BY-SA 4.0 (`assets/spektrafilm/SPEKTRAFILM_LICENSE.txt`) | Ported to Kotlin/GPU; tables computed with `tools/spektrafilm/export_tables.py` |
| **Silero VAD** v6.2.0 (voice activity detection) – © Silero Team, ggml conversion by ggml-org | `app/src/main/assets/silero-vad-v6.2.0.bin` | MIT | None |
| Spectral upsampling after **hanatos** (vkdt) | `engine/Spektra.kt` | BSD-2-Clause | Ported |
| **OpenDRT** – © Jed Smith | `engine/OpenDrt.kt` | GPL-3.0 | Ported |

## Dependencies fetched by the build

| Component | License |
|---|---|
| **FilmCraft** engine (`filmcraft-engine`, `filmcraft-audio-dsp`, `filmcraft-media`) – © ArtCraft Team and the FilmCraft contributors, https://github.com/storytold/filmcraft (pinned commit in `rust/Cargo.toml`) | MIT OR Apache-2.0 |
| **AndroidX / Jetpack Compose / Media3** (Maven) | Apache-2.0 |
| **Kotlin**, **kotlinx.coroutines** | Apache-2.0 |
| Rust crates (`serde_json`, `jni` …, see `rust/Cargo.lock`) | MIT and/or Apache-2.0 |

## Downloaded by the app at run time (not distributed with Klipp)

| Content | Source | License |
|---|---|---|
| Speech models **KB-Whisper** base/small/medium (ggml, for Swedish) | KBLab, National Library of Sweden – huggingface.co/KBLab | Apache-2.0 |
| Speech models **Whisper** base/small/large-v3-turbo and base.en/small.en (ggml) | OpenAI, converted by ggml – huggingface.co/ggerganov/whisper.cpp | MIT |
| Sound effect packs | Kenney (kenney.nl) | CC0 |
| Sound effect packs | BigSoundBank (bigsoundbank.com) | CC0 |
| Sound search | Freesound (freesound.org) – only CC0 sounds, with the user's own API key | CC0 |

The app's "About" text (Settings) lists the same credits.
