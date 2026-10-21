//! Audio for Klipp's own playback: the clips' audio effects (the engine's effect instances → filmcraft-audio-dsp)
//! as a chain that Media3's audio processor runs, and loudness measurement (BS.1770) of a mixed WAV file.

use filmcraft_audio_dsp::AudioEffect;
use filmcraft_engine::project::{EffectInstance, ParamValue};
use filmcraft_engine::render::audio_fx;
use filmcraft_engine::time::Tick;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

/// The effects on an audio clip, in rack order. Created on the UI thread (from the session), run on the audio thread.
pub struct FxChain {
    /// (the effect's index in the clip's rack, copy of the instance)
    insts: Vec<(usize, EffectInstance)>,
    /// One slot per instance (None = the effect couldn't be built and is skipped), so the indices line up.
    dsp: Vec<Option<Box<dyn AudioEffect>>>,
    rate: u32,
    planar: [Vec<f32>; 2],
}

/// The clip's active audio effects that have a DSP (not the built-in ones: volume, channel volume, panning).
pub fn active_effects(effects: &[EffectInstance]) -> Vec<(usize, EffectInstance)> {
    effects
        .iter()
        .enumerate()
        .filter(|(_, e)| e.enabled && !e.def().is_some_and(|d| d.intrinsic) && audio_fx::supported(&e.effect))
        .map(|(i, e)| (i, e.clone()))
        .collect()
}

/// Sets a parameter (without keyframes) in an effect instance. false = the parameter doesn't exist.
fn set_value(inst: &mut EffectInstance, param: &str, value: f64) -> bool {
    let Some(p) = inst.params.get_mut(param) else { return false };
    p.keyframes.clear();
    p.value = match p.value {
        ParamValue::Bool(_) => ParamValue::Bool(value > 0.5),
        ParamValue::Choice(_) => ParamValue::Choice(value.max(0.0).round() as u32),
        _ => ParamValue::Float(value),
    };
    true
}

impl FxChain {
    /// From the snapshot's description: `[{"i": rack index, "id": "denoise", "on": true, "p": {param: value}}]`.
    /// Needs no session (can be created on any thread).
    pub fn from_json(v: &serde_json::Value) -> Option<Self> {
        let mut insts = Vec::new();
        for e in v.as_array()? {
            if e["on"].as_bool() == Some(false) {
                continue;
            }
            let id = e["id"].as_str()?;
            if !audio_fx::supported(id) {
                continue;
            }
            let Some(def) = filmcraft_engine::project::find_effect(id) else { continue };
            let mut inst = def.instance();
            if let Some(p) = e["p"].as_object() {
                for (k, x) in p {
                    if let (Some(prm), Some(f)) = (inst.params.get_mut(k), x.as_f64()) {
                        prm.value = match prm.value {
                            ParamValue::Bool(_) => ParamValue::Bool(f > 0.5),
                            ParamValue::Choice(_) => ParamValue::Choice(f.max(0.0).round() as u32),
                            ParamValue::Float(_) => ParamValue::Float(f),
                            ref other => other.clone(),
                        };
                    }
                }
            }
            insts.push((e["i"].as_u64().unwrap_or(0) as usize, inst));
        }
        Self::new(insts)
    }

    pub fn new(insts: Vec<(usize, EffectInstance)>) -> Option<Self> {
        if insts.is_empty() {
            return None;
        }
        Some(FxChain { insts, dsp: Vec::new(), rate: 0, planar: [Vec::new(), Vec::new()] })
    }

    fn configure(&mut self, rate: u32) {
        if rate == self.rate && self.dsp.len() == self.insts.len() {
            return;
        }
        self.rate = rate;
        self.dsp = self.insts.iter().map(|(_, e)| audio_fx::configured(e, Tick::ZERO, rate)).collect();
    }

    /// Changes a parameter while dragging (the effect with rack index `index`). The state (filters, noise profile)
    /// is kept: a freshly configured copy provides the DSP values, which are transferred to the live effect.
    ///
    /// From JNI the same thing is done in three steps so the copy is built without the chain's lock (otherwise the audio thread waits):
    /// [`prepare_param`](Self::prepare_param) (under the lock), [`configure_inst`](Self::configure_inst) (without),
    /// [`apply_param`](Self::apply_param) (under the lock).
    pub fn set_param(&mut self, index: usize, param: &str, value: f64) -> bool {
        let Some((pos, inst, rate)) = self.prepare_param(index, param, value) else { return false };
        let fresh = Self::configure_inst(&inst, rate);
        self.apply_param(pos, index, param, value, fresh, rate)
    }

    /// Step 1 (cheap, under the lock): the slot in the chain, a copy of the instance with the new value and
    /// the sample rate the DSP is configured for (0 = not yet). None = unknown effect/parameter.
    pub fn prepare_param(&self, index: usize, param: &str, value: f64) -> Option<(usize, EffectInstance, u32)> {
        let pos = self.insts.iter().position(|(i, _)| *i == index)?;
        let mut inst = self.insts[pos].1.clone();
        if !set_value(&mut inst, param, value) {
            return None;
        }
        Some((pos, inst, self.rate))
    }

    /// Step 2 (expensive, without the lock): a freshly configured DSP with the new values.
    pub fn configure_inst(inst: &EffectInstance, rate: u32) -> Option<Box<dyn AudioEffect>> {
        if rate == 0 {
            return None;
        }
        audio_fx::configured(inst, Tick::ZERO, rate)
    }

    /// Step 3 (under the lock): writes the value into the instance and the DSP values from `fresh` into the live effect.
    pub fn apply_param(&mut self, pos: usize, index: usize, param: &str, value: f64, fresh: Option<Box<dyn AudioEffect>>, rate: u32) -> bool {
        if self.insts.get(pos).map(|(i, _)| *i) != Some(index) {
            return false;
        }
        if !set_value(&mut self.insts[pos].1, param, value) {
            return false;
        }
        if self.rate == 0 || pos >= self.dsp.len() {
            return true;
        }
        if rate != self.rate {
            // the rate changed while the copy was being built: reconfigure on the next processing call
            self.rate = 0;
            return true;
        }
        if let (Some(fresh), Some(live)) = (fresh, self.dsp[pos].as_mut()) {
            for spec in fresh.params() {
                if let Some(v) = fresh.param(spec.id) {
                    if live.param(spec.id) != Some(v) {
                        live.set_param(spec.id, v);
                    }
                }
            }
        }
        true
    }

    pub fn reset(&mut self) {
        for d in self.dsp.iter_mut().flatten() {
            d.reset();
        }
    }

    pub fn latency(&self) -> usize {
        self.dsp.iter().flatten().map(|d| d.latency()).sum()
    }

    /// The latency (samples) at a given sample rate – playback compensates for it.
    pub fn latency_at(&mut self, rate: u32) -> usize {
        self.configure(rate);
        self.latency()
    }

    /// Processes interleaved audio (1 or 2 channels) in place. With more channels only the first two
    /// are processed; the rest pass through unchanged.
    pub fn process(&mut self, data: &mut [f32], channels: usize, rate: u32) {
        if channels == 0 || data.is_empty() {
            return;
        }
        self.configure(rate);
        let n = data.len() / channels;
        for c in &mut self.planar {
            c.resize(n, 0.0);
        }
        for f in 0..n {
            let l = data[f * channels];
            let r = if channels >= 2 { data[f * channels + 1] } else { l };
            self.planar[0][f] = l;
            self.planar[1][f] = r;
        }
        {
            let [a, b] = &mut self.planar;
            let mut chans: [&mut [f32]; 2] = [&mut a[..n], &mut b[..n]];
            for d in self.dsp.iter_mut().flatten() {
                d.process(&mut chans);
            }
        }
        for f in 0..n {
            if channels >= 2 {
                data[f * channels] = self.planar[0][f];
                data[f * channels + 1] = self.planar[1][f];
            } else {
                data[f] = (self.planar[0][f] + self.planar[1][f]) * 0.5;
            }
        }
    }
}

/// Curves for the graphical effect editors, from the engine's own DSP (the same one that is heard): `"response"` =
/// frequency response in dB at the frequencies `xs` (EQ), `"transfer"` = output level in dB for the input levels `xs` (compressor).
/// `effect` = an effect in the snapshot's form (`{"id", "p"}`).
pub fn curve(effect: &serde_json::Value, kind: &str, xs: &[f64]) -> Option<Vec<f64>> {
    let mut e = effect.clone();
    e["on"] = serde_json::Value::Bool(true);
    let chain = FxChain::from_json(&serde_json::Value::Array(vec![e]))?;
    let (_, inst) = chain.insts.first()?;
    let d = audio_fx::configured(inst, Tick::ZERO, 48_000)?;
    Some(match kind {
        "response" => xs.iter().map(|&f| d.response_db(f).unwrap_or(0.0)).collect(),
        "transfer" => xs.iter().map(|&x| d.transfer_db(0, x as f32).map(|v| v as f64).unwrap_or(x)).collect(),
        _ => return None,
    })
}

/// The chains Kotlin has open, by id. An unknown (already freed) id does nothing – so an
/// audio processor that happens to run after the chain was released can never reach freed memory.
fn chains() -> &'static Mutex<HashMap<u64, Arc<Mutex<FxChain>>>> {
    static C: OnceLock<Mutex<HashMap<u64, Arc<Mutex<FxChain>>>>> = OnceLock::new();
    C.get_or_init(Default::default)
}

pub fn chain_new(c: FxChain) -> u64 {
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
    let id = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    chains().lock().unwrap_or_else(|e| e.into_inner()).insert(id, Arc::new(Mutex::new(c)));
    id
}

pub fn chain_get(id: u64) -> Option<Arc<Mutex<FxChain>>> {
    chains().lock().unwrap_or_else(|e| e.into_inner()).get(&id).cloned()
}

pub fn chain_free(id: u64) {
    // the chain (e.g. the noise reduction's buffers) is freed after the lock is released – the audio thread must never wait for
    // a deallocation while it fetches another chain
    let removed = chains().lock().unwrap_or_else(|e| e.into_inner()).remove(&id);
    drop(removed);
}

pub fn chain_count() -> usize {
    chains().lock().unwrap_or_else(|e| e.into_inner()).len()
}

/// Loudness (BS.1770 / EBU R128) of a WAV file: integrated LUFS, true peak (dBTP), LRA.
pub fn measure_wav(path: &str) -> Result<(f64, f64, f64), String> {
    let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
    let (rate, chans, samples) = read_wav(&bytes)?;
    let mut m = filmcraft_audio_dsp::loudness::LoudnessMeter::new(rate as f64, chans);
    for chunk in samples.chunks(chans * 4800) {
        m.process_interleaved(chunk);
    }
    Ok((m.integrated(), m.true_peak_dbtp(), m.loudness_range()))
}

/// Simple WAV reader: PCM 16/24/32-bit and float 32, interleaved.
fn read_wav(b: &[u8]) -> Result<(u32, usize, Vec<f32>), String> {
    if b.len() < 12 || &b[0..4] != b"RIFF" || &b[8..12] != b"WAVE" {
        return Err("not a WAV file".into());
    }
    let u16le = |i: usize| u16::from_le_bytes([b[i], b[i + 1]]);
    let u32le = |i: usize| u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]]);
    let mut i = 12;
    let (mut fmt, mut chans, mut rate, mut bits) = (0u16, 0usize, 0u32, 0u16);
    while i + 8 <= b.len() {
        let id = &b[i..i + 4];
        let size = u32le(i + 4) as usize;
        let body = i + 8;
        let end = body.saturating_add(size).min(b.len());
        if id == b"fmt " {
            if size < 16 || body + 16 > b.len() {
                return Err("corrupt WAV (fmt too short)".into());
            }
            fmt = u16le(body);
            chans = u16le(body + 2) as usize;
            rate = u32le(body + 4);
            bits = u16le(body + 14);
            if fmt == 0xFFFE && size >= 26 && body + 26 <= b.len() {
                fmt = u16le(body + 24); // WAVE_FORMAT_EXTENSIBLE: the subtype
            }
        } else if id == b"data" {
            if chans == 0 {
                return Err("WAV without fmt".into());
            }
            if rate == 0 || chans > 8 {
                return Err(format!("WAV with {chans} channels / {rate} Hz is not supported"));
            }
            let d = &b[body..end];
            let s: Vec<f32> = match (fmt, bits) {
                (1, 16) => d.chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]]) as f32 / 32768.0).collect(),
                (1, 24) => d.chunks_exact(3).map(|c| ((i32::from_le_bytes([0, c[0], c[1], c[2]]) >> 8) as f32) / 8_388_608.0).collect(),
                (1, 32) => d.chunks_exact(4).map(|c| i32::from_le_bytes([c[0], c[1], c[2], c[3]]) as f32 / 2_147_483_648.0).collect(),
                (3, 32) => d.chunks_exact(4).map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect(),
                _ => return Err(format!("WAV format {fmt}/{bits}-bit is not supported")),
            };
            return Ok((rate, chans, s));
        }
        i = body.saturating_add(size).saturating_add(size & 1);
    }
    Err("WAV without data".into())
}
