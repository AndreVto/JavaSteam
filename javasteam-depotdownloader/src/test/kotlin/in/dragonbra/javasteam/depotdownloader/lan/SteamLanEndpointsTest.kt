package `in`.dragonbra.javasteam.depotdownloader.lan

import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamLanEndpointsTest {
    @Test
    fun `IPv6 link prefixes are compared without resolving a host`() {
        val first = InetAddress.getByName("fe80::1234").address
        val second = InetAddress.getByName("fe80::abcd").address
        val other = InetAddress.getByName("fe81::1234").address
        assertTrue(SteamLanEndpoints.sameNetwork(first, second, 64))
        assertFalse(SteamLanEndpoints.sameNetwork(first, other, 64))
        assertFalse(SteamLanEndpoints.sameNetwork(first, second, 128))
    }

    @Test
    fun `IPv4 discovery is matched to its local interface`() {
        val source = InetAddress.getByName("192.168.0.4").address
        val local = InetAddress.getByName("192.168.0.112").address
        val remote = InetAddress.getByName("192.168.1.112").address
        assertTrue(SteamLanEndpoints.sameNetwork(source, local, 24))
        assertFalse(SteamLanEndpoints.sameNetwork(source, remote, 24))
        assertFalse(SteamLanEndpoints.sameNetwork(source, local, 0))
    }
}
