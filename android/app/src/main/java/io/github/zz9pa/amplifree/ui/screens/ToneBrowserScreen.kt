package io.github.zz9pa.amplifree.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zz9pa.amplifree.ui.theme.*

/**
 * Searchable list of every tone stored on the connected amp. The names are read
 * from the amp itself, so the list is always exactly what that amp holds.
 */
@Composable
fun ToneBrowserScreen(
    ampPresets: List<Pair<Int, String>>,
    activeTone: String?,
    isReady: Boolean,
    scanProgress: Pair<Int, Int>?,
    statusText: String,
    onRescan: () -> Unit,
    onLoad: (index: Int, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(ampPresets, query) {
        if (query.isBlank()) ampPresets
        else ampPresets.filter { it.second.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmpMetallicDark)
            .padding(12.dp)
            .testTag("tone_browser")
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AmpGold) },
            placeholder = { Text("Search tones", color = AmpTextSecondary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AmpGold,
                unfocusedBorderColor = AmpSurfaceVariant,
                focusedTextColor = AmpTextPrimary,
                unfocusedTextColor = AmpTextPrimary,
                cursorColor = AmpGold
            ),
            modifier = Modifier.fillMaxWidth().testTag("tone_search")
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    scanProgress != null -> "Reading the amp… ${scanProgress.first}/${scanProgress.second}"
                    ampPresets.isEmpty() -> "No tones loaded"
                    query.isBlank() -> "${ampPresets.size} tones on the amp"
                    else -> "${filtered.size} of ${ampPresets.size}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = AmpTextSecondary,
                fontSize = 11.sp
            )
            OutlinedButton(
                onClick = onRescan,
                enabled = isReady && scanProgress == null,
                border = androidx.compose.foundation.BorderStroke(1.dp, AmpGold)
            ) {
                Text("REFRESH", color = AmpGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (scanProgress != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { scanProgress.first.toFloat() / scanProgress.second.coerceAtLeast(1) },
                color = AmpGold,
                trackColor = AmpSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(10.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        query.isNotBlank() -> "Nothing matches \"$query\""
                        !isReady -> "Connect your amp to see its tones.\n$statusText"
                        else -> "Waiting for the amp's tone list…"
                    },
                    color = AmpTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.first }) { (index, name) ->
                val isActive = activeTone == name
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isActive) AmpSurfaceVariant else AmpSurface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (isActive) AmpGold else AmpSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isReady) { onLoad(index, name) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
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
                                "Slot $index",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmpTextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        if (isActive) Icon(Icons.Default.Check, contentDescription = "Active", tint = AmpGold)
                    }
                }
            }
        }
    }
}
