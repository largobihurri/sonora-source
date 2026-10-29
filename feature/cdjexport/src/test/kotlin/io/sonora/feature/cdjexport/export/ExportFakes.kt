package io.sonora.feature.cdjexport.export

import io.sonora.core.domain.analysis.AnalysisCache
import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.domain.analysis.CachedTrackAnalyzer
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.analysis.TrackAnalysis
import io.sonora.core.domain.analysis.TrackAnalyzer
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.DeviceArtworkImages
import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.ExportArtwork
import io.sonora.core.domain.export.ExportSourceReader
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.domain.export.SourceTags
import io.sonora.core.domain.export.StickEntry
import io.sonora.core.domain.export.StickFileSystem
import io.sonora.core.domain.export.StickIoException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * An in-memory stick. Names compare without regard to case, as on FAT. [log] records every change in order
 * ("write <path>", "replace <from> -> <to>", "delete <path>"); [failWrites] makes writes to matching paths fail like a
 * pulled stick; [onOpenWrite] runs before a write starts (to cancel an export at a chosen point); [onReplace] runs
 * with the target before a replace moves anything (to fail one, or to delete the target first like a replace that
 * stopped half-way).
 */
class FakeStickFileSystem(var free: Long? = 64L shl 30) : StickFileSystem {
    private val files = LinkedHashMap<String, Pair<String, ByteArray>>()
    val log = mutableListOf<String>()
    var failWrites: (String) -> Boolean = { false }
    var onOpenWrite: (String) -> Unit = {}
    var onReplace: (String) -> Unit = {}

    /** Every file, by its path as written, in creation order. */
    val paths: List<String> get() = files.values.map { it.first }

    fun put(path: String, bytes: ByteArray) {
        files[key(path)] = path to bytes
    }

    fun bytes(path: String): ByteArray? = files[key(path)]?.second

    /** A copy of every file, to compare a stick before and after. */
    fun snapshot(): Map<String, List<Byte>> = files.values.associate { (path, bytes) -> path to bytes.toList() }

    override fun freeBytes(): Long? = free

    override fun exists(path: String): Boolean = key(path) in files

    override fun sizeOf(path: String): Long? = files[key(path)]?.second?.size?.toLong()

    override fun read(path: String): ByteArray? = files[key(path)]?.second?.copyOf()

    override fun openWrite(path: String): OutputStream {
        onOpenWrite(path)
        if (failWrites(path)) throw StickIoException("Stick removed while opening $path")
        log += "write $path"
        val existing = files[key(path)]?.first ?: path
        files[key(path)] = existing to ByteArray(0)
        return object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWrites(path)) throw IOException("Stick removed while writing $path")
                super.write(b, off, len)
                files[key(path)] = existing to toByteArray()
            }
        }
    }

    override fun replace(source: String, target: String) {
        onReplace(target)
        val moved = files.remove(key(source)) ?: throw StickIoException("No $source")
        log += "replace $source -> $target"
        files.remove(key(target))
        files[key(target)] = target to moved.second
    }

    override fun delete(path: String): Boolean {
        log += "delete $path"
        if (files.remove(key(path)) != null) return true
        // A folder: everything under it, and the empty folders too.
        val prefix = key(path).trimEnd('/') + "/"
        val inside = files.keys.filter { it.startsWith(prefix) }
        val emptyInside = emptyFolders.filter { key(it) == key(path) || key(it).startsWith(prefix) }
        inside.forEach { files.remove(it) }
        emptyFolders.removeAll(emptyInside.toSet())
        return inside.isNotEmpty() || emptyInside.isNotEmpty()
    }

    /** Folders with nothing in them (the others exist through the files they hold). */
    val emptyFolders = mutableListOf<String>()

    override fun list(path: String): List<StickEntry>? {
        val prefix = if (path.isEmpty()) "" else key(path).trimEnd('/') + "/"
        val entries = LinkedHashMap<String, StickEntry>()
        val all = files.values.map { it.first } + emptyFolders.map { "$it/" }
        for (item in all) {
            if (!key(item).startsWith(prefix) || key(item) == prefix) continue
            val rest = item.substring(prefix.length)
            val name = rest.substringBefore('/')
            entries.getOrPut(key(name)) { StickEntry(name, isFolder = '/' in rest) }
        }
        return if (entries.isEmpty() && path.isNotEmpty() && emptyFolders.none { key(it) == key(path) }) null else entries.values.toList()
    }

    private fun key(path: String) = path.uppercase(Locale.ROOT)
}

/**
 * One picked file: its bytes and tags, whether reading it fails part-way, and [changedBytes] it holds from its second
 * opening on (a file edited while it is being exported).
 */
class FakeSource(
    val file: SourceFile,
    val bytes: ByteArray,
    val tags: SourceTags = SourceTags.NONE,
    val failReadAfter: Int? = null,
    val changedBytes: ByteArray? = null,
) {
    var opened = 0
}

class FakeSources : ExportSourceReader {
    val files = LinkedHashMap<String, FakeSource>()
    val folders = mutableMapOf<String, List<String>>()
    val unreadable = mutableSetOf<String>()

    fun add(
        name: String,
        bytes: ByteArray = name.toByteArray() + ByteArray(1000) { it.toByte() },
        tags: SourceTags = SourceTags.NONE,
        audio: SourceAudio = AIFF_16,
        uri: String = "content://picked/$name",
        failReadAfter: Int? = null,
        changedBytes: ByteArray? = null,
    ): SourceFile {
        val file = SourceFile(uri, name, bytes.size.toLong(), audio)
        files[uri] = FakeSource(file, bytes, tags, failReadAfter, changedBytes)
        return file
    }

    override suspend fun listAudioFiles(treeUri: String): List<String> =
        folders[treeUri] ?: throw IOException("No folder $treeUri")

    override suspend fun inspect(uri: String): SourceFile {
        if (uri in unreadable) throw IOException("Cannot read $uri")
        return files[uri]?.file ?: throw IOException("No file $uri")
    }

    /** The URIs whose tags were read, in order. */
    val tagReads = mutableListOf<String>()

    /** URIs whose tags cannot be read for now (the document cannot be opened): [readTags] throws, as its port says. */
    val tagsUnreadable = mutableSetOf<String>()

    override suspend fun readTags(uri: String): SourceTags {
        tagReads += uri
        if (uri in tagsUnreadable) throw IOException("Cannot open $uri for now")
        return files[uri]?.tags ?: SourceTags.NONE
    }

    override fun openInput(uri: String): InputStream {
        val source = files[uri] ?: throw IOException("No file $uri")
        val bytes = source.changedBytes?.takeIf { source.opened > 0 } ?: source.bytes
        source.opened++
        val limit = source.failReadAfter ?: return ByteArrayInputStream(bytes)
        return object : InputStream() {
            private var position = 0

            override fun read(): Int {
                if (position >= limit) throw IOException("Source went away")
                return if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1
            }
        }
    }

    companion object {
        val AIFF_16 = SourceAudio(AudioContainer.AIFF, bitsPerSample = 16, sampleRateHz = 44_100, channelCount = 2)
    }
}

/**
 * Converts by adding a plain AIFF 16-bit file to [sources] (its bytes derived from the original's, so converting the
 * same file twice gives the same bytes, as the real engine's seeded dither does). [failures] makes a source fail like an
 * undecodable file; [onConvert] runs first (to cancel at a chosen point). [open] holds the temporary files not discarded.
 */
class FakeConverter(private val sources: FakeSources) : ExportTrackConverter {
    val converted = mutableListOf<String>()
    val failures = mutableMapOf<String, Exception>()
    val open = mutableSetOf<String>()
    var leftoversDiscarded = 0
    var onConvert: (SourceFile) -> Unit = {}
    private var next = 0

    override suspend fun convert(source: SourceFile, profile: DeviceProfile, onProgress: (Float) -> Unit): ConvertedTrack {
        onConvert(source)
        failures[source.uri]?.let { throw it }
        val original = sources.files.getValue(source.uri).bytes
        val name = source.fileName.substringBeforeLast('.') + ".aiff"
        val uri = "file:///cache/export-conversions/${next++}.aiff"
        val file = sources.add(name, bytes = "AIFF".toByteArray() + original, audio = FakeSources.AIFF_16, uri = uri)
        converted += source.uri
        open += uri
        onProgress(0.5f)
        onProgress(1f)
        return ConvertedTrack(file) { open -= uri }
    }

    override fun discardLeftovers() {
        leftoversDiscarded++
    }
}

/**
 * Pictures without image codecs: a picture's device images are its bytes behind "S" (small) and "L" (large), so the
 * same picture always gives the same images; [undecodable] pictures give none. [libraryPictures] are the folder images
 * the library holds, by document URI.
 */
class FakeArtwork : ExportArtwork {
    val libraryPictures = mutableMapOf<String, ByteArray>()
    val undecodable = mutableSetOf<List<Byte>>()

    override suspend fun libraryPicture(documentUri: String): ByteArray? = libraryPictures[documentUri]

    override fun deviceImages(picture: ByteArray): DeviceArtworkImages? =
        if (picture.toList() in undecodable) null else DeviceArtworkImages("S".toByteArray() + picture, "L".toByteArray() + picture)
}

/** Analyses every track as 128 BPM in A minor over 10 s, unless told to fail for a URI. */
class FakeAnalyzer : TrackAnalyzer {
    val failures = mutableMapOf<String, Exception>()
    val analysed = mutableListOf<String>()

    override suspend fun analyze(sourceUri: String, onProgress: (Float) -> Unit): TrackAnalysis {
        failures[sourceUri]?.let { throw it }
        analysed += sourceUri
        onProgress(0.5f)
        onProgress(1f)
        return ANALYSIS
    }

    companion object {
        val ANALYSIS = TrackAnalysis(
            beatGrid = BeatGrid(bpm = 128.0, firstBeatMs = 0.0, firstBeatInBar = 1, beatCount = 21),
            key = MusicalKey.A_MINOR,
            waveform = BlueWaveform(
                detail = ByteArray(1500) { BlueWaveform.packColumn(it % 32, it % 8) },
                preview = ByteArray(BlueWaveform.PREVIEW_COLUMNS) { BlueWaveform.packColumn(it % 32, 3) },
                tinyPreview = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS) { (it % 16).toByte() },
            ),
            durationMs = 10_000,
            analyzerVersion = "fake/1",
        )

        val NO_BEATS = AnalysisException.NoBeatsDetected()
    }
}

/** An in-memory analysis cache; [broken] makes every call fail like an unusable database. */
class FakeAnalysisCache : AnalysisCache {
    val entries = mutableMapOf<Pair<String, String>, TrackAnalysis>()
    var broken = false

    override suspend fun get(contentSha256: String, analyzerVersion: String): TrackAnalysis? {
        if (broken) throw IllegalStateException("cache broken")
        return entries[contentSha256 to analyzerVersion]
    }

    override suspend fun put(contentSha256: String, analysis: TrackAnalysis) {
        if (broken) throw IllegalStateException("cache broken")
        entries[contentSha256 to analysis.analyzerVersion] = analysis
    }

    override suspend fun remove(contentSha256: String, analyzerVersion: String) {
        if (broken) throw IllegalStateException("cache broken")
        entries.remove(contentSha256 to analyzerVersion)
    }

    override suspend fun prune(keepAnalyzerVersion: String) {
        if (broken) throw IllegalStateException("cache broken")
        entries.keys.removeAll { it.second != keepAnalyzerVersion }
    }
}

/** This analyser behind [cache] (a fresh one by default), keyed by [FakeAnalyzer.ANALYSIS]'s version. */
fun TrackAnalyzer.cached(cache: AnalysisCache = FakeAnalysisCache()): CachedTrackAnalyzer =
    CachedTrackAnalyzer(this, cache, FakeAnalyzer.ANALYSIS.analyzerVersion)

/**
 * Records what was kept and given up. [readFailure] makes [takeRead] throw for a URI; [readWriteAnswer] answers
 * [takeReadWrite] (or throws).
 */
class FakeUriPermissions : UriPermissions {
    val read = mutableListOf<String>()
    val readWrite = mutableListOf<String>()
    val released = mutableListOf<String>()
    var leftoverReleases = 0
    var readFailure: (String) -> Exception? = { null }
    var readWriteAnswer: (String) -> Boolean = { true }

    /** Trees whose read and write access the app holds; a granted [takeReadWrite] adds its tree. */
    val heldTrees = mutableSetOf<String>()

    /** Read grants taken and not given up yet. */
    val kept: Set<String> get() = read.toSet() - released.toSet()

    override fun takeRead(uri: String): Boolean {
        readFailure(uri)?.let { throw it }
        released -= uri
        read += uri
        return true
    }

    override fun takeReadWrite(treeUri: String): Boolean = readWriteAnswer(treeUri).also {
        if (it) {
            readWrite += treeUri
            heldTrees += treeUri
        }
    }

    override fun heldReadWriteTrees(): Set<String> = heldTrees.toSet()

    override fun release(uri: String) {
        released += uri
    }

    override fun releaseLeftovers() {
        leftoverReleases++
    }
}
