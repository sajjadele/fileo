package ir.fileo.core.discovery

import android.util.Log
import ir.fileo.core.net.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * مکانیزم جایگزین کشف با UDP Broadcast — وقتی روتر mDNS را بلاک کرده باشد.
 * هر دستگاه هر ۲ ثانیه یک بستهٔ Announce پخش می‌کند و هم‌زمان گوش می‌دهد؛
 * پس هر دو طرف بدون نیاز به پاسخ، همدیگر را می‌بینند.
 */
class UdpDiscovery(
    private val scope: CoroutineScope,
    private val selfDeviceId: String,
    private val deviceName: String,
    private val model: String,
    private val tcpPort: Int,
    private val onDeviceSeen: (DiscoveredDevice) -> Unit,
) {

    private var listenSocket: DatagramSocket? = null
    private var sendSocket: DatagramSocket? = null
    private var announcerJob: Job? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        startListener()
        startAnnouncer()
    }

    fun stop() {
        if (!running) return
        running = false
        announcerJob?.cancel()
        announcerJob = null
        runCatching { listenSocket?.close() }
        runCatching { sendSocket?.close() }
        listenSocket = null
        sendSocket = null
    }

    private fun startListener() {
        scope.launch(Dispatchers.IO) {
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
            }.getOrElse {
                Log.w(TAG, "cannot bind discovery port $DISCOVERY_PORT: $it")
                return@launch
            }
            listenSocket = socket

            val buffer = ByteArray(MAX_PACKET_SIZE)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    handlePacket(packet)
                } catch (e: Exception) {
                    if (!running) break // سوکت هنگام stop بسته شد — خروج طبیعی
                    Log.d(TAG, "receive error (continuing): ${e.message}")
                }
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val senderIp = packet.address?.hostAddress ?: return
        val parsed = DiscoveryCodec.parse(String(packet.data, packet.offset, packet.length)) ?: return
        if (parsed.deviceId == selfDeviceId) return
        if (senderIp == NetworkUtils.findLocalIpv4()) return // خودمان

        onDeviceSeen(
            DiscoveredDevice(
                deviceId = parsed.deviceId,
                name = parsed.deviceName,
                model = parsed.model,
                ip = senderIp,
                port = parsed.tcpPort,
                source = DiscoveredDevice.Source.UDP,
                lastSeenMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun startAnnouncer() {
        announcerJob = scope.launch(Dispatchers.IO) {
            val socket = runCatching {
                DatagramSocket().apply { broadcast = true }
            }.getOrElse {
                Log.w(TAG, "cannot create announce socket: $it")
                return@launch
            }
            sendSocket = socket

            val payload = DiscoveryCodec.encode(
                AnnouncePacket(
                    deviceId = selfDeviceId,
                    deviceName = deviceName,
                    model = model,
                    tcpPort = tcpPort,
                ),
            ).toByteArray(Charsets.UTF_8)

            val target = runCatching { InetAddress.getByName(BROADCAST_ADDRESS) }.getOrNull()
                ?: return@launch

            while (kotlin.coroutines.coroutineContext.isActive && running) {
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
                }
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val TAG = "UdpDiscovery"
        const val DISCOVERY_PORT = 47_777
        const val BROADCAST_ADDRESS = "255.255.255.255"
        const val ANNOUNCE_INTERVAL_MS = 2_000L
        const val MAX_PACKET_SIZE = 2048
    }
}
