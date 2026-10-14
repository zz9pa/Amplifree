package io.github.zz9pa.amplifree.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.zz9pa.amplifree.R
import io.github.zz9pa.amplifree.ui.FriendlyStatus
import io.github.zz9pa.amplifree.ui.LinkStatus
import io.github.zz9pa.amplifree.ui.components.*
import io.github.zz9pa.amplifree.ui.theme.*

/**
 * Searchable list of every tone stored on the connected amp. The names are read
 * from the amp itself, so the list is always exactly what that amp holds.
 */
@Composable
fun ToneBrowserScreen(
    ampPresets: List<Pair<Int, String>>,
    activeTone: String?,
    link: LinkStatus,
    scanProgress: Pair<Int, Int>?,
    status: FriendlyStatus,
    onRescan: () -> Unit,
    onLoad: (index: Int, name: String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isReady = link.isReady
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val filtered = remember(ampPresets, query) {
        if (query.isBlank()) ampPresets
        else ampPresets.filter { it.second.contains(query, ignoreCase = true) }
    }
    val resources = LocalContext.current.resources

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmpMetallicDark)
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp)
            .testTag("tone_browser")
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AmpGold) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }, modifier = Modifier.testTag("tone_search_clear")) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.tones_search_clear),
                            tint = AmpTextSecondary)
                    }
                }
            } else null,
            placeholder = { Text(stringResource(R.string.tones_search_hint), color = AmpTextSecondary) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AmpGold,
                unfocusedBorderColor = AmpSurfaceVariant,
                focusedTextColor = AmpTextPrimary,
                unfocusedTextColor = AmpTextPrimary,
                cursorColor = AmpGold
            ),
            modifier = Modifier.fillMaxWidth().testTag("tone_search")
        )

        if (!isReady && ampPresets.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LinkBanner(link, stringResource(R.string.tones_banner_disconnected), onConnect)
        }

        Spacer(Modifier.height(4.dp))

        // With nothing listed the empty states below carry their own Refresh/Connect action.
        if (ampPresets.isNotEmpty() || scanProgress != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        scanProgress != null ->
                            stringResource(R.string.tones_scanning, scanProgress.first, scanProgress.second)
                        ampPresets.isEmpty() -> ""
                        query.isBlank() ->
                            resources.getQuantityString(R.plurals.tones_count, ampPresets.size, ampPresets.size)
                        else -> stringResource(R.string.tones_filtered_count, filtered.size, ampPresets.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = AmpTextSecondary,
                    modifier = Modifier.weight(1f)
                )
                val refreshDescription = stringResource(R.string.tones_refresh_description)
                AmpOutlinedButton(
                    text = stringResource(R.string.tones_refresh),
                    onClick = onRescan,
                    enabled = isReady && scanProgress == null,
                    modifier = Modifier
                        .semantics { contentDescription = refreshDescription }
                        .testTag("tone_refresh")
                )
            }
        }

        if (scanProgress != null) {
            LinearProgressIndicator(
                progress = { scanProgress.first.toFloat() / scanProgress.second.coerceAtLeast(1) },
                color = AmpGold,
                trackColor = AmpSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag("tone_scan_progress")
            )
        }

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    query.isNotBlank() -> StateMessage(
                        icon = Icons.Default.SearchOff,
                        title = stringResource(R.string.tones_no_match_title),
                        body = stringResource(R.string.tones_no_match_body, query),
                        action = { AmpOutlinedButton(stringResource(R.string.tones_search_clear), { query = "" }) }
                    )
                    !isReady -> ConnectPrompt(
                        link = link,
                        body = stringResource(R.string.tones_disconnected_body),
                        detail = status.text.takeIf { it.isNotBlank() },
                        onConnect = onConnect
                    )
                    scanProgress != null || status.isBusy -> StateMessage(
                        icon = null,
                        busy = true,
                        title = stringResource(R.string.tones_waiting_title),
                        body = stringResource(R.string.tones_waiting_body),
                        detail = status.text.takeIf { it.isNotBlank() }
                    )
                    else -> StateMessage(
                        icon = Icons.Default.LibraryMusic,
                        title = stringResource(R.string.tones_empty_title),
                        body = stringResource(R.string.tones_empty_body),
                        detail = status.text.takeIf { status.isError },
                        action = { AmpButton(stringResource(R.string.tones_refresh), onRescan) }
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filtered, key = { it.first }) { (index, name) ->
                val isActive = activeTone == name
                val loadAction = stringResource(R.string.tones_load_action)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isActive) AmpSurfaceVariant else AmpSurface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (isActive) AmpGold else AmpSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isReady, onClickLabel = loadAction, role = Role.Button) {
                            onLoad(index, name)
                        }
                        // Name, slot and "Active" are read as a single item.
                        .semantics(mergeDescendants = true) { selected = isActive }
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isReady) AmpTextPrimary else AmpTextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.tones_slot, index),
                                style = MaterialTheme.typography.bodySmall,
                                color = AmpTextSecondary
                            )
                        }
                        if (isActive) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.tones_active), tint = AmpGold)
                        }
                    }
                }
            }
        }
    }
}
