/*
 * JNI bridge for io.sonora.core.analysis.NativeBridge. Copyright (C) 2026 Sonora contributors. GPL-3.0-or-later.
 *
 * Handles are sa_analyzer pointers stored in a jlong. Every function returns an SA_* status; the Kotlin side maps
 * failures to AnalysisException.
 */
#include <jni.h>

#include "sonora_analyzer.h"

namespace {

// Layout of the result array, mirrored by NativeResults.kt.
enum ResultIndex {
    kBpm = 0,
    kFirstBeatFrame = 1,
    kFirstBeatInBar = 2,
    kKey = 3,
    kTotalFrames = 4,
    kDetectedBeats = 5,
    kResultSize = 6,
};

sa_analyzer* fromHandle(jlong handle) { return reinterpret_cast<sa_analyzer*>(handle); }

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_sonora_core_analysis_NativeBridge_create(JNIEnv* env, jclass, jint sampleRate, jlongArray handleOut) {
    if (handleOut == nullptr || env->GetArrayLength(handleOut) < 1) return SA_ERR_INVALID_ARGUMENT;
    sa_analyzer* analyzer = nullptr;
    const int status = sa_create(sampleRate, &analyzer);
    if (status != SA_OK) return status;
    const jlong handle = reinterpret_cast<jlong>(analyzer);
    env->SetLongArrayRegion(handleOut, 0, 1, &handle);
    return SA_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_sonora_core_analysis_NativeBridge_process(JNIEnv* env, jclass, jlong handle, jfloatArray samples,
                                                  jint count) {
    if (handle == 0 || samples == nullptr || count < 0 || count > env->GetArrayLength(samples)) {
        return SA_ERR_INVALID_ARGUMENT;
    }
    if (count == 0) return SA_OK;
    // No JNI calls between Get/ReleasePrimitiveArrayCritical; sa_process only computes.
    auto* data = static_cast<float*>(env->GetPrimitiveArrayCritical(samples, nullptr));
    if (data == nullptr) return SA_ERR_OUT_OF_MEMORY;
    const int status = sa_process(fromHandle(handle), data, static_cast<size_t>(count));
    env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
    return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_sonora_core_analysis_NativeBridge_finish(JNIEnv* env, jclass, jlong handle, jdoubleArray resultOut) {
    if (handle == 0 || resultOut == nullptr || env->GetArrayLength(resultOut) < kResultSize) {
        return SA_ERR_INVALID_ARGUMENT;
    }
    sa_result result{};
    const int status = sa_finish(fromHandle(handle), &result);
    if (status != SA_OK) return status;
    jdouble values[kResultSize];
    values[kBpm] = result.bpm;
    values[kFirstBeatFrame] = result.first_beat_frame;
    values[kFirstBeatInBar] = result.first_beat_in_bar;
    values[kKey] = result.key;
    values[kTotalFrames] = static_cast<jdouble>(result.total_frames);
    values[kDetectedBeats] = result.detected_beats;
    env->SetDoubleArrayRegion(resultOut, 0, kResultSize, values);
    return SA_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_io_sonora_core_analysis_NativeBridge_destroy(JNIEnv*, jclass, jlong handle) {
    sa_destroy(fromHandle(handle));
}
