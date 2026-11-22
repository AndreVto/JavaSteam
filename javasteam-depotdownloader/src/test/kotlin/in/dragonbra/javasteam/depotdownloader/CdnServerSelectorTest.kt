package `in`.dragonbra.javasteam.depotdownloader

import `in`.dragonbra.javasteam.steam.cdn.Server
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CdnServerSelectorTest {
    private fun hosts(count: Int) = (1..count).map { Server.fromHostAndPort("cdn$it.test", 443) }

    @Test
    fun `prioritizes a bounded set of hosts but probes reserves`() {
        val servers = hosts(12)
        val selector = CdnServerSelector()
        selector.update(servers)
        val selections = (0 until 64).map { selector.select() }
        assertTrue(selections.take(15).all { it in servers.take(8) })
        assertTrue(selections.any { it in servers.drop(8) })
    }

    @Test
    fun `repeated host failure moves selection to another host and cooldown expires`() {
        var now = 1_000L
        val servers = hosts(3)
        val selector = CdnServerSelector(preferredCount = 1, cooldownNanos = 100L, nowNanos = { now })
        selector.update(servers)
        assertEquals(servers[0], selector.select())
        selector.failed(servers[0])
        assertEquals(servers[0], selector.select())
        selector.failed(servers[0])
        assertEquals(servers[1], selector.select())
        now += 101L
        assertEquals(servers[0], selector.select())
    }

    @Test
    fun `successful transfer clears the failure streak`() {
        val servers = hosts(2)
        val selector = CdnServerSelector(preferredCount = 1)
        selector.update(servers)
        selector.failed(servers[0])
        selector.succeeded(servers[0])
        selector.failed(servers[0])
        assertEquals(servers[0], selector.select())
    }

    @Test
    fun `all cooled hosts still allow retry and refreshed lists reset health`() {
        val servers = hosts(2)
        val selector = CdnServerSelector(preferredCount = 1)
        selector.update(servers)
        repeat(2) { selector.failed(servers[0]); selector.failed(servers[1]) }
        assertTrue(selector.select() in servers)
        selector.update(servers)
        assertEquals(servers[0], selector.select())
    }
}
