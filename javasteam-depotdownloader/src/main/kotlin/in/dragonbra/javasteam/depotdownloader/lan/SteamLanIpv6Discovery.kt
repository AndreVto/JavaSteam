package `in`.dragonbra.javasteam.depotdownloader.lan

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.Collections

internal object SteamLanIpv6Discovery {
    fun probe(
        discoveredHost: String,
        localClientId: Long,
        peerClientId: Long,
        steamId: Long,
        ownPacket: ByteArray,
        onEvent: (String) -> Unit,
    ): List<Inet6Address> {
        val peerAddress = InetAddress.getByName(discoveredHost) as? Inet4Address ?: return emptyList()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.let(Collections::list).orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filter { network ->
                val sameIpv4Network = network.interfaceAddresses.any { local ->
                    val address = local.address
                    address is Inet4Address && SteamLanEndpoints.sameNetwork(
                        address.address, peerAddress.address, local.networkPrefixLength.toInt(),
                    )
                }
                sameIpv4Network && Collections.list(network.inetAddresses).any {
                    it is Inet6Address && !it.isLoopbackAddress && !it.isAnyLocalAddress
                }
            }
        val group = InetAddress.getByName("ff02::1") as Inet6Address
        val found = linkedMapOf<String, Inet6Address>()
        for (network in interfaces.take(4)) {
            runCatching {
                DatagramChannel.open(StandardProtocolFamily.INET6).use { channel ->
                    channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, network)
                    channel.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
                    val socket = channel.socket()
                    socket.soTimeout = 350
                    val multicast = Inet6Address.getByAddress(null, group.address, network)
                    repeat(2) { sequence ->
                        val query = SteamLanWire.discoveryPacket(localClientId, sequence + 1)
                        channel.send(ByteBuffer.wrap(query), InetSocketAddress(multicast, SteamLanWire.discoveryPort))
                        repeat(3) receiveLoop@ {
                            val packet = DatagramPacket(ByteArray(4096), 4096)
                            try {
                                socket.receive(packet)
                            } catch (_: SocketTimeoutException) {
                                return@receiveLoop
                            }
                            val source = packet.address as? Inet6Address ?: return@receiveLoop
                            if (source.isAnyLocalAddress || source.isLoopbackAddress || source.isMulticastAddress) {
                                return@receiveLoop
                            }
                            val data = packet.data.copyOf(packet.length)
                            val scoped = if (source.isLinkLocalAddress) {
                                Inet6Address.getByAddress(null, source.address, network)
                            } else {
                                source
                            }
                            if (runCatching { SteamLanWire.packetType(data) }.getOrNull() == 0) {
                                socket.send(DatagramPacket(ownPacket, ownPacket.size, scoped, packet.port))
                            }
                            val status = runCatching { SteamLanWire.status(data) }.getOrNull()
                            if (status != null && matchesPeer(status, peerClientId, steamId)) {
                                found.putIfAbsent(scoped.hostAddress, scoped)
                                socket.send(DatagramPacket(ownPacket, ownPacket.size, scoped, packet.port))
                            }
                        }
                    }
                }
            }.onFailure { error ->
                onEvent("[steam-lan] IPv6 discovery failed reason=${error::class.java.simpleName}")
            }
        }
        return found.values.toList()
    }

    internal fun matchesPeer(status: SteamLanWire.Status, peerClientId: Long, steamId: Long): Boolean =
        status.clientId == peerClientId && status.steamIds.contains(steamId)
}
