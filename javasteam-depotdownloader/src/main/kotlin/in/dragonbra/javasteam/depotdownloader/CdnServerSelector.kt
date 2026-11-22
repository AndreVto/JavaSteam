package `in`.dragonbra.javasteam.depotdownloader

import `in`.dragonbra.javasteam.steam.cdn.Server
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class CdnServerSelector(
    private val preferredCount: Int = 8,
    private val cooldownNanos: Long = TimeUnit.SECONDS.toNanos(30),
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private class Health {
        val failures = AtomicInteger()
        val cooldownUntil = AtomicLong()
    }

    private val health = ConcurrentHashMap<Server, Health>()
    private val next = AtomicLong()

    @Volatile
    private var servers: List<Server> = emptyList()

    init {
        require(preferredCount > 0)
        require(cooldownNanos > 0)
    }

    fun update(updated: List<Server>) {
        health.clear()
        next.set(0)
        servers = updated.toList()
    }

    fun select(): Server {
        val available = servers
        require(available.isNotEmpty()) { "No Steam CDN servers available" }
        val sequence = next.getAndIncrement()
        val preferred = minOf(preferredCount, available.size)
        val reserveCount = available.size - preferred
        val now = nowNanos()

        if (reserveCount > 0 && Math.floorMod(sequence, 16L) == 15L) {
            val probe = preferred + Math.floorMod(sequence / 16L, reserveCount.toLong()).toInt()
            if (!isCoolingDown(available[probe], now)) return available[probe]
        }

        val start = Math.floorMod(sequence, preferred.toLong()).toInt()
        for (i in 0 until preferred) {
            val server = available[(start + i) % preferred]
            if (!isCoolingDown(server, now)) return server
        }
        for (i in preferred until available.size) {
            val server = available[i]
            if (!isCoolingDown(server, now)) return server
        }
        return available[start]
    }

    fun succeeded(server: Server) {
        health[server]?.apply {
            failures.set(0)
            cooldownUntil.set(0)
        }
    }

    fun failed(server: Server) {
        val state = health.computeIfAbsent(server) { Health() }
        if (state.failures.incrementAndGet() >= 2) {
            state.cooldownUntil.set(nowNanos() + cooldownNanos)
            state.failures.set(0)
        }
    }

    private fun isCoolingDown(server: Server, now: Long): Boolean =
        health[server]?.cooldownUntil?.get()?.let { it != 0L && it - now > 0L } == true
}
