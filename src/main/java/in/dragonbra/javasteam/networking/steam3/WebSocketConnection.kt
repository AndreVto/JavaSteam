package `in`.dragonbra.javasteam.networking.steam3

import `in`.dragonbra.javasteam.util.log.LogManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.URLProtocol
import io.ktor.http.path
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class WebSocketConnection : Connection(), CoroutineScope {
    companion object {
        private val logger = LogManager.getLogger<WebSocketConnection>()
        private const val PING_INTERVAL_MILLIS = 20_000L
    }

    private val job: Job = SupervisorJob()
    private val disconnecting = AtomicBoolean(false)
    private val lifecycleLock = Any()

    @Volatile private var client: HttpClient? = null
    @Volatile private var session: WebSocketSession? = null
    private var endpoint: InetSocketAddress? = null

    override val coroutineContext: CoroutineContext = Dispatchers.IO + job

    override fun connect(endPoint: InetSocketAddress, timeout: Int) {
        launch {
            logger.debug("Trying connection to ${endPoint.hostName}:${endPoint.port}")
            try {
                if (disconnecting.get()) return@launch
                endpoint = endPoint
                val httpClient = HttpClient(CIO) {
                    engine {
                        endpoint { connectTimeout = timeout.toLong() }
                    }
                    install(WebSockets) {
                        pingIntervalMillis = PING_INTERVAL_MILLIS
                    }
                }
                val clientAccepted = synchronized(lifecycleLock) {
                    if (disconnecting.get()) false else { client = httpClient; true }
                }
                if (!clientAccepted) {
                    httpClient.close()
                    return@launch
                }

                val connectedSession = httpClient.webSocketSession {
                    url {
                        host = endPoint.hostName
                        port = endPoint.port
                        protocol = URLProtocol.WSS
                        path("cmsocket/")
                    }
                }
                val sessionAccepted = synchronized(lifecycleLock) {
                    if (disconnecting.get()) false else { session = connectedSession; true }
                }
                if (!sessionAccepted) {
                    connectedSession.close()
                    return@launch
                }

                launch {
                    try {
                        connectedSession.incoming.consumeEach { frame ->
                            when (frame) {
                                is Frame.Binary -> onNetMsgReceived(NetMsgEventArgs(frame.readBytes(), currentEndPoint))
                                is Frame.Close -> disconnect(false)
                                is Frame.Ping -> logger.debug("Received ping")
                                is Frame.Pong -> logger.debug("Received pong")
                                is Frame.Text -> logger.debug("Received plain text ${frame.readText()}")
                            }
                        }
                    } catch (e: CancellationException) {
                        logger.debug("Websocket cancelling: ${e.message}")
                    } catch (e: Exception) {
                        logger.error("An error occurred while receiving data", e)
                    } finally {
                        if (!disconnecting.get()) disconnect(false)
                    }
                }

                if (!disconnecting.get()) {
                    logger.debug("Connected to ${endPoint.hostName}:${endPoint.port}")
                    onConnected()
                }
            } catch (e: CancellationException) {
                if (!disconnecting.get()) disconnect(false)
            } catch (e: Exception) {
                logger.error("An error occurred setting up the web socket client", e)
                disconnect(false)
            }
        }
    }

    override fun disconnect(userInitiated: Boolean) {
        if (!disconnecting.compareAndSet(false, true)) return
        logger.debug("Disconnect called: $userInitiated")
        val (currentSession, currentClient) = synchronized(lifecycleLock) {
            (session to client).also { session = null; client = null }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                job.cancelChildren()
                withTimeoutOrNull(1_500L) { currentSession?.close() }
            } finally {
                try {
                    currentClient?.close()
                } finally {
                    onDisconnected(userInitiated)
                }
            }
        }
    }

    override fun send(data: ByteArray) {
        launch {
            if (disconnecting.get()) return@launch
            try {
                session?.send(Frame.Binary(true, data))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("An error occurred while sending data", e)
                disconnect(false)
            }
        }
    }

    override fun getLocalIP(): InetAddress = InetAddress.getLocalHost()

    override fun getCurrentEndPoint(): InetSocketAddress? = endpoint

    override fun getProtocolTypes(): ProtocolTypes = ProtocolTypes.WEB_SOCKET
}
