package com.example.burplite.model

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** A mutable HTTP request that the UI can edit before it's forwarded. */
data class EditableRequest(
    var method: String,
    var url: String,
    var httpVersion: String = "HTTP/1.1",
    val headers: MutableMap<String, String>,
    var body: ByteArray
)

data class HttpResponseSnapshot(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray
)

/**
 * One intercepted transaction. When intercept mode is ON, the proxy
 * creates this, publishes it to the UI, and suspends on [resumeSignal]
 * until the user taps "Forward" (optionally after editing [request]).
 */
class HttpTransaction(
    val id: String = UUID.randomUUID().toString(),
    val request: EditableRequest,
    val isHttps: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    @Volatile var response: HttpResponseSnapshot? = null
    @Volatile var dropped: Boolean = false

    /** Completed by the UI when user hits Forward/Drop. */
    val resumeSignal: CompletableFuture<Void> = CompletableFuture()

    fun forward() = resumeSignal.complete(null)
    fun drop() { dropped = true; resumeSignal.complete(null) }

    // ---- response interception (edit status/body before client receives) ----

    /** True when the user chose to drop the response instead of forwarding it. */
    @Volatile var responseDropped: Boolean = false

    /** Completed by the UI when the paused response is approved/dropped/edited. */
    val responseSignal: CompletableFuture<Void> = CompletableFuture()

    @Volatile var editedStatus: Int? = null
    @Volatile var editedBody: ByteArray? = null

    fun approveResponse() = responseSignal.complete(null)

    fun rejectResponse() {
        responseDropped = true
        responseSignal.complete(null)
    }

    fun approveResponseWithEdit(status: Int, body: ByteArray) {
        editedStatus = status
        editedBody = body
        responseSignal.complete(null)
    }
}
