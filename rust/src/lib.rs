//! Klipp's bridge to the FilmCraft engine.
//!
//! Kotlin talks to the engine through a single call: `execute(command, json) -> json`, just as
//! FilmCraft itself does between its UI and the engine. There is also `snapshot()`, which returns the timeline and
//! media pool in a compact JSON format that the UI draws from.

use filmcraft_engine::Session;
use filmcraft_engine::project::{BinEntry, Project, Sequence, TrackItem};
use filmcraft_engine::time::Tick;
use serde_json::{Value, json};
use std::sync::Arc;

pub mod audio;
pub mod fds;
pub mod render;
pub mod track;

pub struct Bridge {
    pub session: Session,
    /// Length of the undo stack when the outermost group began (see `klipp.beginGroup`).
    group: Option<usize>,
    /// How many `klipp.beginGroup` calls are waiting for their `klipp.endGroup` (nested groups merge into the outermost).
    depth: usize,
    /// The history limit before the group (raised while a group is open, so no steps drop off in the middle of it).
    saved_limit: Option<usize>,
    /// The engine crashed (caught panic) in the middle of a command: the session may be half-modified, so everything except
    /// `file.open`/`file.newProject` is refused until a project has been opened or created anew.
    tainted: bool,
    /// One id per step in the engine's undo stack (same order) and in the redo stack. Kotlin places its own steps
    /// (color changes) between the engine's using them: a new engine step always gets a new, larger id.
    undo_ids: Vec<u64>,
    redo_ids: Vec<u64>,
    next_step: u64,
    /// Increments on every new edit in the engine (not undo/redo): Klipp's own redo steps no longer apply then.
    edit_serial: u64,
}

fn secs(t: Tick) -> f64 {
    t.seconds()
}

impl Bridge {
    pub fn new() -> Self {
        Bridge {
            // files from Android given as /proc/self/fd/N are read from their open handles (see fds.rs)
            session: Session::new(Arc::new(fds::KlippServices)),
            group: None,
            depth: 0,
            saved_limit: None,
            tainted: false,
            undo_ids: Vec::new(),
            redo_ids: Vec::new(),
            next_step: 1,
            edit_serial: 0,
        }
    }

    /// Runs an engine command. Response: `{"ok":true,"result":…}` or `{"ok":false,"error":"…"}`.
    ///
    /// Klipp's own: `klipp.beginGroup` / `klipp.endGroup {label}` merge everything in between into one
    /// undo step (e.g. a speed ramp = several splits and speeds).
    pub fn execute(&mut self, id: &str, params: &str) -> String {
        let resets = matches!(id, "file.open" | "file.newProject");
        if self.tainted && !resets {
            return json!({"ok": false, "error": "the engine crashed earlier – open the project again"}).to_string();
        }
        let before = self.undo_ptrs();
        let redo_before = self.session.history.redo.len();
        let out = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| self.execute_inner(id, params, resets))) {
            Ok(out) => out,
            Err(_) => {
                self.tainted = true;
                json!({"ok": false, "error": format!("the engine crashed in `{id}`")}).to_string()
            }
        };
        self.sync_step_ids(id, &before, redo_before);
        out
    }

    fn undo_ptrs(&self) -> Vec<usize> {
        self.session.history.undo.iter().map(|u| Arc::as_ptr(&u.1) as usize).collect()
    }

    /// Keeps [undo_ids]/[redo_ids] in step with the engine's stacks after a command. Steps are recognized by their
    /// project object (the engine keeps the same object through undo and redo); a step that didn't exist before is new.
    fn sync_step_ids(&mut self, cmd: &str, before: &[usize], redo_before: usize) {
        let after = self.undo_ptrs();
        let redo_after = self.session.history.redo.len();
        let n = before.len();
        if cmd == "edit.undo" && after.len() + 1 == n && after[..] == before[..n - 1] && redo_after == redo_before + 1 {
            let id = self.undo_ids.pop().unwrap_or(0);
            self.redo_ids.push(id);
            return;
        }
        if cmd == "edit.redo" && after.len() == n + 1 && after[..n] == before[..] && redo_after + 1 == redo_before {
            let id = match self.redo_ids.pop() {
                Some(id) if id != 0 => id,
                _ => self.fresh_step(),
            };
            self.undo_ids.push(id);
            return;
        }
        // everything else: steps that remain keep their id (even when the oldest drops off or a group is
        // merged), new steps get new ones
        let old: std::collections::HashMap<usize, u64> = before.iter().copied().zip(self.undo_ids.iter().copied()).collect();
        let mut fresh = false;
        let mut ids = Vec::with_capacity(after.len());
        for p in &after {
            match old.get(p) {
                Some(&id) => ids.push(id),
                None => {
                    ids.push(self.fresh_step());
                    fresh = true;
                }
            }
        }
        self.undo_ids = ids;
        if redo_after == 0 {
            if redo_before > 0 {
                fresh = true;
            }
            self.redo_ids.clear();
        } else if redo_after != self.redo_ids.len() {
            // (doesn't happen: only undo/redo changes the redo stack without clearing it)
            self.redo_ids.resize(redo_after, 0);
        }
        if fresh {
            self.edit_serial += 1;
        }
    }

    fn fresh_step(&mut self) -> u64 {
        let id = self.next_step;
        self.next_step += 1;
        id
    }

    fn execute_inner(&mut self, id: &str, params: &str, resets: bool) -> String {
        let params: Value = if params.trim().is_empty() {
            json!({})
        } else {
            match serde_json::from_str(params) {
                Ok(v) => v,
                Err(e) => return json!({"ok": false, "error": format!("invalid JSON: {e}")}).to_string(),
            }
        };
        match id {
            "klipp.beginGroup" => {
                if self.depth == 0 {
                    self.group = Some(self.session.history.undo.len());
                    // full history (200 steps): otherwise the engine removes the oldest step on every new one, the length
                    // stays put and the group couldn't be merged – as FilmCraft itself does in its groups
                    let h = &mut self.session.history;
                    if self.saved_limit.is_none() {
                        self.saved_limit = Some(h.limit);
                    }
                    h.limit = usize::MAX;
                }
                self.depth += 1;
                return json!({"ok": true, "result": null}).to_string();
            }
            "klipp.renameBin" => {
                // the engine only renames media items – folders (bins) are renamed here, as an undo step
                let (Some(id), Some(name)) = (params["bin"].as_u64(), params["name"].as_str()) else {
                    return json!({"ok": false, "error": "needs bin and name"}).to_string();
                };
                let name = name.to_string();
                let r = self.session.edit("Rename Bin", |pr, _| {
                    let b = pr.root.find_bin_mut(filmcraft_engine::project::BinId(id))
                        .ok_or_else(|| filmcraft_engine::EngineError::BadParams { cmd: "klipp.renameBin".into(), msg: format!("no folder {id}") })?;
                    b.name = name;
                    Ok(())
                });
                return match r {
                    Ok(()) => json!({"ok": true, "result": null}).to_string(),
                    Err(e) => json!({"ok": false, "error": e.to_string()}).to_string(),
                };
            }
            "klipp.transcriptOf" => {
                // one media item's transcript (the engine only lists the sequence's): words with media times
                let Some(id) = params["item"].as_u64() else {
                    return json!({"ok": false, "error": "needs item"}).to_string();
                };
                let words: Vec<Value> = self.session.project.transcripts.get(&filmcraft_engine::project::ItemId(id))
                    .map(|t| t.words.iter().map(|w| json!({"text": w.text, "start": w.start.0, "end": w.end.0})).collect())
                    .unwrap_or_default();
                let language = self.session.project.transcripts.get(&filmcraft_engine::project::ItemId(id)).map(|t| t.language.clone());
                return json!({"ok": true, "result": {"words": words, "language": language}}).to_string();
            }
            "klipp.clearRedo" => {
                // a change outside the engine (color) after an undo: the engine's redo steps no longer apply
                self.session.history.redo.clear();
                self.redo_ids.clear();
                return json!({"ok": true, "result": null}).to_string();
            }
            "klipp.endGroup" => {
                let steps = self.end_group(params["label"].as_str());
                return json!({"ok": true, "result": {"merged": steps}}).to_string();
            }
            _ => {}
        }
        match self.session.execute(id, params) {
            Ok(v) => {
                if resets {
                    // new/opened project: new undo history, no half-open groups or crashes left over
                    self.tainted = false;
                    self.group = None;
                    self.depth = 0;
                    if let Some(l) = self.saved_limit.take() {
                        self.session.history.limit = l;
                    }
                }
                json!({"ok": true, "result": v}).to_string()
            }
            Err(e) => json!({"ok": false, "error": e.to_string()}).to_string(),
        }
    }

    /// Ends an undo group: the steps after the group's start become one (the state before the group is kept).
    /// Nested groups: only the outermost `klipp.endGroup` merges.
    fn end_group(&mut self, label: Option<&str>) -> usize {
        if self.depth == 0 {
            self.group = None;
            return 0;
        }
        self.depth -= 1;
        if self.depth > 0 {
            return 0;
        }
        let group = self.group.take();
        let h = &mut self.session.history;
        let limit = self.saved_limit.take().unwrap_or(h.limit);
        h.limit = limit;
        h.merge_key = None;
        let mut steps = 0;
        // the stack may have shrunk during the group (undo) – then there is nothing safe to merge
        if let Some(n) = group {
            let len = h.undo.len();
            if len > n {
                steps = len - n;
                if steps > 1 {
                    h.undo.truncate(n + 1);
                }
                if let (Some(l), Some(last)) = (label, h.undo.last_mut()) {
                    last.0 = l.to_string();
                }
            }
        }
        // the limit applies again: the oldest steps go first
        if h.undo.len() > limit {
            let extra = h.undo.len() - limit;
            h.undo.drain(..extra);
        }
        steps
    }

    /// The audio effect chain for a clip (None if the clip has no DSP effects).
    pub fn fx_chain(&self, clip: u64) -> Option<audio::FxChain> {
        let q = self.session.active_sequence()?;
        let it = q.all_tracks().flat_map(|t| t.items.iter()).find(|i| i.id.0 == clip)?;
        audio::FxChain::new(audio::active_effects(&it.effects))
    }

    /// Timeline + media pool in seconds, for the UI.
    pub fn snapshot(&self) -> String {
        let s = &self.session;
        let mut media = Vec::new();
        let mut bins = Vec::new();
        collect_items(&s.project, &s.project.root.children, &mut media, s, None, &mut bins);
        let mut seq = s.active_sequence().map(|q| seq_json(q)).unwrap_or(Value::Null);
        if let Some(q) = s.active_sequence() {
            let t = s.targeting();
            let name_of = |id: Option<filmcraft_engine::project::TrackId>| {
                id.and_then(|id| q.all_tracks().find(|tr| tr.id == id)).map(|tr| tr.id.0)
            };
            seq["videoTarget"] = json!(name_of(t.video_dest));
            seq["audioTarget"] = json!(name_of(t.audio_dest));
            seq["targeted"] = json!(t.targeted.iter().map(|i| i.0).collect::<Vec<_>>());
            // nested sequences that the clips point to (for playback, which flattens them)
            let mut nests = serde_json::Map::new();
            let mut todo: Vec<u64> = q.all_tracks().flat_map(|t| t.items.iter().map(|i| i.item.0)).collect();
            let mut depth = 0;
            while !todo.is_empty() && depth < 4 {
                let mut next = Vec::new();
                for id in todo {
                    let key = id.to_string();
                    if nests.contains_key(&key) { continue }
                    if let Some(n) = s.project.sequence(filmcraft_engine::project::ItemId(id)) {
                        next.extend(n.all_tracks().flat_map(|t| t.items.iter().map(|i| i.item.0)));
                        nests.insert(key, seq_json(n));
                    }
                }
                todo = next;
                depth += 1;
            }
            if !nests.is_empty() {
                seq["nests"] = Value::Object(nests);
            }
        }
        json!({
            "playhead": secs(s.playhead()),
            "fps": s.active_sequence().map(|q| q.settings.frame_rate.as_f64()).unwrap_or(25.0),
            "canUndo": !s.history.undo.is_empty(),
            "canRedo": !s.history.redo.is_empty(),
            "undoIds": self.undo_ids,
            "redoCount": s.history.redo.len(),
            "redoIds": self.redo_ids,
            "editSerial": self.edit_serial,
            "linkedSelection": s.state.linked_selection,
            "media": media,
            "bins": bins,
            "sequence": seq,
        })
        .to_string()
    }
}

impl Default for Bridge {
    fn default() -> Self {
        Self::new()
    }
}

fn num(v: f64) -> Value {
    if v.is_finite() { json!(v) } else { Value::Null }
}

/// The clip's properties (Effect Controls) – the value at the clip's start; Klipp sets no keyframes.
fn clip_json(c: &TrackItem) -> Value {
    use filmcraft_engine::project::ParamValue;
    let t0 = Tick::ZERO;
    let mut v = json!({
        "id": c.id.0,
        "item": c.item.0,
        "name": c.name,
        "start": secs(c.start),
        "duration": secs(c.duration),
        "sourceIn": secs(c.source_in),
        "speed": c.speed,
        "link": c.link,
        // compound clip (Premiere's Group): the clips are selected, moved and colored together in the app
        "group": c.group,
        "reverse": c.reverse,
        // disabled clip (Premiere's "Enable"): stays in place but is not seen/heard
        "enabled": c.enabled,
        // freeze frame: the source time shown for the whole clip
        "hold": c.frame_hold.map(secs),
    });
    if let Some(m) = c.effect("motion") {
        let pos = m.param("position").map(|p| p.vec2_at(t0));
        v["x"] = pos.map(|p| num(p.x)).unwrap_or(Value::Null);
        v["y"] = pos.map(|p| num(p.y)).unwrap_or(Value::Null);
        v["scale"] = num(m.f64_at("scale", t0));
        v["rotation"] = num(m.f64_at("rotation", t0));
    }
    if let Some(o) = c.effect("opacity") {
        v["opacity"] = num(o.f64_at("opacity", t0));
    }
    if let Some(a) = c.effect("volume") {
        v["levelDb"] = num(a.f64_at("level", t0));
    }
    // keyframes (Effect Controls): time as media time in ticks (exact, for engine commands) and timeline time in seconds
    let mut kf = serde_json::Map::new();
    for (eff, param, key) in [("motion", "position", "position"), ("motion", "scale", "scale"), ("motion", "rotation", "rotation"), ("opacity", "opacity", "opacity"), ("volume", "level", "level")] {
        let Some(prm) = c.effect(eff).and_then(|e| e.param(param)) else { continue };
        if prm.keyframes.is_empty() { continue }
        let list: Vec<Value> = prm.keyframes.iter().map(|k| {
            // the inverse of the engine's moving source time (effects animate through a frame hold; a reversed clip
            // counts down from its source out point)
            let sp = if c.speed.abs() > 1e-9 { c.speed.abs() } else { 1.0 };
            let tl = if c.reverse {
                secs(c.start) + (secs(c.source_in) + secs(c.duration) * sp - secs(k.time)) / sp
            } else {
                secs(c.start) + (secs(k.time) - secs(c.source_in)) / sp
            };
            let v = match &k.value {
                ParamValue::Float(f) => json!([num(*f)]),
                ParamValue::Vec2(p) => json!([num(p.x), num(p.y)]),
                _ => json!([]),
            };
            json!({"mt": k.time.0, "t": tl, "v": v, "interp": format!("{:?}", k.interp), "inInf": k.in_influence, "outInf": k.out_influence})
        }).collect();
        kf.insert(key.into(), Value::Array(list));
    }
    if !kf.is_empty() {
        v["kf"] = Value::Object(kf);
    }
    // audio effects with DSP (index = position in the clip's rack, for effects.setParam/remove)
    let afx: Vec<Value> = c
        .effects
        .iter()
        .enumerate()
        .filter(|(_, e)| !e.def().is_some_and(|d| d.intrinsic) && filmcraft_engine::render::audio_fx::supported(&e.effect))
        .map(|(i, e)| {
            let mut p = serde_json::Map::new();
            for (k, prm) in &e.params {
                let x = prm.keyframes.first().map(|k| &k.value).unwrap_or(&prm.value);
                if let Some(f) = x.as_f64() {
                    p.insert(k.clone(), num(f));
                }
            }
            json!({"i": i, "id": e.effect, "on": e.enabled, "p": p})
        })
        .collect();
    if !afx.is_empty() {
        v["afx"] = Value::Array(afx);
    }
    if let Some(l) = c.graphic_layers().find(|e| e.effect == filmcraft_engine::project::graphic::TEXT_LAYER) {
        let text = match l.param("text").map(|p| &p.value) {
            Some(ParamValue::Text(t)) => t.clone(),
            _ => String::new(),
        };
        let color = match l.param("fill_color").map(|p| &p.value) {
            Some(ParamValue::Color(c)) => *c,
            _ => [1.0, 1.0, 1.0, 1.0],
        };
        let pos = l.vec2_at("position", t0);
        let name = match l.param("name").map(|p| &p.value) {
            Some(ParamValue::Text(t)) => t.clone(),
            _ => String::new(),
        };
        v["text"] = json!({"text": text, "size": l.f64_at("size", t0), "x": num(pos.x), "y": num(pos.y), "color": color, "name": name});
    }
    v
}

fn seq_json(q: &Sequence) -> Value {
    let tracks = |ts: &Vec<filmcraft_engine::project::Track>| -> Value {
        ts.iter()
            .map(|t| {
                json!({
                    "id": t.id.0, "name": t.name, "locked": t.locked, "muted": t.muted, "solo": t.solo,
                    "enabled": t.enabled, "volumeDb": t.volume_db, "pan": t.pan,
                    "syncLock": t.sync_lock,
                    "autoMode": format!("{:?}", t.mixer.mode),
                    // volume automation (the track's keyframes, sequence time): [[s, dB], …]
                    "volKf": t.mixer.lanes.get("volume").map(|l| l.keyframes.iter().map(|k| json!([secs(k.time), num(k.value.as_f64().unwrap_or(0.0))])).collect::<Vec<_>>()).unwrap_or_default(),
                    "clips": t.items.iter().map(clip_json).collect::<Vec<_>>(),
                    "transitions": t.transitions.iter().map(|x| json!({
                        "id": x.id.0, "effect": x.effect.effect, "start": secs(x.start), "duration": secs(x.duration),
                        "from": x.from.map(|c| c.0), "to": x.to.map(|c| c.0), "reverse": x.reverse,
                    })).collect::<Vec<_>>(),
                })
            })
            .collect()
    };
    json!({
        "width": q.settings.width,
        "height": q.settings.height,
        "sampleRate": q.settings.sample_rate,
        "duration": secs(q.duration()),
        "markIn": q.mark_in.map(secs),
        "markOut": q.mark_out.map(secs),
        "markers": q.markers.iter().map(|m| json!({"id": m.id.0, "time": secs(m.start), "duration": secs(m.duration), "name": m.name, "color": format!("{:?}", m.color)})).collect::<Vec<_>>(),
        "video": tracks(&q.video_tracks),
        "audio": tracks(&q.audio_tracks),
        "captionTracks": q.caption_tracks.iter().map(|t| {
            let st = &t.style;
            let hex = |c: [u8; 4]| format!("#{:02x}{:02x}{:02x}{:02x}", c[0], c[1], c[2], c[3]);
            json!({"id": t.id.0, "name": t.name, "enabled": t.enabled, "count": t.captions.len(),
                "captionIds": t.captions.iter().map(|c| c.id.0).collect::<Vec<_>>(),
                "style": {"font": st.font, "size": st.size, "color": hex(st.color), "background": st.background,
                    "backgroundColor": hex(st.background_color), "align": format!("{:?}", st.align).to_lowercase(),
                    "anchor": format!("{:?}", st.anchor).to_lowercase(), "margin": st.margin, "lineSpacing": st.line_spacing,
                    "outline": st.outline, "outlineColor": hex(st.outline_color)}})
        }).collect::<Vec<_>>(),
        // what shows in the program monitor: all enabled caption tracks (with the track, for the style)
        "captions": q.caption_tracks.iter().filter(|t| t.enabled).flat_map(|t| t.captions.iter().map(move |c| (t.id.0, c)))
            .map(|(tid, c)| json!({"id": c.id.0, "track": tid, "start": secs(c.start), "duration": secs(c.duration), "text": c.text}))
            .collect::<Vec<_>>(),
        // captions on disabled tracks: not shown, but drawn gray in the timeline (so the track can be turned back on)
        "hiddenCaptions": q.caption_tracks.iter().filter(|t| !t.enabled).flat_map(|t| t.captions.iter().map(move |c| (t.id.0, c)))
            .map(|(tid, c)| json!({"id": c.id.0, "track": tid, "start": secs(c.start), "duration": secs(c.duration), "text": c.text}))
            .collect::<Vec<_>>(),
    })
}

/// The media in the project panel, with the folder (bin) it is in (null = top level). The folders are collected in `bins`.
fn collect_items(p: &Project, entries: &[BinEntry], out: &mut Vec<Value>, s: &Session, bin: Option<u64>, bins: &mut Vec<Value>) {
    for e in entries {
        match e {
            BinEntry::Bin(b) => {
                bins.push(json!({"id": b.id.0, "name": b.name, "parent": bin}));
                collect_items(p, &b.children, out, s, Some(b.id.0), bins)
            }
            BinEntry::Item(id) => {
                if let Some(it) = p.items.get(id) {
                    let filmcraft_engine::project::ItemKind::Media(mc) = &it.kind else { continue };
                    let dur = filmcraft_engine::media_duration(p, &s.media, *id).map(secs);
                    let path = match &mc.media {
                        filmcraft_engine::project::MediaRef::File { path } => Some(path.clone()),
                        _ => None,
                    };
                    out.push(json!({
                        "id": id.0,
                        "name": it.name,
                        "path": path,
                        "duration": dur,
                        "hasVideo": mc.info.video.is_some(),
                        "width": mc.info.video.as_ref().map(|v| v.width),
                        "height": mc.info.video.as_ref().map(|v| v.height),
                        "fps": mc.info.video.as_ref().map(|v| v.frame_rate.as_f64()),
                        "codec": mc.info.video.as_ref().map(|v| v.codec.clone()),
                        "hasAudio": !mc.info.audio_streams.is_empty(),
                        "markIn": mc.mark_in.map(secs),
                        "markOut": mc.mark_out.map(secs),
                        "bin": bin,
                        "offline": mc.offline,
                        // words in the item's transcript (0 = none) – the media library shows which files have text
                        "words": p.transcripts.get(id).map(|t| t.words.len()).unwrap_or(0),
                    }));
                }
            }
        }
    }
}

// ---------------------------------------------------------------- JNI

mod jni_api {
    use super::Bridge;
    use jni::JNIEnv;
    use jni::objects::{JClass, JString};
    use jni::sys::{jlong, jstring};
    use std::panic::{AssertUnwindSafe, catch_unwind};

    fn get(env: &mut JNIEnv, s: &JString) -> String {
        env.get_string(s).map(|s| s.into()).unwrap_or_default()
    }

    /// The string as a Java string. If that fails, a fixed error response is tried; null only if even that fails
    /// (or if a Java exception is already pending – it is then thrown in Kotlin).
    pub(super) fn ret(env: &mut JNIEnv, s: String) -> jstring {
        if let Ok(j) = env.new_string(s) {
            return j.into_raw();
        }
        if env.exception_check().unwrap_or(true) {
            return std::ptr::null_mut();
        }
        env.new_string("{\"ok\":false,\"error\":\"JNI error: could not create the response\"}")
            .map(|j| j.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }

    /// Writes panic messages to logcat (tag "Klipp"); the default hook runs too.
    pub(super) fn install_panic_hook() {
        static ONCE: std::sync::Once = std::sync::Once::new();
        ONCE.call_once(|| {
            let prev = std::panic::take_hook();
            std::panic::set_hook(Box::new(move |info: &std::panic::PanicHookInfo<'_>| {
                log_panic(&info.to_string());
                prev(info);
            }));
        });
    }

    #[cfg(target_os = "android")]
    fn log_panic(msg: &str) {
        use std::ffi::{CString, c_char};
        #[link(name = "log")]
        unsafe extern "C" {
            fn __android_log_write(prio: i32, tag: *const c_char, text: *const c_char) -> i32;
        }
        let text = CString::new(msg.replace('\0', " ")).unwrap_or_default();
        // 6 = ANDROID_LOG_ERROR
        // SAFETY: both pointers are valid, NUL-terminated C strings for the duration of the call.
        unsafe {
            __android_log_write(6, c"Klipp".as_ptr(), text.as_ptr());
        }
    }

    #[cfg(not(target_os = "android"))]
    fn log_panic(msg: &str) {
        eprintln!("klipp-bridge: {msg}");
    }

    /// 0 = the engine could not be created.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeNew(_env: JNIEnv, _c: JClass) -> jlong {
        install_panic_hook();
        catch_unwind(|| Box::into_raw(Box::new(Bridge::new())) as jlong).unwrap_or(0)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFree(_env: JNIEnv, _c: JClass, h: jlong) {
        if h == 0 {
            return;
        }
        // SAFETY: h comes from nativeNew and is freed only once (Kotlin zeroes the handle).
        let _ = catch_unwind(AssertUnwindSafe(|| drop(unsafe { Box::from_raw(h as *mut Bridge) })));
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeExecute<'a>(
        mut env: JNIEnv<'a>,
        _c: JClass<'a>,
        h: jlong,
        id: JString<'a>,
        params: JString<'a>,
    ) -> jstring {
        let out = catch_unwind(AssertUnwindSafe(|| {
            if h == 0 {
                return serde_json::json!({"ok": false, "error": "the engine is closed"}).to_string();
            }
            // SAFETY: the handle lives as long as the Engine object in Kotlin; calls come from one thread at a time.
            let b = unsafe { &mut *(h as *mut Bridge) };
            let id = get(&mut env, &id);
            let params = get(&mut env, &params);
            b.execute(&id, &params)
        }))
        .unwrap_or_else(|_| "{\"ok\":false,\"error\":\"the engine crashed\"}".to_string());
        ret(&mut env, out)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeSnapshot<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, h: jlong) -> jstring {
        let out = if h == 0 {
            "{\"error\":\"the engine is closed\"}".to_string()
        } else {
            // SAFETY: see nativeExecute.
            let b = unsafe { &*(h as *mut Bridge) };
            catch_unwind(AssertUnwindSafe(|| b.snapshot())).unwrap_or_else(|_| "{\"error\":\"snapshot crashed\"}".to_string())
        };
        ret(&mut env, out)
    }
}

// ---------------------------------------------------------------- JNI: audio

mod jni_audio {
    use super::audio;
    use jni::JNIEnv;
    use jni::objects::{JClass, JFloatArray, JString};
    use jni::sys::{jboolean, jdouble, jint, jlong, jstring};

    fn string(env: &mut JNIEnv, s: &JString) -> String {
        env.get_string(s).map(|s| s.into()).unwrap_or_default()
    }

    /// An effect chain from the snapshot's `afx` list (JSON). 0 = no effects.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFxNew<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, json: JString<'a>) -> jlong {
        super::jni_api::install_panic_hook();
        let s = string(&mut env, &json);
        std::panic::catch_unwind(|| {
            let v: serde_json::Value = serde_json::from_str(&s).ok()?;
            audio::FxChain::from_json(&v).map(audio::chain_new)
        })
        .ok()
        .flatten()
        .unwrap_or(0) as jlong
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFxFree(_env: JNIEnv, _c: JClass, fx: jlong) {
        let _ = std::panic::catch_unwind(|| audio::chain_free(fx as u64));
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFxCount(_env: JNIEnv, _c: JClass) -> jint {
        audio::chain_count() as jint
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFxSet<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, fx: jlong, index: jint, param: JString<'a>, value: jdouble) -> jboolean {
        let p = string(&mut env, &param);
        let Some(c) = audio::chain_get(fx as u64) else { return 0 };
        // the new DSP is built without the lock (the audio thread must not wait for it); the lock is taken only to read/swap in
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let index = index as usize;
            let Some((pos, inst, rate)) = c.lock().unwrap_or_else(|e| e.into_inner()).prepare_param(index, &p, value) else {
                return false;
            };
            let fresh = audio::FxChain::configure_inst(&inst, rate);
            c.lock().unwrap_or_else(|e| e.into_inner()).apply_param(pos, index, &p, value, fresh, rate)
        }))
        .unwrap_or(false) as jboolean
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFxLatency(_env: JNIEnv, _c: JClass, fx: jlong, rate: jint) -> jint {
        let Some(c) = audio::chain_get(fx as u64) else { return 0 };
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| c.lock().unwrap_or_else(|e| e.into_inner()).latency_at(rate.max(1) as u32))).unwrap_or(0) as jint
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFxReset(_env: JNIEnv, _c: JClass, fx: jlong) {
        let _ = std::panic::catch_unwind(|| {
            if let Some(c) = audio::chain_get(fx as u64) {
                // a chain that crashed mid-processing is reset and becomes usable again
                c.lock().unwrap_or_else(|e| e.into_inner()).reset();
                c.clear_poison();
            }
        });
    }

    /// Processes `len` interleaved floats (`channels` channels) in place.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFxProcess<'a>(env: JNIEnv<'a>, _c: JClass<'a>, fx: jlong, data: JFloatArray<'a>, len: jint, channels: jint, rate: jint) {
        let Some(c) = audio::chain_get(fx as u64) else { return };
        let n = len.max(0) as usize;
        let mut buf = vec![0f32; n];
        if env.get_float_array_region(&data, 0, &mut buf).is_err() {
            return;
        }
        // a poisoned chain (panic mid-processing) is skipped until nativeFxReset
        let ok = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| match c.lock() {
            Ok(mut g) => {
                g.process(&mut buf, channels.max(1) as usize, rate.max(1) as u32);
                true
            }
            Err(_) => false,
        }));
        if ok.unwrap_or(false) {
            let _ = env.set_float_array_region(&data, 0, &buf);
        }
    }

    /// Like nativeFxProcess, but directly in a direct ByteBuffer (PCM 16-bit or float) without copies via
    /// Java arrays – the audio thread avoids two copies and a per-sample loop in Kotlin.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFxProcessDirect<'a>(
        env: JNIEnv<'a>, _c: JClass<'a>, fx: jlong, buf: jni::objects::JByteBuffer<'a>, offset: jint, frames: jint, channels: jint, float: jboolean, rate: jint,
    ) -> jboolean {
        let Some(c) = audio::chain_get(fx as u64) else { return 0 };
        let Ok(ptr) = env.get_direct_buffer_address(&buf) else { return 0 };
        let Ok(cap) = env.get_direct_buffer_capacity(&buf) else { return 0 };
        if ptr.is_null() || offset < 0 || frames < 0 || channels < 0 {
            return 0;
        }
        let ch = (channels as usize).max(1);
        let bps: usize = if float != 0 { 4 } else { 2 };
        let off = offset as usize;
        let Some(n) = (frames as usize).checked_mul(ch) else { return 0 };
        let Some(end) = n.checked_mul(bps).and_then(|len| off.checked_add(len)) else { return 0 };
        if end > cap {
            return 0;
        }
        // SAFETY: the address and capacity come from the JVM; the region lies within the buffer (checked above),
        // and the buffer is not used by anyone else during the call (the audio processor waits for the result).
        let bytes = unsafe { std::slice::from_raw_parts_mut(ptr.add(off), n * bps) };
        thread_local! { static TMP: std::cell::RefCell<Vec<f32>> = const { std::cell::RefCell::new(Vec::new()) }; }
        let ok = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            TMP.with(|t| {
                let mut v = t.borrow_mut();
                v.resize(n, 0.0);
                if bps == 4 {
                    for (i, x) in v.iter_mut().enumerate() { *x = f32::from_ne_bytes([bytes[i * 4], bytes[i * 4 + 1], bytes[i * 4 + 2], bytes[i * 4 + 3]]); }
                } else {
                    for (i, x) in v.iter_mut().enumerate() { *x = i16::from_ne_bytes([bytes[i * 2], bytes[i * 2 + 1]]) as f32 / 32768.0; }
                }
                // a poisoned chain (panic mid-processing) is skipped until nativeFxReset
                let Ok(mut g) = c.lock() else { return false };
                g.process(&mut v, ch, rate.max(1) as u32);
                drop(g);
                if bps == 4 {
                    for (i, x) in v.iter().enumerate() { bytes[i * 4..i * 4 + 4].copy_from_slice(&x.to_ne_bytes()); }
                } else {
                    for (i, x) in v.iter().enumerate() { bytes[i * 2..i * 2 + 2].copy_from_slice(&((x * 32768.0).round().clamp(-32768.0, 32767.0) as i16).to_ne_bytes()); }
                }
                true
            })
        }));
        ok.unwrap_or(false) as jboolean
    }

    /// Curve for a graphical effect editor (see `audio::curve`). null if the effect has none.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeFxCurve<'a>(
        mut env: JNIEnv<'a>, _c: JClass<'a>, json: JString<'a>, kind: JString<'a>, xs: jni::objects::JDoubleArray<'a>,
    ) -> jni::sys::jdoubleArray {
        let j = string(&mut env, &json);
        let k = string(&mut env, &kind);
        let n = env.get_array_length(&xs).unwrap_or(0).max(0) as usize;
        let mut v = vec![0f64; n];
        if env.get_double_array_region(&xs, 0, &mut v).is_err() {
            return std::ptr::null_mut();
        }
        let out = std::panic::catch_unwind(|| serde_json::from_str::<serde_json::Value>(&j).ok().and_then(|e| audio::curve(&e, &k, &v))).ok().flatten();
        let Some(out) = out else { return std::ptr::null_mut() };
        let Ok(arr) = env.new_double_array(out.len() as i32) else { return std::ptr::null_mut() };
        if env.set_double_array_region(&arr, 0, &out).is_err() {
            return std::ptr::null_mut();
        }
        arr.into_raw()
    }

    /// Loudness of a WAV file: `{"lufs":…, "truePeak":…, "lra":…}` or `{"error":…}`.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeMeasureWav<'a>(mut env: JNIEnv<'a>, _c: JClass<'a>, path: JString<'a>) -> jstring {
        let p = string(&mut env, &path);
        let out = match std::panic::catch_unwind(|| audio::measure_wav(&p)) {
            Ok(Ok((i, tp, lra))) => serde_json::json!({"lufs": super::num(i), "truePeak": super::num(tp), "lra": super::num(lra)}),
            Ok(Err(e)) => serde_json::json!({"error": e}),
            Err(_) => serde_json::json!({"error": "the measurement crashed"}),
        };
        super::jni_api::ret(&mut env, out.to_string())
    }
}

// ---------------------------------------------------------------- JNI: Power Window tracking

mod jni_track {
    use super::track::{Roi, Tracker};
    use jni::JNIEnv;
    use jni::objects::{JByteBuffer, JClass};
    use jni::sys::{jboolean, jfloat, jfloatArray, jint, jlong};
    use std::panic::{AssertUnwindSafe, catch_unwind};

    /// Tracker that works in `w`×`h` (the luma plane is scaled down to that). 0 = invalid size or error.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeTrackNew(_env: JNIEnv, _c: JClass, w: jint, h: jint) -> jlong {
        super::jni_api::install_panic_hook();
        if !(16..=4096).contains(&w) || !(16..=4096).contains(&h) {
            return 0;
        }
        catch_unwind(|| Box::into_raw(Box::new(Tracker::new(w as usize, h as usize))) as jlong).unwrap_or(0)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeTrackFree(_env: JNIEnv, _c: JClass, h: jlong) {
        if h == 0 {
            return;
        }
        // SAFETY: h comes from nativeTrackNew and is freed only once (Kotlin zeroes the handle).
        let _ = catch_unwind(AssertUnwindSafe(|| drop(unsafe { Box::from_raw(h as *mut Tracker) })));
    }

    /// Loads a luma plane (direct ByteBuffer from a MediaCodec Image) and tracks: `reset` = start over with the window
    /// (cx, cy, rx, ry normalized, shape 1 = ellipse, 2 = rectangle), otherwise one step (the window is ignored).
    /// Response `[dx, dy, scale, rotation, quality, points, x0, y0, x1, y1, …]` (the tracked points, normalized);
    /// null on invalid arguments or error.
    #[unsafe(no_mangle)]
    #[allow(clippy::too_many_arguments)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeTrackFrame<'a>(
        env: JNIEnv<'a>, _c: JClass<'a>, h: jlong, buf: JByteBuffer<'a>, offset: jint, row_stride: jint, pixel_stride: jint, src_w: jint, src_h: jint,
        reset: jboolean, cx: jfloat, cy: jfloat, rx: jfloat, ry: jfloat, shape: jint,
    ) -> jfloatArray {
        let null = std::ptr::null_mut();
        if h == 0 || offset < 0 || row_stride <= 0 || pixel_stride <= 0 || src_w <= 0 || src_h <= 0 {
            return null;
        }
        let Ok(ptr) = env.get_direct_buffer_address(&buf) else { return null };
        let Ok(cap) = env.get_direct_buffer_capacity(&buf) else { return null };
        if ptr.is_null() {
            return null;
        }
        let (off, rs, ps, sw, sh) = (offset as usize, row_stride as usize, pixel_stride as usize, src_w as usize, src_h as usize);
        // last byte read: offset + (srcH−1)·rowStride + (srcW−1)·pixelStride < capacity
        let last = (sh - 1).checked_mul(rs).and_then(|a| (sw - 1).checked_mul(ps).and_then(|b| a.checked_add(b))).and_then(|a| a.checked_add(off));
        let Some(last) = last.filter(|&l| l < cap) else { return null };
        // SAFETY: the address and capacity come from the JVM and the region [off, last] lies within the buffer (checked
        // above); the buffer is not written during the call (the Image is held open until the result has come back).
        let bytes = unsafe { std::slice::from_raw_parts(ptr.add(off) as *const u8, last + 1 - off) };
        let out = catch_unwind(AssertUnwindSafe(|| {
            // SAFETY: the handle lives as long as the tracker in Kotlin; calls come from one thread at a time.
            let t = unsafe { &mut *(h as *mut Tracker) };
            t.load(bytes, rs, ps, sw, sh);
            let r = if reset != 0 { t.reset(Roi { cx, cy, rx, ry, shape }) } else { t.step() };
            let mut v = vec![r.dx, r.dy, r.scale, r.rotation, r.quality, r.points as f32];
            // then the points (x, y normalized) – to show the point cloud
            if t.is_active() {
                t.points_norm(&mut v);
            }
            v
        }));
        let Ok(out) = out else { return null };
        let Ok(arr) = env.new_float_array(out.len() as i32) else { return null };
        if env.set_float_array_region(&arr, 0, &out).is_err() {
            return null;
        }
        arr.into_raw()
    }

    /// Stabilization: loads a luma plane and estimates the whole frame's motion since the previous frame. Response `[a, b, tx, ty,
    /// quality, points]` (similarity transform in working pixels), empty array if no estimate (first frame, too little
    /// structure), null on invalid arguments.
    #[unsafe(no_mangle)]
    #[allow(clippy::too_many_arguments)]
    pub extern "system" fn Java_se_gendut_klipp_engine_Engine_nativeMotionFrame<'a>(
        env: JNIEnv<'a>, _c: JClass<'a>, h: jlong, buf: JByteBuffer<'a>, offset: jint, row_stride: jint, pixel_stride: jint, src_w: jint, src_h: jint,
    ) -> jfloatArray {
        let null = std::ptr::null_mut();
        if h == 0 || offset < 0 || row_stride <= 0 || pixel_stride <= 0 || src_w <= 0 || src_h <= 0 {
            return null;
        }
        let Ok(ptr) = env.get_direct_buffer_address(&buf) else { return null };
        let Ok(cap) = env.get_direct_buffer_capacity(&buf) else { return null };
        if ptr.is_null() {
            return null;
        }
        let (off, rs, ps, sw, sh) = (offset as usize, row_stride as usize, pixel_stride as usize, src_w as usize, src_h as usize);
        let last = (sh - 1).checked_mul(rs).and_then(|a| (sw - 1).checked_mul(ps).and_then(|b| a.checked_add(b))).and_then(|a| a.checked_add(off));
        let Some(last) = last.filter(|&l| l < cap) else { return null };
        // SAFETY: as in nativeTrackFrame – the region lies within the buffer and is not written during the call.
        let bytes = unsafe { std::slice::from_raw_parts(ptr.add(off) as *const u8, last + 1 - off) };
        let out = catch_unwind(AssertUnwindSafe(|| {
            // SAFETY: the handle lives as long as the estimator in Kotlin; calls come from one thread at a time.
            let t = unsafe { &mut *(h as *mut Tracker) };
            t.load(bytes, rs, ps, sw, sh);
            t.global_step().map(|r| r.to_vec()).unwrap_or_default()
        }));
        let Ok(out) = out else { return null };
        let Ok(arr) = env.new_float_array(out.len() as i32) else { return null };
        if env.set_float_array_region(&arr, 0, &out).is_err() {
            return null;
        }
        arr.into_raw()
    }
}

#[cfg(test)]
mod tests;
