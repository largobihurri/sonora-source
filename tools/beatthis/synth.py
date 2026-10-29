"""Deterministic synthetic click tracks ("click_track/1"). No randomness, no music, no external audio.

Formula (all in double precision, then cast to float32; sample rate 22050 Hz so that no resampling is involved):

  beat times        constant tempo:  t_k = OFFSET + k * 60 / bpm
                    linear ramp:     the tempo goes linearly from bpm0 to bpm1 over `duration` seconds, so the number
                                     of beats since OFFSET is  k(t) = (bpm0 * u + (bpm1 - bpm0) * u^2 / (2 * duration)) / 60
                                     with u = t - OFFSET, and  u_k = (-b + sqrt(b^2 + 4 a k)) / (2 a)  with
                                     a = (bpm1 - bpm0) / (120 * duration), b = bpm0 / 60.
                    A beat k exists while t_k <= duration - TAIL (TAIL = 0.2 s).
  sample index      n_k = floor(t_k * 22050 + 0.5)
  click k           for i in 0 .. CLICK_LENGTH - 1 (3307 samples, clipped at the end of the buffer):
                        signal[n_k + i] += amp_k * sin(2 * pi * 2000 * i / 22050) * exp(-(i / 22050) / 0.015)
                    amp_k = 0.6 if k mod 4 == 0 (accented downbeat, 4/4) else 0.4
  silence           all zeros.

Clicks overlap only at 174 BPM and above (the tail is below 1e-4 after 138 ms); they are summed.
"""
from __future__ import annotations

import math

import numpy as np

SAMPLE_RATE = 22050
OFFSET = 0.3
TAIL = 0.2
CLICK_HZ = 2000.0
CLICK_TAU = 0.015
CLICK_LENGTH = 3307  # floor(0.15 * 22050)
AMP_BEAT = 0.4
AMP_DOWNBEAT = 0.6
BEATS_PER_BAR = 4


def beat_times_constant(bpm: float, duration: float) -> np.ndarray:
    period = 60.0 / bpm
    count = int(math.floor((duration - TAIL - OFFSET) / period)) + 1
    return OFFSET + np.arange(count) * period


def beat_times_ramp(bpm0: float, bpm1: float, duration: float) -> np.ndarray:
    a = (bpm1 - bpm0) / (120.0 * duration)
    b = bpm0 / 60.0
    times = []
    k = 0
    while True:
        u = (-b + math.sqrt(b * b + 4.0 * a * k)) / (2.0 * a) if a != 0 else k / b
        t = OFFSET + u
        if t > duration - TAIL:
            break
        times.append(t)
        k += 1
    return np.array(times)


def render_clicks(beat_times: np.ndarray, duration: float) -> tuple[np.ndarray, np.ndarray]:
    """Returns (float32 audio, int64 sample index of each click)."""
    n = int(round(duration * SAMPLE_RATE))
    signal = np.zeros(n, dtype=np.float64)
    i = np.arange(CLICK_LENGTH)
    shape = np.sin(2.0 * np.pi * CLICK_HZ * i / SAMPLE_RATE) * np.exp(-(i / SAMPLE_RATE) / CLICK_TAU)
    indices = np.floor(beat_times * SAMPLE_RATE + 0.5).astype(np.int64)
    for k, start in enumerate(indices):
        amp = AMP_DOWNBEAT if k % BEATS_PER_BAR == 0 else AMP_BEAT
        end = min(n, start + CLICK_LENGTH)
        if end > start:
            signal[start:end] += amp * shape[: end - start]
    return signal.astype(np.float32), indices


def render_silence(duration: float) -> np.ndarray:
    return np.zeros(int(round(duration * SAMPLE_RATE)), dtype=np.float32)


# name -> spec. "kind" selects the generator; the rest are its parameters.
CASES = {
    "click_090": {"kind": "constant", "bpm": 90.0, "duration": 40.0},
    "click_128": {"kind": "constant", "bpm": 128.0, "duration": 40.0},
    "click_174": {"kind": "constant", "bpm": 174.0, "duration": 40.0},
    "ramp_120_124": {"kind": "ramp", "bpm0": 120.0, "bpm1": 124.0, "duration": 40.0},
    "click_128_short": {"kind": "constant", "bpm": 128.0, "duration": 10.0},
    "silence": {"kind": "silence", "duration": 6.0},
}


def render_case(spec: dict):
    """Returns (audio float32, beat_times or None, click sample indices or None)."""
    kind = spec["kind"]
    if kind == "silence":
        return render_silence(spec["duration"]), None, None
    if kind == "constant":
        times = beat_times_constant(spec["bpm"], spec["duration"])
    elif kind == "ramp":
        times = beat_times_ramp(spec["bpm0"], spec["bpm1"], spec["duration"])
    else:
        raise ValueError(kind)
    audio, indices = render_clicks(times, spec["duration"])
    return audio, times, indices
