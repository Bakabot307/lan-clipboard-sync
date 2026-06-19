package com.gnaht.phoneclipboardsync.network

import com.gnaht.phoneclipboardsync.data.ClipPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WireMessage

@Serializable
@SerialName("hello")
data class HelloMessage(
    val deviceId: String,
    val deviceName: String,
    val pairCode: String,
) : WireMessage

@Serializable
@SerialName("clip")
data class ClipMessage(
    val payload: ClipPayload,
) : WireMessage

@Serializable
@SerialName("join_response")
data class JoinResponseMessage(
    val accepted: Boolean,
    val reason: String = "",
    val hostDeviceName: String = "",
    val peers: List<PeerWire> = emptyList(),
) : WireMessage

@Serializable
@SerialName("peers")
data class PeerListMessage(
    val peers: List<PeerWire>,
) : WireMessage

@Serializable
data class PeerWire(
    val deviceId: String,
    val deviceName: String,
)

@Serializable
@SerialName("invite")
data class InviteMessage(
    val inviterDeviceId: String,
    val inviterDeviceName: String,
    val roomCode: String,
    val hostIp: String,
    val port: Int,
) : WireMessage

@Serializable
data class RoomDiscoveryMessage(
    val appId: String = APP_ID,
    val deviceId: String,
    val roomCode: String,
    val deviceName: String,
    val hostIp: String,
    val port: Int,
    val hasOpenRoom: Boolean,
    val memberNames: List<String> = emptyList(),
) {
    companion object {
        const val APP_ID = "com.gnaht.phoneclipboardsync.room"
    }
}
