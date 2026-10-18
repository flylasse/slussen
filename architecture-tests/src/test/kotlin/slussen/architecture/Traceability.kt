package slussen.architecture

import java.io.File

/**
 * The traceability checks T-01 to T-05 of ADR-004 §3, run as Konsist rule K-14 (ADR-001 §6.2). They read `docs/` from
 * the repository root, so a renamed rule or flow in the specification fails the build until tests and markers follow.
 */
object Traceability {
    /**
     * The headings of one use case file: its `### BR-YYY:` rules, its `### A<n>: <title>` flows and the ids on its
     * `**Requirements:**` line.
     */
    data class UseCaseHeadings(
        val businessRules: Set<String>,
        val flows: Set<String>,
        val requirements: Set<String> = emptySet(),
    )

    /** One `@UseCase(...)` marker as written in a test. */
    data class Marker(
        val id: String?,
        val scenario: String?,
        val businessRules: List<String>,
        val location: String,
        val requirements: List<String> = emptyList(),
    )

    fun useCaseFiles(root: File = Sources.repositoryRoot): Map<String, File> =
        File(root, "docs/use_cases").listFiles { f -> f.name.matches(Regex("UC-\\d{3}-.*\\.md")) }.orEmpty()
            .associateBy { it.name.substring(0, 6) }

    fun headings(file: File): UseCaseHeadings {
        val lines = file.readLines()
        val rules = lines.mapNotNull { Regex("""^### (BR-\d{3}):""").find(it)?.groupValues?.get(1) }.toSet()
        val flows = lines.mapNotNull { Regex("""^### (A\d+: .+?)\s*$""").find(it)?.groupValues?.get(1) }.toSet()
        val requirements = lines.firstOrNull { it.startsWith("**Requirements:**") }
            ?.let { line -> requirementOnLine.findAll(line).map { it.value }.toSet() }
            .orEmpty()
        return UseCaseHeadings(rules, flows, requirements)
    }

    fun violations(tests: List<SourceFile>, production: List<SourceFile>): List<String> {
        val useCases = useCaseFiles().mapValues { headings(it.value) }
        return t01(tests) + t02(tests, useCases) + t03(production, useCases) + t04(production) +
            t05(tests + production, avoidTerms())
    }

    private val useCaseClass = Regex("""UC(\d{3})\w+(Test|IT)""")
    private val requirementOnLine = Regex("""\b(?:FR|NFR|C)-\d{3}\b""")
    private val requirementMarker = Regex("""(?:NFR|C)-\d{3}""")

    /** Every `@UseCase(...)` in [text], with its arguments read from the Kotlin source. */
    fun markers(text: String, location: String): List<Marker> {
        val code = KotlinText.withoutComments(text)
        return Regex("""@UseCase\s*\(""").findAll(KotlinText.maskStrings(code)).map { found ->
            val arguments = KotlinText.balanced(code, found.range.last)
            fun named(name: String) = Regex("""\b$name\s*=\s*"([^"]*)"""").find(arguments)?.groupValues?.get(1)
            val id = named("id") ?: Regex("""^\(\s*"([^"]*)"""").find(arguments)?.groupValues?.get(1)
            fun list(name: String) = Regex("""\b$name\s*=\s*\[([^\]]*)]""").find(arguments)?.groupValues?.get(1)
                ?.let { list -> Regex(""""([^"]*)"""").findAll(list).map { it.groupValues[1] }.toList() }
                .orEmpty()
            Marker(id, named("scenario"), list("businessRules"), location, list("requirements"))
        }.toList()
    }

    /**
     * T-01: every class whose name matches `UC\d{3}\w+(Test|IT)` has `@UseCase` on each `@Test` method (or on the class),
     * and every `id` equals the class's prefix written `UC-XXX`.
     */
    fun t01(tests: List<SourceFile>): List<String> = tests.flatMap { file ->
        val code = KotlinText.withoutComments(file.text)
        KotlinText.annotatedDeclarations(code)
            .filter { it.keyword == "class" && useCaseClass.matches(it.name) }
            .flatMap { cls ->
                val expected = "UC-" + useCaseClass.matchEntire(cls.name)!!.groupValues[1]
                val classMarked = cls.annotations.any { it.startsWith("@UseCase") }
                val bodyStart = code.indexOf('{', cls.end)
                val body = if (bodyStart < 0) "" else KotlinText.balanced(code, bodyStart)
                val problems = mutableListOf<String>()
                KotlinText.annotatedDeclarations(body).filter { it.keyword == "fun" }.forEach { function ->
                    val isTest = function.annotations.any { it == "@Test" || it.startsWith("@Test(") }
                    if (isTest && !classMarked && function.annotations.none { it.startsWith("@UseCase") }) {
                        problems += "T-01 $file: ${cls.name}.${function.name} is a @Test without @UseCase"
                    }
                }
                val annotations = cls.annotations.joinToString(" ") + " " + body
                markers(annotations, "$file").filter { it.id != expected }.forEach {
                    problems += "T-01 $file: ${cls.name} carries @UseCase(id = \"${it.id}\"), expected \"$expected\""
                }
                problems
            }
    }

    /**
     * T-02: every `@UseCase.id` names an existing use case file; every `businessRules` entry is a `### BR-YYY:` heading
     * in it; every `scenario` other than `Main Success Scenario` matches a `### A<n>: <title>` heading in it; every
     * `requirements` entry is an `NFR-` or `C-` id on its `**Requirements:**` line.
     */
    fun t02(tests: List<SourceFile>, useCases: Map<String, UseCaseHeadings>): List<String> =
        tests.flatMap { file -> markers(file.text, "$file") }.flatMap { marker ->
            val headings = marker.id?.let { useCases[it] }
                ?: return@flatMap listOf("T-02 ${marker.location}: @UseCase id ${marker.id} names no docs/use_cases file")
            val problems = mutableListOf<String>()
            marker.businessRules.filterNot { it in headings.businessRules }.forEach {
                problems += "T-02 ${marker.location}: ${marker.id} has no business rule $it"
            }
            marker.requirements.filterNot { requirementMarker.matches(it) && it in headings.requirements }.forEach {
                problems += "T-02 ${marker.location}: ${marker.id} does not link the NFR or C $it"
            }
            val scenario = marker.scenario
            if (!scenario.isNullOrEmpty() && scenario != "Main Success Scenario" && scenario !in headings.flows) {
                problems += "T-02 ${marker.location}: ${marker.id} has no flow \"$scenario\""
            }
            problems
        }

    private val markerLine = Regex("""^\s*// UC-""")
    private val markerForm = Regex("""^\s*// (UC-\d{3}) (BR-\d{3}): .+$""")
    private val bareRule = Regex("""^\s*// BR-\d{3}\b""")

    /**
     * T-03: every production comment that starts with `// UC-` has the form `// UC-\d{3} BR-\d{3}: .+` and names a rule
     * heading of the named use case; a comment `// BR-\d{3}` without its use case fails.
     */
    fun t03(production: List<SourceFile>, useCases: Map<String, UseCaseHeadings>): List<String> =
        production.flatMap { file ->
            file.text.lines().mapIndexedNotNull { index, line ->
                val where = "T-03 $file:${index + 1}"
                when {
                    bareRule.containsMatchIn(line) -> "$where: a rule marker without its use case"
                    !markerLine.containsMatchIn(line) -> null
                    else -> {
                        val match = markerForm.find(line)
                        when {
                            match == null -> "$where: not of the form // UC-XXX BR-YYY: <rule>"
                            useCases[match.groupValues[1]] == null -> "$where: ${match.groupValues[1]} has no use case file"
                            match.groupValues[2] !in useCases.getValue(match.groupValues[1]).businessRules ->
                                "$where: ${match.groupValues[1]} has no ${match.groupValues[2]}"
                            else -> null
                        }
                    }
                }
            }
        }

    /** T-04: the next non-blank line after an implementation marker is code or a KDoc block. */
    fun t04(production: List<SourceFile>): List<String> = production.flatMap { file ->
        val lines = file.text.lines()
        lines.indices.filter { markerLine.containsMatchIn(lines[it]) }.mapNotNull { index ->
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() }?.trim()
            when {
                next == null -> "T-04 $file:${index + 1}: a marker at the end of the file"
                next.startsWith("/**") -> null
                next.startsWith("//") || next.startsWith("/*") -> "T-04 $file:${index + 1}: a marker followed by a comment"
                else -> null
            }
        }
    }

    /** The Avoid column of `docs/glossary.md`, each term as its lower-case words. */
    fun avoidTerms(root: File = Sources.repositoryRoot): List<List<String>> =
        File(root, "docs/glossary.md").readLines()
            .filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| Term ") }
            .flatMap { row ->
                val cells = row.trim().removePrefix("|").removeSuffix("|").split("|")
                cells.last().split(",")
            }
            .map { term -> term.trim().lowercase().split(Regex("[\\s-]+")).filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * Exceptions to T-05, each with its reason (ADR-004 §3: "Every exception is listed in the test with its reason").
     */
    private val t05Exceptions: List<Pair<(SourceFile, List<String>) -> Boolean, String>> = listOf(
        { file: SourceFile, term: List<String> ->
            file.module.startsWith("fileformat-") && term.all { it in file.module.removePrefix("fileformat-").split('-') }
        } to "A File Pre-processor module is named after the export format it reads (O-P1-01, ADR-001 §1); its own " +
            "classes may carry that name, for example Csv in :fileformat-hsa-csv.",
    )

    /**
     * T-05: no class, function, property or parameter name declared in `slussen..` uses a term of the Avoid column of
     * `docs/glossary.md` as camel-case words of the name.
     */
    fun t05(files: List<SourceFile>, avoid: List<List<String>>): List<String> {
        val declaration = Regex(
            """\b(?:class|interface|object|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(`?)([A-Za-z_]\w*)\1""",
        )
        val parameter = Regex("""[(,]\s*(?:(?:private|internal|public|protected|override|vararg|val|var|crossinline|noinline)\s+)*([a-z]\w*)\s*:""")
        return files.filter { it.inPackage("slussen") }.flatMap { file ->
            val code = KotlinText.maskStrings(KotlinText.withoutComments(file.text))
            val names = (declaration.findAll(code).map { it.groupValues[2] } + parameter.findAll(code).map { it.groupValues[1] })
                .toSet()
            names.flatMap { name ->
                val words = KotlinText.camelWords(name)
                avoid.filter { term -> words.windowed(term.size).any { it == term } }
                    .filterNot { term -> t05Exceptions.any { (applies, _) -> applies(file, term) } }
                    .map { "T-05 $file: $name uses the glossary's Avoid term \"${it.joinToString(" ")}\"" }
            }
        }
    }
}
