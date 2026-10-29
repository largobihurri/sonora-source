package io.sonora.core.data.archive

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.archive.ArchiveEntry
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveManifest
import io.sonora.core.domain.archive.ManifestError
import io.sonora.core.domain.archive.ManifestReading
import org.junit.Test

class ArchiveManifestJsonTest {

    private val manifest = ArchiveManifest(
        kind = ArchiveKind.PLAYLIST,
        name = "Road Trip: \"2026\"",
        entries = listOf(
            ArchiveEntry("01 - Band - Intro.flac", "Intro", "Band", "Record", "Band", 1, 1, 61_000),
            ArchiveEntry("02 - Ñandú - Café.mp3", "Café", "Ñandú", "Otro", durationMs = 200_500),
        ),
    )

    private fun rejected(json: String) = (ArchiveManifestJson.decode(json) as ManifestReading.Rejected).error

    @Test
    fun `a manifest survives a round trip`() {
        assertThat(ArchiveManifestJson.decode(ArchiveManifestJson.encode(manifest))).isEqualTo(ManifestReading.Read(manifest))
    }

    @Test
    fun `a selection of songs is its own kind`() {
        val selection = manifest.copy(kind = ArchiveKind.SELECTION)
        assertThat(ArchiveManifestJson.encode(selection)).contains("\"kind\":\"selection\"")
        assertThat(ArchiveManifestJson.decode(ArchiveManifestJson.encode(selection))).isEqualTo(ManifestReading.Read(selection))
    }

    @Test
    fun `the encoded manifest names its format, version and kind`() {
        val json = ArchiveManifestJson.encode(manifest)
        assertThat(json).contains("\"format\":\"sonora-archive\"")
        assertThat(json).contains("\"version\":1")
        assertThat(json).contains("\"kind\":\"playlist\"")
        assertThat(json).doesNotContain("albumArtist\":null")
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = """
            {"format":"sonora-archive","version":1,"kind":"album","name":"Record","createdBy":"Sonora 2.0",
             "entries":[{"file":"01.flac","title":"A","artist":"B","album":"Record","rating":5,"bpm":120.5}],
             "extra":{"nested":[1,2,3]}}
        """.trimIndent()
        val read = ArchiveManifestJson.decode(json) as ManifestReading.Read
        assertThat(read.manifest.kind).isEqualTo(ArchiveKind.ALBUM)
        assertThat(read.manifest.entries.single()).isEqualTo(ArchiveEntry("01.flac", "A", "B", "Record"))
    }

    @Test
    fun `another program's json is another format`() {
        assertThat(rejected("""{"format":"other-app","version":1}""")).isEqualTo(ManifestError.OtherFormat("other-app"))
        assertThat(rejected("""{"name":"x"}""")).isEqualTo(ManifestError.OtherFormat(null))
        assertThat(rejected("""[1,2]""")).isEqualTo(ManifestError.OtherFormat(null))
    }

    @Test
    fun `a newer major version is rejected, whatever its shape`() {
        assertThat(rejected("""{"format":"sonora-archive","version":2,"songs":{}}""")).isEqualTo(ManifestError.NewerVersion(2))
    }

    @Test
    fun `broken or incomplete manifests are malformed`() {
        assertThat(rejected("not json")).isInstanceOf(ManifestError.Malformed::class.java)
        assertThat(rejected("""{"format":"sonora-archive","version":1,"kind":"album","entries":[]}""")).isInstanceOf(ManifestError.Malformed::class.java)
        assertThat(rejected("""{"format":"sonora-archive","version":1,"kind":"mixtape","name":"x","entries":[]}""")).isInstanceOf(ManifestError.Malformed::class.java)
        assertThat(rejected("""{"format":"sonora-archive","version":"one"}""")).isInstanceOf(ManifestError.Malformed::class.java)
        assertThat(rejected("""{"format":"sonora-archive","version":1,"kind":"album","name":"x","entries":[{"file":"a/b.flac","title":"t","artist":"a","album":"r"}]}"""))
            .isInstanceOf(ManifestError.Malformed::class.java)
    }

    @Test
    fun `an entry file must be a plain name, not a dot name, a blank one or one with control characters`() {
        fun withFile(file: String) =
            """{"format":"sonora-archive","version":1,"kind":"album","name":"x","entries":[{"file":"$file","title":"t","artist":"a","album":"r"}]}"""
        for (file in listOf(".", "..", "  ", "a\\\\b.flac", "a\\u0000.flac", "a\\nb.flac")) {
            assertThat(rejected(withFile(file))).isInstanceOf(ManifestError.Malformed::class.java)
        }
        assertThat(ArchiveManifestJson.decode(withFile("..Intro...flac"))).isInstanceOf(ManifestReading.Read::class.java)
    }
}
