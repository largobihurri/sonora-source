package io.sonora.core.data.backup

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.backup.BackupError
import io.sonora.core.domain.backup.BackupFavourite
import io.sonora.core.domain.backup.BackupPin
import io.sonora.core.domain.backup.BackupPlaylist
import io.sonora.core.domain.backup.BackupPlays
import io.sonora.core.domain.backup.BackupReading
import io.sonora.core.domain.backup.BackupSettingsFile
import io.sonora.core.domain.backup.LibraryBackup
import io.sonora.core.domain.backup.SettingValue
import io.sonora.core.domain.backup.SongIdentity
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.FolderRef
import org.junit.Test

class BackupJsonTest {

    private val json = BackupJson()

    private val backup = LibraryBackup(
        createdAtMs = 1_759_536_000_000,
        songs = listOf(SongIdentity("content://a/1", "Halo \"Live\"", "Beyoncé", "Wembley", 261_000)),
        playlists = listOf(BackupPlaylist(3, "Mix", "Für dich", listOf("content://a/1", "content://a/1"))),
        favourites = listOf(BackupFavourite(ItemRef.song("content://a/1"), 10), BackupFavourite(ItemRef.album(-42), 11)),
        pins = listOf(BackupPin(PinContext.playlist(3), ItemRef.song("content://a/1"), 12)),
        hidden = listOf(HiddenItem(ItemRef.folder(FolderRef("content://tree", "Music/Old")), "Old", "Music", 13)),
        plays = listOf(BackupPlays("content://a/1", 7, 14)),
        settings = listOf(
            BackupSettingsFile(
                "sonora_equalizer",
                mapOf(
                    "b" to SettingValue.BooleanValue(true),
                    "i" to SettingValue.IntValue(-3),
                    "l" to SettingValue.LongValue(Long.MAX_VALUE),
                    "f" to SettingValue.FloatValue(1.5f),
                    "d" to SettingValue.DoubleValue(0.1),
                    "s" to SettingValue.StringValue("PINK"),
                    "set" to SettingValue.StringSetValue(setOf("x", "y")),
                ),
            ),
        ),
    )

    private fun read(text: String): LibraryBackup = (json.decode(text) as BackupReading.Read).backup

    private fun rejection(text: String): BackupError = (json.decode(text) as BackupReading.Rejected).error

    @Test
    fun `a backup survives a round trip unchanged`() {
        assertThat(read(json.encode(backup))).isEqualTo(backup)
    }

    @Test
    fun `the file names its format and version first`() {
        assertThat(json.encode(backup)).startsWith("""{"format":"sonora-backup","version":2,""")
    }

    @Test
    fun `unknown fields, mark kinds and setting types are skipped`() {
        val text = """
            {"format":"sonora-backup","version":1,"createdAtMs":5,"addedLater":{"x":[1,2]},
             "songs":[{"key":"k","title":"T","artist":"A","mood":"happy"}],
             "favourites":[{"kind":"SONG","key":"k","addedAtMs":1},{"kind":"PODCAST","key":"p","addedAtMs":2}],
             "pins":[{"context":"songs","kind":"STATION","key":"s","pinnedAtMs":3}],
             "hidden":[{"kind":"GENRE","key":"g","title":"Jazz","subtitle":"","hiddenAtMs":4}],
             "settings":[{"name":"sonora_settings","values":[
                {"key":"a","type":"string","value":"DARK"},
                {"key":"b","type":"colour","value":"#fff"},
                {"key":"c","type":"boolean","value":"yes"}]}]}
        """.trimIndent()

        val read = read(text)

        assertThat(read.createdAtMs).isEqualTo(5)
        assertThat(read.songs).containsExactly(SongIdentity("k", "T", "A", album = "", durationMs = 0))
        assertThat(read.favourites).containsExactly(BackupFavourite(ItemRef.song("k"), 1))
        assertThat(read.pins).isEmpty()
        assertThat(read.hidden).isEmpty()
        assertThat(read.settings.single().values).containsExactly("a", SettingValue.StringValue("DARK"))
    }

    @Test
    fun `a file without later sections reads as empty ones`() {
        assertThat(read("""{"format":"sonora-backup","version":1}""")).isEqualTo(LibraryBackup(createdAtMs = 0))
    }

    @Test
    fun `a newer version is refused as such`() {
        assertThat(rejection("""{"format":"sonora-backup","version":3,"songs":"changed"}""")).isEqualTo(BackupError.NewerVersion(3))
    }

    @Test
    fun `a version 1 file still reads, with no DJ analyses`() {
        val v1 = """
            {"format":"sonora-backup","version":1,"createdAtMs":7,
             "songs":[{"key":"k","title":"T","artist":"A","album":"B","durationMs":9}],
             "playlists":[{"id":1,"name":"Mix","description":"","entries":["k"]}],
             "plays":[{"key":"k","count":2,"lastPlayedMs":3}]}
        """.trimIndent()

        val read = read(v1)

        assertThat(read.djAnalyses).isEmpty()
        assertThat(read.songs).containsExactly(SongIdentity("k", "T", "A", "B", 9))
        assertThat(read.playlists).containsExactly(BackupPlaylist(1, "Mix", "", listOf("k")))
        assertThat(read.plays).containsExactly(BackupPlays("k", 2, 3))
    }

    @Test
    fun `DJ analysis records round trip as they are, in order`() {
        val records = (0 until 50).map { n -> java.util.Base64.getEncoder().encodeToString(ByteArray(160) { (it * n).toByte() }) }
        val withRecords = backup.copy(djAnalyses = records)

        val text = json.encode(withRecords)

        assertThat(read(text)).isEqualTo(withRecords)
        assertThat(text).contains(""""djAnalyses":[""")
    }

    @Test
    fun `a backup without DJ analyses writes an empty array that reads back as none`() {
        assertThat(json.encode(backup)).contains(""""djAnalyses":[]""")
        assertThat(read(json.encode(backup)).djAnalyses).isEmpty()
    }

    @Test
    fun `an entry that is not text reads as an empty record, so the DJ store counts it as skipped`() {
        val text = """{"format":"sonora-backup","version":2,"djAnalyses":["QUJD",7,null,{"a":1},"REVG"]}"""

        assertThat(read(text).djAnalyses).containsExactly("QUJD", "", "", "", "REVG").inOrder()
    }

    @Test
    fun `DJ analyses that are not an array make the file malformed`() {
        assertThat(rejection("""{"format":"sonora-backup","version":2,"djAnalyses":"QUJD"}""")).isInstanceOf(BackupError.Malformed::class.java)
    }

    @Test
    fun `20000 tracks fit in the backup file well under its 32 MiB cap`() {
        // A record is about 160 bytes with a one-segment grid; base64 makes it 4/3 of that. A variable-tempo track
        // (20 segments of 17 bytes each) is the pessimistic case.
        fun record(extra: Int) = java.util.Base64.getEncoder().encodeToString(ByteArray(160 + 17 * extra) { (it * 31).toByte() })
        val typical = json.encode(LibraryBackup(createdAtMs = 1, djAnalyses = List(20_000) { record(0) }))
        val variable = json.encode(LibraryBackup(createdAtMs = 1, djAnalyses = List(20_000) { record(20) }))

        // The records really are in the file (a lower bound), and still far from the cap.
        assertThat(typical.toByteArray().size).isGreaterThan(4 * 1024 * 1024)
        assertThat(typical.toByteArray().size).isLessThan(8 * 1024 * 1024)
        assertThat(variable.toByteArray().size).isLessThan(16 * 1024 * 1024)
        assertThat(variable.toByteArray().size).isLessThan(SafBackupDocuments.MAX_BYTES)
    }

    @Test
    fun `another program's file is told apart from a broken backup`() {
        assertThat(rejection("""{"format":"sonora-archive","version":1}""")).isEqualTo(BackupError.OtherFormat("sonora-archive"))
        assertThat(rejection("""[1,2,3]""")).isEqualTo(BackupError.OtherFormat(null))
        assertThat(rejection("""{"name":"x"}""")).isEqualTo(BackupError.OtherFormat(null))
    }

    @Test
    fun `corrupt files are malformed`() {
        assertThat(rejection("")).isInstanceOf(BackupError.Malformed::class.java)
        assertThat(rejection("""{"format":"sonora-backup","version":1,"songs":[{"key":"k"""")).isInstanceOf(BackupError.Malformed::class.java)
        assertThat(rejection("""{"format":"sonora-backup","version":"1"}""")).isInstanceOf(BackupError.Malformed::class.java)
        assertThat(rejection("""{"format":"sonora-backup","version":0}""")).isInstanceOf(BackupError.Malformed::class.java)
        assertThat(rejection("""{"format":"sonora-backup","version":1,"songs":[{"title":"no key"}]}""")).isInstanceOf(BackupError.Malformed::class.java)
        assertThat(rejection("""{"format":"sonora-backup","version":1,"plays":[{"key":"k","count":"many"}]}""")).isInstanceOf(BackupError.Malformed::class.java)
    }
}
