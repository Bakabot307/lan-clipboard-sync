package com.gnaht.phoneclipboardsync.network

import com.gnaht.phoneclipboardsync.data.ClipPayload
import com.gnaht.phoneclipboardsync.data.ConnectionRequest
import com.gnaht.phoneclipboardsync.data.PeerInfo
import java.net.InetSocketAddress
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

class LanClipboardServer(
    port: Int,
    private val json: Json,
    private val getRoomCode: () -> String,
    private val isRoomOpen: () -> Boolean,
    private val shouldAutoAcceptJoin: () -> Boolean,
    private val getHostDeviceName: () -> String,
    private val getHostDeviceId: () -> String,
    private val onStatus: (String) -> Unit,
    private val onJoinRequest: (ConnectionRequest) -> Unit,
    private val onInviteReceived: (ConnectionRequest) -> Unit,
    private val onPeersChanged: (List<PeerInfo>) -> Unit,
    private val onRemoteClip: (ClipPayload) -> Unit,
) : WebSocketServer(InetSocketAddress(port)) {
    private val peers = linkedMapOf<WebSocket, PeerInfo>()
    private val pendingJoins = linkedMapOf<String, Pair<WebSocket, PeerInfo>>()
    private val preApprovedDeviceIds = mutableSetOf<String>()

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        onStatus("Device is connecting")
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        if (conn == null) return

        val removedPeer = peers.remove(conn) != null
        val pendingEntry = pendingJoins.entries.firstOrNull { it.value.first == conn }
        if (pendingEntry != null) {
            pendingJoins.remove(pendingEntry.key)
        }

        if (removedPeer) {
            onPeersChanged(peers.values.toList())
            broadcastPeerList()
        }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        if (conn == null || message.isNullOrBlank()) return
        val decoded = runCatching { json.decodeFromString<WireMessage>(message) }.getOrNull() ?: return

        when (decoded) {
            is HelloMessage -> handleHello(conn, decoded)
            is InviteMessage -> handleInvite(decoded)
            is ClipMessage -> handleClip(conn, decoded)
            is JoinResponseMessage, is PeerListMessage -> Unit
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        onStatus(ex?.message ?: "LAN connection error")
    }

    override fun onStart() {
        onStatus("Ready for LAN connections on port ${address.port}")
    }

    fun preApprove(deviceId: String) {
        preApprovedDeviceIds += deviceId
    }

    fun acceptJoin(deviceId: String) {
        val pending = pendingJoins.remove(deviceId) ?: return
        acceptPeer(pending.first, pending.second)
    }

    fun rejectJoin(deviceId: String) {
        val pending = pendingJoins.remove(deviceId) ?: return
        pending.first.send(
            json.encodeToString<WireMessage>(
                JoinResponseMessage(accepted = false, reason = "Connection rejected"),
            ),
        )
        pending.first.close(4403, "Connection rejected")
    }

    fun clearRoom() {
        pendingJoins.values.forEach { (socket, _) ->
            runCatching {
                socket.send(
                    json.encodeToString<WireMessage>(
                        JoinResponseMessage(accepted = false, reason = "Sharing group closed"),
                    ),
                )
                socket.close(4404, "Sharing group closed")
            }
        }
        pendingJoins.clear()

        peers.keys.forEach { socket ->
            runCatching { socket.close(1000, "Sharing group closed") }
        }
        peers.clear()
        onPeersChanged(emptyList())
    }

    fun relayClipboard(payload: ClipPayload, exclude: WebSocket? = null) {
        val encoded = json.encodeToString<WireMessage>(ClipMessage(payload))
        peers.keys.forEach { socket ->
            if (socket != exclude && socket.isOpen) {
                socket.send(encoded)
            }
        }
    }

    fun shutdown() {
        runCatching { stop(500) }
    }

    private fun handleHello(conn: WebSocket, message: HelloMessage) {
        if (!isRoomOpen()) {
            conn.send(
                json.encodeToString<WireMessage>(
                    JoinResponseMessage(accepted = false, reason = "Device is already in another group"),
                ),
            )
            conn.close(4404, "Device is already in another group")
            return
        }

        if (message.pairCode != getRoomCode()) {
            conn.send(
                json.encodeToString<WireMessage>(
                    JoinResponseMessage(accepted = false, reason = "Invalid connection code"),
                ),
            )
            conn.close(4401, "Invalid connection code")
            return
        }

        val peer = PeerInfo(message.deviceId, message.deviceName)
        if (shouldAutoAcceptJoin()) {
            acceptPeer(conn, peer, autoAccepted = true)
            return
        }

        if (preApprovedDeviceIds.remove(message.deviceId)) {
            acceptPeer(conn, peer)
            return
        }

        pendingJoins[message.deviceId] = conn to peer
        onJoinRequest(
            ConnectionRequest(
                deviceId = message.deviceId,
                deviceName = message.deviceName,
                hostIp = conn.remoteSocketAddress?.address?.hostAddress.orEmpty(),
                port = address.port,
                roomCode = message.pairCode,
                isInvitation = false,
            ),
        )
        onStatus("${message.deviceName} requested to connect")
    }

    private fun handleInvite(message: InviteMessage) {
        onInviteReceived(
            ConnectionRequest(
                deviceId = message.inviterDeviceId,
                deviceName = message.inviterDeviceName,
                hostIp = message.hostIp,
                port = message.port,
                roomCode = message.roomCode,
                isInvitation = true,
            ),
        )
        onStatus("${message.inviterDeviceName} invited you to connect")
    }

    private fun handleClip(conn: WebSocket, message: ClipMessage) {
        if (!peers.containsKey(conn)) {
            conn.close(4403, "Connect first")
            return
        }

        onRemoteClip(message.payload)
        relayClipboard(message.payload, exclude = conn)
    }

    private fun acceptPeer(conn: WebSocket, peer: PeerInfo, autoAccepted: Boolean = false) {
        peers[conn] = peer
        conn.send(
            json.encodeToString<WireMessage>(
                JoinResponseMessage(
                    accepted = true,
                    hostDeviceName = getHostDeviceName(),
                    hostDeviceId = getHostDeviceId(),
                    peers = participantWires(),
                ),
            ),
        )
        onPeersChanged(peers.values.toList())
        broadcastPeerList()
        onStatus(
            if (autoAccepted) {
                "Auto-accepted: ${peer.deviceName}"
            } else {
                "Connected: ${peer.deviceName}"
            },
        )
    }

    private fun broadcastPeerList() {
        val encoded = json.encodeToString<WireMessage>(PeerListMessage(participantWires()))
        peers.keys.forEach { socket ->
            if (socket.isOpen) {
                socket.send(encoded)
            }
        }
    }

    private fun participantWires(): List<PeerWire> {
        return buildList {
            add(PeerWire(deviceId = HOST_PEER_ID, deviceName = getHostDeviceName()))
            addAll(peers.values.map { PeerWire(it.deviceId, it.deviceName) })
        }
    }

    private companion object {
        private const val HOST_PEER_ID = "host"
    }
}
