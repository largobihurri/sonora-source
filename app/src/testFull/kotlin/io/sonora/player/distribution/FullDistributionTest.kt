package io.sonora.player.distribution

import io.sonora.player.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `full` build installs next to the Google Play one: its own application id, so its own library and settings. */
class FullDistributionTest {

    @Test
    fun `the full build has its own application id`() {
        assertEquals("io.sonora.player.full", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `the full build keeps DUB mode, to go on testing it`() {
        assertTrue(FullDubModule.provideDubAvailability().offered)
    }
}
