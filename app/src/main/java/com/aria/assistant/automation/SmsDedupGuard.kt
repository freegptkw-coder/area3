package com.aria.assistant.automation

/**
 * Global SMS deduplication guard (singleton) to prevent dual SMS sends
 * when the same automation is triggered from multiple paths
 * (e.g., voice parse + LLM response parse)
 */
object SmsDedupGuard {
    private val sentHashes = linkedMapOf<Int, Long>() // hash -> timestamp
    private const val DEDUP_WINDOW_MS = 30_000L

    /**
     * Returns true if this exact SMS was already sent within the dedup window.
     * Automatically records the send if it's new.
     */
    @Synchronized
    fun isDuplicate(contact: String, body: String): Boolean {
        val hash = "${contact.toLowerCase()}|${body}".hashCode()
        val now = System.currentTimeMillis()

        // Purge expired entries (oldest first)
        val iterator = sentHashes.iterator()
        while (iterator.hasNext()) {
            val (_, ts) = iterator.next()
            if (now - ts > DEDUP_WINDOW_MS) {
                iterator.remove()
            } else {
                break  // LinkedHashMap is ordered, can stop early
            }
        }

        val lastSeen = sentHashes[hash]
        if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
            return true  // Duplicate within window
        }

        sentHashes[hash] = now
        return false
    }

    /** Reset all state (for testing) */
    @Synchronized
    fun reset() {
        sentHashes.clear()
    }
}
