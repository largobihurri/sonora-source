#!/usr/bin/env python3
"""Validate the reference pipeline on the synthetic cases and time it. Writes build/validation.json.

    OMP_NUM_THREADS=2 nice -n 19 python3 validate.py [--timing] [--threads 1 2]

Reports, per case: beat F-measure (+-70 ms), precision, recall, median offset of the detected beat nearest to each
click, F-measure after an 8 s warm-up, downbeat F-measure; then (with build/mel_spectrogram.onnx present) how far the
numpy front end is from the torch-exported one and how much that moves the logits; then (--timing) the wall time of
the whole pipeline on a 6-minute click track.
"""
from __future__ import annotations

import argparse
import json
import platform
import time
from pathlib import Path

import numpy as np

import beatthis_ref as ref
import synth

HERE = Path(__file__).resolve().parent
WARMUP_SECONDS = 8.0


def case_metrics(session) -> dict:
    results = {}
    for name, spec in synth.CASES.items():
        audio, times, _ = synth.render_case(spec)
        mel = ref.log_mel(audio)
        beat, down = ref.predict_logits(session, mel)
        beat_t, down_t = ref.postprocess_minimal(beat, down)
        entry = {"frames": len(mel), "detectedBeats": len(beat_t), "detectedDownbeats": len(down_t)}
        if times is not None:
            f, p, r = ref.f_measure(times, beat_t)
            fw, _, _ = ref.f_measure(times[times > WARMUP_SECONDS], beat_t[beat_t > WARMUP_SECONDS])
            offsets = np.array([beat_t[np.argmin(np.abs(beat_t - t))] - t for t in times]) if len(beat_t) else np.array([0.0])
            near = offsets[np.abs(offsets) <= 0.070]
            fd, _, _ = ref.f_measure(times[::synth.BEATS_PER_BAR], down_t)
            entry.update({
                "clicks": len(times), "f70": round(f, 4), "precision": round(p, 4), "recall": round(r, 4),
                "f70AfterWarmup": round(fw, 4),
                "medianOffsetMs": round(float(np.median(near)) * 1000, 1) if len(near) else None,
                "downbeatF70": round(fd, 4),
            })
        results[name] = entry
        print(name, entry)
    return results


def frontend_stability(session) -> dict | None:
    path = HERE / "build" / "mel_spectrogram.onnx"
    if not path.exists():
        print("build/mel_spectrogram.onnx absent: skipping the torch front end comparison (fetch_model.py --refs)")
        return None
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.log_severity_level = 3
    torch_mel = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
    out = {}
    for name in ("click_128", "ramp_120_124"):
        audio, _, _ = synth.render_case(synth.CASES[name])
        mine = ref.log_mel(audio)
        theirs = torch_mel.run(None, {"audio_pcm": audio[None]})[0][0]
        b1, d1 = ref.predict_logits(session, mine)
        b2, d2 = ref.predict_logits(session, theirs)
        out[name] = {
            "melMaxAbsDiff": float(np.abs(mine - theirs).max()),
            "melMeanAbsDiff": float(np.abs(mine - theirs).mean()),
            "logitMaxAbsDiff": float(max(np.abs(b1 - b2).max(), np.abs(d1 - d2).max())),
            "sameBeatTimes": bool(np.array_equal(ref.postprocess_minimal(b1, d1)[0], ref.postprocess_minimal(b2, d2)[0])),
        }
        print(name, "numpy vs torch front end:", out[name])
    return out


def thread_stability() -> dict:
    audio, _, _ = synth.render_case(synth.CASES["click_128"])
    mel = ref.log_mel(audio)
    one = ref.predict_logits(ref.open_session(1), mel)
    two = ref.predict_logits(ref.open_session(2), mel)
    diff = float(max(np.abs(one[0] - two[0]).max(), np.abs(one[1] - two[1]).max()))
    print("logits, 1 vs 2 threads, max abs diff:", diff)
    return {"logitMaxAbsDiff1v2Threads": diff}


def timing(threads_list, minutes: float = 6.0) -> dict:
    duration = minutes * 60
    audio, times, _ = synth.render_case({"kind": "constant", "bpm": 128.0, "duration": duration})
    out = {"durationSeconds": duration, "samples": len(audio)}
    t0 = time.perf_counter()
    mel = ref.log_mel(audio)
    out["logMelSeconds"] = round(time.perf_counter() - t0, 2)
    out["frames"] = len(mel)
    for threads in threads_list:
        session = ref.open_session(threads)
        ref.run_chunk(session, mel[:64])  # warm-up, excluded
        t0 = time.perf_counter()
        beat, down = ref.predict_logits(session, mel)
        seconds = time.perf_counter() - t0
        beat_t, _ = ref.postprocess_minimal(beat, down)
        f, p, r = ref.f_measure(times, beat_t)
        out[f"inference_{threads}_thread"] = {"seconds": round(seconds, 2), "chunks": len(ref.split_piece(mel)[0]),
                                              "f70": round(f, 4), "precision": round(p, 4), "recall": round(r, 4)}
        print(f"inference, {threads} thread(s): {seconds:.2f} s for {minutes:.0f} min ({len(mel)} frames), "
              f"F70 {f:.3f} P {p:.3f} R {r:.3f}")
    print(f"log-mel (numpy, float64, single thread BLAS): {out['logMelSeconds']} s")
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--timing", action="store_true", help="also time a 6-minute signal")
    parser.add_argument("--threads", type=int, nargs="+", default=[1, 2])
    args = parser.parse_args()
    import onnxruntime as ort

    session = ref.open_session(1)
    report = {"machine": platform.processor() or platform.machine(), "onnxruntime": ort.__version__,
              "numpy": np.__version__, "python": platform.python_version()}
    report["cases"] = case_metrics(session)
    report["frontendVsTorch"] = frontend_stability(session)
    report["threads"] = thread_stability()
    if args.timing:
        report["timing"] = timing(args.threads)
    (HERE / "build").mkdir(exist_ok=True)
    (HERE / "build" / "validation.json").write_text(json.dumps(report, indent=1) + "\n")


if __name__ == "__main__":
    main()
