package `in`.dragonbra.javasteam.depotdownloader.lan

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

internal object SteamLanEndpoints {
    fun resolve(status: SteamLanWire.Status, discoveredHost: String): List<InetAddress> {
        val discovered = InetAddress.getByName(discoveredHost) as? Inet4Address ?: return emptyList()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.let(Collections::list).orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filter { network ->
                network.interfaceAddresses.any { local ->
                    val address = local.address
                    address is Inet4Address && sameNetwork(address.address, discovered.address, local.networkPrefixLength.toInt())
                }
            }
        val result = linkedMapOf<String, InetAddress>()
        fun add(address: InetAddress) {
            if (address !is Inet6Address || address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return
            for (network in interfaces) {
                val onLink = network.interfaceAddresses.any { local ->
                    val own = local.address
                    own is Inet6Address && if (address.isLinkLocalAddress) {
                        own.isLinkLocalAddress
                    } else {
                        !own.isLinkLocalAddress && sameNetwork(own.address, address.address, local.networkPrefixLength.toInt())
                    }
                }
                if (!onLink) continue
                val scoped = if (address.isLinkLocalAddress) {
                    runCatching { Inet6Address.getByAddress(null, address.address, network) }.getOrNull()
                } else {
                    address
                } ?: continue
                result.putIfAbsent(scoped.hostAddress, scoped)
            }
        }
        fun resolveHost(name: String) {
            val normalized = name.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            if (normalized.isBlank()) return
            runCatching { InetAddress.getAllByName(normalized) }.getOrNull()?.forEach(::add)
        }
        status.addresses.forEach(::resolveHost)
        return result.values.take(4) + discovered
    }

    internal fun sameNetwork(left: ByteArray, right: ByteArray, prefix: Int): Boolean {
        if (left.size != right.size || prefix !in 1..(left.size * 8)) return false
        val bytes = prefix / 8
        val bits = prefix % 8
        for (i in 0 until bytes) if (left[i] != right[i]) return false
        if (bits == 0) return true
        val mask = (0xff shl (8 - bits)) and 0xff
        return (left[bytes].toInt() and mask) == (right[bytes].toInt() and mask)
    }
}
