package slussen.core.port

import slussen.core.consent.ConsentNotEnded

/**
 * Start Over's one storage step in the running process (ADR-002 §3 O-17 step 2): it records, durably, in the start intent
 * of ADR-002 §6, that the next start deletes everything Slussen stores before any database is opened and starts as a new
 * installation with a First Upload Matching. Nothing is deleted by it.
 */
interface StartOverStore {
    /**
     * Writes the record with [notEnded], the Banks whose Bank Consent was not confirmed as ended, in the same write (O-17
     * step 2), so that the start that deletes shows them. Returns only once it is durable; throws, having deleted nothing,
     * when it cannot be written.
     */
    suspend fun recordStartOver(notEnded: List<ConsentNotEnded>)
}
