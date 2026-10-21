"""
Reference images for Klipp's controls that correspond to spektrafilm's (app/src/test/resources/spektrafilm_params_ref.json.gz):
each case is spektrafilm run with one or more controls changed, on a small test image without randomness (grain and glare
off), plus the grain's and the glare's statistics on flat areas. FilmFxParamsTest compares Klipp against them.

Run:  python reference_params.py <spektrafilm/src> <out.json.gz>

spektrafilm: (c) Andrea Volpato et al., GPL-3.0; the profiles CC BY-SA 4.0.
"""
import dataclasses, gzip, json, sys
import numpy as np

SRC, OUT = sys.argv[1], sys.argv[2]
sys.path.insert(0, SRC)

from spektrafilm.profiles.io import load_profile
from spektrafilm.runtime.params_builder import init_params, digest_params
from spektrafilm.runtime.process import Simulator
from spektrafilm.runtime.topology import Tap
from spektrafilm.utils.morph_curves import apply_print_curves_morph

DEFAULT_PRINT = "kodak_portra_endura"


def arr6(a):
    a = np.asarray(a, dtype=float)
    return [None if np.isnan(v) else float("%.6g" % v) for v in a.ravel()]


def test_image(W, H):
    """The same test image as export_tables.py: ramp, red square, blue bar and a highlight (6× white)."""
    yy, xx = np.mgrid[0:H, 0:W]
    u, v = xx / W, yy / H
    img = np.zeros((H, W, 3))
    img[...] = 0.05 + 0.25 * u[..., None] * np.array([1.0, 0.8, 0.6])
    img[(u > 0.3) & (u < 0.55) & (v > 0.3) & (v < 0.6)] = [0.6, 0.15, 0.1]
    img[(np.hypot(u - 0.68, (v - 0.5) * H / W) < 0.05)] = [6.0, 6.0, 5.0]
    img[(u > 0.2) & (u < 0.26) & (v > 0.25) & (v < 0.75)] = [0.02, 0.3, 0.5]
    return img


def base_params(c, W):
    film = c.get("film", "kodak_portra_400")
    prnt = c.get("print", DEFAULT_PRINT)
    pos = load_profile(film).info.type == "positive"
    p = init_params(film_profile=film, print_profile=prnt or DEFAULT_PRINT)
    p.camera.auto_exposure = False
    p.camera.film_format_mm = W * c.get("um", 21.0) / 1000.0
    p.film_render.grain.active = False
    p.print_render.glare.active = False
    p.io.input_color_space = "ITU-R BT.709"
    p.io.input_cctf_decoding = False
    p.io.output_color_space = "sRGB"
    p.io.output_cctf_encoding = True
    p.io.scan_film = pos
    return p


def apply(p, c):
    """The case's controls – the same names as in spektrafilm (path on RuntimePhotoParams)."""
    for path, val in c.get("set", {}).items():
        parts = path.split(".")
        chain = [p]
        for k in parts[:-1]:
            chain.append(getattr(chain[-1], k))
        v = tuple(val) if isinstance(val, list) else val
        try:
            setattr(chain[-1], parts[-1], v)
        except dataclasses.FrozenInstanceError:
            # frozen groups (the chemistry morph): a new instance in the parent
            setattr(chain[-2], parts[-2], dataclasses.replace(chain[-1], **{parts[-1]: v}))


CASES = [
    dict(name="standard"),
    dict(name="kompensation_ev", set={"camera.exposure_compensation_ev": -1.6, "enlarger.print_exposure": 1.3}),
    dict(name="utan_kompensation_ev", set={"camera.exposure_compensation_ev": 0.8, "enlarger.print_exposure_compensation": False}),
    dict(name="boost", set={"film_render.halation.boost_ev": 4.0, "film_render.halation.protect_ev": 2.0}),
    dict(name="boost_6", set={"film_render.halation.boost_ev": 6.0}),
    dict(name="boost_hog", set={"film_render.halation.boost_ev": 8.0, "film_render.halation.boost_range": 0.7, "film_render.halation.halation_amount": 3.0}),
    dict(name="halation_storlek", set={"film_render.halation.halation_amount": 4.0, "film_render.halation.halation_spatial_scale": 2.5}),
    dict(name="spridning_storlek", set={"film_render.halation.scatter_amount": 0.6, "film_render.halation.scatter_spatial_scale": 2.0}),
    dict(name="kemi_gamma_hog", set={"print_render.density_curves_morph.active": True, "print_render.density_curves_morph.gamma_factor": 1.3}),
    dict(name="kemi_gamma_lag", film="kodak_vision3_500t", print="kodak_2383",
         set={"print_render.density_curves_morph.active": True, "print_render.density_curves_morph.gamma_factor": 0.7}),
    dict(name="kemi_alla", set={"print_render.density_curves_morph.active": True, "print_render.density_curves_morph.gamma_factor": 1.1,
                                "print_render.density_curves_morph.gamma_factor_fast": 1.2, "print_render.density_curves_morph.gamma_factor_slow": 0.9,
                                "print_render.density_curves_morph.gamma_factor_red": 1.1, "print_render.density_curves_morph.gamma_factor_green": 0.95,
                                "print_render.density_curves_morph.gamma_factor_blue": 1.05, "print_render.density_curves_morph.developer_exhaustion": 0.5}),
    dict(name="forstoring_diffusion", um=120.0, w=240, h=160,
         set={"enlarger.diffusion_filter.active": True, "enlarger.diffusion_filter.filter_family": "black_pro_mist", "enlarger.diffusion_filter.strength": 1.0}),
    dict(name="skanner_oskarpa", set={"scanner.lens_blur": 1.5}),
    dict(name="filter_stora", set={"enlarger.m_filter_shift": 60.0, "enlarger.y_filter_shift": -40.0}),
    dict(name="ingen_skarpa", set={"scanner.unsharp_mask": [0.7, 0.0]}),
]

out = {"cases": [], "grain": [], "glare": []}
for c in CASES:
    W, H = c.get("w", 96), c.get("h", 64)
    p = base_params(c, W)
    apply(p, c)
    p = digest_params(p)
    apply(p, c)  # (digest_params may reset halation's defaults – the case's controls apply)
    sim = Simulator(p)
    img = test_image(W, H)
    taps = {t: arr6(sim._pipeline.process(img.copy(), collect=t)) for t in [Tap.LOG_E_FILM, Tap.RGB_OUT]}
    extra = {}
    if not p.io.scan_film:
        # the paper's curves with the chemistry controls (s023 morph) over the paper's log E
        extra["printCurves"] = arr6(apply_print_curves_morph(p.print.data.log_exposure, p.print.data.density_curves_model,
                                                             p.print_render.density_curves_morph, profile_type=p.print.info.type))
        extra["morph"] = dataclasses.asdict(p.print_render.density_curves_morph)
    out["cases"].append({**c, "w": W, "h": H, "pixelUm": float(sim._pipeline._resize_service.pixel_size_um),
                         "input": arr6(img), "taps": taps, **extra})
    print("case", c["name"], flush=True)

# The grain: the density's mean and spread on flat areas (with and without sublayers, and with more blur).
for name, extra in [("lager", {}), ("utan_lager", {"film_render.grain.sublayers_active": False}),
                    ("oskarpa", {"film_render.grain.blur": 1.2}), ("yta", {"film_render.grain.particle_area_um2": 0.5})]:
    film, um, n = "kodak_portra_400", 21.0, 192
    p = init_params(film_profile=film, print_profile=DEFAULT_PRINT)
    p.camera.auto_exposure = False
    p.camera.film_format_mm = n * um / 1000.0
    p.print_render.glare.active = False
    p.io.input_color_space = "ITU-R BT.709"
    p.io.input_cctf_decoding = False
    p = digest_params(p)
    p.film_render.halation.active = False
    p.film_render.dir_couplers.diffusion_size_um = 0.0
    apply(p, {"set": extra})
    sim = Simulator(p)
    stats = []
    for v in [0.01, 0.05, 0.18, 0.6, 2.0]:
        d = np.asarray(sim._pipeline.process(np.full((n, n, 3), v), collect=Tap.CMY_FILM))[8:-8, 8:-8]
        stats.append({"level": v, "mean": arr6(d.mean(axis=(0, 1))), "std": arr6(d.std(axis=(0, 1)))})
    out["grain"].append({"name": name, "film": film, "pixelUm": um, "set": extra, "stats": stats})
    print("grain", name, flush=True)

# The glare: the output's mean and spread on a flat gray area (grain off).
for name, pct, rough in [("standard", 0.03, 0.7), ("starkt", 0.2, 0.5)]:
    n = 128
    p = init_params(film_profile="kodak_portra_400", print_profile=DEFAULT_PRINT)
    p.camera.auto_exposure = False
    p.camera.film_format_mm = n * 21.0 / 1000.0
    p.film_render.grain.active = False
    p.io.input_color_space = "ITU-R BT.709"
    p.io.input_cctf_decoding = False
    p.io.output_cctf_encoding = False
    p.scanner.unsharp_mask = (0.0, 0.0)
    p = digest_params(p)
    p.film_render.halation.active = False
    p.print_render.glare.percent = pct
    p.print_render.glare.roughness = rough
    sim = Simulator(p)
    rgb = np.asarray(sim._pipeline.process(np.full((n, n, 3), 0.05)))[4:-4, 4:-4]
    p.print_render.glare.active = False
    ref = np.asarray(Simulator(p)._pipeline.process(np.full((n, n, 3), 0.05)))[4:-4, 4:-4]
    out["glare"].append({"name": name, "percent": pct, "roughness": rough,
                         "mean": arr6(rgb.mean(axis=(0, 1))), "std": arr6(rgb.std(axis=(0, 1))), "without": arr6(ref.mean(axis=(0, 1)))})
    print("glare", name, flush=True)

with gzip.open(OUT, "wt") as f:
    json.dump(out, f, separators=(",", ":"))
