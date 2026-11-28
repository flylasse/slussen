package slussen.app.ui.consent

/**
 * What an intent that reached the activity carries, as plain values, so that plain code decides whether it is an
 * answer (UC-007 step 6, BR-011). [address] carries one-time values and is never logged or kept (BR-006), so
 * [toString] masks it.
 */
class ArrivedIntent(
    val action: String?,
    val address: String?,
    /** The activity was created again from its saved state (a configuration change, the process ended): its intent was handled before. */
    val restored: Boolean,
    /** The task was brought back from recents, which hands the activity its first intent again. */
    val fromRecents: Boolean,
) {
    override fun toString(): String = "ArrivedIntent(action=$action, address=****, restored=$restored, fromRecents=$fromRecents)"
}

/** A value the Operator typed that carries one-time values, such as a pasted address (BR-006, BR-011): masked in [toString]. */
class OneTimeText(val text: String) {
    override fun equals(other: Any?): Boolean = other is OneTimeText && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "OneTimeText(****)"
}

/** The return to Slussen from the Bank's Strong Customer Authentication (UC-007 step 6, BR-011), as plain code decides it. */
object ConsentReturn {
    /** `Intent.ACTION_VIEW`, the action a browser hands an address to the app that answers it with. */
    const val VIEW = "android.intent.action.VIEW"

    // UC-007 BR-011: Slussen's own return address is <application id>://consent, formed from the application id of the build, so that the answer never reaches the other installation.
    /** `slussen.app://consent` for a release build, `slussen.app.debug://consent` for a debug build. */
    fun ownReturnAddress(applicationId: String): String = "$applicationId://consent"

    /**
     * The address of [intent] when it is an answer of the Bank's Strong Customer Authentication at this build's own
     * return address, else null: the launcher, another address, or an intent handed again ([ArrivedIntent.restored],
     * [ArrivedIntent.fromRecents]), whose answer was handed in when it first arrived (BR-002).
     */
    fun answerIn(intent: ArrivedIntent, applicationId: String): String? {
        // UC-007 BR-002: A result is accepted at most once: an intent handed to the activity again is not handed in again.
        if (intent.restored || intent.fromRecents) return null
        if (intent.action != VIEW) return null
        val address = intent.address ?: return null
        val own = ownReturnAddress(applicationId)
        if (!address.startsWith(own)) return null
        val rest = address.substring(own.length)
        return address.takeIf { rest.isEmpty() || rest[0] == '?' || rest[0] == '#' || rest[0] == '/' }
    }
}
