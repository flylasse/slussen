package slussen.app.platform.document

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import slussen.app.ui.fileimport.FileTooLargeException
import slussen.app.ui.fileimport.TransactionFileSource
import java.io.FileNotFoundException

/**
 * The Transaction File Android's document dialog gave (UC-002 step 3; `ACTION_OPEN_DOCUMENT`, so no permission is
 * needed): its whole content, read through its documents provider off the main thread (UC-002 BR-001: no size limit).
 * What the provider throws, a document that is gone, a permission taken back or a file too large for memory, is thrown
 * as an exception for the screen to answer (NFR-023); nothing is kept of the file but what the core stores.
 */
class ChosenTransactionFile(private val resolver: ContentResolver, private val uri: Uri) : TransactionFileSource {
    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        try {
            val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException("The chosen document gave no stream.")
            stream.use { it.readBytes() }
        } catch (tooLarge: OutOfMemoryError) {
            throw FileTooLargeException()
        }
    }
}
