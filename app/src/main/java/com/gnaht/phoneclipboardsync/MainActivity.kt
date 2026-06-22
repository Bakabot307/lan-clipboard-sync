package com.gnaht.phoneclipboardsync

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.gnaht.phoneclipboardsync.clipboard.ClipboardMonitorService
import com.gnaht.phoneclipboardsync.ui.LanClipboardApp
import com.gnaht.phoneclipboardsync.ui.theme.LanClipboardTheme

class MainActivity : ComponentActivity() {
    private var sendClipboardWhenResumed = false
    private var returnAfterNotificationSend = false
    private var pendingShareIntent: Intent? = null

    private val controller by lazy { (application as LanClipboardApplication).controller }
    private val clipboardManager by lazy {
        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!controller.monitorRunning.value) return@OnPrimaryClipChangedListener

        controller.onClipboardChanged()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller.prepareForUse()
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            LanClipboardTheme {
                LanClipboardApp(
                    controller = controller,
                    onStartMonitor = {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, ClipboardMonitorService::class.java),
                        )
                    },
                    onStopMonitor = {
                        stopService(Intent(this, ClipboardMonitorService::class.java))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        }
        window.decorView.post {
            sendPendingShareIfNeeded()
            sendPendingClipboardIfNeeded()
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        window.decorView.post {
            sendPendingShareIfNeeded()
            sendPendingClipboardIfNeeded()
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SEND_CURRENT_CLIPBOARD -> {
                sendClipboardWhenResumed = true
                returnAfterNotificationSend = controller.config.value.returnAfterNotificationSend
                intent.action = null
            }
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE -> {
                pendingShareIntent = Intent(intent)
                intent.action = null
            }
        }
    }

    private fun sendPendingShareIfNeeded() {
        val shareIntent = pendingShareIntent ?: return
        pendingShareIntent = null

        if (controller.sendSharedIntent(shareIntent)) {
            window.decorView.post { finish() }
        }
    }

    private fun sendPendingClipboardIfNeeded() {
        if (!sendClipboardWhenResumed) return
        sendClipboardWhenResumed = false

        controller.onClipboardChanged()
        if (returnAfterNotificationSend) {
            returnAfterNotificationSend = false
            window.decorView.post { finish() }
        }
    }

    companion object {
        const val ACTION_SEND_CURRENT_CLIPBOARD = "com.gnaht.phoneclipboardsync.SEND_CURRENT_CLIPBOARD"
    }
}
