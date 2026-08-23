package com.example.burplite.proxy

/**
 * Minimal runtime state shared between the foreground service and the UI,
 * so the app can surface "proxy is really listening on which port" without
 * OS-notification dependence (fix: Bug 2 / invisible proxy).
 */
object ProxyState {
    @Volatile var running: Boolean = false
    @Volatile var port: Int = -1
    @Volatile var lastError: String? = null
}
