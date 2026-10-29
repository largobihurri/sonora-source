package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.conversion.ConversionBudget
import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.BatchCounts
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.ConversionRequest
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.domain.port.AudioConverter
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionBudgetSource
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.OutputRootFolder
import io.sonora.core.domain.port.SourceFolderAccess
import io.sonora.core.domain.port.StoredDocument
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * In-memory shared storage. Like FAT, names are unique per folder ignoring case; unlike real providers it refuses a
 * taken name instead of adjusting it, so naming bugs fail loudly. Renames give the document a new URI.
 */
class FakeFiles : ConversionFiles {
    class Node(var name: String, val parent: String?, val isFolder: Boolean, var bytes: ByteArray = ByteArray(0))

    val nodes = linkedMapOf<String, Node>()
    var renameSupported = true
    val undeletable = mutableSetOf<String>()
    /** Folders where copies fail as if the disk were full. */
    val failCopyInto = mutableSetOf<String>()
    /** Documents the storage deletes but reports as not deleted. */
    val deleteReportsFailure = mutableSetOf<String>()
    var renameFailure: Exception? = null
    /** Called before each delete, e.g. to cancel the caller while it deletes an original. */
    var onDelete: (String) -> Unit = {}
    private var nextId = 0

    fun folder(name: String, parent: String? = null): String = add(Node(name, parent, isFolder = true))

    fun file(parent: String, name: String, content: String): String = add(Node(name, parent, false, content.toByteArray()))

    fun names(folder: String): List<String> = nodes.values.filter { it.parent == folder }.map { it.name }

    fun content(uri: String): String? = nodes[uri]?.bytes?.let(::String)

    fun uriOf(parent: String, name: String): String? = nodes.entries.firstOrNull { it.value.parent == parent && it.value.name == name }?.key

    private fun add(node: Node): String {
        // FAT and exFAT allow 255 UTF-16 units per name, ext4 and F2FS 255 UTF-8 bytes.
        check(node.name.length <= 255 && node.name.toByteArray(Charsets.UTF_8).size <= 255) { "Name too long: ${node.name}" }
        node.parent?.let { parent ->
            check(nodes[parent]?.isFolder == true) { "No folder $parent" }
            check(names(parent).none { it.equals(node.name, ignoreCase = true) }) { "${node.name} already exists in $parent" }
        }
        val uri = "doc:${nextId++}"
        nodes[uri] = node
        return uri
    }

    private fun doc(uri: String) = nodes.getValue(uri).let { StoredDocument(uri, it.name, it.isFolder) }

    override suspend fun children(folderUri: String): List<StoredDocument> {
        if (nodes[folderUri]?.isFolder != true) throw FileNotFoundException(folderUri)
        return nodes.filterValues { it.parent == folderUri }.keys.map(::doc)
    }

    override suspend fun findOrCreateFolder(parentUri: String, name: String): StoredDocument =
        children(parentUri).firstOrNull { it.isFolder && it.name.equals(name, ignoreCase = true) }
            ?: createFolder(parentUri, name)

    override suspend fun createFolder(parentUri: String, name: String) = doc(folder(name, parentUri))

    override suspend fun createFile(folderUri: String, name: String, mimeType: String) = doc(file(folderUri, name, ""))

    override suspend fun rename(documentUri: String, name: String): StoredDocument? {
        if (!renameSupported) return null
        renameFailure?.let { throw it }
        val node = nodes.remove(documentUri) ?: throw FileNotFoundException(documentUri)
        node.name = name
        return doc(add(node))
    }

    override suspend fun copy(fromUri: String, toUri: String) {
        val bytes = nodes[fromUri]?.bytes ?: throw FileNotFoundException(fromUri)
        val target = nodes[toUri] ?: throw FileNotFoundException(toUri)
        if (target.parent in failCopyInto) throw IOException("Disk full")
        target.bytes = bytes.copyOf()
    }

    override suspend fun delete(documentUri: String): Boolean {
        yield() // real storage calls hop to an I/O thread
        onDelete(documentUri)
        return documentUri !in undeletable && nodes.remove(documentUri) != null && documentUri !in deleteReportsFailure
    }
}

class FakeAccess(var writable: Set<String> = emptySet()) : SourceFolderAccess {
    var failure: Exception? = null

    override suspend fun persistWrite(treeUri: String) = treeUri in writable

    override suspend fun canWrite(folderUri: String): Boolean {
        failure?.let { throw it }
        return folderUri in writable
    }
}

class FakeOutputRoot(treeUri: String?) : ConversionOutputRootStore {
    override val root = MutableStateFlow(treeUri?.let { OutputRootFolder(it, "Music") })

    override suspend fun set(treeUri: String) = OutputRootFolder(treeUri, "Music").also { root.value = it }
}

class FakeBudgets(private val concurrentFiles: Int = 1, private val pauseMs: Long = 0) : ConversionBudgetSource {
    val requested = mutableListOf<PerformanceMode>()

    override fun budgets(mode: PerformanceMode): Flow<ConversionBudget> {
        requested += mode
        return flowOf(ConversionBudget(mode, concurrentFiles, 4_096, 10, pauseMs))
    }
}

/**
 * Writes "AIFF:" + the source's content to the output. Sources named in [failures] throw; outputs whose content
 * contains "corrupt" or that lie in one of [badFolders] do not verify; [gates] hold a source until completed, and
 * [stuckGates] too, ignoring cancellation meanwhile (like a blocking platform call that never returns).
 */
class FakeConverter(private val files: FakeFiles) : AudioConverter {
    val failures = mutableMapOf<String, Exception>()
    val untagged = mutableSetOf<String>()
    val badFolders = mutableSetOf<String>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val stuckGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val requests = mutableListOf<ConversionRequest>()
    val verified = mutableListOf<String>()
    var running = 0
    var maxRunning = 0
    var stepDelayMs = 0L

    override suspend fun convert(request: ConversionRequest, onProgress: (Float) -> Unit): ConversionResult {
        requests += request
        running++
        maxRunning = maxOf(maxRunning, running)
        try {
            onProgress(0.5f)
            gates[request.sourceUri]?.await()
            stuckGates[request.sourceUri]?.let { gate -> withContext(NonCancellable) { gate.await() } }
            if (stepDelayMs > 0) delay(stepDelayMs)
            failures[request.sourceUri]?.let { throw it }
            val content = files.content(request.sourceUri) ?: throw FileNotFoundException(request.sourceUri)
            files.nodes.getValue(request.outputUri).bytes = "AIFF:$content".toByteArray()
            onProgress(1f)
            return result(tagsWritten = request.sourceUri !in untagged)
        } finally {
            running--
        }
    }

    override suspend fun verify(outputUri: String, expected: ConversionResult): OutputVerification {
        verified += outputUri
        val content = files.content(outputUri)
        return when {
            content == null -> OutputVerification.Invalid("missing")
            files.nodes[outputUri]?.parent in badFolders || "corrupt" in content || !content.startsWith("AIFF:") -> OutputVerification.Invalid("bad data")
            else -> OutputVerification.Valid
        }
    }

    companion object {
        fun result(tagsWritten: Boolean = true) = ConversionResult(
            plan = ConversionPlan(ConversionProfiles.XDJ_700, 44_100, 44_100, 2, 2, 16, dither = false),
            outputFrames = 100,
            sourceFrames = 100,
            tagsWritten = tagsWritten,
        )
    }
}

class FakeRepository : ConversionBatchRepository {
    val batchRows = linkedMapOf<Long, ConversionBatch>()
    val itemRows = linkedMapOf<Long, ConversionBatchItem>()
    private val version = MutableStateFlow(0)
    private var nextBatch = 1L
    private var nextItem = 1L
    /** Runs after every [getBatch], e.g. to let a cancel race a job that just read its batch. */
    var afterGetBatch: suspend (Long) -> Unit = {}
    /** Runs before every [transition] to [BatchStatus] `to`, e.g. to land a cancel just before a batch completes. */
    var beforeTransition: suspend (batchId: Long, to: BatchStatus) -> Unit = { _, _ -> }
    /** An item write to fail, as a full or broken database would; `null` writes everything. */
    var failItemWrite: (ConversionBatchItem) -> Exception? = { null }

    override suspend fun create(batch: ConversionBatch, sources: List<BatchSource>): Long {
        val id = nextBatch++
        batchRows[id] = batch.copy(id = id)
        sources.forEachIndexed { i, s ->
            val itemId = nextItem++
            itemRows[itemId] = ConversionBatchItem(itemId, id, i, s.uri, s.displayName, s.parentUri, profileId = s.profileId ?: batch.profileId)
        }
        version.value++
        return id
    }

    private fun withCounts(batch: ConversionBatch): ConversionBatch {
        val items = itemRows.values.filter { it.batchId == batch.id }
        fun count(state: BatchItemState) = items.count { it.state == state }
        return batch.copy(
            counts = BatchCounts(
                total = items.size,
                pending = count(BatchItemState.PENDING),
                running = count(BatchItemState.RUNNING),
                done = count(BatchItemState.DONE),
                failed = count(BatchItemState.FAILED),
                skipped = count(BatchItemState.SKIPPED),
                replacedOriginals = count(BatchItemState.REPLACED_ORIGINAL),
                withoutTags = items.count { it.isConverted && !it.tagsWritten },
                notReplaced = items.count { it.state == BatchItemState.FAILED && it.canReplaceOriginal },
            ),
            profileIds = items.mapTo(LinkedHashSet()) { it.profileId },
        )
    }

    override fun batches() = version.map { batchRows.values.map(::withCounts).reversed() }

    override fun batch(id: Long) = version.map { batchRows[id]?.let(::withCounts) }

    override fun items(batchId: Long) = version.map { itemRows.values.filter { it.batchId == batchId } }

    override suspend fun getBatch(id: Long) = batchRows[id]?.let(::withCounts).also { afterGetBatch(id) }

    override suspend fun getItems(batchId: Long) = itemRows.values.filter { it.batchId == batchId }.sortedBy { it.position }

    override suspend fun updateBatch(batch: ConversionBatch) {
        batchRows[batch.id] = batch.copy(counts = BatchCounts())
        version.value++
    }

    override suspend fun updateItem(item: ConversionBatchItem) {
        yield() // Room hops to its own thread, cancellably
        failItemWrite(item)?.let { throw it }
        itemRows[item.id] = item
        version.value++
    }

    override suspend fun transition(batchId: Long, from: Set<BatchStatus>, to: BatchStatus, failureReason: String?): Boolean {
        beforeTransition(batchId, to)
        val batch = batchRows[batchId]?.takeIf { it.status in from } ?: return false
        batchRows[batchId] = batch.copy(status = to, failureReason = failureReason)
        version.value++
        return true
    }

    override suspend fun delete(batchId: Long) {
        batchRows.remove(batchId)
        itemRows.values.removeAll { it.batchId == batchId }
        version.value++
    }

    fun item(batchId: Long, position: Int) = itemRows.values.single { it.batchId == batchId && it.position == position }
}

fun newBatch(
    createdAtMs: Long = 0,
    overwrite: Boolean = false,
    mode: PerformanceMode = PerformanceMode.NORMAL,
    folderName: String = "2026-10-03 14.05",
) = ConversionBatch(
    id = 0,
    profileId = ConversionProfiles.XDJ_700.id,
    performanceMode = mode,
    createdAtMs = createdAtMs,
    outputFolderName = folderName,
    outputFolderUri = null,
    overwriteOriginals = overwrite,
    status = BatchStatus.QUEUED,
)
