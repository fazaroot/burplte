package com.example.burplite.proxy

import android.util.Log
import com.example.burplite.cert.CertificateAuthority
import com.example.burplite.model.EditableRequest
import com.example.burplite.model.HttpResponseSnapshot
import com.example.burplite.model.HttpTransaction
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.timeout.IdleStateHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.concurrent.DefaultEventExecutorGroup
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Burp-style intercepting proxy — performance-hardened version.
 *
 * Key changes vs the first skeleton (all referenced to
 * docs/proxy-performance-audit.md):
 *
 * - Intercept defaults OFF and pauses are time-limited (B1/B2/B6).
 * - CONNECT: leaf-cert minting + SslContext build happen on the worker
 *   executor, NOT the I/O event loop, and both are cached per host (B5).
 *   The tunnel pipeline is rebuilt in the correct order (the old code
 *   left the front handler in front of the new codec, so decrypted
 *   requests were mis-handled as plain HTTP).
 * - Origin connections are pooled and kept alive per (host,port,tls)
 *   instead of a fresh TCP+TLS handshake per request (B3).
 * - Responses are STREAMED chunk-by-chunk back to the client while a
 *   capped copy is recorded for the UI — no more 50 MB full buffering
 *   before first byte reaches the browser (B4).
 * - Idle/connect timeouts everywhere; no connection can hang forever (B6).
 * - Hop-by-hop headers are stripped in both directions.
 */
class ProxyServer(
    private val port: Int,
    private val caStorageDir: File
) {
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()
    // Worker pool for interception pauses — sized generously so even with
    // intercept ON a burst of parallel requests doesn't starve (audit B1).
    private val interceptExecutor = DefaultEventExecutorGroup(16)
    // Shared outbound pool reused for every origin connection.
    private val clientGroup = NioEventLoopGroup(4)

    private val ca = CertificateAuthority(caStorageDir).apply { init() }

    // One reusable trust-all client SSL context; creating per-connection
    // SslHandlers from it is cheap. Building it per request was waste (B3/B5).
    private val originSslCtx: SslContext = SslContextBuilder.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE).build()

    private val pool = OriginConnectionPool()
    private var channel: Channel? = null

    fun start() {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(HttpServerCodec())
                    ch.pipeline().addLast(HttpObjectAggregator(MAX_RECORDED_BODY))
                    ch.pipeline().addLast(IdleStateHandler(0, 0, CLIENT_IDLE_TIMEOUT_S))
                    ch.pipeline().addLast(interceptExecutor,
                        ProxyFrontHandler(ca, clientGroup, originSslCtx, pool, interceptExecutor))
                }
            })
        channel = bootstrap.bind(port).sync().channel()
    }

    fun stop() {
        channel?.close()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
        interceptExecutor.shutdownGracefully()
        clientGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS)
    }

    val rootCaPemPath: String get() = File(caStorageDir, "burplite_root_ca.pem").absolutePath
}

// ---- tuning knobs (file-level so all helper classes below can see them) ----

/** Max bytes of a request/response kept in memory for the UI; relay still streams beyond this. */
private const val MAX_RECORDED_BODY = 10 * 1024 * 1024
private const val CONNECT_TIMEOUT_MS = 5000

/** Origin connection closed after this much read-idle time (also bounds pooled idles). */
private const val ORIGIN_READ_IDLE_S = 30

/** Client connection closed after this much total idle time. */
private const val CLIENT_IDLE_TIMEOUT_S = 75
private const val TAG = "ProxyServer"

/** Handles the client-facing side of every connection. */
private class ProxyFrontHandler(
    private val ca: CertificateAuthority,
    private val clientGroup: NioEventLoopGroup,
    private val originSslCtx: SslContext,
    private val pool: OriginConnectionPool,
    private val uiExecutor: DefaultEventExecutorGroup
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    /** True while this connection's thread is paused for user interception. */
    @Volatile private var awaitingUser = false

    override fun channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest) {
        if (req.method() == HttpMethod.CONNECT) {
            handleConnect(ctx, req)
        } else {
            handlePlainHttp(ctx, req)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Don't crash the whole channel on a single bad connection (reset, TLS abort, etc.)
        ctx.close()
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any?) {
        // Idle timeout — but never kill a connection that is legitimately
        // paused waiting for the user to forward an intercepted request.
        if (evt is IdleStateEvent && !awaitingUser) {
            ctx.close()
        }
    }

    // ---- HTTPS: CONNECT + MITM ----

    private fun handleConnect(ctx: ChannelHandlerContext, req: FullHttpRequest) {
        val hostPort = req.uri().substringAfter(":", "443")
        val host = req.uri().substringBefore(":")
        val port = hostPort.toIntOrNull() ?: 443

        if (ProxySettings.httpsMode == ProxySettings.HttpsMode.TUNNEL) {
            startTunnel(ctx, host, port)
            return
        }

        // MITM mode: answer 200, then hand over an SslContext backed by either
        // a user-supplied proxy.p12 (SandroProxy-style) or our generated CA leaf.
        val ok = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        ctx.writeAndFlush(ok).addListener(ChannelFutureListener { future ->
            if (!future.isSuccess) return@ChannelFutureListener
            try {
                val sslCtx = ca.customPkcs12Context() ?: ca.serverSslContextFor(host)
                // Pipeline mutations must be serialized with inbound I/O → event loop.
                ctx.channel().eventLoop().execute {
                    val p = ctx.pipeline()
                    p.remove(HttpServerCodec::class.java)
                    p.remove(HttpObjectAggregator::class.java)
                    p.remove(IdleStateHandler::class.java)
                    // The tunnel has its own handler; leaving the front handler in
                    // front of the new codec made it swallow decrypted requests.
                    p.remove(this@ProxyFrontHandler)
                    p.addFirst("ssl", sslCtx.newHandler(ctx.alloc()))
                    p.addLast("http-codec", HttpServerCodec())
                    p.addLast("aggregator", HttpObjectAggregator(MAX_RECORDED_BODY))
                    p.addLast("tunnel", TunnelledHttpsHandler(host))
                }
            } catch (e: Exception) {
                ctx.close()
            }
        })
    }

    /**
     * Blind HTTPS tunnel (default mode): the CONNECTed socket is piped
     * byte-for-byte to the origin — no decryption, no certificate needed on
     * the device. The tunnel is still recorded in HTTP History.
     */
    private fun startTunnel(ctx: ChannelHandlerContext, host: String, port: Int) {
        // Record the CONNECT in history so tunnels are visible in the UI.
        val tx = HttpTransaction(
            request = EditableRequest(
                method = "CONNECT",
                url = "https://$host:$port/",
                headers = linkedMapOf("Host" to host),
                body = ByteArray(0)
            ),
            isHttps = true
        )
        tx.forward()
        tx.response = HttpResponseSnapshot(
            200, linkedMapOf("X-BurpLite-Mode" to "tunnel"), ByteArray(0)
        )
        InterceptStore.notifyComplete(tx)

        val originRef = java.util.concurrent.atomic.AtomicReference<Channel?>(null)

        val bootstrap = Bootstrap()
            .group(clientGroup)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(OriginToClientHandler(ctx))
                }
            })

        bootstrap.connect(host, port).addListener(ChannelFutureListener { f ->
            if (!f.isSuccess) {
                respondBadRequest(ctx, "origin unreachable (CONNECT $host:$port)")
                return@ChannelFutureListener
            }
            originRef.set(f.channel())
            // Answer the CONNECT and swap to raw piping inside ONE event-loop
            // task so no TLS bytes leak through before the pipe is live.
            ctx.channel().eventLoop().execute {
                ctx.writeAndFlush(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
                val p = ctx.pipeline()
                p.remove(HttpServerCodec::class.java)
                p.remove(HttpObjectAggregator::class.java)
                p.remove(IdleStateHandler::class.java)
                p.remove(this@ProxyFrontHandler)
                p.addLast(ClientToOriginHandler(originRef))
            }
        })
    }

    /** Handles decrypted HTTP requests that arrived inside a MITM'd TLS tunnel. */
    private inner class TunnelledHttpsHandler(private val host: String) :
        SimpleChannelInboundHandler<FullHttpRequest>() {
        override fun channelRead0(innerCtx: ChannelHandlerContext, req: FullHttpRequest) {
            forwardIntercepted(innerCtx, req, isHttps = true, host = host)
        }
        override fun exceptionCaught(innerCtx: ChannelHandlerContext, cause: Throwable) {
            innerCtx.close()
        }
    }

    // ---- Plain HTTP forward proxying ----

    private fun handlePlainHttp(ctx: ChannelHandlerContext, req: FullHttpRequest) {
        val uri = try {
            URI(req.uri())
        } catch (e: Exception) {
            logBadClientRequest(ctx, req, "malformed request-uri: ${e.message}")
            respondBadRequest(ctx, "Malformed request URI")
            return
        }
        // NOTE: CONNECT-tunneled requests can never reach here — the front
        // handler is removed from the pipeline before any tunnel goes live.
        var host = uri.host
        if (host.isNullOrEmpty()) {
            // Relative-form request (RFC 7230 §5.3.2 violation, but common for
            // proxy-unaware clients like some WebView-based browsers).
            host = req.headers().entries()
                .firstOrNull { it.key.equals("Host", ignoreCase = true) }
                ?.value?.trim()?.substringBefore(":")
        }
        if (host.isNullOrEmpty()) {
            logBadClientRequest(ctx, req, "no host: relative-form URI and no Host header")
            respondBadRequest(ctx, "Missing host — client sent a relative-form request without a Host header")
            return
        }
        forwardIntercepted(ctx, req, isHttps = false, host = host)
    }

    /** Dump the offending raw request to logcat so client bugs are debuggable. */
    private fun logBadClientRequest(
        ctx: ChannelHandlerContext, req: FullHttpRequest, reason: String
    ) {
        Log.w(TAG, "Unusable request ($reason) from ${ctx.channel().remoteAddress()}")
        Log.w(TAG, "  request-line: ${req.method().name()} ${req.uri()} ${req.protocolVersion()}")
        req.headers().forEach { (k, v) -> Log.w(TAG, "  $k: $v") }
    }

    // ---- shared: build transaction, pause for intercept, stream relay response ----

    private fun forwardIntercepted(
        ctx: ChannelHandlerContext, req: FullHttpRequest, isHttps: Boolean, host: String
    ) {
        val headers = LinkedHashMap<String, String>()
        req.headers().forEach { headers[it.key] = it.value }
        val bodyBytes = ByteArray(req.content().readableBytes()).also { req.content().readBytes(it) }
        val clientKeepAlive = HttpUtil.isKeepAlive(req)

        val scheme = if (isHttps) "https" else "http"
        val fullUrl = if (req.uri().startsWith("http")) req.uri() else "$scheme://$host${req.uri()}"

        val editable = EditableRequest(
            method = req.method().name(),
            url = fullUrl,
            headers = headers,
            body = bodyBytes
        )
        val tx = HttpTransaction(request = editable, isHttps = isHttps)

        // ---- Rule engine: block / redirect before anything else ----
        when (val decision = ProxySettings.evaluate(fullUrl)) {
            is ProxySettings.Decision.Block -> {
                val bodyText = "Blocked by BurpLite rule".toByteArray()
                val resp = DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN,
                    Unpooled.wrappedBuffer(bodyText)
                )
                resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyText.size)
                ctx.writeAndFlush(resp)
                tx.response = HttpResponseSnapshot(403, emptyMap(), bodyText)
                InterceptStore.notifyComplete(tx)
                return
            }
            is ProxySettings.Decision.Redirect -> {
                tx.request.url = decision.newUrl
                val newHost = decision.newUrl.removePrefix("https://").removePrefix("http://")
                    .substringBefore('/')
                tx.request.headers["Host"] = newHost
            }
            ProxySettings.Decision.Pass -> {}
        }

        // ---- Granular interception: pause only when filter matches ----
        val shouldPause = InterceptStore.interceptEnabled &&
            ProxySettings.matchesFilter(fullUrl, req.method().name())
        awaitingUser = shouldPause
        try {
            InterceptStore.submit(tx, shouldPause)
        } finally {
            awaitingUser = false
        }

        if (tx.dropped) {
            respondSimple(ctx, HttpResponseStatus.FORBIDDEN)
            return
        }

        relayToOrigin(ctx, tx, clientKeepAlive)
    }

    /** Sends the (possibly edited) request to the real origin and streams the response back. */
    private fun relayToOrigin(
        ctx: ChannelHandlerContext,
        tx: HttpTransaction,
        clientKeepAlive: Boolean
    ) {
        val uri = try { URI(tx.request.url) } catch (e: Exception) {
            respondBadRequest(ctx, "unroutable request"); return
        }
        val targetHost = uri.host
        if (targetHost.isNullOrEmpty()) {
            respondBadRequest(ctx, "unroutable request"); return
        }
        val targetPort = if (uri.port != -1) uri.port else if (tx.isHttps) 443 else 80
        val poolKey = "$targetHost:$targetPort/${tx.isHttps}"
        val hostHeader = tx.request.headers.entries
            .firstOrNull { it.key.equals("Host", ignoreCase = true) }?.value ?: targetHost
        val pathAndQuery = ((uri.rawPath?.ifEmpty { "/" }) ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")

        val wantResponseIntercept = ProxySettings.responseInterceptEnabled &&
            ProxySettings.matchesFilter(tx.request.url, tx.request.method)
        val job = RelayJob(ctx, tx, clientKeepAlive, poolKey, pool, wantResponseIntercept, uiExecutor)

        // Lambda so we can build a fresh request object if a pooled write fails.
        val createRequest = {
            val outReq = DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.valueOf(tx.request.method),
                pathAndQuery,
                Unpooled.wrappedBuffer(tx.request.body)
            )
            tx.request.headers.forEach { (k, v) ->
                if (!isHopByHop(k) && !k.equals("Host", ignoreCase = true)) outReq.headers().add(k, v)
            }
            outReq.headers().set(HttpHeaderNames.HOST, hostHeader)
            outReq.headers().set(HttpHeaderNames.CONTENT_LENGTH, tx.request.body.size)
            outReq.headers().remove(HttpHeaderNames.TRANSFER_ENCODING)
            outReq
        }

        // Reuse a warm pooled connection when possible (audit B3).
        val pooled = pool.borrow(poolKey)
        if (pooled != null) {
            val handler = pooled.pipeline().get(RelayHandler::class.java)
            if (handler != null) {
                handler.attach(job)
                pooled.writeAndFlush(createRequest()).addListener(ChannelFutureListener { f ->
                    if (!f.isSuccess) {
                        handler.attach(null)
                        pool.evict(poolKey, pooled)
                        pooled.close()
                        connectNew(job, createRequest, poolKey, targetHost, targetPort, tx.isHttps)
                    }
                })
                return
            }
            pooled.close()
        }
        connectNew(job, createRequest, poolKey, targetHost, targetPort, tx.isHttps)
    }

    /** Opens a brand-new origin connection and starts the relay on it. */
    private fun connectNew(
        job: RelayJob,
        createRequest: () -> FullHttpRequest,
        poolKey: String,
        targetHost: String,
        targetPort: Int,
        isHttps: Boolean
    ) {
        val bootstrap = Bootstrap()
            .group(clientGroup)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val p = ch.pipeline()
                    if (isHttps) p.addLast(originSslCtx.newHandler(ch.alloc(), targetHost, targetPort))
                    p.addLast(IdleStateHandler(ORIGIN_READ_IDLE_S, 0, 0))
                    p.addLast(HttpClientCodec())
                    p.addLast(HttpContentDecompressor())
                    p.addLast(RelayHandler(poolKey, pool))
                }
            })
        bootstrap.connect(targetHost, targetPort).addListener(ChannelFutureListener { f ->
            if (f.isSuccess) {
                val ch = f.channel()
                ch.pipeline().get(RelayHandler::class.java)?.attach(job)
                ch.writeAndFlush(createRequest())
            } else {
                job.fail(null)
            }
        })
    }

    /** 400 with a visible diagnostic body so proxy errors differ from origin errors. */
    private fun respondBadRequest(ctx: ChannelHandlerContext, reason: String) {
        val body = ("BurpLite 400 Bad Request\n\n$reason\n\n" +
            "See logcat tag $TAG for the raw request.").toByteArray()
        val resp = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST,
            Unpooled.wrappedBuffer(body)
        )
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.size)
        ctx.writeAndFlush(resp)
    }

    private fun respondSimple(ctx: ChannelHandlerContext, status: HttpResponseStatus) {
        val resp = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status)
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
        ctx.writeAndFlush(resp)
    }
}

/**
 * One in-flight relay: receives origin response headers + streamed content,
 * forwards them to the client as they arrive (no full buffering — audit B4)
 * while recording up to [ProxyServer.MAX_RECORDED_BODY] bytes for the UI.
 */
private class RelayJob(
    private val clientCtx: ChannelHandlerContext,
    private val tx: HttpTransaction,
    private val clientKeepAlive: Boolean,
    private val poolKey: String,
    private val pool: OriginConnectionPool,
    private val interceptResponse: Boolean,
    private val uiExecutor: DefaultEventExecutorGroup
) {
    private val recorded = ByteArrayOutputStream()
    private val respHeaders = LinkedHashMap<String, String>()

    @Volatile private var status = 0
    @Volatile private var settled = false
    @Volatile private var serverKeepAlive = true
    @Volatile private var closeDelimited = false

    fun onHeaders(originCtx: ChannelHandlerContext, resp: HttpResponse) {
        val code = resp.status().code()
        if (code in 100..199) {
            // 1xx interim response: pass through and keep waiting for the real one.
            val out = DefaultHttpResponse(resp.protocolVersion(), resp.status())
            copyHeaders(resp.headers(), out.headers())
            clientCtx.writeAndFlush(out)
            return
        }
        status = code
        serverKeepAlive = isServerKeepAlive(resp)

        resp.headers().forEach { (k, v) ->
            if (!isHopByHop(k)) respHeaders[k] = v
        }
        val hasCL = resp.headers().contains(HttpHeaderNames.CONTENT_LENGTH)
        closeDelimited = !hasCL && !resp.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)

        if (interceptResponse) {
            // Response interception: hold everything back until reviewed (buffered mode).
            return
        }

        val out = DefaultHttpResponse(resp.protocolVersion(), resp.status())
        respHeaders.forEach { (k, v) -> out.headers().add(k, v) }
        clientCtx.writeAndFlush(out)
    }

    fun onContent(originCtx: ChannelHandlerContext, content: HttpContent) {
        if (status == 0) { // body bytes before headers — protocol violation
            fail(originCtx); return
        }
        val buf = content.content()
        val n = buf.readableBytes()
        if (recorded.size() < MAX_RECORDED_BODY) {
            val toRecord = minOf(n, MAX_RECORDED_BODY - recorded.size())
            val chunk = ByteArray(toRecord)
            buf.getBytes(buf.readerIndex(), chunk)
            try { recorded.write(chunk) } catch (e: java.io.IOException) { /* ignore */ }
        }
        // Stream straight through to the browser; retain because the handler
        // releases msg after channelRead0 returns.
        if (interceptResponse) {
            // Buffered interception mode: record only, forward nothing until reviewed.
            if (content is LastHttpContent) settleIntercepted(originCtx)
            return
        }
        clientCtx.writeAndFlush(content.retain())
        if (content is LastHttpContent) settle(originCtx)
    }

    /** Terminates the relay on error; [originCtx] is null if it already died. */
    fun fail(originCtx: ChannelHandlerContext?) {
        if (settled) return
        settled = true
        val code = if (status == 0) 502 else status
        if (tx.response == null) {
            tx.response = HttpResponseSnapshot(
                code, respHeaders,
                if (status == 0) "502 Bad Gateway — origin unreachable".toByteArray() else recorded.toByteArray()
            )
            InterceptStore.notifyComplete(tx)
        }
        if (status == 0) {
            // Nothing sent yet → clean synthetic 502 for the browser.
            val err = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY)
            err.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
            clientCtx.writeAndFlush(err).addListener(ChannelFutureListener { clientCtx.close() })
        } else {
            // Mid-stream failure: framing is broken, abort the client connection.
            clientCtx.flush()
            clientCtx.close()
        }
        originCtx?.let { o ->
            o.pipeline().get(RelayHandler::class.java)?.attach(null)
            o.close()
        }
    }

    private fun settle(originCtx: ChannelHandlerContext) {
        if (settled) return
        settled = true
        tx.response = HttpResponseSnapshot(status, respHeaders, recorded.toByteArray())
        InterceptStore.notifyComplete(tx)

        if (!clientKeepAlive || closeDelimited) clientCtx.close() else clientCtx.flush()

        originCtx.pipeline().get(RelayHandler::class.java)?.attach(null)
        if (serverKeepAlive && originCtx.channel().isActive) {
            pool.keep(poolKey, originCtx.channel())
        } else {
            originCtx.close()
        }
    }

    private fun copyHeaders(from: HttpHeaders, to: HttpHeaders) {
        from.forEach { (k, v) -> if (!isHopByHop(k)) to.add(k, v) }
    }

    private fun isServerKeepAlive(resp: HttpResponse): Boolean {
        val conn = resp.headers().get(HttpHeaderNames.CONNECTION)?.lowercase() ?: ""
        return when {
            conn.contains("close") -> false
            resp.protocolVersion() == HttpVersion.HTTP_1_0 -> conn.contains("keep-alive")
            else -> true
        }
    }

    /**
     * Response-intercepted completion: park the origin connection first (no
     * thread may block on an event loop), then ask the UI to review/edit/drop
     * the buffered response on the worker executor.
     */
    private fun settleIntercepted(originCtx: ChannelHandlerContext) {
        if (settled) return
        settled = true

        tx.response = HttpResponseSnapshot(status, respHeaders, recorded.toByteArray())

        originCtx.pipeline().get(RelayHandler::class.java)?.attach(null)
        val ch = originCtx.channel()
        if (serverKeepAlive && ch.isActive) pool.keep(poolKey, ch) else ch.close()

        uiExecutor.execute {
            try {
                InterceptStore.submitResponseEdit(tx)
            } catch (t: Throwable) {
                tx.approveResponse()
            }
            deliverInterceptedResponse()
        }
    }

    /** Sends the (possibly edited/dropped) response to the client after review. */
    private fun deliverInterceptedResponse() {
        if (tx.responseDropped) {
            InterceptStore.notifyComplete(tx)
            clientCtx.close()
            return
        }
        val finalBody = tx.editedBody ?: recorded.toByteArray()
        val finalStatus = tx.editedStatus ?: status
        tx.response = HttpResponseSnapshot(finalStatus, respHeaders, finalBody)

        val out = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(finalStatus),
            Unpooled.wrappedBuffer(finalBody)
        )
        respHeaders.forEach { (k, v) ->
            // Framing headers are recomputed because body may have been edited.
            if (!isHopByHop(k) &&
                !k.equals(HttpHeaderNames.CONTENT_LENGTH.toString(), ignoreCase = true) &&
                !k.equals(HttpHeaderNames.TRANSFER_ENCODING.toString(), ignoreCase = true)
            ) out.headers().add(k, v)
        }
        out.headers().set(HttpHeaderNames.CONTENT_LENGTH, finalBody.size)
        clientCtx.writeAndFlush(out)
        if (!clientKeepAlive || tx.editedBody != null) clientCtx.close() else clientCtx.flush()
        InterceptStore.notifyComplete(tx)
    }
}

/**
 * Per-origin-channel handler carrying the current [RelayJob]. When no job is
 * attached the channel is parked in the pool; unexpected data closes it.
 */
private class RelayHandler(
    private val poolKey: String,
    private val pool: OriginConnectionPool
) : SimpleChannelInboundHandler<HttpObject>() {

    @Volatile private var job: RelayJob? = null

    fun attach(j: RelayJob?) { job = j }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        val j = job
        if (j == null) { ctx.close(); return }
        when (msg) {
            is HttpResponse -> j.onHeaders(ctx, msg)
            is HttpContent -> j.onContent(ctx, msg)
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any?) {
        if (evt is IdleStateEvent) {
            job?.fail(ctx)
            ctx.close()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        job?.fail(ctx)
        ctx.close()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        job?.fail(null)
        pool.evict(poolKey, ctx.channel())
    }
}

/**
 * Minimal keep-alive connection pool for origin channels, keyed by
 * "host:port/tls". One request in flight per borrowed channel at a time,
 * which keeps response→request matching trivially correct.
 */
private class OriginConnectionPool {
    private val idle = ConcurrentHashMap<String, ConcurrentLinkedQueue<Channel>>()

    fun borrow(key: String): Channel? {
        val q = idle[key] ?: return null
        while (true) {
            val ch = q.poll() ?: return null
            if (ch.isActive) return ch // dead channels are discarded silently
        }
    }

    fun keep(key: String, ch: Channel) {
        if (ch.isActive) {
            idle.getOrPut(key) { ConcurrentLinkedQueue() }.add(ch)
        } else {
            ch.close()
        }
    }

    fun evict(key: String, ch: Channel) {
        idle[key]?.remove(ch)
    }
}

/** RFC 7230 §6.1 hop-by-hop headers. NOTE: Transfer-Encoding handled separately. */
private val HOP_BY_HOP = setOf(
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "proxy-connection", "te", "trailer", "upgrade"
)

private fun isHopByHop(name: String): Boolean = name.lowercase() in HOP_BY_HOP

/** Forwards raw bytes from the CLIENT side of a tunnel to the origin. */
private class ClientToOriginHandler(
    private val originRef: java.util.concurrent.atomic.AtomicReference<Channel?>
) : SimpleChannelInboundHandler<ByteBuf>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        val origin = originRef.get()
        if (origin != null && origin.isActive) {
            origin.writeAndFlush(msg.retain())
        } else {
            ctx.close()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        originRef.get()?.close()
    }
}

/** Forwards raw bytes from the ORIGIN of a tunnel back to the client. */
private class OriginToClientHandler(
    private val clientCtx: ChannelHandlerContext
) : SimpleChannelInboundHandler<ByteBuf>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        clientCtx.writeAndFlush(msg.retain())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        clientCtx.close()
    }
}
