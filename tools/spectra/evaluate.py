#!/usr/bin/env python3
"""
Measures Klipp's spectral upsampling tables against spektrafilm's bundled hanatos2025 table, the way Klipp uses them.

    python3 tools/spectra/evaluate.py <spektrafilm/src> <spectra dir>     (needs spektrafilm's environment: colour-science)

1. Real colours: measured spectra - reflectances under the film's reference illuminant (rawtoaces' 190 training
   patches, ColorChecker (BabelColor average), the CIE 2017 colour-fidelity set (99), CIE 1995 TCS + NIST CQS (29)) and
   LED-like narrow-band lights (30 nm FWHM, 420-675 nm) - through every film Klipp ships: the film exposure from the
   table's spectrum for the colour's XYZ (as spektrafilm computes it, with the film's window) against the exposure from
   the true spectrum through the film's own sensitivities. Error in stops, each side relative to its own mid grey.
   The data sets are used here only to measure; nothing from them goes into the tables.
2. Colorimetry: how far the table's spectra miss their chromaticity (u'v'), inside the spectral locus.
3. Smoothness: second differences of the film's log exposure across the table (stops), per film, away from the locus.
"""
import csv, copy, gzip, os, sys
import numpy as np

SRC, SPECTRA = sys.argv[1], sys.argv[2]
sys.path.insert(0, SRC)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import colour
from scipy.ndimage import map_coordinates
from spektrafilm.config import STANDARD_OBSERVER_CMFS, SPECTRAL_SHAPE
import spektrafilm.utils.spectral_upsampling as su
from spektrafilm.model.illuminants import standard_illuminant
from spektrafilm.runtime.params_builder import init_params, digest_params
from spektrafilm.runtime.process import Simulator
import klipp_spectra as ks

CMF = np.asarray(STANDARD_OBSERVER_CMFS[:])
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "spektrafilm", "film")
FILMS = sorted(f[:-len(".tc.bin")] for f in os.listdir(ASSETS) if f.endswith(".tc.bin"))


def datasets():
    from colour.characterisation import read_training_data_rawtoaces_v1
    al = lambda sd: np.asarray(sd.copy().align(SPECTRAL_SHAPE).values)
    d = {"rawtoaces190": np.asarray(read_training_data_rawtoaces_v1().copy().align(SPECTRAL_SHAPE).values).T,
         "colorchecker24": np.array([al(s) for s in colour.SDS_COLOURCHECKERS["BabelColor Average"].values()])}
    p = os.path.join(os.path.dirname(colour.__file__), "quality", "datasets", "tcs_cfi2017_5_nm.csv.gz")
    rows = np.array([[float(v) for v in r] for r in csv.reader(gzip.open(p, "rt"))])
    d["cie2017_99"] = rows[(rows[:, 0] >= 380) & (rows[:, 0] <= 780), 1:].T
    d["tcs+cqs29"] = np.array([al(s) for s in colour.quality.SDS_TCS["CIE 1995"].values()] +
                              [al(s) for s in colour.quality.SDS_VS["NIST CQS 9.0"].values()])
    d["narrowband18"] = np.array([np.exp(-0.5 * ((ks.WL - c) / (30 / 2.355)) ** 2) for c in range(420, 681, 15)])
    return d


class Film:
    def __init__(self, name):
        self.name = name
        p = digest_params(init_params(film_profile=name))
        self.sens = np.nan_to_num(10 ** np.asarray(p.film.data.log_sensitivity))
        self.adapt = copy.copy(Simulator(p)._pipeline._lut_service.hanatos2025_adaptation)
        self.adapt.apply_surface = False
        self.ill_name = self.adapt.reference_illuminant
        self.ill = np.asarray(standard_illuminant(self.ill_name))

    def lut(self, table):
        old = su.HANATOS2025_SPECTRA_LUT
        su.HANATOS2025_SPECTRA_LUT = table
        try:
            return su.compute_hanatos2025_tc_lut(self.sens, self.adapt)
        finally:
            su.HANATOS2025_SPECTRA_LUT = old


def lookup(lut, xyz):
    b = xyz.sum(-1)
    tc = ks.tri2quad(xyz[:, :2] / np.maximum(b[:, None], 1e-12))
    co = (tc * (lut.shape[0] - 1)).T
    return np.stack([map_coordinates(lut[..., c], co, order=3, mode="nearest") for c in range(3)], -1) * b[:, None]


def errors(lut, film, spectra, emission):
    grey = 0.184 * film.ill[None, :]
    stim = spectra if emission else spectra * film.ill[None, :]
    if emission:
        stim = stim / (stim @ CMF)[:, 1:2].max() * (grey @ CMF)[0, 1] * 2
    pred = lookup(lut, stim @ CMF) / lookup(lut, grey @ CMF)
    true = (stim @ film.sens) / (grey @ film.sens)
    return np.log2(np.maximum(pred, 2 ** -6)) - np.log2(np.maximum(true, 2 ** -6))


def colorimetry(table):
    """u'v' distance between each table spectrum's chromaticity and its grid point, inside the spectral locus:
    (mean, max) where saturation < 0.85, and (mean, max) over the whole inside (Klipp rolls off beyond 0.9 on purpose)."""
    xy = ks.grid_xy(table.shape[0])
    d = ks.ray_sat(xy, ks.hull_polygon(CMF.T), np.array([1 / 3, 1 / 3]))
    xyz = table @ CMF
    uv = lambda X: np.stack([4 * X[..., 0], 9 * X[..., 1]], -1) / (X[..., 0] + 15 * X[..., 1] + 3 * X[..., 2])[..., None]
    e = np.linalg.norm(uv(xyz) - uv(np.concatenate([xy, 1 - xy.sum(-1, keepdims=True)], -1)), axis=-1)
    return e[d < 0.85].mean(), e[d < 0.85].max(), e[d < 1].mean(), e[d < 1].max()


def roughness(lut, table_n):
    L = np.log2(np.maximum(lut, 1e-9))
    d2 = np.zeros_like(L)
    d2[1:-1] += np.abs(L[2:] - 2 * L[1:-1] + L[:-2])
    d2[:, 1:-1] += np.abs(L[:, 2:] - 2 * L[:, 1:-1] + L[:, :-2])
    xy = ks.grid_xy(table_n)
    m = ks.ray_sat(xy, ks.hull_polygon(CMF.T), np.array([1 / 3, 1 / 3])) < 0.97
    m[0], m[-1], m[:, 0], m[:, -1] = False, False, False, False
    v = d2.max(-1)[m]
    return np.percentile(v, 99), v.max()


def main():
    bundled = su.HANATOS2025_SPECTRA_LUT.copy()
    klipp = {f[len("klipp_spectra_"):-4]: np.load(os.path.join(SPECTRA, f)).astype(np.float64)
             for f in os.listdir(SPECTRA) if f.startswith("klipp_spectra_") and f.endswith(".npy")}
    data = datasets()
    films = [Film(n) for n in FILMS]
    acc = {c: ([], []) for c in data}
    rough = ([], [])
    print(f"{'film':24s} ill " + " ".join(f"{c:>17s}" for c in data) + "   (RMS stops, bundled -> Klipp)")
    for f in films:
        lh, lk = f.lut(bundled), f.lut(klipp[f.ill_name])
        row = []
        for c, s in data.items():
            eh, ek = errors(lh, f, s, c.startswith("narrow")), errors(lk, f, s, c.startswith("narrow"))
            acc[c][0].append(eh)
            acc[c][1].append(ek)
            row.append(f"{np.sqrt(np.mean(eh ** 2)):.3f} -> {np.sqrt(np.mean(ek ** 2)):.3f}")
        rough[0].append(roughness(lh, bundled.shape[0]))
        rough[1].append(roughness(lk, klipp[f.ill_name].shape[0]))
        print(f"{f.name:24s} {f.ill_name:3s} " + " ".join(f"{r:>17s}" for r in row))
    print(f"{'all films':28s} " + " ".join(
        f"{np.sqrt(np.mean(np.concatenate(acc[c][0]) ** 2)):.3f} -> {np.sqrt(np.mean(np.concatenate(acc[c][1]) ** 2)):.3f}".rjust(17)
        for c in data))
    print("p95 |error|, all films      " + " ".join(
        f"{np.percentile(np.abs(np.concatenate(acc[c][0])), 95):.3f} -> {np.percentile(np.abs(np.concatenate(acc[c][1])), 95):.3f}".rjust(17)
        for c in data))
    print("\ncolorimetry (u'v' miss: saturation < 0.85 mean / max | whole locus mean / max)")
    print("  bundled   %.6f / %.4f | %.5f / %.4f" % colorimetry(bundled))
    for ill, t in sorted(klipp.items()):
        print(f"  Klipp {ill:3s}" + " %.6f / %.4f | %.5f / %.4f" % colorimetry(t))
    print("\nsmoothness (second difference of log2 exposure across the table, d < 0.97; mean over films of p99 / worst max)")
    for name, r in (("bundled", rough[0]), ("Klipp", rough[1])):
        print(f"  {name:8s} {np.mean([x[0] for x in r]):.3f} / {max(x[1] for x in r):.3f}")


if __name__ == "__main__":
    main()
