package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.RemovableVolume
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VolumeReadsTest {

    private val stick = RemovableVolume("2C9C-2151", "SONORA", 16_000_000_000, RemovableVolume.State.MOUNTED)

    @Test
    fun `a failed read of the volumes is not taken for no volume at all`() = runTest {
        val reads = ArrayDeque(listOf(listOf(stick), null, listOf(stick), listOf(stick.copy(state = RemovableVolume.State.UNMOUNTED))))

        val seen = flowOf(Unit, Unit, Unit, Unit).volumeReads { reads.removeFirst() }.toList()

        assertThat(seen).containsExactly(listOf(stick), listOf(stick.copy(state = RemovableVolume.State.UNMOUNTED))).inOrder()
    }

    @Test
    fun `a stick Android no longer lists after a good read is gone`() = runTest {
        val reads = ArrayDeque(listOf(listOf(stick), emptyList()))

        val seen = flowOf(Unit, Unit).volumeReads { reads.removeFirst() }.toList()

        assertThat(seen).containsExactly(listOf(stick), emptyList<RemovableVolume>()).inOrder()
    }
}
