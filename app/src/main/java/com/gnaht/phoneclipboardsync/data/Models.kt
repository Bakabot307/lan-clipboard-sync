package com.gnaht.phoneclipboardsync.data

import kotlinx.serialization.Serializable

enum class SessionRole {
    HOST,
    CLIENT,
}

data class SessionConfig(
    val deviceId: String,
    val deviceName: String,
    val role: SessionRole,
    val hostIp: String,
    val port: Int,
    val pairCode: String,
    val autoCopyEnabled: Boolean,
    val autoConnectEnabled: Boolean,
    val receivedNotificationsEnabled: Boolean,
    val returnAfterNotificationSend: Boolean,
)

data class PeerInfo(
    val deviceId: String,
    val deviceName: String,
)

data class DiscoveredLanItem(
    val deviceId: String,
    val deviceName: String,
    val hostIp: String,
    val port: Int,
    val roomCode: String,
    val hasOpenRoom: Boolean,
    val memberNames: List<String> = listOf(deviceName),
)

data class ConnectionRequest(
    val deviceId: String,
    val deviceName: String,
    val hostIp: String,
    val port: Int,
    val roomCode: String,
    val isInvitation: Boolean,
)

@Serializable
data class ClipPayload(
    val clipId: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val text: String,
    val timestamp: Long,
)

enum class HistoryDirection {
    SENT,
    RECEIVED,
}

data class HistoryEntry(
    val clipId: String,
    val sourceDeviceName: String,
    val text: String,
    val timestamp: Long,
    val direction: HistoryDirection,
)

data class SessionState(
    val isRunning: Boolean = false,
    val isDiscovering: Boolean = false,
    val role: SessionRole = SessionRole.HOST,
    val localAddress: String = "",
    val statusMessage: String = "Idle",
    val connectedPeers: List<PeerInfo> = emptyList(),
    val discoveredItems: List<DiscoveredLanItem> = emptyList(),
    val pendingRequests: List<ConnectionRequest> = emptyList(),
)
