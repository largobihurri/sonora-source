/*
 * Constant beat grid fitting, derived from Mixxx (src/track/beatutils.cpp and src/track/beatfactory.cpp at commit
 * 332b8026), Copyright (C) Mixxx contributors, GPL-2.0-or-later. Adapted for Sonora (plain C++, no Qt), 2026,
 * GPL-3.0-or-later.
 *
 * Changes from Mixxx: frame positions and BPM are plain doubles; the steps of BeatFactory::makePreferredBeats for
 * fixed-tempo tracks (retrieveConstRegions, makeConstBpm, adjustPhase) are joined in fitConstBeatGrid; adjustPhase
 * skips the correction when no beat agrees with the grid instead of dividing by zero; makeConstBpm also gives up on
 * a non-positive region beat length; comments shortened.
 */
#include "const_beat_grid.h"

#include <algorithm>
#include <cmath>
#include <optional>

namespace sonora {
namespace {

// 25 ms is inaudible and more than twice the +-12 ms step jitter of the QM beat tracker.
constexpr double kMaxSecsPhaseError = 0.025;
// Keeps a region from spanning an offset shift (e.g. when the beat instrument changes).
constexpr double kMaxSecsPhaseErrorSum = 0.1;
constexpr int kMaxOutliersCount = 1;
constexpr int kMinRegionBeatCount = 16;

struct ConstRegion {
    double firstBeat;
    double beatLength;
};

// Splits the coarse beats into regions of constant tempo, ironing out the tracker's step jitter. The last region
// has length 0 and marks the end.
std::vector<ConstRegion> retrieveConstRegions(const std::vector<double>& coarseBeats, double sampleRate) {
    if (coarseBeats.size() < 2) return {};

    const double maxPhaseError = kMaxSecsPhaseError * sampleRate;
    const double maxPhaseErrorSum = kMaxSecsPhaseErrorSum * sampleRate;
    const int count = static_cast<int>(coarseBeats.size());
    int leftIndex = 0;
    int rightIndex = count - 1;

    std::vector<ConstRegion> regions;
    while (leftIndex < count - 1) {
        const double meanBeatLength =
                (coarseBeats[rightIndex] - coarseBeats[leftIndex]) / (rightIndex - leftIndex);
        int outliersCount = 0;
        double ironedBeat = coarseBeats[leftIndex];
        double phaseErrorSum = 0;
        int i = leftIndex + 1;
        for (; i <= rightIndex; ++i) {
            ironedBeat += meanBeatLength;
            const double phaseError = ironedBeat - coarseBeats[i];
            phaseErrorSum += phaseError;
            if (std::fabs(phaseError) > maxPhaseError) {
                outliersCount++;
                // Too many outliers, or the first beat is one: not a constant region.
                if (outliersCount > kMaxOutliersCount || i == leftIndex + 1) break;
            }
            // Drifting away in one direction: the mean beat length is not optimal.
            if (std::fabs(phaseErrorSum) > maxPhaseErrorSum) break;
        }
        if (i > rightIndex) {
            // The first and last beats must not both be correction beats in the same direction.
            double regionBorderError = 0;
            if (rightIndex > leftIndex + 2) {
                const double firstBeatLength = coarseBeats[leftIndex + 1] - coarseBeats[leftIndex];
                const double lastBeatLength = coarseBeats[rightIndex] - coarseBeats[rightIndex - 1];
                regionBorderError = std::fabs(firstBeatLength + lastBeatLength - (2 * meanBeatLength));
            }
            if (regionBorderError < maxPhaseError / 2) {
                regions.push_back({coarseBeats[leftIndex], meanBeatLength});
                leftIndex = rightIndex;
                rightIndex = count - 1;
                continue;
            }
        }
        // Try a region one beat shorter.
        rightIndex--;
    }
    regions.push_back({coarseBeats.back(), 0});
    return regions;
}

std::optional<double> trySnap(double minBpm, double centerBpm, double maxBpm, double fraction) {
    const double snapBpm = std::round(centerBpm * fraction) / fraction;
    if (snapBpm > minBpm && snapBpm < maxBpm) return snapBpm;
    return std::nullopt;
}

// Finds the tempo of the static metronome the track was likely recorded to, starting from the longest constant
// region and widening it with compatible regions before and after it. Returns 0 when no tempo can be inferred.
double makeConstBpm(const std::vector<ConstRegion>& regions, double sampleRate, double* pFirstBeat) {
    if (regions.empty()) return 0;
    const double maxPhaseErrorFrames = kMaxSecsPhaseError * sampleRate;
    const int regionCount = static_cast<int>(regions.size());

    int midRegionIndex = 0;
    double longestRegionLength = 0;
    double longestRegionBeatLength = 0;
    for (int i = 0; i < regionCount - 1; ++i) {
        const double length = regions[i + 1].firstBeat - regions[i].firstBeat;
        if (length > longestRegionLength) {
            longestRegionLength = length;
            longestRegionBeatLength = regions[i].beatLength;
            midRegionIndex = i;
        }
    }
    if (longestRegionLength == 0 || longestRegionBeatLength <= 0) return 0;

    int longestRegionNumberOfBeats = static_cast<int>((longestRegionLength / longestRegionBeatLength) + 0.5);
    double longestRegionBeatLengthMin = longestRegionBeatLength - (maxPhaseErrorFrames / longestRegionNumberOfBeats);
    double longestRegionBeatLengthMax = longestRegionBeatLength + (maxPhaseErrorFrames / longestRegionNumberOfBeats);

    int startRegionIndex = midRegionIndex;

    // A region near the beginning with a similar tempo and phase.
    for (int i = 0; i < midRegionIndex; ++i) {
        const double length = regions[i + 1].firstBeat - regions[i].firstBeat;
        const int numberOfBeats = static_cast<int>((length / regions[i].beatLength) + 0.5);
        if (numberOfBeats < kMinRegionBeatCount) continue; // short regions are too unstable
        const double thisRegionBeatLengthMin = regions[i].beatLength - (maxPhaseErrorFrames / numberOfBeats);
        const double thisRegionBeatLengthMax = regions[i].beatLength + (maxPhaseErrorFrames / numberOfBeats);
        if (longestRegionBeatLength > thisRegionBeatLengthMin && longestRegionBeatLength < thisRegionBeatLengthMax) {
            // Both regions must also be at the same phase.
            const double newLongestRegionLength = regions[midRegionIndex + 1].firstBeat - regions[i].firstBeat;
            const double beatLengthMin = std::max(longestRegionBeatLengthMin, thisRegionBeatLengthMin);
            const double beatLengthMax = std::min(longestRegionBeatLengthMax, thisRegionBeatLengthMax);
            const int maxNumberOfBeats = static_cast<int>(std::round(newLongestRegionLength / beatLengthMin));
            const int minNumberOfBeats = static_cast<int>(std::round(newLongestRegionLength / beatLengthMax));
            if (minNumberOfBeats != maxNumberOfBeats) continue; // ambiguous beat count, find a closer region
            const double newBeatLength = newLongestRegionLength / minNumberOfBeats;
            if (newBeatLength > longestRegionBeatLengthMin && newBeatLength < longestRegionBeatLengthMax) {
                longestRegionLength = newLongestRegionLength;
                longestRegionBeatLength = newBeatLength;
                longestRegionNumberOfBeats = minNumberOfBeats;
                longestRegionBeatLengthMin =
                        longestRegionBeatLength - (maxPhaseErrorFrames / longestRegionNumberOfBeats);
                longestRegionBeatLengthMax =
                        longestRegionBeatLength + (maxPhaseErrorFrames / longestRegionNumberOfBeats);
                startRegionIndex = i;
                break;
            }
        }
    }

    // A region near the end with a similar tempo and phase.
    for (int i = regionCount - 2; i > midRegionIndex; --i) {
        const double length = regions[i + 1].firstBeat - regions[i].firstBeat;
        const int numberOfBeats = static_cast<int>((length / regions[i].beatLength) + 0.5);
        if (numberOfBeats < kMinRegionBeatCount) continue;
        const double thisRegionBeatLengthMin = regions[i].beatLength - (maxPhaseErrorFrames / numberOfBeats);
        const double thisRegionBeatLengthMax = regions[i].beatLength + (maxPhaseErrorFrames / numberOfBeats);
        if (longestRegionBeatLength > thisRegionBeatLengthMin && longestRegionBeatLength < thisRegionBeatLengthMax) {
            const double newLongestRegionLength = regions[i + 1].firstBeat - regions[startRegionIndex].firstBeat;
            const double minBeatLength = std::max(longestRegionBeatLengthMin, thisRegionBeatLengthMin);
            const double maxBeatLength = std::min(longestRegionBeatLengthMax, thisRegionBeatLengthMax);
            const int maxNumberOfBeats = static_cast<int>(std::round(newLongestRegionLength / minBeatLength));
            const int minNumberOfBeats = static_cast<int>(std::round(newLongestRegionLength / maxBeatLength));
            if (minNumberOfBeats != maxNumberOfBeats) continue;
            const double newBeatLength = newLongestRegionLength / minNumberOfBeats;
            if (newBeatLength > longestRegionBeatLengthMin && newBeatLength < longestRegionBeatLengthMax) {
                longestRegionLength = newLongestRegionLength;
                longestRegionBeatLength = newBeatLength;
                longestRegionNumberOfBeats = minNumberOfBeats;
                break;
            }
        }
    }

    longestRegionBeatLengthMin = longestRegionBeatLength - (maxPhaseErrorFrames / longestRegionNumberOfBeats);
    longestRegionBeatLengthMax = longestRegionBeatLength + (maxPhaseErrorFrames / longestRegionNumberOfBeats);

    const double minRoundBpm = 60.0 * sampleRate / longestRegionBeatLengthMax;
    const double maxRoundBpm = 60.0 * sampleRate / longestRegionBeatLengthMin;
    const double centerBpm = 60.0 * sampleRate / longestRegionBeatLength;
    const double roundBpm = roundBpmWithinRange(minRoundBpm, centerBpm, maxRoundBpm);
    if (!(roundBpm > 0) || !std::isfinite(roundBpm)) return 0;

    if (pFirstBeat) {
        // Move the first beat as close to the start of the track as possible.
        const double roundedBeatLength = 60.0 * sampleRate / roundBpm;
        *pFirstBeat = std::fmod(regions[startRegionIndex].firstBeat, roundedBeatLength);
    }
    return roundBpm;
}

// Averages the offset between the grid and the beats that lie within 25 ms of it, and moves the grid by it.
double adjustPhase(double firstBeat, double bpm, double sampleRate, const std::vector<double>& beats) {
    const double beatLength = 60 * sampleRate / bpm;
    const double startOffset = std::fmod(firstBeat, beatLength);
    double offsetAdjust = 0;
    double offsetAdjustCount = 0;
    for (double beat : beats) {
        double offset = std::fmod(beat - startOffset, beatLength);
        if (offset > beatLength / 2) offset -= beatLength;
        if (std::fabs(offset) < (kMaxSecsPhaseError * sampleRate)) {
            offsetAdjust += offset;
            offsetAdjustCount++;
        }
    }
    if (offsetAdjustCount == 0) return firstBeat;
    return firstBeat + offsetAdjust / offsetAdjustCount;
}

} // namespace

double roundBpmWithinRange(double minBpm, double centerBpm, double maxBpm) {
    if (!(minBpm > 0) || !(centerBpm > 0) || !(maxBpm > 0)) return centerBpm;

    // A whole BPM first.
    if (auto snap = trySnap(minBpm, centerBpm, maxBpm, 1.0)) return *snap;
    // Half BPM values only for slow tempos (they may really be up to 175 BPM).
    if (centerBpm < 85.0) {
        if (auto snap = trySnap(minBpm, centerBpm, maxBpm, 2.0)) return *snap;
    }
    // 2/3 for fast tempos (going down to 85).
    if (centerBpm > 127.0) {
        if (auto snap = trySnap(minBpm, centerBpm, maxBpm, 2.0 / 3.0)) return *snap;
    }
    // 1/3 BPM covers 3/2 and 3/4 multipliers, 1/12 the other typical ones.
    if (auto snap = trySnap(minBpm, centerBpm, maxBpm, 3.0)) return *snap;
    if (auto snap = trySnap(minBpm, centerBpm, maxBpm, 12.0)) return *snap;
    return centerBpm;
}

ConstBeatGrid fitConstBeatGrid(const std::vector<double>& coarseBeats, double sampleRate) {
    if (coarseBeats.size() < 2 || !(sampleRate > 0)) return {};
    const std::vector<ConstRegion> regions = retrieveConstRegions(coarseBeats, sampleRate);
    double firstBeat = 0;
    const double bpm = makeConstBpm(regions, sampleRate, &firstBeat);
    if (!(bpm > 0)) return {};
    firstBeat = adjustPhase(firstBeat, bpm, sampleRate, coarseBeats);
    // Mixxx stores the grid anchor on a whole frame.
    return {bpm, std::round(firstBeat)};
}

} // namespace sonora
