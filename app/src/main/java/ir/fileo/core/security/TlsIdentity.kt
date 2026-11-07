package ir.fileo.core.security

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * هویت TLS دستگاه — کاملاً خالص JVM (تست‌پذیر).
 * هر دستگاه یک گواهی self-signed دارد؛ اتصال همیشه رمزنگاری‌شده است.
 * اعتبارسنجی هویت طرف مقابل (TOFU + QR) در فاز QR اضافه می‌شود — نسخهٔ ۱ فقط محرمانگی کانال.
 */
class TlsIdentity(private val keyStore: KeyStore) {

    private val lock = Any()

    @Volatile
    private var cached: SSLContext? = null

    fun sslContext(): SSLContext {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, KEY_PASSWORD.toCharArray())
            }

            // TOFU نسخهٔ ۱: هر گواهی پذیرفته می‌شود — رمزنگاری فعال، احراز هویت بعداً
            val trusting = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            val context = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, arrayOf<TrustManager>(trusting), SecureRandom())
            }
            cached = context
            return context
        }
    }

    fun socketFactory(): SSLSocketFactory = sslContext().socketFactory

    fun serverSocketFactory() = sslContext().serverSocketFactory

    fun toPkcs12Bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        keyStore.store(out, KEY_PASSWORD.toCharArray())
        return out.toByteArray()
    }

    /** SHA-256 از گواهی — برای تأیید fingerprint در فاز QR */
    fun certificateFingerprint(): String? {
        val cert = keyStore.getCertificate(ALIAS) as? X509Certificate ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { b -> "%02x".format(b) }
    }

    companion object {
        const val ALIAS = "fileo"
        const val KEY_PASSWORD = "fileo-local"

        /** خواندن از فرم ذخیره‌شده (PKCS12 بایت‌ها) */
        fun fromPkcs12(bytes: ByteArray): TlsIdentity =
            TlsIdentity(
                KeyStore.getInstance("PKCS12").apply {
                    load(ByteArrayInputStream(bytes), KEY_PASSWORD.toCharArray())
                },
            )
    }
}

/** تولید هویت جدید — خالص JVM، بدون اندروید */
object TlsIdentityFactory {

    fun generate(): TlsIdentity {
        val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()

        val now = System.currentTimeMillis()
        val certificate = JcaX509v3CertificateBuilder(
            X500Name(SUBJECT),
            BigInteger(64, SecureRandom()),
            Date(now - DAY_MS),
            Date(now + 10L * 365 * DAY_MS),
            X500Name(SUBJECT),
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
            .let(JcaX509CertificateConverter()::getCertificate)

        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry(TlsIdentity.ALIAS, keyPair.private, TlsIdentity.KEY_PASSWORD.toCharArray(), arrayOf(certificate))
        return TlsIdentity(keyStore)
    }

    private const val SUBJECT = "CN=Fileo Device"
    private const val DAY_MS = 86_400_000L
}
