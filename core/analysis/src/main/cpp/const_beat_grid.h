/*
 * Constant beat grid fitting, derived from Mixxx (src/track/beatutils.cpp and src/track/beatfactory.cpp at commit
 * 332b8026), Copyright (C) Mixxx contributors, GPL-2.0-or-later. Adapted for Sonora (plain C++, no Qt), 2026,
 * GPL-3.0-or-later.
 */
#ifndef SONORA_CONST_BEAT_GRID_H
#define SONORA_CONST_BEAT_GRID_H

#include <vector>

namespace sonora {

/** A constant-tempo grid: beat k sits at firstBeatFrame + k * 60 * sampleRate / bpm. */
struct ConstBeatGrid {
    double bpm = 0;
    double firstBeatFrame = 0;
    bool valid() const { return bpm > 0; }
};

/**
 * Fits a constant grid to the beat tracker's coarse beat positions (frames, ascending), as Mixxx does for tracks
 * with a fixed tempo: finds regions of constant tempo, joins the longest one with compatible regions before and
 * after it, rounds the tempo to a "musical" value and averages the phase over the beats that agree with it. The
 * first beat is the anchor nearest the start of the track (0 <= firstBeatFrame < beat length, give or take the
 * phase adjustment). Returns an invalid grid when no tempo can be inferred.
 */
ConstBeatGrid fitConstBeatGrid(const std::vector<double>& coarseBeats, double sampleRate);

/** Mixxx's BPM rounding: the "most musical" BPM strictly inside (minBpm, maxBpm), else centerBpm. */
double roundBpmWithinRange(double minBpm, double centerBpm, double maxBpm);

} // namespace sonora

#endif // SONORA_CONST_BEAT_GRID_H
