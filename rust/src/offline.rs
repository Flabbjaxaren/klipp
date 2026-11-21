//! Rendered sound effects: noise reduction and speech enhance worked out ahead of playback, over a clip's whole range.
//!
//! Played live these effects only know the sound that has passed, so the noise reduction spends its first seconds
//! learning the noise and can only shut pauses. Rendered, they hear the whole range first (pass 1, [`Offline::learn`])
//! and then process it (pass 2, [`Offline::process`]):
//! - noise reduction: each band's noise level is the quiet end of what that band holds over the range; the sound is
//!   then cleaned frame by frame with a Wiener gain (decision-directed), so noise also goes down under speech
//! - speech enhance: the same cleaning (fixed, moderate), a voice EQ (low cut, less mud, presence, air), the level
//!   set from the loud parts of the speech (heard over the whole range), a compressor and a peak limiter
//!
//! Other effects in the rack before them run in the engine's DSP, in order. Output = input length, aligned (each
//! stage's latency is taken out). The file-level entry is [`render_raw`]: 16-bit PCM in, 16-bit WAV out.

use filmcraft_audio_dsp::AudioEffect;
use filmcraft_audio_dsp::biquad::{Biquad, Coeffs, FilterType};
use std::collections::VecDeque;
use std::f32::consts::PI;

/// Effects that are rendered (the rest of a rack plays live).
pub const RENDERED: [&str; 2] = ["denoise", "speech_enhance"];

/// Bumped when rendered files come out differently (Kotlin keys its files with it).
pub const VERSION: u32 = 1;

// ------------------------------------------------------------------ FFT

/// In-place radix-2 complex FFT of a fixed power-of-two size.
struct Fft {
    n: usize,
    cos: Vec<f32>,
    sin: Vec<f32>,
    rev: Vec<usize>,
}

impl Fft {
    fn new(n: usize) -> Self {
        let bits = n.trailing_zeros();
        Fft {
            n,
            cos: (0..n / 2).map(|k| (2.0 * std::f64::consts::PI * k as f64 / n as f64).cos() as f32).collect(),
            sin: (0..n / 2).map(|k| (2.0 * std::f64::consts::PI * k as f64 / n as f64).sin() as f32).collect(),
            rev: (0..n).map(|i| (i as u32).reverse_bits() as usize >> (32 - bits)).collect(),
        }
    }

    /// Unscaled; inverse = e^{+iωt}.
    fn run(&self, re: &mut [f32], im: &mut [f32], inverse: bool) {
        let n = self.n;
        for i in 0..n {
            let j = self.rev[i];
            if j > i {
                re.swap(i, j);
                im.swap(i, j);
            }
        }
        let sign = if inverse { 1.0 } else { -1.0 };
        let mut len = 2;
        while len <= n {
            let half = len / 2;
            let step = n / len;
            for start in (0..n).step_by(len) {
                for k in 0..half {
                    let (wr, wi) = (self.cos[k * step], sign * self.sin[k * step]);
                    let (a, b) = (start + k, start + k + half);
                    let tr = re[b] * wr - im[b] * wi;
                    let ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
            len <<= 1;
        }
    }
}

// ------------------------------------------------------------------ STFT

/// About 40 ms frames (2048 at 44.1/48 kHz).
fn fft_size(rate: u32) -> usize {
    ((rate as f32 * 0.04) as usize).next_power_of_two().clamp(256, 8192)
}

/// Two-channel STFT, √Hann windows, hop n/4. Every sample in gives one sample out, `n` samples later.
struct Stft {
    n: usize,
    hop: usize,
    win: Vec<f32>,
    fft: Fft,
    inb: [Vec<f32>; 2],
    fill: usize,
    ola: [Vec<f32>; 2],
    out: [VecDeque<f32>; 2],
    re: [Vec<f32>; 2],
    im: [Vec<f32>; 2],
}

impl Stft {
    fn new(rate: u32) -> Self {
        let n = fft_size(rate);
        let hop = n / 4;
        let z = || vec![0f32; n];
        Stft {
            n,
            hop,
            win: (0..n).map(|i| (0.5 - 0.5 * (2.0 * PI * i as f32 / n as f32).cos()).sqrt()).collect(),
            fft: Fft::new(n),
            inb: [z(), z()],
            fill: n - hop,
            ola: [z(), z()],
            // a hop of zeros ahead: output never waits for a frame (latency n − hop + hop = n)
            out: [VecDeque::from(vec![0f32; hop]), VecDeque::from(vec![0f32; hop])],
            re: [z(), z()],
            im: [z(), z()],
        }
    }

    fn bins(&self) -> usize {
        self.n / 2 + 1
    }

    /// Feeds both channels; `f` gets each frame's spectra (bins 0..=n/2). With `synth` the frames are put back together.
    fn feed(&mut self, x: [&[f32]; 2], synth: bool, f: &mut dyn FnMut(&mut [Vec<f32>; 2], &mut [Vec<f32>; 2])) {
        let len = x[0].len();
        let mut i = 0;
        while i < len {
            let take = (self.n - self.fill).min(len - i);
            for c in 0..2 {
                self.inb[c][self.fill..self.fill + take].copy_from_slice(&x[c][i..i + take]);
            }
            self.fill += take;
            i += take;
            if self.fill == self.n {
                self.frame(synth, f);
            }
        }
    }

    fn frame(&mut self, synth: bool, f: &mut dyn FnMut(&mut [Vec<f32>; 2], &mut [Vec<f32>; 2])) {
        let (n, hop) = (self.n, self.hop);
        for c in 0..2 {
            for j in 0..n {
                self.re[c][j] = self.inb[c][j] * self.win[j];
                self.im[c][j] = 0.0;
            }
            self.fft.run(&mut self.re[c], &mut self.im[c], false);
        }
        f(&mut self.re, &mut self.im);
        if synth {
            // Hann² over hop n/4 sums to 2; the inverse is unscaled
            let s = 0.5 / n as f32;
            for c in 0..2 {
                let (re, im) = (&mut self.re[c], &mut self.im[c]);
                for k in 1..n / 2 {
                    re[n - k] = re[k];
                    im[n - k] = -im[k];
                }
                im[0] = 0.0;
                im[n / 2] = 0.0;
                self.fft.run(re, im, true);
                for j in 0..n {
                    self.ola[c][j] += re[j] * self.win[j] * s;
                }
                self.out[c].extend(self.ola[c][..hop].iter().copied());
                self.ola[c].copy_within(hop.., 0);
                self.ola[c][n - hop..].fill(0.0);
            }
        }
        for c in 0..2 {
            self.inb[c].copy_within(hop.., 0);
        }
        self.fill = n - hop;
    }

    fn take(&mut self, y: [&mut [f32]; 2]) {
        for (c, ch) in y.into_iter().enumerate() {
            for v in ch.iter_mut() {
                *v = self.out[c].pop_front().unwrap_or(0.0);
            }
        }
    }
}

// ------------------------------------------------------------------ noise reduction

/// Histogram steps per dB and range (−200 … +80 dB) for the noise levels.
const H_STEP: f32 = 2.0;
const H_MIN: f32 = -200.0;
const H_LEN: usize = 560;
/// The noise level of a band: this quantile of its (lightly smoothed) power over the range…
const NOISE_Q: f32 = 0.15;
/// …raised to the noise's mean (the quiet end of a noise lies under its mean; measured on steady noise).
const NOISE_CAL: f32 = 2.0;

/// Spectral noise reduction with a noise profile learned from the whole range.
struct Denoise {
    stft: Stft,
    learner: Stft,
    hist: Vec<u32>,
    smooth: Vec<f32>,
    learned: usize,
    noise: Vec<f32>,
    ready: bool,
    floor: f32,
    beta: f32,
    g_prev: Vec<f32>,
    gam_prev: Vec<f32>,
    gains: Vec<f32>,
}

impl Denoise {
    /// `amount` 0…1: 0 = unchanged; 1 = noise down to −45 dB and subtracted twice over.
    fn new(rate: u32, amount: f32) -> Self {
        let stft = Stft::new(rate);
        let bins = stft.bins();
        let a = amount.clamp(0.0, 1.0);
        Denoise {
            learner: Stft::new(rate),
            stft,
            hist: vec![0; bins * H_LEN],
            smooth: vec![0.0; bins],
            learned: 0,
            noise: vec![0.0; bins],
            ready: false,
            floor: 10f32.powf(-45.0 * a / 20.0),
            beta: 1.0 + a,
            g_prev: vec![1.0; bins],
            gam_prev: vec![1.0; bins],
            gains: vec![1.0; bins],
        }
    }

    fn latency(&self) -> usize {
        self.stft.n
    }

    fn learn(&mut self, x: [&[f32]; 2]) {
        let bins = self.learner.bins();
        let (hist, smooth, learned) = (&mut self.hist, &mut self.smooth, &mut self.learned);
        self.learner.feed(x, false, &mut |re, im| {
            for k in 0..bins {
                let p = 0.5 * (re[0][k] * re[0][k] + im[0][k] * im[0][k] + re[1][k] * re[1][k] + im[1][k] * im[1][k]);
                smooth[k] = if *learned == 0 { p } else { 0.6 * smooth[k] + 0.4 * p };
                let db = 10.0 * (smooth[k] + 1e-30).log10();
                let i = (((db - H_MIN) * H_STEP) as isize).clamp(0, H_LEN as isize - 1) as usize;
                hist[k * H_LEN + i] += 1;
            }
            *learned += 1;
        });
    }

    fn finish_learning(&mut self) {
        if self.ready {
            return;
        }
        self.ready = true;
        if self.learned == 0 {
            return;
        }
        let want = ((self.learned as f32) * NOISE_Q).max(1.0) as u32;
        for k in 0..self.noise.len() {
            let h = &self.hist[k * H_LEN..(k + 1) * H_LEN];
            let mut acc = 0;
            let mut idx = 0;
            for (i, &c) in h.iter().enumerate() {
                acc += c;
                if acc >= want {
                    idx = i;
                    break;
                }
            }
            let db = H_MIN + (idx as f32 + 0.5) / H_STEP;
            self.noise[k] = 10f32.powf(db / 10.0) * NOISE_CAL;
        }
        self.hist = Vec::new();
    }

    fn process(&mut self, x: [&mut [f32]; 2]) {
        self.finish_learning();
        let bins = self.stft.bins();
        let passthrough = self.learned == 0 || self.floor >= 0.999;
        let (noise, beta, floor) = (&self.noise, self.beta, self.floor);
        let (g_prev, gam_prev, gains) = (&mut self.g_prev, &mut self.gam_prev, &mut self.gains);
        let [a, b] = x;
        self.stft.feed([&*a, &*b], true, &mut |re, im| {
            if passthrough {
                return;
            }
            for k in 0..bins {
                let p = 0.5 * (re[0][k] * re[0][k] + im[0][k] * im[0][k] + re[1][k] * re[1][k] + im[1][k] * im[1][k]);
                let nk = (beta * noise[k]).max(1e-30);
                let gam = p / nk;
                // decision-directed a-priori SNR (Ephraim–Malah): steady, little musical noise
                let xi = 0.98 * g_prev[k] * g_prev[k] * gam_prev[k] + 0.02 * (gam - 1.0).max(0.0);
                let g = xi / (1.0 + xi);
                g_prev[k] = g;
                gam_prev[k] = gam.min(1e6);
                gains[k] = g.max(floor);
            }
            for k in 0..bins {
                let l = gains[k.saturating_sub(1)];
                let r = gains[(k + 1).min(bins - 1)];
                let g = 0.25 * l + 0.5 * gains[k] + 0.25 * r;
                for c in 0..2 {
                    re[c][k] *= g;
                    im[c][k] *= g;
                }
            }
        });
        self.stft.take([a, b]);
    }
}

// ------------------------------------------------------------------ speech enhance

/// Speech enhance: moderate cleaning, voice EQ, level from the speech's loud parts, compressor, peak limiter.
struct Voice {
    dn: Denoise,
    coeffs: [Coeffs; 4],
    eq: [[Biquad; 4]; 2],
    mix: f32,
    /// 50 ms blocks' level (dB, mono) – histogram for the loud parts.
    lvl_hist: Vec<u32>,
    lvl_acc: f64,
    lvl_n: usize,
    lvl_block: usize,
    gain: f32,
    env: f32,
    att: f32,
    rel: f32,
    lim: f32,
    lim_rel: f32,
    dry: [VecDeque<f32>; 2],
}

/// The loud parts of the speech (95th percentile of 50 ms blocks) are set to this level (dBFS RMS)…
const VOICE_TARGET_DB: f32 = -15.0;
/// …within these bounds (dB).
const VOICE_GAIN_MIN: f32 = -6.0;
const VOICE_GAIN_MAX: f32 = 15.0;
const VOICE_COMP_THR: f32 = -21.0;
const VOICE_COMP_RATIO: f32 = 3.0;
const VOICE_CEILING: f32 = 0.89;

impl Voice {
    fn new(rate: u32, mix: f32, high: bool) -> Self {
        let sr = rate as f64;
        let (mud, presence) = if high { (350.0, 4000.0) } else { (250.0, 3000.0) };
        let dn = Denoise::new(rate, 0.5);
        let lat = dn.latency();
        let coef = |ms: f32| (-1.0 / (ms * 0.001 * rate as f32)).exp();
        Voice {
            dn,
            coeffs: [
                Coeffs::design(FilterType::HighPass, sr, 90.0, std::f64::consts::FRAC_1_SQRT_2, 0.0),
                Coeffs::design(FilterType::Peaking, sr, mud, 1.0, -4.0),
                Coeffs::design(FilterType::Peaking, sr, presence, 0.9, 5.0),
                Coeffs::design(FilterType::HighShelf, sr, 9000.0, std::f64::consts::FRAC_1_SQRT_2, 3.0),
            ],
            eq: [[Biquad::new(); 4]; 2],
            mix: mix.clamp(0.0, 1.0),
            lvl_hist: vec![0; 240],
            lvl_acc: 0.0,
            lvl_n: 0,
            lvl_block: (rate as usize / 20).max(1),
            gain: 1.0,
            env: 0.0,
            att: coef(5.0),
            rel: coef(150.0),
            lim: 1.0,
            lim_rel: coef(60.0),
            dry: [VecDeque::from(vec![0f32; lat]), VecDeque::from(vec![0f32; lat])],
        }
    }

    fn latency(&self) -> usize {
        self.dn.latency()
    }

    fn learn(&mut self, x: [&[f32]; 2]) {
        self.dn.learn(x);
        for i in 0..x[0].len() {
            let m = 0.5 * (x[0][i] + x[1][i]);
            self.lvl_acc += (m * m) as f64;
            self.lvl_n += 1;
            if self.lvl_n == self.lvl_block {
                let db = 10.0 * (self.lvl_acc / self.lvl_n as f64 + 1e-20).log10() as f32;
                // −120 … 0 dB in half dB
                let k = ((db + 120.0) * 2.0).clamp(0.0, 239.0) as usize;
                self.lvl_hist[k] += 1;
                self.lvl_acc = 0.0;
                self.lvl_n = 0;
            }
        }
    }

    fn finish_learning(&mut self) {
        let total: u32 = self.lvl_hist.iter().sum();
        if total == 0 {
            return;
        }
        let want = (total as f32 * 0.95).ceil() as u32;
        let mut acc = 0;
        for (k, &c) in self.lvl_hist.iter().enumerate() {
            acc += c;
            if acc >= want {
                let loud = k as f32 / 2.0 - 120.0;
                // the EQ lifts the voice about 1.5 dB
                let g = (VOICE_TARGET_DB - loud - 1.5).clamp(VOICE_GAIN_MIN, VOICE_GAIN_MAX);
                self.gain = 10f32.powf(g / 20.0);
                break;
            }
        }
        self.lvl_hist.clear();
        self.lvl_hist.shrink_to_fit();
    }

    fn process(&mut self, x: [&mut [f32]; 2]) {
        if !self.lvl_hist.is_empty() || self.lvl_n > 0 {
            self.finish_learning();
            self.lvl_n = 0;
        }
        let [a, b] = x;
        let n = a.len();
        // the dry sound, delayed as the cleaning delays the wet
        let mut dry = [vec![0f32; n], vec![0f32; n]];
        for (c, ch) in [&*a, &*b].into_iter().enumerate() {
            for i in 0..n {
                self.dry[c].push_back(ch[i]);
                dry[c][i] = self.dry[c].pop_front().unwrap_or(0.0);
            }
        }
        self.dn.process([&mut *a, &mut *b]);
        let ratio = 1.0 / VOICE_COMP_RATIO - 1.0;
        for i in 0..n {
            let mut w = [a[i] as f64, b[i] as f64];
            for (c, s) in w.iter_mut().enumerate() {
                for (f, co) in self.eq[c].iter_mut().zip(self.coeffs.iter()) {
                    *s = f.tick(co, *s);
                }
            }
            let (mut l, mut r) = (w[0] as f32 * self.gain, w[1] as f32 * self.gain);
            // compressor on the louder channel's power
            let p = (l * l).max(r * r);
            let k = if p > self.env { self.att } else { self.rel };
            self.env = k * self.env + (1.0 - k) * p;
            let lvl = 10.0 * (self.env + 1e-20).log10();
            if lvl > VOICE_COMP_THR {
                let g = 10f32.powf((lvl - VOICE_COMP_THR) * ratio / 20.0);
                l *= g;
                r *= g;
            }
            // peak limiter: down at once, back over 60 ms
            let pk = l.abs().max(r.abs());
            let target = if pk > VOICE_CEILING { VOICE_CEILING / pk } else { 1.0 };
            self.lim = if target < self.lim { target } else { self.lim_rel * self.lim + (1.0 - self.lim_rel) * target };
            l *= self.lim;
            r *= self.lim;
            a[i] = dry[0][i] + self.mix * (l - dry[0][i]);
            b[i] = dry[1][i] + self.mix * (r - dry[1][i]);
        }
    }
}

// ------------------------------------------------------------------ the chain

enum Stage {
    Engine(Box<dyn AudioEffect>),
    Denoise(Denoise),
    Voice(Voice),
}

impl Stage {
    fn latency(&self) -> usize {
        match self {
            Stage::Engine(d) => d.latency(),
            Stage::Denoise(d) => d.latency(),
            Stage::Voice(v) => v.latency(),
        }
    }
}

/// A rack prefix (the snapshot's `afx` form, in order) rendered over a range.
pub struct Offline {
    stages: Vec<Stage>,
    ch: usize,
    /// Output still to throw away (the stages' latency).
    drop: usize,
    planar: [Vec<f32>; 2],
    /// Frames put in (not counting the tail's zeros) and given out.
    frames_in: u64,
    frames_out: u64,
}

fn num(e: &serde_json::Value, k: &str, d: f64) -> f64 {
    e["p"][k].as_f64().unwrap_or(d)
}

impl Offline {
    pub fn new(chain: &serde_json::Value, rate: u32, channels: usize) -> Option<Self> {
        let mut stages = Vec::new();
        for e in chain.as_array()? {
            if e["on"].as_bool() == Some(false) {
                continue;
            }
            match e["id"].as_str()? {
                "denoise" => stages.push(Stage::Denoise(Denoise::new(rate, (num(e, "amount", 40.0) / 100.0) as f32))),
                "speech_enhance" => stages.push(Stage::Voice(Voice::new(rate, (num(e, "mix", 100.0) / 100.0) as f32, num(e, "tone", 0.0) > 0.5))),
                _ => {
                    let one = crate::audio::FxChain::from_json(&serde_json::Value::Array(vec![e.clone()]));
                    if let Some(d) = one.and_then(|c| c.first_dsp(rate)) {
                        stages.push(Stage::Engine(d));
                    }
                }
            }
        }
        if stages.is_empty() {
            return None;
        }
        let lat = stages.iter().map(Stage::latency).sum();
        Some(Offline { stages, ch: channels.clamp(1, 2), drop: lat, planar: [Vec::new(), Vec::new()], frames_in: 0, frames_out: 0 })
    }

    fn split(&mut self, x: &[f32]) -> usize {
        let n = x.len() / self.ch;
        for c in &mut self.planar {
            c.resize(n, 0.0);
        }
        for f in 0..n {
            let l = x[f * self.ch];
            self.planar[0][f] = l;
            self.planar[1][f] = if self.ch == 2 { x[f * 2 + 1] } else { l };
        }
        n
    }

    /// Pass 1: the whole range, interleaved.
    pub fn learn(&mut self, x: &[f32]) {
        self.split(x);
        let [a, b] = &self.planar;
        for s in &mut self.stages {
            match s {
                Stage::Denoise(d) => d.learn([a, b]),
                Stage::Voice(v) => v.learn([a, b]),
                Stage::Engine(_) => {}
            }
        }
    }

    /// Pass 2: interleaved in, interleaved out (the start held back until the stages' latency has passed).
    pub fn process(&mut self, x: &[f32]) -> Vec<f32> {
        self.frames_in += (x.len() / self.ch) as u64;
        self.run(x)
    }

    fn run(&mut self, x: &[f32]) -> Vec<f32> {
        let n = self.split(x);
        {
            let [a, b] = &mut self.planar;
            for s in &mut self.stages {
                match s {
                    Stage::Engine(d) => d.process(&mut [&mut a[..n], &mut b[..n]]),
                    Stage::Denoise(d) => d.process([&mut a[..n], &mut b[..n]]),
                    Stage::Voice(v) => v.process([&mut a[..n], &mut b[..n]]),
                }
            }
        }
        let skip = self.drop.min(n);
        self.drop -= skip;
        self.frames_out += (n - skip) as u64;
        let mut out = Vec::with_capacity((n - skip) * self.ch);
        for f in skip..n {
            if self.ch == 2 {
                out.push(self.planar[0][f]);
                out.push(self.planar[1][f]);
            } else {
                out.push(0.5 * (self.planar[0][f] + self.planar[1][f]));
            }
        }
        out
    }

    /// The tail: what the latency still holds (so out has as many samples as in).
    pub fn finish(&mut self) -> Vec<f32> {
        let mut out = Vec::new();
        let zeros = vec![0f32; 4096 * self.ch];
        while self.frames_out < self.frames_in {
            out.extend(self.run(&zeros));
        }
        let extra = (self.frames_out - self.frames_in) as usize * self.ch;
        out.truncate(out.len() - extra.min(out.len()));
        self.frames_out = self.frames_in;
        out
    }
}

// ------------------------------------------------------------------ files

/// Progress of running renders (0…1000), by the caller's job id.
fn progress() -> &'static std::sync::Mutex<std::collections::HashMap<i64, u32>> {
    static P: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<i64, u32>>> = std::sync::OnceLock::new();
    P.get_or_init(Default::default)
}

pub fn progress_of(job: i64) -> f32 {
    progress().lock().map(|m| m.get(&job).copied().unwrap_or(0)).unwrap_or(0) as f32 / 1000.0
}

fn set_progress(job: i64, v: f32) {
    if let Ok(mut m) = progress().lock() {
        m.insert(job, (v.clamp(0.0, 1.0) * 1000.0) as u32);
    }
}

/// Renders `raw` (16-bit little-endian PCM, interleaved) through `chain` into the WAV file `out` (16 bits).
/// Progress under `job` ([`progress_of`]): learning to 0.3, processing to 1.
pub fn render_raw(chain: &serde_json::Value, raw: &str, rate: u32, channels: usize, out: &str, job: i64) -> Result<(), String> {
    use std::io::{Read, Write};
    let ch = channels.clamp(1, 2);
    if channels != ch {
        return Err(format!("{channels} channels"));
    }
    let mut fx = Offline::new(chain, rate, ch).ok_or("no rendered effects")?;
    let total = std::fs::metadata(raw).map_err(|e| e.to_string())?.len().max(1);
    let block = 8192 * 2 * ch;
    let read_pass = |f: &mut dyn FnMut(&[f32], u64)| -> Result<(), String> {
        let mut r = std::io::BufReader::new(std::fs::File::open(raw).map_err(|e| e.to_string())?);
        let mut buf = vec![0u8; block];
        let mut done = 0u64;
        loop {
            let mut got = 0;
            while got < block {
                let k = r.read(&mut buf[got..]).map_err(|e| e.to_string())?;
                if k == 0 {
                    break;
                }
                got += k;
            }
            let got = got / (2 * ch) * (2 * ch);
            if got == 0 {
                break;
            }
            let x: Vec<f32> = buf[..got].chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0).collect();
            done += got as u64;
            f(&x, done);
        }
        Ok(())
    };
    set_progress(job, 0.0);
    read_pass(&mut |x, done| {
        fx.learn(x);
        set_progress(job, 0.3 * done as f32 / total as f32);
    })?;
    let tmp = format!("{out}.part");
    let mut w = std::io::BufWriter::new(std::fs::File::create(&tmp).map_err(|e| e.to_string())?);
    let data = (total / (2 * ch as u64)) * 2 * ch as u64;
    let fb = 2 * ch as u32;
    let mut h = Vec::with_capacity(44);
    h.extend_from_slice(b"RIFF");
    h.extend_from_slice(&((36 + data) as u32).to_le_bytes());
    h.extend_from_slice(b"WAVEfmt ");
    h.extend_from_slice(&16u32.to_le_bytes());
    h.extend_from_slice(&1u16.to_le_bytes());
    h.extend_from_slice(&(ch as u16).to_le_bytes());
    h.extend_from_slice(&rate.to_le_bytes());
    h.extend_from_slice(&(rate * fb).to_le_bytes());
    h.extend_from_slice(&(fb as u16).to_le_bytes());
    h.extend_from_slice(&16u16.to_le_bytes());
    h.extend_from_slice(b"data");
    h.extend_from_slice(&(data as u32).to_le_bytes());
    w.write_all(&h).map_err(|e| e.to_string())?;
    let mut err: Option<String> = None;
    let mut put = |y: &[f32], w: &mut std::io::BufWriter<std::fs::File>| {
        let bytes: Vec<u8> = y.iter().flat_map(|v| ((v.clamp(-1.0, 1.0) * 32767.0).round() as i16).to_le_bytes()).collect();
        if let Err(e) = w.write_all(&bytes) {
            err.get_or_insert(e.to_string());
        }
    };
    read_pass(&mut |x, done| {
        let y = fx.process(x);
        put(&y, &mut w);
        set_progress(job, 0.3 + 0.7 * done as f32 / total as f32);
    })?;
    let y = fx.finish();
    put(&y, &mut w);
    if let Some(e) = err {
        return Err(e);
    }
    w.flush().map_err(|e| e.to_string())?;
    drop(w);
    std::fs::rename(&tmp, out).map_err(|e| e.to_string())?;
    set_progress(job, 1.0);
    Ok(())
}

pub fn forget(job: i64) {
    if let Ok(mut m) = progress().lock() {
        m.remove(&job);
    }
}
