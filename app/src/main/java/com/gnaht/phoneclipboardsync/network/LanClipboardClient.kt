package com.gnaht.phoneclipboardsync.network

import com.gnaht.phoneclipboardsync.data.ClipPayload
import java.net.URI
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake

class LanClipboardClient(
    serverUri: URI,
    private val helloMessage: HelloMessage,
    private val json: Json,
    private val onStatus: (String) -> Unit,
    private val onAccepted: (String, String, List<PeerWire>) -> Unit,
    private val onRejected: (String) -> Unit,
    private val onPeerList: (List<PeerWire>) -> Unit,
    private val onRemoteClip: (ClipPayload) -> Unit,
    private val onRemoteBinaryClip: (BinaryClipMetadata, ByteArray) -> Unit,
    private val onClosed: (String) -> Unit,
) : WebSocketClient(serverUri) {
    private var accepted = false

    override fun onOpen(handshakedata: ServerHandshake?) {
        onStatus("Connecting...")
        send(json.encodeToString<WireMessage>(helloMessage))
        onStatus("Waiting for host approval")
    }

    override fun onMessage(message: String?) {
        if (message.isNullOrBlank()) return
        val decoded = runCatching { json.decodeFromString<WireMessage>(message) }.getOrNull() ?: return
        when (decoded) {
            is JoinResponseMessage -> {
                if (decoded.accepted) {
                    accepted = true
                    val hostName = decoded.hostDeviceName.ifBlank { "host" }
                    onStatus("Connected to $hostName")
                    onAccepted(hostName, decoded.hostDeviceId, decoded.peers)
                } else {
                    accepted = false
                    val reason = decoded.reason.ifBlank { "Connection rejected" }
                    onStatus(reason)
                    onRejected(reason)
                    close()
                }
            }

            is PeerListMessage -> onPeerList(decoded.peers)
            is ClipMessage -> onRemoteClip(decoded.payload)
            is HelloMessage, is InviteMessage -> Unit
        }
    }

    override fun onMessage(bytes: java.nio.ByteBuffer?) {
        if (bytes == null) return
        runCatching {
            val jsonLen = bytes.getInt()
            val jsonBytes = ByteArray(jsonLen)
            bytes.get(jsonBytes)
            val metadataJson = String(jsonBytes, Charsets.UTF_8)
            val metadata = json.decodeFromString<BinaryClipMetadata>(metadataJson)
            
            val fileBytes = ByteArray(bytes.remaining())
            bytes.get(fileBytes)
            
            onRemoteBinaryClip(metadata, fileBytes)
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        accepted = false
        val message = reason?.takeIf { it.isNotBlank() } ?: "Disconnected"
        onStatus(message)
        onClosed(message)
    }

    override fun onError(ex: Exception?) {
        onStatus(ex?.message ?: "Connection error")
    }

    fun sendClipboard(payload: ClipPayload): Boolean {
        if (!accepted || !isOpen) return false

        send(json.encodeToString<WireMessage>(ClipMessage(payload)))
        return true
    }

    fun sendBinaryClip(metadata: BinaryClipMetadata, fileBytes: ByteArray): Boolean {
        if (!accepted || !isOpen) return false

        runCatching {
            val metadataJson = json.encodeToString(metadata)
            val jsonBytes = metadataJson.toByteArray(Charsets.UTF_8)
            val buffer = java.nio.ByteBuffer.allocate(4 + jsonBytes.size + fileBytes.size).apply {
                putInt(jsonBytes.size)
                put(jsonBytes)
                put(fileBytes)
                flip()
            }
            send(buffer)
        }
        return true
    }
}
