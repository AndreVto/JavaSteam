package `in`.dragonbra.javasteam.depotdownloader.lan

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SteamLanPipelineTest {
    @Test
    fun `multiple requests share a connection and match out of order responses`() {
        ServerSocket(0).use { listener ->
            val workers = Executors.newFixedThreadPool(16)
            val ready = CountDownLatch(1)
            val server = Thread {
                listener.accept().use { peer ->
                    val input = DataInputStream(peer.getInputStream())
                    val requests = (0 until 16).map { readRequest(input) }
                    ready.countDown()
                    for (sha in requests.reversed()) {
                        peer.getOutputStream().write(response(2, sha, sha.take(3).toByteArray()))
                    }
                }
            }.apply { isDaemon = true; start() }
            val client = Socket("127.0.0.1", listener.localPort)
            SteamLanPipeline(client).use { pipeline ->
                val jobs = (1..16).map { index ->
                    workers.submit<SteamLanWire.Answer> {
                        pipeline.exchange(1, 2, ByteArray(20) { index.toByte() }, 17)
                    }
                }
                assertTrue(ready.await(3, TimeUnit.SECONDS))
                jobs.forEachIndexed { index, job ->
                    val sha = ByteArray(20) { (index + 1).toByte() }
                    val result = job.get(3, TimeUnit.SECONDS)
                    assertContentEquals(sha, result.sha)
                    assertContentEquals(sha.take(3).toByteArray(), result.data)
                    assertEquals(1, result.result)
                }
            }
            workers.shutdownNow()
            server.join(2000)
        }
    }

    @Test
    fun `closing an active connection releases waiting requests`() {
        ServerSocket(0).use { listener ->
            val received = CountDownLatch(1)
            val server = Thread {
                listener.accept().use { peer ->
                    readRequest(DataInputStream(peer.getInputStream()))
                    received.countDown()
                    peer.getInputStream().read()
                }
            }.apply { isDaemon = true; start() }
            val workers = Executors.newSingleThreadExecutor()
            val pipeline = SteamLanPipeline(Socket("127.0.0.1", listener.localPort))
            val job = workers.submit<Boolean> {
                try {
                    pipeline.exchange(1, 2, ByteArray(20) { 5 }, 17)
                    false
                } catch (_: java.io.IOException) {
                    true
                }
            }
            assertTrue(received.await(3, TimeUnit.SECONDS))
            pipeline.close()
            assertTrue(job.get(3, TimeUnit.SECONDS))
            workers.shutdownNow()
            server.join(2000)
        }
    }

    @Test
    fun `response from another depot terminates the connection`() {
        ServerSocket(0).use { listener ->
            val server = Thread {
                listener.accept().use { peer ->
                    val sha = readRequest(DataInputStream(peer.getInputStream()))
                    peer.getOutputStream().write(response(3, sha, byteArrayOf(1)))
                }
            }.apply { isDaemon = true; start() }
            SteamLanPipeline(Socket("127.0.0.1", listener.localPort)).use { pipeline ->
                assertFailsWith<java.io.IOException> {
                    pipeline.exchange(1, 2, ByteArray(20) { 4 }, 17)
                }
            }
            server.join(2000)
        }
    }

    private fun readRequest(input: DataInputStream): ByteArray {
        val outer = ByteArray(8)
        input.readFully(outer)
        val frame = ByteArray(SteamLanWire.read32(outer, 0))
        input.readFully(frame)
        assertEquals(SteamLanWire.chunkRequest, SteamLanWire.read32(frame, 0) and Int.MAX_VALUE)
        val offset = frame.indices.first { it + 22 <= frame.size && frame[it] == 26.toByte() && frame[it + 1] == 20.toByte() }
        return frame.copyOfRange(offset + 2, offset + 22)
    }

    private fun response(depot: Int, sha: ByteArray, data: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(8, 1, 16, 1, 24, depot.toByte(), 34, 20))
            write(sha)
            write(byteArrayOf(40, 0, 48, 0, 58, data.size.toByte()))
            write(data)
        }.toByteArray()
        val frame = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SteamLanWire.chunkResponse or Int.MIN_VALUE).putInt(0).put(body).array()
        return ByteBuffer.allocate(8 + frame.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(frame.size).put("VT01".toByteArray()).put(frame).array()
    }
}
