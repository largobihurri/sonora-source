package io.sonora.core.fat

import io.sonora.core.domain.storage.BlockDevice
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An in-memory device that keeps only blocks holding non-zero bytes, so terabyte-sized virtual devices are cheap. */
class SparseBlockDevice(override val blockSize: Int, override val blockCount: Long) : BlockDevice {
    private val blocks = HashMap<Long, ByteArray>()

    var bytesWritten = 0L
        private set
    var flushes = 0
        private set

    override fun read(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        checkRange(lba, blocks)
        for (i in 0 until blocks) {
            val target = offset + i * blockSize
            val stored = this.blocks[lba + i]
            if (stored == null) buffer.fill(0, target, target + blockSize) else stored.copyInto(buffer, target)
        }
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        checkRange(lba, blocks)
        for (i in 0 until blocks) {
            val from = offset + i * blockSize
            val block = buffer.copyOfRange(from, from + blockSize)
            if (block.all { it == 0.toByte() }) this.blocks.remove(lba + i) else this.blocks[lba + i] = block
        }
        bytesWritten += blocks.toLong() * blockSize
    }

    override fun flush() {
        flushes++
    }

    fun block(lba: Long): ByteArray = ByteArray(blockSize).also { read(lba, it, blocks = 1) }

    fun fill(lba: Long, value: Byte) = write(lba, ByteArray(blockSize) { value }, blocks = 1)

    private fun checkRange(lba: Long, count: Int) {
        if (lba < 0 || count < 0 || lba + count > blockCount) throw IOException("LBA $lba + $count outside $blockCount")
    }
}

/** A device backed by a (sparse) image file. */
class FileBlockDevice(file: File, override val blockSize: Int, override val blockCount: Long) : BlockDevice, Closeable {
    private val image = RandomAccessFile(file, "rw").apply { setLength(blockCount * blockSize) }

    override fun read(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        image.seek(lba * blockSize)
        image.readFully(buffer, offset, blocks * blockSize)
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        image.seek(lba * blockSize)
        image.write(buffer, offset, blocks * blockSize)
    }

    override fun flush() = image.fd.sync()

    override fun close() = image.close()
}

/** Flips one byte of whatever is written to [corruptLba], as a faulty stick would. */
class CorruptingBlockDevice(private val delegate: BlockDevice, private val corruptLba: Long) : BlockDevice by delegate {
    override fun write(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        if (corruptLba !in lba until lba + blocks) return delegate.write(lba, buffer, offset, blocks)
        val copy = buffer.copyOfRange(offset, offset + blocks * blockSize)
        copy[((corruptLba - lba) * blockSize + 3).toInt()] = 0x42
        delegate.write(lba, copy, 0, blocks)
    }
}

/** Fails every write that touches [failingWriteLba], every read that touches [failingReadLba], and [flush] if [failFlush]. */
class FailingBlockDevice(
    private val delegate: BlockDevice,
    private val failingWriteLba: Long? = null,
    private val failingReadLba: Long? = null,
    private val failFlush: Boolean = false,
) : BlockDevice by delegate {
    override fun read(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        if (failingReadLba != null && failingReadLba in lba until lba + blocks) throw IOException("read failed")
        delegate.read(lba, buffer, offset, blocks)
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, blocks: Int) {
        if (failingWriteLba != null && failingWriteLba in lba until lba + blocks) throw IOException("write failed")
        delegate.write(lba, buffer, offset, blocks)
    }

    override fun flush() {
        if (failFlush) throw IOException("flush failed")
        delegate.flush()
    }
}

fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

fun ByteArray.u16(at: Int): Int = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getShort(at).toInt() and 0xFFFF

fun ByteArray.u32(at: Int): Long = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getInt(at).toLong() and 0xFFFF_FFFFL

fun ByteArray.ascii(at: Int, length: Int): String = String(this, at, length, Charsets.US_ASCII)

const val MiB = 1024L * 1024
const val GiB = 1024L * MiB
