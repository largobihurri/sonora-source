package io.sonora.core.domain.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.FolderConflict
import io.sonora.core.domain.model.LibraryFolder
import org.junit.Test

class LibraryFolderRulesTest {

    private fun tree(documentId: String, authority: String = EXTERNAL) =
        "content://$authority/tree/" + documentId.replace(":", "%3A").replace("/", "%2F")

    private fun folder(documentId: String, authority: String = EXTERNAL) =
        LibraryFolder(tree(documentId, authority), documentId.substringAfter(':'), addedAtMs = 1)

    @Test
    fun `location decodes the tree document id and ignores a trailing document path`() {
        assertThat(LibraryFolderRules.location(tree("primary:Music/Rock & Roll")))
            .isEqualTo(TreeLocation(EXTERNAL, "primary:Music/Rock & Roll"))
        assertThat(LibraryFolderRules.location("content://$EXTERNAL/tree/primary%3AMusic/document/primary%3AMusic%2Fa.mp3"))
            .isEqualTo(TreeLocation(EXTERNAL, "primary:Music"))
        // Uri.encode escapes '+' itself, so a literal '+' in a name stays a '+'.
        assertThat(LibraryFolderRules.location("content://$EXTERNAL/tree/primary%3AA+B")?.documentId).isEqualTo("primary:A+B")
    }

    @Test
    fun `location is null for document and non-content uris`() {
        assertThat(LibraryFolderRules.location("content://$EXTERNAL/document/primary%3AMusic")).isNull()
        assertThat(LibraryFolderRules.location("file:///storage/emulated/0/Music")).isNull()
    }

    @Test
    fun `a folder inside an added folder is rejected`() {
        val music = folder("primary:Music")
        val conflict = LibraryFolderRules.conflict(tree("primary:Music/Rock"), listOf(folder("primary:Podcasts"), music))
        assertThat(conflict).isEqualTo(FolderConflict.InsideExisting(music))
    }

    @Test
    fun `a parent of an added folder is rejected`() {
        val rock = folder("primary:Music/Rock")
        assertThat(LibraryFolderRules.conflict(tree("primary:Music"), listOf(rock)))
            .isEqualTo(FolderConflict.ContainsExisting(rock))
    }

    @Test
    fun `the same folder is reported as already added`() {
        val music = folder("primary:Music")
        assertThat(LibraryFolderRules.conflict(tree("primary:Music"), listOf(music))).isEqualTo(FolderConflict.AlreadyAdded(music))
        assertThat(LibraryFolderRules.conflict(tree("primary:Music/"), listOf(music))).isEqualTo(FolderConflict.AlreadyAdded(music))
    }

    @Test
    fun `siblings sharing a name prefix do not overlap`() {
        val existing = listOf(folder("primary:Music"))
        assertThat(LibraryFolderRules.conflict(tree("primary:Music2"), existing)).isNull()
        assertThat(LibraryFolderRules.conflict(tree("primary:Mus"), existing)).isNull()
    }

    @Test
    fun `a volume root contains every folder on that volume only`() {
        val root = folder("primary:")
        assertThat(LibraryFolderRules.conflict(tree("primary:Music"), listOf(root))).isEqualTo(FolderConflict.InsideExisting(root))
        assertThat(LibraryFolderRules.conflict(tree("1234-ABCD:Music"), listOf(root))).isNull()
        assertThat(LibraryFolderRules.conflict(tree("primary:"), listOf(folder("primary:Music"))))
            .isEqualTo(FolderConflict.ContainsExisting(folder("primary:Music")))
    }

    @Test
    fun `folders of different providers never overlap`() {
        val existing = listOf(folder("primary:Music", authority = "other.provider"))
        assertThat(LibraryFolderRules.conflict(tree("primary:Music/Rock"), existing)).isNull()
    }

    @Test
    fun `unparseable uris only conflict when identical`() {
        val odd = LibraryFolder("content://x/odd", "odd", 1)
        assertThat(LibraryFolderRules.conflict("content://x/odd", listOf(odd))).isEqualTo(FolderConflict.AlreadyAdded(odd))
        assertThat(LibraryFolderRules.conflict(tree("primary:Music"), listOf(odd))).isNull()
    }

    private companion object {
        const val EXTERNAL = "com.android.externalstorage.documents"
    }
}
