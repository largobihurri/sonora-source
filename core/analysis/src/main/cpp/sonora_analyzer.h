/*
 * Sonora track analyser: tempo, constant beat grid with downbeats and global key of one track, computed the way
 * Mixxx does it with qm-dsp. Pure C/C++ (no JNI), so the same code runs in the app and in host tests.
 *
 * Copyright (C) 2026 Sonora contributors. GPL-3.0-or-later.
 *
 * Usage: sa_create, then sa_process for every block of mono samples in order, then sa_finish once, then sa_destroy.
 * One analyser handles one track and must not be used from two threads at once.
 */
#ifndef SONORA_ANALYZER_H
#define SONORA_ANALYZER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct sa_analyzer sa_analyzer;

enum {
    SA_OK = 0,
    /** A null pointer or a negative/oversized argument. */
    SA_ERR_INVALID_ARGUMENT = -1,
    /** sa_process or sa_finish after sa_finish. */
    SA_ERR_STATE = -2,
    SA_ERR_OUT_OF_MEMORY = -3,
    /** An unexpected failure inside the DSP code. */
    SA_ERR_INTERNAL = -4,
    /** sa_create: the sample rate is outside [SA_MIN_SAMPLE_RATE, SA_MAX_SAMPLE_RATE]. */
    SA_ERR_UNSUPPORTED_SAMPLE_RATE = -5,
    /** sa_finish: less than SA_MIN_DURATION_MS of audio. */
    SA_ERR_TOO_SHORT = -6,
    /** sa_finish: no steady beat found (silence, beatless material). */
    SA_ERR_NO_BEATS = -7,
};

#define SA_MIN_SAMPLE_RATE 8000
#define SA_MAX_SAMPLE_RATE 192000
#define SA_MIN_DURATION_MS 5000
#define SA_BEATS_PER_BAR 4

typedef struct sa_result {
    /** Constant tempo, rounded as Mixxx rounds it (whole BPM when the beats allow it). */
    double bpm;
    /** The earliest grid beat at or after frame 0, in frames (fractional); beat k is at first + k * 60 * rate / bpm. */
    double first_beat_frame;
    /** Position of that beat in its bar, 1..SA_BEATS_PER_BAR (1 = downbeat). */
    int32_t first_beat_in_bar;
    /** Global key as a qm-dsp index: 1..12 major C..B, 13..24 minor C..B; 0 when no key was detected. */
    int32_t key;
    /** Frames fed through sa_process. */
    int64_t total_frames;
    /** Beats the tracker found before the grid was fitted (diagnostics). */
    int32_t detected_beats;
} sa_result;

/** Creates an analyser for mono audio at sample_rate Hz into *out. Returns SA_OK or an SA_ERR_* code. */
int sa_create(int32_t sample_rate, sa_analyzer** out);

/** Feeds the next frame_count mono samples (any block size, including 0). Returns SA_OK or an SA_ERR_* code. */
int sa_process(sa_analyzer* analyzer, const float* mono, size_t frame_count);

/** Ends the input and computes the result into *out. Callable once. Returns SA_OK or an SA_ERR_* code. */
int sa_finish(sa_analyzer* analyzer, sa_result* out);

/** Frees the analyser. Accepts NULL. */
void sa_destroy(sa_analyzer* analyzer);

#ifdef __cplusplus
}
#endif

#endif /* SONORA_ANALYZER_H */
