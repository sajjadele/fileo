package ir.fileo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ir.fileo.FileoApp
import ir.fileo.R
import ir.fileo.core.transfer.TransferState
import ir.fileo.core.transfer.isTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * سرویس انتقال — فقط وقتی سشن فعالی وجود دارد زندگی می‌کند؛ با خالی شدن، خودش را می‌بندد.
 * (پروندهٔ «بک‌گراند صفر»: در حالت idle هیچ سرویسی بالا نیست)
 */
class TransferService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val engine by lazy { (application as FileoApp).container.transferEngine }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(null))
        acquireLocks()
        observeJob = serviceScope.launch { observeSessions() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeSessions() {
        engine.sessions.flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { it.state }) { it.toList() }
        }.collect { states ->
            val active = states.filter { !it.isTerminal }
            if (active.isEmpty()) {
                stopSelf()
            } else {
                val transferring = active.filterIsInstance<TransferState.Transferring>().firstOrNull()
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(transferring))
            }
        }
    }

    private fun buildNotification(transferring: TransferState.Transferring?): Notification {
        val text = if (transferring != null) {
            getString(R.string.notif_transfer_progress, transferring.fileIndex, transferring.fileCount) +
                " · " + transferring.currentFileName
        } else {
            getString(R.string.notif_transfer_active)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fileo)
            .setContentTitle(getString(R.string.notif_transfer_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (transferring != null && transferring.totalBytes > 0) {
            val progress = (transferring.bytesDone * 100 / transferring.totalBytes).toInt().coerceIn(0, 100)
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_channel_desc) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        // جلوگیری از خواب رفتن Wi-Fi حین انتقال (قطع‌نشدن انتقال با خاموش شدن صفحه)
        wifiLock = getSystemService(WifiManager::class.java)?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "fileo:wifi",
        )?.apply {
            setReferenceCounted(false)
            acquire()
        }
        wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "fileo:transfer",
        )?.apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_MS)
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        serviceScope.cancel()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_WAKE_MS = 2 * 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}
