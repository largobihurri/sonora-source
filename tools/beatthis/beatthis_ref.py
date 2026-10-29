"""Reference implementation of the Beat This! pipeline in numpy + onnxruntime (no PyTorch).

It mirrors CPJKU/beat_this @ b95c8ab (beat_this/preprocessing.py, inference.py, model/postprocessor.py):

    audio (22050 Hz mono float32)
      -> LogMelSpect (torchaudio MelSpectrogram + log1p)         log_mel()
      -> split_piece / model / aggregate_prediction(keep_first)  predict_logits()
      -> Postprocessor(type="minimal")                           postprocess_minimal()

Every constant is documented in README.md. Used by make_goldens.py and validate.py; the Kotlin implementation
(D1.8) must reproduce these functions within the tolerances listed in the README.
"""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

SAMPLE_RATE = 22050
N_FFT = 1024
HOP = 441
N_MELS = 128
F_MIN = 30.0
F_MAX = 11000.0
LOG_MULTIPLIER = 1000.0
FPS = 50
CHUNK = 1500
BORDER = 6

HERE = Path(__file__).resolve().parent
MODEL_PATH = HERE / "build" / "small0.onnx"


# ---------------------------------------------------------------------------------------------------------------
# Front end
# ---------------------------------------------------------------------------------------------------------------

def _hz_to_mel_slaney(freq: np.ndarray) -> np.ndarray:
    f_sp = 200.0 / 3
    mels = freq / f_sp
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp  # 15
    logstep = math.log(6.4) / 27.0
    return np.where(freq >= min_log_hz, min_log_mel + np.log(np.maximum(freq, 1e-12) / min_log_hz) / logstep, mels)


def _mel_to_hz_slaney(mels: np.ndarray) -> np.ndarray:
    f_sp = 200.0 / 3
    freqs = f_sp * mels
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = math.log(6.4) / 27.0
    return np.where(mels >= min_log_mel, min_log_hz * np.exp(logstep * (mels - min_log_mel)), freqs)


def mel_filterbank() -> np.ndarray:
    """torchaudio.functional.melscale_fbanks(513, 30, 11000, 128, 22050, norm=None, mel_scale="slaney").

    Triangular filters laid out on the mel scale, evaluated on the linear FFT bin frequencies (not snapped to bins),
    peak 1.0, no area normalisation. Shape (513, 128), float64.
    """
    n_freqs = N_FFT // 2 + 1
    all_freqs = np.linspace(0.0, SAMPLE_RATE // 2, n_freqs)
    m_pts = np.linspace(_hz_to_mel_slaney(np.array(F_MIN)), _hz_to_mel_slaney(np.array(F_MAX)), N_MELS + 2)
    f_pts = _mel_to_hz_slaney(m_pts)
    f_diff = f_pts[1:] - f_pts[:-1]
    slopes = f_pts[None, :] - all_freqs[:, None]  # (513, 130)
    down = (-slopes[:, :-2]) / f_diff[:-1]
    up = slopes[:, 2:] / f_diff[1:]
    return np.maximum(0.0, np.minimum(down, up))


def hann_periodic(n: int = N_FFT) -> np.ndarray:
    return 0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(n) / n)


def frame_count(num_samples: int) -> int:
    """Frames produced by a centred STFT: 1 + floor(N / hop)."""
    return 1 + num_samples // HOP


def log_mel(audio: np.ndarray, block_frames: int = 1024) -> np.ndarray:
    """Log-mel spectrogram, shape (frames, 128), float32.

    audio: mono float32 at 22050 Hz. Centred frames (reflect padding of n_fft/2 on both sides), periodic Hann,
    magnitude (power=1) divided by sqrt(n_fft) (torchaudio normalized="frame_length"), projected on the Slaney mel
    filterbank, then log1p(1000 * x). Arithmetic is float64, rounded to float32 at the end (torch uses float32
    throughout, so differences of the order of 1e-6 are expected).
    """
    x = np.asarray(audio, dtype=np.float32).astype(np.float64)
    if x.ndim != 1:
        raise ValueError("expected a mono signal")
    pad = N_FFT // 2
    if len(x) <= pad:
        raise ValueError("signal shorter than n_fft/2 + 1 samples")
    padded = np.pad(x, (pad, pad), mode="reflect")
    frames = frame_count(len(x))
    window = hann_periodic()
    fb = mel_filterbank()
    scale = 1.0 / math.sqrt(N_FFT)
    out = np.empty((frames, N_MELS), dtype=np.float32)
    for first in range(0, frames, block_frames):
        last = min(first + block_frames, frames)
        segment = padded[first * HOP: (last - 1) * HOP + N_FFT]
        view = sliding_window_view(segment, N_FFT)[::HOP][: last - first]
        spectrum = np.abs(np.fft.rfft(view * window, axis=1)) * scale
        out[first:last] = np.log1p(LOG_MULTIPLIER * (spectrum @ fb)).astype(np.float32)
    return out


# ---------------------------------------------------------------------------------------------------------------
# Chunked inference
# ---------------------------------------------------------------------------------------------------------------

def split_piece(spect: np.ndarray, chunk_size: int = CHUNK, border_size: int = BORDER):
    """inference.split_piece with avoid_short_end=True. Returns (list of (t, 128) chunks, starts)."""
    n = len(spect)
    starts = np.arange(-border_size, n - border_size, chunk_size - 2 * border_size)
    if n > chunk_size - 2 * border_size:
        starts[-1] = n - (chunk_size - border_size)
    chunks = []
    for start in starts:
        piece = spect[max(start, 0): min(start + chunk_size, n)]
        left = max(0, -int(start))
        right = max(0, min(border_size, int(start) + chunk_size - n))
        if left or right:
            piece = np.pad(piece, ((left, right), (0, 0)), mode="constant")
        chunks.append(piece)
    return chunks, [int(s) for s in starts]


def aggregate_prediction(pred_chunks, starts, full_size, chunk_size=CHUNK, border_size=BORDER):
    """inference.aggregate_prediction, overlap_mode="keep_first". pred_chunks: list of (beat, downbeat) arrays."""
    beat = np.full(full_size, -1000.0, dtype=np.float32)
    downbeat = np.full(full_size, -1000.0, dtype=np.float32)
    for start, (b, d) in reversed(list(zip(starts, pred_chunks))):  # earlier chunks overwrite later ones
        b = b[border_size:-border_size]
        d = d[border_size:-border_size]
        lo, hi = start + border_size, start + chunk_size - border_size
        beat[lo:hi] = b  # numpy clips the slice like torch does when the piece is shorter than a chunk
        downbeat[lo:hi] = d
    return beat, downbeat


def open_session(threads: int = 1, model_path: Path = MODEL_PATH):
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    return ort.InferenceSession(str(model_path), options, providers=["CPUExecutionProvider"])


def run_chunk(session, chunk: np.ndarray):
    beat, downbeat = session.run(None, {"spectrogram": chunk[None].astype(np.float32)})
    return beat[0], downbeat[0]


def predict_logits(session, spect: np.ndarray, with_chunks: bool = False, chunk_size: int = CHUNK,
                   border_size: int = BORDER):
    """Full chunked inference. Returns (beat_logits, downbeat_logits[, starts, raw_chunk_outputs])."""
    chunks, starts = split_piece(spect, chunk_size, border_size)
    raw = [run_chunk(session, c) for c in chunks]
    beat, downbeat = aggregate_prediction(raw, starts, len(spect), chunk_size, border_size)
    if with_chunks:
        return beat, downbeat, starts, raw
    return beat, downbeat


# ---------------------------------------------------------------------------------------------------------------
# Post-processing ("minimal")
# ---------------------------------------------------------------------------------------------------------------

def deduplicate_peaks(peaks, width: int = 1) -> np.ndarray:
    """Replace groups of peak frames at most `width` apart by their running mean (as in the reference)."""
    result = []
    it = iter(int(p) for p in peaks)
    try:
        p = next(it)
    except StopIteration:
        return np.array(result)
    c = 1
    for p2 in it:
        if p2 - p <= width:
            c += 1
            p += (p2 - p) / c
        else:
            result.append(p)
            p = p2
            c = 1
    result.append(p)
    return np.array(result)


def _peaks(logits: np.ndarray) -> np.ndarray:
    padded = np.pad(logits.astype(np.float32), (3, 3), constant_values=-np.inf)
    local_max = sliding_window_view(padded, 7).max(axis=1)
    return np.nonzero((logits == local_max) & (logits > 0))[0]


def postprocess_minimal(beat_logits: np.ndarray, downbeat_logits: np.ndarray, fps: int = FPS):
    """Returns (beat_times, downbeat_times) in seconds. Maxima within +-3 frames (+-70 ms) with logit > 0,
    adjacent peaks averaged, each downbeat moved to the nearest beat, duplicates removed."""
    beat_frames = deduplicate_peaks(_peaks(beat_logits), 1)
    down_frames = deduplicate_peaks(_peaks(downbeat_logits), 1)
    beat_time = beat_frames / fps
    down_time = down_frames / fps
    if len(beat_time) > 0:
        for i, d in enumerate(down_time):
            down_time[i] = beat_time[int(np.argmin(np.abs(beat_time - d)))]
    return beat_time, np.unique(down_time)


# ---------------------------------------------------------------------------------------------------------------
# Evaluation helper
# ---------------------------------------------------------------------------------------------------------------

def f_measure(reference: np.ndarray, estimate: np.ndarray, window: float = 0.070):
    """Beat F-measure with a one-to-one match inside +-window seconds (greedy on sorted times, as mir_eval does
    for well separated beats). Returns (f, precision, recall)."""
    reference = np.sort(np.asarray(reference, dtype=np.float64))
    estimate = np.sort(np.asarray(estimate, dtype=np.float64))
    if len(reference) == 0 or len(estimate) == 0:
        return 0.0, 0.0, 0.0
    used = np.zeros(len(estimate), dtype=bool)
    hits = 0
    for r in reference:
        diff = np.abs(estimate - r)
        diff[used] = np.inf
        j = int(np.argmin(diff))
        if diff[j] <= window:
            used[j] = True
            hits += 1
    precision = hits / len(estimate)
    recall = hits / len(reference)
    f = 0.0 if hits == 0 else 2 * precision * recall / (precision + recall)
    return f, precision, recall
