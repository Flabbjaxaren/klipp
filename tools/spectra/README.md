# Klipp's own spectral upsampling tables

The film node exposes the negative to a *spectrum*, not to RGB. For every colour of the picture, a table gives a smooth
spectrum with that colour's chromaticity, and the film's spectral sensitivities turn it into exposure. spektrafilm ships
one such table (`irradiance_xy_tc.npy`, no licence stated for it). Since 2026-11 Klipp's film tables are made from tables
of its own instead, built by `klipp_spectra.py`
here from the published method only and the CIE colour-matching functions and illuminants spektrafilm already uses.

## Method (`klipp_spectra.py`)

Spectra are a sigmoid of a quadratic polynomial (Jakob & Hanika, *A low-dimensional function space for efficient spectral
upsampling*, Eurographics 2019), in the layout spektrafilm expects (192 × 192 triangle-to-square chromaticity grid,
380–780 nm in 5 nm steps). Differences from a plain implementation, each measured below:

* **Exact through an anti-aliasing filter.** The sigmoid is evaluated at 1 nm, low-passed (σ 5 nm) and only then sampled at
  5 nm, and the coefficients are solved so that the *final* 81-sample spectrum has exactly the grid point's chromaticity
  (relative residual < 1e-9 everywhere). The bundled table misses its chromaticity by Δu′v′ 0.002 on average even in
  unsaturated colours.
* **The film's light.** One table per reference illuminant (D55 for daylight stocks, T for the two tungsten stocks): the
  sigmoid is a reflectance seen under that light, so the table's neutral is the illuminant's own spectrum.
* **Brightness.** The free degree of freedom is fixed at 0.42 × the object-colour (MacAdam) limit at that chromaticity,
  solved exactly as a linear programme. 0.42 was chosen on the training sets below (0.35–0.5 are within 1 % of each other).
* **Near the spectral locus.** Saturation (from the table's white, against the reachable locus with its corners rounded)
  is rolled off smoothly above 0.9 towards 0.98, so every point of the square – also outside the locus – has an exact,
  bounded spectrum. No holes, no inpainting.

## Building and using

    python3 tools/spectra/build.py <spektrafilm/src> [<out dir>]                       # ~4 min, writes klipp_spectra_<ill>.npy + manifest.json
    python3 tools/spektrafilm/export_tables.py <spektrafilm/src> <out> <ref.json> --spectra <out dir>
    python3 tools/spektrafilm/reference_params.py <spektrafilm/src> <out.json.gz> --spectra <out dir>
    python3 tools/spectra/evaluate.py <spektrafilm/src> <out dir>                      # the numbers below

Run with spektrafilm's environment (Python 3.13, its requirements; colour-science provides the test spectra).
With `--spectra`, the exports rebalance each film's sensitivities per channel so mid grey gives exactly the exposure it
gave with the bundled table (the profiles are balanced against that table): neutrals and exposure are unchanged, only
colours move (`tools/spektrafilm/klipp_spectra_patch.py`). Balance factors are within 0.97–1.03.

The shipped film tables (`app/src/main/assets/spektrafilm/film/*.tc.bin`) were made from spektrafilm 3bb2c2d with
tables of this sha256 (numpy 2.4, scipy 1.17; `build.py` prints them):

| table | sha256 |
|---|---|
| `klipp_spectra_D55.npy` | `1b8038b7dd9a0f8d32d112aebcff4458616b3f2e491de721a6efe5798c3ce3a6` |
| `klipp_spectra_T.npy` | `efc986fa28505e5e5c46e6abc0c4b9c8d6a6af044522d7ce930a78bef5086c40` |

## Measured against the bundled table (2026-11-19, `evaluate.py`)

**Real colours through all 20 films** – film exposure from the table vs from the measured spectrum through the film's own
sensitivities, RMS error in stops (lower is better). Training sets (used to choose 0.42): rawtoaces' 190 patches,
ColorChecker; held out: CIE 2017 (99), CIE 1995 TCS + NIST CQS (29); LED-like 30 nm lights.

| | rawtoaces 190 | ColorChecker 24 | CIE 2017 99 | TCS + CQS 29 | narrow-band 18 |
|---|---|---|---|---|---|
| bundled | 0.178 | 0.122 | 0.166 | 0.153 | 1.099 |
| Klipp | 0.177 | 0.120 | 0.164 | 0.146 | 1.110 |

Vision3 500T (tungsten): 0.205 → 0.192, 0.129 → 0.114, 0.164 → 0.155, 0.162 → 0.146. About 0.16 stops is a floor for
any smooth-spectrum table (refitting the films' windows to the new tables gained < 0.005); the remaining error is the
films' sensitivities seeing real spectra differently from smooth ones.

**Exactness** – Δu′v′ between a table spectrum and its grid point: saturation < 0.85 mean 0.0020 (bundled) vs 0.0000;
whole locus mean 0.0028 vs 0.0007 (D55; Klipp rolls off above 0.9 on purpose).

**Smoothness of the film tables** – second difference of log₂ exposure across the table (saturation < 0.97), mean over
films of the 99th percentile / worst: bundled 0.279 / 1.875, Klipp 0.117 / 0.702.

**The picture** (full spektrafilm chain, BT.709 test colours, ΔE2000 vs the bundled table): neutrals 0.000; colours with
saturation < 0.3 0.02–0.09 on average; Portra 400, Ektar, Velvia, Gold, Portra 800 push 2: mean 0.23–0.29, max ≤ 1.8;
Vision3 500T mean 0.84, max 5.6 (saturated blues under tungsten – the stock that also gains most above). A hue ×
saturation sweep through Portra 400 + Endura shows no difference in banding (spektrafilm's input gamut compression keeps
inputs off the table's outermost band).
