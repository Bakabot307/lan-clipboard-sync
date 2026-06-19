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

    private val controller by lazy { (application as LanClipboardApplication).controller }
    private val clipboardManager by lazy {
        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!controller.monitorRunning.value) return@OnPrimaryClipChangedListener

        val currentText = clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()

        controller.onClipboardChanged(currentText)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            sendPendingClipboardIfNeeded()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_SEND_CURRENT_CLIPBOARD) {
            sendClipboardWhenResumed = true
            intent.action = null
        }
    }

    private fun sendPendingClipboardIfNeeded() {
        if (!sendClipboardWhenResumed) return
        sendClipboardWhenResumed = false

        val currentText = clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()

        controller.onClipboardChanged(currentText)
    }

    companion object {
        const val ACTION_SEND_CURRENT_CLIPBOARD = "com.gnaht.phoneclipboardsync.SEND_CURRENT_CLIPBOARD"
    }
}
