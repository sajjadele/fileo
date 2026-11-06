package ir.fileo.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateNetworksTest {

    @Test
    fun `ipv4 private ranges are accepted`() {
        assertTrue(PrivateNetworks.isPrivateIp("192.168.1.5"))
        assertTrue(PrivateNetworks.isPrivateIp("10.0.0.1"))
        assertTrue(PrivateNetworks.isPrivateIp("10.255.255.255"))
        assertTrue(PrivateNetworks.isPrivateIp("172.16.0.1"))
        assertTrue(PrivateNetworks.isPrivateIp("172.31.255.255"))
    }

    @Test
    fun `ipv4 public addresses are rejected`() {
        assertFalse(PrivateNetworks.isPrivateIp("8.8.8.8"))
        assertFalse(PrivateNetworks.isPrivateIp("172.32.0.1")) // بیرون از 12/16.
        assertFalse(PrivateNetworks.isPrivateIp("1.1.1.1"))
        assertFalse(PrivateNetworks.isPrivateIp("9.9.9.9"))
    }

    @Test
    fun `ipv4 loopback and link-local are local`() {
        assertTrue(PrivateNetworks.isPrivateIp("127.0.0.1"))
        assertTrue(PrivateNetworks.isPrivateIp("169.254.10.20"))
    }

    @Test
    fun `invalid ipv4 falls back to ipv6 check and fails`() {
        assertFalse(PrivateNetworks.isPrivateIp("999.1.1.1"))
        assertFalse(PrivateNetworks.isPrivateIp("192.168.1"))
        assertFalse(PrivateNetworks.isPrivateIp("hello"))
    }

    @Test
    fun `ipv6 local addresses are accepted`() {
        assertTrue(PrivateNetworks.isPrivateIp("::1"))
        assertTrue(PrivateNetworks.isPrivateIp("fe80::1234:5678"))
        assertTrue(PrivateNetworks.isPrivateIp("fd12:3456::abcd"))
        assertTrue(PrivateNetworks.isPrivateIp("fc00::1"))
    }

    @Test
    fun `ipv6 global addresses are rejected`() {
        assertFalse(PrivateNetworks.isPrivateIp("2001:db8::1"))
        assertFalse(PrivateNetworks.isPrivateIp("2a00::1"))
    }
}
