package slussen.core.transfer

import slussen.core.port.StoredBankTransaction
import slussen.core.port.StoredTransferDescription
import slussen.core.port.TransferDescriptionKind
import java.text.Normalizer
import java.util.Locale

/** Matching a Transfer Description: an entry of the Card Payment Descriptions or Bank Transfer Descriptions. Pure. */
object TransferDescriptions {
    private val accents = Regex("\\p{M}+")

    /** [text] without regard to case and accents: ÖVERFÖRING and OVERFORING become the same text. */
    private fun withoutCaseAndAccents(text: String): String =
        accents.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")

    // UC-005 BR-012: An entry matches when its text occurs anywhere in the payee or memo, without regard to case and accents; a * stands for any characters and no other character is special.
    /**
     * Whether the entry [entry] matches [text], a payee or a memo. The parts of the entry between its `*` must occur in
     * [text] in their order, each as it is written; an entry that holds nothing but `*` matches nothing.
     */
    fun matches(entry: String, text: String?): Boolean {
        if (text == null) return false
        val parts = entry.split('*').map(::withoutCaseAndAccents).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        val within = withoutCaseAndAccents(text)
        var from = 0
        for (part in parts) {
            val at = within.indexOf(part, from)
            if (at < 0) return false
            from = at + part.length
        }
        return true
    }

    /** Whether an entry of the list [kind] among [descriptions] matches the payee or the memo of [transaction]. */
    fun matchesAny(descriptions: List<StoredTransferDescription>, kind: TransferDescriptionKind, transaction: StoredBankTransaction): Boolean =
        descriptions.any { it.kind == kind && (matches(it.text, transaction.payee) || matches(it.text, transaction.memo)) }
}
