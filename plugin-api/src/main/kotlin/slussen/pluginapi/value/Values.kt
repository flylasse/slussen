package slussen.pluginapi.value

/**
 * Marks the functions that reveal a credential or session value (ADR-006 §2). Only plug-in modules and
 * `:persistence` may opt in (Konsist L-04), and bytecode rule B-06 checks who calls them.
 */
@RequiresOptIn(
    message = "Reveals a credential or session value. Only plug-in modules and :persistence may opt in (ADR-006 §2).",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class SensitiveApi

/** An amount in signed thousandths of the currency unit, the entity model's precision `12,3` (ADR-001 §4). */
@JvmInline
public value class Amount(public val thousandths: Long)

/** An ISO 4217 currency code of three capital letters. */
@JvmInline
public value class CurrencyCode(public val code: String) {
    init {
        require(code.length == 3 && code.all { it in 'A'..'Z' }) { "A currency code is three capital letters." }
    }
}

/**
 * A credential or session value (ADR-001 §4, ADR-006 §2). Its `toString()` returns a fixed mask; its content is read
 * only through [reveal].
 */
public class Secret(private val value: String) {
    @SensitiveApi
    public fun reveal(): String = value

    override fun toString(): String = MASK

    public companion object {
        public const val MASK: String = "Secret(****)"
    }
}

/**
 * A Bank Account number as the Data Provider delivers it, letters and formatting included, masked like [Secret]. The
 * core turns it into the one-way `bank_account_fingerprint` and the `last_four_digits` of an Offered Bank Account
 * (ADR-008 §8) and never stores it.
 */
public class BankAccountNumber(public val delivered: String) {
    override fun toString(): String = MASK

    public companion object {
        public const val MASK: String = "BankAccountNumber(****)"
    }
}
