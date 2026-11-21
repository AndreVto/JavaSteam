package `in`.dragonbra.javasteam.depotdownloader.lan

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamLanIpv6DiscoveryTest {
    @Test
    fun `only the authorized Steam client is accepted`() {
        val peer = SteamLanWire.Status(
            clientId = 123L, hostname = "gaming-pc", addresses = emptyList(), steamIds = listOf(456L),
        )
        assertTrue(SteamLanIpv6Discovery.matchesPeer(peer, 123L, 456L))
        assertFalse(SteamLanIpv6Discovery.matchesPeer(peer, 999L, 456L))
        assertFalse(SteamLanIpv6Discovery.matchesPeer(peer, 123L, 999L))
    }
}
