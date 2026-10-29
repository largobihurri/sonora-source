package io.sonora.core.watchlink

import com.google.android.gms.tasks.Task
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Waits for a Play Services [Task] without blocking. A failed task (Play Services unavailable, the watch gone, the channel
 * closed) is an [IOException], which the sender treats as a dropped connection and retries.
 */
internal suspend fun <T> Task<T>.awaitIo(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(IOException(it.message ?: "Data Layer call failed", it)) }
    addOnCanceledListener { continuation.cancel() }
}
