package slussen.persistence.start

import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.StartKind
import slussen.core.port.StoredPart
import slussen.core.port.UnreadablePart
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.time.Clock

/** Checks one database file before Room opens it (ADR-002 §8 step 1); true when it may be opened as it is. */
fun interface DatabaseCheck {
    fun isReadable(file: File): Boolean
}

/** The files of ADR-002 §1 that the start sequence reads, writes and sets aside. */
class StartFiles(databaseDirectory: File, noBackupDirectory: File) {
    val main = File(databaseDirectory, "slussen.db")
    val mainJournal = File(databaseDirectory, "slussen.db-journal")
    val device = File(noBackupDirectory, "device.db")
    val deviceJournal = File(noBackupDirectory, "device.db-journal")
    val journal = File(noBackupDirectory, "journal.db")
    val journalJournal = File(noBackupDirectory, "journal.db-journal")
    val installationId = File(noBackupDirectory, "installation.id")
    val intent = File(noBackupDirectory, "slussen.start-intent")
    val intentTemporary = File(noBackupDirectory, "slussen.start-intent.tmp")
    val lastGood = File(noBackupDirectory, "slussen.lastgood.db")
    val lastGoodTemporary = File(noBackupDirectory, "slussen.lastgood.db.tmp")
}

/** What the start sequence found and did, before Room opens any database. */
data class StartOutcome(
    val kind: StartKind,
    /** The cause of First Upload Matching to open, or null for a normal start. */
    val firstUploadMatchingCause: FirstUploadMatchingCause?,
    val unreadableParts: List<UnreadablePart>,
)

/**
 * The start sequence of ADR-002 §6 and the last good copy of ADR-002 §8, on plain files, before Room opens a database.
 * It makes the obligation to open a First Upload Matching durable (the start intent) before it changes anything, so that a
 * process that dies between two steps finishes the same work at its next start.
 */
class StartSequence(
    private val files: StartFiles,
    private val mainCheck: DatabaseCheck,
    private val deviceCheck: DatabaseCheck,
    private val journalCheck: DatabaseCheck,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    fun run(): StartOutcome {
        files.lastGoodTemporary.delete()
        files.intentTemporary.delete()
        // Step 1: resume an interrupted start from its intent.
        val resumed = readIntent()
        val cause = resumed ?: classify()
        // UC-006 BR-017: The obligation to open a First Upload Matching is stored before anything is changed.
        if (resumed == null && cause != null) writeIntent(cause)
        val unreadable = mutableListOf<UnreadablePart>()
        if (cause == FirstUploadMatchingCause.LastGoodCopy) {
            val startedFromLastGood = replaceUnreadableMain()
            unreadable += UnreadablePart(StoredPart.TransactionsDecisionsAndSettings, startedFromLastGood)
        }
        if (!files.installationId.exists()) writeDurably(files.installationId, newRandomText())
        if (present(files.main) && mainCheck.isReadable(files.main)) makeLastGoodCopy()
        // Damage to a part that holds no Decision-Critical Data is set aside and only named; it opens no First Upload Matching (UC-008 A1).
        if (present(files.device) && !deviceCheck.isReadable(files.device)) {
            setAside(files.device, files.deviceJournal)
            unreadable += UnreadablePart(StoredPart.CredentialsConsentsAndOfferedBankAccounts, startedFromLastGoodCopy = false)
        }
        if (present(files.journal) && !journalCheck.isReadable(files.journal)) {
            setAside(files.journal, files.journalJournal)
            unreadable += UnreadablePart(StoredPart.DecisionJournal, startedFromLastGoodCopy = false)
        }
        val kind = when (cause) {
            null -> StartKind.Normal
            FirstUploadMatchingCause.NewInstallation -> StartKind.NewInstallation
            FirstUploadMatchingCause.RestoredBackup -> StartKind.RestoredBackup
            FirstUploadMatchingCause.LastGoodCopy -> StartKind.LastGoodCopy
        }
        return StartOutcome(kind, cause, unreadable)
    }

    /** Step 6: deletes the start intent, only after First Upload Matching and the coordinator reset are committed. */
    fun finish() {
        files.intent.delete()
    }

    private fun present(file: File) = file.exists() && file.length() > 0

    /** Step 2, the table of ADR-002 §6. */
    private fun classify(): FirstUploadMatchingCause? {
        val marker = files.installationId.exists()
        val main = present(files.main)
        return when {
            // Decision-Critical Data that cannot be read is started from its last good copy and opens a First Upload Matching (UC-008 A1, BR-009).
            main && !mainCheck.isReadable(files.main) -> FirstUploadMatchingCause.LastGoodCopy
            !marker && !main -> FirstUploadMatchingCause.NewInstallation
            // UC-006 BR-017: Data restored from a device backup may lack what Slussen last recorded; Uploads wait for First Upload Matching.
            !marker && main -> FirstUploadMatchingCause.RestoredBackup
            marker && !main -> FirstUploadMatchingCause.NewInstallation
            else -> null
        }
    }

    /** ADR-002 §8 step 3: the unreadable original is kept unchanged and the last good copy, if any, takes its place. */
    private fun replaceUnreadableMain(): Boolean {
        if (present(files.main) && mainCheck.isReadable(files.main)) return files.lastGood.exists()
        if (files.main.exists()) setAside(files.main, files.mainJournal)
        if (!files.lastGood.exists()) return false
        copyDurably(files.lastGood, files.main)
        return true
    }

    /** ADR-002 §8 step 2: copy, flush, rename over the previous last good copy. */
    private fun makeLastGoodCopy() {
        copyDurably(files.main, files.lastGoodTemporary)
        if (!files.lastGoodTemporary.renameTo(files.lastGood)) {
            files.lastGood.delete()
            files.lastGoodTemporary.renameTo(files.lastGood)
        }
    }

    /** Renames [database] and its rollback journal, unchanged, to an unused `.unreadable-<millis>-<random>` name. */
    private fun setAside(database: File, rollbackJournal: File) {
        var suffix: String
        do {
            suffix = ".unreadable-${clock.millis()}-${newRandomText()}"
        } while (File(database.path + suffix).exists() || File(rollbackJournal.path + suffix).exists())
        check(database.renameTo(File(database.path + suffix))) { "Could not set aside ${database.name}." }
        if (rollbackJournal.exists()) rollbackJournal.renameTo(File(rollbackJournal.path + suffix))
    }

    private fun readIntent(): FirstUploadMatchingCause? {
        if (!files.intent.exists()) return null
        val text = files.intent.readText(Charsets.UTF_8).trim()
        return FirstUploadMatchingCause.entries.firstOrNull { it.text == text } ?: FirstUploadMatchingCause.LastGoodCopy
    }

    private fun writeIntent(cause: FirstUploadMatchingCause) {
        writeDurably(files.intentTemporary, cause.text)
        check(files.intentTemporary.renameTo(files.intent)) { "Could not record the start intent." }
    }

    private fun writeDurably(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    private fun copyDurably(from: File, to: File) {
        from.inputStream().use { input ->
            FileOutputStream(to).use { out ->
                input.copyTo(out)
                out.fd.sync()
            }
        }
    }

    /** 128 random bits in hexadecimal (ADR-008 §7). */
    private fun newRandomText(): String {
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val RANDOM_BYTES = 16
    }
}
