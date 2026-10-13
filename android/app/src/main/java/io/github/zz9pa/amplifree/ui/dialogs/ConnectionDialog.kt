package io.github.zz9pa.amplifree.ui.dialogs

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.zz9pa.amplifree.data.ConnectionState
import io.github.zz9pa.amplifree.data.HardwareManager
import io.github.zz9pa.amplifree.data.HardwareState
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
    val status by hardwareManager.status.collectAsState()
    val ready by hardwareManager.ready.collectAsState()
    val devices = hardwareManager.availableDevices
    val needsLink by hardwareManager.needsLink.collectAsState()
    val context = LocalContext.current
    val connected = hardwareState.connectionState == ConnectionState.CONNECTED

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AmpSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, AmpGold),
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("connection_dialog")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("AMP CONNECTION", style = MaterialTheme.typography.titleMedium,
                    color = AmpGold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmpTextPrimary
                    )
                }

                if (needsLink) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Android has the amp paired but not connected. Tap it in Bluetooth " +
                        "settings; when it shows Connected, come back and Amplifree finishes " +
                        "connecting by itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmpTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmpGold, contentColor = AmpMetallicDark),
                        modifier = Modifier.testTag("open_bt_settings")
                    ) { Text("OPEN BLUETOOTH SETTINGS", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }

                Spacer(Modifier.height(16.dp))
                Text("PAIRED AMPS", style = MaterialTheme.typography.labelSmall,
                    color = AmpGold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                if (devices.isEmpty()) {
                    Text(
                        "No paired amp found. Pair it in Android's Bluetooth settings first " +
                        "(it shows up as an audio device), then come back. After that Amplifree " +
                        "reconnects to it automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmpTextSecondary,
                        fontSize = 12.sp
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 220.dp)
                    ) {
                        items(devices) { device ->
                            val isThis = connected && hardwareState.connectedDevice?.macAddress == device.macAddress
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isThis) AmpSurfaceVariant else AmpMetallicDark,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, if (isThis) AmpGold else AmpSurfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { hardwareManager.connectDevice(device) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(device.name, style = MaterialTheme.typography.titleSmall,
                                        color = AmpTextPrimary, fontWeight = FontWeight.Bold)
                                    if (isThis) Icon(Icons.Default.Check, contentDescription = "Connected", tint = AmpGold)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = { hardwareManager.disconnect() },
                        enabled = hardwareState.connectionState != ConnectionState.DISCONNECTED,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmpGold)
                    ) { Text("DISCONNECT", color = AmpGold, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = AmpGold, contentColor = AmpMetallicDark)
                    ) { Text("CLOSE", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
