package slussen.buildlogic

import java.io.File
import java.util.zip.ZipFile

/**
 * Searches every entry of a built package (dex files, resources, assets, manifest) for planted values, compared as
 * bytes in UTF-8 and UTF-16 (both byte orders), as ADR-003 §3 asks.
 */
object PackageScanner {
    fun encodings(value: String): List<ByteArray> = listOf(
        value.toByteArray(Charsets.UTF_8),
        value.toByteArray(Charsets.UTF_16LE),
        value.toByteArray(Charsets.UTF_16BE),
    )

    /** Each finding as "entry: value", empty when no value appears in any entry. */
    fun findings(apk: File, values: Collection<String>): List<String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.flatMap { entry ->
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            values.filter { value -> encodings(value).any { contains(bytes, it) } }.map { "${entry.name}: $it" }
        }.toList()
    }

    fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
