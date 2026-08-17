package icu.gxb.hypertv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIpTest {

    @Test
    fun `selectPreferredIpv4 prefers 192_168 over other site-local`() {
        assertEquals(
            "192.168.1.5",
            selectPreferredIpv4(listOf("172.16.0.2", "192.168.1.5", "10.0.0.2")),
        )
    }

    @Test
    fun `selectPreferredIpv4 prefers 10_ when no 192_168`() {
        assertEquals("10.0.0.2", selectPreferredIpv4(listOf("172.16.0.2", "10.0.0.2")))
    }

    @Test
    fun `selectPreferredIpv4 falls back to first address when no site-local`() {
        assertEquals("203.0.113.5", selectPreferredIpv4(listOf("203.0.113.5", "8.8.8.8")))
    }

    @Test
    fun `selectPreferredIpv4 returns null for empty input`() {
        assertNull(selectPreferredIpv4(emptyList()))
    }

    @Test
    fun `isSiteLocal matches RFC1918 ranges`() {
        assertTrue(isSiteLocal("192.168.0.1"))
        assertTrue(isSiteLocal("10.1.2.3"))
        assertTrue(isSiteLocal("172.16.0.1"))
        assertTrue(isSiteLocal("172.31.255.255"))
        assertFalse(isSiteLocal("172.15.0.1"))
        assertFalse(isSiteLocal("172.32.0.1"))
        assertFalse(isSiteLocal("8.8.8.8"))
        assertFalse(isSiteLocal("127.0.0.1"))
    }

    @Test
    fun `getLocalIpv4 returns null or a non-loopback ipv4 address`() {
        val ip = getLocalIpv4()
        if (ip != null) {
            assertTrue("unexpected ip: $ip", IPV4_REGEX.matches(ip))
            assertNotEquals("127.0.0.1", ip)
        }
    }

    private companion object {
        val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    }
}
