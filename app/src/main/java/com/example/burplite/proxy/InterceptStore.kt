package com.example.burplite.proxy

import com.example.burplite.model.HttpTransaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton bridge between Netty worker threads (which handle raw
 * sockets) and the UI (Activity/ViewModel), which the user interacts
 * with to pause, edit, and forward requests. Thread-safe.
 */
object InterceptStore {

    @Volatile var interceptEnabled: Boolean = true

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

    /**
     * Called from the Netty handler. Registers the transaction and,
     * if intercept is ON, BLOCKS the current (proxy worker) thread
     * until the user forwards or drops it from the UI.
     *
     * Netty's worker pool should be sized so blocking here doesn't
     * stall unrelated connections (use a dedicated EventExecutorGroup
     * for this handler, not the I/O event loop itself).
     */
    /** Adds a rehydrated past transaction (from Room) without going through intercept/pause. */
    fun restoreFromHistory(tx: HttpTransaction) {
        transactions.putIfAbsent(tx.id, tx)
    }

    fun submit(tx: HttpTransaction) {
        transactions[tx.id] = tx
        listeners.forEach { it(tx) }

        if (interceptEnabled) {
            tx.resumeSignal.join() // waits for UI to call tx.forward()/drop()
        }
    }

    fun all(): List<HttpTransaction> = transactions.values.sortedBy { it.timestamp }

    fun get(id: String): HttpTransaction? = transactions[id]

    fun clear() = transactions.clear()
}
