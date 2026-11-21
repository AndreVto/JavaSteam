package `in`.dragonbra.javasteam.depotdownloader.lan

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class SteamLanPipeline(
    private val socket: Socket,
) : AutoCloseable {
    private data class ChunkKey(val depotId: Int, val sha: String)

    private val guard = Any()
    private val slots = Semaphore(16, true)
    private val pending = HashMap<ChunkKey, ArrayDeque<CompletableFuture<SteamLanWire.Answer>>>()

    @Volatile
    private var failure: IOException? = null

    init {
        socket.soTimeout = 0
        Thread({ readResponses() }, "steam-lan-receiver").apply {
            isDaemon = true
            start()
        }
    }

    fun exchange(appId: Int, depotId: Int, sha: ByteArray, token: Long): SteamLanWire.Answer {
        failure?.let { throw it }
        slots.acquire()
        val key = ChunkKey(depotId, sha.toHex())
        val completion = CompletableFuture<SteamLanWire.Answer>()
        try {
            synchronized(guard) {
                failure?.let { throw it }
                pending.getOrPut(key) { ArrayDeque() }.addLast(completion)
                socket.getOutputStream().write(SteamLanWire.frameChunkRequest(appId, depotId, sha, token))
            }
            return try {
                completion.get(30, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw (e.cause as? Exception ?: IOException("Peer response failed", e))
            } catch (e: TimeoutException) {
                val timeout = IOException("Steam peer chunk response timed out", e)
                fail(timeout)
                throw timeout
            }
        } catch (e: IOException) {
            fail(e)
            throw e
        } finally {
            synchronized(guard) {
                pending[key]?.let { queue ->
                    queue.remove(completion)
                    if (queue.isEmpty()) pending.remove(key)
                }
            }
            slots.release()
        }
    }

    private fun readResponses() {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
            var skipped = 0
            while (true) {
                val header = ByteArray(8)
                input.readFully(header)
                val size = SteamLanWire.read32(header, 0)
                require(header.copyOfRange(4, 8).contentEquals("VT01".toByteArray())) {
                    "Unexpected Steam TCP magic"
                }
                require(size in 8..SteamLanWire.maxFrameBytes) { "Invalid Steam TCP frame length=$size" }
                val frame = ByteArray(size)
                input.readFully(frame)
                val message = SteamLanWire.read32(frame, 0) and Int.MAX_VALUE
                if (message != SteamLanWire.chunkResponse) {
                    require(++skipped <= 4) { "Too many non-chunk Steam TCP frames" }
                    continue
                }
                skipped = 0
                val answer = SteamLanWire.parseChunkFrame(frame)
                val key = ChunkKey(answer.depotId, answer.sha.toHex())
                val completion = synchronized(guard) {
                    pending[key]?.let { queue ->
                        val next = queue.removeFirst()
                        if (queue.isEmpty()) pending.remove(key)
                        next
                    }
                } ?: throw IOException("Unmatched Steam peer response depot=${answer.depotId} sha=${key.sha}")
                completion.complete(answer)
            }
        } catch (e: Exception) {
            fail(IOException("Steam peer receiver stopped: ${e.message.orEmpty()}", e))
        }
    }

    private fun fail(reason: IOException) {
        val waiting: List<CompletableFuture<SteamLanWire.Answer>>
        synchronized(guard) {
            if (failure != null) return
            failure = reason
            waiting = pending.values.flatMap { it.toList() }
            pending.clear()
        }
        runCatching { socket.close() }
        waiting.forEach { it.completeExceptionally(reason) }
    }

    override fun close() {
        fail(IOException("Steam LAN connection closed"))
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) {
            for (byte in this@toHex) {
                append(digits[(byte.toInt() and 255) ushr 4])
                append(digits[byte.toInt() and 15])
            }
        }
    }
}
