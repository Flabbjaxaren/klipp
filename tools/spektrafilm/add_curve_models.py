"""
Adds the papers' curve model ("curveModel") to already exported papers (app/src/main/assets/spektrafilm/print)
without recomputing the rest – the same field that export_tables.py writes.

Run:  python add_curve_models.py <spektrafilm/src> <assets/spektrafilm>
"""
import json, os, sys
import numpy as np
SRC, OUT = sys.argv[1], sys.argv[2]
sys.path.insert(0, SRC)
from spektrafilm.profiles.io import load_profile

def arr(a):
    a = np.asarray(a, dtype=float)
    return [None if np.isnan(v) else float(v) for v in a.ravel()]

d = os.path.join(OUT, "print")
for f in sorted(os.listdir(d)):
    if not f.endswith(".json"): continue
    path = os.path.join(d, f)
    meta = json.load(open(path))
    prof = load_profile(meta["id"])
    m = prof.data.density_curves_model
    meta["curveModel"] = {"type": prof.info.type, "centers": arr(m.centers), "amplitudes": arr(m.amplitudes), "sigmas": arr(m.sigmas), "layers": int(m.n_layers)}
    with open(path, "w") as o:
        json.dump(meta, o, separators=(",", ":"))
    print("print", meta["id"], m.n_layers, flush=True)
