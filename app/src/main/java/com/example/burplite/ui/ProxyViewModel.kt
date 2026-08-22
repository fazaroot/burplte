package com.example.burplite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.burplite.model.*
import com.example.burplite.proxy.InterceptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.net.HttpURLConnection
import java.net.URL

class ProxyViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BurpLiteDatabase.get(app).transactionDao()

    private val _transactions = MutableStateFlow<List<HttpTransaction>>(emptyList())
    val transactions: StateFlow<List<HttpTransaction>> = _transactions.asStateFlow()

    /**
     * QUEUE of requests currently paused for interception (audit B2: the old
     * single-slot `_pending` overwrote waiting transactions so they could never
     * be forwarded — a permanent worker-thread deadlock).
     */
    private val _pending = MutableStateFlow<List<HttpTransaction>>(emptyList())
    val pending: StateFlow<List<HttpTransaction>> = _pending.asStateFlow()

    /** Audit B1: intercept OFF by default so browsing is never blocked. */
    private val _interceptEnabled = MutableStateFlow(false)
    val interceptEnabled: StateFlow<Boolean> = _interceptEnabled.asStateFlow()

    private val _repeaterResult = MutableStateFlow<HttpResponseSnapshot?>(null)
    val repeaterResult: StateFlow<HttpResponseSnapshot?> = _repeaterResult.asStateFlow()

    private val _repeaterSending = MutableStateFlow(false)
    val repeaterSending: StateFlow<Boolean> = _repeaterSending.asStateFlow()

    private var historyLoaded = false

    init {
        InterceptStore.onNewTransaction { tx ->
            _transactions.value = InterceptStore.all()
            if (InterceptStore.interceptEnabled) {
                _pending.value = _pending.value + tx
            }
        }
        InterceptStore.onTransactionComplete { tx ->
            _transactions.value = InterceptStore.all()
            _pending.value = _pending.value.filter { it.id != tx.id }
            persist(tx)
        }
    }

    fun loadHistory() {
        if (historyLoaded) return
        historyLoaded = true
        viewModelScope.launch(Dispatchers.IO) {
            val saved = dao.getAll()
            saved.forEach { entity -> InterceptStore.restoreFromHistory(entity.toReadOnlyTransaction()) }
            _transactions.value = InterceptStore.all()
        }
    }

    fun toggleIntercept(enabled: Boolean) {
        InterceptStore.interceptEnabled = enabled
        _interceptEnabled.value = enabled
    }

    /** Called by the editor UI right before forwarding — mutates the paused transaction. */
    fun updateRequest(tx: HttpTransaction, method: String, url: String, headers: Map<String, String>, body: String) {
        tx.request.method = method
        tx.request.url = url
        tx.request.headers.clear()
        tx.request.headers.putAll(headers)
        tx.request.body = body.toByteArray()
    }

    fun forward(tx: HttpTransaction) {
        tx.forward()
        _pending.value = _pending.value.filter { it.id != tx.id }
    }

    fun drop(tx: HttpTransaction) {
        tx.drop()
        _pending.value = _pending.value.filter { it.id != tx.id }
    }

    fun forwardAll() {
        _pending.value.forEach { it.forward() }
        _pending.value = emptyList()
    }

    fun dropAll() {
        _pending.value.forEach { it.drop() }
        _pending.value = emptyList()
    }

    /** Repeater: resend an arbitrary edited request, outside the intercept flow. */
    fun repeaterSend(method: String, url: String, headers: Map<String, String>, body: String) {
        if (_repeaterSending.value) return
        _repeaterSending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.requestMethod = method
                headers.forEach { (k, v) -> if (!k.equals("Host", true)) conn.setRequestProperty(k, v) }
                if (body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.write(body.toByteArray())
                }
                val status = conn.responseCode
                val respHeaders = conn.headerFields.mapValues { it.value.joinToString(";") }
                    .filterKeys { it != null } as Map<String, String>
                val respBody = (if (status < 400) conn.inputStream else conn.errorStream)
                    ?.readBytes() ?: ByteArray(0)
                _repeaterResult.value = HttpResponseSnapshot(status, respHeaders, respBody)
            } catch (e: Exception) {
                _repeaterResult.value = HttpResponseSnapshot(
                    -1, emptyMap(), "Error: ${e.message}".toByteArray()
                )
            } finally {
                _repeaterSending.value = false
            }
        }
    }

    fun clearHistory() {
        InterceptStore.clear()
        _transactions.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { dao.clearAll() }
    }

    private fun persist(tx: HttpTransaction) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsert(
                TransactionEntity(
                    id = tx.id,
                    method = tx.request.method,
                    url = tx.request.url,
                    requestHeaders = Json.encodeToString(tx.request.headers),
                    requestBody = tx.request.body,
                    statusCode = tx.response?.statusCode,
                    responseHeaders = tx.response?.headers?.let { Json.encodeToString(it) },
                    responseBody = tx.response?.body,
                    isHttps = tx.isHttps,
                    timestamp = tx.timestamp
                )
            )
        }
    }
}

