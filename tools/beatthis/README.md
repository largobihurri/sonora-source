# Beat This! model asset and golden data (unit D1.18)

Everything the Android beat tracker (D1.8 front end and chunking, D1.21 runtime) needs to be built and tested against,
without any of the user's music: a pinned ONNX model, a Python reference of the whole pipeline, and golden files
generated from synthetic audio.

| File | Purpose |
|---|---|
| `model.json` | Manifest: source URL and revision, sha256, size, licences, ONNX I/O spec. |
| `fetch_model.py` | Downloads `build/small0.onnx` (and with `--refs` the reference files) and verifies sha256. Standard library only. |
| `beatthis_ref.py` | numpy + onnxruntime reference: log-mel, chunking, aggregation, post-processing, F-measure. |
| `synth.py` | Deterministic click-track generator (formula in its docstring, repeated below). |
| `make_goldens.py` | Writes `goldens/` (`--check` verifies the committed files; `--chunk 300 --border 24` the app's chunking). |
| `validate.py` | Accuracy on the synthetic cases, front-end parity, thread determinism, 6-minute timing. |
| `verify_weights.py`, `inspect_model.py` | Weight provenance check; opset/I-O/operator listing. |
| `goldens/` | Committed golden data, about 1.3 MiB, plus `chunk300_border24/` (181 KiB). |
| `build/` | Git-ignored: the downloaded model and reference files. |

```bash
python3 -m venv venv && venv/bin/pip install -r requirements.txt   # or any environment with numpy and onnxruntime
python3 fetch_model.py --refs
OMP_NUM_THREADS=2 nice -n 19 python3 make_goldens.py --check
OMP_NUM_THREADS=2 nice -n 19 python3 make_goldens.py --chunk 300 --border 24 --check
OMP_NUM_THREADS=2 nice -n 19 python3 validate.py --timing
```

## Model

Beat This! `small0` (CPJKU, ISMIR 2024): 2,099,720 parameters, trained on all the paper's datasets except GTZAN.

- File: `small0.onnx`, 10,401,044 bytes, sha256 `c462b064a4033050ca6a5354bf866ff4610ab1fd8f1dec1e494b20b22d870aea`.
- Source: https://huggingface.co/ashudesai/songbird-models at revision `345312bf5604913b3c0d05815f93c81c4b114e4d`
  (a `torch.onnx.export`, TorchScript tracer, of the upstream checkpoint, by the model card).
- ONNX: IR 7, opset `ai.onnx` 14, producer pytorch 2.12.0. One input `spectrogram` float32 `[batch, time, 128]`
  (the model card calls it `input_spectrogram`; that is wrong), outputs `beat` and `downbeat` float32 `[batch, time]`
  (logits, 50 per second). The time axis is dynamic: 1, 2, 5, 20, 500 and 1500 frames all run.
- Operators (CPU kernels in ONNX Runtime): Constant, Unsqueeze, Shape, Gather, Mul, Concat, Cast, Slice, Add, Reshape,
  Div, MatMul, Squeeze, Transpose, Neg, Split, Sqrt, ReduceL2, Clip, Expand, Cos, Sin, Erf, Softmax, Sigmoid, Conv,
  BatchNormalization. `inspect_model.py` prints the full histogram.
- Why this export: the other validated one (`musetric/beat-this-onnx`) is the 20 M parameter `final0`, 120 MB, rewritten
  to a static 513-frame window for WebGPU. The `songbird` export is the only small one found, is 10 MB and keeps the
  dynamic time axis.
- Weight provenance (`verify_weights.py`): the upstream checkpoint `small0.ckpt` (8,451,101 bytes, sha256
  `6074be2c4d490c5f6101fcc374a1ec72ae93456e23bb6019783b849f5dc7d47b`, host `cloud.cp.jku.at`, not content addressed)
  was read without PyTorch; every attention, feed-forward and linear tensor (87.6 % of the elements) is bit-identical in
  the ONNX file. The rest are the four conv kernels and batch-norm statistics (folded at export) and the rotary
  frequency constants. The numerical equivalence of the whole graph to PyTorch is claimed by the model card
  (max abs diff < 2e-5) and not reproduced here (no PyTorch).

## Licences (recorded 2026-10-07)

| Item | Licence | Evidence |
|---|---|---|
| Beat This! code | MIT, Copyright (c) 2024 Institute of Computational Perception, JKU Linz, Austria | https://github.com/CPJKU/beat_this/blob/main/LICENSE |
| Beat This! weights (including `small0..2`) | MIT | README, section License: "The code and the published model weights are released under the MIT license" (https://github.com/CPJKU/beat_this#license). The README lists `small0, small1, small2` among the published models. |
| ONNX conversion (`ashudesai/songbird-models`) | MIT (declared in the model card metadata and text "MIT, same as the original CPJKU/beat_this"); no LICENSE file in the repository | https://huggingface.co/ashudesai/songbird-models |
| Reference files (`musetric/beat-this-onnx` filterbank, `mel_spectrogram.onnx`) | MIT (model cards) | only used by the scripts, not shipped |

Caveat, from the same README section: "some of the training files are fully copyrighted or under limited Creative
Commons licences, and it is up to the user to assess whether this may impact their use case". The weights are
distributed by the authors under MIT and no training audio travels with them; this is the usual position for a
research checkpoint, but it is a residual risk the project owner should accept knowingly. MIT is compatible with GPL-3.
When the model is bundled in the app, add the MIT notice (copyright line and permission text) to the third-party
licences. Nothing in `goldens/` comes from a third party: all of it is generated here from synthetic audio and the model.

## Front end (what D1.8 must reproduce)

From `CPJKU/beat_this` @ `b95c8ab`, `beat_this/preprocessing.py` (`LogMelSpect`) and `inference.py`:

| Parameter | Value |
|---|---|
| Input | mono, 22050 Hz, float32. Channels are averaged (arithmetic mean). Other rates are resampled first (the reference uses `soxr`; Sonora uses its polyphase resampler, so expect small differences). |
| STFT | `n_fft` 1024, `win_length` 1024, `hop` 441, window periodic Hann `0.5 - 0.5 cos(2 pi n / 1024)` |
| Centring | `center=True`, reflect padding of 512 samples on both sides, so `frames = 1 + floor(samples / 441)` |
| Magnitude | `abs(rfft)`, `power=1` (not squared), then times `1 / sqrt(1024)` (torchaudio `normalized="frame_length"`) |
| Mel | 128 bands, 30 to 11000 Hz, Slaney scale (`mel_scale="slaney"`), `norm=None` (peak 1.0, no area normalisation) |
| Compression | `log1p(1000 * x)` |
| Output | `[frames, 128]`, row = frame |

Mel filterbank (`torchaudio.functional.melscale_fbanks(513, 30, 11000, 128, 22050, norm=None, mel_scale="slaney")`):
Slaney mel scale (linear below 1000 Hz, `mel = hz / (200/3)`; above it `15 + ln(hz / 1000) / (ln(6.4) / 27)`);
130 equally spaced mel points from the mel of 30 Hz to the mel of 11000 Hz give band edges `f_pts`; for FFT bin `k` at
`f_k = k * 22050 / 1024` and band `m`: `down = (f_k - f_pts[m]) / (f_pts[m+1] - f_pts[m])`,
`up = (f_pts[m+2] - f_k) / (f_pts[m+2] - f_pts[m+1])`, weight `max(0, min(down, up))`. The triangles are laid on the
linear frequencies of the bins (not snapped to bins). `goldens/mel_filterbank.f32` holds the 513 x 128 matrix produced
by `beatthis_ref.mel_filterbank()`; it differs from torchaudio's float32 matrix by at most 1.2e-5 (torch computes it in
float32), and from the front end exported from torch by at most 1.1e-4 in the log-mel (mean 4e-7).

Chunked inference (`split_piece`, `aggregate_prediction`, `overlap_mode="keep_first"`, `avoid_short_end=True`):

- `chunk` 1500 frames, `border` 6. Chunk starts: `arange(-6, T - 6, 1488)`; when `T > 1488` the last start becomes
  `T - 1494`. Each chunk is `spect[max(start,0) : min(start+1500, T)]` zero-padded by `max(0, -start)` frames on the left
  and `max(0, min(6, start + 1500 - T))` on the right (zeros in the log-mel domain). A piece of at most 1488 frames is
  one chunk of `T + 12` frames at most.
- Each chunk is run as batch 1; the first and last 6 outputs are dropped; the piece prediction is initialised to
  `-1000` and filled from the last chunk to the first, so earlier chunks win in the overlaps. For a 2001-frame piece the
  first chunk (start -6) owns frames 0..1487 and the second (start 507) owns 1488..2000.

Post-processing, "minimal" (`Postprocessor`, `fps` 50): keep a frame if its logit is the maximum of its +-3 neighbours
(max-pool kernel 7, `-inf` outside) and above 0; merge peaks at most 1 frame apart into their running mean; time =
frame / 50; each downbeat is moved to the nearest beat and duplicates are removed. The app will use its own sub-frame
peak refinement and grid fitting (D1.9 to D1.11); this is only the sanity reference.

## Synthetic audio ("click_track/1")

No randomness; sample rate 22050 Hz so no resampling is involved. Everything is evaluated in double precision and
cast to float32 at the end.

- Beat times: constant `t_k = 0.3 + k * 60 / bpm`; linear tempo ramp from `bpm0` to `bpm1` over `D` seconds:
  `u_k = (-b + sqrt(b^2 + 4 a k)) / (2 a)`, `t_k = 0.3 + u_k`, `a = (bpm1 - bpm0) / (120 D)`, `b = bpm0 / 60`.
  Beat `k` exists while `t_k <= D - 0.2`.
- Sample index `n_k = floor(t_k * 22050 + 0.5)`.
- Click: for `i = 0 .. 3306` (clipped at the end of the buffer),
  `signal[n_k + i] += amp_k * sin(2 pi 2000 i / 22050) * exp(-(i / 22050) / 0.015)`;
  `amp_k = 0.6` when `k mod 4 == 0` (accented downbeat, 4/4), else `0.4`. Overlapping tails (174 BPM) are summed.
- Silence: zeros.

The goldens also store every `n_k` and `t_k` (`generator.clickSampleIndices`, `beatTimes`), 16 audio samples starting
100 samples after the first click (`audio.probe`), the peak and the RMS, so a Kotlin generator can be checked without
numpy. Why plain clicks: they are free of any music. Sweeps with drum-like or sub-division sounds made the small model
detect far worse, so this exact sound was kept (the model is out of its training domain either way).

## Goldens

All binary files are little-endian float32, row-major, no header; layouts are in each `<case>.json` and in the
docstring of `make_goldens.py`. Cases: `click_090`, `click_128`, `click_174` and `ramp_120_124` (40 s, 2001 frames, two
chunks), `click_128_short` (10 s, 501 frames, one chunk of 513 frames), `silence` (6 s, 301 frames).

| File | Content | Size |
|---|---|---|
| `manifest.json` | constants, model sha256, case list, recommended tolerances | 1.3 KB |
| `mel_filterbank.f32` | 513 x 128 filterbank | 256.5 KB |
| `<case>.json` | generator parameters, click positions, probe, layouts, post-processed beat/downbeat times | 1.2 to 8 KB |
| `<case>.mel.f32` | log-mel slices: head (frames 0..199), seam (1468..1507, long cases only), tail (last 50) | 125 to 145 KB |
| `<case>.logits.f32` | aggregated beat then downbeat logits, `frames` each | 2.4 to 16 KB |
| `<case>.chunks.f32` | raw model output per chunk (beat then downbeat), before the border cut | 2.5 to 24 KB |

Recommended Kotlin tolerances: log-mel 5e-4 absolute (float64 numpy reference; torch's float32 differs by up to 1.1e-4);
logits 5e-3 absolute (same-machine runs are bit-identical across 1 and 2 threads; torch-front-end input moves the
logits by at most 1.8e-5; another ONNX Runtime build, such as Android arm64, may move them by about 1e-4). Compare
beat/downbeat times only for peaks whose logit is not within the tolerance of 0 or of a neighbour's maximum.
`make_goldens.py --check` regenerates everything in memory and compares.

### The app's chunking: `goldens/chunk300_border24/` (D1.26)

The app does NOT run this export with the upstream 1500/6 chunking: D1.20 found its output degrades with long chunks
(synthetic 128 BPM drum loop: beat F 0.255 at 1500 frames, 0.94 at 300; five real tracks: raw model F 0.353 at
1500/6, 0.577 at 300/24), so the app and the calibration share `BeatThisChunking` (`core:djmodel`): 300 frames with a
24-frame border (step 252). `make_goldens.py --chunk 300 --border 24` writes the same six cases run that way into
`goldens/chunk300_border24/`: `<case>.json` (its `mel` section points at `../<case>.mel.f32`, which does not depend on
the chunking), `<case>.logits.f32`, `<case>.chunks.f32` (eight 300-frame chunks for the 2001-frame cases, two for
`click_128_short` and `silence`) and a `manifest.json`; no mel slices or filterbank. The per-chunk model output for a
given chunk is the same either way, so the 1500/6 goldens still check the runtime on long inputs.

Reference beats (minimal post-processing, F within +-70 ms against the clicks), 1500/6 -> 300/24: click_090 0.909 ->
0.663 (121 detections for 60 clicks: double time with only 6 s of context), click_128 0.971 -> 1.000, click_174 0.979
-> 0.996, ramp_120_124 0.614 -> 0.994, click_128_short 0.977 -> 1.000. The clicks are out of the model's training
domain; D1.20's real-music numbers are what chose 300/24. Re-exporting the model with a truly dynamic length and
checking it against PyTorch on real music is a later task (no PyTorch here).

## Validation (Intel i5-9300H, onnxruntime 1.30.0 CPU, numpy 2.5.3, Python 3.14.4, 2026-10-07)

Beat F-measure within +-70 ms against the generated clicks (greedy one-to-one match; "median offset" is the median of
detected-beat minus click over matched clicks; the model's beats sit about 10 ms after the click onset):

| Case | clicks | detected | F | precision | recall | F after 8 s | median offset |
|---|---|---|---|---|---|---|---|
| click_090 | 60 | 72 | 0.909 | 0.833 | 1.000 | 1.000 | +13.3 ms |
| click_128 | 85 | 90 | 0.971 | 0.944 | 1.000 | 1.000 | +10.0 ms |
| click_174 | 115 | 120 | 0.979 | 0.958 | 1.000 | 1.000 | +10.3 ms |
| ramp_120_124 | 81 | 183 | 0.614 | 0.443 | 1.000 | 0.594 | +15.1 ms |
| click_128_short | 21 | 22 | 0.977 | 0.955 | 1.000 | 1.000 | +7.5 ms |
| silence | 0 | 0 | | | | | no beats, no downbeats (logits about -9 to -10) |

Findings that matter for D1.9 to D1.11:

- Every click is found (recall 1.0) and, after the first seconds, nothing else (F 1.0 after 8 s) at constant tempo; the
  extra detections are in the first seconds, where the model has no context.
- On the 120 to 124 BPM ramp (and on a constant 120 BPM click track, checked while exploring) the model also fires
  between the clicks, about 2 detections per click (double time): precision 0.44. A real grid fit needs the octave
  prior and a robust fit, as already planned (D1.10); do not treat the raw peaks as the grid.
- On plain clicks the model marks almost every click as a downbeat (downbeat F 0.37 to 0.52 against the accented
  clicks), so these cases do not validate bar-phase logic (D1.9).
- Front end vs torch: against `mel_spectrogram.onnx` (the exported upstream `LogMelSpect`), log-mel max abs difference
  8.8e-5 (`click_128`) and 1.1e-4 (`ramp_120_124`), logits max abs difference 1.8e-5, identical beat times.

Timing, 6-minute 128 BPM click track (18001 frames, 13 chunks), onnxruntime CPU on this x86-64 laptop:

| Threads | Inference | Log-mel (numpy, float64) |
|---|---|---|
| 1 | 23.5 s | 0.33 s |
| 2 | 14.5 s | |

A single 1500-frame chunk takes 1.6 s on one thread and peaks at about 0.8 GB resident (about 0.1 GB for a 64-frame
input and 0.3 GB at 750 frames: the time attention grows quadratically). This is a laptop number, not the phone's:
the Realme (D1.24) must be measured. With the app's 300-frame chunks (D1.26, Java ONNX Runtime 1.30.0, 2 threads,
arena on) a chunk takes about 95 ms and the JVM peaks at 157 MB, 128 MB of which is the JVM, runtime and loaded
model.
