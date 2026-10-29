# Sonora track analysis: vendored qm-dsp (Mixxx fork) + the Sonora analyser core.
#  - Android (AGP externalNativeBuild): libsonora_analysis.so with the JNI bridge.
#  - Host (plain cmake, used by the Gradle task :core:analysis:hostNativeTest): analyzer_test + ctest.
cmake_minimum_required(VERSION 3.22.1)
project(sonora_analysis LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# The DSP is always built optimised, also for debug variants (AGP builds those with -O0, which makes analysis
# several times slower). Never -ffast-math: qm-dsp relies on IEEE behaviour (NaN checks, denormals).
set(SONORA_DSP_OPTIONS -O2 -ffunction-sections -fdata-sections)

set(QMDSP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/qm-dsp)
add_library(qmdsp STATIC
    ${QMDSP_DIR}/base/Pitch.cpp
    ${QMDSP_DIR}/dsp/chromagram/Chromagram.cpp
    ${QMDSP_DIR}/dsp/chromagram/ConstantQ.cpp
    ${QMDSP_DIR}/dsp/keydetection/GetKeyMode.cpp
    ${QMDSP_DIR}/dsp/onsets/DetectionFunction.cpp
    ${QMDSP_DIR}/dsp/phasevocoder/PhaseVocoder.cpp
    ${QMDSP_DIR}/dsp/rateconversion/Decimator.cpp
    ${QMDSP_DIR}/dsp/tempotracking/DownBeat.cpp
    ${QMDSP_DIR}/dsp/tempotracking/TempoTrackV2.cpp
    ${QMDSP_DIR}/dsp/transforms/FFT.cpp
    ${QMDSP_DIR}/maths/KLDivergence.cpp
    ${QMDSP_DIR}/maths/MathUtilities.cpp
    ${QMDSP_DIR}/ext/kissfft/kiss_fft.c
    ${QMDSP_DIR}/ext/kissfft/tools/kiss_fftr.c
)
target_include_directories(qmdsp PUBLIC ${QMDSP_DIR})
# qm-dsp's FFT wrapper requires double-precision kissfft.
target_compile_definitions(qmdsp PRIVATE kiss_fft_scalar=double)
target_compile_options(qmdsp PRIVATE ${SONORA_DSP_OPTIONS})

add_library(sonora_analyzer_core STATIC
    sonora_analyzer.cpp
    const_beat_grid.cpp
)
target_include_directories(sonora_analyzer_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(sonora_analyzer_core PUBLIC qmdsp)
target_compile_options(sonora_analyzer_core PRIVATE ${SONORA_DSP_OPTIONS} -Wall -Wextra)

if(ANDROID)
    add_library(sonora_analysis SHARED jni_bridge.cpp)
    target_link_libraries(sonora_analysis PRIVATE sonora_analyzer_core)
    target_compile_options(sonora_analysis PRIVATE -Wall -Wextra)
    target_link_options(sonora_analysis PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
    enable_testing()
    set(SONORA_TEST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../test/cpp)
    add_executable(analyzer_test ${SONORA_TEST_DIR}/analyzer_test.cpp)
    target_link_libraries(analyzer_test PRIVATE sonora_analyzer_core)
    target_compile_options(analyzer_test PRIVATE -O2 -Wall -Wextra)
    foreach(test_name
            round_bpm_within_range
            grid_from_jittered_beats
            click_128_bpm_44100
            click_128_bpm_48000
            click_100_bpm_c_major
            click_128_bpm_22050
            block_size_does_not_change_the_result
            too_short
            silence_has_no_beats
            api_misuse)
        add_test(NAME ${test_name} COMMAND analyzer_test ${test_name})
    endforeach()
endif()
