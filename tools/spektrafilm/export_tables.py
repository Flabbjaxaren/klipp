"""
Exports spektrafilm's tables to Klipp (app/src/main/assets/spektrafilm).

Klipp runs spektrafilm's color pipeline (exposure → development → printing → scanning) per pixel on the phone
and bakes it into a 3D LUT. Whatever depends only on the film or the paper is computed here, with spektrafilm's own code:

  * the film's tc-LUT (hanatos2025 spectra × the film's sensitivity, with window fitting and gamut compression)
  * the BT.709/sRGB → XYZ matrix adapted (CAT16) to the film's reference illuminant
  * curves, channel densities, base density, sensitivity (paper), the DIR couplers' values (after digest_params)
  * the scan (density → sRGB-encoded image, with the output's gamut compression) as a 3D LUT per paper and slide film
  * the enlarger's light (TH-KG3), the dichroic filters and the database of neutral filters

Run:  python export_tables.py <spektrafilm/src> <out_dir> [<reference.json>]

spektrafilm: (c) Andrea Volpato et al., GPL-3.0; the profiles CC BY-SA 4.0 – see assets/spektrafilm/LICENSE*.
"""
import json, os, sys
import numpy as np

SRC, OUT = sys.argv[1], sys.argv[2]
REF = sys.argv[3] if len(sys.argv) > 3 else None
sys.path.insert(0, SRC)

import colour
from spektrafilm.profiles.io import load_profile
from spektrafilm.runtime.params_builder import init_params, digest_params
from spektrafilm.runtime.process import Simulator
from spektrafilm.runtime.topology import Tap
from spektrafilm.model.illuminants import standard_illuminant
from spektrafilm.model.color_filters import custom_dichroic_filters
from spektrafilm.utils.spectral_upsampling import _illuminant_to_xy
from spektrafilm.utils.io import read_neutral_print_filters
from spektrafilm.utils.morph_curves import apply_print_curves_morph

SCAN_N = int(os.environ.get("SCAN_N", "33"))

def arr(a):
    a = np.asarray(a, dtype=float)
    return [None if np.isnan(v) else float(v) for v in a.ravel()]

def arr6(a):
    """Like arr, with 6 significant digits (the reference images – the tests compare with a larger margin than that)."""
    return [None if v is None else float("%.6g" % v) for v in arr(a)]

def f16(path, a):
    np.asarray(a, dtype='<f2').tofile(path)

def params_for(film, prnt, scan_film=False):
    p = init_params(film_profile=film, print_profile=prnt)
    p.debug.deactivate_spatial_effects = True
    p.debug.deactivate_stochastic_effects = True
    p.camera.auto_exposure = False
    p.film_render.halation.boost_ev = 0.0
    p.scanner.white_correction = False
    p.scanner.black_correction = False
    p.scanner.unsharp_mask = (0.0, 0.0)
    p.io.input_color_space = "ITU-R BT.709"
    p.io.input_cctf_decoding = False
    p.io.output_color_space = "sRGB"
    p.io.output_cctf_encoding = True
    p.io.scan_film = scan_film
    return p

profiles_dir = os.path.join(SRC, "spektrafilm", "data", "profiles")
stocks = sorted(f[:-5] for f in os.listdir(profiles_dir) if f.endswith(".json"))
films = [s for s in stocks if load_profile(s).info.stage == "filming"]
prints = [s for s in stocks if load_profile(s).info.stage == "printing"]
os.makedirs(os.path.join(OUT, "film"), exist_ok=True)
os.makedirs(os.path.join(OUT, "print"), exist_ok=True)

def scan_lut(sim, dmin, dmax, path):
    """The scan as an N³ LUT over the densities [dmin, dmax] per channel (index [c][m][y], output sRGB-encoded)."""
    g = [np.linspace(dmin[i], dmax[i], SCAN_N) for i in range(3)]
    c, m, y = np.meshgrid(g[0], g[1], g[2], indexing="ij")
    img = np.stack([c, m, y], axis=-1).reshape(SCAN_N, SCAN_N * SCAN_N, 3)
    rgb = sim._pipeline._scanning_stage.scan(img)
    f16(path, np.asarray(rgb).reshape(SCAN_N, SCAN_N, SCAN_N, 3))

def spatial_of(p):
    h, g, d, gl = p.film_render.halation, p.film_render.grain, p.film_render.dir_couplers, p.print_render.glare
    return {
        "halation": {"scatterCoreUm": list(h.scatter_core_um), "scatterTailUm": list(h.scatter_tail_um),
                     "scatterTailWeight": list(h.scatter_tail_weight), "strength": list(h.halation_strength),
                     "firstSigmaUm": list(h.halation_first_sigma_um), "bounces": int(h.halation_n_bounces),
                     "bounceDecay": float(h.halation_bounce_decay), "renormalize": bool(h.halation_renormalize)},
        "grain": {"particleAreaUm2": float(g.particle_area_um2), "particleScale": list(g.particle_scale),
                  "particleScaleLayers": list(g.particle_scale_layers), "densityMin": list(g.density_min),
                  "uniformity": list(g.uniformity), "blur": float(g.blur), "blurDyeCloudsUm": float(g.blur_dye_clouds_um),
                  "microStructure": list(g.micro_structure), "sublayers": bool(g.sublayers_active)},
        "couplers": {"diffusionUm": float(d.diffusion_size_um), "tailUm": float(d.diffusion_tail_um), "tailWeight": float(d.diffusion_tail_weight)},
        "glare": {"percent": float(gl.percent), "roughness": float(gl.roughness), "blur": float(gl.blur)},
        "unsharp": list(p.scanner.unsharp_mask),
    }

def curve_model(prof):
    m = prof.data.density_curves_model
    return {"type": prof.info.type, "centers": arr(m.centers), "amplitudes": arr(m.amplitudes), "sigmas": arr(m.sigmas), "layers": int(m.n_layers)}

index = {"films": [], "prints": [], "scanN": SCAN_N}
default_print = "kodak_portra_endura"

for s in films:
    prof = load_profile(s)
    i = prof.info
    pos = i.type == "positive"
    prnt = i.target_print or default_print
    p = digest_params(params_for(s, prnt, scan_film=pos))
    sim = Simulator(p)
    pl = sim._pipeline
    sens = np.nan_to_num(10 ** np.asarray(p.film.data.log_sensitivity))
    tc = pl._lut_service.get_filming_tc_lut(sens)
    f16(os.path.join(OUT, "film", s + ".tc.bin"), tc)
    illu_xy = _illuminant_to_xy(i.reference_illuminant)
    m709 = np.stack([colour.RGB_to_XYZ(np.eye(3)[k], colourspace="ITU-R BT.709", apply_cctf_decoding=False,
                                       illuminant=illu_xy, chromatic_adaptation_transform="CAT16") for k in range(3)], axis=1)
    msrgb = np.stack([colour.RGB_to_XYZ(np.eye(3)[k], colourspace="sRGB", apply_cctf_decoding=False,
                                        illuminant=illu_xy, chromatic_adaptation_transform="CAT16") for k in range(3)], axis=1)
    dc = p.film_render.dir_couplers
    meta = {
        "id": s, "name": i.name, "type": i.type, "use": i.use, "targetPrint": i.target_print,
        "tcSize": int(tc.shape[0]),
        "logExposure": arr(p.film.data.log_exposure),
        "densityCurves": arr(p.film.data.density_curves),
        "channelDensity": arr(p.film.data.channel_density),
        "baseDensity": arr(p.film.data.base_density),
        "m709": arr(m709), "mSrgb": arr(msrgb), "refXy": arr(illu_xy),
        "couplers": {"self": list(dc.gamma_samelayer_rgb), "r": list(dc.gamma_interlayer_r_to_gb),
                     "g": list(dc.gamma_interlayer_g_to_rb), "b": list(dc.gamma_interlayer_b_to_rg),
                     "inhSelf": dc.inhibition_samelayer, "inhInter": dc.inhibition_interlayer},
        "grainDensityMin": list(p.film_render.grain.density_min),
        "antihalation": i.antihalation,
        # the grain's layers (three sublayers per channel): the density curves per layer, index [le][layer][channel]
        "densityCurvesLayers": arr(p.film.data.density_curves_layers),
        # the spatial and random effects after digest_params (halation after the film's base and antihalation)
        "spatial": spatial_of(digest_params(init_params(film_profile=s, print_profile=prnt))),
    }
    if pos:
        dmin = -np.asarray(p.film_render.grain.density_min)
        dmax = np.nanmax(p.film.data.density_curves, axis=0)
        scan_lut(sim, dmin, dmax, os.path.join(OUT, "film", s + ".scan.bin"))
        meta["scanMin"] = arr(dmin); meta["scanMax"] = arr(dmax)
    with open(os.path.join(OUT, "film", s + ".json"), "w") as f:
        json.dump(meta, f, separators=(",", ":"))
    index["films"].append({"id": s, "name": i.name, "type": i.type, "use": i.use, "targetPrint": i.target_print, "antihalation": i.antihalation})
    print("film", s, flush=True)

for s in prints:
    prof = load_profile(s)
    i = prof.info
    p = digest_params(params_for("kodak_portra_400", s))
    sim = Simulator(p)
    curves = apply_print_curves_morph(p.print.data.log_exposure, p.print.data.density_curves_model,
                                      p.print_render.density_curves_morph, profile_type=p.print.info.type)
    dmin = np.nanmin(p.print.data.density_curves, axis=0)
    dmax = np.nanmax(p.print.data.density_curves, axis=0)
    scan_lut(sim, dmin, dmax, os.path.join(OUT, "print", s + ".scan.bin"))
    meta = {
        "id": s, "name": i.name, "type": i.type,
        "logExposure": arr(p.print.data.log_exposure),
        "densityCurves": arr(curves),
        "sensitivity": arr(np.nan_to_num(10 ** np.asarray(p.print.data.log_sensitivity))),
        "channelDensity": arr(p.print.data.channel_density),
        "baseDensity": arr(p.print.data.base_density),
        "scanMin": arr(dmin), "scanMax": arr(dmax),
        # the curve model (three layers per channel: center, amplitude, width) for the chemistry controls (s023 morph)
        "curveModel": curve_model(prof),
    }
    with open(os.path.join(OUT, "print", s + ".json"), "w") as f:
        json.dump(meta, f, separators=(",", ":"))
    index["prints"].append({"id": s, "name": i.name, "support": i.support})
    print("print", s, flush=True)

index["enlargerIlluminant"] = arr(standard_illuminant("TH-KG3"))
index["dichroic"] = arr(custom_dichroic_filters.filters)
index["neutralFilters"] = read_neutral_print_filters()
with open(os.path.join(OUT, "index.json"), "w") as f:
    json.dump(index, f, separators=(",", ":"))

# ---------------------------------------------------------------------------------------------------------------
# Reference values for Klipp's tests: every stage (taps) for a few film/paper/settings and a grid of colors.
if REF:
    rng = np.random.default_rng(7)
    grid = np.linspace(0.0, 1.0, 6)
    cols = np.stack(np.meshgrid(grid, grid, grid, indexing="ij"), -1).reshape(-1, 3)
    cols = np.concatenate([cols, rng.uniform(0, 1.6, (40, 3)), [[0.184] * 3, [2.0, 2.0, 2.0], [0.0, 0.0, 0.0]]])
    cases = [
        dict(film="kodak_portra_400", print="kodak_portra_endura"),
        dict(film="kodak_vision3_500t", print="kodak_2383", ev=1.0, m=10.0),
        dict(film="kodak_ektar_100", print="fujifilm_crystal_archive_typeii", gamma=0.8, y=-8.0),
        dict(film="fujifilm_velvia_100", print=None, ev=-0.5),
        dict(film="kodak_gold_200", print="kodak_ektacolor_edge", printExposure=1.3, couplers=0.5, ev=0.7),
        dict(film="kodak_portra_800_push2", print="kodak_endura_premier", ev=-1.0, couplers=0.0),
    ]
    out = []
    for c in cases:
        pos = c["print"] is None
        p = params_for(c["film"], c["print"] or default_print, scan_film=pos)
        p.camera.exposure_compensation_ev = c.get("ev", 0.0)
        p.enlarger.m_filter_shift = c.get("m", 0.0)
        p.enlarger.y_filter_shift = c.get("y", 0.0)
        p.enlarger.print_exposure = c.get("printExposure", 1.0)
        p.film_render.density_curve_gamma = c.get("gamma", 1.0)
        p.film_render.dir_couplers.amount = c.get("couplers", 1.0)
        p = digest_params(p)
        sim = Simulator(p)
        img = cols.reshape(1, -1, 3).astype(float)
        taps = {}
        for t in ([Tap.LOG_E_FILM, Tap.CMY_FILM] + ([] if pos else [Tap.LOG_E_PRINT, Tap.CMY_PRINT]) + [Tap.RGB_OUT]):
            taps[t] = arr(sim._pipeline.process(img, collect=t))
        out.append({**c, "neutral": [p.enlarger.c_filter_neutral, p.enlarger.m_filter_neutral, p.enlarger.y_filter_neutral],
                    "input": arr(cols), "taps": taps})
        print("ref", c, flush=True)
    with open(REF, "w") as f:
        json.dump(out, f, separators=(",", ":"))

    REF_SP = REF.replace(".json", "_spatial.json")
    # Spatial effects (halation, scatter, the DIR couplers' diffusion, diffusion filters, lens blur,
    # the scanner's sharpening) on small test images, without randomness (grain and glare off). Each case has its own pixel size;
    # the diffusion filters' kernels fit entirely in the image (spektrafilm otherwise truncates the kernel at half the image size).
    def test_image(W, H):
        yy, xx = np.mgrid[0:H, 0:W]
        u, v = xx / W, yy / H
        img = np.zeros((H, W, 3))
        img[...] = 0.05 + 0.25 * u[..., None] * np.array([1.0, 0.8, 0.6])
        img[(u > 0.3) & (u < 0.55) & (v > 0.3) & (v < 0.6)] = [0.6, 0.15, 0.1]
        img[(np.hypot(u - 0.68, (v - 0.5) * H / W) < 0.05)] = [6.0, 6.0, 5.0]
        img[(u > 0.2) & (u < 0.26) & (v > 0.25) & (v < 0.75)] = [0.02, 0.3, 0.5]
        return img
    sp_cases = [
        dict(film="kodak_portra_400", print="kodak_portra_endura", w=96, h=64, um=21.0),
        dict(film="kodak_vision3_500t", print="kodak_2383", ev=0.5, halation=2.0, w=96, h=64, um=10.0),
        dict(film="fujifilm_velvia_100", print=None, lensBlurUm=30.0, w=96, h=64, um=21.0),
        dict(film="kodak_gold_200", print="kodak_ektacolor_edge", diffusion="black_pro_mist", strength=1.0, w=240, h=160, um=120.0),
        dict(film="kodak_ektar_100", print="fujifilm_crystal_archive_typeii", diffusion="cinebloom", strength=0.5, couplers=0.5, w=240, h=160, um=400.0),
    ]
    out = []
    for c in sp_cases:
        pos = c["print"] is None
        p = init_params(film_profile=c["film"], print_profile=c["print"] or default_print)
        p.camera.auto_exposure = False
        p.camera.exposure_compensation_ev = c.get("ev", 0.0)
        W, H = c["w"], c["h"]
        img = test_image(W, H)
        p.camera.film_format_mm = W * c["um"] / 1000.0
        p.camera.lens_blur_um = c.get("lensBlurUm", 0.0)
        p.film_render.halation.boost_ev = 0.0
        p.film_render.halation.halation_amount = c.get("halation", 1.0)
        p.film_render.grain.active = False
        p.print_render.glare.active = False
        p.film_render.dir_couplers.amount = c.get("couplers", 1.0)
        p.enlarger.print_exposure_compensation = False
        if "diffusion" in c:
            p.camera.diffusion_filter.active = True
            p.camera.diffusion_filter.filter_family = c["diffusion"]
            p.camera.diffusion_filter.strength = c["strength"]
        p.scanner.white_correction = False
        p.scanner.black_correction = False
        p.io.input_color_space = "ITU-R BT.709"
        p.io.input_cctf_decoding = False
        p.io.output_color_space = "sRGB"
        p.io.output_cctf_encoding = True
        p.io.scan_film = pos
        p = digest_params(p)
        sim = Simulator(p)
        taps = {}
        for t in [Tap.LOG_E_FILM, Tap.CMY_FILM, Tap.RGB_OUT]:
            taps[t] = arr6(sim._pipeline.process(img.copy(), collect=t))
        out.append({**c, "pixelUm": float(sim._pipeline._resize_service.pixel_size_um), "input": arr6(img), "taps": taps})
        print("ref spatial", c, sim._pipeline._resize_service.pixel_size_um, flush=True)

    # The grain: the density's spread (standard deviation) on flat areas at a few gray levels, and the mean.
    grain_out = []
    for film, um in [("kodak_portra_400", 21.0), ("kodak_vision3_500t", 10.0), ("fujifilm_velvia_100", 21.0)]:
        pos = load_profile(film).info.type == "positive"
        p = init_params(film_profile=film, print_profile=default_print)
        p.camera.auto_exposure = False
        n = 192
        p.camera.film_format_mm = n * um / 1000.0
        p.film_render.halation.active = False
        p.film_render.dir_couplers.diffusion_size_um = 0.0
        p.print_render.glare.active = False
        p.io.input_color_space = "ITU-R BT.709"
        p.io.input_cctf_decoding = False
        p.io.scan_film = pos
        p = digest_params(p)
        p.film_render.halation.active = False
        p.film_render.dir_couplers.diffusion_size_um = 0.0
        sim = Simulator(p)
        levels = [0.01, 0.05, 0.18, 0.6, 2.0]
        stats = []
        for v in levels:
            flat = np.full((n, n, 3), v)
            d = np.asarray(sim._pipeline.process(flat, collect=Tap.CMY_FILM))
            inner = d[8:-8, 8:-8]
            stats.append({"level": v, "mean": arr(inner.mean(axis=(0, 1))), "std": arr(inner.std(axis=(0, 1)))})
        grain_out.append({"film": film, "pixelUm": um, "stats": stats})
        print("ref grain", film, flush=True)
    with open(REF_SP, "w") as f:
        json.dump({"spatial": out, "grain": grain_out}, f, separators=(",", ":"))
