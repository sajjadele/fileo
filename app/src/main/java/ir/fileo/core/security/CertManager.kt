package ir.fileo.core.security

import android.content.Context
import android.util.Log
import java.io.File

/**
 * نگهداری پایدار هویت TLS در حافظهٔ خصوصی اپ — یک گواهی برای عمر نصب.
 * (تعویض هویت = پاک کردن دادهٔ اپ)
 */
class CertManager(private val context: Context) {

    @Volatile
    private var identity: TlsIdentity? = null

    fun identity(): TlsIdentity {
        identity?.let { return it }
        synchronized(this) {
            identity?.let { return it }
            val file = File(context.filesDir, KEYSTORE_FILE)
            val loaded = if (file.exists()) {
                runCatching { TlsIdentity.fromPkcs12(file.readBytes()) }
                    .onFailure { Log.w(TAG, "identity unreadable — regenerating", it) }
                    .getOrNull()
            } else {
                null
            }
            val fresh = loaded ?: TlsIdentityFactory.generate().also {
                runCatching { file.writeBytes(it.toPkcs12Bytes()) }
                    .onFailure { Log.e(TAG, "cannot persist identity", it) }
            }
            identity = fresh
            return fresh
        }
    }

    companion object {
        private const val TAG = "CertManager"
        private const val KEYSTORE_FILE = "fileo-identity.p12"
    }
}
