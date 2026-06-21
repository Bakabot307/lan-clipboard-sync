package com.gnaht.phoneclipboardsync.network

import android.content.Context
import android.net.wifi.WifiManager
import com.gnaht.phoneclipboardsync.data.ClipPayload
import com.gnaht.phoneclipboardsync.data.ConnectionRequest
import com.gnaht.phoneclipboardsync.data.DiscoveredLanItem
import com.gnaht.phoneclipboardsync.data.PeerInfo
import com.gnaht.phoneclipboardsync.data.SessionConfig
import com.gnaht.phoneclipboardsync.data.SessionRole
import com.gnaht.phoneclipboardsync.data.SessionState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake

class LanSessionManager(
    private val scope: CoroutineScope,
    appContext: Context,
) {
    private val applicationContext = appContext.applicationContext
    private val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
        setReferenceCounted(false)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }

    private var server: LanClipboardServer? = null
    private var client: LanClipboardClient? = null
    private var localConfig: SessionConfig? = null
    private var activeConfig: SessionConfig? = null
    private var activeGroupDeviceId: String? = null
    private var visibleGroupMembers: List<PeerInfo> = emptyList()
    private var discoveryJob: Job? = null
    private var advertiserJob: Job? = null

    private val _sessionState = MutableStateFlow(SessionState())
    private val _incomingClips = MutableSharedFlow<ClipPayload>(extraBufferCapacity = 32)
    private val _localIpAddress = MutableStateFlow(NetworkUtils.findLocalIpv4Address())

    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    val incomingClips = _incomingClips
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    fun updateLocalConfig(config: SessionConfig) {
        localConfig = config
        _localIpAddress.value = NetworkUtils.findLocalIpv4Address()
        ensureServer(config)
        startAdvertiser()
        if (!_sessionState.value.isRunning) {
            _sessionState.update {
                it.copy(
                    localAddress = _localIpAddress.value,
                    statusMessage = "Ready to connect",
                )
            }
        }
    }

    fun start(config: SessionConfig) {
        updateLocalConfig(config)

        when (config.role) {
            SessionRole.HOST -> startHost(config)
            SessionRole.CLIENT -> startClient(config)
        }
    }

    fun scanLan(config: SessionConfig) {
        updateLocalConfig(config)
        acquireLanDiscoveryLock()
        discoveryJob?.cancel()

        _sessionState.update {
            it.copy(
                isDiscovering = true,
                discoveredItems = emptyList(),
                statusMessage = "Scanning for LAN devices",
                localAddress = _localIpAddress.value,
            )
        }

        discoveryJob = scope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val buffer = ByteArray(DISCOVERY_PACKET_BYTES)

            runCatching {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.soTimeout = DISCOVERY_SOCKET_TIMEOUT_MS.toInt()
                    socket.bind(InetSocketAddress(DISCOVERY_PORT))

                    while (isActive && System.currentTimeMillis() - startedAt < DISCOVERY_TIMEOUT_MS) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }

                        val text = packet.data.decodeToString(0, packet.length)
                        val message = runCatching {
                            json.decodeFromString<RoomDiscoveryMessage>(text)
                        }.getOrNull() ?: continue

                        val configSnapshot = localConfig ?: continue
                        if (
                            message.appId != RoomDiscoveryMessage.APP_ID ||
                            message.deviceId == configSnapshot.deviceId ||
                            message.deviceId == activeGroupDeviceId ||
                            !message.hasOpenRoom
                        ) {
                            continue
                        }

                        val hostIp = message.hostIp.ifBlank { packet.address.hostAddress.orEmpty() }
                        if (hostIp.isBlank()) continue

                        val item = DiscoveredLanItem(
                            deviceId = message.deviceId,
                            deviceName = message.deviceName,
                            hostIp = hostIp,
                            port = message.port,
                            roomCode = message.roomCode,
                            hasOpenRoom = message.hasOpenRoom,
                            memberNames = message.memberNames.ifEmpty { listOf(message.deviceName) },
                        )

                        _sessionState.update { state ->
                            val updated = (state.discoveredItems.filterNot { it.deviceId == item.deviceId } + item)
                                .sortedWith(compareByDescending<DiscoveredLanItem> { it.memberNames.size }.thenBy { it.deviceName })
                            state.copy(discoveredItems = updated)
                        }

                    }
                }
            }.onFailure { throwable ->
                updateStatus(throwable.message ?: "Could not scan LAN devices")
            }

            _sessionState.update { state ->
                state.copy(
                    isDiscovering = false,
                    statusMessage = if (state.discoveredItems.isEmpty()) {
                        "No LAN devices found"
                    } else {
                        "Found ${state.discoveredItems.size} devices or groups"
                    },
                )
            }
        }
    }

    fun connectToItem(config: SessionConfig, item: DiscoveredLanItem) {
        updateLocalConfig(config)

        if (!item.hasOpenRoom) return
        activeGroupDeviceId = item.deviceId

        startClient(
            config.copy(
                role = SessionRole.CLIENT,
                hostIp = item.hostIp,
                port = item.port,
                pairCode = item.roomCode,
            ),
        )
    }

    fun acceptRequest(request: ConnectionRequest) {
        _sessionState.update {
            it.copy(pendingRequests = it.pendingRequests.filterNot { pending -> pending.deviceId == request.deviceId })
        }

        if (request.isInvitation) {
            val config = localConfig ?: return
            activeGroupDeviceId = request.deviceId
            startClient(
                config.copy(
                    role = SessionRole.CLIENT,
                    hostIp = request.hostIp,
                    port = request.port,
                    pairCode = request.roomCode,
                ),
            )
        } else {
            server?.acceptJoin(request.deviceId)
        }
    }

    fun rejectRequest(request: ConnectionRequest) {
        _sessionState.update {
            it.copy(pendingRequests = it.pendingRequests.filterNot { pending -> pending.deviceId == request.deviceId })
        }

        if (!request.isInvitation) {
            server?.rejectJoin(request.deviceId)
        }
    }

    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null

        client?.close()
        client = null

        server?.clearRoom()
        activeConfig = null
        activeGroupDeviceId = null
        visibleGroupMembers = emptyList()

        _sessionState.update {
            it.copy(
                isRunning = false,
                isDiscovering = false,
                role = SessionRole.HOST,
                statusMessage = "Ready to connect",
                connectedPeers = emptyList(),
                pendingRequests = emptyList(),
            )
        }
    }

    fun shutdown() {
        discoveryJob?.cancel()
        discoveryJob = null

        advertiserJob?.cancel()
        advertiserJob = null
        releaseLanDiscoveryLock()

        client?.close()
        client = null

        server?.clearRoom()
        server?.shutdown()
        server = null

        activeConfig = null
        activeGroupDeviceId = null
        visibleGroupMembers = emptyList()

        _sessionState.update {
            it.copy(
                isRunning = false,
                isDiscovering = false,
                role = SessionRole.HOST,
                statusMessage = "Ready to connect",
                connectedPeers = emptyList(),
                discoveredItems = emptyList(),
                pendingRequests = emptyList(),
            )
        }
    }

    fun publishLocalClip(payload: ClipPayload): Boolean {
        val delivered = when (activeConfig?.role) {
            SessionRole.HOST -> {
                val hasPeers = _sessionState.value.connectedPeers.isNotEmpty()
                if (hasPeers) {
                    server?.relayClipboard(payload)
                }
                hasPeers
            }
            SessionRole.CLIENT -> client?.sendClipboard(payload) == true
            null -> false
        }

        updateStatus(
            if (delivered) {
                "Clipboard sent"
            } else {
                "No connected devices to receive clipboard"
            },
        )
        return delivered
    }

    fun markRemoteClipReceived(sourceDeviceName: String) {
        updateStatus("Received clipboard from $sourceDeviceName")
    }

    private fun ensureServer(config: SessionConfig) {
        if (server != null) return

        server = LanClipboardServer(
            port = config.port,
            json = json,
            getRoomCode = { activeConfig?.pairCode ?: localConfig?.pairCode.orEmpty() },
            isRoomOpen = { activeConfig?.role != SessionRole.CLIENT },
            shouldAutoAcceptJoin = { localConfig?.autoConnectEnabled == true },
            getHostDeviceName = { activeConfig?.deviceName ?: localConfig?.deviceName ?: config.deviceName },
            getHostDeviceId = { activeConfig?.deviceId ?: localConfig?.deviceId ?: config.deviceId },
            onStatus = { updateStatus(it) },
            onJoinRequest = { request -> addPendingRequest(request) },
            onInviteReceived = { request -> handleIncomingInvitation(request) },
            onPeersChanged = { updatePeers(it) },
            onRemoteClip = { payload -> scope.launch { _incomingClips.emit(payload) } },
        ).also { it.start() }
    }

    private fun startHost(config: SessionConfig) {
        updateLocalConfig(config)
        client?.close()
        client = null
        server?.clearRoom()
        activeConfig = config

        _sessionState.update {
            it.copy(
                isRunning = true,
                role = SessionRole.HOST,
                localAddress = _localIpAddress.value,
                statusMessage = "Ready to share clipboard",
                connectedPeers = emptyList(),
            )
        }
    }

    private fun startClient(config: SessionConfig) {
        updateLocalConfig(config)
        client?.close()
        client = null
        activeConfig = config

        if (config.hostIp.isBlank()) {
            _sessionState.update {
                it.copy(isRunning = false, statusMessage = "Host address not found")
            }
            return
        }

        _sessionState.update {
            it.copy(
                isRunning = false,
                role = SessionRole.CLIENT,
                localAddress = _localIpAddress.value,
                statusMessage = "Connecting to ${config.hostIp}:${config.port}",
                connectedPeers = emptyList(),
            )
        }

        lateinit var newClient: LanClipboardClient
        newClient = LanClipboardClient(
            serverUri = URI("ws://${config.hostIp}:${config.port}"),
            helloMessage = HelloMessage(
                deviceId = config.deviceId,
                deviceName = config.deviceName,
                pairCode = config.pairCode,
            ),
            json = json,
            onStatus = { updateStatus(it) },
            onAccepted = { hostName, hostDeviceId, peers ->
                if (hostDeviceId.isNotBlank()) {
                    activeGroupDeviceId = hostDeviceId
                }
                val allPeers = peers.toPeerInfos()
                val remotePeers = allPeers.filterNot { it.deviceId == config.deviceId }
                visibleGroupMembers = allPeers
                _sessionState.update {
                    it.copy(
                        isRunning = true,
                        role = SessionRole.CLIENT,
                        statusMessage = "Sharing clipboard with ${remotePeers.joinNames()}",
                        connectedPeers = remotePeers.ifEmpty { listOf(PeerInfo("host", hostName)) },
                    )
                }
            },
            onRejected = { reason ->
                _sessionState.update { it.copy(isRunning = false, statusMessage = reason) }
            },
            onPeerList = { peers ->
                val allPeers = peers.toPeerInfos()
                val remotePeers = allPeers.filterNot { it.deviceId == config.deviceId }
                visibleGroupMembers = allPeers
                if (remotePeers.isEmpty()) {
                    handleClientClosed(newClient, "Disconnected")
                } else {
                    _sessionState.update {
                        it.copy(
                            isRunning = true,
                            connectedPeers = remotePeers,
                            statusMessage = "Sharing clipboard with ${remotePeers.joinNames()}",
                        )
                    }
                }
            },
            onRemoteClip = { payload -> scope.launch { _incomingClips.emit(payload) } },
            onClosed = { reason -> handleClientClosed(newClient, reason) },
        ).also {
            client = it
            scope.launch(Dispatchers.IO) {
                runCatching { it.connectBlocking(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    .onSuccess { connected ->
                        if (!connected && client === it) {
                            it.close()
                            handleClientClosed(it, "Connection timed out")
                        }
                    }
                    .onFailure { throwable ->
                        if (client === it) {
                            handleClientClosed(it, throwable.message ?: "Connection failed")
                        }
                    }
            }
        }
    }

    private fun startAdvertiser() {
        if (advertiserJob?.isActive == true) return

        acquireLanDiscoveryLock()
        advertiserJob = scope.launch(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    while (isActive) {
                        val config = localConfig
                        if (config != null) {
                            val roomConfig = activeConfig
                            val localHostIp = NetworkUtils.findLocalIpv4Address()
                            val isClientGroup = roomConfig?.role == SessionRole.CLIENT && _sessionState.value.isRunning
                            val groupMembers = currentGroupMembers(config)
                            val advertisedHostIp = if (isClientGroup) roomConfig?.hostIp.orEmpty() else localHostIp
                            val advertisedPort = if (isClientGroup) roomConfig?.port ?: config.port else config.port
                            val advertisedDeviceId = if (isClientGroup) {
                                activeGroupDeviceId ?: roomConfig?.hostIp.orEmpty()
                            } else {
                                config.deviceId
                            }
                            val sortedMemberNames = groupMembers.map { it.deviceName }.sorted()
                            val displayName = if (sortedMemberNames.size > 1) {
                                "Group ${sortedMemberNames.joinToString(", ")}"
                            } else {
                                config.deviceName
                            }
                            val message = RoomDiscoveryMessage(
                                deviceId = advertisedDeviceId,
                                roomCode = roomConfig?.pairCode ?: config.pairCode,
                                deviceName = displayName,
                                hostIp = advertisedHostIp,
                                port = advertisedPort,
                                hasOpenRoom = true,
                                memberNames = sortedMemberNames,
                            )
                            val data = json.encodeToString(message).toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(
                                data,
                                data.size,
                                InetAddress.getByName(BROADCAST_ADDRESS),
                                DISCOVERY_PORT,
                            )
                            socket.send(packet)
                        }
                        delay(DISCOVERY_INTERVAL_MS)
                    }
                }
            }.onFailure { throwable ->
                updateStatus(throwable.message ?: "Could not advertise device on LAN")
            }
        }
    }

    private fun sendInvite(config: SessionConfig, item: DiscoveredLanItem) {
        val hostIp = NetworkUtils.findLocalIpv4Address()
        val invite = InviteMessage(
            inviterDeviceId = config.deviceId,
            inviterDeviceName = config.deviceName,
            roomCode = config.pairCode,
            hostIp = hostIp,
            port = config.port,
        )

        updateStatus("Sending invite to ${item.deviceName}")
        scope.launch(Dispatchers.IO) {
            runCatching {
                val socket = object : WebSocketClient(URI("ws://${item.hostIp}:${item.port}")) {
                    override fun onOpen(handshakedata: ServerHandshake?) = Unit

                    override fun onMessage(message: String?) = Unit

                    override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit

                    override fun onError(ex: Exception?) {
                        updateStatus(ex?.message ?: "Could not send invite")
                    }
                }

                socket.connectBlocking()
                if (socket.isOpen) {
                    socket.send(json.encodeToString<WireMessage>(invite))
                    Thread.sleep(INVITE_FLUSH_DELAY_MS)
                    socket.closeBlocking()
                }
            }.onSuccess {
                updateStatus("Invite sent to ${item.deviceName}")
            }.onFailure { throwable ->
                updateStatus(throwable.message ?: "Could not send invite")
            }
        }
    }

    private fun addPendingRequest(request: ConnectionRequest) {
        _sessionState.update { state ->
            state.copy(
                pendingRequests = state.pendingRequests
                    .filterNot { it.deviceId == request.deviceId && it.isInvitation == request.isInvitation } + request,
            )
        }
    }

    private fun handleIncomingInvitation(request: ConnectionRequest) {
        if (localConfig?.autoConnectEnabled == true) {
            updateStatus("Auto-accepting invitation from ${request.deviceName}")
            acceptRequest(request)
        } else {
            addPendingRequest(request)
        }
    }

    private fun handleClientClosed(socket: LanClipboardClient, reason: String) {
        if (client !== socket) return

        client = null
        activeConfig = null
        activeGroupDeviceId = null
        visibleGroupMembers = emptyList()

        _sessionState.update {
            it.copy(
                isRunning = false,
                role = SessionRole.HOST,
                connectedPeers = emptyList(),
                pendingRequests = emptyList(),
                statusMessage = reason.ifBlank { "Disconnected" },
            )
        }
    }

    private fun acquireLanDiscoveryLock() {
        runCatching {
            if (!multicastLock.isHeld) {
                multicastLock.acquire()
            }
        }
    }

    private fun releaseLanDiscoveryLock() {
        runCatching {
            if (multicastLock.isHeld) {
                multicastLock.release()
            }
        }
    }

    private fun updateStatus(message: String) {
        _sessionState.update { it.copy(statusMessage = message, localAddress = _localIpAddress.value) }
    }

    private fun updatePeers(peers: List<PeerInfo>) {
        val config = localConfig
        if (peers.isNotEmpty() && activeConfig == null && config != null) {
            activeConfig = config.copy(role = SessionRole.HOST)
            activeGroupDeviceId = config.deviceId
        }

        visibleGroupMembers = if (config != null) {
            listOf(PeerInfo(config.deviceId, config.deviceName)) + peers
        } else {
            peers
        }

        _sessionState.update {
            it.copy(
                isRunning = peers.isNotEmpty(),
                role = SessionRole.HOST,
                connectedPeers = peers,
                statusMessage = if (peers.isEmpty()) {
                    activeConfig = null
                    activeGroupDeviceId = null
                    visibleGroupMembers = emptyList()
                    "Ready to connect"
                } else {
                    "Sharing clipboard with ${peers.joinNames()}"
                },
            )
        }
    }

    private fun currentGroupMembers(config: SessionConfig): List<PeerInfo> {
        val state = _sessionState.value
        return if (state.isRunning && visibleGroupMembers.isNotEmpty()) {
            visibleGroupMembers
        } else {
            listOf(PeerInfo(config.deviceId, config.deviceName))
        }
    }

    private fun List<PeerWire>.toPeerInfos(): List<PeerInfo> {
        return map { PeerInfo(deviceId = it.deviceId, deviceName = it.deviceName) }
            .distinctBy { it.deviceId }
    }

    private fun List<PeerInfo>.joinNames(): String {
        return if (isEmpty()) {
            "another device"
        } else {
            joinToString(", ") { it.deviceName }
        }
    }

    private companion object {
        private const val DISCOVERY_PORT = 8788
        private const val BROADCAST_ADDRESS = "255.255.255.255"
        private const val DISCOVERY_INTERVAL_MS = 1200L
        private const val DISCOVERY_TIMEOUT_MS = 10000L
        private const val DISCOVERY_SOCKET_TIMEOUT_MS = 1000L
        private const val DISCOVERY_PACKET_BYTES = 2048
        private const val INVITE_FLUSH_DELAY_MS = 250L
        private const val CONNECTION_TIMEOUT_SECONDS = 6L
        private const val MULTICAST_LOCK_TAG = "LanClipboardSync"
    }
}

