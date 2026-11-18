#!/usr/bin/env python3
"""
Builds Klipp's spectral upsampling tables (klipp_spectra.py) for every reference illuminant spektrafilm's film profiles
use, with spektrafilm's own CMFs and illuminants.

    python3 tools/spectra/build.py <spektrafilm/src> [<out dir>]      (default out: ~/AndroidStudioProjects/Klipp-builds/spectra-1)

Writes <out>/klipp_spectra_<illuminant>.npy (192 x 192 x 81, float32) and <out>/manifest.json (parameters, SHA-256).
tools/spektrafilm/export_tables.py --spectra <out> then makes the film tables from them. Takes a few minutes.
"""
import hashlib, json, os, sys, time
import numpy as np

SRC = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.expanduser("~/AndroidStudioProjects/Klipp-builds/spectra-1")
sys.path.insert(0, SRC)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import klipp_spectra as ks


def main():
    from spektrafilm.config import STANDARD_OBSERVER_CMFS, SPECTRAL_SHAPE
    from spektrafilm.model.illuminants import standard_illuminant
    from spektrafilm.profiles.io import load_profile
    assert np.allclose(SPECTRAL_SHAPE.wavelengths, ks.WL)
    cmf = np.asarray(STANDARD_OBSERVER_CMFS[:])
    pdir = os.path.join(SRC, "spektrafilm", "data", "profiles")
    ills = sorted({load_profile(f[:-5]).info.reference_illuminant for f in os.listdir(pdir)
                   if f.endswith(".json") and load_profile(f[:-5]).info.stage == "filming"})
    os.makedirs(OUT, exist_ok=True)
    manifest = {"method": "klipp_spectra.py (Jakob & Hanika 2019 sigmoid model, exact through a low-pass, MacAdam-scaled)",
                "params": dict(n=ks.N, f=ks.F, blur_nm=ks.BLUR, d0=ks.D0, cap=ks.CAP, power=ks.POWER,
                               round_deg=ks.ROUND_DEG, tau=ks.TAU, ksum="sum(cmf)/3"),
                "numpy": np.__version__, "tables": {}}
    for ill in ills:
        t = time.time()
        print(f"{ill}:", flush=True)
        table, info = ks.build_table(cmf, np.asarray(standard_illuminant(ill)), workers=max(1, (os.cpu_count() or 2) - 2))
        path = os.path.join(OUT, f"klipp_spectra_{ill}.npy")
        np.save(path, table.astype(np.float32))
        sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
        manifest["tables"][ill] = {"file": os.path.basename(path), "sha256": sha, "white_xy": info["white"].tolist(),
                                   "max_relative_residual": float(np.nanmax(info["res"]))}
        print(f"  {path}  sha256 {sha}  ({time.time() - t:.0f} s)", flush=True)
    with open(os.path.join(OUT, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main()
