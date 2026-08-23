package com.example.burplite.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runtime-configurable proxy behaviour (rules & filters), inspired by the
 * feature set of classic MITM tools but written from scratch for burplte.
 *
 * Thread-safe: mutated from the UI thread, read from Netty workers.
 */
object ProxySettings {

    // ---- Intercept filter (audit-inspired granular interception) ----

    /** When false, ALL requests pause while intercept is ON. */
    @Volatile var filterEnabled: Boolean = false

    /**
     * Match expression:
     *  - "host:example.com"  → host contains example.com
     *  - "method:POST"       → exact method match
     *  - anything else       → substring match on full URL (case-insensitive)
     */
    @Volatile var filterText: String = ""

    /** When true, responses matching [filterText] pause for editing too. */
    @Volatile var responseInterceptEnabled: Boolean = false

    fun matchesFilter(url: String, method: String): Boolean {
        if (!filterEnabled) return true
        val t = filterText.trim()
        if (t.isEmpty()) return true
        return when {
            t.startsWith("host:", ignoreCase = true) -> {
                val host = url.removePrefix("https://").removePrefix("http://")
                    .substringBefore('/').substringBefore(':')
                host.contains(t.substringAfter(':').trim(), ignoreCase = true)
            }
            t.startsWith("method:", ignoreCase = true) ->
                method.equals(t.substringAfter(':').trim(), ignoreCase = true)
            else -> url.contains(t, ignoreCase = true)
        }
    }

    // ---- HTTPS handling mode ----

    enum class HttpsMode {
        /** Blind-tunnel CONNECT: no decryption, no certificate needed on device. */
        TUNNEL,
        /** Decrypt HTTPS (needs the CA trusted on device, or a custom PKCS#12). */
        MITM
    }

    /** TUNNEL by default: browsing just works without installing any certificate. */
    @Volatile var httpsMode: HttpsMode = HttpsMode.TUNNEL

    // ---- Block / Redirect rules ----

    enum class Action { BLOCK, REDIRECT }

    data class Rule(
        val id: String,
        val pattern: String,   // substring matched against the full URL
        val action: Action,
        val target: String,    // for REDIRECT: new base URL ("https://new.host")
        val enabled: Boolean = true
    )

    private val rules = CopyOnWriteArrayList<Rule>()
    private val idCounter = java.util.concurrent.atomic.AtomicLong(1)

    fun rulesList(): List<Rule> = rules.toList()

    fun addRule(pattern: String, action: Action, target: String): Rule {
        val r = Rule(
            id = "rule-${idCounter.getAndIncrement()}",
            pattern = pattern.trim(),
            action = action,
            target = target.trim()
        )
        rules.add(r)
        return r
    }

    fun removeRule(id: String) {
        rules.removeAll { it.id == id }
    }

    fun toggleRule(id: String, enabled: Boolean) {
        val idx = rules.indexOfFirst { it.id == id }
        if (idx >= 0) rules[idx] = rules[idx].copy(enabled = enabled)
    }

    fun clearRules() = rules.clear()

    /**
     * Evaluate rules against a URL (first enabled match wins).
     * @return BLOCK → blocked; REDIRECT(target) → rewrite; null → pass through.
     */
    sealed class Decision {
        object Block : Decision()
        data class Redirect(val newUrl: String) : Decision()
        object Pass : Decision()
    }

    fun evaluate(url: String): Decision {
        for (r in rules) {
            if (!r.enabled || r.pattern.isBlank()) continue
            if (url.contains(r.pattern, ignoreCase = true)) {
                return when (r.action) {
                    Action.BLOCK -> Decision.Block
                    Action.REDIRECT -> {
                        if (r.target.isBlank()) Decision.Block
                        else {
                            val path = url.removePrefix("https://").removePrefix("http://")
                                .substringAfter('/', "")
                            val base = r.target.removeSuffix("/")
                            val query = url.substringAfter('?', "")
                            Decision.Redirect(base + "/" + path + (if (query.isEmpty()) "" else "?$query"))
                        }
                    }
                }
            }
        }
        return Decision.Pass
    }
}
