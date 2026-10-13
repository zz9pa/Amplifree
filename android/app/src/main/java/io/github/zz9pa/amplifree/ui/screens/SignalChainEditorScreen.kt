package io.github.zz9pa.amplifree.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.ui.theme.*

/**
 * Edits the tone that is actually loaded in the amp.
 *
 * Everything here comes from the decoded preset (PresetCodec): the blocks are
 * the real signal chain, the toggles are the real *EnableState booleans on the
 * routing block, and the sliders are the real float parameters. Changing one
 * sends a single SetDspModelParam on the Editor service.
 */

/** Routing block that carries the chain's enable/mix switches. */
private const val ROUTING = "GuitarProc"

/** Pretty names for the blocks we know; anything else falls back to its symbol. */
private val BLOCK_LABELS = mapOf(
    "GuitarProc" to "Routing",
    "Gate" to "Noise Gate",
    "VolumePedal" to "Volume Pedal",
    "Wah" to "Wah",
    "FX1" to "FX 1",
    "FX2" to "FX 2",
    "FX3" to "FX 3",
    "FX4" to "FX 4",
    "Amp" to "Amplifier",
    "Air" to "Air",
    "StudioCab" to "Studio Cab",
    "LiveCab" to "Live Cab",
    "PostComp" to "Compressor",
    "PostEQ" to "EQ"
)

/** "DriveState" -> "Drive"; "Thresh/GState" -> "Thresh/G". */
private fun prettyParam(name: String) = name.removeSuffix("State").ifEmpty { name }

/** Enable switches live on the routing block and are named <thing>EnableState. */
private fun enableLabel(name: String) =
    name.removeSuffix("EnableState")
        .replace("FX", "FX ")
        .replace("Cab", "Cabinet")
        .replace("VolumePedal", "Volume Pedal")

@Composable
fun SignalChainEditorScreen(
    preset: PresetCodec.Preset?,
    revision: Int,
    isConnected: Boolean,
    statusText: String,
    onSetFloat: (block: String, param: String, value: Float) -> Unit,
    onSetBool: (block: String, param: String, value: Boolean) -> Unit,
    onOpenPresets: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (preset == null) {
        EmptyEditor(isConnected, statusText, onOpenPresets, modifier)
        return
    }

    // `revision` is read so Compose recomposes after an in-place record edit.
    val blocks = remember(preset, revision) { preset.blocks() }
    val routing = blocks.firstOrNull { it.symbol == ROUTING }
    val chain = blocks.filter { it.symbol != ROUTING }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AmpMetallicDark)
            .padding(horizontal = 12.dp)
            .testTag("signal_chain_editor"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Column {
                Text(
                    text = preset.name.ifBlank { "Live Tone" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = AmpGold,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = if (isConnected) statusText else "Not connected — changes won't reach the amp",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) AmpCyanLight else AmpTextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // ---- pedal / block on-off switches ----------------------------------
        if (routing != null) {
            val switches = routing.params.filter { it.isBool && it.name.endsWith("EnableState") }
            if (switches.isNotEmpty()) {
                item {
                    SectionCard("SIGNAL PATH") {
                        switches.forEach { p ->
                            var on by remember(preset, revision, p.name) {
                                mutableStateOf(p.record.asBool)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    enableLabel(p.name),
                                    color = if (on) AmpTextPrimary else AmpTextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                                )
                                Switch(
                                    checked = on,
                                    enabled = isConnected,
                                    onCheckedChange = {
                                        on = it
                                        p.record.setBool(it)
                                        onSetBool(ROUTING, p.name, it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = AmpGold,
                                        checkedTrackColor = AmpSurfaceVariant
                                    ),
                                    modifier = Modifier.testTag("enable_${p.name}")
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- one card per module, with its real parameters ------------------
        items(chain, key = { it.symbol }) { block ->
            val floats = block.params.filter { it.isFloat }
            SectionCard(
                title = (BLOCK_LABELS[block.symbol] ?: block.symbol).uppercase(),
                subtitle = block.flow
            ) {
                if (floats.isEmpty()) {
                    Text(
                        "No adjustable parameters",
                        color = AmpTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp
                    )
                } else {
                    floats.forEach { p ->
                        ParamSlider(
                            label = prettyParam(p.name),
                            initial = p.record.asFloat,
                            enabled = isConnected,
                            key = "${block.symbol}.${p.name}.$revision",
                            onCommit = { v ->
                                p.record.setFloat(v)
                                onSetFloat(block.symbol, p.name, v)
                            }
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenPresets,
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmpGold)
                ) { Text("BROWSE TONES", color = AmpGold, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onReload,
                    enabled = isConnected,
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmpGold)
                ) { Text("RELOAD FROM AMP", color = AmpGold, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun EmptyEditor(isConnected: Boolean, statusText: String, onOpenPresets: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmpMetallicDark)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (isConnected) "Reading your tone…" else "Not connected",
            style = MaterialTheme.typography.titleLarge,
            color = AmpGold,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect your amp and the tone that is playing on it appears here, " +
            "ready to edit: every pedal, the amp and the cab.",
            style = MaterialTheme.typography.bodyMedium,
            color = AmpTextSecondary
        )
        Spacer(Modifier.height(6.dp))
        Text(statusText, style = MaterialTheme.typography.bodySmall, color = AmpCyanLight, fontSize = 11.sp)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onOpenPresets,
            colors = ButtonDefaults.buttonColors(containerColor = AmpGold, contentColor = AmpMetallicDark)
        ) { Text("BROWSE TONES", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = AmpSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AmpSurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium,
                    color = AmpGold, fontWeight = FontWeight.ExtraBold)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = AmpCyanLight, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * Slider that only pushes on release. A guitar preset parameter change is a
 * command on the wire, so streaming every pixel of a drag would flood the
 * link; committing on release keeps it responsive without spamming.
 */
@Composable
private fun ParamSlider(
    label: String,
    initial: Float,
    enabled: Boolean,
    key: String,
    onCommit: (Float) -> Unit
) {
    var value by remember(key) { mutableFloatStateOf(initial) }
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = AmpTextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "%.0f%%".format(value * 100f),
                color = AmpTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp
            )
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value) },
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = AmpGold,
                activeTrackColor = AmpOrangeGlow,
                inactiveTrackColor = AmpSurfaceVariant
            ),
            modifier = Modifier.testTag("param_$label")
        )
    }
}
