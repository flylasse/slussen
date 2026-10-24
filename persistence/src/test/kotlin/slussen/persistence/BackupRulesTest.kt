package slussen.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ADR-002 §5: both backup rule files include exactly `slussen.db` in every section, and no rule names `device.db` or
 * `journal.db`.
 */
class BackupRulesTest {
    private val xmlDirectory = File("src/main/res/xml")

    private fun includes(section: Element): List<Pair<String, String>> {
        val children = section.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }.map {
            assertEquals("include", it.tagName, "only <include> rules are allowed")
            it.getAttribute("domain") to it.getAttribute("path")
        }
    }

    private fun parse(name: String): Element {
        val file = File(xmlDirectory, name)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    @Test
    fun dataExtractionRulesIncludeOnlyTheMainDatabase() {
        val root = parse("data_extraction_rules.xml")
        val sections = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
        assertEquals(listOf("cloud-backup", "device-transfer"), sections.map { it.tagName })
        sections.forEach { assertEquals(listOf("database" to SlussenDatabases.MAIN_FILE), includes(it)) }
    }

    @Test
    fun fullBackupContentIncludesOnlyTheMainDatabase() {
        val root = parse("backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertEquals(listOf("database" to SlussenDatabases.MAIN_FILE), includes(root))
    }

    @Test
    fun noRuleNamesTheDeviceOrJournalDatabase() {
        listOf("data_extraction_rules.xml", "backup_rules.xml").forEach { name ->
            val text = File(xmlDirectory, name).readText()
            assertFalse(SlussenDatabases.DEVICE_FILE in text, "$name names device.db")
            assertFalse(SlussenDatabases.JOURNAL_FILE in text, "$name names journal.db")
        }
    }
}
