package slussen.app.platform.document

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import slussen.core.port.JournalExportDocument
import java.io.FileNotFoundException
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * The document Android's save dialog gave for a Journal Export (UC-011 step 2; `ACTION_CREATE_DOCUMENT`, so no permission
 * is needed): written in UTF-8 from its start, and removed again through its documents provider when the export does not
 * complete (A3). Its [name] is the one the Operator kept or typed in the dialog.
 */
class PickedDocument(private val resolver: ContentResolver, private val uri: Uri) : JournalExportDocument {
    /** The name the document has at its place, asked of its provider off the main thread; null when it does not say. */
    suspend fun name(): String? = withContext(Dispatchers.IO) {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (failure: Exception) {
            null
        }
    }

    /** "wt" empties a document that already held something; a provider that does not know it is opened with "w". */
    override fun open(): Writer {
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (unsupported: FileNotFoundException) {
            resolver.openOutputStream(uri, "w")
        } catch (unsupported: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        } ?: throw FileNotFoundException("The chosen place gave no stream.")
        return OutputStreamWriter(stream, Charsets.UTF_8)
    }

    override fun remove(): Boolean =
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (failure: Exception) {
            false
        }
}
