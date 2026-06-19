package com.gnaht.phoneclipboardsync.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gnaht.phoneclipboardsync.R
import com.gnaht.phoneclipboardsync.network.LanSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ClipboardSyncController(
    appContext: Context,
    private val preferences: AppPreferences,
) {
    private val applicationContext = appContext.applicationContext
    private val clipboardManager =
        applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionManager = LanSessionManager(scope)
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val _monitorRunning = MutableStateFlow(false)

    private var ignoredClipboardText: String? = null

    val config: StateFlow<SessionConfig> = preferences.config
    val sessionState: StateFlow<SessionState> = sessionManager.sessionState
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()
    val monitorRunning: StateFlow<Boolean> = _monitorRunning.asStateFlow()
    val localIpAddress: StateFlow<String> = sessionManager.localIpAddress.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = "",
    )

    init {
        createReceivedNotificationChannel()
        preferences.updateRole(SessionRole.HOST)
        sessionManager.updateLocalConfig(config.value.copy(role = SessionRole.HOST))
        scope.launch {
            config.collectLatest { currentConfig ->
                sessionManager.updateLocalConfig(currentConfig.copy(role = SessionRole.HOST))
            }
        }
        scope.launch {
            sessionManager.incomingClips.collectLatest { payload ->
                if (payload.sourceDeviceId == config.value.deviceId) {
                    return@collectLatest
                }

                addHistory(
                    HistoryEntry(
                        clipId = payload.clipId,
                        sourceDeviceName = payload.sourceDeviceName,
                        text = payload.text,
                        timestamp = payload.timestamp,
                        direction = HistoryDirection.RECEIVED,
                    ),
                )
                sessionManager.markRemoteClipReceived(payload.sourceDeviceName)
                showReceivedNotification(payload)

                if (config.value.autoCopyEnabled) {
                    ignoredClipboardText = payload.text
                    clipboardManager.setPrimaryClip(
                        ClipData.newPlainText("LAN clipboard", payload.text),
                    )
                }
            }
        }
    }

    fun updateDeviceName(value: String) = preferences.updateDeviceName(value)

    fun updateRole(value: SessionRole) = preferences.updateRole(value)

    fun updateHostIp(value: String) = preferences.updateHostIp(value)

    fun updatePort(value: Int) = preferences.updatePort(value)

    fun updatePairCode(value: String) = preferences.updatePairCode(value.filter { it.isDigit() }.take(6))

    fun updateAutoCopyEnabled(value: Boolean) = preferences.updateAutoCopyEnabled(value)

    fun updateAutoConnectEnabled(value: Boolean) = preferences.updateAutoConnectEnabled(value)

    fun updateReceivedNotificationsEnabled(value: Boolean) = preferences.updateReceivedNotificationsEnabled(value)

    fun refreshRoomCode() {
        preferences.updatePairCode(generateRoomCode())
    }

    fun createRoom() {
        sessionManager.updateLocalConfig(config.value.copy(role = SessionRole.HOST))
    }

    fun joinRoom() {
        preferences.updateRole(SessionRole.CLIENT)
        scanLan()
    }

    fun scanLan() {
        sessionManager.scanLan(config.value)
    }

    fun connectToItem(item: DiscoveredLanItem) {
        if (!item.hasOpenRoom) return

        val currentConfig = config.value
        val clientConfig = currentConfig.copy(
            role = SessionRole.CLIENT,
            hostIp = item.hostIp,
            port = item.port,
            pairCode = item.roomCode,
        )
        preferences.updateHostIp(item.hostIp)
        preferences.updatePort(item.port)
        sessionManager.connectToItem(clientConfig, item)
    }

    fun acceptRequest(request: ConnectionRequest) {
        if (request.isInvitation) {
            val clientConfig = config.value.copy(
                role = SessionRole.CLIENT,
                hostIp = request.hostIp,
                port = request.port,
                pairCode = request.roomCode,
            )
            preferences.updateRole(SessionRole.CLIENT)
            preferences.updateHostIp(request.hostIp)
            preferences.updatePort(request.port)
            preferences.updatePairCode(request.roomCode)
            sessionManager.updateLocalConfig(clientConfig)
        }

        sessionManager.acceptRequest(request)
    }

    fun rejectRequest(request: ConnectionRequest) {
        sessionManager.rejectRequest(request)
    }

    fun startSession() {
        sessionManager.start(config.value)
    }

    fun stopSession() {
        sessionManager.stop()
        preferences.updateRole(SessionRole.HOST)
        sessionManager.updateLocalConfig(config.value.copy(role = SessionRole.HOST))
    }

    fun setMonitorRunning(value: Boolean) {
        _monitorRunning.value = value
    }

    fun onClipboardChanged(text: String) {
        if (text.isBlank()) return

        if (ignoredClipboardText == text) {
            ignoredClipboardText = null
            return
        }

        val payload = ClipPayload(
            clipId = UUID.randomUUID().toString(),
            sourceDeviceId = config.value.deviceId,
            sourceDeviceName = config.value.deviceName,
            text = text,
            timestamp = System.currentTimeMillis(),
        )

        if (!sessionManager.publishLocalClip(payload)) return

        addHistory(
            HistoryEntry(
                clipId = payload.clipId,
                sourceDeviceName = payload.sourceDeviceName,
                text = payload.text,
                timestamp = payload.timestamp,
                direction = HistoryDirection.SENT,
            ),
        )
    }

    private fun addHistory(entry: HistoryEntry) {
        val updated = buildList {
            add(entry)
            addAll(
                _history.value.filterNot { it.clipId == entry.clipId }.take(MAX_HISTORY - 1),
            )
        }
        _history.value = updated
    }

    private fun createReceivedNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            RECEIVED_CHANNEL_ID,
            applicationContext.getString(R.string.received_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    private fun showReceivedNotification(payload: ClipPayload) {
        if (!config.value.receivedNotificationsEnabled) {
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val preview = payload.text.replace('\n', ' ').take(NOTIFICATION_PREVIEW_LENGTH)
        val notification = NotificationCompat.Builder(applicationContext, RECEIVED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clipboard)
            .setContentTitle(applicationContext.getString(R.string.received_notification_title))
            .setContentText("${payload.sourceDeviceName}: $preview")
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.text.take(NOTIFICATION_BIG_TEXT_LENGTH)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            notification,
        )
    }

    private fun generateRoomCode(): String = (100000..999999).random().toString()

    private companion object {
        private const val MAX_HISTORY = 10
        private const val RECEIVED_CHANNEL_ID = "clipboard_received"
        private const val NOTIFICATION_PREVIEW_LENGTH = 80
        private const val NOTIFICATION_BIG_TEXT_LENGTH = 240
    }
}

