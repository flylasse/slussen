package slussen.core.request

import slussen.core.port.ProviderRequestKind
import slussen.core.port.ProviderRequestStore
import slussen.core.port.StoredProviderRequest
import java.time.Duration
import java.time.Instant

/**
 * The Unattended Accesses of the Bank Accounts read through one Data Provider that needs a Bank Consent (UC-001 BR-012,
 * C-006, glossary Unattended Access), counted only from the stored Provider Requests: those of that Data Provider sent
 * in the preceding 24 hours, closed at its start so that a request sent exactly 24 hours ago is still counted (the safe
 * side against a Bank that counts any 24 hours closed at both ends; the Request Limit's own window is open there), without the Operator's presence (`unattended`), with the Bank Account they read and their kind. A read of the
 * transactions is counted by its first request, of kind Transactions, however many pages followed (each further page is
 * of kind Other, ADR-001 §4.0); a read of the balance by its request of kind Balance. A request is counted whatever
 * started its Fetch and however that Fetch ended, failed or stopped included, since the gate stored it before it let it
 * out. The one count that the gate and the Scheduled Fetch (A13), the question before an Operator's Fetch (A22) and the
 * import status (UC-008 BR-006) use.
 */
class UnattendedAccesses private constructor(private val counted: List<StoredProviderRequest>) {
    /** The Unattended Accesses of [kind], Transactions or Balance, that [bankAccountId] used in the preceding 24 hours. */
    fun used(bankAccountId: Long, kind: ProviderRequestKind): Int = sentAt(bankAccountId, kind).size

    // UC-001 BR-012: A Scheduled Fetch reads the transactions of a Bank Account at most 4 times and its balance at most 4 times in any 24 hours.
    /** True when a further unattended read of [kind] of [bankAccountId] would exceed the limit PSD2 sets (C-006). */
    fun reached(bankAccountId: Long, kind: ProviderRequestKind): Boolean = used(bankAccountId, kind) >= UNATTENDED_ACCESS_LIMIT

    /**
     * When a read of [kind] of [bankAccountId] is free again: when so many of its counted accesses have left the 24-hour
     * window that fewer than the limit remain, that is one millisecond, the precision at which a Provider Request's time
     * is stored, after the deciding access is 24 hours old; or null while the limit is not reached.
     */
    fun freesUpAt(bankAccountId: Long, kind: ProviderRequestKind): Instant? {
        val sent = sentAt(bankAccountId, kind)
        if (sent.size < UNATTENDED_ACCESS_LIMIT) return null
        return sent[sent.size - UNATTENDED_ACCESS_LIMIT].plus(REQUEST_LIMIT_WINDOW).plus(STORED_PRECISION)
    }

    private fun sentAt(bankAccountId: Long, kind: ProviderRequestKind): List<Instant> =
        counted.filter { it.bankAccountId == bankAccountId && it.kind == kind }.map { it.sentAt }.sorted()

    companion object {
        /** The precision at which a Provider Request's time is stored (`PROVIDER_REQUEST.sent_at`, in milliseconds). */
        private val STORED_PRECISION: Duration = Duration.ofMillis(1)

        /**
         * The time after which [ProviderRequestStore.sentSince] gives every request this count needs at [now], the one
         * sent exactly at the window's start included.
         */
        fun readSince(now: Instant): Instant = now.minus(REQUEST_LIMIT_WINDOW).minus(STORED_PRECISION)

        /** The Unattended Accesses through the Data Provider [dataProviderId] at [now], from [requests] as stored. */
        fun of(requests: List<StoredProviderRequest>, dataProviderId: Long, now: Instant): UnattendedAccesses {
            val windowStart = now.minus(REQUEST_LIMIT_WINDOW)
            return UnattendedAccesses(
                requests.filter {
                    it.dataProviderId == dataProviderId && it.unattended && it.bankAccountId != null &&
                        it.kind != ProviderRequestKind.Other && !it.sentAt.isBefore(windowStart)
                },
            )
        }

        suspend fun of(requests: ProviderRequestStore, dataProviderId: Long, now: Instant): UnattendedAccesses =
            of(requests.sentSince(readSince(now)), dataProviderId, now)
    }
}
