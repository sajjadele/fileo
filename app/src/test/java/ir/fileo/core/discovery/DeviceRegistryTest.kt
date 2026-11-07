package ir.fileo.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegistryTest {

    private var time = 1_000L

    private fun registry() = DeviceRegistry { time }

    private fun device(
        id: String = "dev-1",
        name: String = "Phone A",
        ip: String = "192.168.1.5",
        source: DiscoveredDevice.Source = DiscoveredDevice.Source.UDP,
        lastSeen: Long = time,
    ) = DiscoveredDevice(
        deviceId = id,
        name = name,
        model = "Model X",
        ip = ip,
        port = 47778,
        source = source,
        lastSeenMs = lastSeen,
    )

    @Test
    fun `upsert adds and snapshot returns sorted list`() {
        val registry = registry()
        registry.upsert(device(id = "b", name = "B"))
        registry.upsert(device(id = "a", name = "A"))
        assertEquals(listOf("A", "B"), registry.snapshot().map { it.name })
    }

    @Test
    fun `same deviceId from mdns and udp merges into one entry`() {
        val registry = registry()
        registry.upsert(device(source = DiscoveredDevice.Source.UDP, lastSeen = 1_000))
        registry.upsert(
            device(
                source = DiscoveredDevice.Source.MDNS,
                name = "Phone A (2)",
                ip = "", // resolve موقت mDNS بدون ip
                lastSeen = 2_000,
            ),
            alias = "Phone A (2)",
        )
        val snapshot = registry.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("192.168.1.5", snapshot.first().ip) // ip خالی، ip معتبر قبلی را خراب نمی‌کند
        assertEquals(2_000, snapshot.first().lastSeenMs) // اما freshness تازه می‌شود
    }

    @Test
    fun `markLost removes device by mdns service name alias`() {
        val registry = registry()
        registry.upsert(device(id = "dev-1"), alias = "_fileo service")
        registry.markLost("_fileo service")
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `markLost does not remove unrelated devices`() {
        val registry = registry()
        registry.upsert(device(id = "dev-1", name = "A"), alias = "alias-1")
        registry.upsert(device(id = "dev-2", name = "B"), alias = "alias-2")
        registry.markLost("alias-1")
        assertEquals(listOf("B"), registry.snapshot().map { it.name })
    }

    @Test
    fun `prune removes only stale devices`() {
        time = 500
        val registry = registry()
        registry.upsert(device(id = "old", lastSeen = 500))
        time = 6_000
        registry.upsert(device(id = "fresh", lastSeen = 6_000))
        time = 8_000

        val alive = registry.prune(maxAgeMs = 7_000)

        assertEquals(listOf("fresh"), alive.map { it.deviceId })
        assertFalse(alive.any { it.deviceId == "old" })
    }

    @Test
    fun `clear empties everything`() {
        val registry = registry()
        registry.upsert(device())
        registry.clear()
        assertTrue(registry.snapshot().isEmpty())
    }
}
