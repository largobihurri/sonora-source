package io.sonora.player.export

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.sonora.core.domain.port.RekordboxExportRequester
import io.sonora.feature.cdjexport.ExportRequests
import javax.inject.Inject

/** The library's "Export as Rekordbox Playlist" hands its songs to the CDJ export as one pending playlist request. */
internal class CdjRekordboxExportRequester @Inject constructor(private val requests: ExportRequests) : RekordboxExportRequester {
    override fun request(name: String, trackUris: List<String>) {
        requests.request(name, trackUris)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface RekordboxExportModule {
    @Binds
    fun rekordboxExportRequester(impl: CdjRekordboxExportRequester): RekordboxExportRequester
}
