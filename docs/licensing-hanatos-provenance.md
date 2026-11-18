# Provenance of the hanatos2025 spectral data in Klipp's film tables

Traced 2026-11-18. Only public repositories and release files were read; no third-party program was run (running
vkdt's compiled tool was refused, and wasn't needed – vkdt's own build of the table was compared instead). **Result:
the generating path is established; the licence of the generated data is not.** Nothing is relabelled as BSD, and no
film table was changed.

## The chain

| Step | What | Evidence |
|---|---|---|
| 1 | **Klipp** `app/src/main/assets/spektrafilm/film/<stock>.tc.bin` (20 films) | `tools/spektrafilm/export_tables.py`: spektrafilm's `compute_hanatos2025_tc_lut` = `irradiance_xy_tc.npy` × the film's sensitivity, window-fitted, gamut-compressed |
| 2 | **spektrafilm** `src/spektrafilm/data/luts/spectral_upsampling/irradiance_xy_tc.npy` – 192×192×81 float16, sha256 `52f90c72…a783`, git blob `7b81b9d2` (same blob at `fa02602` and at Klipp's revision `3bb2c2d`) | made by spektrafilm's `scripts/make_spectral_upsampling_luts.py` (`compute_lut_spectra(lut_size=192, …)`) – see the check below |
| 3 | **spektrafilm** `hanatos_irradiance_xy_coeffs_250304.lut` – sha256 `6bfb0317…e40f`, git blob `61c816d5`; added in `fa02602f6ae1985b5f9da2f0b3c0815c446ba52b` (2025-03-05, "spectral upsampling: update lut for hanatos2025 – Thank you again hanatos!"), replacing `…_250221.lut` | vkdt LUT format: header magic 1234, version 2, 4 channels, float32, 512×512; then the text block `mkspectra` writes (below) |
| 4 | **vkdt** `src/tools/spec/mkspectra.c`, run without arguments by vkdt's build (`src/tools/flat.mk`: `../bin/data/spectra-em.lut: mkspectra macadam.lut` → `./mkspectra`), resolution 512, equal-energy (E) white, CIE 1931 2° observer from `src/tools/shared/cie1931.h` | the text block and the timeline below |

## Which vkdt version made the table

- The text block in the 250304 file ("spectral upsampling table for Jakob 2019 style sigmoid spectra. / lookup using
  triangle-to-quad xy chromaticities. / … MacAdams limit.") is **byte-identical** to the one `mkspectra.c` writes from
  `8c46fd6e74afacb724fd9e1d00ea008d60bc3baf` (2025-02-21, "mkspectra: write some info into the lut") until
  `c554327391871ac9694277faf9f79164f8ea0629` (2026-09-20), which added a "created from ssf:" line. So the file was made
  with `mkspectra` from that window.
- In that window the tool changed in `32b4c8d6` (2025-02-21, inpainting), `8aae908edca8a7d5c58dd266a37047bcf84d14e9`
  (2025-03-02 18:53 +0100, "filmsim: smoother inpainting for extreme spectra refs #164") and not again until 2026-09-20.
  spektrafilm's earlier file was named `…_250221` (the day of `8c46fd6e`/`32b4c8d6`), this one `…_250304`, committed
  2025-03-05. In vkdt issue #164 hanatos wrote on 2025-03-02 "i found a few spots where i could make things smoother
  for extreme spectra". Most likely, therefore, **`mkspectra` at `8aae908e`** – the dates and names fit; there is no
  explicit statement.

## Data comparisons

1. **spektrafilm's documented conversion reproduces its npy exactly.** `tools/licenses/provenance/reconvert_spectra.py`
   (written for Klipp from the documented steps at `fa02602`: read the LUT, cubic interpolation on a 192×192 grid,
   sigmoid spectra on 360–800 nm in 1 nm steps divided by channel 4, Gaussian σ = 5 nm, resampled to 380–780 nm in
   5 nm steps, float16) applied to the 250304 LUT gives spektrafilm's `irradiance_xy_tc.npy` with **all 2 985 984
   elements identical**.
2. **vkdt's own build of the table is the same table, not the same file.** vkdt release 1.0.0
   (`afcc804898715d05952066718f4e4c0eba21ed76`, 2025-12-13; `mkspectra.c` as at `8aae908e`), asset
   `vkdt-rawler-1.0.0-win64.zip` (sha256 `f9b261a5…66dc`), file `vkdt/data/spectra-em.lut` (sha256 `b0a75e7b…f179`):
   same size (4 194 741 bytes), identical header and text block; 30.8 % of the 512×512 coefficient texels identical, the
   rest differ slightly (an independent run of the optimiser on another platform). Through the same conversion: 65 %
   of the npy elements identical; where a spectrum carries energy (> 1 % of its peak) the relative difference is
   median 0, 99.9th percentile 0.25 %, max 1.7 %. So spektrafilm's file is a run of the same vkdt tool and
   configuration, not a copy of a file vkdt published.

## Licences along the way

- **vkdt**: "our code is licenced under the 2-clause bsd licence (if not clearly marked otherwise in the respective
  source files …)" (readme; `LICENCE.bsd2`, © 2019 Johannes Hanika; `LICENCE.gpl3` for marked files). `mkspectra.c`
  at `8aae908e` carries no other marking. vkdt states **no licence for the tables its tools generate** (`spectra-em.lut`
  ships in its binary releases without a licence file).
- **Third-party parts of the tool**: `mkspectra.c` uses "the coefficient cube optimiser from the paper: Wenzel Jakob
  and Johannes Hanika, A low-dimensional function space for efficient spectral upsampling, Eurographics 2019"; its
  structure follows Wenzel Jakob's `rgb2spec` (`rgb2spec_opt.cpp`), and vkdt's `src/tools/shared/cie1931.h` is adapted
  from `rgb2spec`'s `details/cie1931.h` – `rgb2spec` is BSD-3-Clause, © 2020 Wenzel Jakob; vkdt's copy doesn't carry
  that notice. `src/tools/shared/lu.h`: "LU decomposition & triangular solving code lifted from Wikipedia". The CIE
  1931 colour-matching functions and illuminants are CIE data. These concern the tool's source code; whether and how
  they bear on the generated table is part of the open question.
- **spektrafilm**: the npy is produced by its own GPL script; the 250304 LUT is distributed in its GPL repository with
  only the credit "Thanks to hanatos for providing luts and sample code".

## What remains open

The rights in the generated coefficient table (and so in tables derived from it) are not stated by anyone. The
generating path is documented, so the question to hanatos can be precise: whether `spectra-em.lut` / the 250304 file
– `mkspectra`'s output – may be used under vkdt's BSD-2 terms (or freely), including derived tables in a paid app, and
how he would like it credited (draft: `docs/licensing-open-questions.md`, A2). Until then the film tables' inventory
entry stays "unclear".
