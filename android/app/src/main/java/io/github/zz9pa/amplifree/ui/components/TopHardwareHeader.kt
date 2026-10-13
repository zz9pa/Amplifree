package io.github.zz9pa.amplifree.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zz9pa.amplifree.data.ConnectionState
import io.github.zz9pa.amplifree.data.HardwareState
import io.github.zz9pa.amplifree.ui.theme.*

/** Connection chip on the left, the tone currently on the amp on the right. */
@Composable
fun TopHardwareHeader(
    hardwareState: HardwareState,
    ready: Boolean,
    toneName: String?,
    onOpenConnectionDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = hardwareState.connectionState == ConnectionState.CONNECTED
    Surface(
        color = AmpSurface,
        modifier = modifier
            .fillMaxWidth()
            // The app draws edge-to-edge; without this the header sits under the status
            // bar and its controls cannot be tapped.
            .windowInsetsPadding(WindowInsets.statusBars)
            .border(1.dp, AmpSurfaceVariant, RoundedCornerShape(0.dp, 0.dp, 12.dp, 12.dp))
            .testTag("hardware_header")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(AmpMetallicDark)
                    .clickable { onOpenConnectionDialog() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("connection_chip")
            ) {
                Icon(
                    imageVector = if (connected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                    contentDescription = "Amp connection",
                    tint = when {
                        connected && ready -> AmpCyanLight
                        connected -> AmpOrangeGlow
                        else -> AmpTextSecondary
                    },
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        connected && ready -> hardwareState.connectedDevice?.name ?: "Connected"
                        connected -> "Connecting…"
                        else -> "Tap to connect"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AmpTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            if (!toneName.isNullOrBlank()) {
                Text(
                    text = toneName,
                    style = MaterialTheme.typography.titleMedium,
                    color = AmpGold,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}
