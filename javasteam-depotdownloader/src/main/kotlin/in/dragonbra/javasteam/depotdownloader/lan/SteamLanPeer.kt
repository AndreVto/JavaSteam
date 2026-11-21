package `in`.dragonbra.javasteam.depotdownloader.lan

import com.google.protobuf.ByteString
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesRemoteclientServiceMessages.CRemoteClient_DeviceDetails
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesRemoteclientServiceMessages.CRemoteClient_GetRecentClients_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesRemoteclientServiceMessages.CRemoteClient_Online_Notification
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesRemoteclientServiceMessages.CRemoteClient_SteamBroadcast_Notification
import `in`.dragonbra.javasteam.rpc.service.RemoteClient
import `in`.dragonbra.javasteam.rpc.service.RemoteClientSteamClient
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.cdn.DepotChunk
import `in`.dragonbra.javasteam.types.ChunkData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** Settings for native downloads from a Steam desktop client on the local network. */
data class SteamLanOptions(
    val clientId: Long,
    val callbacks: CallbackManager,
    val preferredHost: String? = null,
    val deviceName: String = "Android",
    val onEvent: (String) -> Unit = {},
    val onTransferActive: (Boolean) -> Unit = {},
)

class SteamLanPeer private constructor(
    private val appId: Int,
    private val depots: Set<Int>,
    private val token: Long,
    private val callbackSubscription: Closeable,
    private val onEvent: (String) -> Unit,
    private val onTransferActive: (Boolean) -> Unit,
    connection: Socket,
) : Closeable {
    private val pipeline = SteamLanPipeline(connection)
    private val failures = AtomicInteger()
    private val disabled = AtomicBoolean()
    private val completedChunks = AtomicInteger()
    private val completedBytes = AtomicLong()
    private val receivedBytes = AtomicLong()
    private val closed = AtomicBoolean()
    private val transferActive = AtomicBoolean()

    private fun setTransferActive(active: Boolean) {
        synchronized(transferActive) {
            if (active && (disabled.get() || closed.get())) return
            if (transferActive.getAndSet(active) != active) onTransferActive(active)
        }
    }

    companion object {
        suspend fun open(client: SteamClient, appId: Int, scope: CoroutineScope, options: SteamLanOptions): SteamLanPeer? {
            require(options.clientId != 0L)
            val steamId = client.steamID?.convertToUInt64() ?: return null
            val unified = client.getHandler(SteamUnifiedMessages::class.java) ?: return null
            val content = client.getHandler(SteamContent::class.java) ?: return null
            val remote = unified.createService(RemoteClient::class.java)
            val ownPacket = SteamLanWire.statusPacket(options.clientId, steamId, options.deviceName, "")
            val statuses = ConcurrentHashMap<Long, SteamLanWire.Status>()
            val subscription = options.callbacks.subscribeServiceNotification(
                RemoteClientSteamClient::class.java,
                CRemoteClient_SteamBroadcast_Notification.Builder::class.java,
            ) { notification ->
                val incoming = notification.body.build().payload.toByteArray()
                try {
                    val status = SteamLanWire.status(incoming)
                    if (status != null && status.clientId != options.clientId && status.steamIds.contains(steamId)) {
                        statuses[status.clientId] = status
                    }
                    if (SteamLanWire.packetType(incoming) == 0) {
                        remote.sendSteamBroadcastPacket(
                            CRemoteClient_SteamBroadcast_Notification.newBuilder()
                                .setSteamid(steamId).setClientid(options.clientId)
                                .setPayload(ByteString.copyFrom(ownPacket)).build(),
                        )
                    }
                } catch (_: Exception) {
                }
            }
            try {
                remote.notifyOnline(
                    CRemoteClient_Online_Notification.newBuilder()
                        .setSteamid(steamId).setRemoteClientId(options.clientId)
                        .setDeviceDetails(
                            CRemoteClient_DeviceDetails.newBuilder()
                                .setDeviceFriendlyName(options.deviceName)
                                .setOsType(-102).setGamingDeviceType(1),
                        ).build(),
                )
                remote.sendSteamBroadcastPacket(
                    CRemoteClient_SteamBroadcast_Notification.newBuilder()
                        .setSteamid(steamId).setClientid(options.clientId)
                        .setPayload(ByteString.copyFrom(ownPacket)).build(),
                )
                withTimeoutOrNull(2500L) {
                    runCatching { remote.getRecentClients(CRemoteClient_GetRecentClients_Request.getDefaultInstance()).await() }
                }
                for (attempt in 0 until 4) {
                    if (statuses.isNotEmpty()) break
                    delay(500)
                }
                val addresses = linkedSetOf<String>()
                options.preferredHost?.takeIf { it.isNotBlank() }?.let { addresses.add(it) }
                statuses.values.forEach { addresses.addAll(it.addresses) }
                addresses += "255.255.255.255"
                for (address in addresses) {
                    val discovered = withContext(Dispatchers.IO) {
                        discover(address, options.clientId, steamId, ownPacket)
                    } ?: continue
                    val response = try {
                        withTimeoutOrNull(8000L) {
                            content.requestPeerContentServer(
                                options.clientId, steamId, discovered.first.clientId, appId, 0, scope,
                            ).await()
                        } ?: continue
                    } catch (e: Exception) {
                        options.onEvent("[steam-lan] peer authorization failed app=$appId reason=${e::class.java.simpleName}")
                        continue
                    }
                    if (response.serverPort !in 1..65535 || response.accessToken == 0L) continue
                    val reported = statuses[discovered.first.clientId]
                    val peerStatus = discovered.first.copy(
                        addresses = (discovered.first.addresses + reported?.addresses.orEmpty()).distinct(),
                    )
                    val candidates = withContext(Dispatchers.IO) {
                        val advertised = SteamLanEndpoints.resolve(peerStatus, discovered.second)
                        val active = if (advertised.none { it is Inet6Address }) {
                            SteamLanIpv6Discovery.probe(
                                discovered.second, options.clientId, discovered.first.clientId,
                                steamId, ownPacket, options.onEvent,
                            )
                        } else {
                            emptyList()
                        }
                        (active + advertised).distinctBy { it.hostAddress }
                    }
                    val connected = withContext(Dispatchers.IO) {
                        var result: Pair<InetAddress, Socket>? = null
                        for (address in candidates) {
                            val connection = Socket()
                            try {
                                connection.connect(InetSocketAddress(address, response.serverPort), 1800)
                                connection.soTimeout = 12000
                                connection.tcpNoDelay = true
                                result = address to connection
                                break
                            } catch (_: Exception) {
                                connection.close()
                            }
                        }
                        result
                    }
                    if (connected == null) {
                        continue
                    }
                    options.onEvent("[steam-lan] connected app=$appId")
                    return SteamLanPeer(
                        appId, response.installedDepotsList.toSet(),
                        response.accessToken, subscription, options.onEvent, options.onTransferActive, connected.second,
                    )
                }
                options.onEvent("[steam-lan] no eligible local Steam content server for app=$appId; using CDN")
                subscription.close()
                return null
            } catch (e: Exception) {
                subscription.close()
                options.onEvent("[steam-lan] discovery unavailable app=$appId reason=${e::class.java.simpleName}; using CDN")
                return null
            }
        }

        private fun discover(
            host: String,
            clientId: Long,
            steamId: Long,
            ownPacket: ByteArray,
        ): Pair<SteamLanWire.Status, String>? {
            val target = InetAddress.getByName(host)
            if (target !is Inet4Address) return null
            if (target.hostAddress != "255.255.255.255" && !target.isSiteLocalAddress) return null
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                runCatching { socket.bind(InetSocketAddress(SteamLanWire.discoveryPort)) }
                    .onFailure { socket.bind(InetSocketAddress(0)) }
                socket.broadcast = true
                socket.soTimeout = 750
                val buffer = ByteArray(4096)
                var peer: Pair<SteamLanWire.Status, String>? = null
                repeat(3) { attempt ->
                    if (peer != null) return@repeat
                    val query = SteamLanWire.discoveryPacket(clientId, attempt + 1)
                    socket.send(DatagramPacket(query, query.size, target, SteamLanWire.discoveryPort))
                    repeat(3) {
                        val response = DatagramPacket(buffer, buffer.size)
                        try { socket.receive(response) } catch (_: SocketTimeoutException) { return@repeat }
                        val data = response.data.copyOf(response.length)
                        val source = response.address.hostAddress
                        if (!response.address.isSiteLocalAddress) return@repeat
                        if (runCatching { SteamLanWire.packetType(data) }.getOrNull() == 0) {
                            socket.send(DatagramPacket(ownPacket, ownPacket.size, response.address, response.port))
                        }
                        val status = runCatching { SteamLanWire.status(data) }.getOrNull()
                        if (status != null && status.clientId != clientId && status.steamIds.contains(steamId)) {
                            socket.send(DatagramPacket(ownPacket, ownPacket.size, response.address, response.port))
                            peer = status to source
                        }
                    }
                }
                return peer
            }
            return null
        }
    }

    fun serves(depotId: Int): Boolean = !disabled.get() && (depots.isEmpty() || depotId in depots)

    fun fetch(depotId: Int, chunk: ChunkData): ByteArray? {
        if (!serves(depotId)) return null
        var rejectedResult = -1
        return try {
            val sha = requireNotNull(chunk.chunkID)
            val answer = pipeline.exchange(appId, depotId, sha, token)
            if (answer.result != 1) {
                rejectedResult = answer.result
                throw IllegalArgumentException("Steam peer returned result=${answer.result}")
            }
            require(answer.appId == appId && answer.depotId == depotId) {
                "Steam peer response app_match=${answer.appId == appId} depot_match=${answer.depotId == depotId}"
            }
            require(answer.sha.contentEquals(sha)) { "Steam peer response SHA mismatch" }
            require(!answer.encrypted) { "Steam peer returned encrypted content" }
            val plain = if (answer.compressed) {
                ByteArray(chunk.uncompressedLength).also {
                    DepotChunk.processDecrypted(chunk, answer.data, it)
                }
            } else {
                answer.data
            }
            require(plain.size == chunk.uncompressedLength)
            if (!answer.compressed) {
                require(`in`.dragonbra.javasteam.util.Adler32.calculate(plain) == chunk.checksum)
            }
            require(MessageDigest.getInstance("SHA-1").digest(plain).contentEquals(sha))
            failures.set(0)
            completedChunks.incrementAndGet()
            completedBytes.addAndGet(plain.size.toLong())
            receivedBytes.addAndGet(answer.data.size.toLong())
            if (!transferActive.get()) setTransferActive(true)
            plain
        } catch (e: Exception) {
            val consecutive = failures.incrementAndGet()
            val shouldDisable = rejectedResult == 53 || e is java.io.IOException || consecutive >= 4
            if (shouldDisable && disabled.compareAndSet(false, true)) {
                pipeline.close()
                setTransferActive(false)
            }
            val reason = if (rejectedResult >= 0) "result=$rejectedResult" else e::class.java.simpleName
            onEvent("[steam-lan] chunk failed app=$appId depot=$depotId reason=$reason disabled=${disabled.get()}; CDN fallback")
            null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disabled.set(true)
        pipeline.close()
        callbackSubscription.close()
        setTransferActive(false)
        onEvent("[steam-lan] finished app=$appId chunks=${completedChunks.get()} lan_bytes=${receivedBytes.get()} verified_bytes=${completedBytes.get()}")
    }
}
