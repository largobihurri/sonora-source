/*
 * Sonora track analyser. Copyright (C) 2026 Sonora contributors. GPL-3.0-or-later.
 * Parts derived from Mixxx (GPL-2.0-or-later, Copyright (C) Mixxx contributors): the framing of Framer
 * (DownmixAndOverlapHelper), the DF post-processing in finish (AnalyzerQueenMaryBeats::finalize) and globalKey
 * (KeyUtils::calculateGlobalKey).
 *
 * Follows Mixxx's analysers (AnalyzerQueenMaryBeats, AnalyzerQueenMaryKey, KeyUtils::calculateGlobalKey at Mixxx
 * commit 332b8026) and qm-vamp-plugins' BarBeatTrack for downbeats, on top of the vendored qm-dsp:
 *  - onset detection function (complex spectral difference) every ~11.6 ms, TempoTrackV2 beat tracking, then a
 *    constant grid fitted as Mixxx does for fixed-tempo tracks (const_beat_grid.cpp);
 *  - downbeats: DownBeat's beat-synchronous spectral difference over the fitted grid, on audio this file decimates
 *    itself in blocks that are whole multiples of the decimation factor (DownBeat::pushAudioBlock needs blocks of
 *    one DF step, and 557 frames at 48 kHz is not a multiple of 16, which made it drift by ~2.3%);
 *  - key: GetKeyMode on non-overlapping blocks; the global key is the one that lasts longest.
 */
#include "sonora_analyzer.h"

#include "const_beat_grid.h"

#include <dsp/keydetection/GetKeyMode.h>
#include <dsp/onsets/DetectionFunction.h>
#include <dsp/rateconversion/Decimator.h>
#include <dsp/tempotracking/DownBeat.h>
#include <dsp/tempotracking/TempoTrackV2.h>
#include <maths/MathUtilities.h>

#include <algorithm>
#include <cmath>
#include <map>
#include <memory>
#include <new>
#include <utility>
#include <vector>

namespace {

// Mixxx: the DF step is int(rate * 0.01161f) frames (512 at 44.1 kHz) and the window the next power of two of
// rate / 50 Hz, so the DF rate stays ~86 Hz at every sample rate.
constexpr float kStepSecs = 0.01161f;
constexpr int kMaximumBinSizeHz = 50;
// qm-vamp-plugins BarBeatTrack: downbeats on audio decimated to ~3 kHz.
constexpr int kDownbeatTargetRateHz = 3000;
constexpr int kDownbeatBlocksPerDecimation = 256;
constexpr float kTuningFrequencyHz = 440.0f;
// TempoTrackV2 analyses 512 DF values (~6 s) per window and needs at least half a window of input.
constexpr size_t kMinDetectionFunctionLength = 512;

/**
 * Cuts a mono stream into windows of `window` frames advancing by `step`, the first one centred on frame 0 (half a
 * window of zeros in front), and zero-pads the end like Mixxx's DownmixAndOverlapHelper::finalize.
 */
class Framer {
public:
    Framer(size_t window, size_t step) : m_buffer(window, 0.0), m_window(window), m_step(step), m_fill(window / 2) {}

    template <typename Sink>
    void push(const float* input, size_t count, Sink&& sink) {
        size_t done = 0;
        while (done < count) {
            const size_t take = std::min(count - done, m_window - m_fill);
            for (size_t i = 0; i < take; ++i) m_buffer[m_fill + i] = input ? double(input[done + i]) : 0.0;
            m_fill += take;
            done += take;
            if (m_fill == m_window) {
                sink(m_buffer.data());
                std::copy(m_buffer.begin() + m_step, m_buffer.end(), m_buffer.begin());
                m_fill -= m_step;
            }
        }
    }

    template <typename Sink>
    void finish(Sink&& sink) {
        push(nullptr, std::max(m_window - m_fill, m_window / 2 - 1), sink);
    }

private:
    std::vector<double> m_buffer;
    size_t m_window;
    size_t m_step;
    size_t m_fill;
};

/** Decimates a stream by a power of two (2..64) with qm-dsp's anti-aliasing decimators, in fixed blocks. */
class Downsampler {
public:
    explicit Downsampler(int factor)
            : m_factor(factor),
              m_block(size_t(factor) * kDownbeatBlocksPerDecimation),
              m_first(int(m_block), std::min(factor, Decimator::getHighestSupportedFactor())),
              m_second(int(m_block) / m_first.getFactor(), factor / m_first.getFactor()),
              m_pending(),
              m_middle(m_block / size_t(m_first.getFactor())) {
        m_pending.reserve(m_block);
    }

    int factor() const { return m_factor; }

    void push(const float* input, size_t count) {
        size_t done = 0;
        while (done < count) {
            const size_t take = std::min(count - done, m_block - m_pending.size());
            m_pending.insert(m_pending.end(), input + done, input + done + take);
            done += take;
            if (m_pending.size() == m_block) flushBlock();
        }
    }

    /** Decimates the last partial block (zero-padded) and returns the whole decimated stream. */
    const std::vector<float>& finish(size_t totalFrames) {
        if (!m_pending.empty()) {
            m_pending.resize(m_block, 0.0f);
            flushBlock();
        }
        m_output.resize(std::min(m_output.size(), totalFrames / size_t(m_factor)));
        return m_output;
    }

private:
    void flushBlock() {
        const size_t outCount = m_block / size_t(m_factor);
        const size_t at = m_output.size();
        m_output.resize(at + outCount);
        if (m_second.getFactor() > 1) {
            m_first.process(m_pending.data(), m_middle.data());
            m_second.process(m_middle.data(), m_output.data() + at);
        } else {
            m_first.process(m_pending.data(), m_output.data() + at);
        }
        m_pending.clear();
    }

    int m_factor;
    size_t m_block;
    Decimator m_first;
    Decimator m_second;
    std::vector<float> m_pending;
    std::vector<float> m_middle;
    std::vector<float> m_output;
};

DFConfig detectionFunctionConfig(int step, int window) {
    DFConfig config;
    config.DFType = DF_COMPLEXSD;
    config.stepSize = step;
    config.frameLength = window;
    config.dbRise = 3;
    config.adaptiveWhitening = false;
    config.whiteningRelaxCoeff = -1;
    config.whiteningFloor = -1;
    return config;
}

int downbeatFactor(int sampleRate) {
    return MathUtilities::nextPowerOfTwo(sampleRate / kDownbeatTargetRateHz);
}

} // namespace

struct sa_analyzer {
    explicit sa_analyzer(int rate)
            : sampleRate(rate),
              step(int(double(rate) * kStepSecs)),
              window(MathUtilities::nextPowerOfTwo(rate / kMaximumBinSizeHz)),
              detectionFunction(detectionFunctionConfig(step, window)),
              dfFramer(size_t(window), size_t(step)),
              keyMode(GetKeyMode::Config(double(rate), kTuningFrequencyHz)),
              keyFramer(size_t(keyMode.getBlockSize()), size_t(keyMode.getHopSize())),
              downsampler(downbeatFactor(rate)) {}

    void process(const float* mono, size_t count) {
        dfFramer.push(mono, count, [this](double* frame) { onDetectionFrame(frame); });
        keyFramer.push(mono, count, [this](double* frame) { onKeyFrame(frame); });
        downsampler.push(mono, count);
        totalFrames += int64_t(count);
    }

    int finish(sa_result* out) {
        finished = true;
        dfFramer.finish([this](double* frame) { onDetectionFrame(frame); });
        keyFramer.finish([this](double* frame) { onKeyFrame(frame); });
        const std::vector<float>& decimated = downsampler.finish(size_t(totalFrames));

        if (totalFrames * 1000 < int64_t(SA_MIN_DURATION_MS) * sampleRate) return SA_ERR_TOO_SHORT;

        // AnalyzerQueenMaryBeats::finalize: drop trailing non-positive values and the first two (onset noise).
        size_t nonZero = dfValues.size();
        while (nonZero > 0 && dfValues[nonZero - 1] <= 0.0) --nonZero;
        if (nonZero < 2 + kMinDetectionFunctionLength) return SA_ERR_NO_BEATS;
        const std::vector<double> df(dfValues.begin() + 2, dfValues.begin() + long(nonZero));

        TempoTrackV2 tracker(float(sampleRate), step);
        std::vector<int> beatPeriod(df.size() / 128 + 1);
        tracker.calculateBeatPeriod(df, beatPeriod);
        std::vector<double> dfBeats;
        tracker.calculateBeats(df, beatPeriod, dfBeats);

        // A beat is detected between two DF values: add half a step.
        std::vector<double> coarseBeats;
        coarseBeats.reserve(dfBeats.size());
        for (double beat : dfBeats) coarseBeats.push_back(beat * step + step / 2);

        const sonora::ConstBeatGrid grid = sonora::fitConstBeatGrid(coarseBeats, double(sampleRate));
        if (!grid.valid()) return SA_ERR_NO_BEATS;

        const double beatLength = 60.0 * sampleRate / grid.bpm;
        double firstBeat = std::fmod(grid.firstBeatFrame, beatLength);
        if (firstBeat < 0) firstBeat += beatLength;

        out->bpm = grid.bpm;
        out->first_beat_frame = firstBeat;
        out->first_beat_in_bar = firstBeatInBar(decimated, firstBeat, beatLength);
        out->key = globalKey();
        out->total_frames = totalFrames;
        out->detected_beats = int32_t(dfBeats.size());
        return SA_OK;
    }

    bool finished = false;

private:
    void onDetectionFrame(double* frame) { dfValues.push_back(detectionFunction.processTimeDomain(frame)); }

    void onKeyFrame(double* frame) {
        // Hop == block size, so window k is centred on frame k * hop.
        const int64_t centre = keyWindows++ * int64_t(keyMode.getHopSize());
        const int key = keyMode.process(frame);
        if (key < 1 || key > 24) return;
        if (key != previousKey) {
            keyChanges.emplace_back(key, centre);
            previousKey = key;
        }
    }

    // KeyUtils::calculateGlobalKey: the key that lasts longest in total.
    int globalKey() const {
        if (keyChanges.empty()) return 0;
        std::map<int, int64_t> durations;
        for (size_t i = 0; i < keyChanges.size(); ++i) {
            const int64_t start = std::max<int64_t>(0, keyChanges[i].second);
            const int64_t end = i + 1 < keyChanges.size() ? keyChanges[i + 1].second : totalFrames;
            durations[keyChanges[i].first] += std::max<int64_t>(0, end - start);
        }
        int best = keyChanges.front().first;
        int64_t bestDuration = -1;
        for (const auto& entry : durations) {
            if (entry.second > bestDuration) {
                best = entry.first;
                bestDuration = entry.second;
            }
        }
        return best;
    }

    // Beat-in-bar of the grid's first beat, from DownBeat over the whole grid (one global bar phase).
    int firstBeatInBar(const std::vector<float>& decimated, double firstBeat, double beatLength) const {
        const int factor = downsampler.factor();
        std::vector<double> beats; // in units of `factor` frames, i.e. decimated samples
        for (double frame = firstBeat; frame < double(totalFrames); frame += beatLength) {
            beats.push_back(frame / factor);
        }
        if (beats.size() < 2 || decimated.empty()) return 1;
        DownBeat downBeat{float(sampleRate), size_t(factor), size_t(factor)};
        downBeat.setBeatsPerBar(SA_BEATS_PER_BAR);
        std::vector<int> downbeats;
        downBeat.findDownBeats(decimated.data(), decimated.size(), beats, downbeats);
        if (downbeats.empty()) return 1;
        const int firstDownbeat = downbeats.front(); // 0..3: grid index of the first downbeat
        return (SA_BEATS_PER_BAR - firstDownbeat) % SA_BEATS_PER_BAR + 1;
    }

    const int sampleRate;
    const int step;
    const int window;
    DetectionFunction detectionFunction;
    Framer dfFramer;
    std::vector<double> dfValues;
    GetKeyMode keyMode;
    Framer keyFramer;
    int64_t keyWindows = 0;
    int previousKey = -1;
    std::vector<std::pair<int, int64_t>> keyChanges;
    Downsampler downsampler;
    int64_t totalFrames = 0;
};

int sa_create(int32_t sample_rate, sa_analyzer** out) {
    if (!out) return SA_ERR_INVALID_ARGUMENT;
    *out = nullptr;
    if (sample_rate < SA_MIN_SAMPLE_RATE || sample_rate > SA_MAX_SAMPLE_RATE) return SA_ERR_UNSUPPORTED_SAMPLE_RATE;
    try {
        *out = new sa_analyzer(sample_rate);
        return SA_OK;
    } catch (const std::bad_alloc&) {
        return SA_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return SA_ERR_INTERNAL;
    }
}

int sa_process(sa_analyzer* analyzer, const float* mono, size_t frame_count) {
    if (!analyzer || (!mono && frame_count > 0)) return SA_ERR_INVALID_ARGUMENT;
    if (analyzer->finished) return SA_ERR_STATE;
    if (frame_count == 0) return SA_OK;
    try {
        analyzer->process(mono, frame_count);
        return SA_OK;
    } catch (const std::bad_alloc&) {
        return SA_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return SA_ERR_INTERNAL;
    }
}

int sa_finish(sa_analyzer* analyzer, sa_result* out) {
    if (!analyzer || !out) return SA_ERR_INVALID_ARGUMENT;
    if (analyzer->finished) return SA_ERR_STATE;
    *out = sa_result{};
    try {
        return analyzer->finish(out);
    } catch (const std::bad_alloc&) {
        return SA_ERR_OUT_OF_MEMORY;
    } catch (...) {
        return SA_ERR_INTERNAL;
    }
}

void sa_destroy(sa_analyzer* analyzer) { delete analyzer; }
