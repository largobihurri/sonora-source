package io.sonora.player.resources

import io.sonora.core.data.dub.SharedPreferencesDubModeSettings
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The Android Auto Backup rules, read from disk: the DUB mode flag is device state, so its file is excluded from the
 * full backup (up to Android 11) and from both cloud backup and device transfer (Android 12 and later).
 */
class BackupRulesTest {

    private val xml = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
        .resolve("app/src/main/res/xml")

    private fun parse(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xml, name)).documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.excludesSharedPreferences(file: String): Boolean = children().any {
        it.tagName == "exclude" && it.getAttribute("domain") == "sharedpref" && it.getAttribute("path") == "$file.xml"
    }

    private val dubFlagFile = SharedPreferencesDubModeSettings.FILE_NAME

    @Test
    fun `the full backup excludes the DUB mode flag file`() {
        val rules = parse("backup_rules.xml")
        assertTrue("backup_rules.xml must exclude $dubFlagFile.xml", rules.excludesSharedPreferences(dubFlagFile))
    }

    @Test
    fun `cloud backup and device transfer both exclude the DUB mode flag file`() {
        val sections = parse("data_extraction_rules.xml").children()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val rules = sections.single { it.tagName == section }
            assertTrue(
                "data_extraction_rules.xml <$section> must exclude $dubFlagFile.xml",
                rules.excludesSharedPreferences(dubFlagFile),
            )
        }
    }
}
