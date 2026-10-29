// Host tests for the Sonora analyser core (run by :core:analysis:hostNativeTest through ctest).
// Usage: analyzer_test [test_name]   (no argument runs every test)

#include "const_beat_grid.h"
#include "sonora_analyzer.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

namespace {

int g_failures = 0;

#define CHECK(cond)                                                                    \
    do {                                                                               \
        if (!(cond)) {                                                                 \
            std::fprintf(stderr, "  FAILED %s:%d: %s\n", __FILE__, __LINE__, #cond);   \
            ++g_failures;                                                              \
        }                                                                              \
    } while (0)

#define CHECK_NEAR(actual, expected, tolerance)                                                      \
    do {                                                                                             \
        const double a_ = (actual), e_ = (expected), t_ = (tolerance);                               \
        if (!(std::fabs(a_ - e_) <= t_)) {                                                           \
            std::fprintf(stderr, "  FAILED %s:%d: %s = %.6f, expected %.6f +- %.6f\n", __FILE__,      \
                         __LINE__, #actual, a_, e_, t_);                                             \
            ++g_failures;                                                                            \
        }                                                                                            \
    } while (0)

#define CHECK_EQ(actual, expected)                                                                   \
    do {                                                                                             \
        const long long a_ = (long long)(actual), e_ = (long long)(expected);                        \
        if (a_ != e_) {                                                                              \
            std::fprintf(stderr, "  FAILED %s:%d: %s = %lld, expected %lld\n", __FILE__, __LINE__,   \
                         #actual, a_, e_);                                                           \
            ++g_failures;                                                                            \
        }                                                                                            \
    } while (0)

const double kPi = 3.14159265358979323846;
struct Harmony {
    std::vector<double> chord; // sustained triad
    double bassRoots[2];       // bass note of odd and even bars, below the key detector's range (C3 and up)
};
const Harmony kAMinor = {{220.0, 261.63, 329.63}, {110.0, 82.41}}; // A3 C4 E4; A2 / E2
const Harmony kCMajor = {{261.63, 329.63, 392.00}, {65.41, 98.0}}; // C4 E4 G4; C2 / G2

/**
 * A synthetic dance track: a sustained triad, and from offsetSecs on a kick on every beat, a noise burst on beat 1
 * of every bar and a bass note that changes on every bar line (the beat at offsetSecs is a downbeat).
 *
 * The bass change matters: qm-dsp's DownBeat looks for the beat with the largest spectral change from the beat
 * before it. An accent alone changes the spectrum as much going into beat 1 as coming out of it, so the downbeat
 * would be a coin toss between beats 1 and 2; music changes harmony on the bar line.
 */
std::vector<float> clickTrack(double sampleRate, double bpm, double offsetSecs, double secs, const Harmony& harmony) {
    const size_t n = size_t(sampleRate * secs);
    std::vector<float> x(n);
    const double beatLength = 60.0 / bpm * sampleRate;
    const double offset = offsetSecs * sampleRate;
    uint32_t seed = 1;
    for (size_t i = 0; i < n; ++i) {
        const double t = double(i) / sampleRate;
        double v = 0;
        for (double f : harmony.chord) v += 0.08 * std::sin(2 * kPi * f * t);
        if (double(i) >= offset) {
            const double position = (double(i) - offset) / beatLength;
            const long beat = long(position);
            const double dt = (position - double(beat)) * beatLength / sampleRate;
            v += 0.6 * std::exp(-dt * 30) * std::sin(2 * kPi * 60 * dt);
            v += 0.15 * std::sin(2 * kPi * harmony.bassRoots[(beat / 4) % 2] * t);
            if (beat % 4 == 0) {
                seed = seed * 1664525u + 1013904223u;
                v += 0.3 * std::exp(-dt * 8) * ((seed >> 9) / 4194304.0 - 1.0);
            }
        }
        x[i] = float(v);
    }
    return x;
}

struct Outcome {
    int status = SA_ERR_INTERNAL;
    sa_result result{};
};

Outcome analyse(int sampleRate, const std::vector<float>& mono, size_t blockSize = 4096) {
    Outcome outcome;
    sa_analyzer* analyzer = nullptr;
    outcome.status = sa_create(sampleRate, &analyzer);
    if (outcome.status != SA_OK) return outcome;
    for (size_t i = 0; i < mono.size(); i += blockSize) {
        const size_t count = std::min(blockSize, mono.size() - i);
        outcome.status = sa_process(analyzer, mono.data() + i, count);
        if (outcome.status != SA_OK) {
            sa_destroy(analyzer);
            return outcome;
        }
    }
    outcome.status = sa_finish(analyzer, &outcome.result);
    sa_destroy(analyzer);
    return outcome;
}

void report(const char* name, int sampleRate, const Outcome& o, double expectedFirstBeatSecs) {
    const double firstBeatSecs = o.result.first_beat_frame / sampleRate;
    std::printf("  %s: status=%d bpm=%.4f firstBeat=%.4fs (error %+.1f ms) inBar=%d key=%d frames=%lld beats=%d\n",
                name, o.status, o.result.bpm, firstBeatSecs, (firstBeatSecs - expectedFirstBeatSecs) * 1000.0,
                o.result.first_beat_in_bar, o.result.key, (long long)o.result.total_frames,
                o.result.detected_beats);
}

void checkClickTrack(int sampleRate, double bpm, double offsetSecs, const Harmony& harmony,
                     double expectedFirstBeatSecs, int expectedInBar, int expectedKey) {
    const auto signal = clickTrack(sampleRate, bpm, offsetSecs, 60.0, harmony);
    const Outcome o = analyse(sampleRate, signal);
    report("result", sampleRate, o, expectedFirstBeatSecs);
    CHECK_EQ(o.status, SA_OK);
    CHECK_NEAR(o.result.bpm, bpm, 0.01);
    CHECK_NEAR(o.result.first_beat_frame / sampleRate, expectedFirstBeatSecs, 0.012);
    CHECK_EQ(o.result.first_beat_in_bar, expectedInBar);
    if (expectedKey >= 0) CHECK_EQ(o.result.key, expectedKey);
    CHECK_EQ(o.result.total_frames, (long long)signal.size());
}

// --- tests ---------------------------------------------------------------------------------------------------

void round_bpm_within_range() {
    using sonora::roundBpmWithinRange;
    CHECK_NEAR(roundBpmWithinRange(127.9, 128.02, 128.1), 128.0, 1e-12);
    // Below 85 BPM half values are allowed.
    CHECK_NEAR(roundBpmWithinRange(84.4, 84.45, 84.6), 84.5, 1e-12);
    // Above 127 BPM 1.5 BPM steps (a 2/3 factor), then 1/3 BPM steps.
    CHECK_NEAR(roundBpmWithinRange(130.45, 130.5, 130.55), 130.5, 1e-9);
    CHECK_NEAR(roundBpmWithinRange(140.6, 140.65, 140.7), 140.0 + 2.0 / 3.0, 1e-9);
    // 1/12 BPM steps as the last resort, then the centre unchanged.
    CHECK_NEAR(roundBpmWithinRange(100.41, 100.415, 100.42), 100.0 + 5.0 / 12.0, 1e-9);
    CHECK_NEAR(roundBpmWithinRange(100.4001, 100.4002, 100.4003), 100.4002, 1e-12);
}

void grid_from_jittered_beats() {
    // A tracker quantises beats to its 512-frame step (+ half a step): +-6 ms jitter at 44.1 kHz.
    const double sampleRate = 44100, step = 512, bpm = 128, firstBeat = 0.3 * sampleRate;
    const double beatLength = 60 * sampleRate / bpm;
    std::vector<double> coarse;
    for (int k = 0; k < 400; ++k) {
        const double exact = firstBeat + k * beatLength;
        coarse.push_back(std::floor(exact / step) * step + step / 2);
    }
    const sonora::ConstBeatGrid grid = sonora::fitConstBeatGrid(coarse, sampleRate);
    std::printf("  grid: bpm=%.4f first=%.1f frames (exact %.1f)\n", grid.bpm, grid.firstBeatFrame, firstBeat);
    CHECK(grid.valid());
    CHECK_NEAR(grid.bpm, 128.0, 1e-9);
    CHECK_NEAR(grid.firstBeatFrame / sampleRate, 0.3, 0.012);

    CHECK(!sonora::fitConstBeatGrid({}, sampleRate).valid());
    CHECK(!sonora::fitConstBeatGrid({1000.0}, sampleRate).valid());
}

void click_128_bpm_44100() { checkClickTrack(44100, 128, 0.3, kAMinor, 0.3, 1, 22); }

void click_128_bpm_48000() { checkClickTrack(48000, 128, 0.3, kAMinor, 0.3, 1, 22); }

// Downbeat at 0.9 s; the earliest grid beat (0.3 s) is the last beat of the bar before it.
void click_100_bpm_c_major() { checkClickTrack(44100, 100, 0.9, kCMajor, 0.3, 4, 1); }

// Low rates still find the tempo; the key detector's range exceeds their Nyquist frequency, so no key check.
void click_128_bpm_22050() { checkClickTrack(22050, 128, 0.3, kAMinor, 0.3, 1, -1); }

void block_size_does_not_change_the_result() {
    const auto signal = clickTrack(44100, 128, 0.3, 30.0, kAMinor);
    const Outcome reference = analyse(44100, signal, 4096);
    CHECK_EQ(reference.status, SA_OK);
    for (size_t blockSize : {size_t(1), size_t(777), signal.size()}) {
        const Outcome o = analyse(44100, signal, blockSize);
        CHECK_EQ(o.status, SA_OK);
        // Field by field: memcmp would also compare sa_result's padding bytes, which hold no defined value.
        CHECK(o.result.bpm == reference.result.bpm);
        CHECK(o.result.first_beat_frame == reference.result.first_beat_frame);
        CHECK_EQ(o.result.first_beat_in_bar, reference.result.first_beat_in_bar);
        CHECK_EQ(o.result.key, reference.result.key);
        CHECK_EQ(o.result.total_frames, reference.result.total_frames);
        CHECK_EQ(o.result.detected_beats, reference.result.detected_beats);
    }
}

void too_short() {
    const auto signal = clickTrack(44100, 128, 0.0, 4.9, kAMinor);
    CHECK_EQ(analyse(44100, signal).status, SA_ERR_TOO_SHORT);
    CHECK_EQ(analyse(44100, {}).status, SA_ERR_TOO_SHORT);
}

void silence_has_no_beats() {
    const std::vector<float> silence(size_t(44100 * 30.0), 0.0f);
    CHECK_EQ(analyse(44100, silence).status, SA_ERR_NO_BEATS);
}

void api_misuse() {
    sa_analyzer* analyzer = nullptr;
    CHECK_EQ(sa_create(SA_MIN_SAMPLE_RATE - 1, &analyzer), SA_ERR_UNSUPPORTED_SAMPLE_RATE);
    CHECK(analyzer == nullptr);
    CHECK_EQ(sa_create(SA_MAX_SAMPLE_RATE + 1, &analyzer), SA_ERR_UNSUPPORTED_SAMPLE_RATE);
    CHECK_EQ(sa_create(44100, nullptr), SA_ERR_INVALID_ARGUMENT);

    CHECK_EQ(sa_create(44100, &analyzer), SA_OK);
    CHECK(analyzer != nullptr);
    CHECK_EQ(sa_process(analyzer, nullptr, 10), SA_ERR_INVALID_ARGUMENT);
    CHECK_EQ(sa_process(analyzer, nullptr, 0), SA_OK);
    CHECK_EQ(sa_process(nullptr, nullptr, 0), SA_ERR_INVALID_ARGUMENT);
    CHECK_EQ(sa_finish(analyzer, nullptr), SA_ERR_INVALID_ARGUMENT);
    sa_result result{};
    CHECK_EQ(sa_finish(analyzer, &result), SA_ERR_TOO_SHORT);
    const float sample = 0.0f;
    CHECK_EQ(sa_process(analyzer, &sample, 1), SA_ERR_STATE);
    CHECK_EQ(sa_finish(analyzer, &result), SA_ERR_STATE);
    sa_destroy(analyzer);
    sa_destroy(nullptr);
}

struct TestCase {
    const char* name;
    void (*run)();
};

const TestCase kTests[] = {
    {"round_bpm_within_range", round_bpm_within_range},
    {"grid_from_jittered_beats", grid_from_jittered_beats},
    {"click_128_bpm_44100", click_128_bpm_44100},
    {"click_128_bpm_48000", click_128_bpm_48000},
    {"click_100_bpm_c_major", click_100_bpm_c_major},
    {"click_128_bpm_22050", click_128_bpm_22050},
    {"block_size_does_not_change_the_result", block_size_does_not_change_the_result},
    {"too_short", too_short},
    {"silence_has_no_beats", silence_has_no_beats},
    {"api_misuse", api_misuse},
};

} // namespace

int main(int argc, char** argv) {
    int ran = 0;
    for (const TestCase& test : kTests) {
        if (argc > 1 && std::strcmp(argv[1], test.name) != 0) continue;
        const int before = g_failures;
        std::printf("[ RUN  ] %s\n", test.name);
        test.run();
        std::printf("[ %s ] %s\n", g_failures == before ? " OK " : "FAIL", test.name);
        ++ran;
    }
    if (ran == 0) {
        std::fprintf(stderr, "No test named %s\n", argc > 1 ? argv[1] : "(any)");
        return 2;
    }
    return g_failures == 0 ? 0 : 1;
}
