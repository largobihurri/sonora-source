package io.sonora.player.distribution

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The online lyrics search (LRCLIB) is the app's only use of the network, and only the `full` build has it, so only
 * that build asks for the internet permission; the `play` build stays offline, and also removes the permission ONNX
 * Runtime's own manifest declares.
 */
class NetworkPermissionTest {

    private val app = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "src/main/AndroidManifest.xml").isFile }

    private fun internetLines(sourceSet: String): List<String> =
        File(app, "src/$sourceSet/AndroidManifest.xml").takeIf { it.isFile }?.readLines().orEmpty()
            .filter { "android.permission.INTERNET" in it }

    private fun asksForInternet(sourceSet: String) = internetLines(sourceSet).any { "tools:node=\"remove\"" !in it }

    private fun removesInternet(sourceSet: String) = internetLines(sourceSet).any { "tools:node=\"remove\"" in it }

    @Test
    fun `only the full build asks for the internet permission`() {
        assertFalse("the shared manifest asks for internet", asksForInternet("main"))
        assertFalse("the play manifest asks for internet", asksForInternet("play"))
        assertTrue("the play manifest does not remove the libraries' internet permission", removesInternet("play"))
        assertTrue("the full manifest does not ask for internet", asksForInternet("full"))
    }
}
