"""
Makes spektrafilm use Klipp's own spectral upsampling tables (tools/spectra) instead of its bundled hanatos2025 table.
Used by export_tables.py and reference_params.py with --spectra <dir>, so the film tables and the reference values for
Klipp's tests come from the same tables.

  * every film profile, when loaded, makes the table for its reference illuminant (klipp_spectra_<illuminant>.npy) the
    active one (spektrafilm reads utils.spectral_upsampling.HANATOS2025_SPECTRA_LUT when it builds the film's LUT);
  * its sensitivities are rebalanced per channel so that mid grey (0.184, BT.2020, at the film's illuminant, with the
    film's window as spektrafilm applies it) gives exactly the film exposure it gives with the bundled table - the
    profiles are balanced against that table. Neutrals and exposure stay as they were; only colours move.
"""
import copy
import json
import os
import sys

import numpy as np


def take_spectra_arg(argv):
    """Removes '--spectra <dir>' from argv; returns the dir or None."""
    if "--spectra" not in argv:
        return None
    i = argv.index("--spectra")
    d = argv[i + 1]
    del argv[i:i + 2]
    return d


def install(spectra_dir, log=print):
    import spektrafilm.runtime.params_builder as pb
    import spektrafilm.utils.spectral_upsampling as su
    bundled = su.HANATOS2025_SPECTRA_LUT
    manifest = json.load(open(os.path.join(spectra_dir, "manifest.json")))
    tables = {ill: np.load(os.path.join(spectra_dir, t["file"])).astype(np.float64) for ill, t in manifest["tables"].items()}
    load = pb.load_profile
    gains = {}

    def grey_raw(prof, table):
        a = copy.copy(prof.hanatos2025_adaptation())
        a.apply_window, a.apply_surface = True, False                 # spektrafilm's defaults, as the exports use
        su.HANATOS2025_SPECTRA_LUT = table
        lut = su.compute_hanatos2025_tc_lut(np.nan_to_num(10 ** np.asarray(prof.data.log_sensitivity)), a)
        tc, b = su._rgb_to_tc_b(np.array([[[0.184] * 3]]), color_space="ITU-R BT.2020",
                                reference_illuminant=prof.info.reference_illuminant)
        return (su.apply_lut_cubic_2d(lut, tc) * b[..., None]).ravel()

    def load_with_klipp_spectra(stock):
        prof = load(stock)
        if prof.info.stage != "filming":
            return prof
        table = tables[prof.info.reference_illuminant]
        if stock not in gains:
            gains[stock] = grey_raw(prof, bundled) / grey_raw(prof, table)
            log(f"spectra {stock} ({prof.info.reference_illuminant}): balance {np.round(gains[stock], 4)}", file=sys.stderr)
        prof = copy.deepcopy(prof)
        prof.data.log_sensitivity = np.asarray(prof.data.log_sensitivity) + np.log10(gains[stock])[None, :]
        su.HANATOS2025_SPECTRA_LUT = table
        return prof

    pb.load_profile = load_with_klipp_spectra
    return manifest
