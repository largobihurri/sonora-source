package io.sonora.core.fat

import io.sonora.core.domain.storage.BlockDevice
import io.sonora.core.domain.storage.FormatStructure
import io.sonora.core.domain.storage.StickFormatProgress
import io.sonora.core.domain.storage.StickFormatResult
import io.sonora.core.domain.storage.UsbStickFormatter
import io.sonora.core.domain.storage.VolumeLabel

/** [UsbStickFormatter] backed by [UsbVolumeFormatter]: MBR + FAT32, read back after writing. */
class FatUsbStickFormatter(private val formatter: UsbVolumeFormatter = UsbVolumeFormatter()) : UsbStickFormatter {

    override fun format(
        device: BlockDevice,
        label: VolumeLabel?,
        onProgress: (StickFormatProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): StickFormatResult {
        val result = formatter.format(
            device,
            FormatOptions(label),
            onProgress = { onProgress(StickFormatProgress(it.bytesWritten, it.totalBytes)) },
            isCancelled = isCancelled,
        )
        return when (result) {
            is FormatResult.Success -> StickFormatResult.Success(
                label = label,
                volumeBytes = result.layout.partitionSectors * result.layout.blockSize,
                clusterBytes = result.layout.clusterBytes,
            )
            is FormatResult.UnsupportedBlockSize -> StickFormatResult.UnsupportedBlockSize(result.blockSize)
            is FormatResult.DeviceTooSmall -> StickFormatResult.TooSmall(result.deviceBytes)
            is FormatResult.DeviceTooLarge -> StickFormatResult.TooLarge(result.deviceBytes, result.maximumBytes)
            is FormatResult.IoError -> StickFormatResult.IoError(result.lba, result.cause)
            is FormatResult.VerificationMismatch ->
                StickFormatResult.VerificationMismatch(FormatStructure.valueOf(result.structure.name), result.lba)
            FormatResult.Cancelled -> StickFormatResult.Cancelled
        }
    }
}
