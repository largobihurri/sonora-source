package io.sonora.core.data.archive.work

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The passwords of running archive jobs, by job id, in this process's memory only. WorkManager persists a job's input
 * data in its database, so a password must never go there; it lives here instead, and is lost with the process.
 * Removing one wipes its characters.
 */
@Singleton
class ArchivePasswords @Inject constructor() {

    private val passwords = ConcurrentHashMap<String, CharArray>()

    /** Holds [password] (the array itself, not a copy) for [jobId], wiping any one held before. */
    fun put(jobId: String, password: CharArray) {
        passwords.put(jobId, password)?.takeIf { it !== password }?.fill('\u0000')
    }

    fun get(jobId: String): CharArray? = passwords[jobId]

    /** Forgets [jobId]'s password and wipes it. */
    fun remove(jobId: String) {
        passwords.remove(jobId)?.fill('\u0000')
    }
}
