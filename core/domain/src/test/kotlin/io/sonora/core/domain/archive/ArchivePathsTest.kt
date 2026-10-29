package io.sonora.core.domain.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArchivePathsTest {

    @Test
    fun `relative paths are split into their segments`() {
        assertThat(ArchivePaths.safeSegments("Record/01 - Band - Intro.flac")).containsExactly("Record", "01 - Band - Intro.flac").inOrder()
        assertThat(ArchivePaths.safeSegments("./a//b.mp3")).containsExactly("a", "b.mp3").inOrder()
        assertThat(ArchivePaths.safeSegments("Windows\\style\\c.mp3")).containsExactly("Windows", "style", "c.mp3").inOrder()
    }

    @Test
    fun `absolute paths and drive letters are unsafe`() {
        assertThat(ArchivePaths.safeSegments("/etc/passwd")).isNull()
        assertThat(ArchivePaths.safeSegments("\\\\server\\share\\a.mp3")).isNull()
        assertThat(ArchivePaths.safeSegments("C:\\Music\\a.mp3")).isNull()
        assertThat(ArchivePaths.safeSegments("C:a.mp3")).isNull()
    }

    @Test
    fun `parent references are unsafe wherever they are`() {
        assertThat(ArchivePaths.safeSegments("../evil.mp3")).isNull()
        assertThat(ArchivePaths.safeSegments("a/../../evil.mp3")).isNull()
        assertThat(ArchivePaths.safeSegments("a/..")).isNull()
        assertThat(ArchivePaths.safeSegments("a\\..\\..\\evil.mp3")).isNull()
        // Windows drops trailing dots and spaces: "... " is the current folder there.
        assertThat(ArchivePaths.safeSegments("... /a.mp3")).isNull()
    }

    @Test
    fun `empty, control-character and too deep paths are unsafe`() {
        assertThat(ArchivePaths.safeSegments("")).isNull()
        assertThat(ArchivePaths.safeSegments("./")).isNull()
        assertThat(ArchivePaths.safeSegments("a\u0000.mp3")).isNull()
        assertThat(ArchivePaths.safeSegments("a\nb.mp3")).isNull()
        assertThat(ArchivePaths.safeSegments(List(ArchivePaths.MAX_DEPTH + 1) { "d" }.joinToString("/") + ".mp3")).isNull()
    }

    @Test
    fun `segments with FAT-illegal characters are made safe`() {
        assertThat(ArchivePaths.safeSegments("Mix*<1>/a?.mp3")).containsExactly("Mix__1_", "a_.mp3").inOrder()
    }

    @Test
    fun `audio files are told by their extension, ignoring case`() {
        assertThat(ArchivePaths.isAudio("a.FLAC")).isTrue()
        assertThat(ArchivePaths.isAudio("b.aiff")).isTrue()
        assertThat(ArchivePaths.isAudio("c.m4a")).isTrue()
        assertThat(ArchivePaths.isAudio("notes.txt")).isFalse()
        assertThat(ArchivePaths.isAudio("evil.mp3.exe")).isFalse()
        assertThat(ArchivePaths.isAudio("mp3")).isFalse()
    }

    @Test
    fun `entries are classified as audio, manifest or skipped`() {
        assertThat(ArchivePaths.classify("Record/01.flac")).isEqualTo(EntryVerdict.Audio(listOf("Record", "01.flac")))
        assertThat(ArchivePaths.classify("Record/sonora.json")).isEqualTo(EntryVerdict.Manifest(listOf("Record", "sonora.json")))
        assertThat(ArchivePaths.classify("SONORA.JSON")).isEqualTo(EntryVerdict.Manifest(listOf("SONORA.JSON")))
        assertThat(ArchivePaths.classify("a/b/sonora.json")).isEqualTo(EntryVerdict.Skipped(SkipReason.NOT_AUDIO))
        assertThat(ArchivePaths.classify("Record/")).isEqualTo(EntryVerdict.Skipped(SkipReason.DIRECTORY))
        assertThat(ArchivePaths.classify("Record/cover.jpg")).isEqualTo(EntryVerdict.Skipped(SkipReason.NOT_AUDIO))
        assertThat(ArchivePaths.classify("../evil.flac")).isEqualTo(EntryVerdict.Skipped(SkipReason.UNSAFE_PATH))
    }
}
