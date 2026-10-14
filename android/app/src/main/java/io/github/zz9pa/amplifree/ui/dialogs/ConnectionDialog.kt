package io.github.zz9pa.amplifree.ui.dialogs

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.zz9pa.amplifree.R
import io.github.zz9pa.amplifree.data.ConnectionState
import io.github.zz9pa.amplifree.data.HardwareManager
import io.github.zz9pa.amplifree.data.HardwareState
import io.github.zz9pa.amplifree.ui.friendlyStatus
import io.github.zz9pa.amplifree.ui.components.AmpButton
import io.github.zz9pa.amplifree.ui.components.AmpOutlinedButton
import io.github.zz9pa.amplifree.ui.components.MinTouchTarget
import io.github.zz9pa.amplifree.ui.theme.*

/**
 * Pick which paired amp to connect to, see what the link is doing, and disconnect.
 * Tones are browsed on their own screen — this dialog is only about the connection.
 */
@Composable
fun ConnectionDialog(
    hardwareState: HardwareState,
    hardwareManager: HardwareManager,
    onDismiss: () -> Unit
) {
    val rawStatus by hardwareManager.status.collectAsState()
    val ready by hardwareManager.ready.collectAsState()
    val devices = hardwareManager.availableDevices
    val needsLink by hardwareManager.needsLink.collectAsState()
    val context = LocalContext.current
    val status = friendlyStatus(rawStatus, context.resources)
    val connected = hardwareState.connectionState == ConnectionState.CONNECTED

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AmpSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, AmpGold),
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("connection_dialog")
        ) {
            // Scrolls as a whole so nothing is cut off with a large font or in landscape.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleMedium,
                    color = AmpGold, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    // The colour is only a hint; the status text next to it carries the meaning.
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(
                            when {
                                connected && ready -> AmpCyanLight
                                hardwareState.connectionState == ConnectionState.DISCONNECTED -> AmpRedLED
                                else -> AmpOrangeGlow
                            }
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    if (status.isBusy) {
                        CircularProgressIndicator(
                            color = AmpGold, trackColor = AmpSurfaceVariant, strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = status.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.isError) AmpRedLED else AmpTextPrimary
                    )
                }

                if (needsLink) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.connection_needs_link),
                        style = MaterialTheme.typography.bodySmall,
                        color = AmpTextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    AmpButton(
                        text = stringResource(R.string.connection_open_bt_settings),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.testTag("open_bt_settings")
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.connection_paired_amps), style = MaterialTheme.typography.labelMedium,
                    color = AmpGold, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))

                if (devices.isEmpty()) {
                    Text(
                        stringResource(R.string.connection_no_amps),
                        style = MaterialTheme.typography.bodySmall,
                        color = AmpTextSecondary
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 220.dp)
                    ) {
                        items(devices) { device ->
                            val isThis = connected && hardwareState.connectedDevice?.macAddress == device.macAddress
                            val connectAction = stringResource(R.string.connection_connect_to, device.name)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isThis) AmpSurfaceVariant else AmpMetallicDark,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, if (isThis) AmpGold else AmpSurfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(onClickLabel = connectAction, role = Role.Button) {
                                        hardwareManager.connectDevice(device)
                                    }
                                    .semantics(mergeDescendants = true) {}
                            ) {
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 56.dp)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(device.name, style = MaterialTheme.typography.titleSmall,
                                        color = AmpTextPrimary, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f))
                                    if (isThis) {
                                        Icon(Icons.Default.Check,
                                            contentDescription = stringResource(R.string.connection_connected),
                                            tint = AmpGold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AmpOutlinedButton(
                        text = stringResource(R.string.connection_disconnect),
                        onClick = { hardwareManager.disconnect() },
                        enabled = hardwareState.connectionState != ConnectionState.DISCONNECTED,
                        modifier = Modifier.weight(1f)
                    )
                    AmpButton(
                        text = stringResource(R.string.dialog_close),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget)
                    )
                }
            }
        }
    }
}
