package ir.fileo.di

import android.content.Context
import android.os.Build
import ir.fileo.core.discovery.DiscoveryEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/**
 * نگهدارنده وابستگی‌های سطح اپ (DI دستی و سبک).
 */
class AppContainer(appContext: Context) {

    val appContext: Context = appContext.applicationContext

    /** اسکوپ سطح اپ — فقط برای کارهای فعال (discovery/transfer)؛ هیچ کاری دائمی در آن زندگی نمی‌کند */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** شناسه پایدار این دستگاه — در اعلان‌های discovery برای تشخیص خودی/غیرخودی */
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }

    /** نام نمایشی این دستگاه برای سایر کاربران فایلو (قابل ویرایش از تنظیمات، بعداً) */
    val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    val model: String
        get() = Build.MODEL.orEmpty()

    val discoveryEngine: DiscoveryEngine by lazy {
        DiscoveryEngine(
            context = appContext,
            scope = applicationScope,
            selfDeviceId = deviceId,
            selfDeviceName = deviceName,
            selfModel = model,
            tcpPort = TRANSFER_PORT,
        )
    }

    companion object {
        /** پورت TCP سرویس انتقال فایل (فاز بعدی) */
        const val TRANSFER_PORT = 47_778
        private const val PREFS_NAME = "fileo"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
