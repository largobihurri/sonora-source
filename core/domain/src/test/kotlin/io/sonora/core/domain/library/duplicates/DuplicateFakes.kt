package io.sonora.core.domain.library.duplicates

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeDuplicateLibrary(var files: List<DuplicateCopy>) : DuplicateLibrary {
    var fails = false

    override suspend fun copies(): List<DuplicateCopy> {
        if (fails) error("database closed")
        return files
    }
}

/** Hashes a file to its [contents] entry; files without one cannot be read. */
internal class FakeHasher(val contents: MutableMap<String, String> = mutableMapOf()) : ContentHasher {
    val hashed = mutableListOf<String>()

    /** Hashing [gated] waits for [gate]. */
    var gated: String? = null
    val gate = CompletableDeferred<Unit>()

    override suspend fun hash(uri: String): String? {
        if (uri == gated) gate.await()
        hashed += uri
        if (uri.endsWith("/throws")) error("provider crashed")
        return contents[uri]
    }
}

internal class FakeFingerprints : DuplicateFingerprints {
    val stored = linkedMapOf<String, DuplicateFingerprint>()
    var updates = 0

    override suspend fun all(): List<DuplicateFingerprint> = stored.values.toList()

    override suspend fun update(fingerprints: List<DuplicateFingerprint>, gone: Collection<String>) {
        updates++
        gone.forEach(stored::remove)
        fingerprints.forEach { stored[it.uri] = it }
    }
}

internal class FakeDismissals : DuplicateDismissals {
    val sets = mutableListOf<Set<String>>()

    override suspend fun dismissed(): List<Set<String>> = sets.toList()

    override suspend fun dismiss(uris: Set<String>) {
        sets += uris
    }
}

internal class FakeAlerts(enabled: Boolean = true, var last: Int = 0) : DuplicateAlerts {
    override val enabled = MutableStateFlow(enabled)

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun lastNotifiedCount(): Int = last

    override suspend fun setLastNotifiedCount(count: Int) {
        last = count
    }
}

internal class FakeNotifier(var allowed: Boolean = true) : DuplicateNotifier {
    val posted = mutableListOf<Int>()

    override fun notify(groups: Int): Boolean {
        if (allowed) posted += groups
        return allowed
    }
}

internal class FakeFirstRun(var done: Boolean = false) : DuplicateFirstRun {
    override suspend fun done(): Boolean = done

    override suspend fun markDone() {
        done = true
    }
}

internal class FakeWork : DuplicateWork {
    var periodic = 0
    var catchUps = 0

    override fun schedulePeriodic() {
        periodic++
    }

    override fun catchUp() {
        catchUps++
    }
}
