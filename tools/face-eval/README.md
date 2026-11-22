# Face following: evaluation and prototype

The prototype of Klipp's face following and the measurement that chose it (2026-11-23).

- `follow.py` – the algorithm without MediaPipe: landmarks → centre/size/roll (`observe`), which face is the chosen
  one (`Follower`: every face a track, greedy matching by position and size, hold and re-acquire), and the smoothing
  (One Euro forwards and backwards). Mirrored line by line in `app/.../engine/FaceFollow.kt`.
- `mp_source.py` – MediaPipe Face Landmarker as the source: a crop that follows the face, VIDEO mode; tiles in IMAGE
  mode for the wide search. Mirrored in `engine/FaceLandmarks.kt`.
- `yunet_base.py` – the face following of Klipp ≤ 0.21 (YuNet on every frame, `rust/src/face.rs`, + `FaceSmooth`).
- `clips.py` – the test clips (Xiph derf `foreman_cif`, `akiyo_cif`) and the synthesized cases (two faces with the chosen
  one covered for 12 frames, a mid and a far face on a 1280×720 canvas).
- `evaluate.py` – runs both, prints the table; `--fixtures` writes `app/src/test/resources/face/*.json` for
  `FaceFollowTest` (the Kotlin follower must make the same choices and the same track).

Run (the clips and the model are not in the repository; `--fetch` downloads the clips):

    uv run --python 3.12 --with mediapipe==1.2.3 --with opencv-python --with numpy \
        python -I tools/face-eval/evaluate.py --clips /tmp/face-clips --fetch \
        --model app/src/main/assets/face_landmarker.task [--yunet640] [--gray] [--fixtures app/src/test/resources/face]

After changing a constant in `follow.py` or `mp_source.py`, change it in the Kotlin files too and write the fixtures
anew.

## Result (Mac CPU; truth = MediaPipe's landmarks on the full source frames, see `evaluate.py`)

| clip | pipeline | lost | wrong | err % | fast % | jit % | sjit % | serr % | ms |
|---|---|---|---|---|---|---|---|---|---|
| akiyo (still) | YuNet (before) | 0 | 0 | 8.0 | 5.6 | 0.61 | 0.05 | 1.5 | 5.4 |
| akiyo (still) | MediaPipe (after) | 0 | 0 | 1.6 | 1.8 | 0.39 | 0.01 | 1.0 | 10.0 |
| foreman (head turns) | YuNet (before) | 1 | 0 | 23.8 | 24.3 | 2.16 | 0.13 | 4.2 | 4.9 |
| foreman (head turns) | MediaPipe (after) | 3 | 0 | 4.6 | 4.9 | 2.95 | 0.09 | 3.1 | 15.3 |
| two faces, chosen one covered | YuNet (before) | 0 | 27 | 23.4 | 24.6 | 41.65 | 2.51 | 23.9 | 5.1 |
| two faces, chosen one covered | MediaPipe (after) | 1 | 0 | 6.5 | 5.2 | 4.22 | 0.12 | 3.2 | 17.9 |
| mid (face ~60 px of 1280) | YuNet (before) | 184 | 181 | 65.4 | 65.4 | 0.51 | 0.01 | 7.1 | 3.0 |
| mid (face ~60 px of 1280) | MediaPipe (after) | 2 | 0 | 6.5 | 6.6 | 3.04 | 0.10 | 3.2 | 10.3 |
| mid, auto reframe (whole picture) | YuNet (before) | 184 | 181 | 65.4 | 65.4 | 0.51 | 0.01 | 7.1 | 2.7 |
| mid, auto reframe (whole picture) | MediaPipe (after) | 2 | 0 | 6.5 | 6.6 | 3.04 | 0.10 | 3.2 | 12.2 |
| far (face ~33 px of 1280) | YuNet (before) | 187 | 187 | – | – | – | – | – | 3.4 |
| far (face ~33 px of 1280) | MediaPipe (after) | 1 | 0 | 8.0 | 8.0 | 3.77 | 0.09 | 3.7 | 9.9 |

lost: frames without the face (of 300 / 187 / 175 visible); wrong: on the other person or more than one face size off;
err/fast: centre error in % of the face size (all frames / fast frames); jit/sjit: frame-to-frame acceleration of
centre and ln(size) (noise); serr: slow size error (pumping). The truth is MediaPipe's, so err favours the landmark
centre; lost, wrong and the synthesized cases do not depend on it. A 640 px YuNet (`--yunet640`) finds the mid face
in most frames (26 lost) but not the far one, and still switches to the other person (27 wrong).

Grey frames work as well as colour (`--gray`), so the app gives MediaPipe the luma plane only.
