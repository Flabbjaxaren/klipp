"""
Klipp's spectral upsampling tables: chromaticity xy -> a smooth spectrum, for the film's exposure.

Written for Klipp from the published method only (W. Jakob, J. Hanika: "A low-dimensional function space for efficient
spectral upsampling", Computer Graphics Forum 38(2), 2019 - spectra as a sigmoid of a quadratic polynomial). No
third-party table or code is used; the inputs are the CIE 1931 2-degree colour-matching functions and the film's
reference illuminant, both as spektrafilm defines them (SPECTRAL_SHAPE 380..780 nm / 5 nm).

The table has the layout spektrafilm's film code expects (and Klipp's film tables are made from):

    T[i, j, :]   81 samples, 380..780 nm in 5 nm steps, for tc = (i/(n-1), j/(n-1)), xy = quad2tri(tc)
    X+Y+Z of T[i, j] (sums against the 81-sample CMFs) = KSUM

The spectrum at each point:

    r(l)  = sigma(c0 u^2 + c1 u + c2)      at 1 nm, 360..800 nm, u = (l - 360) / 440,  sigma(x) = 1/2 + x / (2 sqrt(1 + x^2))
    S     = I * (G r)                      G = gaussian low-pass (BLUR nm), then sampled at the 81 wavelengths
    T     = S scaled to X+Y+Z = KSUM

 * The coefficients are solved so that S has *exactly* the chromaticity g(xy) (damped Newton, homotopy from the flat
   spectrum). The low-pass is part of the model, so the 5 nm samples never alias a steep sigmoid edge - the cause of
   rough tables near the spectral locus - and the result is still exact.
 * I is the film's reference illuminant (D55 for daylight stocks, T for tungsten): r behaves like a reflectance seen
   under the light the film is balanced for, and the table's neutral is that illuminant's own spectrum.
 * The free degree of freedom (3 coefficients, 2 chromaticity constraints): X+Y+Z of I*G r = F * the largest X+Y+Z any
   r in [0, 1] reaches at that chromaticity (the object-colour / MacAdam limit, solved exactly as a linear programme).
 * g(xy) = xy up to saturation D0, then a smooth roll-off towards CAP < 1. Saturation is measured from the table's white
   along the hue ray against the reachable locus, whose corners (the red end against the purple line above all) are
   rounded (soft minimum of the boundary radius over +-ROUND_DEG, TAU). Every point of the square - also outside the
   locus - so gets an exact, bounded spectrum: no holes, no inpainting.
"""
import numpy as np
from scipy.ndimage import gaussian_filter1d
from scipy.optimize import linprog

# The chosen design (see README.md for how it was chosen and measured).
N = 192
F = 0.42
BLUR = 5.0
D0 = 0.9
CAP = 0.98
POWER = 4.0
ROUND_DEG = 3.0
TAU = 0.02

WL = np.arange(380.0, 781.0, 5.0)
WL1 = np.arange(360.0, 801.0, 1.0)
U = (WL1 - 360.0) / 440.0
P1 = np.stack([U * U, U, np.ones_like(U)], axis=1)     # 441 x 3
SEL = np.searchsorted(WL1, WL)


def blur_matrix(sigma_nm):
    m = np.eye(len(WL1)) if sigma_nm <= 0 else gaussian_filter1d(np.eye(len(WL1)), sigma_nm, axis=0, mode="reflect")
    return m[SEL]                                          # 81 x 441


def quad2tri(tc):
    x, y = tc[..., 0], tc[..., 1]
    return np.stack((1 - np.sqrt(x), y * np.sqrt(x)), -1)


def tri2quad(xy):
    tx, ty = xy[..., 0], xy[..., 1]
    y = ty / np.where(1 - tx == 0, 1e-30, 1 - tx)
    x = (1 - tx) ** 2
    return np.stack((np.clip(x, 0, 1), np.clip(y, 0, 1)), -1)


def sig(x):
    return 0.5 + 0.5 * x / np.sqrt(1 + x * x)


def dsig(x):
    return 0.5 / (1 + x * x) ** 1.5


def grid_xy(n):
    v = np.linspace(0, 1, n)
    tx, ty = np.meshgrid(v, v, indexing="ij")
    return quad2tri(np.stack((tx, ty), -1))


def hull_polygon(a):
    """Chromaticities of the columns of a (3 x m: XYZ of each 1 nm band) -> their convex hull: the reachable locus."""
    from scipy.spatial import ConvexHull
    s = a.sum(0)
    keep = s > 1e-9 * s.max()
    pts = (a[:2, keep] / s[keep]).T
    return pts[ConvexHull(pts).vertices]


def ray_sat(xy, poly, w):
    """|xy - w| / |boundary - w| along the ray from w through xy (poly convex, contains w)."""
    v = xy.reshape(-1, 2) - w
    t_hit = np.full(len(v), np.inf)
    for p0, p1 in zip(poly, np.roll(poly, -1, axis=0)):
        e = p1 - p0
        det = e[0] * (-v[:, 1]) - (-v[:, 0]) * e[1]
        r = w - p0
        with np.errstate(divide="ignore", invalid="ignore"):
            s = (r[0] * (-v[:, 1]) - (-v[:, 0]) * r[1]) / det
            t = (e[0] * r[1] - r[0] * e[1]) / det
        ok = (s >= -1e-12) & (s <= 1 + 1e-12) & (t > 0) & np.isfinite(t)
        t_hit[ok] = np.minimum(t_hit[ok], t[ok])
    return (1.0 / t_hit).reshape(xy.shape[:-1])


def rounded_sat(xy, poly, w, round_deg=ROUND_DEG, tau=TAU):
    """Like ray_sat, against a boundary whose radius R(hue) is a soft minimum over a gaussian window of +-round_deg."""
    step = 0.1
    th = np.radians(np.arange(0, 360, step))
    dirs = np.stack([np.cos(th), np.sin(th)], 1)
    R = 1e-3 / ray_sat(w + 1e-3 * dirs, poly, w)
    k = np.arange(-int(4 * round_deg / step), int(4 * round_deg / step) + 1)
    g = np.exp(-0.5 * (k * step / round_deg) ** 2)
    g /= g.sum()
    e = np.exp(-(R - R.min()) / tau)
    Rs = np.minimum(R.min() - tau * np.log(sum(gi * np.roll(e, -ki) for ki, gi in zip(k, g))), R)
    v = xy - w
    h = np.degrees(np.arctan2(v[..., 1], v[..., 0])) % 360
    return np.hypot(v[..., 0], v[..., 1]) / np.interp(h, np.arange(0, 360, step), Rs, period=360)


def knee(d, d0=D0, cap=CAP, p=POWER):
    """Identity below d0, smooth roll-off towards cap (the family of spektrafilm's input gamut knee)."""
    out = np.array(d, dtype=float)
    m = out > d0
    s = cap - d0
    n = (out[m] - d0) / s
    out[m] = d0 + s * n / (1 + n ** p) ** (1 / p)
    return out


def _lp_bmax(args):
    a, c = args
    n = a.shape[1]
    obj = np.zeros(n + 1)
    obj[-1] = -1                                                       # maximise t
    aeq = np.concatenate([a, -c[:, None]], 1)                          # a r = t c,  0 <= r <= 1
    res = linprog(obj, A_eq=aeq, b_eq=np.zeros(3), bounds=[(0, 1)] * n + [(0, None)], method="highs")
    return res.x[-1] if res.status == 0 else np.nan


def macadam_bmax(xy, a, workers):
    from multiprocessing import Pool
    pts = xy.reshape(-1, 2)
    cs = np.stack([pts[:, 0], pts[:, 1], 1 - pts[:, 0] - pts[:, 1]], 1)
    with Pool(workers) as pool:
        out = pool.map(_lp_bmax, [(a, c) for c in cs], chunksize=128)
    return np.array(out).reshape(xy.shape[:-1])


def fit(targets, a, steps=40, iters=6):
    """a sigma(P1 c) = target for each row; homotopy from the flat spectrum with the same X+Y+Z, damped Newton."""
    n = targets.shape[0]
    tot = a.sum(1)
    level = np.clip(targets.sum(1) / tot.sum(), 1e-6, 1 - 1e-6)
    t0 = level[:, None] * tot[None, :]
    u = 2 * level - 1
    c = np.zeros((n, 3))
    c[:, 2] = u / np.sqrt(1 - u * u)
    for s in np.linspace(0, 1, steps + 1)[1:]:
        tgt = (1 - s) * t0 + s * targets
        for _ in range(iters):
            x = c @ P1.T
            r = sig(x) @ a.T - tgt
            jac = np.einsum("kl,nl,lj->nkj", a, dsig(x), P1)
            d = np.nan_to_num(np.linalg.solve(jac + 1e-14 * np.eye(3), r[..., None])[..., 0])
            alpha = np.ones(n)
            best, bestn = c.copy(), np.linalg.norm(r, axis=1)
            for _ls in range(6):
                cn = c - alpha[:, None] * d
                nn = np.linalg.norm(sig(cn @ P1.T) @ a.T - tgt, axis=1)
                ok = nn < bestn
                best[ok], bestn[ok] = cn[ok], nn[ok]
                alpha = np.where(ok, 0, alpha * 0.5)
                if not np.any(alpha > 0):
                    break
            c = best
    res = np.linalg.norm(sig(c @ P1.T) @ a.T - targets, axis=1) / np.linalg.norm(targets, axis=1)
    return c, res


def _fit_chunk(args):
    return fit(*args)


def build_table(cmf, illum, n=N, f=F, blur=BLUR, d0=D0, cap=CAP, p=POWER, round_deg=ROUND_DEG, tau=TAU,
                ksum=None, workers=8, log=print):
    """-> (n x n x 81 table, info). cmf: 81 x 3, illum: 81 (both on WL)."""
    from multiprocessing import Pool
    g = blur_matrix(blur)
    a = cmf.T @ (illum[:, None] * g)                         # 3 x 441: r (1 nm) -> XYZ
    poly = hull_polygon(a)
    w = a.sum(1)[:2] / a.sum()                               # the table's white (r = 1)
    xy = grid_xy(n)
    d = rounded_sat(xy, poly, w, round_deg, tau)
    scale = np.where(d > 0, knee(d, d0, cap, p) / np.where(d > 0, d, 1), 1.0)
    xy_eff = w + (xy - w) * scale[..., None]
    pts = xy_eff.reshape(-1, 2)
    bmax = macadam_bmax(pts, a, workers)
    cvec = np.stack([pts[:, 0], pts[:, 1], 1 - pts.sum(1)], 1)
    targets = (f * bmax)[:, None] * cvec
    parts = np.array_split(np.arange(len(targets)), workers * 2)
    with Pool(workers) as pool:
        out = pool.map(_fit_chunk, [(targets[i], a) for i in parts])
    c = np.concatenate([o[0] for o in out])
    res = np.concatenate([o[1] for o in out])
    slow = np.flatnonzero(res > 1e-9)          # a few far-out purples need a finer homotopy
    if len(slow):
        c2, r2 = fit(targets[slow], a, steps=120, iters=8)
        better = r2 < res[slow]
        c[slow[better]], res[slow[better]] = c2[better], r2[better]
    log(f"  {len(res)} points, chromaticity residual (relative): max {np.nanmax(res):.1e}, > 1e-6 at {(res > 1e-6).sum()}")
    s = (sig(c @ P1.T) @ g.T) * illum[None, :]
    if ksum is None:
        ksum = cmf.sum() / 3.0
    s *= (ksum / (s @ cmf).sum(1))[:, None]
    return s.reshape(n, n, 81), dict(res=res.reshape(n, n), xy_eff=xy_eff, coeffs=c.reshape(n, n, 3), white=w)
