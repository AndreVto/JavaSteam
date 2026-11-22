package `in`.dragonbra.javasteam.depotdownloader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CdnRegionPreferenceTest {
    private data class Candidate(val name: String, val cell: Int)

    @Test
    fun `matching cell is preferred among Steam ranked candidates`() {
        val servers = listOf(
            Candidate("remote1", 200), Candidate("remote2", 200),
            Candidate("local1", 100), Candidate("remote3", 300), Candidate("local2", 100),
        )
        val ranked = CdnRegionPreference.prioritize(servers, 100, 2) { it.cell }
        assertEquals(listOf("local1", "local2", "remote1", "remote2", "remote3"), ranked.map { it.name })
    }

    @Test
    fun `unknown client cell preserves Steam ordering`() {
        val servers = listOf(Candidate("a", 1), Candidate("b", 2))
        assertEquals(servers, CdnRegionPreference.prioritize(servers, 0, 2) { it.cell })
        assertEquals(servers, CdnRegionPreference.prioritize(servers, 3, 2) { it.cell })
    }

    @Test
    fun `distantly ranked cell match does not override Steam ranking`() {
        val servers = (1..12).map { Candidate("host$it", if (it == 12) 100 else 200) }
        val ranked = CdnRegionPreference.prioritize(servers, 100, 4) { it.cell }
        assertEquals(servers, ranked)
    }

    @Test
    fun `only a bounded window is reordered and reserve hosts remain available`() {
        val servers = (1..12).map { Candidate("host$it", if (it == 7 || it == 12) 100 else 200) }
        val ranked = CdnRegionPreference.prioritize(servers, 100, 4) { it.cell }
        assertEquals("host7", ranked.first().name)
        assertEquals(servers.drop(8), ranked.drop(8))
        assertEquals(servers.toSet(), ranked.toSet())
    }
}
