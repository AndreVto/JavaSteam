package `in`.dragonbra.javasteam.depotdownloader.lan

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SteamLanWireTest {
    @Test
    fun `discovery and status match Steam packet framing`() {
        val id = -1234567890123456L
        val steamId = 76561198123456789L
        val discovery = SteamLanWire.discoveryPacket(id, 7)
        assertEquals(0, SteamLanWire.packetType(discovery))
        val status = SteamLanWire.statusPacket(id, steamId, "Android", "192.168.1.20")
        assertEquals(1, SteamLanWire.packetType(status))
        val peer = assertNotNull(SteamLanWire.status(status))
        assertEquals(id, peer.clientId)
        assertEquals("Android", peer.hostname)
        assertEquals(listOf(steamId), peer.steamIds)
        assertEquals(listOf("192.168.1.20"), peer.addresses)
        assertNull(SteamLanWire.status(status.copyOf(status.size - 1)))
    }

    @Test
    fun `peer chunk request uses Steam TCP framing`() {
        val sha = ByteArray(20) { it.toByte() }
        val request = SteamLanWire.frameChunkRequest(250900, 250901, sha, -5L)
        assertEquals(request.size - 8, SteamLanWire.read32(request, 0))
        assertContentEquals("VT01".toByteArray(), request.copyOfRange(4, 8))
        assertEquals(9902, SteamLanWire.read32(request, 8) and Int.MAX_VALUE)
        assertEquals(0, SteamLanWire.read32(request, 12))
    }

    @Test
    fun `peer rejection result is preserved without chunk bytes`() {
        val payload = byteArrayOf(8, 5, 16, 1, 24, 2)
        val frame = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(9903 or Int.MIN_VALUE).putInt(0).put(payload).array()
        val response = SteamLanWire.parseChunkFrame(frame)
        assertEquals(5, response.result)
        assertEquals(1, response.appId)
        assertEquals(2, response.depotId)
        assertEquals(0, response.sha.size)
    }

    @Test
    fun `peer response is decoded by Valve message field numbers`() {
        val sha = ByteArray(20) { (it + 10).toByte() }
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(8, 1, 16, -108, -88, 15, 24, -107, -88, 15, 34, 20))
            write(sha)
            write(byteArrayOf(40, 0, 48, 0, 58, 3, 1, 2, 3))
        }.toByteArray()
        val frame = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(9903 or Int.MIN_VALUE).putInt(0).put(payload).array()
        val response = SteamLanWire.parseChunkFrame(frame)
        assertEquals(1, response.result)
        assertEquals(250900, response.appId)
        assertEquals(250901, response.depotId)
        assertContentEquals(sha, response.sha)
        assertEquals(false, response.encrypted)
        assertEquals(false, response.compressed)
        assertContentEquals(byteArrayOf(1, 2, 3), response.data)
        assertFailsWith<IllegalArgumentException> {
            SteamLanWire.parseChunkFrame(frame.copyOf(6))
        }
    }
}
