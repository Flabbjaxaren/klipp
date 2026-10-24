//! Power Window tracking: KLT point tracker with a robust similarity transform (scale, rotation, translation).
//!
//! `load` downscales the luma plane (area average) to the working resolution and builds a Gaussian pyramid + Scharr gradients
//! in one of two buffers, so the previous frame is kept. `reset` finds Shi-Tomasi corners in the window, `step` tracks them
//! with pyramidal Lucas-Kanade forward and backward (forward-backward error), estimates the frame's similarity with RANSAC
//! (two-point samples) + least squares and accumulates it. Coordinates: pixels at working resolution, y down;
//! positive rotation = clockwise on screen.
//!
//! The background: a window is almost always drawn larger than the object, so some of the points in it lie on
//! the background – and if the background stands still while the object moves, they drag the estimate along ("the cloud stays
//! behind"). So points in a ring outside the window are tracked too, their common motion (the camera's) is estimated,
//! and points in the window that move like the background over a few frames are excluded from the object's estimate
//! (camera motion compensation + split into static/moving points, as in classic motion segmentation).

/// LK window (even: center between pixels, gives full 4-wide NEON rows).
const WIN: usize = 16;
const HALF: f32 = (WIN as f32 - 1.0) * 0.5;
const MAX_LEVELS: usize = 3;
const MIN_SIDE: usize = 40;
const MAX_PTS: usize = 150;
const LK_ITERS: usize = 15;
const LK_EPS: f32 = 0.02;
/// Minimum eigenvalue (per pixel, intensity²/px²) for LK to trust the window.
const LK_MIN_EIG: f32 = 0.05;
/// Maximum mean residual (intensity 0..255) for a tracked point.
const LK_MAX_RES: f32 = 24.0;
const FB_MAX: f32 = 1.0;
const RANSAC_ITERS: usize = 150;
const INLIER_PX: f32 = 1.5;
/// Shi-Tomasi: 7x7 window, threshold relative to the max and an absolute floor.
const DET_R: usize = 3;
const DET_REL: f32 = 0.01;
const DET_MIN: f32 = 0.5;
/// Points closer to the frame edge than this can't be tracked at level 0.
const MARGIN: usize = WIN / 2 + 2;
/// More points than this are tracked on two threads.
const PAR_MIN: usize = 64;
const SEED: u32 = 0x9E37_79B9;
/// Points are found in the inner part of the window (windows are often drawn a bit larger than the object – the edge is background).
const DET_K: f32 = 0.7;
/// Points that ended up outside window·KEEP_K are dropped (they have slid off onto the background).
const KEEP_K: f32 = 1.1;
/// Background points: in the ring between window·BG_IN and window·BG_OUT.
const BG_IN: f32 = 1.35;
const BG_OUT: f32 = 2.6;
const BG_MAX: usize = 80;
/// Fewer background points than this → find new ones.
const BG_MIN: usize = 40;
/// Minimum number (and fraction ½) of inliers for the background motion to count as estimated.
const BG_MIN_INL: usize = 8;
/// A window point's deviation from the background motion is accumulated with decay (≈ the last ~7 frames);
/// below BG_ACC px after at least BG_AGE frames it moves like the background.
const BG_DECAY: f32 = 0.85;
const BG_ACC: f32 = 1.2;
const BG_AGE: u16 = 3;
/// An object point's deviation from the object's motion is accumulated the same way; above FG_ACC px it is sliding off
/// the object (e.g. a point on the edge that slowly ends up on the background) and is dropped.
const FG_ACC: f32 = 1.0;
/// Stabilization (whole-frame motion): the grid for corner points, points per cell, minimum distance (px),
/// minimum number of points/inliers for an estimate.
const G_COLS: usize = 12;
const G_ROWS: usize = 8;
const G_PER: usize = 3;
const G_MIN_DIST: usize = 8;
const G_MIN: usize = 12;

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Roi {
    pub cx: f32,
    pub cy: f32,
    pub rx: f32,
    pub ry: f32,
    /// 1 = ellipse, 2 = rectangle (anything else counts as ellipse)
    pub shape: i32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TrackResult {
    /// Translation of the window center, normalized to working width/height.
    pub dx: f32,
    pub dy: f32,
    pub scale: f32,
    /// Radians, clockwise positive (y down).
    pub rotation: f32,
    /// 0..1, roughly the inlier fraction (low = lost/held).
    pub quality: f32,
    pub points: u32,
}

impl Default for TrackResult {
    fn default() -> Self {
        TrackResult { dx: 0.0, dy: 0.0, scale: 1.0, rotation: 0.0, quality: 0.0, points: 0 }
    }
}

/// x' = a·x − b·y + tx, y' = b·x + a·y + ty (complex: z' = m·z + t, m = a + ib = s·e^{iθ}).
#[derive(Clone, Copy, Debug, PartialEq)]
struct Sim {
    a: f32,
    b: f32,
    tx: f32,
    ty: f32,
}

impl Sim {
    const ID: Sim = Sim { a: 1.0, b: 0.0, tx: 0.0, ty: 0.0 };
    #[inline]
    fn apply(&self, p: [f32; 2]) -> [f32; 2] {
        [self.a * p[0] - self.b * p[1] + self.tx, self.b * p[0] + self.a * p[1] + self.ty]
    }
    /// f ∘ self
    fn then(&self, f: &Sim) -> Sim {
        let t = f.apply([self.tx, self.ty]);
        Sim { a: f.a * self.a - f.b * self.b, b: f.a * self.b + f.b * self.a, tx: t[0], ty: t[1] }
    }
    fn inv(&self) -> Sim {
        let d = (self.a * self.a + self.b * self.b).max(1e-12);
        let (a, b) = (self.a / d, -self.b / d);
        Sim { a, b, tx: -(a * self.tx - b * self.ty), ty: -(b * self.tx + a * self.ty) }
    }
    fn scale(&self) -> f32 {
        self.a.hypot(self.b)
    }
    fn angle(&self) -> f32 {
        self.b.atan2(self.a)
    }
}

#[derive(Default)]
struct Level {
    w: usize,
    h: usize,
    img: Vec<f32>,
    gx: Vec<f32>,
    gy: Vec<f32>,
}

/// The window in the reference frame, in working pixels.
#[derive(Clone, Copy, Default)]
struct RoiPx {
    cx: f32,
    cy: f32,
    rx: f32,
    ry: f32,
    rect: bool,
}

pub struct Tracker {
    w: usize,
    h: usize,
    /// Most object points (MAX_PTS × the density, see `set_density`).
    max_pts: usize,
    /// The smallest distance between points (px at working resolution).
    min_dist: f32,
    /// Double-buffered pyramids; `cur` is the most recently loaded.
    frames: [Vec<Level>; 2],
    cur: usize,
    loaded: u32,
    fresh: bool,
    // downscaling
    acc: Vec<f32>,
    xmap: Vec<(u32, u32)>,
    xkey: usize,
    tmp: Vec<f32>,
    // detection
    sxx: Vec<f32>,
    sxy: Vec<f32>,
    syy: Vec<f32>,
    hxx: Vec<f32>,
    hxy: Vec<f32>,
    hyy: Vec<f32>,
    eig: Vec<f32>,
    cand: Vec<(f32, u32)>,
    grid: Vec<[f32; 2]>,
    // tracking
    pts: Vec<[f32; 2]>,
    src: Vec<[f32; 2]>,
    dst: Vec<[f32; 2]>,
    src2: Vec<[f32; 2]>,
    dst2: Vec<[f32; 2]>,
    inl: Vec<bool>,
    wts: Vec<f32>,
    /// Per point in `pts`: tracked as background (doesn't count toward the object), number of frames, accumulated deviation
    /// from the background motion.
    isbg: Vec<bool>,
    age: Vec<u16>,
    dev: Vec<[f32; 2]>,
    fdev: Vec<[f32; 2]>,
    idx: Vec<u32>,
    idx2: Vec<u32>,
    // the background
    bg: Vec<[f32; 2]>,
    bsrc: Vec<[f32; 2]>,
    bdst: Vec<[f32; 2]>,
    bidx: Vec<u32>,
    binl: Vec<bool>,
    ones: Vec<f32>,
    bg_last: Sim,
    bg_ok: bool,
    // the object's candidates (without background points)
    csrc: Vec<[f32; 2]>,
    cdst: Vec<[f32; 2]>,
    cwts: Vec<f32>,
    cmap: Vec<u32>,
    cinl: Vec<bool>,
    init_count: usize,
    roi: RoiPx,
    active: bool,
    total: Sim,
    last: Sim,
    last_ok: bool,
    rng: u32,
    last_res: TrackResult,
}

impl Tracker {
    pub fn new(work_w: usize, work_h: usize) -> Self {
        let (w, h) = (work_w.clamp(WIN + 8, 4096), work_h.clamp(WIN + 8, 4096));
        let mk = || {
            let mut v = Vec::new();
            let (mut lw, mut lh) = (w, h);
            loop {
                let n = lw * lh;
                v.push(Level { w: lw, h: lh, img: vec![0.0; n], gx: vec![0.0; n], gy: vec![0.0; n] });
                let (nw, nh) = (lw / 2, lh / 2);
                if v.len() >= MAX_LEVELS || nw.min(nh) < MIN_SIDE {
                    break;
                }
                (lw, lh) = (nw, nh);
            }
            v
        };
        Tracker {
            w,
            h,
            max_pts: MAX_PTS,
            min_dist: 5.0,
            frames: [mk(), mk()],
            cur: 0,
            loaded: 0,
            fresh: false,
            acc: Vec::new(),
            xmap: Vec::new(),
            xkey: usize::MAX,
            tmp: vec![0.0; w * h],
            sxx: Vec::new(),
            sxy: Vec::new(),
            syy: Vec::new(),
            hxx: Vec::new(),
            hxy: Vec::new(),
            hyy: Vec::new(),
            eig: Vec::new(),
            cand: Vec::new(),
            grid: Vec::new(),
            pts: Vec::with_capacity(MAX_PTS),
            src: Vec::with_capacity(MAX_PTS),
            dst: Vec::with_capacity(MAX_PTS),
            src2: Vec::with_capacity(MAX_PTS),
            dst2: Vec::with_capacity(MAX_PTS),
            inl: Vec::with_capacity(MAX_PTS),
            wts: Vec::with_capacity(MAX_PTS),
            isbg: Vec::with_capacity(MAX_PTS),
            age: Vec::with_capacity(MAX_PTS),
            dev: Vec::with_capacity(MAX_PTS),
            fdev: Vec::with_capacity(MAX_PTS),
            idx: Vec::with_capacity(MAX_PTS),
            idx2: Vec::with_capacity(MAX_PTS),
            bg: Vec::with_capacity(BG_MAX),
            bsrc: Vec::with_capacity(BG_MAX),
            bdst: Vec::with_capacity(BG_MAX),
            bidx: Vec::with_capacity(BG_MAX),
            binl: Vec::with_capacity(BG_MAX),
            ones: Vec::with_capacity(BG_MAX),
            bg_last: Sim::ID,
            bg_ok: false,
            csrc: Vec::with_capacity(MAX_PTS),
            cdst: Vec::with_capacity(MAX_PTS),
            cwts: Vec::with_capacity(MAX_PTS),
            cmap: Vec::with_capacity(MAX_PTS),
            cinl: Vec::with_capacity(MAX_PTS),
            init_count: 0,
            roi: RoiPx::default(),
            active: false,
            total: Sim::ID,
            last: Sim::ID,
            last_ok: false,
            rng: SEED,
            last_res: TrackResult::default(),
        }
    }

    /// Downscales an 8-bit luma plane into the new current frame (the previous becomes the prior one). Invalid sizes are ignored.
    pub fn load(&mut self, src: &[u8], row_stride: usize, pixel_stride: usize, src_w: usize, src_h: usize) {
        if src_w == 0 || src_h == 0 || pixel_stride == 0 {
            return;
        }
        let need = (src_h - 1)
            .checked_mul(row_stride)
            .and_then(|a| (src_w - 1).checked_mul(pixel_stride).and_then(|b| a.checked_add(b)))
            .and_then(|a| a.checked_add(1));
        if need.is_none_or(|n| n > src.len()) {
            return;
        }
        self.cur ^= 1;
        let (w, h) = (self.w, self.h);
        // column table: output pixel x covers source columns [x0, x1)
        let key = src_w;
        if self.xkey != key || self.xmap.len() != w {
            self.xmap.clear();
            for x in 0..w {
                let x0 = x * src_w / w;
                let x1 = ((x + 1) * src_w / w).clamp(x0 + 1, src_w);
                self.xmap.push((x0 as u32, x1 as u32));
            }
            self.xkey = key;
        }
        self.acc.resize(src_w, 0.0);
        let lv0 = &mut self.frames[self.cur][0];
        let acc = &mut self.acc[..src_w];
        for oy in 0..h {
            let y0 = oy * src_h / h;
            let y1 = ((oy + 1) * src_h / h).clamp(y0 + 1, src_h);
            // at most ~4 rows per output row (4K → 480 is needlessly expensive otherwise), all columns
            let ystep = ((y1 - y0) / 4).max(1);
            acc.fill(0.0);
            let mut nr = 0u32;
            let mut y = y0;
            while y < y1 {
                let row = &src[y * row_stride..];
                if pixel_stride == 1 {
                    for (a, &v) in acc.iter_mut().zip(&row[..src_w]) {
                        *a += v as f32;
                    }
                } else {
                    for (a, v) in acc.iter_mut().zip(row.iter().step_by(pixel_stride)) {
                        *a += *v as f32;
                    }
                }
                nr += 1;
                y += ystep;
            }
            let out = &mut lv0.img[oy * w..(oy + 1) * w];
            for (o, &(x0, x1)) in out.iter_mut().zip(&self.xmap) {
                let s: f32 = acc[x0 as usize..x1 as usize].iter().sum();
                *o = s / (nr as f32 * (x1 - x0) as f32);
            }
        }
        let lv = &mut self.frames[self.cur];
        for l in 1..lv.len() {
            let (a, b) = lv.split_at_mut(l);
            pyr_down(&a[l - 1], &mut b[0], &mut self.tmp);
        }
        for l in lv.iter_mut() {
            gradients(l);
        }
        self.loaded = self.loaded.saturating_add(1);
        self.fresh = true;
    }

    /// Whole-frame motion from the previously loaded frame to the latest (stabilization): new corner points in a
    /// grid over the whole previous frame ([G_COLS]×[G_ROWS], at most [G_PER] per cell, so no single object
    /// dominates), pyramidal LK with a forward-backward check, RANSAC similarity. Response `[a, b, tx, ty,
    /// quality, points]` in working pixels (q = M·p, see [Sim]); None before the second frame or if there are too few points.
    pub fn global_step(&mut self) -> Option<[f32; 6]> {
        if self.loaded < 2 {
            return None;
        }
        let prev_i = self.cur ^ 1;
        let mut pts = std::mem::take(&mut self.pts);
        pts.clear();
        self.grid_corners(prev_i, &mut pts);
        let pred = if self.last_ok { self.last } else { Sim::ID };
        let pinv = pred.inv();
        self.src.clear();
        self.dst.clear();
        self.idx.clear();
        {
            let (prev, cur) = (&self.frames[prev_i], &self.frames[self.cur]);
            track_points(prev, cur, &pts, 0, &pred, &pinv, &mut self.src, &mut self.dst, &mut self.idx);
        }
        self.pts = pts;
        let n = self.src.len();
        if n < G_MIN {
            self.last_ok = false;
            return None;
        }
        self.wts.clear();
        self.wts.resize(n, 1.0);
        let r = ransac(&self.src, &self.dst, &self.wts, &mut self.inl, &mut self.rng);
        let Some((m, inl)) = r else {
            self.last_ok = false;
            return None;
        };
        if inl < G_MIN {
            self.last_ok = false;
            return None;
        }
        self.last = m;
        self.last_ok = true;
        Some([m.a, m.b, m.tx, m.ty, inl as f32 / n as f32, inl as f32])
    }

    /// Corner points (Shi–Tomasi, 7×7) in frame `fi`, level 0, distributed over a grid.
    fn grid_corners(&mut self, fi: usize, out: &mut Vec<[f32; 2]>) {
        let lv = &self.frames[fi][0];
        let (w, h) = (lv.w, lv.h);
        let r = DET_R;
        if w <= 2 * MARGIN + 2 || h <= 2 * MARGIN + 2 {
            return;
        }
        // structure tensor products, box sums via cumulative sums per row and then per column
        let n = w * h;
        for v in [&mut self.sxx, &mut self.sxy, &mut self.syy, &mut self.hxx, &mut self.hxy, &mut self.hyy] {
            v.resize(n, 0.0);
        }
        for i in 0..n {
            let (gx, gy) = (lv.gx[i], lv.gy[i]);
            self.sxx[i] = gx * gx;
            self.sxy[i] = gx * gy;
            self.syy[i] = gy * gy;
        }
        let d = (2 * r + 1) as f32;
        for (src, dst) in [(&self.sxx, &mut self.hxx), (&self.sxy, &mut self.hxy), (&self.syy, &mut self.hyy)] {
            // horizontal
            for y in 0..h {
                let row = &src[y * w..(y + 1) * w];
                let o = &mut dst[y * w..(y + 1) * w];
                let mut acc = 0f32;
                for x in 0..(2 * r + 1).min(w) {
                    acc += row[x];
                }
                for x in 0..w {
                    o[x] = if x >= r && x + r < w { acc } else { 0.0 };
                    if x >= r && x + r + 1 < w {
                        acc += row[x + r + 1] - row[x - r];
                    }
                }
            }
        }
        self.eig.resize(n, 0.0);
        self.eig.fill(0.0);
        self.tmp.resize(self.tmp.len().max(3 * w), 0.0);
        let norm = 1.0 / (d * d);
        let mut maxe = 0f32;
        for y in r..h - r {
            let (va, rest) = self.tmp.split_at_mut(w);
            let (vb, vc) = rest.split_at_mut(w);
            let vc = &mut vc[..w];
            va.fill(0.0);
            vb.fill(0.0);
            vc.fill(0.0);
            for j in y - r..=y + r {
                let o = j * w;
                for x in 0..w {
                    va[x] += self.hxx[o + x];
                    vb[x] += self.hxy[o + x];
                    vc[x] += self.hyy[o + x];
                }
            }
            for x in MARGIN..w - MARGIN {
                let (a, b, c) = (va[x] * norm, vb[x] * norm, vc[x] * norm);
                let m = 0.5 * (a + c);
                let dd = 0.5 * (a - c);
                let e = m - (dd * dd + b * b).sqrt();
                self.eig[y * w + x] = e;
                if y >= MARGIN && y < h - MARGIN && e > maxe {
                    maxe = e;
                }
            }
        }
        let thr = (maxe * DET_REL).max(DET_MIN);
        if maxe < thr {
            return;
        }
        let (x0, x1, y0, y1) = (MARGIN, w - MARGIN, MARGIN, h - MARGIN);
        let min_d2 = (G_MIN_DIST * G_MIN_DIST) as f32;
        for gy in 0..G_ROWS {
            for gx in 0..G_COLS {
                let cx0 = x0 + (x1 - x0) * gx / G_COLS;
                let cx1 = x0 + (x1 - x0) * (gx + 1) / G_COLS;
                let cy0 = y0 + (y1 - y0) * gy / G_ROWS;
                let cy1 = y0 + (y1 - y0) * (gy + 1) / G_ROWS;
                self.cand.clear();
                for y in cy0.max(1)..cy1.min(h - 1) {
                    for x in cx0.max(1)..cx1.min(w - 1) {
                        let e = self.eig[y * w + x];
                        if e < thr {
                            continue;
                        }
                        let up = &self.eig[(y - 1) * w + x - 1..][..3];
                        let mid = &self.eig[y * w + x - 1..][..3];
                        let dn = &self.eig[(y + 1) * w + x - 1..][..3];
                        if e < up[0] || e < up[1] || e < up[2] || e < mid[0] || e <= mid[2] || e <= dn[0] || e <= dn[1] || e <= dn[2] {
                            continue;
                        }
                        self.cand.push((e, (y * w + x) as u32));
                    }
                }
                self.cand.sort_unstable_by(|a, b| b.0.total_cmp(&a.0).then(a.1.cmp(&b.1)));
                let start = out.len();
                for &(_, i) in &self.cand {
                    if out.len() - start >= G_PER {
                        break;
                    }
                    let p = [(i as usize % w) as f32, (i as usize / w) as f32];
                    if out[start..].iter().all(|q| (q[0] - p[0]).powi(2) + (q[1] - p[1]).powi(2) >= min_d2) {
                        out.push(p);
                    }
                }
            }
        }
    }

    /// Is the tracker following a window (after a `reset` with a loaded frame)?
    pub fn is_active(&self) -> bool {
        self.active
    }

    /// The points currently tracked (after the latest `reset`/`step`), normalized 0..1 to the working frame.
    pub fn points_norm(&self, out: &mut Vec<f32>) {
        let (w, h) = (self.w as f32, self.h as f32);
        // only the object's points (not the ones in the window that follow the background)
        out.extend(self.pts.iter().zip(&self.isbg).filter(|(_, b)| !**b).flat_map(|(p, _)| [p[0] / w, p[1] / h]));
    }

    /// Starts tracking `roi` (normalized) in the most recently loaded frame.
    /// A denser point cloud: `d` (1–4) times as many object points, closer together. More points follow small or
    /// partly covered objects better; each frame takes about `d` times as long. Set before `reset`.
    pub fn set_density(&mut self, d: f32) {
        let d = d.clamp(1.0, 4.0);
        self.max_pts = (MAX_PTS as f32 * d).round() as usize;
        self.min_dist = (5.0 / d.sqrt()).max(2.5);
    }

    pub fn reset(&mut self, roi: Roi) -> TrackResult {
        self.pts.clear();
        self.bg.clear();
        self.bg_ok = false;
        self.bg_last = Sim::ID;
        self.sync_state(true);
        self.total = Sim::ID;
        self.last = Sim::ID;
        self.last_ok = false;
        self.rng = SEED;
        self.fresh = false;
        let fin = |v: f32, d: f32| if v.is_finite() { v } else { d };
        let (w, h) = (self.w as f32, self.h as f32);
        self.roi = RoiPx {
            cx: fin(roi.cx, 0.5).clamp(-1.0, 2.0) * w,
            cy: fin(roi.cy, 0.5).clamp(-1.0, 2.0) * h,
            rx: (fin(roi.rx, 0.1).abs() * w).clamp(1.0, 4.0 * w),
            ry: (fin(roi.ry, 0.1).abs() * h).clamp(1.0, 4.0 * h),
            rect: roi.shape == 2,
        };
        if self.loaded == 0 {
            self.active = false;
            self.last_res = TrackResult::default();
            return self.last_res;
        }
        self.active = true;
        self.detect_fallback();
        self.sync_state(false);
        self.detect_bg();
        self.init_count = self.pts.len();
        let n = self.pts.len();
        self.last_res = TrackResult { quality: (n as f32 / 20.0).min(1.0), points: n as u32, ..TrackResult::default() };
        self.last_res
    }

    /// Tracks from the previous to the most recently loaded frame. Without a new frame (or before `reset`) the previous response is repeated.
    pub fn step(&mut self) -> TrackResult {
        if !self.active || self.loaded < 2 || !self.fresh {
            return self.last_res;
        }
        self.fresh = false;
        let pred = if self.last_ok { self.last } else { Sim::ID };
        let pinv = pred.inv();
        let (prev, cur) = (&self.frames[self.cur ^ 1], &self.frames[self.cur]);
        self.src.clear();
        self.dst.clear();
        self.idx.clear();
        // the background points are tracked with the background's previous motion as the guess
        let bpred = if self.bg_ok { self.bg_last } else { Sim::ID };
        let bpinv = bpred.inv();
        self.bsrc.clear();
        self.bdst.clear();
        self.bidx.clear();
        if self.pts.len() > PAR_MIN {
            // the second half (and the background) on an extra thread (thread start ~tens of µs, pays off with many points)
            let (a, b) = self.pts.split_at(self.pts.len() / 2);
            let base = a.len() as u32;
            let (src2, dst2, idx2) = (&mut self.src2, &mut self.dst2, &mut self.idx2);
            let (bg, bsrc, bdst, bidx) = (&self.bg, &mut self.bsrc, &mut self.bdst, &mut self.bidx);
            src2.clear();
            dst2.clear();
            idx2.clear();
            std::thread::scope(|s| {
                s.spawn(|| {
                    track_points(prev, cur, b, base, &pred, &pinv, src2, dst2, idx2);
                    track_points(prev, cur, bg, 0, &bpred, &bpinv, bsrc, bdst, bidx);
                });
                track_points(prev, cur, a, 0, &pred, &pinv, &mut self.src, &mut self.dst, &mut self.idx);
            });
            self.src.extend_from_slice(&self.src2);
            self.dst.extend_from_slice(&self.dst2);
            self.idx.extend_from_slice(&self.idx2);
        } else {
            track_points(prev, cur, &self.pts, 0, &pred, &pinv, &mut self.src, &mut self.dst, &mut self.idx);
            track_points(prev, cur, &self.bg, 0, &bpred, &bpinv, &mut self.bsrc, &mut self.bdst, &mut self.bidx);
        }
        // the background motion (the camera): RANSAC over the ring's points
        self.ones.clear();
        self.ones.resize(self.bsrc.len(), 1.0);
        let bmodel = ransac(&self.bsrc, &self.bdst, &self.ones, &mut self.binl, &mut self.rng)
            .filter(|&(_, c)| c >= BG_MIN_INL && 2 * c >= self.bsrc.len())
            .map(|(m, _)| m);
        self.bg_ok = bmodel.is_some();
        self.bg.clear();
        if let Some(m) = bmodel {
            self.bg_last = m;
            self.bg.extend(self.bdst.iter().zip(&self.binl).filter(|(_, ok)| **ok).map(|(q, _)| *q));
        }
        let n = self.src.len();
        // window points that move like the background (accumulated over a few frames) don't count toward the object
        let (mut nage, mut ndev, mut like) = (Vec::with_capacity(n), Vec::with_capacity(n), Vec::with_capacity(n));
        for k in 0..n {
            let i = self.idx[k] as usize;
            let (mut a, mut d) = (self.age.get(i).copied().unwrap_or(0), self.dev.get(i).copied().unwrap_or([0.0; 2]));
            match bmodel {
                Some(m) => {
                    let r = m.apply(self.src[k]);
                    d = [d[0] * BG_DECAY + self.dst[k][0] - r[0], d[1] * BG_DECAY + self.dst[k][1] - r[1]];
                    a = a.saturating_add(1);
                }
                None => {
                    // without a background model it can't be determined: start over
                    a = 0;
                    d = [0.0; 2];
                }
            }
            nage.push(a);
            ndev.push(d);
            like.push(bmodel.is_some() && a >= BG_AGE && d[0].hypot(d[1]) < BG_ACC);
        }
        // weight per point: closer to the window center weighs more (the object, not the background behind the edge)
        let wts = &mut self.wts;
        wts.clear();
        {
            let t = &self.total;
            let c = t.apply([self.roi.cx, self.roi.cy]);
            let sc = t.scale().max(1e-3);
            let (rx, ry) = (self.roi.rx * sc, self.roi.ry * sc);
            // ... and a point that has started sliding off the object (accumulated deviation from its motion) weighs less
            let fdev = &self.fdev;
            wts.extend(self.src.iter().zip(&self.idx).map(|(p, &i)| {
                let (u, v) = ((p[0] - c[0]) / rx, (p[1] - c[1]) / ry);
                let d = fdev.get(i as usize).map_or(0.0, |d| d[0].hypot(d[1])) / 0.4;
                (-(u * u + v * v) * 1.5).exp() / (1.0 + d * d)
            }));
        }
        let nf = like.iter().filter(|b| !**b).count();
        // if only some of the points move differently from the background, those are the object; if all move
        // like the background (the object stands still in frame, or the camera doesn't follow it), all are used
        let subset = nf >= 6 && nf < n;
        let est = if subset {
            self.csrc.clear();
            self.cdst.clear();
            self.cwts.clear();
            self.cmap.clear();
            for k in (0..n).filter(|&k| !like[k]) {
                self.csrc.push(self.src[k]);
                self.cdst.push(self.dst[k]);
                self.cwts.push(self.wts[k]);
                self.cmap.push(k as u32);
            }
            let e = ransac(&self.csrc, &self.cdst, &self.cwts, &mut self.cinl, &mut self.rng);
            self.inl.clear();
            self.inl.resize(n, false);
            for (j, &k) in self.cmap.iter().enumerate() {
                self.inl[k as usize] = self.cinl[j];
            }
            e
        } else {
            ransac(&self.src, &self.dst, &self.wts, &mut self.inl, &mut self.rng)
        };
        // the scale as the median of pairwise distance ratios (like Median Flow): insensitive to points on the edge that
        // lag behind toward the background – least squares lets them pull the cloud apart, so the window grows
        let est = est.map(|(f, c)| {
            let s = median_scale(&self.src, &self.dst, &self.inl, &mut self.rng).unwrap_or(f.scale());
            (with_scale(&f, s, &self.src, &self.dst, &self.wts, &self.inl), c)
        });
        let n_eff = if subset { nf } else { n };
        let (mut quality, lost) = match est {
            Some((_, c)) => {
                let r = c as f32 / n_eff.max(1) as f32;
                (r * (c as f32 / 20.0).min(1.0), c < 6 || r < 0.3)
            }
            None => (0.0, true),
        };
        let mut inliers = 0;
        match est {
            Some((f, c)) if !lost => {
                self.total = self.total.then(&f);
                self.last = f;
                self.last_ok = true;
                // kept: the object's inliers, and window points that follow the background (they hold the spot – otherwise
                // new points are searched for there again – but aren't counted)
                self.pts.clear();
                self.isbg.clear();
                self.age.clear();
                let old_fdev = std::mem::take(&mut self.fdev);
                self.dev.clear();
                let bgt2 = INLIER_PX * INLIER_PX;
                for k in 0..n {
                    let on_bg = subset && like[k] && bmodel.is_some_and(|m| {
                        let r = m.apply(self.src[k]);
                        (r[0] - self.dst[k][0]).powi(2) + (r[1] - self.dst[k][1]).powi(2) < bgt2
                    });
                    if self.inl[k] || on_bg {
                        let fd = if self.inl[k] {
                            let i = self.idx[k] as usize;
                            let o = old_fdev.get(i).copied().unwrap_or([0.0; 2]);
                            let r = f.apply(self.src[k]);
                            [o[0] * BG_DECAY + self.dst[k][0] - r[0], o[1] * BG_DECAY + self.dst[k][1] - r[1]]
                        } else {
                            [0.0; 2]
                        };
                        self.pts.push(self.dst[k]);
                        self.isbg.push(!self.inl[k]);
                        self.age.push(nage[k]);
                        self.dev.push(ndev[k]);
                        self.fdev.push(fd);
                    }
                }
                // points that slowly slide off the object are dropped (if enough others remain)
                let slip: Vec<bool> = self.isbg.iter().zip(&self.fdev).map(|(b, d)| *b || d[0].hypot(d[1]) <= FG_ACC).collect();
                if slip.iter().zip(&self.isbg).filter(|(k, b)| **k && !**b).count() >= 12 {
                    retain_by(&mut self.pts, &slip);
                    retain_by(&mut self.isbg, &slip);
                    retain_by(&mut self.age, &slip);
                    retain_by(&mut self.dev, &slip);
                    retain_by(&mut self.fdev, &slip);
                }
                // points that have slid out of the window no longer follow the object
                let keep: Vec<bool> = self.pts.iter().map(|p| self.inside(*p, KEEP_K)).collect();
                retain_by(&mut self.pts, &keep);
                retain_by(&mut self.isbg, &keep);
                retain_by(&mut self.age, &keep);
                retain_by(&mut self.dev, &keep);
                retain_by(&mut self.fdev, &keep);
                inliers = c;
                let fg = self.isbg.iter().filter(|b| !**b).count();
                if fg < 30.max(self.init_count / 2) {
                    let cap = (self.pts.len() + self.max_pts.saturating_sub(fg)).min(self.max_pts + BG_MAX);
                    self.detect(DET_K, cap);
                    self.sync_state(false);
                }
            }
            _ => {
                // lost: hold the transform, find new points in the (held) window
                self.last_ok = false;
                quality = quality.min(0.2);
                self.pts.clear();
                self.sync_state(true);
                self.detect_fallback();
                self.sync_state(false);
            }
        }
        // background points that the object has slid over (or that were lost) are replaced
        let keep: Vec<bool> = self.bg.iter().map(|p| !self.inside(*p, BG_IN * 0.95)).collect();
        retain_by(&mut self.bg, &keep);
        if self.bg.len() < BG_MIN {
            self.detect_bg();
        }
        self.last_res = self.result(quality, inliers as u32);
        self.last_res
    }

    /// Keeps `isbg`/`age`/`dev` the same length as `pts` (new points: object, no history); `clear` = empty them.
    fn sync_state(&mut self, clear: bool) {
        if clear {
            self.isbg.clear();
            self.age.clear();
            self.dev.clear();
            self.fdev.clear();
        }
        let n = self.pts.len();
        self.isbg.resize(n, false);
        self.age.resize(n, 0);
        self.dev.resize(n, [0.0; 2]);
        self.fdev.resize(n, [0.0; 2]);
    }

    /// Background points in the ring around the window.
    fn detect_bg(&mut self) {
        self.detect_in(BG_OUT, BG_IN, true, BG_MAX);
    }

    fn result(&self, quality: f32, points: u32) -> TrackResult {
        let c = [self.roi.cx, self.roi.cy];
        let q = self.total.apply(c);
        TrackResult {
            dx: (q[0] - c[0]) / self.w as f32,
            dy: (q[1] - c[1]) / self.h as f32,
            scale: self.total.scale(),
            rotation: self.total.angle(),
            quality: quality.clamp(0.0, 1.0),
            points,
        }
    }

    /// Points in the inner part of the window; too few (small or smooth object) → the whole window, then a bit outside.
    fn detect_fallback(&mut self) {
        self.detect(DET_K, self.max_pts);
        if self.pts.len() < 12 {
            self.detect(1.0, self.max_pts);
        }
        if self.pts.len() < 8 {
            self.detect(1.2, self.max_pts);
        }
    }

    /// Is `p` (current frame) inside the window (transformed by `total`) enlarged `k` times?
    #[inline]
    fn inside(&self, p: [f32; 2], k: f32) -> bool {
        let t = &self.total;
        let c = t.apply([self.roi.cx, self.roi.cy]);
        let (dx, dy) = (p[0] - c[0], p[1] - c[1]);
        let s2 = (t.a * t.a + t.b * t.b).max(1e-12);
        let qx = (t.a * dx + t.b * dy) / s2 / (k * self.roi.rx);
        let qy = (-t.b * dx + t.a * dy) / s2 / (k * self.roi.ry);
        if self.roi.rect { qx.abs() <= 1.0 && qy.abs() <= 1.0 } else { qx * qx + qy * qy <= 1.0 }
    }

    /// Shi-Tomasi corners in the current frame within window·k, with a minimum distance to each other and to existing points.
    fn detect(&mut self, k: f32, max_total: usize) {
        self.detect_in(k, 0.0, false, max_total);
    }

    /// Like `detect`, but only outside window·`k_in` (if > 0) and to the background points if `bg`.
    fn detect_in(&mut self, k: f32, k_in: f32, bg: bool, max_total: usize) {
        let mut target = std::mem::take(if bg { &mut self.bg } else { &mut self.pts });
        self.detect_into(&mut target, k, k_in, max_total);
        if bg {
            self.bg = target;
        } else {
            self.pts = target;
        }
    }

    fn detect_into(&mut self, target: &mut Vec<[f32; 2]>, k: f32, k_in: f32, max_total: usize) {
        if target.len() >= max_total {
            return;
        }
        let (w, h) = (self.w, self.h);
        let t = self.total;
        let (s, th) = (t.scale(), t.angle());
        let c = t.apply([self.roi.cx, self.roi.cy]);
        let (cs, sn) = (th.cos().abs(), th.sin().abs());
        let hx = s * k * (cs * self.roi.rx + sn * self.roi.ry);
        let hy = s * k * (sn * self.roi.rx + cs * self.roi.ry);
        let clampi = |v: f32, lo: usize, hi: usize| (v.max(lo as f32).min(hi as f32)) as usize;
        let x0 = clampi((c[0] - hx).floor(), MARGIN, w - MARGIN);
        let x1 = clampi((c[0] + hx).ceil() + 1.0, MARGIN, w - MARGIN);
        let y0 = clampi((c[1] - hy).floor(), MARGIN, h - MARGIN);
        let y1 = clampi((c[1] + hy).ceil() + 1.0, MARGIN, h - MARGIN);
        if x1 <= x0 + 2 || y1 <= y0 + 2 {
            return;
        }
        let lv = &self.frames[self.cur][0];
        // structure tensor products in the area expanded by the window radius
        let (ex0, ey0) = (x0 - DET_R, y0 - DET_R);
        let (bw, bh) = (x1 - x0 + 2 * DET_R, y1 - y0 + 2 * DET_R);
        let (rw, rh) = (x1 - x0, y1 - y0);
        for v in [&mut self.sxx, &mut self.sxy, &mut self.syy] {
            v.resize(bw * bh, 0.0);
        }
        for r in 0..bh {
            let o = (ey0 + r) * w + ex0;
            let (gx, gy) = (&lv.gx[o..o + bw], &lv.gy[o..o + bw]);
            let ro = r * bw;
            let (a, b, cc) = (&mut self.sxx[ro..ro + bw], &mut self.sxy[ro..ro + bw], &mut self.syy[ro..ro + bw]);
            for i in 0..bw {
                a[i] = gx[i] * gx[i];
                b[i] = gx[i] * gy[i];
                cc[i] = gy[i] * gy[i];
            }
        }
        // 7x7 box sum: horizontal then vertical
        let d = 2 * DET_R + 1;
        for (s, hs) in [(&self.sxx, &mut self.hxx), (&self.sxy, &mut self.hxy), (&self.syy, &mut self.hyy)] {
            hs.resize(bh * rw, 0.0);
            for r in 0..bh {
                let row = &s[r * bw..(r + 1) * bw];
                let out = &mut hs[r * rw..(r + 1) * rw];
                out.fill(0.0);
                for j in 0..d {
                    for (o, v) in out.iter_mut().zip(&row[j..j + rw]) {
                        *o += *v;
                    }
                }
            }
        }
        self.eig.resize(rw * rh, 0.0);
        self.tmp.resize(self.tmp.len().max(3 * rw), 0.0);
        let norm = 1.0 / (d * d) as f32;
        let mut maxr = 0f32;
        for r in 0..rh {
            let (va, rest) = self.tmp.split_at_mut(rw);
            let (vb, vc) = rest.split_at_mut(rw);
            let vc = &mut vc[..rw];
            va.fill(0.0);
            vb.fill(0.0);
            vc.fill(0.0);
            for j in 0..d {
                let o = (r + j) * rw;
                let (a, b, cc) = (&self.hxx[o..o + rw], &self.hxy[o..o + rw], &self.hyy[o..o + rw]);
                for i in 0..rw {
                    va[i] += a[i];
                    vb[i] += b[i];
                    vc[i] += cc[i];
                }
            }
            let out = &mut self.eig[r * rw..(r + 1) * rw];
            for i in 0..rw {
                let (a, b, cc) = (va[i] * norm, vb[i] * norm, vc[i] * norm);
                let m = 0.5 * (a + cc);
                let dd = 0.5 * (a - cc);
                out[i] = m - (dd * dd + b * b).sqrt();
            }
        }
        // max inside the window
        for r in 0..rh {
            for i in 0..rw {
                let e = self.eig[r * rw + i];
                if e > maxr && self.inside([(x0 + i) as f32, (y0 + r) as f32], k) && !(k_in > 0.0 && self.inside([(x0 + i) as f32, (y0 + r) as f32], k_in)) {
                    maxr = e;
                }
            }
        }
        let thr = (maxr * DET_REL).max(DET_MIN);
        if maxr < thr {
            return;
        }
        // local maxima (3x3) above the threshold
        self.cand.clear();
        for r in 1..rh.saturating_sub(1) {
            for i in 1..rw - 1 {
                let e = self.eig[r * rw + i];
                if e < thr {
                    continue;
                }
                let up = &self.eig[(r - 1) * rw + i - 1..][..3];
                let mid = &self.eig[r * rw + i - 1..][..3];
                let dn = &self.eig[(r + 1) * rw + i - 1..][..3];
                // a denser cloud (object points only) takes every textured pixel, not just the local maxima – the
                // minimum distance below spreads them out
                let dense = k_in <= 0.0 && self.max_pts > MAX_PTS;
                if !dense && (e < up[0] || e < up[1] || e < up[2] || e < mid[0] || e <= mid[2] || e <= dn[0] || e <= dn[1] || e <= dn[2]) {
                    continue;
                }
                let ins = self.inside([(x0 + i) as f32, (y0 + r) as f32], k) && !(k_in > 0.0 && self.inside([(x0 + i) as f32, (y0 + r) as f32], k_in));
                if ins {
                    self.cand.push((e, (r * rw + i) as u32));
                }
            }
        }
        self.cand.sort_unstable_by(|a, b| b.0.total_cmp(&a.0).then(a.1.cmp(&b.1)));
        // minimum distance via a grid (cell side md/√2 → at most one point per cell)
        let area = 4.0 * hx * hy;
        let md = (0.5 * (area / (if k_in > 0.0 { BG_MAX } else { self.max_pts }) as f32).sqrt()).max(if k_in > 0.0 { 5.0 } else { self.min_dist });
        let cell = md / std::f32::consts::SQRT_2;
        let gw = (w as f32 / cell) as usize + 1;
        let gh = (h as f32 / cell) as usize + 1;
        self.grid.clear();
        self.grid.resize(gw * gh, [f32::NAN; 2]);
        let cidx = |p: [f32; 2]| ((p[0].max(0.0) / cell) as usize).min(gw - 1) + ((p[1].max(0.0) / cell) as usize).min(gh - 1) * gw;
        for &p in target.iter() {
            self.grid[cidx(p)] = p;
        }
        let md2 = md * md;
        for &(_, idx) in &self.cand {
            if target.len() >= max_total {
                break;
            }
            let (r, i) = (idx as usize / rw, idx as usize % rw);
            let p = [(x0 + i) as f32, (y0 + r) as f32];
            let (gx, gy) = (((p[0] / cell) as usize).min(gw - 1), ((p[1] / cell) as usize).min(gh - 1));
            let mut free = true;
            'n: for yy in gy.saturating_sub(2)..(gy + 3).min(gh) {
                for xx in gx.saturating_sub(2)..(gx + 3).min(gw) {
                    let q = self.grid[yy * gw + xx];
                    if !q[0].is_nan() && (q[0] - p[0]).powi(2) + (q[1] - p[1]).powi(2) < md2 {
                        free = false;
                        break 'n;
                    }
                }
            }
            if free {
                self.grid[gy * gw + gx] = p;
                target.push(p);
            }
        }
    }
}

/// Gaussian 5-tap [1 4 6 4 1]/16, separable, only at decimated positions.
fn pyr_down(s: &Level, d: &mut Level, tmp: &mut Vec<f32>) {
    let (w, h, w2, h2) = (s.w, s.h, d.w, d.h);
    tmp.resize(h * w2, 0.0);
    const K: [f32; 5] = [1.0 / 16.0, 4.0 / 16.0, 6.0 / 16.0, 4.0 / 16.0, 1.0 / 16.0];
    for y in 0..h {
        let row = &s.img[y * w..(y + 1) * w];
        let out = &mut tmp[y * w2..(y + 1) * w2];
        for (x2, o) in out.iter_mut().enumerate() {
            let c = 2 * x2;
            *o = if c >= 2 && c + 2 < w {
                let r = &row[c - 2..c + 3];
                K[0] * r[0] + K[1] * r[1] + K[2] * r[2] + K[3] * r[3] + K[4] * r[4]
            } else {
                (0..5).map(|i| K[i] * row[(c + i).saturating_sub(2).min(w - 1)]).sum()
            };
        }
    }
    for y2 in 0..h2 {
        let c = 2 * y2;
        let r: [usize; 5] = std::array::from_fn(|i| (c + i).saturating_sub(2).min(h - 1));
        let out = &mut d.img[y2 * w2..(y2 + 1) * w2];
        let rows: [&[f32]; 5] = std::array::from_fn(|i| &tmp[r[i] * w2..(r[i] + 1) * w2]);
        for (x, o) in out.iter_mut().enumerate() {
            *o = K[0] * rows[0][x] + K[1] * rows[1][x] + K[2] * rows[2][x] + K[3] * rows[3][x] + K[4] * rows[4][x];
        }
    }
}

/// Scharr gradients (normalized, intensity/px); the edge is zeroed.
fn gradients(l: &mut Level) {
    let (w, h) = (l.w, l.h);
    let n = w - 2;
    l.gx[..w].fill(0.0);
    l.gy[..w].fill(0.0);
    l.gx[(h - 1) * w..].fill(0.0);
    l.gy[(h - 1) * w..].fill(0.0);
    for y in 1..h - 1 {
        let r0 = &l.img[(y - 1) * w..y * w];
        let r1 = &l.img[y * w..(y + 1) * w];
        let r2 = &l.img[(y + 1) * w..(y + 2) * w];
        let (a0, a1, a2) = (&r0[..n], &r0[1..n + 1], &r0[2..n + 2]);
        let (b0, b2) = (&r1[..n], &r1[2..n + 2]);
        let (c0, c1, c2) = (&r2[..n], &r2[1..n + 1], &r2[2..n + 2]);
        let gx = &mut l.gx[y * w..(y + 1) * w];
        gx[0] = 0.0;
        gx[w - 1] = 0.0;
        let gx = &mut gx[1..n + 1];
        for i in 0..n {
            gx[i] = (3.0 * (a2[i] - a0[i]) + 10.0 * (b2[i] - b0[i]) + 3.0 * (c2[i] - c0[i])) * (1.0 / 32.0);
        }
        let gy = &mut l.gy[y * w..(y + 1) * w];
        gy[0] = 0.0;
        gy[w - 1] = 0.0;
        let gy = &mut gy[1..n + 1];
        for i in 0..n {
            gy[i] = (3.0 * (c0[i] - a0[i]) + 10.0 * (c1[i] - a1[i]) + 3.0 * (c2[i] - a2[i])) * (1.0 / 32.0);
        }
    }
}

/// The window's top-left integer corner and bilinear weights, if the window (+1 for interpolation) fits.
#[inline]
fn window(w: usize, h: usize, x: f32, y: f32) -> Option<(usize, usize, [f32; 4])> {
    let (fx, fy) = (x - HALF, y - HALF);
    if !(fx >= 0.0 && fy >= 0.0) {
        return None;
    }
    let (x0, y0) = (fx as usize, fy as usize);
    if x0 + WIN + 1 > w || y0 + WIN + 1 > h {
        return None;
    }
    let (ax, ay) = (fx - x0 as f32, fy - y0 as f32);
    Some((x0, y0, [(1.0 - ax) * (1.0 - ay), ax * (1.0 - ay), (1.0 - ax) * ay, ax * ay]))
}

#[inline]
fn sample(img: &[f32], w: usize, x0: usize, y0: usize, k: [f32; 4], out: &mut [f32; WIN * WIN]) {
    for (r, o) in out.chunks_exact_mut(WIN).enumerate() {
        let a = &img[(y0 + r) * w + x0..][..WIN + 1];
        let b = &img[(y0 + r + 1) * w + x0..][..WIN + 1];
        for i in 0..WIN {
            o[i] = k[0] * a[i] + k[1] * a[i + 1] + k[2] * b[i] + k[3] * b[i + 1];
        }
    }
}

/// Pyramidal LK for a point `p` (level 0) with initial guess `guess`. Returns the new position and mean residual.
fn lk(prev: &[Level], cur: &[Level], p: [f32; 2], guess: [f32; 2]) -> Option<([f32; 2], f32)> {
    let nl = prev.len().min(cur.len());
    let top = (1u32 << (nl - 1)) as f32;
    let (mut fx, mut fy) = ((guess[0] - p[0]) / top, (guess[1] - p[1]) / top);
    let mut tp = [0f32; WIN * WIN];
    let mut ix = [0f32; WIN * WIN];
    let mut iy = [0f32; WIN * WIN];
    let mut res = f32::INFINITY;
    for l in (0..nl).rev() {
        let sc = 1.0 / (1u32 << l) as f32;
        let (px, py) = (p[0] * sc, p[1] * sc);
        let (lp, lc) = (&prev[l], &cur[l]);
        let Some((x0, y0, k)) = window(lp.w, lp.h, px, py) else {
            if l == 0 {
                return None;
            }
            fx *= 2.0;
            fy *= 2.0;
            continue;
        };
        sample(&lp.img, lp.w, x0, y0, k, &mut tp);
        sample(&lp.gx, lp.w, x0, y0, k, &mut ix);
        sample(&lp.gy, lp.w, x0, y0, k, &mut iy);
        // G per column (vectorizable), summed at the end
        let (mut gxx, mut gxy, mut gyy) = ([0f32; WIN], [0f32; WIN], [0f32; WIN]);
        for (xr, yr) in ix.chunks_exact(WIN).zip(iy.chunks_exact(WIN)) {
            for i in 0..WIN {
                gxx[i] += xr[i] * xr[i];
                gxy[i] += xr[i] * yr[i];
                gyy[i] += yr[i] * yr[i];
            }
        }
        let (a, b, c) = (gxx.iter().sum::<f32>(), gxy.iter().sum::<f32>(), gyy.iter().sum::<f32>());
        let area = (WIN * WIN) as f32;
        let min_eig = (0.5 * (a + c) - (0.25 * (a - c) * (a - c) + b * b).sqrt()) / area;
        let det = a * c - b * b;
        if min_eig < LK_MIN_EIG || det <= f32::EPSILON {
            if l == 0 {
                return None;
            }
            fx *= 2.0;
            fy *= 2.0;
            continue;
        }
        let id = 1.0 / det;
        let mut j = [0f32; WIN * WIN];
        for _ in 0..LK_ITERS {
            let Some((qx0, qy0, kq)) = window(lc.w, lc.h, px + fx, py + fy) else {
                if l == 0 {
                    return None;
                }
                break;
            };
            sample(&lc.img, lc.w, qx0, qy0, kq, &mut j);
            let (mut bx, mut by, mut be) = ([0f32; WIN], [0f32; WIN], [0f32; WIN]);
            for (((jr, tr), xr), yr) in j.chunks_exact(WIN).zip(tp.chunks_exact(WIN)).zip(ix.chunks_exact(WIN)).zip(iy.chunks_exact(WIN)) {
                for i in 0..WIN {
                    let e = jr[i] - tr[i];
                    bx[i] += e * xr[i];
                    by[i] += e * yr[i];
                    be[i] += e.abs();
                }
            }
            let (sx, sy) = (bx.iter().sum::<f32>(), by.iter().sum::<f32>());
            res = be.iter().sum::<f32>() / area;
            let ddx = (c * sx - b * sy) * id;
            let ddy = (a * sy - b * sx) * id;
            fx -= ddx;
            fy -= ddy;
            if !(fx.is_finite() && fy.is_finite()) {
                return None;
            }
            if ddx * ddx + ddy * ddy < LK_EPS * LK_EPS {
                break;
            }
        }
        if l > 0 {
            fx *= 2.0;
            fy *= 2.0;
        }
    }
    Some(([p[0] + fx, p[1] + fy], res))
}

/// Tracks the points forward and backward; keeps those with a small forward-backward error (pairs in `src`/`dst`, the point's
/// index + `base` in `idx`).
#[allow(clippy::too_many_arguments)]
fn track_points(prev: &[Level], cur: &[Level], pts: &[[f32; 2]], base: u32, pred: &Sim, pinv: &Sim, src: &mut Vec<[f32; 2]>, dst: &mut Vec<[f32; 2]>, idx: &mut Vec<u32>) {
    for (i, &p) in pts.iter().enumerate() {
        let Some((q, r)) = lk(prev, cur, p, pred.apply(p)) else { continue };
        if r > LK_MAX_RES {
            continue;
        }
        let Some((b, _)) = lk(cur, prev, q, pinv.apply(q)) else { continue };
        if (b[0] - p[0]).powi(2) + (b[1] - p[1]).powi(2) < FB_MAX * FB_MAX {
            src.push(p);
            dst.push(q);
            idx.push(base + i as u32);
        }
    }
}

/// Median of |q_i − q_j| / |p_i − p_j| over (at most 400 random) pairs of inliers at least 8 px apart.
fn median_scale(src: &[[f32; 2]], dst: &[[f32; 2]], inl: &[bool], rng: &mut u32) -> Option<f32> {
    let ids: Vec<usize> = (0..src.len()).filter(|&i| inl[i]).collect();
    let n = ids.len();
    if n < 4 {
        return None;
    }
    let mut r = Vec::with_capacity(400);
    let mut add = |i: usize, j: usize| {
        let (dp, dq) = ((src[i][0] - src[j][0]).hypot(src[i][1] - src[j][1]), (dst[i][0] - dst[j][0]).hypot(dst[i][1] - dst[j][1]));
        if dp >= 8.0 {
            r.push(dq / dp);
        }
    };
    if n * (n - 1) / 2 <= 400 {
        for a in 0..n {
            for b in a + 1..n {
                add(ids[a], ids[b]);
            }
        }
    } else {
        for _ in 0..400 {
            let (a, b) = (xorshift(rng) as usize % n, xorshift(rng) as usize % n);
            if a != b {
                add(ids[a], ids[b]);
            }
        }
    }
    if r.len() < 3 {
        return None;
    }
    let m = r.len() / 2;
    let (_, med, _) = r.select_nth_unstable_by(m, |a, b| a.total_cmp(b));
    Some(med.clamp(0.8, 1.25))
}

/// `f` with the scale `s` (the angle is kept) and the translation that keeps the weighted centroid in place.
fn with_scale(f: &Sim, s: f32, src: &[[f32; 2]], dst: &[[f32; 2]], w: &[f32], inl: &[bool]) -> Sim {
    let sc = f.scale();
    if !(sc > 1e-6 && s.is_finite()) {
        return *f;
    }
    let (a, b) = (f.a / sc * s, f.b / sc * s);
    let (mut ws, mut mp, mut mq) = (0f32, [0f32; 2], [0f32; 2]);
    for (((p, q), wi), _) in src.iter().zip(dst).zip(w).zip(inl).filter(|(_, m)| **m) {
        ws += wi;
        mp = [mp[0] + wi * p[0], mp[1] + wi * p[1]];
        mq = [mq[0] + wi * q[0], mq[1] + wi * q[1]];
    }
    if ws < 1e-6 {
        return Sim { a, b, ..*f };
    }
    let (mp, mq) = ([mp[0] / ws, mp[1] / ws], [mq[0] / ws, mq[1] / ws]);
    Sim { a, b, tx: mq[0] - (a * mp[0] - b * mp[1]), ty: mq[1] - (b * mp[0] + a * mp[1]) }
}

/// Keeps the elements where `keep` is true (same length).
fn retain_by<T>(v: &mut Vec<T>, keep: &[bool]) {
    let mut it = keep.iter();
    v.retain(|_| *it.next().unwrap_or(&true));
}

#[inline]
fn xorshift(s: &mut u32) -> u32 {
    let mut x = *s;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *s = x;
    x
}

/// Weighted least-squares similarity (closed form, 2D Umeyama without reflection) over the marked pairs.
fn fit(src: &[[f32; 2]], dst: &[[f32; 2]], w: &[f32], mask: &[bool]) -> Option<Sim> {
    let (mut n, mut ws, mut mp, mut mq) = (0usize, 0f32, [0f32; 2], [0f32; 2]);
    for (((p, q), wi), _) in src.iter().zip(dst).zip(w).zip(mask).filter(|(_, m)| **m) {
        n += 1;
        ws += wi;
        mp = [mp[0] + wi * p[0], mp[1] + wi * p[1]];
        mq = [mq[0] + wi * q[0], mq[1] + wi * q[1]];
    }
    if n < 2 || ws < 1e-6 {
        return None;
    }
    mp = [mp[0] / ws, mp[1] / ws];
    mq = [mq[0] / ws, mq[1] / ws];
    let (mut sa, mut sb, mut sp) = (0f32, 0f32, 0f32);
    for (((p, q), wi), _) in src.iter().zip(dst).zip(w).zip(mask).filter(|(_, m)| **m) {
        let (px, py, qx, qy) = (p[0] - mp[0], p[1] - mp[1], q[0] - mq[0], q[1] - mq[1]);
        sa += wi * (px * qx + py * qy);
        sb += wi * (px * qy - py * qx);
        sp += wi * (px * px + py * py);
    }
    if sp < 1e-3 {
        return None;
    }
    let (mut a, mut b) = (sa / sp, sb / sp);
    // the scale change per frame is limited
    let s = a.hypot(b);
    if !(s.is_finite() && s > 1e-6) {
        return None;
    }
    let k = s.clamp(0.8, 1.25) / s;
    a *= k;
    b *= k;
    Some(Sim { a, b, tx: mq[0] - (a * mp[0] - b * mp[1]), ty: mq[1] - (b * mp[0] + a * mp[1]) })
}

fn count_inliers(m: &Sim, src: &[[f32; 2]], dst: &[[f32; 2]], mask: &mut [bool]) -> usize {
    let t2 = INLIER_PX * INLIER_PX;
    let mut c = 0;
    for ((p, q), o) in src.iter().zip(dst).zip(mask.iter_mut()) {
        let r = m.apply(*p);
        *o = (r[0] - q[0]).powi(2) + (r[1] - q[1]).powi(2) < t2;
        c += *o as usize;
    }
    c
}

/// RANSAC with two-point samples (score = sum of the inliers' weights `w`), then two rounds of least squares
/// on the inliers. `inl` receives the final inlier mask.
fn ransac(src: &[[f32; 2]], dst: &[[f32; 2]], w: &[f32], inl: &mut Vec<bool>, rng: &mut u32) -> Option<(Sim, usize)> {
    let n = src.len();
    inl.clear();
    inl.resize(n, false);
    if n < 2 {
        return None;
    }
    let t2 = INLIER_PX * INLIER_PX;
    let (mut best, mut bm, mut best_w) = (0usize, Sim::ID, 0f32);
    let wsum: f32 = w.iter().sum::<f32>().max(1e-6);
    for _ in 0..RANSAC_ITERS {
        let i = xorshift(rng) as usize % n;
        let j = xorshift(rng) as usize % n;
        let (dpx, dpy) = (src[i][0] - src[j][0], src[i][1] - src[j][1]);
        let d2 = dpx * dpx + dpy * dpy;
        if i == j || d2 < 4.0 {
            continue;
        }
        let (dqx, dqy) = (dst[i][0] - dst[j][0], dst[i][1] - dst[j][1]);
        // m = dq / dp
        let a = (dqx * dpx + dqy * dpy) / d2;
        let b = (dqy * dpx - dqx * dpy) / d2;
        let s2 = a * a + b * b;
        if !(0.5..2.0).contains(&s2) {
            continue;
        }
        let m = Sim { a, b, tx: dst[i][0] - (a * src[i][0] - b * src[i][1]), ty: dst[i][1] - (b * src[i][0] + a * src[i][1]) };
        let (mut c, mut cw) = (0usize, 0f32);
        for ((p, q), wi) in src.iter().zip(dst).zip(w) {
            let r = m.apply(*p);
            let ok = (r[0] - q[0]).powi(2) + (r[1] - q[1]).powi(2) < t2;
            c += ok as usize;
            cw += if ok { *wi } else { 0.0 };
        }
        if cw > best_w {
            best_w = cw;
            best = c;
            bm = m;
            if cw >= wsum * 0.95 {
                break;
            }
        }
    }
    if best < 2 {
        return None;
    }
    count_inliers(&bm, src, dst, inl);
    let mut m = bm;
    for _ in 0..2 {
        // weighted: points near the window center (the object) weigh more than the edge, where a point can lie
        // half on the background
        let Some(f) = fit(src, dst, w, inl) else { break };
        let c = count_inliers(&f, src, dst, inl);
        if c < 2 {
            count_inliers(&m, src, dst, inl);
            break;
        }
        m = f;
    }
    let c = count_inliers(&m, src, dst, inl);
    Some((m, c))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    const SW: usize = 960;
    const SH: usize = 540;
    const STRIDE: usize = 1024;
    const WW: usize = 480;
    const WH: usize = 270;

    struct Tex {
        w: usize,
        h: usize,
        v: Vec<f32>,
    }

    impl Tex {
        /// Noise blurred at two scales + sharp spots, normalized around 128.
        fn new(w: usize, h: usize, seed: u32, blobs: usize) -> Tex {
            let mut s = seed | 1;
            let mut v: Vec<f32> = (0..w * h).map(|_| (xorshift(&mut s) % 256) as f32).collect();
            let blur = |v: &mut Vec<f32>, r: usize| {
                let mut t = v.clone();
                for y in 0..h {
                    for x in 0..w {
                        let mut a = 0.0;
                        for d in 0..=2 * r {
                            a += v[y * w + (x + w + d - r) % w];
                        }
                        t[y * w + x] = a / (2 * r + 1) as f32;
                    }
                }
                for y in 0..h {
                    for x in 0..w {
                        let mut a = 0.0;
                        for d in 0..=2 * r {
                            a += t[((y + h + d - r) % h) * w + x];
                        }
                        v[y * w + x] = a / (2 * r + 1) as f32;
                    }
                }
            };
            blur(&mut v, 1);
            blur(&mut v, 1);
            let mean = v.iter().sum::<f32>() / v.len() as f32;
            let sd = (v.iter().map(|x| (x - mean).powi(2)).sum::<f32>() / v.len() as f32).sqrt().max(1e-3);
            for x in v.iter_mut() {
                *x = 128.0 + (*x - mean) / sd * 35.0;
            }
            for _ in 0..blobs {
                let cx = (xorshift(&mut s) as usize % w) as f32;
                let cy = (xorshift(&mut s) as usize % h) as f32;
                let r = 3.0 + (xorshift(&mut s) % 10) as f32;
                let val = (xorshift(&mut s) % 256) as f32;
                let (x0, x1) = ((cx - r).max(0.0) as usize, ((cx + r) as usize + 1).min(w));
                let (y0, y1) = ((cy - r).max(0.0) as usize, ((cy + r) as usize + 1).min(h));
                for y in y0..y1 {
                    for x in x0..x1 {
                        if (x as f32 - cx).powi(2) + (y as f32 - cy).powi(2) <= r * r {
                            v[y * w + x] = 0.5 * v[y * w + x] + 0.5 * val;
                        }
                    }
                }
            }
            Tex { w, h, v }
        }

        fn flat(val: f32) -> Tex {
            Tex { w: 4, h: 4, v: vec![val; 16] }
        }

        fn at(&self, x: f32, y: f32) -> f32 {
            let x = x.clamp(0.0, (self.w - 1) as f32 - 0.001);
            let y = y.clamp(0.0, (self.h - 1) as f32 - 0.001);
            let (x0, y0) = (x as usize, y as usize);
            let (ax, ay) = (x - x0 as f32, y - y0 as f32);
            let i = y0 * self.w + x0;
            let v = &self.v;
            (1.0 - ay) * ((1.0 - ax) * v[i] + ax * v[i + 1]) + ay * ((1.0 - ax) * v[i + self.w] + ax * v[i + self.w + 1])
        }
    }

    /// Foreground spot: patch point u ↦ c + s·R(θ)·u, visible where |u| < r.
    #[derive(Clone, Copy)]
    struct Patch {
        cx: f32,
        cy: f32,
        s: f32,
        th: f32,
        r: f32,
    }

    struct Scene<'a> {
        bg: &'a Tex,
        fg: Option<(&'a Tex, Patch)>,
        pan: (f32, f32),
        occ: Option<(f32, f32, f32, f32)>,
    }

    fn render(sc: &Scene) -> Vec<u8> {
        let mut out = vec![0u8; STRIDE * (SH - 1) + SW];
        for y in 0..SH {
            for x in 0..SW {
                let (fx, fy) = (x as f32, y as f32);
                let mut v = sc.bg.at(fx + sc.pan.0 + 100.0, fy + sc.pan.1 + 100.0);
                if let Some((t, p)) = sc.fg {
                    let (dx, dy) = ((fx - p.cx) / p.s, (fy - p.cy) / p.s);
                    let (c, s) = (p.th.cos(), p.th.sin());
                    let (ux, uy) = (c * dx + s * dy, -s * dx + c * dy);
                    if ux * ux + uy * uy < p.r * p.r {
                        v = t.at(ux + t.w as f32 * 0.5, uy + t.h as f32 * 0.5);
                    }
                }
                if let Some((x0, y0, x1, y1)) = sc.occ
                    && fx >= x0 && fx < x1 && fy >= y0 && fy < y1
                {
                    v = 128.0;
                }
                out[y * STRIDE + x] = v.round().clamp(0.0, 255.0) as u8;
            }
        }
        out
    }

    /// The camera shakes: frame point p = C_t(world point), C_t = similarity (rotation, scale, translation around the center).
    fn cam(t: usize) -> Sim {
        let tf = t as f32;
        let (th, sc) = (0.02 * (0.7 * tf).sin(), 1.0 + 0.012 * (0.5 * tf).sin());
        let (tx, ty) = (9.0 * (1.3 * tf).sin() + 0.8 * tf, 6.0 * (0.9 * tf).cos());
        let (a, b) = (sc * th.cos(), sc * th.sin());
        let (cx, cy) = (SW as f32 * 0.5, SH as f32 * 0.5);
        // around the frame center: p = R(w − c) + c + t
        Sim { a, b, tx: cx + tx - (a * cx - b * cy), ty: cy + ty - (b * cx + a * cy) }
    }

    /// Shaky frame with a large object (radius [fg_r]) that moves on its own within the frame.
    fn render_shaky(bg: &Tex, fg: &Tex, t: usize, fg_r: f32) -> Vec<u8> {
        let inv = cam(t).inv();
        let mut out = vec![0u8; STRIDE * (SH - 1) + SW];
        let (fcx, fcy) = (300.0 + 7.0 * t as f32, 280.0 + 3.0 * t as f32);
        for y in 0..SH {
            for x in 0..SW {
                let q = inv.apply([x as f32, y as f32]);
                let mut v = bg.at(q[0] + 150.0, q[1] + 150.0);
                let (dx, dy) = (x as f32 - fcx, y as f32 - fcy);
                if dx * dx + dy * dy < fg_r * fg_r {
                    v = fg.at(dx + fg.w as f32 * 0.5, dy + fg.h as f32 * 0.5);
                }
                out[y * STRIDE + x] = v.round().clamp(0.0, 255.0) as u8;
            }
        }
        out
    }

    #[test]
    fn global_motion_follows_camera_not_subject() {
        let bg = Tex::new(SW + 300, SH + 300, 11, 400);
        let fg = Tex::new(400, 400, 77, 60);
        let mut tr = Tracker::new(WW, WH);
        let k = WW as f32 / SW as f32;
        let mut worst = 0f32;
        let t0 = Instant::now();
        for t in 0..24 {
            let img = render_shaky(&bg, &fg, t, 130.0);
            tr.load(&img, STRIDE, 1, SW, SH);
            let r = tr.global_step();
            if t == 0 {
                assert!(r.is_none());
                continue;
            }
            let [a, b, tx, ty, q, n] = r.expect("estimate");
            let m = Sim { a, b, tx, ty };
            // expected: M = C_t ∘ C_{t−1}⁻¹ (in source pixels), compared in working pixels
            let exp = cam(t - 1).inv().then(&cam(t));
            for p in [[60.0f32, 40.0], [420.0, 40.0], [60.0, 230.0], [420.0, 230.0], [240.0, 135.0]] {
                let e = exp.apply([p[0] / k, p[1] / k]);
                let g = m.apply(p);
                let d = ((g[0] - e[0] * k).powi(2) + (g[1] - e[1] * k).powi(2)).sqrt();
                worst = worst.max(d);
            }
            assert!(q > 0.4 && n >= 30.0, "t={t} q={q} n={n}");
        }
        eprintln!("global motion: largest error {worst:.3} working px, {:?}", t0.elapsed());
        assert!(worst < 0.35, "error {worst}");
    }

    #[test]
    fn global_motion_flat_frame_gives_none() {
        let flat = vec![128u8; STRIDE * (SH - 1) + SW];
        let mut tr = Tracker::new(WW, WH);
        tr.load(&flat, STRIDE, 1, SW, SH);
        tr.load(&flat, STRIDE, 1, SW, SH);
        assert!(tr.global_step().is_none());
    }

    fn roi_for(p: &Patch) -> Roi {
        Roi { cx: p.cx / SW as f32, cy: p.cy / SH as f32, rx: 0.85 * p.r * p.s / SW as f32, ry: 0.85 * p.r * p.s / SH as f32, shape: 1 }
    }

    fn path(t: usize) -> Patch {
        let tf = t as f32;
        Patch {
            cx: 300.0 + 4.0 * tf + 6.0 * (tf * 0.2).sin(),
            cy: 250.0 + 1.5 * tf,
            s: 1.0 + 0.25 * (tf * std::f32::consts::PI / 70.0).sin(),
            th: 0.004 * tf,
            r: 90.0,
        }
    }

    /// Runs a sequence; `occ(t, patch)` gives an optional occluding rectangle. Returns the max center error (working px) and scale error.
    fn run_patch(frames: usize, occ: impl Fn(usize) -> Option<(f32, f32, f32, f32)>, label: &str) -> (f32, f32, f32, f32) {
        run_patch_dense(frames, occ, label, 1.0)
    }

    fn run_patch_dense(frames: usize, occ: impl Fn(usize) -> Option<(f32, f32, f32, f32)>, label: &str, density: f32) -> (f32, f32, f32, f32) {
        let bg = Tex::new(1200, 800, 7, 400);
        let fg = Tex::new(256, 256, 1234, 120);
        let mut tr = Tracker::new(WW, WH);
        tr.set_density(density);
        let p0 = path(0);
        let (mut emax, mut smax, mut rmax, mut qmin) = (0f32, 0f32, 0f32, 1f32);
        let mut ms = 0f64;
        for t in 0..frames {
            let p = path(t);
            let img = render(&Scene { bg: &bg, fg: Some((&fg, p)), pan: (0.0, 0.0), occ: occ(t) });
            let t0 = Instant::now();
            tr.load(&img, STRIDE, 1, SW, SH);
            let r = if t == 0 { tr.reset(roi_for(&p0)) } else { tr.step() };
            if t > 0 {
                ms += t0.elapsed().as_secs_f64() * 1e3;
            }
            let ex = r.dx * WW as f32 - (p.cx - p0.cx) * 0.5;
            let ey = r.dy * WH as f32 - (p.cy - p0.cy) * 0.5;
            let e = ex.hypot(ey);
            let se = (r.scale / (p.s / p0.s) - 1.0).abs();
            let re = (r.rotation - (p.th - p0.th)).abs();
            emax = emax.max(e);
            smax = smax.max(se);
            rmax = rmax.max(re);
            if t > 0 {
                qmin = qmin.min(r.quality);
            }
            if t % 10 == 0 || e > 1.0 {
                println!("{label} t={t:2} err={e:.2}px scale err={:.2}% rot err={re:.4} q={:.2} pts={}", se * 100.0, r.quality, r.points);
            }
        }
        println!("{label}: {:.3} ms/frame (load+step), max err {emax:.2}px, scale err {:.2}%, rot err {rmax:.4}, min q {qmin:.2}", ms / (frames - 1) as f64, smax * 100.0);
        (emax, smax, rmax, qmin)
    }

    /// Loose window (larger than the object, lots of background in the window) – as when you circle a person.
    /// `path` gives the object per frame, `pan` the camera's panning. Returns the max center and scale error.
    fn run_loose(frames: usize, k: f32, path: impl Fn(usize) -> Patch, pan: impl Fn(usize) -> (f32, f32), label: &str) -> (f32, f32) {
        let bg = Tex::new(1600, 900, 77, 500);
        let fg = Tex::new(256, 256, 4321, 120);
        let mut tr = Tracker::new(WW, WH);
        let p0 = path(0);
        let roi = Roi { cx: p0.cx / SW as f32, cy: p0.cy / SH as f32, rx: k * p0.r * p0.s / SW as f32, ry: k * p0.r * p0.s / SH as f32, shape: 1 };
        let (mut emax, mut smax) = (0f32, 0f32);
        for t in 0..frames {
            let p = path(t);
            let img = render(&Scene { bg: &bg, fg: Some((&fg, p)), pan: pan(t), occ: None });
            tr.load(&img, STRIDE, 1, SW, SH);
            let r = if t == 0 { tr.reset(roi) } else { tr.step() };
            let ex = r.dx * WW as f32 - (p.cx - p0.cx) * 0.5;
            let ey = r.dy * WH as f32 - (p.cy - p0.cy) * 0.5;
            let e = ex.hypot(ey);
            let se = (r.scale / (p.s / p0.s) - 1.0).abs();
            emax = emax.max(e);
            smax = smax.max(se);
            if t % 10 == 0 {
                println!("{label} t={t:2} err={e:.2}px scale err={:.2}% scale={:.4} q={:.2} pts={}", se * 100.0, r.scale, r.quality, r.points);
            }
        }
        println!("{label}: max err {emax:.2}px, scale err {:.2}%", smax * 100.0);
        (emax, smax)
    }

    #[test]
    fn track_loose_window_static_bg() {
        // the object moves right across a static, textured background; the window is 1.7× the object
        let path = |t: usize| Patch { cx: 300.0 + 5.0 * t as f32, cy: 270.0 + 1.0 * t as f32, s: 1.0, th: 0.0, r: 60.0 };
        let (e, s) = run_loose(60, 1.7, path, |_| (0.0, 0.0), "loose window");
        assert!(e < 2.5, "center error {e}");
        assert!(s < 0.04, "scale error {s}");
    }

    #[test]
    fn track_loose_window_walking_away() {
        // moves away from the camera: shrinks and drifts a little, the background stands still
        let path = |t: usize| {
            let tf = t as f32;
            Patch { cx: 480.0 + 1.5 * tf, cy: 300.0 - 0.8 * tf, s: 1.0 - 0.005 * tf, th: 0.0, r: 70.0 }
        };
        let (e, s) = run_loose(60, 1.6, path, |_| (0.0, 0.0), "moving away");
        assert!(e < 3.0, "center error {e}");
        assert!(s < 0.03, "scale error {s}");
    }

    #[test]
    fn track_loose_window_camera_follows() {
        // the camera pans along (the background slides left) while the object moves slowly in frame
        let path = |t: usize| Patch { cx: 420.0 + 1.5 * t as f32, cy: 270.0, s: 1.0, th: 0.0, r: 60.0 };
        let (e, s) = run_loose(60, 1.7, path, |t| (6.0 * t as f32, 0.5 * t as f32), "camera follows");
        assert!(e < 3.0, "center error {e}");
        assert!(s < 0.05, "scale error {s}");
    }

    #[test]
    fn track_moving_patch() {
        let (e, s, r, _) = run_patch(72, |_| None, "moving");
        assert!(e < 1.5, "center error {e}");
        assert!(s < 0.03, "scale error {s}");
        assert!(r < 0.03, "rotation error {r}");
    }

    /// A denser cloud: more points in the same window, and the moving, scaling, turning patch is still followed as well.
    #[test]
    fn track_dense_cloud() {
        let bg = Tex::new(1200, 800, 99, 400);
        let img = render(&Scene { bg: &bg, fg: None, pan: (0.0, 0.0), occ: None });
        let roi = Roi { cx: 0.4, cy: 0.5, rx: 0.15, ry: 0.2, shape: 2 };
        let count = |d: f32| {
            let mut tr = Tracker::new(WW, WH);
            tr.set_density(d);
            tr.load(&img, STRIDE, 1, SW, SH);
            tr.reset(roi).points
        };
        let (n1, n2, n4) = (count(1.0), count(2.0), count(4.0));
        println!("points: normal {n1}, dense {n2}, very dense {n4}");
        assert!(n2 as f32 > n1 as f32 * 1.5 && n4 as f32 > n2 as f32 * 1.3, "{n1} {n2} {n4}");
        for d in [2.0, 4.0] {
            let (e, s, r, _) = run_patch_dense(72, |_| None, "dense", d);
            assert!(e < 1.5 && s < 0.03 && r < 0.03, "density {d}: center {e} scale {s} rotation {r}");
        }
    }

    #[test]
    fn track_occlusion() {
        // static gray screen over ~40 % of the spot in frames 25..31
        let p = path(25);
        let rr = p.r * p.s;
        let occ = (p.cx - rr - 20.0, p.cy - rr - 20.0, p.cx - 0.15 * rr, p.cy + rr + 20.0);
        let (e, s, _, _) = run_patch(60, |t| (25..32).contains(&t).then_some(occ), "occluded");
        assert!(e < 2.0, "center error {e}");
        assert!(s < 0.04, "scale error {s}");
    }

    #[test]
    fn track_static_background() {
        let bg = Tex::new(1200, 800, 99, 400);
        let mut tr = Tracker::new(WW, WH);
        let img = render(&Scene { bg: &bg, fg: None, pan: (0.0, 0.0), occ: None });
        tr.load(&img, STRIDE, 1, SW, SH);
        let r0 = tr.reset(Roi { cx: 0.4, cy: 0.5, rx: 0.15, ry: 0.2, shape: 2 });
        assert!(r0.points >= 30, "{r0:?}");
        // the point cloud: as many as reported, inside (near) the window
        let mut pts = Vec::new();
        tr.points_norm(&mut pts);
        assert_eq!(pts.len(), 2 * r0.points as usize);
        assert!(pts.chunks(2).all(|p| (p[0] - 0.4).abs() <= 0.15 * 1.25 && (p[1] - 0.5).abs() <= 0.2 * 1.25), "{pts:?}");
        for _ in 0..20 {
            tr.load(&img, STRIDE, 1, SW, SH);
            let r = tr.step();
            assert!((r.dx * WW as f32).abs() < 0.1 && (r.dy * WH as f32).abs() < 0.1, "{r:?}");
            assert!((r.scale - 1.0).abs() < 0.005 && r.rotation.abs() < 0.005, "{r:?}");
            assert!(r.quality > 0.8, "{r:?}");
        }
    }

    #[test]
    fn track_global_pan() {
        let bg = Tex::new(1200, 800, 5, 400);
        let mut tr = Tracker::new(WW, WH);
        let mut emax = 0f32;
        for t in 0..40 {
            let pan = (5.0 * t as f32, -2.5 * t as f32 + 0.3 * (t as f32).powf(1.3));
            let img = render(&Scene { bg: &bg, fg: None, pan, occ: None });
            tr.load(&img, STRIDE, 1, SW, SH);
            let r = if t == 0 { tr.reset(Roi { cx: 0.5, cy: 0.5, rx: 0.2, ry: 0.25, shape: 1 }) } else { tr.step() };
            // the content moves −pan (source pixels), half at working resolution
            let e = (r.dx * WW as f32 + pan.0 * 0.5).hypot(r.dy * WH as f32 + pan.1 * 0.5);
            emax = emax.max(e);
            assert!(t == 0 || r.quality > 0.5, "t={t} {r:?}");
        }
        println!("panning: max err {emax:.3}px");
        assert!(emax < 1.0, "{emax}");
    }

    /// Timing: 150 points, panning, load and step separately (run with --release --nocapture).
    #[test]
    fn track_bench() {
        let bg = Tex::new(1200, 800, 11, 600);
        let mut tr = Tracker::new(WW, WH);
        let imgs: Vec<Vec<u8>> = (0..61).map(|t| render(&Scene { bg: &bg, fg: None, pan: (3.0 * t as f32, 1.0 * t as f32), occ: None })).collect();
        tr.load(&imgs[0], STRIDE, 1, SW, SH);
        let r0 = tr.reset(Roi { cx: 0.5, cy: 0.5, rx: 0.35, ry: 0.4, shape: 2 });
        let (mut tl, mut ts, mut pts) = (0f64, 0f64, 0u32);
        for img in &imgs[1..] {
            let t0 = Instant::now();
            tr.load(img, STRIDE, 1, SW, SH);
            let t1 = Instant::now();
            let r = tr.step();
            ts += t1.elapsed().as_secs_f64() * 1e3;
            tl += (t1 - t0).as_secs_f64() * 1e3;
            pts += r.points;
        }
        let n = (imgs.len() - 1) as f64;
        println!("bench: start points {}, mean inliers {:.0}, load {:.3} ms, step {:.3} ms per frame", r0.points, pts as f64 / n, tl / n, ts / n);
        assert!(r0.points >= 120, "{r0:?}");
    }

    #[test]
    fn track_flat_roi() {
        let flat = Tex::flat(100.0);
        let mut tr = Tracker::new(WW, WH);
        let img = render(&Scene { bg: &flat, fg: None, pan: (0.0, 0.0), occ: None });
        tr.load(&img, STRIDE, 1, SW, SH);
        let r = tr.reset(Roi { cx: 0.5, cy: 0.5, rx: 0.2, ry: 0.2, shape: 1 });
        assert_eq!(r.points, 0);
        assert!(r.quality < 0.2);
        for _ in 0..5 {
            tr.load(&img, STRIDE, 1, SW, SH);
            let r = tr.step();
            assert!(r.quality < 0.2 && r.dx == 0.0 && r.dy == 0.0 && r.scale == 1.0, "{r:?}");
        }
        // broken arguments: nothing happens, no panic
        tr.load(&img[..100], STRIDE, 1, SW, SH);
        tr.load(&img, 0, 0, 0, 0);
        let _ = Tracker::new(0, 0).step();
        let _ = Tracker::new(1, 1).reset(Roi { cx: f32::NAN, cy: 0.5, rx: f32::INFINITY, ry: 0.0, shape: 9 });
    }

    #[test]
    fn track_pixel_stride() {
        let bg = Tex::new(400, 300, 3, 50);
        let (w, h) = (300usize, 200usize);
        let mut a = vec![0u8; w * h];
        let mut b = vec![0u8; 2 * w * h];
        for y in 0..h {
            for x in 0..w {
                let v = bg.at(x as f32, y as f32) as u8;
                a[y * w + x] = v;
                b[y * 2 * w + 2 * x] = v;
                b[y * 2 * w + 2 * x + 1] = 255;
            }
        }
        let (mut t1, mut t2) = (Tracker::new(150, 100), Tracker::new(150, 100));
        t1.load(&a, w, 1, w, h);
        t2.load(&b, 2 * w, 2, w, h);
        assert_eq!(t1.frames[t1.cur][0].img, t2.frames[t2.cur][0].img);
    }
}
