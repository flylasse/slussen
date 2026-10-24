package slussen.architecture

/** The logging rules L-01 to L-06 of ADR-006 §5, run as Konsist rule K-13 (ADR-001 §6.2). */
object LoggingRules {
    private fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

    fun violations(scope: List<SourceFile>): List<String> =
        l01(scope) + l02(scope) + l03(scope) + l04(scope) + l05(scope) + l06(scope)

    /** L-01: `android.util.Log` is imported, or named fully qualified, only in `slussen.app.platform.log`. */
    fun l01(scope: List<SourceFile>): List<String> {
        val log = Regex("""\bandroid\.util\.Log\b""")
        return scope.filterNot { it.inPackage("slussen.app.platform.log") }.flatMap { file ->
            log.findAll(file.code).map { "L-01 $file:${lineOf(file.text, it.range.first)}: android.util.Log" }.toList()
        }
    }

    /** L-02: no production code calls `println`, `print`, `System.out`, `System.err` or `printStackTrace`. */
    fun l02(scope: List<SourceFile>): List<String> {
        val output = Regex("""(?<![\w.])print(ln)?\s*\(|\bSystem\s*\.\s*(out|err)\b|\bprintStackTrace\b""")
        return scope.flatMap { file ->
            output.findAll(file.code).map { "L-02 $file:${lineOf(file.text, it.range.first)}: ${it.value}" }.toList()
        }
    }

    /** L-03: no file imports or names `okhttp3.logging`. */
    fun l03(scope: List<SourceFile>): List<String> = scope.filter { "okhttp3.logging" in it.code }.map { "L-03 $it" }

    private val optIn = Regex("""@(file:)?OptIn\([^)]*\bSensitiveApi\b""")

    /**
     * L-04: `@OptIn(SensitiveApi::class)` appears only in plug-in modules and `:persistence`, and never in a file that
     * references `SlussenLog`.
     */
    fun l04(scope: List<SourceFile>): List<String> = scope.filter { optIn.containsMatchIn(it.code) }.mapNotNull { file ->
        when {
            file.module !in Sources.pluginModules && file.module != "persistence" ->
                "L-04 $file: opts in to SensitiveApi outside the plug-in modules and :persistence"
            Regex("""\bSlussenLog\b""").containsMatchIn(file.code) ->
                "L-04 $file: opts in to SensitiveApi and references SlussenLog"
            else -> null
        }
    }

    /**
     * L-05: no call of `SlussenLog.detail` builds its message from `.message`, `.localizedMessage` or
     * `.stackTraceToString()` of a `Throwable`.
     */
    fun l05(scope: List<SourceFile>): List<String> {
        val detailCall = Regex("""\.detail\s*\(""")
        val throwableText = Regex("""\.(message|localizedMessage)\b|\.stackTraceToString\s*\(""")
        return scope.flatMap { file ->
            detailCall.findAll(file.code).mapNotNull { call ->
                val open = file.code.indexOf('(', call.range.first)
                var arguments = KotlinText.balanced(file.code, open)
                val after = open + arguments.length
                val lambda = Regex("""^\s*\{""").find(file.code.substring(after))
                if (lambda != null) arguments += KotlinText.balanced(file.code, after + lambda.range.last)
                if (throwableText.containsMatchIn(arguments)) {
                    "L-05 $file:${lineOf(file.text, call.range.first)}: detail message built from a Throwable's text"
                } else {
                    null
                }
            }.toList()
        }
    }

    /** L-06: every class that implements `LogEvent` is an enum class without a `String` constructor parameter or property. */
    fun l06(scope: List<SourceFile>): List<String> {
        val paths = scope.map { it.relativePath }.toSet()
        fun inScope(path: String) = SourceFile(path, "").relativePath in paths
        val violations = mutableListOf<String>()
        Sources.scope.classes(true, true).filter { inScope(it.path) }
            .filter { it.parents(false).any { p -> p.name == "LogEvent" } }
            .forEach { cls ->
                if (!cls.hasEnumModifier) violations += "L-06 ${cls.name} implements LogEvent but is not an enum class"
                val stringParameters = if (cls.hasPrimaryConstructor) {
                    cls.primaryConstructor!!.parameters.filter { it.type.name.removeSuffix("?") == "String" }
                } else {
                    emptyList()
                }
                val stringProperties = cls.properties(true).filter { it.type?.name?.removeSuffix("?") == "String" }
                if (stringParameters.isNotEmpty() || stringProperties.isNotEmpty()) {
                    violations += "L-06 ${cls.name} has a String constructor parameter or property"
                }
            }
        Sources.scope.objects(true).filter { inScope(it.path) }
            .filter { it.parents(false).any { p -> p.name == "LogEvent" } }
            .forEach { violations += "L-06 ${it.name} implements LogEvent but is an object, not an enum class" }
        Sources.scope.interfaces(true).filter { inScope(it.path) }
            .filter { it.parents(false).any { p -> p.name == "LogEvent" } }
            .forEach { violations += "L-06 ${it.name} extends LogEvent but is an interface, not an enum class" }
        return violations
    }
}
