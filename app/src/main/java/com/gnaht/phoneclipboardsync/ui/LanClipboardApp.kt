package com.gnaht.phoneclipboardsync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gnaht.phoneclipboardsync.R
import com.gnaht.phoneclipboardsync.data.ClipboardSyncController
import com.gnaht.phoneclipboardsync.data.ConnectionRequest
import com.gnaht.phoneclipboardsync.data.DiscoveredLanItem
import com.gnaht.phoneclipboardsync.data.HistoryDirection
import com.gnaht.phoneclipboardsync.data.HistoryEntry
import com.gnaht.phoneclipboardsync.data.SessionConfig
import com.gnaht.phoneclipboardsync.data.SessionRole
import com.gnaht.phoneclipboardsync.data.SessionState
import com.gnaht.phoneclipboardsync.ui.theme.StatusAmber
import com.gnaht.phoneclipboardsync.ui.theme.StatusAmberDark
import com.gnaht.phoneclipboardsync.ui.theme.StatusAmberDarkContainer
import com.gnaht.phoneclipboardsync.ui.theme.StatusAmberLight
import com.gnaht.phoneclipboardsync.ui.theme.StatusGreen
import com.gnaht.phoneclipboardsync.ui.theme.StatusGreenDark
import com.gnaht.phoneclipboardsync.ui.theme.StatusGreenDarkContainer
import com.gnaht.phoneclipboardsync.ui.theme.StatusGreenLight
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================
 *  Root composable
 * ============================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanClipboardApp(
    controller: ClipboardSyncController,
    onStartMonitor: () -> Unit,
    onStopMonitor: () -> Unit,
) {
    val config by controller.config.collectAsStateWithLifecycle()
    val sessionState by controller.sessionState.collectAsStateWithLifecycle()
    val history by controller.history.collectAsStateWithLifecycle()
    val localIp by controller.localIpAddress.collectAsStateWithLifecycle()
    val monitorRunning by controller.monitorRunning.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    val copiedMessage = stringResource(R.string.history_copied_snackbar)
    val sentMessage = "Clipboard sent."
    val emptyClipboardMessage = "Clipboard is empty."
    val latestHistory = history.firstOrNull()
    val receivedMessage = latestHistory?.let { "Received clipboard from ${it.sourceDeviceName}" }

    LaunchedEffect(latestHistory?.clipId) {
        if (latestHistory?.direction == HistoryDirection.RECEIVED && receivedMessage != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = receivedMessage,
                duration = SnackbarDuration.Short,
            )
        }
    }

    // Auto-start clipboard monitor when in an active session
    val isInActiveSession = sessionState.isRunning && sessionState.connectedPeers.isNotEmpty()
    LaunchedEffect(isInActiveSession) {
        if (isInActiveSession && !monitorRunning) {
            onStartMonitor()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.top_bar_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (localIp.isNotBlank()) {
                            Text(
                                localIp,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            /* — 1. Connection status banner — */
            ConnectionStatusBanner(
                config = config,
                sessionState = sessionState,
                localIp = localIp,
            )

            /* — 2. Monitor toggle (prominent) — */
            MonitorToggleCard(
                monitorRunning = monitorRunning,
                onStart = onStartMonitor,
                onStop = onStopMonitor,
            )

            /* — 3. Pending join requests — */
            AnimatedVisibility(
                visible = sessionState.pendingRequests.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PendingRequestsCard(
                    requests = sessionState.pendingRequests,
                    onAccept = controller::acceptRequest,
                    onReject = controller::rejectRequest,
                )
            }

            /* — 4. Room lobby — */
            val isInSession = sessionState.isRunning && sessionState.connectedPeers.isNotEmpty()

            RoomSearchCard(
                config = config,
                localIp = localIp,
                sessionState = sessionState,
                onScanLan = controller::scanLan,
                onJoinRoom = controller::connectToItem,
                onLeaveRoom = controller::stopSession,
                onSendClipboard = {
                    val text = clipboardManager.getText()?.text.orEmpty()
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        if (text.isBlank()) {
                            snackbarHostState.showSnackbar(
                                message = emptyClipboardMessage,
                                duration = SnackbarDuration.Short,
                            )
                        } else {
                            controller.onClipboardChanged(text)
                            snackbarHostState.showSnackbar(
                                message = sentMessage,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                },
            )

            /* — 5. Leave/close button — */
            AnimatedVisibility(
                visible = isInSession,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                LeaveRoomCard(
                    role = sessionState.role,
                    onLeave = controller::stopSession,
                )
            }
            /* — 6. Clipboard history — */
            HistoryCard(
                history = history,
                onCopyAgain = { text ->
                    clipboardManager.setText(AnnotatedString(text))
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            message = copiedMessage,
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            config = config,
            monitorRunning = monitorRunning,
            onDismiss = { settingsOpen = false },
            onDeviceNameChanged = controller::updateDeviceName,
            onAutoConnectChanged = controller::updateAutoConnectEnabled,
            onAutoCopyChanged = controller::updateAutoCopyEnabled,
            onReceivedNotificationsChanged = controller::updateReceivedNotificationsEnabled,
            onStartMonitor = onStartMonitor,
            onStopMonitor = onStopMonitor,
        )
    }
}

/* ============================================================
 *  Connection status banner
 * ============================================================ */

@Composable
private fun ConnectionStatusBanner(
    config: SessionConfig,
    sessionState: SessionState,
    localIp: String,
) {
    val isDark = isSystemInDarkTheme()

    val (containerColor, contentColor, dotColor) = when {
        sessionState.isRunning && sessionState.role == SessionRole.HOST -> Triple(
            if (isDark) StatusGreenDarkContainer else StatusGreenLight,
            if (isDark) StatusGreenDark else StatusGreen,
            if (isDark) StatusGreenDark else StatusGreen,
        )
        sessionState.isRunning && sessionState.role == SessionRole.CLIENT -> Triple(
            if (isDark) StatusGreenDarkContainer else StatusGreenLight,
            if (isDark) StatusGreenDark else StatusGreen,
            if (isDark) StatusGreenDark else StatusGreen,
        )
        sessionState.isDiscovering -> Triple(
            if (isDark) StatusAmberDarkContainer else StatusAmberLight,
            if (isDark) StatusAmberDark else StatusAmber,
            if (isDark) StatusAmberDark else StatusAmber,
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.outline,
        )
    }

    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(400),
        label = "bannerBg",
    )

    ElevatedCard(
        modifier = Modifier.animateContentSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = animatedContainerColor),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(color = dotColor, animate = sessionState.isRunning)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when {
                        sessionState.role == SessionRole.CLIENT && sessionState.isRunning ->
                            "Sharing clipboard"
                        sessionState.role == SessionRole.HOST && sessionState.isRunning ->
                            "Sharing clipboard"
                        sessionState.isDiscovering ->
                            stringResource(R.string.room_search_scanning_text)
                        else -> "Ready to connect"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
            }

            Text(
                text = when {
                    sessionState.connectedPeers.isNotEmpty() -> {
                        val names = sessionState.connectedPeers.joinToString(", ") { it.deviceName }
                        "Sharing with $names"
                    }
                    sessionState.isRunning && sessionState.role == SessionRole.CLIENT ->
                        sessionState.statusMessage
                    sessionState.isRunning && sessionState.role == SessionRole.HOST ->
                        sessionState.statusMessage
                    else -> sessionState.statusMessage
                },
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = .75f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoChip(
                    icon = Icons.Default.Devices,
                    text = config.deviceName,
                    tint = contentColor,
                )
                if (localIp.isNotBlank()) {
                    InfoChip(
                        icon = Icons.Default.Wifi,
                        text = localIp,
                        tint = contentColor,
                    )
                }
                InfoChip(
                    icon = Icons.Default.Sync,
                    text = "${sessionState.connectedPeers.size}",
                    tint = contentColor,
                )
            }
        }
    }
}

@Composable
private fun PulseDot(color: Color, animate: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(if (animate) color else color.copy(alpha = .55f)),
    )
}

@Composable
private fun InfoChip(icon: ImageVector, text: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = tint.copy(alpha = .12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = tint,
            )
        }
    }
}

/* ============================================================
 *  Monitor toggle card
 * ============================================================ */

@Composable
private fun MonitorToggleCard(
    monitorRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (monitorRunning) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val animatedCardColor by animateColorAsState(
        targetValue = cardColor,
        animationSpec = tween(400),
        label = "monitorBg",
    )

    ElevatedCard(
        modifier = Modifier.animateContentSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = animatedCardColor),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (monitorRunning) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Column {
                    Text(
                        stringResource(R.string.monitor_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (monitorRunning) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        if (monitorRunning) {
                            stringResource(R.string.monitor_on_description)
                        } else {
                            stringResource(R.string.monitor_off_description)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (monitorRunning) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Switch(
                checked = monitorRunning,
                onCheckedChange = { if (it) onStart() else onStop() },
            )
        }
    }
}

/* ============================================================
 *  Room lobby card
 * ============================================================ */

@Composable
private fun RoomSearchCard(
    config: SessionConfig,
    localIp: String,
    sessionState: SessionState,
    onScanLan: () -> Unit,
    onJoinRoom: (DiscoveredLanItem) -> Unit,
    onLeaveRoom: () -> Unit,
    onSendClipboard: () -> Unit,
) {
    val items = sessionState.discoveredItems.filter { it.hasOpenRoom }
    val isSharing = sessionState.isRunning && sessionState.connectedPeers.isNotEmpty()
    val isWaitingForApproval = sessionState.role == SessionRole.CLIENT && !sessionState.isRunning
    val sharingNames = sessionState.connectedPeers.joinToString(", ") { it.deviceName }
    val status = when {
        isSharing -> "Sharing clipboard with $sharingNames"
        isWaitingForApproval -> sessionState.statusMessage
        sessionState.isDiscovering -> "Scanning for LAN devices"
        else -> "Choose a device or group to start sharing"
    }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Radar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Clipboard sharing",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    PulseDot(
                        color = MaterialTheme.colorScheme.primary,
                        animate = sessionState.isRunning || sessionState.isDiscovering,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (isSharing) "Sharing with" else "This device",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                PlayerSlot(
                    name = config.deviceName,
                    subtitle = if (isSharing) "You" else "Ready to connect",
                    active = true,
                    highlight = true,
                )

                sessionState.connectedPeers.forEach { peer ->
                    PlayerSlot(
                        name = peer.deviceName,
                        subtitle = "Sharing",
                        active = true,
                    )
                }
            }

            if (!isSharing) {
                Button(
                    onClick = onScanLan,
                    enabled = !sessionState.isDiscovering,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    if (sessionState.isDiscovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.room_search_btn_scanning))
                    } else {
                        Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Find devices")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    if (items.isEmpty()) "Nearby devices" else "Nearby devices (${items.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                if (items.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { item ->
                            RoomRow(room = item, onJoin = { onJoinRoom(item) })
                        }
                    }
                } else {
                    EmptyState(
                        icon = Icons.Default.Router,
                        text = if (sessionState.isDiscovering) {
                            stringResource(R.string.room_search_scanning_text)
                        } else {
                            "Tap Find devices while the other devices are open on the same Wi-Fi."
                        },
                    )
                }
            } else {
                Button(
                    onClick = onSendClipboard,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send current clipboard")
                }

                Text(
                    "Clipboard text will be shared with connected devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlayerSlot(
    name: String,
    subtitle: String,
    active: Boolean,
    highlight: Boolean = false,
) {
    val containerColor = if (highlight) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (highlight) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = .68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (active) StatusGreen else MaterialTheme.colorScheme.outline),
            )
        }
    }
}

@Composable
private fun RoomRow(
    room: DiscoveredLanItem,
    onJoin: () -> Unit,
) {
    val memberNames = room.memberNames.ifEmpty { listOf(room.deviceName) }
    val isGroup = memberNames.size > 1
    val title = if (isGroup) {
        "Group ${memberNames.joinToString(", ")}"
    } else {
        memberNames.first()
    }
    val subtitle = if (isGroup) {
        "Sharing: ${memberNames.joinToString(", ")}"
    } else {
        "Device ready to connect"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
                ) {
                    Text(
                    text = title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FilledTonalButton(
                onClick = onJoin,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Connect")
            }
        }
    }
}
/* ============================================================
 *  Pending requests card
 * ============================================================ */

@Composable
private fun PendingRequestsCard(
    requests: List<ConnectionRequest>,
    onAccept: (ConnectionRequest) -> Unit,
    onReject: (ConnectionRequest) -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    stringResource(R.string.pending_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                /* Badge */
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                ) {
                    Text(
                        text = "${requests.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }

            requests.forEach { request ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .7f),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        /* Avatar */
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = request.deviceName.take(1).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onTertiary,
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                request.deviceName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (request.isInvitation) {
                                    stringResource(R.string.pending_invitation)
                                } else {
                                    stringResource(R.string.pending_subtitle)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IconButton(
                            onClick = { onReject(request) },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.pending_reject))
                        }

                        IconButton(
                            onClick = { onAccept(request) },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.pending_accept))
                        }
                    }
                }
            }
        }
    }
}

/* ============================================================
 *  Connected devices card
 * ============================================================ */

@Composable
private fun ConnectedDevicesCard(sessionState: SessionState) {
    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.Devices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    stringResource(R.string.peers_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            sessionState.connectedPeers.forEach { peer ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        /* Avatar */
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = peer.deviceName.take(1).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondary,
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                peer.deviceName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.peers_online),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        /* Online dot */
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(StatusGreen),
                        )
                    }
                }
            }
        }
    }
}

/* ============================================================
 *  Leave / close room card
 * ============================================================ */

@Composable
private fun LeaveRoomCard(
    role: SessionRole,
    onLeave: () -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Disconnect",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Stop sharing clipboard with the current devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = onLeave,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Disconnect")
            }
        }
    }
}

/* ============================================================
 *  Clipboard history card
 * ============================================================ */

@Composable
private fun HistoryCard(
    history: List<HistoryEntry>,
    onCopyAgain: (String) -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .animateContentSize(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (history.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.ContentCopy,
                    text = stringResource(R.string.history_empty),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(history, key = { it.clipId }) { item ->
                        HistoryRow(item = item, onCopyAgain = onCopyAgain)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: HistoryEntry,
    onCopyAgain: (String) -> Unit,
) {
    val isSent = item.direction == HistoryDirection.SENT
    val directionColor = if (isSent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (isSent) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = directionColor,
                    )
                    Text(
                        text = if (isSent) {
                            stringResource(R.string.history_sent)
                        } else {
                            stringResource(R.string.history_received)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = directionColor,
                    )
                    Text(
                        text = "• ${item.sourceDeviceName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatTime(item.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }

            Text(
                text = item.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(
                    onClick = { onCopyAgain(item.text) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.history_copy_btn),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/* ============================================================
 *  Empty state helper
 * ============================================================ */

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/* ============================================================
 *  Settings dialog
 * ============================================================ */

@Composable
private fun SettingsDialog(
    config: SessionConfig,
    monitorRunning: Boolean,
    onDismiss: () -> Unit,
    onDeviceNameChanged: (String) -> Unit,
    onAutoConnectChanged: (Boolean) -> Unit,
    onAutoCopyChanged: (Boolean) -> Unit,
    onReceivedNotificationsChanged: (Boolean) -> Unit,
    onStartMonitor: () -> Unit,
    onStopMonitor: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = config.deviceName,
                    onValueChange = onDeviceNameChanged,
                    label = { Text(stringResource(R.string.settings_device_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                SettingSwitchRow(
                    title = stringResource(R.string.settings_auto_connect_title),
                    description = stringResource(R.string.settings_auto_connect_desc),
                    checked = config.autoConnectEnabled,
                    onCheckedChange = onAutoConnectChanged,
                )

                SettingSwitchRow(
                    title = stringResource(R.string.settings_auto_copy_title),
                    description = stringResource(R.string.settings_auto_copy_desc),
                    checked = config.autoCopyEnabled,
                    onCheckedChange = onAutoCopyChanged,
                )

                SettingSwitchRow(
                    title = stringResource(R.string.settings_received_notifications_title),
                    description = stringResource(R.string.settings_received_notifications_desc),
                    checked = config.receivedNotificationsEnabled,
                    onCheckedChange = onReceivedNotificationsChanged,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_monitor_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (monitorRunning) {
                                stringResource(R.string.settings_monitor_on)
                            } else {
                                stringResource(R.string.settings_monitor_off)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (monitorRunning) {
                        OutlinedButton(
                            onClick = onStopMonitor,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(stringResource(R.string.settings_monitor_stop))
                        }
                    } else {
                        Button(
                            onClick = onStartMonitor,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(stringResource(R.string.settings_monitor_start))
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(stringResource(R.string.settings_done))
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/* ============================================================
 *  Helpers
 * ============================================================ */

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}



