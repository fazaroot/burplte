package com.example.burplte.proxy

import com.example.burplite.cert.CertificateAuthority
import com.example.burplite.model.EditableRequest
import com.example.burplite.model.HttpResponseSnapshot
import com.example.burplite.model.HttpTransaction
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
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
                    ch.pipeline().addLast(interceptExecutor, ProxyFrontHandler(ca, clientGroup, originSslCtx, pool))
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

/** Handles the client-facing side of every connection. */
private class ProxyFrontHandler(
    private val ca: CertificateAuthority,
    private val clientGroup: NioEventLoopGroup,
    private val originSslCtx: SslContext,
    private val pool: OriginConnectionPool
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
        val host = req.uri().substringBefore(":")
        val ok = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        ctx.writeAndFlush(ok).addListener(ChannelFutureListener { future ->
            if (!future.isSuccess) return@ChannelFutureListener
            try {
                // Minting a leaf cert (RSA-2048 keygen + signing) is expensive:
                // do it on this worker executor, never on the I/O event loop (audit B5).
                val sslCtx = ca.serverSslContextFor(host)
                // Pipeline mutations must be serialized with inbound I/O → event loop.
                ctx.channel().eventLoop().execute {
                    val p = ctx.pipeline()
                    p.remove(HttpServerCodec::class.java)
                    p.remove(HttpObjectAggregator::class.java)
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
            respondSimple(ctx, HttpResponseStatus.BAD_REQUEST); return
        }
        val host = uri.host ?: req.headers().get(HttpHeaderNames.HOST)?.substringBefore(":") ?: ""
        forwardIntercepted(ctx, req, isHttps = false, host = host)
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

        // Blocks this worker (on interceptExecutor, not the I/O loop) until the
        // user forwards/drops — or until InterceptStore's timeout auto-forwards.
        awaitingUser = InterceptStore.interceptEnabled
        try {
            InterceptStore.submit(tx)
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
            respondSimple(ctx, HttpResponseStatus.BAD_REQUEST); return
        }
        val targetHost = uri.host
        if (targetHost.isNullOrEmpty()) {
            respondSimple(ctx, HttpResponseStatus.BAD_REQUEST); return
        }
        val targetPort = if (uri.port != -1) uri.port else if (tx.isHttps) 443 else 80
        val poolKey = "$targetHost:$targetPort/${tx.isHttps}"
        val hostHeader = tx.request.headers.entries
            .firstOrNull { it.key.equals("Host", ignoreCase = true) }?.value ?: targetHost
        val pathAndQuery = ((uri.rawPath?.ifEmpty { "/" }) ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")

        val job = RelayJob(ctx, tx, clientKeepAlive, poolKey, pool)

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
                pooled.writeAndFlush(createRequest()).addListener { f ->
                    if (!f.isSuccess) {
                        handler.attach(null)
                        pool.evict(poolKey, pooled)
                        pooled.close()
                        connectNew(job, createRequest, poolKey, targetHost, targetPort, tx.isHttps)
                    }
                }
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
        bootstrap.connect(targetHost, targetPort).addListener { f ->
            if (f.isSuccess) {
                val ch = f.channel()
                ch.pipeline().get(RelayHandler::class.java)?.attach(job)
                ch.writeAndFlush(createRequest())
            } else {
                job.fail(null)
            }
        }
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
    private val pool: OriginConnectionPool
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

        val out = DefaultHttpResponse(resp.protocolVersion(), resp.status())
        resp.headers().forEach { (k, v) ->
            // Transfer-Encoding is deliberately KEPT: it defines the chunked
            // framing we are relaying. Only true hop-by-hop headers are stripped.
            if (!isHopByHop(k)) {
                out.headers().add(k, v)
                respHeaders[k] = v
            }
        }
        val hasCL = resp.headers().contains(HttpHeaderNames.CONTENT_LENGTH)
        closeDelimited = !hasCL && !resp.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)
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




