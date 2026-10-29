package io.sonora.core.beatmodel

import android.content.res.AssetManager
import java.io.IOException
import java.io.InputStream

/** Opens the bytes of the model file. */
fun interface BeatModelSource {

    /** A new stream over the model file; the caller closes it. @throws IOException if the file is missing or unreadable. */
    @Throws(IOException::class)
    fun open(): InputStream
}

/** The model as an app asset (`FileNotFoundException`, an `IOException`, when the build did not package it). */
class AssetBeatModelSource(private val assets: AssetManager, private val path: String) : BeatModelSource {
    override fun open(): InputStream = assets.open(path)
}

/** Where the factory reports why it has no model. */
fun interface BeatModelLog {
    fun warn(message: String, cause: Throwable?)
}

/** Logcat, tag [TAG] (ColorOS hides it unless `adb shell setprop log.tag.SonoraBeatModel VERBOSE`). */
object AndroidBeatModelLog : BeatModelLog {
    const val TAG = "SonoraBeatModel"

    override fun warn(message: String, cause: Throwable?) {
        android.util.Log.w(TAG, message, cause)
    }
}
