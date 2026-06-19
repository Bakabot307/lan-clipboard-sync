package com.gnaht.phoneclipboardsync.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class AppPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val sharedPreferences: SharedPreferences =
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<SessionConfig> = _config

    fun updateDeviceName(value: String) = edit { putString(KEY_DEVICE_NAME, value.trim()) }

    fun updateRole(value: SessionRole) = edit { putString(KEY_ROLE, value.name) }

    fun updateHostIp(value: String) = edit { putString(KEY_HOST_IP, value.trim()) }

    fun updatePort(value: Int) = edit { putInt(KEY_PORT, value) }

    fun updatePairCode(value: String) = edit { putString(KEY_PAIR_CODE, value.trim()) }

    fun updateAutoCopyEnabled(value: Boolean) = edit { putBoolean(KEY_AUTO_COPY, value) }

    fun updateAutoConnectEnabled(value: Boolean) = edit { putBoolean(KEY_AUTO_CONNECT, value) }

    fun updateReceivedNotificationsEnabled(value: Boolean) = edit {
        putBoolean(KEY_RECEIVED_NOTIFICATIONS, value)
    }

    fun updateReturnAfterNotificationSendEnabled(value: Boolean) = edit {
        putBoolean(KEY_RETURN_AFTER_NOTIFICATION_SEND, value)
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sharedPreferences.edit().apply(block).apply()
        _config.value = loadConfig()
    }

    private fun loadConfig(): SessionConfig {
        val storedDeviceId = sharedPreferences.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also {
                sharedPreferences.edit().putString(KEY_DEVICE_ID, it).apply()
            }

        return SessionConfig(
            deviceId = storedDeviceId,
            deviceName = sharedPreferences.getString(KEY_DEVICE_NAME, null)
                ?: defaultDeviceName(),
            role = SessionRole.valueOf(
                sharedPreferences.getString(KEY_ROLE, SessionRole.HOST.name) ?: SessionRole.HOST.name,
            ),
            hostIp = sharedPreferences.getString(KEY_HOST_IP, "") ?: "",
            port = sharedPreferences.getInt(KEY_PORT, DEFAULT_PORT),
            pairCode = sharedPreferences.getString(KEY_PAIR_CODE, null)
                ?: generatePairCode().also { sharedPreferences.edit().putString(KEY_PAIR_CODE, it).apply() },
            autoCopyEnabled = sharedPreferences.getBoolean(KEY_AUTO_COPY, true),
            autoConnectEnabled = sharedPreferences.getBoolean(KEY_AUTO_CONNECT, false),
            receivedNotificationsEnabled = sharedPreferences.getBoolean(KEY_RECEIVED_NOTIFICATIONS, true),
            returnAfterNotificationSend = sharedPreferences.getBoolean(KEY_RETURN_AFTER_NOTIFICATION_SEND, false),
        )
    }

    private fun defaultDeviceName(): String {
        val androidDeviceName = Settings.Global.getString(
            appContext.contentResolver,
            "device_name",
        )
        if (!androidDeviceName.isNullOrBlank()) return androidDeviceName

        return listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Phone" }
    }

    private companion object {
        private const val PREF_NAME = "lan_clipboard_sync_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_ROLE = "role"
        private const val KEY_HOST_IP = "host_ip"
        private const val KEY_PORT = "port"
        private const val KEY_PAIR_CODE = "pair_code"
        private const val KEY_AUTO_COPY = "auto_copy"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_RECEIVED_NOTIFICATIONS = "received_notifications"
        private const val KEY_RETURN_AFTER_NOTIFICATION_SEND = "return_after_notification_send"

        private const val DEFAULT_PORT = 8787
        private fun generatePairCode(): String = (100000..999999).random().toString()
    }
}
