#!/usr/bin/env python3
"""Generate (or verify with --check) the Beat This! golden data in goldens/ from synthetic audio only.

    python3 fetch_model.py --refs         # once: model + reference files into build/
    OMP_NUM_THREADS=2 nice -n 19 python3 make_goldens.py
    python3 make_goldens.py --check         # regenerate in memory and compare with the committed files
    python3 make_goldens.py --chunk 300 --border 24 [--check]   # the app's chunking (D1.26), see below

With the reference chunking (1500 frames, border 6, the upstream default) everything below goes to goldens/. With any
other --chunk/--border only what depends on the chunking goes to goldens/chunk<C>_border<B>/: <case>.json (whose mel
section points at ../<case>.mel.f32, which does not depend on the chunking), <case>.logits.f32, <case>.chunks.f32 and
a manifest.json; the mel slices and the filterbank stay in goldens/.

File formats (all binary files are little-endian float32, row-major, no header):
  manifest.json                global constants, model identity, case list, tolerances
  mel_filterbank.f32           513 x 128 Slaney mel filterbank (see beatthis_ref.mel_filterbank)
  <case>.json                  generator parameters, click positions, audio probe, array layouts, post-processed times
  <case>.mel.f32               selected log-mel frames: the slices listed in <case>.json, concatenated, each 128 wide
  <case>.logits.f32            aggregated logits of the whole piece: beat[frames] then downbeat[frames]
  <case>.chunks.f32            raw model outputs per chunk, before the border cut: for each chunk beat[len], downbeat[len]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np

import beatthis_ref as ref
import synth

HERE = Path(__file__).resolve().parent
GOLDENS = HERE / "goldens"

HEAD_FRAMES = 200
SEAM_FRAMES = 40
TAIL_FRAMES = 50
PROBE_LENGTH = 16

MEL_TOLERANCE = 5e-4
LOGIT_TOLERANCE = 5e-3


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def slice_plan(frames: int) -> list[dict]:
    """Which log-mel frames are stored. head: the first frames; seam: around the first chunk boundary (the first chunk
    owns frames < 1488, the second takes over from there); tail: the last frames (end reflect padding)."""
    plan = [{"name": "head", "start": 0, "count": min(HEAD_FRAMES, frames)}]
    seam_centre = ref.CHUNK - 2 * ref.BORDER  # 1488
    if frames > seam_centre + SEAM_FRAMES:
        plan.append({"name": "seam", "start": seam_centre - SEAM_FRAMES // 2, "count": SEAM_FRAMES})
    if frames > HEAD_FRAMES + TAIL_FRAMES:
        plan.append({"name": "tail", "start": frames - TAIL_FRAMES, "count": TAIL_FRAMES})
    return plan


def is_reference_chunking(chunk: int, border: int) -> bool:
    return chunk == ref.CHUNK and border == ref.BORDER


def goldens_dir(chunk: int, border: int) -> Path:
    return GOLDENS if is_reference_chunking(chunk, border) else GOLDENS / f"chunk{chunk}_border{border}"


def build_case(name: str, spec: dict, session, chunk: int = ref.CHUNK, border: int = ref.BORDER) -> dict:
    audio, times, indices = synth.render_case(spec)
    mel = ref.log_mel(audio)
    beat, down, starts, raw = ref.predict_logits(session, mel, with_chunks=True, chunk_size=chunk, border_size=border)
    beat_t, down_t = ref.postprocess_minimal(beat, down)
    plan = slice_plan(len(mel))
    mel_slices = np.concatenate([mel[p["start"]: p["start"] + p["count"]].ravel() for p in plan]).astype("<f4")
    chunk_blob = np.concatenate([np.concatenate([b, d]) for b, d in raw]).astype("<f4")
    probe_start = int(indices[0]) + 100 if indices is not None else 0
    meta = {
        "schema": "sonora.beatthis.golden/1",
        "case": name,
        "generator": {
            "name": "silence/1" if spec["kind"] == "silence" else "click_track/1",
            "spec": spec,
            "sampleRate": synth.SAMPLE_RATE,
            "numSamples": int(len(audio)),
            "clickSampleIndices": [int(i) for i in indices] if indices is not None else [],
            "clickIsDownbeat": [int(k % synth.BEATS_PER_BAR == 0) for k in range(len(indices))] if indices is not None else [],
            "beatTimes": [float(t) for t in times] if times is not None else [],
            "formulaDoc": "synth.py module docstring",
        },
        "audio": {
            "peak": float(np.abs(audio).max()),
            "rms": float(np.sqrt(np.mean(audio.astype(np.float64) ** 2))),
            "probe": {"start": probe_start, "values": [float(v) for v in audio[probe_start: probe_start + PROBE_LENGTH]]},
        },
        "frames": int(len(mel)),
        "mel": {"file": f"{name}.mel.f32" if is_reference_chunking(chunk, border) else f"../{name}.mel.f32",
                "width": ref.N_MELS, "slices": plan},
        "logits": {"file": f"{name}.logits.f32", "layout": "beat[frames] then downbeat[frames]", "frames": int(len(mel))},
        "chunks": {
            "file": f"{name}.chunks.f32",
            "chunkSize": chunk,
            "border": border,
            "starts": starts,
            "lengths": [int(len(b)) for b, _ in raw],
            "layout": "per chunk: beat[length] then downbeat[length], before the border cut",
        },
        "postprocess": {
            "type": "minimal",
            "beatTimes": [float(t) for t in beat_t],
            "downbeatTimes": [float(t) for t in down_t],
        },
    }
    files = {
        f"{name}.logits.f32": np.concatenate([beat, down]).astype("<f4"),
        f"{name}.chunks.f32": chunk_blob,
    }
    if is_reference_chunking(chunk, border):
        files[f"{name}.mel.f32"] = mel_slices
    return {"meta": meta, "files": files}


def build_manifest(model_sha: str, ort_version: str, numpy_version: str, cases: list[str]) -> dict:
    return {
        "schema": "sonora.beatthis.goldens/1",
        "model": {"manifest": "../model.json", "file": "small0.onnx", "sha256": model_sha},
        "frontend": {
            "sampleRate": ref.SAMPLE_RATE, "nFft": ref.N_FFT, "hop": ref.HOP, "melBins": ref.N_MELS,
            "fMin": ref.F_MIN, "fMax": ref.F_MAX, "melScale": "slaney", "melNorm": "none", "power": 1,
            "normalizedFrameLength": ref.N_FFT, "logMultiplier": ref.LOG_MULTIPLIER,
            "window": "hann periodic", "center": True, "padMode": "reflect",
        },
        "inference": {"chunkSize": ref.CHUNK, "borderSize": ref.BORDER, "overlapMode": "keep_first",
                      "avoidShortEnd": True, "fps": ref.FPS},
        "melFilterbank": {"file": "mel_filterbank.f32", "shape": [ref.N_FFT // 2 + 1, ref.N_MELS]},
        "cases": cases,
        "recommendedTolerances": {
            "melAbsolute": MEL_TOLERANCE,
            "logitAbsolute": LOGIT_TOLERANCE,
            "note": "The goldens were produced with float64 numpy DSP and onnxruntime 1.30 x86-64 CPU. The reference "
                    "torch front end differs from them by <= 1.3e-4 on the mel; a different ONNX Runtime build "
                    "(Android arm64) may differ by about 1e-4 on logits. Compare beat/downbeat times only where the "
                    "logit is not within the logit tolerance of the decision threshold 0.",
        },
        "tools": {"onnxruntime": ort_version, "numpy": numpy_version},
    }


def build_chunking_manifest(model_sha: str, ort_version: str, numpy_version: str, cases: list[str], chunk: int,
                            border: int) -> dict:
    return {
        "schema": "sonora.beatthis.goldens.chunking/1",
        "model": {"manifest": "../../model.json", "file": "small0.onnx", "sha256": model_sha},
        "frontend": "../manifest.json (the mel slices and the filterbank are in the parent directory)",
        "inference": {"chunkSize": chunk, "borderSize": border, "overlapMode": "keep_first", "avoidShortEnd": True,
                      "fps": ref.FPS},
        "cases": cases,
        "recommendedTolerances": {"logitAbsolute": LOGIT_TOLERANCE},
        "tools": {"onnxruntime": ort_version, "numpy": numpy_version},
    }


def write_json(path: Path, obj: dict) -> None:
    path.write_text(json.dumps(obj, indent=1, ensure_ascii=False) + "\n")


def check(case: dict, name: str, directory: Path = GOLDENS) -> list[str]:
    problems = []
    meta_path = directory / f"{name}.json"
    if not meta_path.exists():
        return [f"{name}: missing {meta_path.name}"]
    old = json.loads(meta_path.read_text())
    new = case["meta"]
    for key in ("generator", "frames", "mel", "chunks", "postprocess"):
        if old[key] != new[key]:
            problems.append(f"{name}: '{key}' differs")
    for fname, data in case["files"].items():
        path = directory / fname
        if not path.exists():
            problems.append(f"{fname}: missing")
            continue
        stored = np.fromfile(path, dtype="<f4")
        tolerance = MEL_TOLERANCE if fname.endswith(".mel.f32") else LOGIT_TOLERANCE
        if stored.shape != data.shape:
            problems.append(f"{fname}: size {stored.shape} != {data.shape}")
        else:
            worst = float(np.abs(stored - data).max())
            print(f"  {fname:34s} max abs diff {worst:.3e}")
            if worst > tolerance:
                problems.append(f"{fname}: max abs diff {worst:.3e} > {tolerance}")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="compare with the committed goldens instead of writing")
    parser.add_argument("--chunk", type=int, default=ref.CHUNK, help="chunk length in frames (default %(default)s)")
    parser.add_argument("--border", type=int, default=ref.BORDER, help="border in frames (default %(default)s)")
    args = parser.parse_args()
    if args.chunk <= 0 or args.border < 0 or 2 * args.border >= args.chunk:
        sys.exit(f"border {args.border} does not fit chunk {args.chunk}")
    reference_chunking = is_reference_chunking(args.chunk, args.border)
    out_dir = goldens_dir(args.chunk, args.border)

    import onnxruntime as ort

    model_manifest = json.loads((HERE / "model.json").read_text())
    if not ref.MODEL_PATH.exists() or sha256_file(ref.MODEL_PATH) != model_manifest["sha256"]:
        sys.exit("build/small0.onnx missing or its sha256 differs: run fetch_model.py")
    session = ref.open_session(threads=1)
    names = list(synth.CASES)

    fb = ref.mel_filterbank()
    fb_ref_path = HERE / "build" / "mel-filterbank.bin"
    if fb_ref_path.exists():
        torchaudio_fb = np.fromfile(fb_ref_path, dtype="<f4").reshape(fb.shape)
        print(f"mel filterbank vs torchaudio MelScale.fb: max abs diff {np.abs(fb - torchaudio_fb).max():.3e}")
    else:
        print("note: build/mel-filterbank.bin absent, filterbank not cross-checked (fetch_model.py --refs)")

    out_dir.mkdir(exist_ok=True)
    problems = []
    for name in names:
        print(f"{name}")
        case = build_case(name, synth.CASES[name], session, args.chunk, args.border)
        if args.check:
            problems += check(case, name, out_dir)
            continue
        write_json(out_dir / f"{name}.json", case["meta"])
        for fname, data in case["files"].items():
            data.tofile(out_dir / fname)
        print(f"  frames {case['meta']['frames']}, beats {len(case['meta']['postprocess']['beatTimes'])}, "
              f"downbeats {len(case['meta']['postprocess']['downbeatTimes'])}")
    if args.check and not reference_chunking:
        if problems:
            print("FAILED:\n  " + "\n  ".join(problems))
            sys.exit(1)
        print("goldens verified")
        return
    if args.check:
        stored_fb = np.fromfile(GOLDENS / "mel_filterbank.f32", dtype="<f4")
        worst = float(np.abs(stored_fb - fb.astype("<f4").ravel()).max())
        print(f"mel_filterbank.f32 max abs diff {worst:.3e}")
        if worst > 1e-7:
            problems.append("mel_filterbank.f32 differs")
        if problems:
            print("FAILED:\n  " + "\n  ".join(problems))
            sys.exit(1)
        print("goldens verified")
        return
    if reference_chunking:
        fb.astype("<f4").tofile(GOLDENS / "mel_filterbank.f32")
        write_json(GOLDENS / "manifest.json", build_manifest(model_manifest["sha256"], ort.__version__, np.__version__, names))
    else:
        write_json(out_dir / "manifest.json", build_chunking_manifest(
            model_manifest["sha256"], ort.__version__, np.__version__, names, args.chunk, args.border))
    files = [p for p in out_dir.iterdir() if p.is_file()]
    total = sum(p.stat().st_size for p in files)
    print(f"wrote {len(files)} files in {out_dir.name}/, {total / 1024:.0f} KiB")


if __name__ == "__main__":
    main()
