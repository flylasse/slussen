package slussen.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import java.io.File

/**
 * The source files the Konsist rules read (ADR-001 §6.2), classified by module and source set from their path. The
 * scopes of the rules: **Prod** (main source sets), **Test** (test, androidTest and testFixtures source sets and
 * `:testing`), **All**.
 */
object Sources {
    val repositoryRoot: File = File(
        System.getProperty("slussen.repositoryRoot")
            ?: error("slussen.repositoryRoot is set by build-logic for :architecture-tests:test (ADR-001 §6.4)."),
    ).canonicalFile

    /** The modules of ADR-001 §1 whose code the rules read; build-logic is read only where a rule says so. */
    val appModules: List<String> = repositoryRoot.listFiles()!!
        .filter { File(it, "build.gradle.kts").isFile && File(it, "src").isDirectory }
        .map { it.name }
        .filter { it != "build-logic" }
        .sorted()

    val providerModules = appModules.filter { it.startsWith("provider-") }
    val budgetAppModules = appModules.filter { it.startsWith("budgetapp-") }
    val fileFormatModules = appModules.filter { it.startsWith("fileformat-") }
    val pluginModules = providerModules + budgetAppModules + fileFormatModules

    val scope: KoScope by lazy {
        val konsistRoot = File(Konsist.projectRootPath).canonicalFile
        check(konsistRoot == repositoryRoot) { "Konsist reads $konsistRoot, not the repository $repositoryRoot." }
        Konsist.scopeFromProject()
    }

    /** Every Kotlin source file of the repository's modules and of build-logic. */
    val all: List<SourceFile> by lazy {
        scope.files
            .filter { it.path.endsWith(".kt") }
            .map { SourceFile(it.path, it.text) }
            .filter { it.module in appModules || it.module == "build-logic" }
    }

    val prod: List<SourceFile> by lazy { all.filter { it.isProduction && it.module != "build-logic" } }

    val test: List<SourceFile> by lazy { all.filter { !it.isProduction && it.module != "build-logic" } }

    fun prodOf(vararg modules: String): List<SourceFile> = prod.filter { it.module in modules }

    fun allOf(modules: Collection<String>): List<SourceFile> =
        all.filter { it.module in modules && it.module != "build-logic" }

    /** A rule whose scope is empty fails instead of passing (ADR-001 §6.2). */
    fun <T> requireScope(rule: String, scope: List<T>): List<T> {
        assertTrue(scope.isNotEmpty(), "$rule: its scope holds no file, so it would pass vacuously (ADR-001 §6.2).")
        return scope
    }

    fun failOn(rule: String, violations: List<String>) {
        if (violations.isNotEmpty()) fail<Unit>("$rule:\n" + violations.joinToString("\n") { "  - $it" })
    }
}

/** One Kotlin source file, with its module, source set and package read from its path and text. */
class SourceFile(absolutePath: String, val text: String) {
    val relativePath: String = File(absolutePath).canonicalFile.relativeTo(Sources.repositoryRoot).invariantSeparatorsPath
    val module: String = relativePath.substringBefore('/')
    val sourceSet: String = relativePath.split('/').let { parts -> parts.getOrElse(parts.indexOf("src") + 1) { "" } }

    /** `:testing` is test-only (ADR-001 §1), so its main source set belongs to the Test scope. */
    val isProduction: Boolean = sourceSet == "main" && module != "testing" && module != "architecture-tests"

    val packageName: String = Regex("""(?m)^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1) ?: ""

    val imports: List<String> = Regex("""(?m)^\s*import\s+([\w.*]+)""").findAll(text).map { it.groupValues[1] }.toList()

    /** The text with comments blanked out; string literals stay. */
    val code: String by lazy { KotlinText.withoutComments(text) }

    val fileName: String = relativePath.substringAfterLast('/')

    fun inPackage(root: String): Boolean = packageName == root || packageName.startsWith("$root.")

    override fun toString(): String = relativePath
}

/** Small lexical helpers for the text rules. */
object KotlinText {
    /** Replaces line and block comments by spaces, keeping string and character literals and line breaks. */
    fun withoutComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(text, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != c && text[j] != '\n') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, text.length)
                    out.append(text, i, end)
                    i = end
                }
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && next == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    for (k in i until end) out.append(if (text[k] == '\n') '\n' else ' ')
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Replaces the content of every string and character literal by spaces, keeping the quotes, line breaks and every
     * position, so that a search finds only code. Apply to a text without comments.
     */
    fun maskStrings(code: String): String {
        val out = StringBuilder(code)
        var i = 0
        fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, code.length)) if (code[k] != '\n') out.setCharAt(k, ' ')
        }
        while (i < code.length) {
            val c = code[i]
            when {
                code.startsWith("\"\"\"", i) -> {
                    val end = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it }
                    blank(i + 3, end)
                    i = end + 3
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != c && code[j] != '\n') {
                        if (code[j] == '\\') j++
                        j++
                    }
                    blank(i + 1, j)
                    i = j + 1
                }
                else -> i++
            }
        }
        return out.toString()
    }

    /** The text from [open] (an opening brace or parenthesis) to its matching closing one, string literals skipped. */
    fun balanced(text: String, open: Int): String {
        val opening = text[open]
        val closing = if (opening == '{') '}' else ')'
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\') i++
                    i++
                }
            } else if (c == opening) {
                depth++
            } else if (c == closing) {
                depth--
                if (depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        return text.substring(open)
    }

    /** A `class` or `fun` declaration with the annotations written directly before it. */
    data class Declaration(val keyword: String, val name: String, val annotations: List<String>, val end: Int)

    private val modifiers = setOf(
        "private", "public", "internal", "protected", "override", "open", "abstract", "suspend", "inline", "data",
        "enum", "sealed", "inner", "annotation", "final", "operator", "infix", "tailrec", "value", "fun",
    )

    /** Scans [code] (without comments) for `class` and `fun` declarations and the annotations right before them. */
    fun annotatedDeclarations(code: String): List<Declaration> {
        val result = mutableListOf<Declaration>()
        val pending = mutableListOf<String>()
        var i = 0
        val identifier = Regex("""[A-Za-z_][\w]*""")
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> {
                    if (code.startsWith("\"\"\"", i)) {
                        i = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it + 3 }
                    } else {
                        i++
                        while (i < code.length && code[i] != '"' && code[i] != '\n') {
                            if (code[i] == '\\') i++
                            i++
                        }
                        i++
                    }
                    pending.clear()
                }
                c == '\'' -> {
                    val end = code.indexOf('\'', if (code.getOrNull(i + 1) == '\\') i + 3 else i + 2)
                    i = if (end < 0) code.length else end + 1
                    pending.clear()
                }
                c == '@' -> {
                    val found = Regex("""@[\w.:]+""").find(code, i)
                    if (found == null || found.range.first != i) {
                        // A label such as return@flatMap, not an annotation.
                        pending.clear()
                        i++
                        continue
                    }
                    val name = found.value
                    var j = i + name.length
                    while (j < code.length && code[j] == ' ') j++
                    val arguments = if (j < code.length && code[j] == '(') KotlinText.balanced(code, j) else ""
                    pending += name + arguments
                    i = if (arguments.isEmpty()) i + name.length else j + arguments.length
                }
                c.isLetter() || c == '_' -> {
                    val word = identifier.find(code, i)!!.value
                    i += word.length
                    if (word == "class" || word == "fun") {
                        val name = identifier.find(code, i)
                        if (name != null && code.substring(i, name.range.first).isBlank()) {
                            result += Declaration(word, name.value, pending.toList(), name.range.last + 1)
                            i = name.range.last + 1
                        }
                        pending.clear()
                    } else if (word !in modifiers) {
                        pending.clear()
                    }
                }
                c.isWhitespace() -> i++
                else -> {
                    pending.clear()
                    i++
                }
            }
        }
        return result
    }

    /** The words of a camel-case name, in lower case: `bankTransactionID2` gives bank, transaction, id, 2. */
    fun camelWords(name: String): List<String> =
        Regex("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+").findAll(name).map { it.value.lowercase() }.toList()
}
