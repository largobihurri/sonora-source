/*
 * JNI bridge for io.sonora.core.mp3.LameJni. Copyright (C) 2026 Sonora contributors. GPL-3.0-or-later.
 *
 * Handles are lame_t pointers stored in a jlong. Encode and flush return the bytes written or a negative status
 * (LAME's own, or the STATUS_* below, mirrored by LameBridge.kt); Mp3Encoder validates arguments before calling, so
 * these checks only guard the native side.
 */
#include <jni.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

#include "lame.h"

enum {
    STATUS_OK = 0,
    STATUS_INVALID_ARGUMENT = -100,
    STATUS_OUT_OF_MEMORY = -101,
};

static lame_t fromHandle(jlong handle) { return (lame_t) (intptr_t) handle; }

/* LAME prints its messages to stderr by default; the app has no use for them. */
static void silent(const char *format, va_list args) {
    (void) format;
    (void) args;
}

JNIEXPORT jint JNICALL
Java_io_sonora_core_mp3_LameJni_create(JNIEnv *env, jobject self, jint sampleRate, jint channels, jint cbrKbps,
                                       jint vbrQuality, jlongArray handleOut) {
    (void) self;
    if (handleOut == NULL || (*env)->GetArrayLength(env, handleOut) < 1) return STATUS_INVALID_ARGUMENT;
    if (channels < 1 || channels > 2 || vbrQuality > 9 || (vbrQuality < 0 && cbrKbps <= 0)) {
        return STATUS_INVALID_ARGUMENT;
    }
    lame_t lame = lame_init();
    if (lame == NULL) return STATUS_OUT_OF_MEMORY;
    lame_set_errorf(lame, silent);
    lame_set_debugf(lame, silent);
    lame_set_msgf(lame, silent);

    lame_set_in_samplerate(lame, sampleRate);
    /* Keep the input rate: LAME would otherwise pick a lower one for low bitrates. */
    lame_set_out_samplerate(lame, sampleRate);
    lame_set_num_channels(lame, channels);
    lame_set_mode(lame, channels == 1 ? MONO : JOINT_STEREO);
    if (vbrQuality < 0) {
        lame_set_VBR(lame, vbr_off);
        lame_set_brate(lame, cbrKbps);
    } else {
        lame_set_VBR(lame, vbr_default);
        lame_set_VBR_quality(lame, (float) vbrQuality);
    }
    /* Sonora writes the tags itself; the LAME/Xing info frame stays (placeholder now, lametagFrame after flush). */
    lame_set_write_id3tag_automatic(lame, 0);
    lame_set_bWriteVbrTag(lame, 1);

    const int status = lame_init_params(lame);
    if (status < 0) {
        lame_close(lame);
        return status;
    }
    /* lame_init_params moves a CBR bitrate its MPEG version lacks to the nearest one without failing: refuse it. */
    if (vbrQuality < 0 && lame_get_brate(lame) != cbrKbps) {
        lame_close(lame);
        return STATUS_INVALID_ARGUMENT;
    }
    const jlong handle = (jlong) (intptr_t) lame;
    (*env)->SetLongArrayRegion(env, handleOut, 0, 1, &handle);
    return STATUS_OK;
}

/* Encodes interleaved PCM of either type; isFloat picks LAME's entry point. */
static jint encode(JNIEnv *env, jlong handle, jarray pcm, jint frames, jbyteArray out, int isFloat) {
    if (handle == 0 || pcm == NULL || out == NULL || frames < 0) return STATUS_INVALID_ARGUMENT;
    lame_t lame = fromHandle(handle);
    const int channels = lame_get_num_channels(lame);
    if ((jlong) frames * channels > (*env)->GetArrayLength(env, pcm)) return STATUS_INVALID_ARGUMENT;
    if (frames == 0) return 0;
    const jsize capacity = (*env)->GetArrayLength(env, out);
    // No JNI calls between Get/ReleasePrimitiveArrayCritical; LAME only computes.
    void *samples = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (samples == NULL) return STATUS_OUT_OF_MEMORY;
    unsigned char *bytes = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (bytes == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, pcm, samples, JNI_ABORT);
        return STATUS_OUT_OF_MEMORY;
    }
    int written;
    if (isFloat) {
        const float *f = samples;
        written = channels == 1
                  ? lame_encode_buffer_ieee_float(lame, f, NULL, frames, bytes, capacity)
                  : lame_encode_buffer_interleaved_ieee_float(lame, f, frames, bytes, capacity);
    } else {
        short *s = samples;
        written = channels == 1
                  ? lame_encode_buffer(lame, s, NULL, frames, bytes, capacity)
                  : lame_encode_buffer_interleaved(lame, s, frames, bytes, capacity);
    }
    (*env)->ReleasePrimitiveArrayCritical(env, out, bytes, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, samples, JNI_ABORT);
    return written;
}

JNIEXPORT jint JNICALL
Java_io_sonora_core_mp3_LameJni_encodeShorts(JNIEnv *env, jobject self, jlong handle, jshortArray pcm, jint frames,
                                             jbyteArray out) {
    (void) self;
    return encode(env, handle, pcm, frames, out, 0);
}

JNIEXPORT jint JNICALL
Java_io_sonora_core_mp3_LameJni_encodeFloats(JNIEnv *env, jobject self, jlong handle, jfloatArray pcm, jint frames,
                                             jbyteArray out) {
    (void) self;
    return encode(env, handle, pcm, frames, out, 1);
}

JNIEXPORT jint JNICALL
Java_io_sonora_core_mp3_LameJni_flush(JNIEnv *env, jobject self, jlong handle, jbyteArray out) {
    (void) self;
    if (handle == 0 || out == NULL) return STATUS_INVALID_ARGUMENT;
    const jsize capacity = (*env)->GetArrayLength(env, out);
    unsigned char *bytes = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (bytes == NULL) return STATUS_OUT_OF_MEMORY;
    const int written = lame_encode_flush(fromHandle(handle), bytes, capacity);
    (*env)->ReleasePrimitiveArrayCritical(env, out, bytes, 0);
    return written;
}

JNIEXPORT jint JNICALL
Java_io_sonora_core_mp3_LameJni_lametagFrame(JNIEnv *env, jobject self, jlong handle, jbyteArray out) {
    (void) self;
    if (handle == 0 || out == NULL) return STATUS_INVALID_ARGUMENT;
    lame_t lame = fromHandle(handle);
    /* With no room LAME returns the frame's size (0 when it writes none) and copies nothing. */
    const size_t size = lame_get_lametag_frame(lame, NULL, 0);
    const jsize capacity = (*env)->GetArrayLength(env, out);
    if (size == 0 || (size_t) capacity < size) return (jint) size;
    unsigned char *bytes = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (bytes == NULL) return STATUS_OUT_OF_MEMORY;
    const size_t written = lame_get_lametag_frame(lame, bytes, (size_t) capacity);
    (*env)->ReleasePrimitiveArrayCritical(env, out, bytes, 0);
    return (jint) written;
}

JNIEXPORT void JNICALL
Java_io_sonora_core_mp3_LameJni_destroy(JNIEnv *env, jobject self, jlong handle) {
    (void) env;
    (void) self;
    if (handle != 0) lame_close(fromHandle(handle));
}
