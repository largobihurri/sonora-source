# Sonora MP3 encoder: vendored LAME 3.100 libmp3lame (lame/README.vendor.md) + the JNI bridge, as libsonora_mp3.so.
cmake_minimum_required(VERSION 3.22.1)
project(sonora_mp3 LANGUAGES C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)

set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lame)
add_library(mp3lame STATIC
    ${LAME_DIR}/libmp3lame/bitstream.c
    ${LAME_DIR}/libmp3lame/encoder.c
    ${LAME_DIR}/libmp3lame/fft.c
    ${LAME_DIR}/libmp3lame/gain_analysis.c
    ${LAME_DIR}/libmp3lame/id3tag.c
    ${LAME_DIR}/libmp3lame/lame.c
    ${LAME_DIR}/libmp3lame/mpglib_interface.c
    ${LAME_DIR}/libmp3lame/newmdct.c
    ${LAME_DIR}/libmp3lame/presets.c
    ${LAME_DIR}/libmp3lame/psymodel.c
    ${LAME_DIR}/libmp3lame/quantize.c
    ${LAME_DIR}/libmp3lame/quantize_pvt.c
    ${LAME_DIR}/libmp3lame/reservoir.c
    ${LAME_DIR}/libmp3lame/set_get.c
    ${LAME_DIR}/libmp3lame/tables.c
    ${LAME_DIR}/libmp3lame/takehiro.c
    ${LAME_DIR}/libmp3lame/util.c
    ${LAME_DIR}/libmp3lame/vbrquantize.c
    ${LAME_DIR}/libmp3lame/VbrTag.c
    ${LAME_DIR}/libmp3lame/version.c
)
# config.h (hand-written for the NDK) sits in ${LAME_DIR}; the sources include it as <config.h>.
target_include_directories(mp3lame PRIVATE ${LAME_DIR} ${LAME_DIR}/libmp3lame PUBLIC ${LAME_DIR}/include)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H NDEBUG)
# Always optimised, also for debug variants (AGP builds those with -O0, which makes encoding several times slower);
# NDEBUG drops LAME's internal asserts in every variant alike. Upstream code: its warnings are not ours to fix.
target_compile_options(mp3lame PRIVATE -O2 -ffunction-sections -fdata-sections -w)
target_link_libraries(mp3lame PRIVATE m)

add_library(sonora_mp3 SHARED lame_jni.c)
target_link_libraries(sonora_mp3 PRIVATE mp3lame)
target_compile_options(sonora_mp3 PRIVATE -O2 -Wall -Wextra)
target_link_options(sonora_mp3 PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
