//! Clips that Media3 can't play as they are (reverse, freeze frame) are rendered by the engine to a file of their own,
//! in a separate session (the user's project and undo history are not affected).

use crate::Bridge;
use serde_json::{Value, json};

fn exec(b: &mut Bridge, id: &str, p: Value) -> Result<Value, String> {
    let r: Value = serde_json::from_str(&b.execute(id, &p.to_string())).map_err(|e| e.to_string())?;
    if r["ok"] == true { Ok(r["result"].clone()) } else { Err(format!("{id}: {}", r["error"].as_str().unwrap_or("error"))) }
}

/// Renders the source's `[src_in, src_in + dur)` (seconds) reversed and/or as a freeze frame at `hold`
/// (source time) to `out`: picture as H.264 (`video`), otherwise audio as WAV.
#[allow(clippy::too_many_arguments)]
pub fn render_clip(path: &str, src_in: f64, dur: f64, reverse: bool, hold: Option<f64>, video: bool, w: u32, h: u32, fps: f64, out: &str) -> Result<Value, String> {
    let tps = filmcraft_engine::time::TICKS_PER_SECOND as f64;
    let ticks = |s: f64| (s * tps).round() as i64;
    let mut b = Bridge::new();
    let r = exec(&mut b, "file.import", json!({"paths": [path]}))?;
    let item = r["items"][0].as_u64().ok_or("the import returned nothing")?;
    exec(&mut b, "file.newSequence", json!({"name": "klipp-render", "width": w.max(16), "height": h.max(16), "fps": if fps > 0.0 { fps } else { 25.0 }, "video": 1, "audio": 1}))?;
    let mut place = json!({"item": item, "seconds": 0.0, "insert": false, "sourceIn": ticks(src_in), "duration": ticks(dur), "video": video, "audio": !video});
    place["track"] = json!(if video { "V1" } else { "A1" });
    if !video {
        place["audioTrack"] = json!("A1");
    }
    let placed = exec(&mut b, "timeline.place", place)?;
    let clips: Vec<u64> = placed["clips"].as_array().map(|a| a.iter().filter_map(|v| v.as_u64()).collect()).unwrap_or_default();
    if clips.is_empty() {
        return Err("the clip could not be placed".into());
    }
    if reverse {
        exec(&mut b, "clip.speedDuration", json!({"clips": clips, "speed": 100, "reverse": true, "ripple": false}))?;
    }
    if let Some(t) = hold {
        exec(&mut b, "clip.frameHold", json!({"clips": clips, "time": ticks(t)}))?;
    }
    let mut p = json!({"path": out, "wait": true, "range": "entire"});
    if video {
        p["format"] = json!("h264");
        p["audio"] = json!(false);
        p["crf"] = json!(18);
    } else {
        p["format"] = json!("wav");
    }
    exec(&mut b, "file.exportMedia", p)
}
