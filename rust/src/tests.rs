use super::*;

fn media(name: &str) -> String {
    let dir = std::env::var("KLIPP_TEST_MEDIA").expect("set KLIPP_TEST_MEDIA to the folder with the test clips");
    format!("{dir}/{name}")
}

fn ok(b: &mut Bridge, id: &str, p: Value) -> Value {
    let r: Value = serde_json::from_str(&b.execute(id, &p.to_string())).unwrap();
    assert_eq!(r["ok"], true, "{id} {p}: {r}");
    r["result"].clone()
}

#[test]
fn import_mark_insert_overwrite_undo() {
    let mut b = Bridge::new();
    let r = ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4"), media("klipp_b.mp4")]}));
    println!("import -> {r}");
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    println!("media -> {}", snap["media"]);
    let items: Vec<u64> = snap["media"].as_array().unwrap().iter().map(|m| m["id"].as_u64().unwrap()).collect();
    assert_eq!(items.len(), 2);

    let m0 = &snap["media"][0];
    assert_eq!(m0["width"], 1280);
    ok(&mut b, "file.newSequence", json!({"width": m0["width"], "height": m0["height"], "fps": m0["fps"]}));

    // Source: clip A, in 2 s, out 6 s → insert at 0
    ok(&mut b, "source.open", json!({"item": items[0]}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": items[0], "in": 2 * tps, "out": 6 * tps}));
    ok(&mut b, "playhead.set", json!({"seconds": 0.0}));
    ok(&mut b, "source.insert", json!({}));

    // Clip B, in 1 s out 3 s → insert at 2 s (splits A)
    ok(&mut b, "source.open", json!({"item": items[1]}));
    ok(&mut b, "project.setMarks", json!({"item": items[1], "in": tps, "out": 3 * tps}));
    ok(&mut b, "playhead.set", json!({"seconds": 2.0}));
    ok(&mut b, "source.insert", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    println!("after insert -> {}", snap["sequence"]["video"][0]);
    let v1 = snap["sequence"]["video"][0]["clips"].as_array().unwrap().clone();
    assert_eq!(v1.len(), 3, "A split in two + B in the middle");
    assert!((snap["sequence"]["duration"].as_f64().unwrap() - 6.08).abs() < 0.05, "out point inclusive: 4.04 + 2.04");

    // Overwrite 1 s at 0.5 s → length unchanged
    ok(&mut b, "project.setMarks", json!({"item": items[1], "in": 4 * tps, "out": 5 * tps}));
    ok(&mut b, "playhead.set", json!({"seconds": 0.5}));
    ok(&mut b, "source.overwrite", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    println!("after overwrite -> {}", snap["sequence"]["video"][0]);
    assert!((snap["sequence"]["duration"].as_f64().unwrap() - 6.08).abs() < 0.05, "out point inclusive: 4.04 + 2.04");
    assert_eq!(snap["canUndo"], true);

    ok(&mut b, "edit.undo", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["sequence"]["video"][0]["clips"].as_array().unwrap().len(), 3);
    println!("audio -> {}", snap["sequence"]["audio"][0]);
    if let Ok(out) = std::env::var("KLIPP_SNAPSHOT_OUT") {
        std::fs::write(out, b.snapshot()).unwrap();
    }
}

/// Copy clip → source: in = the clip's sourceIn, out = sourceIn + length − 1 frame (out is inclusive).
/// An inserted copied clip must be exactly as long as the original.
#[test]
fn copied_clip_reinserts_with_same_length() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": 1, "in": 2 * tps, "out": 5 * tps}));
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "source.insert", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let c = snap["sequence"]["video"][0]["clips"][0].clone();
    let (src_in, dur) = (c["sourceIn"].as_f64().unwrap(), c["duration"].as_f64().unwrap());
    let frame = 1.0 / 25.0;
    let to_t = |s: f64| (s * tps as f64).round() as i64;
    ok(&mut b, "project.setMarks", json!({"item": 1, "in": to_t(src_in), "out": to_t(src_in + dur - frame)}));
    ok(&mut b, "playhead.set", json!({"seconds": dur}));
    ok(&mut b, "source.insert", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let clips = snap["sequence"]["video"][0]["clips"].as_array().unwrap().clone();
    assert_eq!(clips.len(), 2);
    assert!((clips[1]["duration"].as_f64().unwrap() - dur).abs() < 1e-6, "the copy is the same length: {clips:?}");
    assert!((clips[1]["sourceIn"].as_f64().unwrap() - src_in).abs() < 1e-6);
}

/// Save → open in a new engine → relink media to new paths (like new fd numbers after a restart).
#[test]
fn save_open_relink_markers_targets() {
    let dir = std::env::temp_dir().join(format!("klipp-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let a1 = dir.join("57");
    std::fs::copy(media("klipp_a.mp4"), &a1).unwrap();
    let proj = dir.join("current.fcproj");

    let mut b = Bridge::new();
    let r = ok(&mut b, "file.import", json!({"paths": [a1.to_string_lossy()]}));
    let item = r["items"][0].as_u64().unwrap();
    ok(&mut b, "clip.rename", json!({"item": item, "name": "IMG_1.mp4"}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": item, "in": tps, "out": 3 * tps}));
    ok(&mut b, "source.open", json!({"item": item}));
    ok(&mut b, "source.insert", json!({}));
    ok(&mut b, "markers.add", json!({"target": "program", "time": tps, "name": "Bra tagning"}));
    ok(&mut b, "timeline.setTrack", json!({"track": "A1", "volumeDb": -6.0}));
    ok(&mut b, "timeline.setTargeting", json!({"track": "V2", "targeted": true}));
    let before: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(before["sequence"]["markers"][0]["name"], "Bra tagning");
    assert!((before["sequence"]["audio"][0]["volumeDb"].as_f64().unwrap() + 6.0).abs() < 1e-6);
    println!("targets -> {} {} {}", before["sequence"]["videoTarget"], before["sequence"]["audioTarget"], before["sequence"]["targeted"]);
    if let Ok(out) = std::env::var("KLIPP_SNAPSHOT2_OUT") {
        std::fs::write(out, b.snapshot()).unwrap();
    }
    ok(&mut b, "file.save", json!({"path": proj.to_string_lossy()}));

    // "restart": the file has a new fd number
    let a2 = dir.join("91");
    std::fs::rename(&a1, &a2).unwrap();
    let mut b2 = Bridge::new();
    ok(&mut b2, "file.open", json!({"path": proj.to_string_lossy()}));
    let r = ok(&mut b2, "media.relink", json!({"item": item, "path": a2.to_string_lossy(), "force": true}));
    println!("relink -> {r}");
    let after: Value = serde_json::from_str(&b2.snapshot()).unwrap();
    assert_eq!(after["media"][0]["path"], json!(a2.to_string_lossy()));
    assert_eq!(after["media"][0]["name"], "IMG_1.mp4");
    assert_eq!(after["sequence"]["video"][0]["clips"].as_array().unwrap().len(), 1);
    assert_eq!(after["sequence"]["markers"][0]["name"], "Bra tagning");
    assert!((after["sequence"]["audio"][0]["volumeDb"].as_f64().unwrap() + 6.0).abs() < 1e-6);
    std::fs::remove_dir_all(&dir).ok();
}

/// Speed 200 % with ripple: the clip becomes half as long and the next clip moves up.
#[test]
fn speed_200_percent_ripples() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    for (inn, at) in [(0, 0.0), (5, 4.0)] {
        ok(&mut b, "project.setMarks", json!({"item": 1, "in": inn * tps, "out": (inn + 4) * tps - tps / 25}));
        ok(&mut b, "source.open", json!({"item": 1}));
        ok(&mut b, "playhead.set", json!({"seconds": at}));
        ok(&mut b, "source.insert", json!({}));
    }
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let first = snap["sequence"]["video"][0]["clips"][0]["id"].as_u64().unwrap();
    ok(&mut b, "clip.speedDuration", json!({"clips": [first], "speed": 200, "ripple": true}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let v = snap["sequence"]["video"][0]["clips"].as_array().unwrap();
    assert!((v[0]["speed"].as_f64().unwrap() - 2.0).abs() < 1e-9);
    assert!((v[0]["duration"].as_f64().unwrap() - 2.0).abs() < 0.05, "{v:?}");
    assert!((v[1]["start"].as_f64().unwrap() - 2.0).abs() < 0.05, "ripple moves the next clip: {v:?}");
}

/// Properties, title, transition and captions – all via engine commands, read back from the snapshot.
#[test]
fn properties_titles_transitions_captions() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    // two clips with handles on both sides: 1–4 s and 5–8 s
    for (inn, at) in [(1, 0.0), (5, 3.0)] {
        ok(&mut b, "project.setMarks", json!({"item": 1, "in": inn * tps, "out": (inn + 3) * tps - tps / 25}));
        ok(&mut b, "source.open", json!({"item": 1}));
        ok(&mut b, "playhead.set", json!({"seconds": at}));
        ok(&mut b, "source.insert", json!({}));
    }
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let v = snap["sequence"]["video"][0]["clips"].as_array().unwrap().clone();
    let a = snap["sequence"]["audio"][0]["clips"][0]["id"].as_u64().unwrap();
    let (c0, c1) = (v[0]["id"].as_u64().unwrap(), v[1]["id"].as_u64().unwrap());
    assert_eq!(v[0]["scale"], 100.0, "{}", v[0]);
    assert_eq!(v[0]["opacity"], 100.0);

    ok(&mut b, "effects.setParam", json!({"clip": c0, "effect": "motion", "param": "scale", "value": 50.0}));
    ok(&mut b, "effects.setParam", json!({"clip": c0, "effect": "motion", "param": "position", "value": [320.0, 180.0]}));
    ok(&mut b, "effects.setParam", json!({"clip": c0, "effect": "motion", "param": "rotation", "value": 15.0}));
    ok(&mut b, "effects.setParam", json!({"clip": c0, "effect": "opacity", "param": "opacity", "value": 60.0}));
    ok(&mut b, "effects.setParam", json!({"clip": a, "effect": "volume", "param": "level", "value": -9.0}));

    let r = ok(&mut b, "sequence.applyVideoTransition", json!({"clip": c1, "effect": "cross_dissolve", "frames": 20, "edge": "in"}));
    println!("transition -> {r}");

    ok(&mut b, "playhead.set", json!({"seconds": 1.0}));
    let r = ok(&mut b, "graphics.newText", json!({"text": "Hej Klipp", "size": 80.0, "seconds": 2.0, "position": [640.0, 600.0]}));
    let title = r["clip"].as_u64().unwrap();
    ok(&mut b, "graphics.setText", json!({"clip": title, "text": "Hej igen"}));
    ok(&mut b, "effects.setParam", json!({"clip": title, "effect": "graphic_text", "param": "size", "value": 80.0}));
    ok(&mut b, "effects.setParam", json!({"clip": title, "effect": "graphic_text", "param": "fill_color", "value": "#ffb77c"}));
    // Klipp's template and animation are stored in the layer's name
    ok(&mut b, "effects.setParam", json!({"clip": title, "effect": "graphic_text", "param": "name", "value": "klipp:mall=namnskylt;in=studs"}));

    let dir = std::env::temp_dir().join(format!("klipp-srt-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let srt = dir.join("sv.srt");
    std::fs::write(&srt, "1\n00:00:00,500 --> 00:00:02,000\nFörsta raden\n\n2\n00:00:02,500 --> 00:00:04,000\nAndra\nraden\n").unwrap();
    let r = ok(&mut b, "captions.import", json!({"path": srt.to_string_lossy()}));
    println!("captions -> {r}");

    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let q = &snap["sequence"];
    let c = &q["video"][0]["clips"][0];
    assert_eq!(c["scale"], 50.0);
    assert_eq!(c["x"], 320.0);
    assert_eq!(c["rotation"], 15.0);
    assert_eq!(c["opacity"], 60.0);
    assert_eq!(q["audio"][0]["clips"][0]["levelDb"], -9.0);
    let tr = &q["video"][0]["transitions"][0];
    assert_eq!(tr["effect"], "cross_dissolve", "{}", q["video"][0]);
    assert!((tr["duration"].as_f64().unwrap() - 0.8).abs() < 1e-6);
    let g = q["video"].as_array().unwrap().iter().flat_map(|t| t["clips"].as_array().unwrap().iter()).find(|c| c["id"] == title).unwrap().clone();
    println!("titel -> {g}");
    assert_eq!(g["text"]["text"], "Hej igen");
    assert_eq!(g["text"]["size"], 80.0);
    assert_eq!(g["text"]["name"], "klipp:mall=namnskylt;in=studs");
    assert_eq!(q["captions"].as_array().unwrap().len(), 2, "{}", q["captions"]);
    assert_eq!(q["captions"][1]["text"], "Andra\nraden");
    // edit and delete a caption
    let cid = q["captions"][0]["id"].as_u64().unwrap();
    ok(&mut b, "captions.setText", json!({"caption": cid, "text": "Ändrad rad"}));
    let s1: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(s1["sequence"]["captions"][0]["text"], "Ändrad rad");
    ok(&mut b, "edit.undo", json!({}));
    let col = g["text"]["color"].as_array().unwrap();
    assert!((col[0].as_f64().unwrap() - 1.0).abs() < 0.01 && (col[2].as_f64().unwrap() - 124.0 / 255.0).abs() < 0.01, "{col:?}");
    // the transition can be removed, and the caption track can be turned off
    let snap_before = b.snapshot();
    let tid = q["captionTracks"][0]["id"].as_u64().unwrap();
    ok(&mut b, "captions.setTrack", json!({"track": tid, "enabled": false}));
    let s2: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(s2["sequence"]["captions"].as_array().unwrap().len(), 0);
    // captions that are turned off remain (gray in the timeline)
    assert_eq!(s2["sequence"]["hiddenCaptions"].as_array().unwrap().len(), q["captions"].as_array().unwrap().len());
    ok(&mut b, "captions.setTrack", json!({"track": tid, "enabled": true}));
    ok(&mut b, "sequence.removeTransition", json!({"transitions": [tr["id"]]}));
    let s3: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!(s3["sequence"]["video"][0]["transitions"].as_array().unwrap().is_empty());
    if let Ok(out) = std::env::var("KLIPP_SNAPSHOT3_OUT") {
        std::fs::write(out, snap_before).unwrap();
    }
    std::fs::remove_dir_all(&dir).ok();
}

/// Linked selection: off = only the picture is trimmed, on = the audio follows.
#[test]
fn linked_selection_toggles_trim_of_partner() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": 1, "in": tps, "out": 5 * tps - tps / 25}));
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "source.insert", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["linkedSelection"], true, "on by default");
    let v = snap["sequence"]["video"][0]["clips"][0]["id"].as_u64().unwrap();
    ok(&mut b, "timeline.trim", json!({"clip": v, "edge": "out", "delta": -tps, "mode": "regular"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!((snap["sequence"]["audio"][0]["clips"][0]["duration"].as_f64().unwrap() - 3.0).abs() < 0.05, "the audio followed");
    let r = ok(&mut b, "sequence.linkedSelection", json!({"on": false}));
    assert_eq!(r["linkedSelection"], false);
    ok(&mut b, "timeline.trim", json!({"clip": v, "edge": "out", "delta": -tps, "mode": "regular"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["linkedSelection"], false);
    assert!((snap["sequence"]["video"][0]["clips"][0]["duration"].as_f64().unwrap() - 2.0).abs() < 0.05);
    assert!((snap["sequence"]["audio"][0]["clips"][0]["duration"].as_f64().unwrap() - 3.0).abs() < 0.05, "the audio stayed: {}", snap["sequence"]["audio"][0]);
}

/// Audio transitions: cross dissolve between two audio clips and a fade out to silence.
#[test]
fn audio_crossfade_and_fade_out() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    for (inn, at) in [(1, 0.0), (5, 3.0)] {
        ok(&mut b, "project.setMarks", json!({"item": 1, "in": inn * tps, "out": (inn + 3) * tps - tps / 25}));
        ok(&mut b, "source.open", json!({"item": 1}));
        ok(&mut b, "playhead.set", json!({"seconds": at}));
        ok(&mut b, "source.insert", json!({}));
    }
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let a = snap["sequence"]["audio"][0]["clips"].as_array().unwrap().clone();
    let (a0, a1) = (a[0]["id"].as_u64().unwrap(), a[1]["id"].as_u64().unwrap());
    ok(&mut b, "sequence.applyAudioTransition", json!({"clip": a1, "effect": "constant_power", "frames": 20, "edge": "in"}));
    ok(&mut b, "sequence.applyAudioTransition", json!({"clip": a1, "effect": "constant_power", "frames": 25, "edge": "out"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let tr = snap["sequence"]["audio"][0]["transitions"].as_array().unwrap().clone();
    println!("audio transitions -> {tr:?}");
    assert_eq!(tr.len(), 2);
    let cross = tr.iter().find(|x| x["from"] == a0).unwrap();
    assert_eq!(cross["to"], a1);
    assert!((cross["duration"].as_f64().unwrap() - 0.8).abs() < 1e-6);
    let out = tr.iter().find(|x| x["to"].is_null()).unwrap();
    assert!((out["start"].as_f64().unwrap() + out["duration"].as_f64().unwrap() - 6.0).abs() < 0.05, "ends at the clip's end: {out}");
    if let Ok(p) = std::env::var("KLIPP_SNAPSHOT4_OUT") { std::fs::write(p, b.snapshot()).unwrap(); }
}

#[test]
fn caption_delete() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let dir = std::env::temp_dir().join(format!("klipp-srt2-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let srt = dir.join("a.srt");
    std::fs::write(&srt, "1\n00:00:00,500 --> 00:00:02,000\nEtt\n\n2\n00:00:02,500 --> 00:00:04,000\nTvå\n").unwrap();
    ok(&mut b, "captions.import", json!({"path": srt.to_string_lossy()}));
    let s: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let id = s["sequence"]["captions"][0]["id"].as_u64().unwrap();
    ok(&mut b, "captions.delete", json!({"captions": [id]}));
    let s: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(s["sequence"]["captions"].as_array().unwrap().len(), 1);
    assert_eq!(s["sequence"]["captions"][0]["text"], "Två");
    std::fs::remove_dir_all(&dir).ok();
}

/// Keyframes: scale animated 100 → 50 % over the clip, moved and deleted; opacity toggled off/on.
#[test]
fn keyframes_add_move_delete() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": 1, "in": 2 * tps, "out": 6 * tps - tps / 25}));
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "playhead.set", json!({"seconds": 1.0}));
    ok(&mut b, "source.insert", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let c = snap["sequence"]["video"][0]["clips"][0]["id"].as_u64().unwrap();
    // the clip sits at 1–5 s, source from 2 s
    ok(&mut b, "effects.setParam", json!({"clip": c, "effect": "motion", "param": "scale", "value": 100.0, "keyframe": true, "time": tps}));
    ok(&mut b, "effects.setParam", json!({"clip": c, "effect": "motion", "param": "scale", "value": 50.0, "time": 3 * tps}));
    ok(&mut b, "effects.setParam", json!({"clip": c, "effect": "motion", "param": "position", "value": [100.0, 100.0], "keyframe": true, "time": 2 * tps}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let cl = &snap["sequence"]["video"][0]["clips"][0];
    println!("kf -> {}", cl["kf"]);
    let sk = cl["kf"]["scale"].as_array().unwrap();
    assert_eq!(sk.len(), 2, "animated parameter: new value at another time = new keyframe");
    assert!((sk[0]["t"].as_f64().unwrap() - 1.0).abs() < 1e-6);
    assert!((sk[1]["t"].as_f64().unwrap() - 3.0).abs() < 1e-6);
    assert_eq!(sk[1]["v"][0], 50.0);
    assert_eq!(sk[0]["mt"].as_i64().unwrap(), 2 * tps, "media time = source 2 s");
    assert_eq!(cl["kf"]["position"][0]["v"][1], 100.0);
    // move the second keyframe to 4 s (timeline) = source 5 s, and set smooth interpolation
    let mt = sk[1]["mt"].as_i64().unwrap();
    ok(&mut b, "effects.moveKeyframe", json!({"clip": c, "effect": "motion", "param": "scale", "mediaTime": mt, "to": 5 * tps}));
    ok(&mut b, "effects.setInterpolation", json!({"clip": c, "effect": "motion", "param": "scale", "mediaTime": 2 * tps, "interpolation": "autoBezier"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let sk = snap["sequence"]["video"][0]["clips"][0]["kf"]["scale"].as_array().unwrap().clone();
    assert!((sk[1]["t"].as_f64().unwrap() - 4.0).abs() < 1e-6, "{sk:?}");
    assert_eq!(sk[0]["interp"], "AutoBezier");
    ok(&mut b, "effects.deleteKeyframe", json!({"clip": c, "effect": "motion", "param": "scale", "mediaTime": 5 * tps}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["sequence"]["video"][0]["clips"][0]["kf"]["scale"].as_array().unwrap().len(), 1);
    // stopwatch off = static value, no keyframes
    ok(&mut b, "effects.toggleAnimation", json!({"clip": c, "effect": "motion", "param": "position"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!(snap["sequence"]["video"][0]["clips"][0]["kf"]["position"].is_null(), "{}", snap["sequence"]["video"][0]["clips"][0]["kf"]);
    if let Ok(p) = std::env::var("KLIPP_SNAPSHOT5_OUT") {
        ok(&mut b, "effects.setParam", json!({"clip": c, "effect": "motion", "param": "scale", "value": 50.0, "time": 3 * tps}));
        ok(&mut b, "effects.setParam", json!({"clip": c, "effect": "opacity", "param": "opacity", "value": 100.0, "keyframe": true, "time": tps}));
        ok(&mut b, "effects.setParam", json!({"clip": c, "effect": "opacity", "param": "opacity", "value": 0.0, "time": 5 * tps - tps / 25}));
        std::fs::write(p, b.snapshot()).unwrap();
    }
}

/// Project settings: resolution and frame rate can be changed afterwards.
#[test]
fn sequence_settings_change() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": 1, "in": tps, "out": 3 * tps - tps / 25}));
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "source.insert", json!({}));
    let r = ok(&mut b, "sequence.settings", json!({"width": 1080, "height": 1920, "fps": 30.0, "scaleMotion": false}));
    println!("settings -> {r}");
    let s: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(s["sequence"]["width"], 1080);
    assert_eq!(s["sequence"]["height"], 1920);
    assert!((s["fps"].as_f64().unwrap() - 30.0).abs() < 1e-6);
    assert_eq!(s["sequence"]["video"][0]["clips"].as_array().unwrap().len(), 1);
    assert!(s["sequence"]["sampleRate"].as_u64().unwrap() >= 44100);
    ok(&mut b, "sequence.settings", json!({"fps": 23.976}));
    let s: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!((s["fps"].as_f64().unwrap() - 23.976).abs() < 0.01, "{}", s["fps"]);
}

#[test]
#[ignore]
fn dump_commands() {
    let mut out = String::new();
    for c in filmcraft_engine::commands::command_specs() {
        out.push_str(&format!("{}\t{}\t{}\n", c.id, c.label, c.params));
    }
    std::fs::write(std::env::var("KLIPP_DUMP").unwrap(), out).unwrap();
}

/// Exploration of the engine's capabilities with real clips (run by hand).
#[test]
#[ignore]
fn explore_engine() {
    let mut b = Bridge::new();
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    let run = |b: &mut Bridge, id: &str, p: Value| {
        let t = std::time::Instant::now();
        let r = b.execute(id, &p.to_string());
        let s = if r.len() > 600 { format!("{}…", &r[..600]) } else { r.clone() };
        println!("== {id} ({} ms): {s}", t.elapsed().as_millis());
        serde_json::from_str::<Value>(&r).unwrap()
    };
    run(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4"), media("klipp_b.mp4")]}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let items: Vec<u64> = snap["media"].as_array().unwrap().iter().map(|m| m["id"].as_u64().unwrap()).collect();
    run(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    run(&mut b, "timeline.place", json!({"item": items[0], "track": "V1", "audioTrack": "A1", "seconds": 0.0, "insert": false}));
    run(&mut b, "timeline.place", json!({"item": items[1], "track": "V1", "audioTrack": "A1", "seconds": 10.0, "insert": false}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let a1 = snap["sequence"]["audio"][0]["clips"].as_array().unwrap().clone();
    let v1 = snap["sequence"]["video"][0]["clips"].as_array().unwrap().clone();
    println!("V1 {:?}", v1.iter().map(|c| (c["id"].clone(), c["start"].clone(), c["duration"].clone())).collect::<Vec<_>>());
    let ac = a1[0]["id"].as_u64().unwrap();
    let vc0 = v1[0]["id"].as_u64().unwrap();
    let vc1 = v1[1]["id"].as_u64().unwrap();
    run(&mut b, "effects.apply", json!({"clips": [ac], "effect": "DeNoise"}));
    run(&mut b, "effects.apply", json!({"clips": [ac], "effect": "Parametric Equalizer"}));
    run(&mut b, "effects.apply", json!({"clips": [ac], "effect": "Dynamics"}));
    {
        let q = b.session.active_sequence().unwrap();
        let it = q.audio_tracks[0].items.iter().find(|i| i.id.0 == ac).unwrap();
        for (i, e) in it.effects.iter().enumerate() {
            println!("fx[{i}] {} enabled={} params={:?}", e.effect, e.enabled, e.params.keys().collect::<Vec<_>>());
        }
    }
    run(&mut b, "essentialSound.autoMatch", json!({"clips": [ac], "target": -16.0}));
    run(&mut b, "clip.audioPeak", json!({"clips": [ac]}));
    run(&mut b, "mixer.setKeyframe", json!({"strip": "A1", "lane": "volume", "time": 2 * tps, "value": -6.0}));
    run(&mut b, "mixer.setKeyframe", json!({"strip": "A1", "lane": "volume", "time": 4 * tps, "value": 0.0}));
    run(&mut b, "mixer.inspect", json!({"time": 3 * tps}));
    run(&mut b, "timeline.roll", json!({"left": vc0, "right": vc1, "deltaFrames": -10}));
    run(&mut b, "timeline.slip", json!({"clip": vc1, "deltaFrames": 12}));
    run(&mut b, "timeline.slide", json!({"clip": vc1, "deltaFrames": -5}));
    run(&mut b, "timeline.setTrack", json!({"track": "V1", "locked": true}));
    run(&mut b, "timeline.slip", json!({"clip": vc1, "deltaFrames": 12}));
    run(&mut b, "timeline.setTrack", json!({"track": "V1", "locked": false, "syncLock": true}));
    run(&mut b, "clip.frameHold", json!({"clips": [vc0], "time": tps}));
    run(&mut b, "clip.speedDuration", json!({"clips": [vc1], "speed": 100, "reverse": true, "ripple": false}));
    run(&mut b, "clip.insertFrameHoldSegment", json!({"clip": vc1, "seconds": 1.0}));
    run(&mut b, "timeline.select", json!({"clips": [vc0]}));
    run(&mut b, "clip.nest", json!({"name": "Nästlad"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    println!("V1 after: {}", snap["sequence"]["video"][0]["clips"]);
    run(&mut b, "file.newBin", json!({"name": "Intervjuer"}));
    run(&mut b, "project.items", json!({"recursive": true}));
    run(&mut b, "media.proxyPresets", json!({}));
    let dir = std::env::temp_dir().join("klipp-proxy");
    std::fs::create_dir_all(&dir).unwrap();
    run(&mut b, "media.createProxies", json!({"items": [items[0]], "preset": "h264_quarter", "destination": dir.to_string_lossy(), "wait": true}));
    run(&mut b, "media.status", json!({"item": items[0]}));
    let wav = std::env::temp_dir().join("klipp-mix.wav");
    run(&mut b, "file.exportMedia", json!({"path": wav.to_string_lossy(), "format": "wav", "wait": true}));
    println!("wav: {:?}", std::fs::metadata(&wav).map(|m| m.len()));
    run(&mut b, "sequence.transcribe", json!({"track": "A1", "language": "auto"}));
    run(&mut b, "file.listAutoSaves", json!({}));
    run(&mut b, "export.formats", json!({}));
}

/// Audio round 0.15: effects in the snapshot, the effect chain (live parameters), volume automation, loudness.
#[test]
fn audio_fx_chain_automation_loudness() {
    let mut b = Bridge::new();
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let item = snap["media"][0]["id"].as_u64().unwrap();
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    ok(&mut b, "timeline.place", json!({"item": item, "track": "V1", "audioTrack": "A1", "seconds": 0.0, "insert": false}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let ac = snap["sequence"]["audio"][0]["clips"][0]["id"].as_u64().unwrap();
    assert!(snap["sequence"]["audio"][0]["clips"][0]["afx"].is_null(), "no effects yet");
    assert!(b.fx_chain(ac).is_none());

    ok(&mut b, "effects.apply", json!({"clips": [ac], "effect": "denoise"}));
    ok(&mut b, "effects.apply", json!({"clips": [ac], "effect": "parametric_eq"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let afx = snap["sequence"]["audio"][0]["clips"][0]["afx"].as_array().unwrap().clone();
    assert_eq!(afx.len(), 2);
    assert_eq!(afx[0]["id"], "denoise");
    assert_eq!(afx[0]["p"]["amount"], 40.0);
    let deq = afx[1]["i"].as_u64().unwrap();
    ok(&mut b, "effects.setParam", json!({"clip": ac, "effect": deq, "param": "low_gain", "value": 6.0}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["sequence"]["audio"][0]["clips"][0]["afx"][1]["p"]["low_gain"], 6.0);

    // the chain processes audio and accepts live parameters
    let mut chain = b.fx_chain(ac).expect("chain");
    let mut sig: Vec<f32> = (0..9600).map(|i| ((i as f32 * 0.05).sin() * 0.3) + (((i * 7919) % 1000) as f32 / 1000.0 - 0.5) * 0.05).collect();
    let orig = sig.clone();
    chain.process(&mut sig, 2, 48000);
    assert!(sig.iter().all(|v| v.is_finite()));
    assert!(sig.iter().zip(&orig).any(|(a, o)| (a - o).abs() > 1e-4), "the effects change the signal");
    assert!(chain.set_param(afx[0]["i"].as_u64().unwrap() as usize, "amount", 90.0));
    assert!(!chain.set_param(99, "amount", 1.0), "unknown effect");
    let mut s2 = orig.clone();
    chain.process(&mut s2, 2, 48000);
    assert!(s2.iter().all(|v| v.is_finite()));
    // mono
    let mut m = vec![0.1f32; 480];
    chain.process(&mut m, 1, 44100);
    assert!(m.iter().all(|v| v.is_finite()));

    // the same chain built from the snapshot's JSON (as Kotlin does) gives the same audio as from the session
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let mut from_json = audio::FxChain::from_json(&snap["sequence"]["audio"][0]["clips"][0]["afx"]).expect("chain from JSON");
    let mut from_sess = b.fx_chain(ac).unwrap();
    let (mut x1, mut x2) = (orig.clone(), orig.clone());
    from_json.process(&mut x1, 2, 48000);
    from_sess.process(&mut x2, 2, 48000);
    assert!(x1.iter().zip(&x2).all(|(a, b)| (a - b).abs() < 1e-6), "JSON chain = session chain");
    println!("chain latency: {} samples", from_sess.latency_at(48000));
    // curves for the graphical editors (the engine's own DSP)
    let eq = json!({"id": "parametric_eq", "p": {"low_gain": 6.0}});
    let r = audio::curve(&eq, "response", &[50.0, 1000.0, 10000.0]).unwrap();
    assert!(r[0] > 3.0 && r[1].abs() < 1.0 && r[2].abs() < 1.0, "bass raised: {r:?}");
    let comp = json!({"id": "dynamics_rack", "p": {"comp_on": 1.0, "comp_threshold": -20.0, "comp_ratio": 4.0}});
    let t = audio::curve(&comp, "transfer", &[-40.0, -20.0, 0.0]).unwrap();
    assert!((t[0] + 40.0).abs() < 0.5 && (t[1] + 20.0).abs() < 0.5 && (t[2] + 15.0).abs() < 0.5, "4:1 above −20 dB: {t:?}");
    assert!(audio::curve(&json!({"id": "denoise", "p": {}}), "response", &[100.0]).is_some(), "no curve = zeros");
    assert!(audio::curve(&json!({"id": "does_not_exist"}), "response", &[100.0]).is_none());
    // the registry: a freed id does nothing
    let id = audio::chain_new(from_json);
    assert!(audio::chain_get(id).is_some());
    audio::chain_free(id);
    assert!(audio::chain_get(id).is_none());

    // a disabled effect isn't included in the chain, but shows in the snapshot
    ok(&mut b, "effects.toggleEnabled", json!({"clip": ac, "index": afx[0]["i"]}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["sequence"]["audio"][0]["clips"][0]["afx"][0]["on"], false);

    // split: both halves keep the effects
    ok(&mut b, "timeline.razor", json!({"time": 3 * tps}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    for c in snap["sequence"]["audio"][0]["clips"].as_array().unwrap() {
        assert_eq!(c["afx"].as_array().map(|a| a.len()), Some(2), "{c}");
    }
    ok(&mut b, "edit.undo", json!({}));

    // volume automation on A1
    ok(&mut b, "mixer.setKeyframe", json!({"strip": "A1", "lane": "volume", "time": 2 * tps, "value": -6.0}));
    ok(&mut b, "mixer.setKeyframe", json!({"strip": "A1", "lane": "volume", "time": 4 * tps, "value": 0.0}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let kf = snap["sequence"]["audio"][0]["volKf"].as_array().unwrap().clone();
    assert_eq!(kf.len(), 2);
    assert!((kf[0][0].as_f64().unwrap() - 2.0).abs() < 1e-6 && kf[0][1] == -6.0);
    assert_eq!(snap["sequence"]["audio"][0]["autoMode"], "Read");

    // voice-over: audio only on A2 at 1 s (no picture is added)
    let v_before = snap["sequence"]["video"][0]["clips"].as_array().unwrap().len();
    ok(&mut b, "timeline.place", json!({"item": item, "track": "A2", "seconds": 1.0, "insert": false, "video": false}));
    let s2: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(s2["sequence"]["video"][0]["clips"].as_array().unwrap().len(), v_before, "no picture");
    let a2 = s2["sequence"]["audio"][1]["clips"].as_array().unwrap();
    assert_eq!(a2.len(), 1);
    assert!((a2[0]["start"].as_f64().unwrap() - 1.0).abs() < 1e-6);
    ok(&mut b, "edit.undo", json!({}));

    // loudness of the mix (the engine's WAV export)
    let wav = std::env::temp_dir().join(format!("klipp-loud-{}.wav", std::process::id()));
    ok(&mut b, "file.exportMedia", json!({"path": wav.to_string_lossy(), "format": "wav", "wait": true}));
    let (lufs, tp, _lra) = audio::measure_wav(&wav.to_string_lossy()).unwrap();
    println!("mix: {lufs:.1} LUFS, peak {tp:.1} dBTP");
    assert!(lufs > -60.0 && lufs < 0.0, "{lufs}");
    assert!(tp < 1.0);
    std::fs::remove_file(&wav).ok();
    assert!(audio::measure_wav("/does/not/exist.wav").is_err());
}

#[test]
#[ignore]
fn explore_reverse_render() {
    let mut b = Bridge::new();
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let item = snap["media"][0]["id"].as_u64().unwrap();
    ok(&mut b, "file.newSequence", json!({"name": "rev", "width": 1280, "height": 720, "fps": 25.0, "video": 1, "audio": 1}));
    ok(&mut b, "timeline.place", json!({"item": item, "track": "V1", "audioTrack": "A1", "seconds": 0.0, "insert": false, "sourceIn": 2 * tps, "duration": 4 * tps}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let v = snap["sequence"]["video"][0]["clips"][0]["id"].as_u64().unwrap();
    let a = snap["sequence"]["audio"][0]["clips"][0]["id"].as_u64().unwrap();
    ok(&mut b, "clip.speedDuration", json!({"clips": [v, a], "speed": 100, "reverse": true, "ripple": false}));
    let out = std::env::temp_dir().join("klipp-rev.mp4");
    let t = std::time::Instant::now();
    let r = ok(&mut b, "file.exportMedia", json!({"path": out.to_string_lossy(), "format": "h264", "wait": true, "crf": 18}));
    println!("reverse render {} ms: {r}", t.elapsed().as_millis());
    println!("{:?}", std::fs::metadata(&out).map(|m| m.len()));
    println!("{}", b.snapshot());
}

/// Editing round 0.16: undo groups, reverse/freeze frame in the snapshot, nesting, rendering.
/// Paused (0.16 isn't done: reverse doesn't show in the snapshot yet) – run by hand.
#[test]
#[ignore]
fn edit_round_groups_nests_render() {
    let mut b = Bridge::new();
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4"), media("klipp_b.mp4")]}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let items: Vec<u64> = snap["media"].as_array().unwrap().iter().map(|m| m["id"].as_u64().unwrap()).collect();
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    ok(&mut b, "timeline.place", json!({"item": items[0], "track": "V1", "audioTrack": "A1", "seconds": 0.0, "insert": false}));
    ok(&mut b, "timeline.place", json!({"item": items[1], "track": "V1", "audioTrack": "A1", "seconds": 10.0, "insert": false}));
    let undo0 = b.session.history.undo.len();

    // group: three splits = one undo step
    ok(&mut b, "klipp.beginGroup", json!({}));
    for t in [2, 4, 6] {
        ok(&mut b, "timeline.razor", json!({"time": t * tps}));
    }
    let r = ok(&mut b, "klipp.endGroup", json!({"label": "Hastighetsramp"}));
    assert_eq!(r["merged"], 3);
    assert_eq!(b.session.history.undo.len(), undo0 + 1);
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["sequence"]["video"][0]["clips"].as_array().unwrap().len(), 5);
    ok(&mut b, "edit.undo", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["sequence"]["video"][0]["clips"].as_array().unwrap().len(), 2, "one undo removes the whole group");
    let r = ok(&mut b, "klipp.endGroup", json!({}));
    assert_eq!(r["merged"], 0, "a group without a start does nothing");

    // reverse and freeze frame show in the snapshot
    let v1: Vec<u64> = snap["sequence"]["video"][0]["clips"].as_array().unwrap().iter().map(|c| c["id"].as_u64().unwrap()).collect();
    ok(&mut b, "clip.speedDuration", json!({"clips": [v1[1]], "speed": 100, "reverse": true, "ripple": false}));
    ok(&mut b, "clip.frameHold", json!({"clips": [v1[0]], "time": 3 * tps}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let c0 = &snap["sequence"]["video"][0]["clips"][0];
    let c1 = &snap["sequence"]["video"][0]["clips"][1];
    assert_eq!(c1["reverse"], true);
    assert!((c0["hold"].as_f64().unwrap() - 3.0).abs() < 1e-6, "{c0}");
    assert!(c1["hold"].is_null());

    // nest the first clip: the nest sequence is included in the snapshot
    ok(&mut b, "timeline.select", json!({"clips": [v1[0]], "add": false, "toggle": false}));
    let n = ok(&mut b, "clip.nest", json!({"name": "Nästlad"}));
    let seq_id = n["sequence"].as_u64().unwrap();
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let nest = &snap["sequence"]["nests"][seq_id.to_string()];
    assert!(nest.is_object(), "nests: {}", snap["sequence"]["nests"]);
    assert_eq!(nest["video"][0]["clips"][0]["item"], items[0]);
    assert!((nest["video"][0]["clips"][0]["hold"].as_f64().unwrap() - 3.0).abs() < 1e-6, "the freeze frame is carried into the nest");

    // rendering (separate session): reversed picture and audio
    let out = std::env::temp_dir().join(format!("klipp-r-{}.mp4", std::process::id()));
    let r = render::render_clip(&media("klipp_a.mp4"), 1.0, 1.0, true, None, true, 320, 180, 25.0, &out.to_string_lossy()).unwrap();
    assert_eq!(r["result"]["frames"], 25, "{r}");
    let wav = std::env::temp_dir().join(format!("klipp-r-{}.wav", std::process::id()));
    render::render_clip(&media("klipp_a.mp4"), 1.0, 1.0, true, None, false, 320, 180, 25.0, &wav.to_string_lossy()).unwrap();
    assert!(std::fs::metadata(&wav).unwrap().len() > 40_000);
    let hold = render::render_clip(&media("klipp_a.mp4"), 1.0, 2.0, false, Some(1.5), true, 320, 180, 25.0, &out.to_string_lossy()).unwrap();
    assert_eq!(hold["result"]["frames"], 50);
    assert!(render::render_clip("/does/not/exist.mp4", 0.0, 1.0, true, None, true, 320, 180, 25.0, &out.to_string_lossy()).is_err());
    std::fs::remove_file(&out).ok();
    std::fs::remove_file(&wav).ok();
}

/// Delete the video clip in a linked pair (edit.clear and edit.rippleDelete): what happens to the audio, and can it be undone?
#[test]
fn delete_linked_clip_and_undo() {
    for cmd in ["edit.clear", "edit.rippleDelete"] {
        let mut b = Bridge::new();
        ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
        ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
        let tps = filmcraft_engine::time::TICKS_PER_SECOND;
        ok(&mut b, "project.setMarks", json!({"item": 1, "in": tps, "out": 5 * tps - tps / 25}));
        ok(&mut b, "source.open", json!({"item": 1}));
        ok(&mut b, "source.insert", json!({}));
        let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
        let v = snap["sequence"]["video"][0]["clips"][0]["id"].as_u64().unwrap();
        println!("selection before: {:?}", b.session.state.selection);
        let r: Value = serde_json::from_str(&b.execute("timeline.select", &json!({"clips": []}).to_string())).unwrap();
        println!("deselect -> {r} {:?}", b.session.state.selection);
        let undo_before = b.session.history.undo.len();
        let r: Value = serde_json::from_str(&b.execute(cmd, &json!({"clips": [v]}).to_string())).unwrap();
        println!("{cmd} -> {r}");
        let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
        println!("{cmd}: video {} audio {} canUndo {} undo {}→{}", snap["sequence"]["video"][0]["clips"], snap["sequence"]["audio"][0]["clips"].as_array().map(|a| a.len()).unwrap_or(0), snap["canUndo"], undo_before, b.session.history.undo.len());
        ok(&mut b, "edit.undo", json!({}));
        let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
        println!("{cmd} undone: video {}", snap["sequence"]["video"][0]["clips"].as_array().map(|a| a.len()).unwrap_or(0));
    }
}

/// The Trim tool: slip and roll – signs and linked partners. Track flags in the snapshot.
#[test]
fn slip_roll_and_track_flags() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4"), media("klipp_b.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    for (item, at) in [(1u64, 0.0f64), (2, 2.0)] {
        ok(&mut b, "source.open", json!({"item": item}));
        ok(&mut b, "project.setMarks", json!({"item": item, "in": tps, "out": 3 * tps - tps / 25}));
        ok(&mut b, "playhead.set", json!({"seconds": at}));
        ok(&mut b, "source.overwrite", json!({}));
    }
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let v = snap["sequence"]["video"][0]["clips"].as_array().unwrap().clone();
    assert_eq!(v.len(), 2, "{v:?}");
    let (l, r) = (v[0]["id"].as_u64().unwrap(), v[1]["id"].as_u64().unwrap());
    let a_in = |s: &Value, i: usize| s["sequence"]["audio"][0]["clips"][i]["sourceIn"].as_f64().unwrap();
    // slip +0.5 s: the source in increases 0.5 s, position unchanged; the audio follows
    ok(&mut b, "timeline.slip", json!({"clip": l, "delta": tps / 2}));
    let s2: Value = serde_json::from_str(&b.snapshot()).unwrap();
    println!("slip: {}", s2["sequence"]["video"][0]["clips"][0]);
    assert!((s2["sequence"]["video"][0]["clips"][0]["sourceIn"].as_f64().unwrap() - 1.5).abs() < 0.01);
    assert!((s2["sequence"]["video"][0]["clips"][0]["start"].as_f64().unwrap()).abs() < 0.01);
    assert!((a_in(&s2, 0) - 1.5).abs() < 0.01, "the audio slipped along: {}", s2["sequence"]["audio"][0]);
    // roll +0.4 s: the edit point moves right
    ok(&mut b, "timeline.roll", json!({"left": l, "right": r, "delta": tps * 2 / 5}));
    let s3: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let c = &s3["sequence"]["video"][0]["clips"];
    println!("roll: {} | {}", c[0], c[1]);
    assert!((c[0]["duration"].as_f64().unwrap() - 2.4).abs() < 0.01);
    assert!((c[1]["start"].as_f64().unwrap() - 2.4).abs() < 0.01);
    assert!((c[1]["sourceIn"].as_f64().unwrap() - 1.4).abs() < 0.01);
    // track flags
    ok(&mut b, "timeline.setTrack", json!({"track": "V1", "enabled": false}));
    ok(&mut b, "timeline.setTrack", json!({"track": "A1", "locked": true}));
    let s4: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(s4["sequence"]["video"][0]["enabled"], false);
    assert_eq!(s4["sequence"]["audio"][0]["locked"], true);
    // clip off/on (Shift+E in Premiere): only that clip (and linked audio), undo turns it back on
    assert_eq!(s4["sequence"]["video"][0]["clips"][0]["enabled"], true);
    ok(&mut b, "clip.enable", json!({"clips": [r]}));
    let s5: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let vc = &s5["sequence"]["video"][0]["clips"];
    assert_eq!(vc[0]["enabled"], true);
    assert_eq!(vc[1]["enabled"], false);
    ok(&mut b, "edit.undo", json!({}));
    let s6: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(s6["sequence"]["video"][0]["clips"][1]["enabled"], true);
    // locked track: slip the picture (linked to locked audio) – what happens?
    let r5: Value = serde_json::from_str(&b.execute("timeline.slip", &json!({"clip": l, "delta": tps / 5}).to_string())).unwrap();
    println!("slip with locked partner: {r5}");
    ok(&mut b, "edit.undo", json!({}));
}

/// The Media panel: folders, move to folder, rename, delete (including media in use – the clips first).
#[test]
fn bins_move_rename_delete() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4"), media("klipp_b.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let r = ok(&mut b, "file.newBin", json!({"name": "B-roll"}));
    let bin = r["bin"].as_u64().unwrap();
    ok(&mut b, "project.moveToBin", json!({"items": [2], "bin": bin}));
    ok(&mut b, "clip.rename", json!({"item": 1, "name": "Intervju"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    println!("bins {} media {}", snap["bins"], snap["media"]);
    assert_eq!(snap["bins"][0]["name"], "B-roll");
    let m: Vec<Value> = snap["media"].as_array().unwrap().clone();
    assert!(m.iter().any(|x| x["id"] == 2 && x["bin"] == bin));
    assert!(m.iter().any(|x| x["id"] == 1 && x["name"] == "Intervju" && x["bin"].is_null()));
    // rename the folder
    ok(&mut b, "klipp.renameBin", json!({"bin": bin, "name": "Miljöer"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["bins"][0]["name"], "Miljöer");
    ok(&mut b, "edit.undo", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["bins"][0]["name"], "B-roll", "can be undone");
    // back to the top level
    ok(&mut b, "project.moveToBin", json!({"items": [2], "bin": null}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!(snap["media"].as_array().unwrap().iter().any(|x| x["id"] == 2 && x["bin"].is_null()));
    // media in use: the clips go first, then the media – one undo step
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "source.insert", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let clips: Vec<u64> = (snap["sequence"]["video"][0]["clips"].as_array().unwrap().iter().chain(snap["sequence"]["audio"][0]["clips"].as_array().unwrap()))
        .filter(|c| c["item"] == 1).map(|c| c["id"].as_u64().unwrap()).collect();
    assert!(!clips.is_empty());
    ok(&mut b, "klipp.beginGroup", json!({}));
    ok(&mut b, "edit.clear", json!({"clips": clips}));
    let r = ok(&mut b, "project.delete", json!({"items": [1]}));
    ok(&mut b, "klipp.endGroup", json!({"label": "Ta bort media"}));
    println!("delete -> {r}");
    assert_eq!(r["items"], 1, "gone, not offline");
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!(snap["media"].as_array().unwrap().iter().all(|x| x["id"] != 1));
    ok(&mut b, "edit.undo", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!(snap["media"].as_array().unwrap().iter().any(|x| x["id"] == 1), "undo brings the media back");
    assert!(!snap["sequence"]["video"][0]["clips"].as_array().unwrap().is_empty(), "… and the clips");
    // delete a folder: its contents go with it
    let r = ok(&mut b, "file.newBin", json!({"name": "Tom"}));
    let bin2 = r["bin"].as_u64().unwrap();
    ok(&mut b, "project.moveToBin", json!({"items": [2], "bin": bin2}));
    let r = ok(&mut b, "project.delete", json!({"items": [bin2]}));
    println!("folder deleted -> {r}");
}

/// Without test media: undo groups (nested), the crash flag and the WAV reader's checks.
#[test]
fn groups_taint_and_wav_checks() {
    let mut b = Bridge::new();
    ok(&mut b, "file.newSequence", json!({"width": 640, "height": 360, "fps": 25.0}));
    let undo0 = b.session.history.undo.len();
    ok(&mut b, "klipp.beginGroup", json!({}));
    ok(&mut b, "file.newBin", json!({"name": "a"}));
    ok(&mut b, "klipp.beginGroup", json!({}));
    ok(&mut b, "file.newBin", json!({"name": "b"}));
    assert_eq!(ok(&mut b, "klipp.endGroup", json!({}))["merged"], 0, "the inner group doesn't merge");
    ok(&mut b, "file.newBin", json!({"name": "c"}));
    let merged = ok(&mut b, "klipp.endGroup", json!({"label": "Mappar"}))["merged"].as_u64().unwrap() as usize;
    assert_eq!(merged, 3, "three folders in the outer group");
    assert_eq!(b.session.history.undo.len(), undo0 + 1);
    assert_eq!(ok(&mut b, "klipp.endGroup", json!({}))["merged"], 0, "a group without a start does nothing");

    // after a caught crash, everything except new/open project is refused
    b.tainted = true;
    let r: Value = serde_json::from_str(&b.execute("file.newBin", r#"{"name":"x"}"#)).unwrap();
    assert_eq!(r["ok"], false);
    let r: Value = serde_json::from_str(&b.execute("file.newProject", "{}")).unwrap();
    if r["ok"] == true {
        assert!(!b.tainted, "a new project resets the crash flag");
    }

    let wav = |chans: u16, rate: u32, fmt_len: u32| {
        let mut v = b"RIFF\0\0\0\0WAVE".to_vec();
        v.extend_from_slice(b"fmt ");
        v.extend_from_slice(&fmt_len.to_le_bytes());
        let mut f = Vec::new();
        f.extend_from_slice(&1u16.to_le_bytes());
        f.extend_from_slice(&chans.to_le_bytes());
        f.extend_from_slice(&rate.to_le_bytes());
        f.extend_from_slice(&(rate * chans as u32 * 2).to_le_bytes());
        f.extend_from_slice(&(chans * 2).to_le_bytes());
        f.extend_from_slice(&16u16.to_le_bytes());
        f.truncate(fmt_len as usize);
        v.extend_from_slice(&f);
        v.extend_from_slice(b"data");
        v.extend_from_slice(&8u32.to_le_bytes());
        v.extend_from_slice(&[0u8; 8]);
        v
    };
    let dir = std::env::temp_dir();
    let check = |name: &str, bytes: Vec<u8>| {
        let p = dir.join(format!("klipp-wav-{}-{name}.wav", std::process::id()));
        std::fs::write(&p, bytes).unwrap();
        let r = audio::measure_wav(&p.to_string_lossy());
        std::fs::remove_file(&p).ok();
        r
    };
    assert!(check("ok", wav(2, 48_000, 16)).is_ok());
    assert!(check("short", wav(2, 48_000, 8)).is_err(), "fmt too short");
    assert!(check("zero", wav(0, 48_000, 16)).is_err(), "0 channels");
    assert!(check("nine", wav(9, 48_000, 16)).is_err(), "too many channels");
    assert!(check("hz", wav(2, 0, 16)).is_err(), "0 Hz");
}

/// Full undo history (200 steps): a group is still merged into one step and the limit applies again afterwards.
#[test]
fn group_merges_when_history_is_full() {
    let mut b = Bridge::new();
    ok(&mut b, "file.newSequence", json!({"width": 640, "height": 360, "fps": 25.0}));
    let limit = b.session.history.limit;
    for i in 0..limit + 5 {
        ok(&mut b, "file.newBin", json!({"name": format!("f{i}")}));
    }
    assert_eq!(b.session.history.undo.len(), limit);
    ok(&mut b, "klipp.beginGroup", json!({}));
    for i in 0..30 {
        ok(&mut b, "file.newBin", json!({"name": format!("g{i}")}));
    }
    let merged = ok(&mut b, "klipp.endGroup", json!({"label": "Grupp"}))["merged"].as_u64().unwrap();
    assert_eq!(merged, 30, "all steps in the group are counted");
    assert_eq!(b.session.history.undo.len(), limit, "the limit applies again");
    assert_eq!(b.session.history.limit, limit);
    assert_eq!(b.session.history.undo.last().unwrap().0, "Grupp");
    // one undo removes all 30 folders at once
    let before = b.session.project.root.children.len();
    ok(&mut b, "edit.undo", json!({}));
    assert_eq!(before - b.session.project.root.children.len(), 30);
}

fn step_ids(b: &Bridge) -> (Vec<u64>, u64, u64) {
    let s: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let ids = s["undoIds"].as_array().unwrap().iter().map(|v| v.as_u64().unwrap()).collect();
    (ids, s["redoCount"].as_u64().unwrap(), s["editSerial"].as_u64().unwrap())
}

/// Undo step ids (for Klipp's color steps between the engine's): undo/redo keep them, new steps get new and larger ones,
/// the oldest dropping off and merged groups don't disturb the order, and `klipp.clearRedo` clears redo.
#[test]
fn undo_step_ids_follow_the_history() {
    let mut b = Bridge::new();
    ok(&mut b, "file.newSequence", json!({"width": 640, "height": 360, "fps": 25.0}));
    ok(&mut b, "file.newBin", json!({"name": "a"}));
    ok(&mut b, "file.newBin", json!({"name": "b"}));
    let (ids, redo, serial) = step_ids(&b);
    assert_eq!(ids.len(), b.session.history.undo.len());
    assert!(ids.windows(2).all(|w| w[0] < w[1]), "ascending: {ids:?}");
    assert_eq!(redo, 0);
    // undo: the top id moves to redo; no new edit
    ok(&mut b, "edit.undo", json!({}));
    let (ids2, redo2, serial2) = step_ids(&b);
    assert_eq!(ids2[..], ids[..ids.len() - 1]);
    assert_eq!((redo2, serial2), (1, serial));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["redoIds"][0].as_u64().unwrap(), *ids.last().unwrap(), "the redo step has the undone id");
    // redo: the same id back
    ok(&mut b, "edit.redo", json!({}));
    assert_eq!(step_ids(&b), (ids.clone(), 0, serial));
    // undo + new edit: new (larger) id, the counter increments
    ok(&mut b, "edit.undo", json!({}));
    ok(&mut b, "file.newBin", json!({"name": "c"}));
    let (ids3, redo3, serial3) = step_ids(&b);
    assert_eq!(ids3.len(), ids.len());
    assert!(*ids3.last().unwrap() > *ids.last().unwrap());
    assert_eq!(redo3, 0);
    assert!(serial3 > serial);
    // a group becomes one step with one id; the steps before are not disturbed
    ok(&mut b, "klipp.beginGroup", json!({}));
    ok(&mut b, "file.newBin", json!({"name": "d"}));
    ok(&mut b, "file.newBin", json!({"name": "e"}));
    ok(&mut b, "klipp.endGroup", json!({"label": "G"}));
    let (ids4, _, _) = step_ids(&b);
    assert_eq!(ids4.len(), ids3.len() + 1);
    assert_eq!(ids4[..ids3.len()], ids3[..]);
    // a read changes nothing
    ok(&mut b, "playhead.set", json!({"seconds": 0.0}));
    assert_eq!(step_ids(&b).0, ids4);
    // redo is cleared by klipp.clearRedo
    ok(&mut b, "edit.undo", json!({}));
    assert_eq!(step_ids(&b).1, 1);
    ok(&mut b, "klipp.clearRedo", json!({}));
    assert_eq!(step_ids(&b).1, 0);
    assert!(!b.session.history.can_redo());
    // full history: the oldest drops off, the rest keep their ids
    let limit = b.session.history.limit;
    for i in 0..limit + 3 {
        ok(&mut b, "file.newBin", json!({"name": format!("f{i}")}));
    }
    let (full, _, _) = step_ids(&b);
    assert_eq!(full.len(), limit);
    ok(&mut b, "file.newBin", json!({"name": "sist"}));
    let (full2, _, _) = step_ids(&b);
    assert_eq!(full2.len(), limit);
    assert_eq!(full2[..limit - 1], full[1..]);
    assert!(full2.windows(2).all(|w| w[0] < w[1]));
}

/// Freeze frame (Insert Frame Hold Segment) and reverse through the bridge: the fields in the snapshot and that the rest moves along.
#[test]
fn frame_hold_segment_and_reverse() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": 1, "in": tps, "out": 5 * tps}));
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "source.insert", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let c = snap["sequence"]["video"][0]["clips"][0].clone();
    let id = c["id"].as_u64().unwrap();
    let total0 = snap["sequence"]["duration"].as_f64().unwrap();
    // freeze frame 2 s at 1.5 s in the timeline (source 2.5 s)
    ok(&mut b, "clip.insertFrameHoldSegment", json!({"clip": id, "time": 3 * tps / 2, "seconds": 2.0}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let v = snap["sequence"]["video"][0]["clips"].as_array().unwrap().clone();
    println!("after freeze frame -> {:?}", v.iter().map(|c| (c["start"].clone(), c["duration"].clone(), c["hold"].clone())).collect::<Vec<_>>());
    assert_eq!(v.len(), 3, "the clip split with the freeze frame in between");
    let hold = &v[1];
    assert!((hold["start"].as_f64().unwrap() - 1.5).abs() < 0.021);
    assert!((hold["duration"].as_f64().unwrap() - 2.0).abs() < 0.021);
    assert!((hold["hold"].as_f64().unwrap() - 2.5).abs() < 0.041, "the source time at the playhead");
    assert!(v[0]["hold"].is_null() && v[2]["hold"].is_null());
    assert!((v[2]["start"].as_f64().unwrap() - 3.5).abs() < 0.021, "the rest moved 2 s");
    assert!((snap["sequence"]["duration"].as_f64().unwrap() - (total0 + 2.0)).abs() < 0.05);
    // reverse on the last piece
    let last = v[2]["id"].as_u64().unwrap();
    ok(&mut b, "clip.speedDuration", json!({"clips": [last], "speed": 100, "reverse": true}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let r = snap["sequence"]["video"][0]["clips"].as_array().unwrap().iter().find(|c| c["id"].as_u64() == Some(last)).cloned().unwrap();
    assert_eq!(r["reverse"], true);
    assert!((r["speed"].as_f64().unwrap() - 1.0).abs() < 1e-9);
    // undo both
    ok(&mut b, "edit.undo", json!({}));
    ok(&mut b, "edit.undo", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(snap["sequence"]["video"][0]["clips"].as_array().unwrap().len(), 1);
}

/// The app's transitions (TransitionType in Transitions.kt) must exist in the engine – otherwise a transition disappears on change.
#[test]
fn app_transition_effects_exist() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": 1, "in": tps, "out": 7 * tps}));
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "source.insert", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let id = snap["sequence"]["video"][0]["clips"][0]["id"].as_u64().unwrap();
    ok(&mut b, "timeline.razor", json!({"time": 3 * tps, "clip": id}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let second = snap["sequence"]["video"][0]["clips"][1]["id"].as_u64().unwrap();
    for effect in ["cross_dissolve", "dip_to_black", "dip_to_white", "wipe", "push", "slide", "cross_zoom", "iris_round"] {
        ok(&mut b, "sequence.applyVideoTransition", json!({"clip": second, "effect": effect, "frames": 25, "edge": "in", "reverse": true}));
        let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
        let tr = snap["sequence"]["video"][0]["transitions"].as_array().unwrap();
        assert_eq!(tr.len(), 1, "{effect}");
        assert_eq!(tr[0]["effect"], effect);
        assert_eq!(tr[0]["reverse"], true, "{effect}: reverse is carried along");
        ok(&mut b, "sequence.removeTransition", json!({"transitions": [tr[0]["id"].clone()]}));
    }
    // an unknown effect is an error (the app then puts the old one back)
    let r: Value = serde_json::from_str(&b.execute("sequence.applyVideoTransition", &json!({"clip": second, "effect": "zoom", "frames": 25, "edge": "in"}).to_string())).unwrap();
    assert_eq!(r["ok"], false);
}

/// Compound clips: the engine's group shows in the snapshot, can be ungrouped and undone.
#[test]
fn clip_groups_in_snapshot() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": 1, "in": tps, "out": 7 * tps}));
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "source.insert", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let id = snap["sequence"]["video"][0]["clips"][0]["id"].as_u64().unwrap();
    ok(&mut b, "timeline.razor", json!({"time": 3 * tps, "clip": id}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let v: Vec<u64> = snap["sequence"]["video"][0]["clips"].as_array().unwrap().iter().map(|c| c["id"].as_u64().unwrap()).collect();
    assert!(snap["sequence"]["video"][0]["clips"][0]["group"].is_null());
    ok(&mut b, "klipp.beginGroup", json!({}));
    ok(&mut b, "timeline.select", json!({"clips": v, "linked": true}));
    ok(&mut b, "clip.group", json!({}));
    ok(&mut b, "klipp.endGroup", json!({"label": "Gruppera"}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let groups: Vec<Value> = snap["sequence"]["video"][0]["clips"].as_array().unwrap().iter().map(|c| c["group"].clone()).collect();
    assert!(groups[0].is_u64() && groups[0] == groups[1], "both video clips in the same group: {groups:?}");
    // linked audio was selected too and belongs to the same group
    assert_eq!(snap["sequence"]["audio"][0]["clips"][0]["group"], groups[0]);
    // one undo step
    ok(&mut b, "edit.undo", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!(snap["sequence"]["video"][0]["clips"].as_array().unwrap().iter().all(|c| c["group"].is_null()));
    ok(&mut b, "edit.redo", json!({}));
    ok(&mut b, "timeline.select", json!({"clips": [v[0]], "linked": true}));
    ok(&mut b, "clip.ungroup", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!(snap["sequence"]["video"][0]["clips"][0]["group"].is_null());
    assert!(snap["sequence"]["video"][0]["clips"][1]["group"].is_u64(), "only the selected clip leaves the group");
}

/// Auto ducking: the music (A2) is lowered where the dialogue (A1) is heard, as volume keyframes on the music clip – one undo step.
#[test]
fn auto_ducking_lowers_music_under_dialogue() {
    let mut b = Bridge::new();
    // music: the test clip's tone throughout; dialogue: 2 s audio, 2 s silence (tal.m4a)
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4"), media("tal.m4a")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let items: Vec<(u64, String)> = snap["media"].as_array().unwrap().iter().map(|m| (m["id"].as_u64().unwrap(), m["name"].as_str().unwrap_or("").to_string())).collect();
    let music = items.iter().find(|(_, n)| n.contains("klipp_a")).unwrap().0;
    let talk = items.iter().find(|(_, n)| n.contains("tal")).unwrap().0;
    ok(&mut b, "timeline.place", json!({"item": talk, "track": "A1", "seconds": 0.0, "insert": false, "video": false}));
    ok(&mut b, "timeline.place", json!({"item": music, "track": "A2", "seconds": 0.0, "insert": false, "video": false}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let m = snap["sequence"]["audio"][1]["clips"][0]["id"].as_u64().unwrap();
    let undo0 = snap["undoIds"].as_array().map(|a| a.len()).unwrap_or(0);
    // as the app does it: the music is typed, ducked against untyped audio (the dialogue), in a group
    ok(&mut b, "klipp.beginGroup", json!({}));
    ok(&mut b, "essentialSound.setType", json!({"clips": [m], "type": "music"}));
    ok(&mut b, "essentialSound.set", json!({"clips": [m], "values": {"ducking.against_untyped": true, "ducking.reduce_db": -12.0, "ducking.fade_s": 0.3}}));
    let r = ok(&mut b, "essentialSound.generateDucking", json!({"clips": [m]}));
    ok(&mut b, "klipp.endGroup", json!({"label": "Ducka"}));
    println!("ducking -> {r}");
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let c = &snap["sequence"]["audio"][1]["clips"][0];
    let kf = c["kf"]["level"].as_array().expect("volume keyframes on the music").clone();
    assert!(kf.len() >= 4, "{kf:?}");
    // the level at a point in time (linear between the keyframes)
    let at = |t: f64| -> f64 {
        let pts: Vec<(f64, f64)> = kf.iter().map(|k| (k["t"].as_f64().unwrap(), k["v"][0].as_f64().unwrap())).collect();
        if t <= pts[0].0 { return pts[0].1; }
        for w in pts.windows(2) { if t <= w[1].0 { let f = (t - w[0].0) / (w[1].0 - w[0].0).max(1e-9); return w[0].1 + f * (w[1].1 - w[0].1); } }
        pts.last().unwrap().1
    };
    // under the dialogue (1 s, 5 s) lowered, in the pauses (3 s, 7 s) full level
    assert!(at(1.0) < -10.0, "under the dialogue: {}", at(1.0));
    assert!(at(5.0) < -10.0, "under the dialogue: {}", at(5.0));
    assert!(at(3.0) > -1.0, "in the pause: {}", at(3.0));
    assert!(at(7.0) > -1.0, "in the pause: {}", at(7.0));
    // one undo step removes everything
    let undo1 = snap["undoIds"].as_array().map(|a| a.len()).unwrap_or(0);
    assert_eq!(undo1, undo0 + 1);
    ok(&mut b, "edit.undo", json!({}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert!(snap["sequence"]["audio"][1]["clips"][0]["kf"]["level"].is_null());
}

/// Text-based editing as the app does it: a word transcript set on a media item (media ticks), the sequence words
/// listed with timeline ticks, words extracted (all tracks close the gap), pauses and filler words removed.
#[test]
fn transcript_set_inspect_extract() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let item = snap["media"][0]["id"].as_u64().unwrap();
    let m0 = &snap["media"][0];
    ok(&mut b, "file.newSequence", json!({"width": m0["width"], "height": m0["height"], "fps": m0["fps"]}));
    ok(&mut b, "source.open", json!({"item": item}));
    ok(&mut b, "playhead.set", json!({"seconds": 0.0}));
    ok(&mut b, "source.insert", json!({}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND as f64;
    let t = |s: f64| (s * tps).round() as i64;
    // "hej eh då ... sen" with a 2.5 s pause before "sen"
    let words = json!([
        {"text": "Hej", "start": t(0.5), "end": t(0.9)},
        {"text": "eh", "start": t(1.0), "end": t(1.3)},
        {"text": "då,", "start": t(1.4), "end": t(1.8)},
        {"text": "sen", "start": t(4.3), "end": t(4.7)},
        {"text": "slut.", "start": t(4.8), "end": t(5.3)},
    ]);
    ok(&mut b, "transcript.set", json!({"item": item, "transcript": {"language": "sv", "source": "whisper", "speakers": [], "words": words}}));
    let ins = ok(&mut b, "transcript.inspect", json!({}));
    let w = ins["words"].as_array().unwrap();
    assert_eq!(w.len(), 5, "{ins}");
    assert_eq!(w[2]["text"], "då,");
    assert!((w[3]["start"].as_i64().unwrap() as f64 / tps - 4.3).abs() < 0.001);
    let dur = |b: &Bridge| { let s: Value = serde_json::from_str(&b.snapshot()).unwrap(); s["sequence"]["duration"].as_f64().unwrap() };
    let d0 = dur(&b);
    // delete "då," (index 2): the video and its sound get shorter by about the word
    ok(&mut b, "transcript.extract", json!({"from": 2, "to": 2}));
    let d1 = dur(&b);
    assert!(d0 - d1 > 0.3 && d0 - d1 < 0.6, "{d0} → {d1}");
    let s: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let vlen: f64 = s["sequence"]["video"][0]["clips"].as_array().unwrap().iter().map(|c| c["duration"].as_f64().unwrap()).sum();
    let alen: f64 = s["sequence"]["audio"][0]["clips"].as_array().unwrap().iter().map(|c| c["duration"].as_f64().unwrap()).sum();
    assert!((vlen - alen).abs() < 0.05, "video and sound stay in sync: {vlen} vs {alen}");
    // filler words ("eh") and the long pause
    let r = ok(&mut b, "transcript.removeFillers", json!({"fillers": ["eh", "öh"]}));
    assert_eq!(r["removed"], 1, "{r}");
    let r = ok(&mut b, "transcript.removePauses", json!({"minSeconds": 0.8, "keepSeconds": 0.15}));
    assert!(r["removed"].as_u64().unwrap() >= 1, "{r}");
    let ins = ok(&mut b, "transcript.inspect", json!({}));
    let texts: Vec<&str> = ins["words"].as_array().unwrap().iter().map(|w| w["text"].as_str().unwrap()).collect();
    assert_eq!(texts, vec!["Hej", "sen", "slut."]);
    // undo brings the words back
    ok(&mut b, "edit.undo", json!({}));
    ok(&mut b, "edit.undo", json!({}));
    ok(&mut b, "edit.undo", json!({}));
    let ins = ok(&mut b, "transcript.inspect", json!({}));
    assert_eq!(ins["words"].as_array().unwrap().len(), 5);
}

/// Keyframes on a reversed clip are shown where they were set (timeline time from the reversed media time), and on a
/// freeze frame the app's move to the moving source time keeps them apart (the engine alone puts them all on the
/// frozen frame).
#[test]
fn keyframe_times_on_reverse_and_freeze_frame() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "project.setMarks", json!({"item": 1, "in": tps, "out": 5 * tps}));
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "source.insert", json!({}));
    let clip = |b: &Bridge, i: usize| { let s: Value = serde_json::from_str(&b.snapshot()).unwrap(); s["sequence"]["video"][0]["clips"][i].clone() };
    let id = clip(&b, 0)["id"].as_u64().unwrap();
    // reversed: a keyframe set at 1 s is shown at 1 s (it was shown mirrored, near the end)
    ok(&mut b, "clip.speedDuration", json!({"clips": [id], "speed": 100, "reverse": true}));
    ok(&mut b, "effects.setParam", json!({"clip": id, "effect": "motion", "param": "scale", "value": 50.0, "keyframe": true, "time": tps}));
    let t = clip(&b, 0)["kf"]["scale"][0]["t"].as_f64().unwrap();
    assert!((t - 1.0).abs() < 0.001, "reversed clip keyframe at {t}");
    ok(&mut b, "edit.undo", json!({}));
    ok(&mut b, "edit.undo", json!({}));
    // freeze frame 2 s at 1.5 s: the engine puts keyframes at the frozen source time …
    ok(&mut b, "clip.insertFrameHoldSegment", json!({"clip": id, "time": 3 * tps / 2, "seconds": 2.0}));
    let hold = clip(&b, 1);
    let hid = hold["id"].as_u64().unwrap();
    for at in [2.0, 3.0] {
        ok(&mut b, "effects.setParam", json!({"clip": hid, "effect": "motion", "param": "scale", "value": 80.0, "keyframe": true, "time": (at * tps as f64) as i64}));
    }
    assert_eq!(clip(&b, 1)["kf"]["scale"].as_array().unwrap().len(), 1, "the engine collapses them onto the frozen frame");
    // … and moving it to the moving source time (as the app does) puts it where it was set
    let k = clip(&b, 1)["kf"]["scale"][0].clone();
    let h = clip(&b, 1);
    let to = ((h["sourceIn"].as_f64().unwrap() + (3.0 - h["start"].as_f64().unwrap())) * tps as f64).round() as i64;
    ok(&mut b, "effects.moveKeyframe", json!({"clip": hid, "effect": "motion", "param": "scale", "mediaTime": k["mt"], "to": to}));
    let t = clip(&b, 1)["kf"]["scale"][0]["t"].as_f64().unwrap();
    assert!((t - 3.0).abs() < 0.001, "freeze frame keyframe at {t}");
}

#[test]
fn keyframe_ease_and_influence() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    ok(&mut b, "file.newSequence", json!({"width": 1280, "height": 720, "fps": 25.0}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "source.open", json!({"item": 1}));
    ok(&mut b, "source.insert", json!({}));
    let clip = |b: &Bridge| { let s: Value = serde_json::from_str(&b.snapshot()).unwrap(); s["sequence"]["video"][0]["clips"][0].clone() };
    let id = clip(&b)["id"].as_u64().unwrap();
    for (at, v) in [(0, 0.0), (2, 100.0)] {
        ok(&mut b, "effects.setParam", json!({"clip": id, "effect": "opacity", "param": "opacity", "value": v, "keyframe": true, "time": at * tps}));
    }
    let ks = clip(&b)["kf"]["opacity"].clone();
    let (m0, m1) = (ks[0]["mt"].as_i64().unwrap(), ks[1]["mt"].as_i64().unwrap());
    // what the app's easy ease sends: bezier + a third on both sides (as one group), then a softer landing
    ok(&mut b, "effects.setInterpolation", json!({"clip": id, "effect": "opacity", "param": "opacity", "mediaTime": m0, "interpolation": "bezier"}));
    ok(&mut b, "effects.setKeyframe", json!({"clip": id, "effect": "opacity", "param": "opacity", "mediaTime": m0, "outInfluence": 0.6}));
    ok(&mut b, "effects.setKeyframe", json!({"clip": id, "effect": "opacity", "param": "opacity", "mediaTime": m1, "inInfluence": 0.9, "value": 80.0}));
    let ks = clip(&b)["kf"]["opacity"].clone();
    assert_eq!(ks[0]["interp"], "Bezier");
    assert!((ks[0]["outInf"].as_f64().unwrap() - 0.6).abs() < 1e-9);
    assert!((ks[1]["inInf"].as_f64().unwrap() - 0.9).abs() < 1e-9);
    assert!((ks[1]["v"][0].as_f64().unwrap() - 80.0).abs() < 1e-9);
    // the engine's curve at a quarter, half and three quarters – the same numbers are asserted in the app (KeyframeEaseTest)
    use filmcraft_engine::project::{Interpolation, Keyframe, Param, ParamValue};
    let mut p = Param::new(ParamValue::Float(0.0));
    let mut a = Keyframe::new(Tick(0), ParamValue::Float(0.0));
    a.interp = Interpolation::Bezier;
    a.out_influence = 0.6;
    let mut z = Keyframe::new(Tick(1000), ParamValue::Float(80.0));
    z.in_influence = 0.9;
    p.keyframes = vec![a, z];
    let got: Vec<f64> = [250, 500, 750].iter().map(|&t| p.f64_at(Tick(t))).collect();
    let want = [EASE_Q1, EASE_HALF, EASE_Q3];
    for (g, w) in got.iter().zip(want) {
        assert!((g - w).abs() < 1e-6, "engine curve {got:?}");
    }
}

const EASE_Q1: f64 = 8.51905335501151;
const EASE_HALF: f64 = 62.66094097352554;
const EASE_Q3: f64 = 77.29654326012408;

#[test]
fn transcript_of_a_media_item() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    let words = json!([{"text": "Hej", "start": tps, "end": 2 * tps}, {"text": "där.", "start": 2 * tps, "end": 3 * tps}]);
    ok(&mut b, "transcript.set", json!({"item": 1, "transcript": {"language": "sv", "source": "whisper", "speakers": [], "words": words}}));
    let r = ok(&mut b, "klipp.transcriptOf", json!({"item": 1}));
    assert_eq!(r["words"].as_array().unwrap().len(), 2);
    assert_eq!(r["words"][1]["text"], "där.");
    assert_eq!(r["words"][0]["start"].as_i64().unwrap(), tps as i64);
    assert_eq!(r["language"], "sv");
    let s: Value = serde_json::from_str(&b.snapshot()).unwrap();
    assert_eq!(s["media"][0]["words"], 2);
    // no transcript: empty
    ok(&mut b, "transcript.delete", json!({"items": [1]}));
    assert!(ok(&mut b, "klipp.transcriptOf", json!({"item": 1}))["words"].as_array().unwrap().is_empty());
}

/// Color labels: set on clips (one undo step), shown in the snapshot, kept by a split; null = the media item's label.
#[test]
fn clip_labels() {
    let mut b = Bridge::new();
    ok(&mut b, "file.import", json!({"paths": [media("klipp_a.mp4")]}));
    let snap: Value = serde_json::from_str(&b.snapshot()).unwrap();
    let m0 = snap["media"][0].clone();
    let item = m0["id"].as_u64().unwrap();
    ok(&mut b, "file.newSequence", json!({"width": m0["width"], "height": m0["height"], "fps": m0["fps"]}));
    ok(&mut b, "source.open", json!({"item": item}));
    ok(&mut b, "playhead.set", json!({"seconds": 0.0}));
    ok(&mut b, "source.insert", json!({}));
    let clips = |b: &Bridge| -> Vec<Value> {
        let s: Value = serde_json::from_str(&b.snapshot()).unwrap();
        s["sequence"]["video"][0]["clips"].as_array().unwrap().clone()
    };
    let c = clips(&b)[0].clone();
    let own = c["label"].as_str().unwrap().to_string();
    let id = c["id"].as_u64().unwrap();
    ok(&mut b, "klipp.setLabel", json!({"clips": [id], "label": "Yellow"}));
    assert_eq!(clips(&b)[0]["label"], "Yellow");
    // a split keeps the label on both halves
    let tps = filmcraft_engine::time::TICKS_PER_SECOND;
    ok(&mut b, "timeline.razor", json!({"time": 2 * tps, "clip": id}));
    let after = clips(&b);
    assert_eq!(after.len(), 2, "{after:?}");
    assert!(after.iter().all(|c| c["label"] == "Yellow"), "{after:?}");
    ok(&mut b, "edit.undo", json!({}));
    // no label: back to the media item's own
    ok(&mut b, "klipp.setLabel", json!({"clips": [id], "label": null}));
    assert_eq!(clips(&b)[0]["label"], own.as_str());
    ok(&mut b, "edit.undo", json!({}));
    assert_eq!(clips(&b)[0]["label"], "Yellow");
    let r: Value = serde_json::from_str(&b.execute("klipp.setLabel", &json!({"clips": [id], "label": "Nope"}).to_string())).unwrap();
    assert_eq!(r["ok"], false);
}
