package com.example.burplite.proxy

import android.util.Log
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.buffer.Unpooled
import io.netty.util.CharsetUtil
import java.security.KeyPair
import java.security.cert.X509Certificate

private const val TAG = "HttpProxyHandler"

class HttpProxyHandler(
    private val caCertificate: X509Certificate?,
    private val caKeyPair: KeyPair?
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        try {
            Log.d(TAG, "Received request: ${request.method()} ${request.uri()}")
            
            // Create a simple response
            val content = Unpooled.copiedBuffer(
                "BurpLite Proxy Server\r\nRequest: ${request.method()} ${request.uri()}",
                CharsetUtil.UTF_8
            )
            
            val response = DefaultFullHttpResponse(
                request.protocolVersion(),
                HttpResponseStatus.OK,
                content
            )
            
            response.headers().set("Content-Type", "text/plain; charset=UTF-8")
            response.headers().set("Content-Length", content.readableBytes())
            
            ctx.writeAndFlush(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        Log.e(TAG, "Exception in proxy handler", cause)
        sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR)
        ctx.close()
    }

    private fun sendErrorResponse(ctx: ChannelHandlerContext, status: HttpResponseStatus) {
        val content = Unpooled.copiedBuffer(
            "Error: ${status.code()} ${status.reasonPhrase()}",
            CharsetUtil.UTF_8
        )
        
        val response = DefaultFullHttpResponse(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
            status,
            content
        )
        
        response.headers().set("Content-Type", "text/plain; charset=UTF-8")
        response.headers().set("Content-Length", content.readableBytes())
        
        ctx.writeAndFlush(response)
    }
}
