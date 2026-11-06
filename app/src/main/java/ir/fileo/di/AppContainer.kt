package ir.fileo.di

import android.content.Context
import android.os.Build

/**
 * نگهدارنده وابستگی‌های سطح اپ (DI دستی و سبک).
 * موتورهای اصلی — discovery، transfer، storage، security — در مراحل بعدی اینجا ثبت می‌شوند.
 */
class AppContainer(appContext: Context) {

    val appContext: Context = appContext.applicationContext

    /** نام نمایشی این دستگاه برای سایر کاربران فایلو */
    val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
