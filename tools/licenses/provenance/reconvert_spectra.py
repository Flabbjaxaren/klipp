"""Usage: python3 reconvert_spectra.py <coefficient .lut> <irradiance_xy_tc.npy>   (numpy, scipy)

Independent re-run of spektrafilm's documented conversion (make_spectral_upsampling_luts.py @ fa02602):
coefficient LUT (vkdt .lut) -> irradiance spectra LUT (.npy). Written from the documented steps; compares with the shipped npy."""
import sys, struct, numpy as np
from scipy.interpolate import RegularGridInterpolator
from scipy.ndimage import gaussian_filter
lut_path, npy_path = sys.argv[1], sys.argv[2]
raw = open(lut_path, "rb").read()
magic, ver, ch, dt, wd, ht = struct.unpack("<IHBBII", raw[:16])
assert (magic, ver, ch, dt) == (1234, 2, 4, 1)
data = np.frombuffer(raw, dtype="<f4", count=wd * ht * 4, offset=16).reshape(ht, wd, 4)   # file order: row j, column i
coeffs_lut = np.transpose(data, (1, 0, 2))                                                  # spektrafilm's px[i][j]
size = 192
v = np.linspace(0, 1, size)
tx, ty = np.meshgrid(v, v, indexing="ij")
tc = np.stack((tx, ty), axis=-1)
x = np.linspace(0, 1, coeffs_lut.shape[0])
coeffs = np.stack([RegularGridInterpolator((x, x), coeffs_lut[:, :, i].astype(np.float64), method="cubic")(tc) for i in range(4)], axis=-1)
wl = np.arange(380, 781, 5.0)
wl_up = np.linspace(360, 800, 441)
s = (coeffs[..., 0, None] * wl_up + coeffs[..., 1, None]) * wl_up + coeffs[..., 2, None]
s = 0.5 * s / np.sqrt(s * s + 1.0) + 0.5
s /= coeffs[..., 3][..., None]
s = gaussian_filter(s, 5.0 * 1, axes=-1)
s = np.apply_along_axis(lambda a: np.interp(wl, wl_up, a), -1, s)
mine = s.astype(np.half)
ship = np.load(npy_path)
print("shipped npy:", ship.shape, ship.dtype, "| mine:", mine.shape, mine.dtype)
d = np.abs(mine.astype(np.float64) - ship.astype(np.float64))
rel = d / np.maximum(np.abs(ship.astype(np.float64)), 1e-6)
finite = np.isfinite(ship) & np.isfinite(mine)
print("identical elements: %.4f %%" % (100 * np.mean(mine == ship)))
print("max abs diff %.3g, max rel diff %.3g (finite), elements differing by >1 half-ulp: %d of %d" % (
    d[finite].max(), rel[finite].max(), int(np.sum(d[finite] > np.spacing(np.abs(ship[finite]).astype(np.half)).astype(np.float64))), finite.sum()))
# relative difference of the spectra, where they carry energy (above 1 % of each spectrum's peak)
sh = ship.astype(np.float64); mi = mine.astype(np.float64)
peak = np.nanmax(np.abs(sh), axis=-1, keepdims=True)
mask = finite & (np.abs(sh) > 0.01 * peak)
r = np.abs(mi - sh)[mask] / np.abs(sh)[mask]
print("spectra rel. diff where >1%% of peak: median %.2e, 99th pct %.2e, 99.9th %.2e, max %.2e" % (np.median(r), np.percentile(r, 99), np.percentile(r, 99.9), r.max()))
