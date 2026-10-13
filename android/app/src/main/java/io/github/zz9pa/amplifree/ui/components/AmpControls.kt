package io.github.zz9pa.amplifree.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zz9pa.amplifree.R
import io.github.zz9pa.amplifree.ui.LinkStatus
import io.github.zz9pa.amplifree.ui.theme.*

/** Smallest touch target Android recommends. */
val MinTouchTarget = 48.dp

/** Filled call-to-action button. Always at least 48dp tall and free to wrap at large font sizes. */
@Composable
fun AmpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = AmpGold, contentColor = AmpMetallicDark),
        modifier = modifier.heightIn(min = MinTouchTarget)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center) }
}

/** Secondary button with the gold outline. */
@Composable
fun AmpOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, if (enabled) AmpGold else AmpSurfaceVariant),
        modifier = modifier.heightIn(min = MinTouchTarget)
    ) {
        Text(
            text,
            color = if (enabled) AmpGold else AmpTextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * A centred message with an optional progress spinner and action: used for "not connected",
 * "loading" and "nothing here" states so they all look and read the same.
 * Scrolls, so it still fits when the font is large or the screen is short.
 */
@Composable
fun StateMessage(
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    detail: String? = null,
    busy: Boolean = false,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
        ) {
            if (busy) {
                CircularProgressIndicator(color = AmpGold, trackColor = AmpSurfaceVariant, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(16.dp))
            } else if (icon != null) {
                // Decorative: the title next to it says everything the icon does.
                Icon(icon, contentDescription = null, tint = AmpGold, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = AmpGold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = AmpTextSecondary, textAlign = TextAlign.Center)
            }
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = AmpCyanLight, textAlign = TextAlign.Center)
            }
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * Full-screen "your amp isn't connected" state shared by the tabs, with the Connect action.
 * [detail] is the (already friendly) status line, shown under the explanation.
 */
@Composable
fun ConnectPrompt(
    link: LinkStatus,
    body: String,
    detail: String?,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (link) {
        LinkStatus.CONNECTING -> StateMessage(
            icon = Icons.Default.BluetoothSearching,
            title = stringResource(R.string.link_connecting_title),
            body = body,
            detail = detail,
            busy = true,
            modifier = modifier,
            action = { AmpOutlinedButton(stringResource(R.string.action_connect), onConnect) }
        )
        LinkStatus.NEEDS_LINK -> StateMessage(
            icon = Icons.Default.Bluetooth,
            title = stringResource(R.string.link_needs_link_title),
            body = body,
            detail = detail,
            modifier = modifier,
            action = { AmpButton(stringResource(R.string.action_connect), onConnect) }
        )
        else -> StateMessage(
            icon = Icons.Default.BluetoothDisabled,
            title = stringResource(R.string.link_disconnected_title),
            body = body,
            detail = detail,
            modifier = modifier,
            action = { AmpButton(stringResource(R.string.action_connect), onConnect) }
        )
    }
}
