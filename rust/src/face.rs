//! Face following for windows and pinned titles: the face is found anew in every frame (YuNet, MIT, a 230 kB CNN
//! from the OpenCV model zoo, run with tract) instead of following points on it – so a head that turns, nods or
//! looks away is still found, where points slide off the face. The window follows the face's centre, its size and
//! the line through the eyes (roll).
//!
//! Frames come in buffer orientation (phone videos are often stored sideways); the detector needs upright faces, so
//! the work image is turned to display orientation first, and every result is in display orientation.

use tract_onnx::prelude::*;

/// YuNet 2023mar (OpenCV zoo, opencv/face_detection_yunet on Hugging Face, MIT).
static MODEL: &[u8] = include_bytes!("../models/face_detection_yunet_2023mar.onnx");

const STRIDES: [usize; 3] = [8, 16, 32];
const MIN_SCORE: f32 = 0.5;

/// One face in display orientation, normalized to the frame (0..1): centre, size (the larger side of the box), the
/// eye line's angle (radians, clockwise, y down) and the five landmarks (eyes, nose, mouth corners).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Face {
    pub score: f32,
    pub cx: f32,
    pub cy: f32,
    pub w: f32,
    pub h: f32,
    pub angle: f32,
    pub kps: [f32; 10],
}

impl Face {
    fn size(&self) -> f32 {
        self.w.max(self.h)
    }
}

type Plan = SimplePlan<TypedFact, Box<dyn TypedOp>, Graph<TypedFact, Box<dyn TypedOp>>>;

pub struct FaceDetector {
    /// Upright work image (display orientation) and its padded size (multiples of 32).
    w: usize,
    h: usize,
    pw: usize,
    ph: usize,
    plan: Plan,
    input: Vec<f32>,
}

impl FaceDetector {
    /// A detector for upright images of `w`×`h` (display orientation).
    pub fn new(w: usize, h: usize) -> TractResult<Self> {
        let pw = w.div_ceil(32) * 32;
        let ph = h.div_ceil(32) * 32;
        let plan = tract_onnx::onnx()
            .model_for_read(&mut std::io::Cursor::new(MODEL))?
            .with_input_fact(0, f32::fact([1, 3, ph, pw]).into())?
            .into_optimized()?
            .into_runnable()?;
        Ok(FaceDetector { w, h, pw, ph, plan, input: vec![0.0; 3 * pw * ph] })
    }

    /// Faces in the upright gray image `img` (`w`×`h`, 0..255), best first.
    pub fn detect(&mut self, img: &[f32]) -> Vec<Face> {
        let (w, h, pw, ph) = (self.w, self.h, self.pw, self.ph);
        if img.len() < w * h {
            return Vec::new();
        }
        // gray in all three channels (the network wants BGR 0..255), zero padding right and below
        let plane = pw * ph;
        for y in 0..h {
            let row = &img[y * w..(y + 1) * w];
            for c in 0..3 {
                self.input[c * plane + y * pw..c * plane + y * pw + w].copy_from_slice(row);
            }
        }
        let t = match tract_ndarray::Array4::from_shape_vec((1, 3, ph, pw), self.input.clone()) {
            Ok(a) => Tensor::from(a),
            Err(_) => return Vec::new(),
        };
        let Ok(out) = self.plan.run(tvec!(t.into())) else { return Vec::new() };
        // outputs: cls_8/16/32, obj_8/16/32, bbox_8/16/32, kps_8/16/32
        let mut faces = Vec::new();
        for (k, &s) in STRIDES.iter().enumerate() {
            let (Ok(cls), Ok(obj), Ok(bbox), Ok(kps)) =
                (out[k].as_slice::<f32>(), out[3 + k].as_slice::<f32>(), out[6 + k].as_slice::<f32>(), out[9 + k].as_slice::<f32>())
            else {
                continue;
            };
            let cols = pw / s;
            let rows = ph / s;
            for r in 0..rows {
                for c in 0..cols {
                    let i = r * cols + c;
                    if i >= cls.len() {
                        break;
                    }
                    let score = (cls[i].clamp(0.0, 1.0) * obj[i].clamp(0.0, 1.0)).sqrt();
                    if score < MIN_SCORE {
                        continue;
                    }
                    let sf = s as f32;
                    let b = &bbox[i * 4..i * 4 + 4];
                    let cx = (c as f32 + b[0]) * sf;
                    let cy = (r as f32 + b[1]) * sf;
                    let bw = b[2].exp() * sf;
                    let bh = b[3].exp() * sf;
                    let mut kp = [0f32; 10];
                    for n in 0..5 {
                        kp[2 * n] = ((kps[i * 10 + 2 * n] + c as f32) * sf) / w as f32;
                        kp[2 * n + 1] = ((kps[i * 10 + 2 * n + 1] + r as f32) * sf) / h as f32;
                    }
                    // eye line: from the right eye (image left) to the left eye – in pixels, so the aspect is right
                    let angle = ((kp[3] - kp[1]) * h as f32).atan2((kp[2] - kp[0]) * w as f32);
                    faces.push(Face { score, cx: cx / w as f32, cy: cy / h as f32, w: bw / w as f32, h: bh / h as f32, angle, kps: kp });
                }
            }
        }
        nms(faces, w as f32 / h as f32)
    }
}

/// Non-maximum suppression (overlap 0.3), best first.
fn nms(mut f: Vec<Face>, aspect: f32) -> Vec<Face> {
    f.sort_by(|a, b| b.score.total_cmp(&a.score));
    let mut out: Vec<Face> = Vec::new();
    for x in f {
        if out.iter().all(|o| iou(o, &x, aspect) < 0.3) {
            out.push(x);
        }
    }
    out
}

fn iou(a: &Face, b: &Face, aspect: f32) -> f32 {
    let (ax0, ax1, ay0, ay1) = (a.cx - a.w / 2.0, a.cx + a.w / 2.0, a.cy - a.h / 2.0, a.cy + a.h / 2.0);
    let (bx0, bx1, by0, by1) = (b.cx - b.w / 2.0, b.cx + b.w / 2.0, b.cy - b.h / 2.0, b.cy + b.h / 2.0);
    let iw = (ax1.min(bx1) - ax0.max(bx0)).max(0.0) * aspect;
    let ih = (ay1.min(by1) - ay0.max(by0)).max(0.0);
    let inter = iw * ih;
    let u = a.w * a.h * aspect + b.w * b.h * aspect - inter;
    if u > 0.0 { inter / u } else { 0.0 }
}

/// The face tracker: a frame in buffer orientation in, the chosen face's motion since the reference frame out.
pub struct FaceTracker {
    /// Buffer work size and the turn to display orientation (degrees clockwise: 0, 90, 180, 270).
    bw: usize,
    bh: usize,
    rot: u32,
    gray: Vec<f32>,
    up: Vec<f32>,
    det: Option<FaceDetector>,
    reference: Option<Face>,
    last: Option<Face>,
    /// The window's centre in display orientation (where to look before a face is found).
    want: (f32, f32),
    want_r: f32,
    pub last_faces: usize,
}

/// Result as the point tracker's: `[dx, dy, scale, rotation, quality, n, x0, y0, …]` in display orientation (the
/// landmarks as points).
pub type FaceResult = Vec<f32>;

impl FaceTracker {
    pub fn new(work_w: usize, work_h: usize, rotation: i32) -> Self {
        let rot = (((rotation % 360) + 360) % 360) as u32;
        let (dw, dh) = if rot == 90 || rot == 270 { (work_h, work_w) } else { (work_w, work_h) };
        FaceTracker {
            bw: work_w,
            bh: work_h,
            rot,
            gray: vec![0.0; work_w * work_h],
            up: vec![0.0; work_w * work_h],
            det: FaceDetector::new(dw, dh).ok(),
            reference: None,
            last: None,
            want: (0.5, 0.5),
            want_r: 0.2,
            last_faces: 0,
        }
    }

    /// Downscales the luma plane (box average) into the buffer work image, then turns it upright.
    pub fn load(&mut self, src: &[u8], row_stride: usize, pixel_stride: usize, src_w: usize, src_h: usize) {
        let (w, h) = (self.bw, self.bh);
        if src_w == 0 || src_h == 0 || w == 0 || h == 0 {
            return;
        }
        for oy in 0..h {
            let y0 = oy * src_h / h;
            let y1 = ((oy + 1) * src_h / h).clamp(y0 + 1, src_h);
            let ys = ((y1 - y0) / 3).max(1);
            for ox in 0..w {
                let x0 = ox * src_w / w;
                let x1 = ((ox + 1) * src_w / w).clamp(x0 + 1, src_w);
                let xs = ((x1 - x0) / 3).max(1);
                let (mut s, mut n) = (0u32, 0u32);
                let mut y = y0;
                while y < y1 {
                    let mut x = x0;
                    while x < x1 {
                        if let Some(v) = src.get(y * row_stride + x * pixel_stride) {
                            s += *v as u32;
                            n += 1;
                        }
                        x += xs;
                    }
                    y += ys;
                }
                self.gray[oy * w + ox] = if n > 0 { s as f32 / n as f32 } else { 0.0 };
            }
        }
        // upright: display pixel (X, Y) ← buffer pixel
        let (dw, dh) = if self.rot == 90 || self.rot == 270 { (h, w) } else { (w, h) };
        for y in 0..dh {
            for x in 0..dw {
                let (u, v) = match self.rot {
                    90 => (y, h - 1 - x),
                    180 => (w - 1 - x, h - 1 - y),
                    270 => (w - 1 - y, x),
                    _ => (x, y),
                };
                self.up[y * dw + x] = self.gray[v * w + u];
            }
        }
    }

    fn faces(&mut self) -> Vec<Face> {
        let up = std::mem::take(&mut self.up);
        let f = self.det.as_mut().map(|d| d.detect(&up)).unwrap_or_default();
        self.up = up;
        self.last_faces = f.len();
        f
    }

    /// Picks the face the window is on (`cx, cy, rx, ry` normalized, buffer orientation) in the loaded frame.
    pub fn reset(&mut self, cx: f32, cy: f32, rx: f32, ry: f32) -> FaceResult {
        let (dx, dy) = to_display(cx, cy, self.rot);
        self.want = (dx, dy);
        self.want_r = rx.max(ry).clamp(0.02, 1.0);
        self.reference = None;
        self.last = None;
        let faces = self.faces();
        // the face whose centre is nearest the window's, within about the window's size
        let pick = faces
            .iter()
            .filter(|f| dist(f.cx, f.cy, dx, dy) < (self.want_r + f.size()).max(0.08))
            .min_by(|a, b| dist(a.cx, a.cy, dx, dy).total_cmp(&dist(b.cx, b.cy, dx, dy)))
            .copied();
        self.reference = pick;
        self.last = pick;
        result(pick.as_ref(), pick.as_ref(), pick.is_some())
    }

    /// The same face in the loaded frame: nearest the previous one and of a similar size. Not found: quality 0.
    pub fn step(&mut self) -> FaceResult {
        let faces = self.faces();
        let prev = self.last;
        let pick = match prev {
            Some(p) => faces
                .iter()
                .filter(|f| dist(f.cx, f.cy, p.cx, p.cy) < p.size() * 1.2 && (f.size() / p.size()).clamp(0.0, 10.0) > 0.6 && f.size() / p.size() < 1.6)
                .min_by(|a, b| dist(a.cx, a.cy, p.cx, p.cy).total_cmp(&dist(b.cx, b.cy, p.cx, p.cy)))
                .copied(),
            None => faces
                .iter()
                .filter(|f| dist(f.cx, f.cy, self.want.0, self.want.1) < (self.want_r + f.size()).max(0.08))
                .min_by(|a, b| dist(a.cx, a.cy, self.want.0, self.want.1).total_cmp(&dist(b.cx, b.cy, self.want.0, self.want.1)))
                .copied(),
        };
        if self.reference.is_none() {
            self.reference = pick;
        }
        if pick.is_some() {
            self.last = pick;
        }
        result(self.reference.as_ref(), if pick.is_some() { pick.as_ref() } else { self.last.as_ref() }, pick.is_some())
    }
}

fn dist(ax: f32, ay: f32, bx: f32, by: f32) -> f32 {
    ((ax - bx).powi(2) + (ay - by).powi(2)).sqrt()
}

/// A point normalized in buffer orientation → display orientation (the frame turned `rot` degrees clockwise).
fn to_display(u: f32, v: f32, rot: u32) -> (f32, f32) {
    match rot {
        90 => (1.0 - v, u),
        180 => (1.0 - u, 1.0 - v),
        270 => (v, 1.0 - u),
        _ => (u, v),
    }
}

fn result(reference: Option<&Face>, cur: Option<&Face>, found: bool) -> FaceResult {
    let (Some(r), Some(c)) = (reference, cur) else { return vec![0.0, 0.0, 1.0, 0.0, 0.0, 0.0] };
    let mut v = vec![c.cx - r.cx, c.cy - r.cy, c.size() / r.size().max(1e-4), wrap(c.angle - r.angle), if found { c.score } else { 0.0 }, 5.0];
    v.extend_from_slice(&c.kps);
    v
}

fn wrap(a: f32) -> f32 {
    let p = std::f32::consts::PI;
    (a + p).rem_euclid(2.0 * p) - p
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The model loads and finds no face in flat gray.
    #[test]
    fn no_face_in_gray() {
        let mut d = FaceDetector::new(320, 180).map_err(|e| format!("{e:?}")).unwrap();
        assert!(d.detect(&vec![128.0; 320 * 180]).is_empty());
    }

    /// A real clip (YUV4MPEG2, luma): KLIPP_TRACK_Y4M=path, KLIPP_TRACK_ROI="cx,cy,rx,ry", optional KLIPP_FACE_ROT
    /// (the clip is turned that many degrees counterclockwise first, as a sideways phone file). Prints the face's track.
    #[test]
    #[ignore]
    fn face_y4m_clip() {
        let Ok(path) = std::env::var("KLIPP_TRACK_Y4M") else { return };
        let roi: Vec<f32> = std::env::var("KLIPP_TRACK_ROI").unwrap().split(',').map(|x| x.trim().parse().unwrap()).collect();
        let rot: i32 = std::env::var("KLIPP_FACE_ROT").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
        let data = std::fs::read(&path).unwrap();
        let head_end = data.iter().position(|&b| b == b'\n').unwrap();
        let head = std::str::from_utf8(&data[..head_end]).unwrap();
        let num = |tag: char| head.split(' ').find(|t| t.starts_with(tag)).unwrap()[1..].parse::<usize>().unwrap();
        let (w, h) = (num('W'), num('H'));
        let frame = w * h * 3 / 2;
        // stored sideways: the buffer is the picture turned counterclockwise by `rot`, so display = buffer turned clockwise
        let (bw, bh) = if rot == 90 || rot == 270 { (h, w) } else { (w, h) };
        let ww = 480usize.min(bw.max(bh));
        let (tw, th) = if bw >= bh { (ww, (ww as f32 * bh as f32 / bw as f32).round() as usize) } else { ((ww as f32 * bw as f32 / bh as f32).round() as usize, ww) };
        let mut tr = FaceTracker::new(tw, th, rot);
        let (mut pos, mut t, mut lost) = (head_end + 1, 0, 0);
        let t0 = std::time::Instant::now();
        let mut buf = vec![0u8; w * h];
        while pos < data.len() {
            let fe = pos + data[pos..].iter().position(|&b| b == b'\n').unwrap() + 1;
            if fe + frame > data.len() {
                break;
            }
            let y = &data[fe..fe + w * h];
            // buffer pixel (u, v) of the sideways file
            for v in 0..bh {
                for u in 0..bw {
                    let (x, yy) = match rot { 90 => (bh - 1 - v, u), 180 => (w - 1 - u, h - 1 - v), 270 => (v, bw - 1 - u), _ => (u, v) };
                    buf[v * bw + u] = y[yy * w + x];
                }
            }
            tr.load(&buf, bw, 1, bw, bh);
            // the ROI is given in display orientation; the reset takes buffer orientation
            let (bu, bv) = match rot { 90 => (roi[1], 1.0 - roi[0]), 180 => (1.0 - roi[0], 1.0 - roi[1]), 270 => (1.0 - roi[1], roi[0]), _ => (roi[0], roi[1]) };
            let r = if t == 0 { tr.reset(bu, bv, roi[2], roi[3]) } else { tr.step() };
            if r[4] <= 0.0 {
                lost += 1;
            }
            if t % 10 == 0 || std::env::var("KLIPP_TRACK_ALL").is_ok() {
                println!("t={t:3} cx={:.3} cy={:.3} scale={:.3} rot={:+.3} q={:.2} faces={}", roi[0] + r[0], roi[1] + r[1], r[2], r[3], r[4], tr.last_faces);
            }
            pos = fe + frame;
            t += 1;
        }
        println!("{t} frames, {lost} without the face, {:.1} ms per frame", t0.elapsed().as_secs_f64() * 1000.0 / t as f64);
    }
}
