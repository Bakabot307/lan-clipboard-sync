package com.gnaht.phoneclipboardsync.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gnaht.phoneclipboardsync.R
import com.gnaht.phoneclipboardsync.network.LanSessionManager
import com.gnaht.phoneclipboardsync.network.BinaryClipMetadata
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
    private val sessionManager = LanSessionManager(scope, applicationContext)
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val _monitorRunning = MutableStateFlow(false)

    private val remoteClipboardSuppressions = linkedMapOf<String, Long>()
    private var lastPublishedClipboardText: String? = null
    private var lastPublishedClipboardAtMs = 0L

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
                    suppressRemoteClipboardEcho(payload.text)
                    clipboardManager.setPrimaryClip(
                        ClipData.newPlainText("LAN clipboard", payload.text),
                    )
                }
            }
        }
        scope.launch {
            sessionManager.incomingBinaryClips.collectLatest { (metadata, fileBytes) ->
                if (metadata.sourceDeviceId == config.value.deviceId) {
                    return@collectLatest
                }

                if (!metadata.mimeType.startsWith("image/")) {
                    return@collectLatest
                }

                val displayName = metadata.fileName
                val displayText = "[Image] $displayName"

                addHistory(
                    HistoryEntry(
                        clipId = metadata.clipId,
                        sourceDeviceName = metadata.sourceDeviceName,
                        text = displayText,
                        timestamp = metadata.timestamp,
                        direction = HistoryDirection.RECEIVED,
                    ),
                )
                sessionManager.markRemoteClipReceived(metadata.sourceDeviceName)
                showReceivedBinaryNotification(metadata)

                if (config.value.autoCopyEnabled) {
                    runCatching {
                        val dir = java.io.File(applicationContext.cacheDir, "clipboard_sync").apply { mkdirs() }
                        dir.listFiles()?.forEach { it.delete() }
                        val file = java.io.File(dir, metadata.fileName)
                        file.writeBytes(fileBytes)

                        val fileUri = androidx.core.content.FileProvider.getUriForFile(
                            applicationContext,
                            "${applicationContext.packageName}.provider",
                            file
                        )

                        val clip = ClipData.newUri(applicationContext.contentResolver, metadata.fileName, fileUri)
                        clipboardManager.setPrimaryClip(clip)
                    }
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

    fun updateReturnAfterNotificationSendEnabled(value: Boolean) =
        preferences.updateReturnAfterNotificationSendEnabled(value)

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

    fun prepareForUse() {
        sessionManager.updateLocalConfig(config.value.copy(role = SessionRole.HOST))
    }

    fun shutdownForTaskRemoval() {
        sessionManager.shutdown()
        preferences.updateRole(SessionRole.HOST)
        _monitorRunning.value = false
    }

    fun setMonitorRunning(value: Boolean) {
        _monitorRunning.value = value
    }

    fun onClipboardChanged(text: String) {
        onClipboardChanged()
    }

    fun onClipboardChanged() {
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0) return
        val item = clipData.getItemAt(0)
        
        val uri = item.uri
        if (uri != null) {
            if (uri.authority == "${applicationContext.packageName}.provider") {
                return
            }
            
            val mimeType = applicationContext.contentResolver.getType(uri) ?: "application/octet-stream"
            if (!mimeType.startsWith("image/")) {
                return
            }
            var fileName = "file"
            
            val cursor = applicationContext.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = it.getString(nameIndex)
                    }
                }
            }
            if (fileName == "file" && uri.scheme == "file") {
                fileName = uri.lastPathSegment ?: "file"
            }
            if (!fileName.contains(".") && mimeType.startsWith("image/")) {
                val ext = mimeType.substringAfter("/", "png").takeUnless { it == "*" }.orEmpty().ifBlank { "png" }
                fileName = "$fileName.$ext"
            }

            val fileSize = runCatching {
                applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            if (fileSize > 35 * 1024 * 1024) {
                return
            }

            val bytes = runCatching {
                applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return
            
            val metadata = BinaryClipMetadata(
                clipId = UUID.randomUUID().toString(),
                sourceDeviceId = config.value.deviceId,
                sourceDeviceName = config.value.deviceName,
                fileName = fileName,
                mimeType = mimeType,
                timestamp = System.currentTimeMillis()
            )
            
            if (!sessionManager.publishLocalBinaryClip(metadata, bytes)) return
            
            addHistory(
                HistoryEntry(
                    clipId = metadata.clipId,
                    sourceDeviceName = metadata.sourceDeviceName,
                    text = "[Image] $fileName",
                    timestamp = metadata.timestamp,
                    direction = HistoryDirection.SENT,
                )
            )
        } else {
            val text = item.coerceToText(applicationContext)?.toString().orEmpty()
            if (text.isBlank()) return

            val now = System.currentTimeMillis()
            if (shouldSuppressRemoteClipboardEcho(text, now)) {
                return
            }

            if (isDuplicateOutgoingClipboard(text, now)) {
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
            lastPublishedClipboardText = text
            lastPublishedClipboardAtMs = now

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
    }

    fun suppressClipboardSync(text: String) {
        suppressRemoteClipboardEcho(text)
    }

    fun sendSharedIntent(intent: Intent): Boolean {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val streamUri = intent.getStreamUri()
                if (streamUri != null) {
                    sendUriDirectly(streamUri, intent.type)
                } else {
                    sendTextDirectly(intent.getSharedText().orEmpty())
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streamUris = intent.getStreamUris()
                if (streamUris.isNotEmpty()) {
                    streamUris.map { uri -> sendUriDirectly(uri, intent.type) }.any { it }
                } else {
                    sendTextDirectly(intent.getSharedText().orEmpty())
                }
            }
            else -> false
        }
    }

    fun sendTextDirectly(text: String): Boolean {
        if (text.isBlank()) return false

        val payload = ClipPayload(
            clipId = UUID.randomUUID().toString(),
            sourceDeviceId = config.value.deviceId,
            sourceDeviceName = config.value.deviceName,
            text = text,
            timestamp = System.currentTimeMillis(),
        )

        if (!sessionManager.publishLocalClip(payload)) return false
        lastPublishedClipboardText = text
        lastPublishedClipboardAtMs = System.currentTimeMillis()

        addHistory(
            HistoryEntry(
                clipId = payload.clipId,
                sourceDeviceName = payload.sourceDeviceName,
                text = payload.text,
                timestamp = payload.timestamp,
                direction = HistoryDirection.SENT,
            ),
        )
        return true
    }

    private fun sendUriDirectly(uri: Uri, sharedMimeType: String?): Boolean {
        if (uri.authority == "${applicationContext.packageName}.provider") {
            return false
        }

        val mimeType = sharedMimeType
            ?.takeUnless { it.isBlank() || it == "*/*" }
            ?: applicationContext.contentResolver.getType(uri)
            ?: "application/octet-stream"
        if (!mimeType.startsWith("image/")) {
            return false
        }
        val fileName = resolveFileName(uri, mimeType)

        val fileSize = runCatching {
            applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        if (fileSize > 35 * 1024 * 1024) {
            return false
        }

        val bytes = runCatching {
            applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return false

        val metadata = BinaryClipMetadata(
            clipId = UUID.randomUUID().toString(),
            sourceDeviceId = config.value.deviceId,
            sourceDeviceName = config.value.deviceName,
            fileName = fileName,
            mimeType = mimeType,
            timestamp = System.currentTimeMillis(),
        )

        if (!sessionManager.publishLocalBinaryClip(metadata, bytes)) return false

        addHistory(
            HistoryEntry(
                clipId = metadata.clipId,
                sourceDeviceName = metadata.sourceDeviceName,
                text = "[Image] $fileName",
                timestamp = metadata.timestamp,
                direction = HistoryDirection.SENT,
            ),
        )
        return true
    }

    private fun resolveFileName(uri: Uri, mimeType: String): String {
        var fileName = "file"

        val cursor = applicationContext.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = it.getString(nameIndex)
                }
            }
        }
        if (fileName == "file" && uri.scheme == "file") {
            fileName = uri.lastPathSegment ?: "file"
        }
        if (!fileName.contains(".") && mimeType.startsWith("image/")) {
            val ext = mimeType.substringAfter("/", "png").takeUnless { it == "*" }.orEmpty().ifBlank { "png" }
            fileName = "$fileName.$ext"
        }
        return fileName
    }

    private fun Intent.getSharedText(): String? {
        return getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: getStringExtra(Intent.EXTRA_HTML_TEXT)
            ?: getStringExtra(Intent.EXTRA_SUBJECT)
    }

    private fun Intent.getStreamUri(): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun Intent.getStreamUris(): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
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

    private fun suppressRemoteClipboardEcho(text: String) {
        val now = System.currentTimeMillis()
        pruneRemoteClipboardSuppressions(now)
        remoteClipboardSuppressions[text] = now + REMOTE_CLIPBOARD_SUPPRESSION_MS
    }

    private fun shouldSuppressRemoteClipboardEcho(text: String, now: Long): Boolean {
        pruneRemoteClipboardSuppressions(now)
        return remoteClipboardSuppressions[text]?.let { expiresAt -> expiresAt > now } == true
    }

    private fun pruneRemoteClipboardSuppressions(now: Long) {
        val iterator = remoteClipboardSuppressions.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) {
                iterator.remove()
            }
        }

        while (remoteClipboardSuppressions.size > MAX_REMOTE_CLIPBOARD_SUPPRESSIONS) {
            val firstKey = remoteClipboardSuppressions.keys.firstOrNull() ?: break
            remoteClipboardSuppressions.remove(firstKey)
        }
    }

    private fun isDuplicateOutgoingClipboard(text: String, now: Long): Boolean {
        return lastPublishedClipboardText == text &&
            now - lastPublishedClipboardAtMs < DUPLICATE_OUTGOING_CLIPBOARD_MS
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

    private fun showReceivedBinaryNotification(metadata: BinaryClipMetadata) {
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

        val notification = NotificationCompat.Builder(applicationContext, RECEIVED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clipboard)
            .setContentTitle(applicationContext.getString(R.string.received_notification_title))
            .setContentText("${metadata.sourceDeviceName}: Received Image - ${metadata.fileName}")
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
        private const val REMOTE_CLIPBOARD_SUPPRESSION_MS = 15_000L
        private const val MAX_REMOTE_CLIPBOARD_SUPPRESSIONS = 20
        private const val DUPLICATE_OUTGOING_CLIPBOARD_MS = 1_000L
    }
}

