package slussen.pluginapi.testfixtures

/**
 * The test marker of ADR-004 §2: names the use case, the scenario and the business rules a test proves. Checked by
 * the traceability rules T-01 and T-02 of `:architecture-tests`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class UseCase(
    /** "UC-001". */
    val id: String,
    /** "Main Success Scenario" or "A1: Data Provider Asked Slussen to Wait". */
    val scenario: String = "",
    /** Bare ids: ["BR-004"]. */
    val businessRules: Array<String> = [],
)
