package io.sonora.core.rekordbox.anlz

import java.util.Locale

/**
 * Where the analysis files of one track go on the stick: [datPath] and [extPath] relative to the stick root (to write
 * them), and [analyzePath], the `.DAT` path from the root as the track's `analyze_path` in `export.pdb` holds it. The
 * players open the `.EXT` by swapping the extension of the `.DAT` path.
 */
data class AnlzPaths(val datPath: String, val extPath: String, val analyzePath: String) {

    /** The `.2EX` file (3-band waveforms, read by the CDJ-3000), next to the `.DAT` like the `.EXT`. */
    val ex2Path: String get() = datPath.substringBeforeLast('.') + ".2EX"

    companion object {
        private const val ROOT = "PIONEER/USBANLZ"
        private const val BUCKETS = 200_003L

        /**
         * The analysis folder of the audio file at [audioPath], exactly the `file_path` string of its track in
         * `export.pdb` (from the stick root, with a leading `/`): `PIONEER/USBANLZ/Pxxx/xxxxxxxx`, as rekordbox's
         * `CreateAnlzFileFolderPath` names it.
         *
         * The players ignore a track's `analyze_path` and look in this folder (checked on an XDJ-700, firmware 1.13:
         * analysis anywhere else leaves the track unanalysed), so the folder must be this one. Over the UTF-16 code
         * units `c` of the path, `v = (v * 0x5BC9 + c) * 0x93B5 + c` in 32 bits from 0; `r = v mod 200003` (unsigned)
         * names the inner folder (`%08X`) and seven of its bits the outer one (`P%03X`). It reproduces the folders of
         * rekordbox's own demo export.
         */
        fun folderFor(audioPath: String): String {
            var v = 0
            for (c in audioPath) {
                v = (v * 0x5BC9 + c.code) * 0x93B5 + c.code
            }
            val r = (v.toLong() and 0xFFFF_FFFFL) % BUCKETS
            val p = (r and 1) or (r shr 1 and 2) or (r shr 4 and 4) or (r shr 4 and 8) or (r shr 5 and 0x10) or
                (r shr 8 and 0x20) or (r shr 10 and 0x40)
            return String.format(Locale.ROOT, "%s/P%03X/%08X", ROOT, p, r)
        }

        /**
         * The analysis files of the audio file at [audioPath] in its [folderFor]: `ANLZ0000.DAT` and `.EXT`, or the
         * next [number] when other tracks' files already take that folder (as rekordbox does: the players tell the
         * files of one folder apart by the path each holds in its `PPTH` section).
         */
        fun forAudioPath(audioPath: String, number: Int = 0): AnlzPaths {
            require(number in 0..9999) { "ANLZ file number out of range: $number" }
            val folder = folderFor(audioPath)
            // Locale.ROOT: digits must be ASCII whatever the phone's language.
            val name = String.format(Locale.ROOT, "ANLZ%04d", number)
            val dat = "$folder/$name.DAT"
            return AnlzPaths(datPath = dat, extPath = "$folder/$name.EXT", analyzePath = "/$dat")
        }
    }
}
