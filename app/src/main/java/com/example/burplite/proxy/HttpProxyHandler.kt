package com.example.burplite.proxy

import android.util.Log
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpVersion
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
                "BurpLite Proxy Server\r\n" +
                "Request: ${request.method()} ${request.uri()}\r\n" +
                "Headers:\r\n${request.headers()}",
                CharsetUtil.UTF_8
            )
            
            val response = DefaultFullHttpResponse(
                request.protocolVersion(),
                HttpResponseStatus.OK,
                content
            )
            
            response.headers().set("Content-Type", "text/plain; charset=UTF-8")
            response.headers().set("Content-Length", content.readableBytes())
            response.headers().set("Connection", "close")
            
            ctx.writeAndFlush(response).addListener { future ->
                if (!future.isSuccess) {
                    Log.e(TAG, "Error writing response", future.cause())
                }
                ctx.close()
            }
            
            Log.d(TAG, "Response sent for ${request.method()} ${request.uri()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR)
        }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        Log.d(TAG, "Channel active: ${ctx.channel().remoteAddress()}")
        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        Log.d(TAG, "Channel inactive: ${ctx.channel().remoteAddress()}")
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        Log.e(TAG, "Exception in proxy handler: ${cause.message}", cause)
        sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR)
        ctx.close()
    }

    private fun sendErrorResponse(ctx: ChannelHandlerContext, status: HttpResponseStatus) {
        try {
            val content = Unpooled.copiedBuffer(
                "Error: ${status.code()} ${status.reasonPhrase()}",
                CharsetUtil.UTF_8
            )
            
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                content
            )
            
            response.headers().set("Content-Type", "text/plain; charset=UTF-8")
            response.headers().set("Content-Length", content.readableBytes())
            response.headers().set("Connection", "close")
            
            ctx.writeAndFlush(response).addListener { future ->
                if (!future.isSuccess) {
                    Log.e(TAG, "Error writing error response", future.cause())
                }
                ctx.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendErrorResponse", e)
            ctx.close()
        }
    }
}
