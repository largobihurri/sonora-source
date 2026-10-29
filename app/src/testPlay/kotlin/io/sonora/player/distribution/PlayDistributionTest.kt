package io.sonora.player.distribution

import io.sonora.player.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The `play` build is GPL code sold on Google Play: no proprietary Google Play services may be linked into it. */
class PlayDistributionTest {

    @Test
    fun `the play build carries neither the Cast framework nor the Wearable Data Layer`() {
        val proprietary = listOf(
            "com.google.android.gms.cast.framework.CastContext",
            "com.google.android.gms.wearable.Wearable",
            "com.google.android.gms.common.GoogleApiAvailability",
            "androidx.media3.cast.CastPlayer",
            "io.sonora.core.cast.CastRouting",
            "io.sonora.core.watchphone.DefaultWatchSyncController",
        )
        for (name in proprietary) assertFalse("$name is on the play build's classpath", isOnClasspath(name))
    }

    @Test
    fun `the play build keeps the application id Google Play knows`() {
        assertEquals("io.sonora.player", BuildConfig.APPLICATION_ID)
    }

    private fun isOnClasspath(name: String): Boolean =
        runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
}
