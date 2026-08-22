package com.example.burplite.proxy

import com.example.burplite.model.HttpTransaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Singleton bridge between Netty worker threads (which handle raw
 * sockets) and the UI (Activity/ViewModel), which the user interacts
 * with to pause, edit, and forward requests. Thread-safe.
 *
 * Performance notes (see docs/proxy-performance-audit.md):
 * - [interceptEnabled] defaults to FALSE so normal browsing never
 *   blocks a worker thread (audit B1).
 * - When intercept IS enabled, [submit] no longer blocks forever:
 *   after [interceptTimeoutMs] it auto-forwards so a forgotten pending
 *   request can never deadlock the executor (audit B2/B6).
 * - In-memory history is capped at [MAX_IN_MEMORY]; Room keeps the
 *   full history on disk (audit B7).
 */
object InterceptStore {

    /** OFF by default: browsing stays fast; users opt in from the UI. */
    @Volatile var interceptEnabled: Boolean = false

    /** How long an intercepted request may wait for the user before auto-forwarding. */
    @Volatile var interceptTimeoutMs: Long = 60_000L

    private val transactions = ConcurrentHashMap<String, HttpTransaction>()
    private val listeners = CopyOnWriteArrayList<(HttpTransaction) -> Unit>()
    private val completeListeners = CopyOnWriteArrayList<(HttpTransaction) -> Unit>()

    fun onNewTransaction(listener: (HttpTransaction) -> Unit) {
        listeners.add(listener)
    }

    /** Fired once a transaction's response has arrived (for persistence to Room, etc). */
    fun onTransactionComplete(listener: (HttpTransaction) -> Unit) {
        completeListeners.add(listener)
    }

    fun notifyComplete(tx: HttpTransaction) {
        completeListeners.forEach { it(tx) }
    }

    /** Adds a rehydrated past transaction (from Room) without going through intercept/pause. */
    fun restoreFromHistory(tx: HttpTransaction) {
        transactions.putIfAbsent(tx.id, tx)
    }

    fun submit(tx: HttpTransaction) {
        transactions[tx.id] = tx
        evictOldestSettledIfNeeded()
        listeners.forEach { it(tx) }

        if (interceptEnabled) {
            try {
                tx.resumeSignal.get(interceptTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // Auto-forward instead of blocking a worker thread forever.
                tx.forward()
            }
        }
    }

    fun all(): List<HttpTransaction> = transactions.values.sortedBy { it.timestamp }

    fun get(id: String): HttpTransaction? = transactions[id]

    fun clear() = transactions.clear()

    private fun evictOldestSettledIfNeeded() {
        if (transactions.size <= MAX_IN_MEMORY) return
        transactions.values
            .filter { it.response != null || it.dropped }
            .minByOrNull { it.timestamp }
            ?.let { transactions.remove(it.id) }
    }

    private const val MAX_IN_MEMORY = 500
}
