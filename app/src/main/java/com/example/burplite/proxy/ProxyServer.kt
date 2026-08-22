package com.example.burplite.proxy

import android.util.Log
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.traffic.ChannelTrafficShapingHandler
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.*

private const val TAG = "ProxyServer"

class ProxyServer(
    private val port: Int,
    private val caStorageDir: File
) {
    private val bossGroup = NioEventLoopGroup()
    private val workerGroup = NioEventLoopGroup()
    private var serverChannel: Channel? = null
    private var caKeyPair: KeyPair? = null
    private var caCertificate: X509Certificate? = null

    var rootCaPemPath: String? = null
        private set

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun start() {
        try {
            Log.d(TAG, "Starting proxy server on port $port")
            
            // Initialize or load CA certificate
            initializeCertificateAuthority()
            
            // Create server bootstrap
            val bootstrap = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val pipeline = ch.pipeline()
                        
                        // Add HTTP codec
                        pipeline.addLast("httpServerCodec", HttpServerCodec())
                        pipeline.addLast("httpObjectAggregator", HttpObjectAggregator(65536))
                        
                        // Add traffic shaping for monitoring
                        pipeline.addLast("trafficShaping", ChannelTrafficShapingHandler(0, 0))
                        
                        // Add proxy handler
                        pipeline.addLast("proxyHandler", HttpProxyHandler(caCertificate, caKeyPair))
                    }
                })
            
            // Bind to port
            val channelFuture = bootstrap.bind("127.0.0.1", port)
            serverChannel = channelFuture.sync().channel()
            Log.d(TAG, "Proxy server started successfully on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server", e)
            throw e
        }
    }

    fun stop() {
        try {
            Log.d(TAG, "Stopping proxy server")
            serverChannel?.close()?.sync()
            workerGroup.shutdownGracefully()
            bossGroup.shutdownGracefully()
            Log.d(TAG, "Proxy server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy server", e)
        }
    }

    private fun initializeCertificateAuthority() {
        try {
            val caPemFile = File(caStorageDir, "ca.pem")
            val caKeyFile = File(caStorageDir, "ca.key")
            
            if (caPemFile.exists() && caKeyFile.exists()) {
                Log.d(TAG, "Loading existing CA certificate")
                // In a production app, you would load the existing certificates
                // For now, we'll regenerate them
                generateCertificateAuthority(caPemFile)
            } else {
                Log.d(TAG, "Generating new CA certificate")
                generateCertificateAuthority(caPemFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing certificate authority", e)
            throw e
        }
    }

    private fun generateCertificateAuthority(caPemFile: File) {
        try {
            // Create CA directory if it doesn't exist
            caStorageDir.mkdirs()
            
            // Generate key pair
            val keyPairGen = KeyPairGenerator.getInstance("RSA")
            keyPairGen.initialize(2048)
            caKeyPair = keyPairGen.generateKeyPair()
            
            // Generate self-signed CA certificate
            val issuerName = X500Name("CN=BurpLite CA, O=BurpLite, L=Proxy, C=US")
            val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000) // 1 year
            
            val builder = JcaX509v3CertificateBuilder(
                issuerName,
                serialNumber,
                notBefore,
                notAfter,
                issuerName,
                caKeyPair!!.public
            )
            
            // Add basic constraints
            val extUtils = JcaX509ExtensionUtils()
            builder.addExtension(
                Extension.basicConstraints,
                true,
                BasicConstraints(true)
            )
            builder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extUtils.createSubjectKeyIdentifier(caKeyPair!!.public)
            )
            builder.addExtension(
                Extension.authorityKeyIdentifier,
                false,
                extUtils.createAuthorityKeyIdentifier(caKeyPair!!.public)
            )
            
            // Sign certificate
            val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .setProvider(BouncyCastleProvider())
                .build(caKeyPair!!.private)
            
            val certHolder: X509CertificateHolder = builder.build(signer)
            val converter = JcaX509CertificateConverter()
            caCertificate = converter.getCertificate(certHolder)
            
            // Write CA certificate to PEM file
            writeCertificateToPem(caCertificate!!, caPemFile)
            rootCaPemPath = caPemFile.absolutePath
            
            Log.d(TAG, "CA certificate generated and saved to ${caPemFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error generating certificate authority", e)
            throw e
        }
    }

    private fun writeCertificateToPem(cert: X509Certificate, file: File) {
        try {
            FileWriter(file).use { writer ->
                PEMWriter(writer).use { pemWriter ->
                    pemWriter.writeObject(cert)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing certificate to PEM file", e)
            throw e
        }
    }
}
