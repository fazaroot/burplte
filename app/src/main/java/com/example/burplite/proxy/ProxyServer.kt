package com.example.burplite.proxy

import com.example.burplite.cert.CertificateAuthority
import com.example.burplite.model.EditableRequest
import com.example.burplite.model.HttpResponseSnapshot
import com.example.burplite.model.HttpTransaction
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.concurrent.DefaultEventExecutorGroup
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Minimal Burp-style intercepting proxy.
 *
 * - Plain HTTP requests: parsed, handed to InterceptStore (pauses if
 *   intercept is ON), then forwarded with a plain HTTP client.
 * - CONNECT (HTTPS): we answer "200 Connection Established", perform a
 *   TLS handshake with the CLIENT using a leaf cert minted on the fly
 *   for the requested host (signed by our root CA), decrypt the inner
 *   HTTP traffic, intercept it the same way, then re-encrypt and relay
 *   to the real origin server over a fresh TLS client connection.
 *
 * Only usable against traffic where the client trusts our root CA
 * (install it manually on the test device) — i.e. lab environments.
 *
 * NOT supported: WebSocket upgrade tunneling (CONNECT for wss/ws is
 * out of scope for this skeleton — such connections will fail to
 * intercept cleanly; add a dedicated WebSocketFrame path if needed).
 */
class ProxyServer(
    private val port: Int,
    private val caStorageDir: File
) {
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()
    // Separate pool so InterceptStore.submit() blocking (pause/edit) never stalls Netty's I/O loop
    private val interceptExecutor = DefaultEventExecutorGroup(8)
    // Single shared client-side loop group reused for every outbound (origin) connection,
    // instead of spinning up a new NioEventLoopGroup per request.
    private val clientGroup = NioEventLoopGroup(4)

    private val ca = CertificateAuthority(caStorageDir).apply { init() }
    private var channel: Channel? = null

    fun start() {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(HttpServerCodec())
                    ch.pipeline().addLast(HttpObjectAggregator(50 * 1024 * 1024))
                    ch.pipeline().addLast(interceptExecutor, ProxyFrontHandler(ca, clientGroup))
                }
            })
        channel = bootstrap.bind(port).sync().channel()
    }

    fun stop() {
        channel?.close()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
        interceptExecutor.shutdownGracefully()
        clientGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS)
    }

    val rootCaPemPath: String get() = File(caStorageDir, "burplite_root_ca.pem").absolutePath
}

/** Handles the client-facing side of every connection. */
private class ProxyFrontHandler(
    private val ca: CertificateAuthority,
    private val clientGroup: NioEventLoopGroup
) : SimpleChannelInboundHandler<FullHttpRequest>() {

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

    // ---- HTTPS: CONNECT + MITM ----

    private fun handleConnect(ctx: ChannelHandlerContext, req: FullHttpRequest) {
        val hostPort = req.uri() // "host:443"
        val host = hostPort.substringBefore(":")

        val ok = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        ctx.writeAndFlush(ok).addListener(ChannelFutureListener { future ->
            if (!future.isSuccess) return@ChannelFutureListener
            try {
                ctx.pipeline().remove(HttpServerCodec::class.java)
                val (leafCert, leafKey) = ca.certFor(host)
                val sslCtx = SslContextBuilder.forServer(leafKey, leafCert, ca.rootCert).build()
                ctx.pipeline().addFirst("ssl", sslCtx.newHandler(ctx.alloc()))
                ctx.pipeline().addLast(HttpServerCodec())
                ctx.pipeline().addLast(HttpObjectAggregator(50 * 1024 * 1024))
                ctx.pipeline().addLast(TunnelledHttpsHandler(host))
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
        val uri = URI(req.uri())
        val host = uri.host ?: req.headers().get(HttpHeaderNames.HOST)?.substringBefore(":")
        forwardIntercepted(ctx, req, isHttps = false, host = host ?: "")
    }

    // ---- shared: build transaction, pause for intercept, forward, relay response ----

    private fun forwardIntercepted(
        ctx: ChannelHandlerContext, req: FullHttpRequest, isHttps: Boolean, host: String
    ) {
        val headers = LinkedHashMap<String, String>()
        req.headers().forEach { headers[it.key] = it.value }
        val bodyBytes = ByteArray(req.content().readableBytes()).also { req.content().readBytes(it) }

        val scheme = if (isHttps) "https" else "http"
        val fullUrl = if (req.uri().startsWith("http")) req.uri() else "$scheme://$host${req.uri()}"

        val editable = EditableRequest(
            method = req.method().name(),
            url = fullUrl,
            headers = headers,
            body = bodyBytes
        )
        val tx = HttpTransaction(request = editable, isHttps = isHttps)

        // Blocks this worker (on interceptExecutor, not the I/O loop) until user forwards/drops
        InterceptStore.submit(tx)

        if (tx.dropped) {
            val resp = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN)
            ctx.writeAndFlush(resp)
            return
        }

        relayToOrigin(ctx, tx)
    }

    /** Sends the (possibly edited) request to the real origin and streams the response back. */
    private fun relayToOrigin(ctx: ChannelHandlerContext, tx: HttpTransaction) {
        val uri = try { URI(tx.request.url) } catch (e: Exception) {
            ctx.writeAndFlush(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST))
            return
        }
        val targetHost = uri.host
        if (targetHost == null) {
            ctx.writeAndFlush(DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST))
            return
        }
        val targetPort = if (uri.port != -1) uri.port else if (tx.isHttps) 443 else 80

        val bootstrap = Bootstrap()
            .group(clientGroup) // reuse the shared pool instead of spawning a new one
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    if (tx.isHttps) {
                        // Lab use: trust-all client SSL context. Swap for a pinned trust store
                        // if you want to validate real origin certs.
                        val sslCtx = SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE).build()
                        ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), targetHost, targetPort))
                    }
                    ch.pipeline().addLast(HttpClientCodec())
                    // Automatically un-gzips/deflates AND un-chunks the response body,
                    // and fixes up Content-Encoding/Transfer-Encoding headers accordingly,
                    // so what we display/forward is always plain readable bytes.
                    ch.pipeline().addLast(HttpContentDecompressor())
                    ch.pipeline().addLast(HttpObjectAggregator(50 * 1024 * 1024))
                    ch.pipeline().addLast(object : SimpleChannelInboundHandler<FullHttpResponse>() {
                        override fun channelRead0(originCtx: ChannelHandlerContext, resp: FullHttpResponse) {
                            val respHeaders = LinkedHashMap<String, String>()
                            resp.headers().forEach { respHeaders[it.key] = it.value }
                            val respBody = ByteArray(resp.content().readableBytes())
                                .also { resp.content().readBytes(it) }

                            tx.response = HttpResponseSnapshot(resp.status().code(), respHeaders, respBody)
                            InterceptStore.notifyComplete(tx)

                            val outbound = DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1, resp.status(), Unpooled.wrappedBuffer(respBody)
                            )
                            outbound.headers().add(resp.headers())
                            outbound.headers().set(HttpHeaderNames.CONTENT_LENGTH, respBody.size)
                            ctx.writeAndFlush(outbound)
                            originCtx.close()
                        }
                        override fun exceptionCaught(originCtx: ChannelHandlerContext, cause: Throwable) {
                            val err = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY)
                            ctx.writeAndFlush(err)
                            originCtx.close()
                        }
                    })
                }
            })

        val outReq = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.valueOf(tx.request.method),
            uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: ""),
            Unpooled.wrappedBuffer(tx.request.body)
        )
        tx.request.headers.forEach { (k, v) -> outReq.headers().set(k, v) }
        outReq.headers().set(HttpHeaderNames.HOST, targetHost)
        outReq.headers().set(HttpHeaderNames.CONTENT_LENGTH, tx.request.body.size)
        // We already decompress inbound and send plain bytes onward, so don't ask
        // the origin for a transfer-encoding we're not prepared to stream.
        outReq.headers().remove(HttpHeaderNames.TRANSFER_ENCODING)

        bootstrap.connect(targetHost, targetPort).addListener(ChannelFutureListener { future ->
            if (future.isSuccess) {
                future.channel().writeAndFlush(outReq)
            } else {
                val err = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY)
                ctx.writeAndFlush(err)
            }
        })
    }
}
