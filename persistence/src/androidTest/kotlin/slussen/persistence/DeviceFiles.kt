package slussen.persistence

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** The app's own files on the device or emulator, wiped before each instrumented test so every test starts fresh. */
object DeviceFiles {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun wipe() {
        val databases = SlussenDatabases.mainFile(context).parentFile
        listOfNotNull(databases, context.noBackupFilesDir).forEach { directory ->
            directory.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    fun everyFileUnder(directory: File): List<File> = directory.walkTopDown().filter { it.isFile }.toList()
}
