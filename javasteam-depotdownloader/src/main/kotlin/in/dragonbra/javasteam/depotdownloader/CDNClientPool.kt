package `in`.dragonbra.javasteam.depotdownloader

import `in`.dragonbra.javasteam.steam.cdn.Client
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.util.log.LogManager
import `in`.dragonbra.javasteam.util.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages a pool of CDN server connections for efficient content downloading.
 * This class maintains a list of available CDN servers, automatically selects appropriate
 * servers based on load and app compatibility, and handles connection rotation when
 * servers fail or become unavailable.
 *
 * @param steamSession The Steam3 session for server communication
 * @param appId The application ID to download - used to filter compatible CDN servers
 * @param scope The coroutine scope for async operations
 * @param debug If true, enables debug logging
 *
 * @author Oxters
 * @author Lossy
 * @since Nov 7, 2024
 */
class CDNClientPool(
    private val steamSession: Steam3Session,
    private val appId: Int,
    private val scope: CoroutineScope,
    debug: Boolean = false,
    private val preferredHostCount: Int = 8,
) : AutoCloseable {

    private var logger: Logger? = null

    private val selector = CdnServerSelector(preferredCount = preferredHostCount)

    private val mutex: Mutex = Mutex()

    var cdnClient: Client? = null
        private set

    var proxyServer: Server? = null
        private set

    init {
        cdnClient = Client(steamSession.steamClient, collectDiagnostics = true)

        if (debug) {
            logger = LogManager.getLogger(CDNClientPool::class.java)
        }
    }

    override fun close() {
        logger?.debug("Closing...")

        cdnClient?.diagnosticSummary()?.forEach { println("[steam-cdn-region] app=$appId $it") }
        cdnClient?.close()
        cdnClient = null
        proxyServer = null

        logger = null
    }

    @Throws(Exception::class)
    suspend fun updateServerList(maxNumServers: Int? = null) = mutex.withLock {
        val clientCellId = steamSession.steamClient.cellID ?: 0
        val serversForSteamPipe = steamSession.steamContent!!.getServersForSteamPipe(
            cellId = clientCellId,
            maxNumServers = maxNumServers,
            parentScope = scope
        ).await()

        proxyServer = serversForSteamPipe.firstOrNull { it.useAsProxy }

        val weightedCdnServers = serversForSteamPipe
            .filter { server ->
                val isEligibleForApp = server.allowedAppIds.isEmpty() || server.allowedAppIds.contains(appId)
                isEligibleForApp && (server.type == "SteamCache" || server.type == "CDN")
            }
            .sortedBy { it.weightedLoad }
            .distinctBy { Triple(it.protocol, it.vHost ?: it.host, it.port) }

        val orderedServers = CdnRegionPreference.prioritize(
            weightedCdnServers, clientCellId, preferredHostCount,
        ) { it.cellId }
        selector.update(orderedServers)

        val matchingCellCount = weightedCdnServers.count { clientCellId > 0 && it.cellId == clientCellId }
        val matchingInWindow = weightedCdnServers
            .take(maxOf(8, preferredHostCount * 2))
            .count { clientCellId > 0 && it.cellId == clientCellId }
        val country = steamSession.steamClient.ipCountryCode ?: "unknown"
        println(
            "[steam-cdn-region] app=$appId country=$country clientCell=$clientCellId " +
                "returned=${serversForSteamPipe.size} eligible=${orderedServers.size} " +
                "matchingCell=$matchingCellCount matchingInWindow=$matchingInWindow " +
                "preferredHosts=${minOf(preferredHostCount, orderedServers.size)} " +
                "mode=${if (matchingInWindow > 0) "cell-match" else "steam-weighted-load"}"
        )
        orderedServers.take(24).forEachIndexed { index, server ->
            println(
                "[steam-cdn-region] rank=${index + 1} tier=${if (index < preferredHostCount) "preferred" else "reserve"} " +
                    "host=${server.vHost ?: server.host} cell=${server.cellId} " +
                    "sameCell=${clientCellId > 0 && server.cellId == clientCellId} " +
                    "weightedLoad=${server.weightedLoad} type=${server.type}"
            )
        }
        logger?.debug("Found ${orderedServers.size} Servers")

        if (weightedCdnServers.isEmpty()) {
            throw Exception("Failed to retrieve any download servers.")
        }
    }

    fun getConnection(): Server = selector.select()

    fun returnConnection(server: Server?) {
        if (server == null) {
            logger?.error("null server returned to cdn pool.")
            return
        }

        selector.succeeded(server)
    }

    fun returnBrokenConnection(server: Server?) {
        if (server == null) {
            logger?.error("null broken server returned to pool")
            return
        }

        selector.failed(server)
    }
}
