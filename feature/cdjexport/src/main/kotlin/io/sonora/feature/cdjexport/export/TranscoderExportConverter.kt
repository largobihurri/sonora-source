package io.sonora.feature.cdjexport.export

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.SourceAudioProbe
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.model.ConversionRequest
import io.sonora.core.domain.port.AudioConverter
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Converts with the app's converter engine ([AudioConverter], the one behind the Converter screen, so a file converted
 * here is the same as one converted there) into the app's cache, under [FOLDER]: a `file://` document the export reads
 * like a picked one. The result is checked against the player's profile before it is handed out.
 */
class TranscoderExportConverter internal constructor(
    private val converter: AudioConverter,
    /** Where the conversions go (made when needed). */
    private val folderOf: () -> File,
    /** The document URI of a file there. */
    private val uriOf: (File) -> String,
) : ExportTrackConverter {

    @Inject
    constructor(converter: AudioConverter, @ApplicationContext context: Context) :
        this(converter, { File(context.cacheDir, FOLDER) }, { Uri.fromFile(it).toString() })

    private val folder: File get() = folderOf()

    override suspend fun convert(
        source: SourceFile,
        profile: DeviceProfile,
        onProgress: (Float) -> Unit,
    ): ConvertedTrack = withContext(Dispatchers.IO) {
        val conversion = ExportConversion.conversionProfile(profile)
        val output = File.createTempFile("export-", ".${conversion.fileExtension}", folder.apply { mkdirs() })
        try {
            val uri = uriOf(output)
            converter.convert(ConversionRequest(source.uri, uri, conversion), onProgress)
            val name = conversion.outputFileName(source.fileName)
            val audio = SourceAudioProbe.probe(name, conversion.container.mimeType) { output.inputStream() }
            if (profile.check(audio) != null) {
                throw IOException("The conversion of ${source.fileName} is not what the ${profile.displayName} takes: $audio")
            }
            ConvertedTrack(SourceFile(uri, name, output.length(), audio)) { output.delete() }
        } catch (e: Throwable) {
            // Failed or cancelled: nothing of it stays.
            output.delete()
            throw e
        }
    }

    override fun discardLeftovers() {
        folder.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val FOLDER = "export-conversions"
    }
}
