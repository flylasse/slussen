package slussen.persistence.start

import kotlinx.coroutines.Dispatchers
import slussen.core.consent.ConsentNotEnded
import slussen.core.consent.NotEndedBecause
import slussen.core.port.StartOverRecord
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.withContext
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.StartKind
import slussen.core.port.StartOverStore
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
    /** What this start read from the record of a Start Over it finished (ADR-002 §3 O-17 step 6); null for any other start. */
    val startOver: StartOverRecord? = null,
) {
    /** This start finished a Start Over (ADR-002 §3 O-17): everything stored before it was deleted. */
    val startedOver: Boolean get() = startOver != null
}

/**
 * The start stopped before any database was opened (ADR-002 §3 O-17 steps 4-6): a file of the old data, or the start
 * intent, could not be deleted. [startingOver] is true while a Start Over is being finished. Nothing can be used until a
 * later start has finished.
 */
class StartNotFinished(message: String, val startingOver: Boolean) : IllegalStateException(message)

/** The second line of a start intent that Start Over recorded (ADR-002 §3 O-17 step 2). */
internal const val START_OVER_LINE = "Start Over"

/** A start intent as read: its cause, and the record of Start Over when it carries one. */
private class StartIntent(val cause: FirstUploadMatchingCause, val startOver: StartOverRecord?)

/** `BANK.name` and `DATA_PROVIDER.name` (entity model): what one entry of the record takes from stored names. */
private const val BANK_NAME_MAX_LENGTH = 100
private const val DATA_PROVIDER_NAME_MAX_LENGTH = 50

/** One entry of the record (O-17 step 2), or null when its names do not fit the form: never shortened or guessed. */
private fun entryOf(notEnded: ConsentNotEnded): String? {
    val names = listOf(notEnded.dataProviderName, notEnded.bankName)
    if (names.any { name -> name.any { it == '\t' || it == '\n' || it == '\r' } }) return null
    if (notEnded.bankName.length > BANK_NAME_MAX_LENGTH || notEnded.dataProviderName.length > DATA_PROVIDER_NAME_MAX_LENGTH) return null
    return listOf(notEnded.because.name, notEnded.dataProviderName, notEnded.bankName).joinToString("\t")
}

/** The record of Start Over's lines 3 onwards, or a record whose list is not known when its number cannot be read. */
private fun recordOf(lines: List<String>): StartOverRecord {
    val count = lines.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return StartOverRecord(null, complete = false)
    val entries = lines.drop(1).filter { it.isNotEmpty() }
    val read = entries.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 3) return@mapNotNull null
        val because = NotEndedBecause.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
        ConsentNotEnded(parts[2], parts[1], because).takeIf { entryOf(it) == line }
    }
    return StartOverRecord(read, complete = read.size == count && entries.size == count)
}

/** Flushes [directory] to the device, so that a rename or a removal in it survives a power loss (ADR-002 §6 steps 3, 6). */
internal fun flushDirectoryOf(directory: File) {
    try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (failure: java.io.IOException) {
        // A file system that cannot flush a directory leaves the rename to the device's own order.
        Unit
    }
}

/** Writes [text] to [file], flushed to the device before it returns. */
internal fun writeDurably(file: File, text: String) {
    FileOutputStream(file).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
}

/** ADR-002 §6 step 3: the start intent written to a temporary file, flushed, renamed over the intent, the directory flushed. */
internal fun writeIntentDurably(files: StartFiles, text: String, flushDirectory: (File) -> Unit) {
    writeDurably(files.intentTemporary, text)
    check(files.intentTemporary.renameTo(files.intent)) { "Could not record the start intent." }
    files.intent.parentFile?.let(flushDirectory)
}

/**
 * Start Over's one step in the running process (ADR-002 §3 O-17 step 2): the start intent with the cause New
 * Installation and the line `Start Over`, written as every start intent is. It deletes nothing; the next start does.
 */
class FileStartOverStore(private val files: StartFiles, private val flushDirectory: (File) -> Unit = ::flushDirectoryOf) : StartOverStore {
    // UC-006 BR-017: From the record of Start Over on, every start opens a First Upload Matching with cause New Installation.
    override suspend fun recordStartOver(notEnded: List<ConsentNotEnded>) = withContext(Dispatchers.IO) {
        // A12 step 4 of UC-010: the Banks to name are written with the record, in the same write; one whose names do not fit is left out and counted.
        val lines = listOf(FirstUploadMatchingCause.NewInstallation.text, START_OVER_LINE, notEnded.size.toString()) + notEnded.mapNotNull(::entryOf)
        writeIntentDurably(files, lines.joinToString("\n") + "\n", flushDirectory)
    }
}

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
    /** Deletes one file of Start Over (O-17 step 4); true when it is gone. A test makes it fail at any one. */
    private val deleteFile: (File) -> Boolean = File::delete,
    /** Deletes the Keystore key of the service credentials (ADR-002 §2a), at Start Over only. */
    private val deleteCredentialKey: () -> Unit = {},
    /** Flushes a directory after a rename or a removal in it (ADR-002 §6 steps 3, 6). */
    private val flushDirectory: (File) -> Unit = ::flushDirectoryOf,
) {
    /** Whether the intent read at [run] carries Start Over, for [finish]'s failure. */
    private var finishingStartOver = false

    fun run(): StartOutcome {
        files.lastGoodTemporary.delete()
        files.intentTemporary.delete()
        // Step 1: resume an interrupted start from its intent.
        val intent = readIntent()
        // A12 of UC-010: a start that resumes from Start Over's intent deletes everything Slussen stores before any database is opened (ADR-002 §3 O-17 step 4).
        if (intent?.startOver != null) {
            finishingStartOver = true
            deleteEverything()
        }
        val resumed = intent?.cause
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
        return StartOutcome(kind, cause, unreadable, intent?.startOver)
    }

    /**
     * ADR-002 §3 O-17 step 4, in its order: the last good copy first, so that no start can bring the old data back, then
     * `slussen.db`, `device.db` with the Keystore key, `journal.db`, each with every file SQLite keeps beside it and every
     * original set aside as unreadable, then `installation.id`. A file still there after its deletion stops the start
     * with the intent kept, and the next start deletes again from the beginning.
     */
    private fun deleteEverything() {
        delete(files.lastGood)
        delete(files.lastGoodTemporary)
        deleteDatabase(files.main)
        deleteDatabase(files.device)
        // ADR-002 §2a: with device.db gone the key encrypts nothing; one the Keystore does not delete does not stop the start.
        try {
            deleteCredentialKey()
        } catch (failure: Exception) {
            Unit
        }
        deleteDatabase(files.journal)
        delete(files.installationId)
    }

    /** [database], its rollback journal, `-wal` and `-shm`, and each of them set aside as unreadable (§8 step 3). */
    private fun deleteDatabase(database: File) {
        val directory = database.parentFile ?: return
        val stem = database.name
        listOf(database, File(database.path + "-journal"), File(database.path + "-wal"), File(database.path + "-shm")).forEach(::delete)
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith("$stem.unreadable-") || it.name.matches(Regex(Regex.escape(stem) + "-(journal|wal|shm)\\.unreadable-.*")) }
            .sortedBy { it.name }
            .forEach(::delete)
    }

    private fun delete(file: File) {
        if (!file.exists()) return
        deleteFile(file)
        if (file.exists()) throw StartNotFinished("Could not delete ${file.name} at Start Over; the next start deletes it again.", startingOver = true)
    }

    /**
     * Step 6: deletes the start intent, only after First Upload Matching and the coordinator reset are committed, and
     * flushes the directory. An intent that stays stops the start (ADR-002 §3 O-17 step 5): it must never stay behind an
     * installation that goes on.
     */
    fun finish() {
        if (files.intent.exists()) deleteFile(files.intent)
        if (files.intent.exists()) throw StartNotFinished("Could not remove the start intent.", startingOver = finishingStartOver || readIntent()?.startOver != null)
        files.intent.parentFile?.let(flushDirectory)
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

    /**
     * Renames [database] and every file SQLite keeps beside it, unchanged, to an unused `.unreadable-<millis>-<random>`
     * name: its rollback journal ([rollbackJournal]) and the `-wal` and `-shm` an earlier build may have left in
     * write-ahead logging, so that the original is kept whole (ADR-002 §8 step 5) and no log is replayed into the
     * database created in its place.
     */
    private fun setAside(database: File, rollbackJournal: File) {
        val beside = listOf(rollbackJournal, File(database.path + "-wal"), File(database.path + "-shm"))
        var suffix: String
        do {
            suffix = ".unreadable-${clock.millis()}-${newRandomText()}"
        } while ((beside + database).any { File(it.path + suffix).exists() })
        check(database.renameTo(File(database.path + suffix))) { "Could not set aside ${database.name}." }
        beside.filter { it.exists() }.forEach { it.renameTo(File(it.path + suffix)) }
    }

    private fun readIntent(): StartIntent? {
        if (!files.intent.exists()) return null
        val lines = files.intent.readText(Charsets.UTF_8).lines()
        val first = lines.first().trim()
        val cause = FirstUploadMatchingCause.entries.firstOrNull { it.text == first } ?: FirstUploadMatchingCause.LastGoodCopy
        // ADR-002 §3 O-17 step 6: only the exact first two lines of Start Over's record delete; garbage never does.
        val startOver = first == FirstUploadMatchingCause.NewInstallation.text && lines.getOrNull(1)?.trim() == START_OVER_LINE
        return StartIntent(cause, if (startOver) recordOf(lines.drop(2)) else null)
    }

    private fun writeIntent(cause: FirstUploadMatchingCause) = writeIntentDurably(files, cause.text, flushDirectory)

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
