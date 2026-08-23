package com.example.burplite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.burplite.model.*
import com.example.burplite.proxy.InterceptStore
import com.example.burplite.proxy.ProxySettings
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

    /** Responses paused for review/edit before reaching the client. */
    private val _pendingResponses = MutableStateFlow<List<HttpTransaction>>(emptyList())
    val pendingResponses: StateFlow<List<HttpTransaction>> = _pendingResponses.asStateFlow()

    // ---- proxy settings mirrors (single source of truth: ProxySettings) ----

    private val _filterEnabled = MutableStateFlow(ProxySettings.filterEnabled)
    val filterEnabled: StateFlow<Boolean> = _filterEnabled.asStateFlow()

    private val _filterText = MutableStateFlow(ProxySettings.filterText)
    val filterText: StateFlow<String> = _filterText.asStateFlow()

    private val _responseInterceptOn = MutableStateFlow(ProxySettings.responseInterceptEnabled)
    val responseInterceptOn: StateFlow<Boolean> = _responseInterceptOn.asStateFlow()

    private val _rules = MutableStateFlow(ProxySettings.rulesList())
    val rules: StateFlow<List<ProxySettings.Rule>> = _rules.asStateFlow()

    /** Audit B1: intercept OFF by default so browsing is never blocked. */
    private val _interceptEnabled = MutableStateFlow(false)
    val interceptEnabled: StateFlow<Boolean> = _interceptEnabled.asStateFlow()

    private val _repeaterResult = MutableStateFlow<HttpResponseSnapshot?>(null)
    val repeaterResult: StateFlow<HttpResponseSnapshot?> = _repeaterResult.asStateFlow()

    private val _repeaterSending = MutableStateFlow(false)
    val repeaterSending: StateFlow<Boolean> = _repeaterSending.asStateFlow()

    /** Intruder-lite state */
    data class FuzzResult(val payload: String, val status: Int, val size: Int, val bodyPreview: String)

    private val _fuzzRunning = MutableStateFlow(false)
    val fuzzRunning: StateFlow<Boolean> = _fuzzRunning.asStateFlow()

    private val _fuzzResults = MutableStateFlow<List<FuzzResult>>(emptyList())
    val fuzzResults: StateFlow<List<FuzzResult>> = _fuzzResults.asStateFlow()

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
            _pendingResponses.value = _pendingResponses.value.filter { it.id != tx.id }
            persist(tx)
        }
        InterceptStore.onResponsePending { tx ->
            if (tx.response != null) {
                _pendingResponses.value = _pendingResponses.value + tx
            }
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

    // ---- rules & filters (v2) ----

    fun setInterceptFilter(enabled: Boolean, text: String) {
        ProxySettings.filterEnabled = enabled
        ProxySettings.filterText = text
        _filterEnabled.value = enabled
        _filterText.value = text
    }

    fun setResponseIntercept(enabled: Boolean) {
        ProxySettings.responseInterceptEnabled = enabled
        _responseInterceptOn.value = enabled
    }

    fun addRule(pattern: String, action: ProxySettings.Action, target: String) {
        if (pattern.isBlank()) return
        ProxySettings.addRule(pattern, action, target)
        _rules.value = ProxySettings.rulesList()
    }

    fun removeRule(id: String) {
        ProxySettings.removeRule(id)
        _rules.value = ProxySettings.rulesList()
    }

    fun toggleRule(id: String, enabled: Boolean) {
        ProxySettings.toggleRule(id, enabled)
        _rules.value = ProxySettings.rulesList()
    }

    fun clearRules() {
        ProxySettings.clearRules()
        _rules.value = emptyList()
    }

    // ---- response interception queue ----

    fun approveResponse(tx: HttpTransaction) {
        tx.approveResponse()
        _pendingResponses.value = _pendingResponses.value.filter { it.id != tx.id }
    }

    fun rejectResponse(tx: HttpTransaction) {
        tx.rejectResponse()
        _pendingResponses.value = _pendingResponses.value.filter { it.id != tx.id }
    }

    fun approveResponseWithEdit(tx: HttpTransaction, status: Int, body: String) {
        tx.approveResponseWithEdit(status, body.toByteArray())
        _pendingResponses.value = _pendingResponses.value.filter { it.id != tx.id }
    }

    /**
     * Intruder-lite: replace every "{FUZZ}" marker in the URL, headers and
     * body with each payload, fire sequentially and collect results.
     */
    fun runFuzz(method: String, url: String, headers: Map<String, String>, bodyTemplate: String, payloadLines: List<String>) {
        if (_fuzzRunning.value) return
        val payloads = payloadLines.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        _fuzzRunning.value = true
        _fuzzResults.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = mutableListOf<FuzzResult>()
                for (p in payloads) {
                    val u = url.replace("{FUZZ}", p)
                    val b = bodyTemplate.replace("{FUZZ}", p)
                    try {
                        val conn = URL(u).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 30_000
                        conn.requestMethod = method
                        headers.forEach { (k, v) ->
                            if (!k.equals("Host", true)) {
                                conn.setRequestProperty(k.replace("{FUZZ}", p), v.replace("{FUZZ}", p))
                            }
                        }
                        if (b.isNotEmpty()) {
                            conn.doOutput = true
                            conn.outputStream.write(b.toByteArray())
                        }
                        val status = conn.responseCode
                        val respBody = (if (status < 400) conn.inputStream else conn.errorStream)
                            ?.readBytes() ?: ByteArray(0)
                        results += FuzzResult(p, status, respBody.size, String(respBody).take(4000))
                    } catch (e: Exception) {
                        results += FuzzResult(p, -1, 0, "Error: ${e.message}")
                    }
                    _fuzzResults.value = results.toList()
                }
            } finally {
                _fuzzRunning.value = false
            }
        }
    }

    fun clearFuzzResults() {
        if (!_fuzzRunning.value) _fuzzResults.value = emptyList()
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

