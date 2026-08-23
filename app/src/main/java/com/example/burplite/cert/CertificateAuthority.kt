package com.example.burplite.cert

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates a self-signed Root CA once (install this into Android's
 * "Trusted credentials > User" manually for lab devices), then mints
 * a leaf certificate on-the-fly for every host the proxy intercepts,
 * signed by that root CA.
 *
 * NOTE: this is for isolated lab traffic (DVWA/WebGoat/bWAPP etc).
 * Do not point this at anything you don't own/control.
 */
class CertificateAuthority(private val storageDir: File) {

    private val leafCache = ConcurrentHashMap<String, Pair<X509Certificate, PrivateKey>>()

    lateinit var rootCert: X509Certificate
        private set
    private lateinit var rootKey: KeyPair

    private val caCertFile = File(storageDir, "burplite_root_ca.pem")
    private val caKeyFile = File(storageDir, "burplite_root_ca.key")

    fun init() {
        if (caCertFile.exists() && caKeyFile.exists()) {
            loadRootCa()
        } else {
            generateRootCa()
            persistRootCa()
        }
    }

    /** Returns cert+key for [host], generating & caching if needed. */
    fun certFor(host: String): Pair<X509Certificate, PrivateKey> =
        leafCache.getOrPut(host) { generateLeafCert(host) }

    // Netty server SslContext per host, built once and cached: minting the leaf
    // keypair + signing is expensive (audit B5) — never do it inside an I/O loop.
    private val sslCtxCache = ConcurrentHashMap<String, SslContext>()

    /** Cached [SslContext] presenting our MITM leaf certificate for [host]. */
    fun serverSslContextFor(host: String): SslContext =
        sslCtxCache.getOrPut(host) {
            val (leafCert, leafKey) = certFor(host)
            SslContextBuilder.forServer(leafKey, leafCert, rootCert).build()
        }

    // ---- SandroProxy-style "drop your own certificate in the directory" ----

    @Volatile private var cachedCustomCtx: SslContext? = null
    private var customChecked = false
    private val customLock = Any()

    /**
     * If the user placed a PKCS#12 file named <code>proxy.p12</code> inside
     * [storageDir], it is loaded once and used for ALL MITM connections —
     * no per-host generation, no need to install a CA on the device (the
     * certificate just has to be one the client already trusts).
     *
     * @return the cached context, or null when no usable proxy.p12 exists.
     */
    fun customPkcs12Context(): SslContext? {
        if (customChecked) return cachedCustomCtx
        synchronized(customLock) {
            if (customChecked) return cachedCustomCtx
            val p12 = File(storageDir, "proxy.p12")
            cachedCustomCtx = if (!p12.exists()) null else try {
                val ks = KeyStore.getInstance("PKCS12")
                p12.inputStream().use { ks.load(it, null) }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(ks, null)
                SslContextBuilder.forServer(kmf).build()
            } catch (e: Exception) {
                null
            }
            customChecked = true
            return cachedCustomCtx
        }
    }

    // -------- root CA --------

    private fun generateRootCa() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        rootKey = kpg.generateKeyPair()

        val subject = X500Name("CN=BurpLite Root CA, O=BurpLite Lab, C=ID")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
        val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L)

        val builder = JcaX509v3CertificateBuilderCompat(
            subject, serial, notBefore, notAfter, subject, rootKey.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(rootKey.private)
        rootCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun persistRootCa() {
        storageDir.mkdirs()
        caCertFile.writeBytes(pemEncode("CERTIFICATE", rootCert.encoded))
        caKeyFile.writeBytes(pemEncode("PRIVATE KEY", rootKey.private.encoded))
    }

    private fun loadRootCa() {
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        rootCert = certFactory.generateCertificate(caCertFile.inputStream()) as X509Certificate

        val keyBytes = pemDecode(caKeyFile.readText())
        val kf = KeyFactory.getInstance("RSA")
        val privateKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
        // public key from cert
        rootKey = KeyPair(rootCert.publicKey, privateKey)
    }

    // -------- leaf cert per host --------

    private fun generateLeafCert(host: String): Pair<X509Certificate, PrivateKey> {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val leafKey = kpg.generateKeyPair()

        val subject = X500Name("CN=$host, O=BurpLite Lab, C=ID")
        val serial = BigInteger.valueOf(System.currentTimeMillis() + host.hashCode())
        val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
        val notAfter = Date(System.currentTimeMillis() + 825L * 86_400_000L) // ~825 days

        val builder = JcaX509v3CertificateBuilderCompat(
            X500Name(rootCert.subjectX500Principal.name), serial, notBefore, notAfter,
            subject, leafKey.public
        )
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(false))
        builder.addExtension(
            Extension.subjectAlternativeName, false,
            GeneralNames(GeneralName(GeneralName.dNSName, host))
        )
        val authorityKeyId = JcaX509ExtensionUtils().createAuthorityKeyIdentifier(rootCert)
        builder.addExtension(Extension.authorityKeyIdentifier, false, authorityKeyId)

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(rootKey.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return cert to leafKey.private
    }

    // -------- pem helpers --------

    private fun pemEncode(type: String, bytes: ByteArray): ByteArray {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes)
        return "-----BEGIN $type-----\n$b64\n-----END $type-----\n".toByteArray()
    }

    private fun pemDecode(pem: String): ByteArray {
        val clean = pem.replace(Regex("-----[A-Z ]+-----"), "").replace("\n", "")
        return Base64.getDecoder().decode(clean)
    }
}

/** Thin wrapper so builder args read top-to-bottom (issuer, serial, dates, subject, pubkey). */
private fun JcaX509v3CertificateBuilderCompat(
    issuer: X500Name, serial: BigInteger, notBefore: Date, notAfter: Date,
    subject: X500Name, publicKey: PublicKey
) = X509v3CertificateBuilder(
    issuer, serial, notBefore, notAfter, subject,
    org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(publicKey.encoded)
)
