package slussen.buildlogic

import java.io.File

/** The version name and version code of ADR-005 §4, read from `VERSION` at the repository root (`AGENTS.md` §5). */
data class AppVersion(val major: Int, val minor: Int, val revision: Int) {
    val versionName: String get() = "$major.$minor.$revision"

    /** major × 1,000,000 + minor × 1,000 + revision, so every bump of `VERSION` gives a higher code (NFR-017). */
    val versionCode: Int get() = major * 1_000_000 + minor * 1_000 + revision

    companion object {
        private val form = Regex("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})")

        /**
         * Reads [file]: exactly one line `major.minor.revision`, each part a whole number from 0 to 999, with or
         * without one line ending. Anything else fails the build, naming the file (ADR-005 §4).
         */
        fun read(file: File): AppVersion {
            if (!file.isFile) throw IllegalStateException("The version file ${file.path} is missing (ADR-005 section 4).")
            return parse(file.readText(Charsets.UTF_8), file.path)
        }

        fun parse(text: String, fileName: String): AppVersion {
            val line = when {
                text.endsWith("\r\n") -> text.dropLast(2)
                text.endsWith("\n") -> text.dropLast(1)
                else -> text
            }
            val match = form.matchEntire(line)
                ?: throw IllegalStateException(
                    "The version file $fileName must hold exactly one line major.minor.revision, each part a whole " +
                        "number from 0 to 999 (ADR-005 section 4).",
                )
            val (major, minor, revision) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), revision.toInt())
        }
    }
}
