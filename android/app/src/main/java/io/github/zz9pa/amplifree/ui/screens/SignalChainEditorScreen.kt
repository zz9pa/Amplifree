package io.github.zz9pa.amplifree.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.github.zz9pa.amplifree.bluetooth.ParamInfo
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.ui.dialogs.ModelPickerDialog
import io.github.zz9pa.amplifree.ui.ModelNames
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
    dirty: Boolean = false,
    saveStatus: String? = null,
    canUndo: Boolean = false,
    onSave: () -> Unit = {},
    onUndo: () -> Unit = {},
    onDismissSaveStatus: () -> Unit = {},
    modelChoices: (block: String) -> List<String> = { emptyList() },
    paramInfo: Map<String, ParamInfo> = emptyMap(),
    symbolName: (Int) -> String = { "#$it" },
    onSetInt: (block: String, param: String, value: Int) -> Unit = { _, _, _ -> },
    masterVolume: Float? = null,
    onMasterVolume: (Float) -> Unit = {},
    onSwapModel: (block: String, model: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var picking by remember { mutableStateOf<String?>(null) }
    if (preset == null) {
        EmptyEditor(isConnected, statusText, onOpenPresets, modifier)
        return
    }

    // `revision` is read so Compose recomposes after an in-place record edit.
    val blocks = remember(preset, revision) { preset.blocks() }
    val routing = blocks.firstOrNull { it.symbol == ROUTING }
    val chain = blocks.filter { it.symbol != ROUTING }

    picking?.let { sym ->
        ModelPickerDialog(
            title = BLOCK_LABELS[sym] ?: sym,
            current = blocks.firstOrNull { it.symbol == sym }?.flow,
            options = modelChoices(sym),
            onPick = { m -> picking = null; onSwapModel(sym, m) },
            onDismiss = { picking = null }
        )
    }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.name.ifBlank { "Live Tone" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = AmpGold,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (dirty) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "● MODIFIED",
                            color = AmpOrangeGlow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("modified_chip")
                        )
                    }
                }
                Text(
                    text = if (isConnected) statusText else "Not connected — changes won't reach the amp",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) AmpCyanLight else AmpTextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        if (masterVolume != null) {
            item {
                SectionCard("MASTER VOLUME") {
                    ParamSlider(
                        label = "Master",
                        initial = masterVolume,
                        range = 0f..1f,
                        enabled = isConnected,
                        key = "master.${masterVolume}",
                        onCommit = onMasterVolume
                    )
                }
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

        // ---- mix levels and routing (they live on the routing block) ---------
        if (routing != null) {
            val others = routing.params.filter {
                (it.isFloat || it.isBool || it.isInt) && !(it.isBool && it.name.endsWith("EnableState"))
            }
            if (others.isNotEmpty()) {
                item {
                    SectionCard("MIX & ROUTING") {
                        others.forEach { p ->
                            ParamRow(
                                blockSymbol = ROUTING,
                                p = p,
                                info = paramInfo[ROUTING + "." + p.name] ?: paramInfo[(routing.flow ?: ROUTING) + "." + p.name],
                                label = routingLabel(p.name),
                                revision = revision,
                                isConnected = isConnected,
                                symbolName = symbolName,
                                onSetFloat = onSetFloat,
                                onSetBool = onSetBool,
                                onSetInt = onSetInt
                            )
                        }
                    }
                }
            }
        }

        // ---- one card per module, with its real parameters ------------------
        items(chain, key = { it.symbol }) { block ->
            val choices = modelChoices(block.symbol)
            SectionCard(
                title = (BLOCK_LABELS[block.symbol] ?: block.symbol).uppercase(),
                subtitle = block.flow?.let { ModelNames.name(it) + if (choices.size > 1 && isConnected) "  ▾" else "" },
                onSubtitleClick = if (choices.size > 1 && isConnected) ({ picking = block.symbol }) else null
            ) {
                val editable = block.params.filter { it.isFloat || it.isBool || it.isInt }
                if (editable.isEmpty()) {
                    Text(
                        "No adjustable parameters",
                        color = AmpTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp
                    )
                } else {
                    editable.forEach { p ->
                        ParamRow(
                            blockSymbol = block.symbol,
                            p = p,
                            info = paramInfo[(block.flow ?: block.symbol) + "." + p.name],
                            label = prettyParam(p.name),
                            revision = revision,
                            isConnected = isConnected,
                            symbolName = symbolName,
                            onSetFloat = onSetFloat,
                            onSetBool = onSetBool,
                            onSetInt = onSetInt
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Saving is switched off until the stored-tone format is handled correctly: writing the
                // live tone's layout into a slot makes the amp go silent (see the hardware notes).
                Button(
                    onClick = onSave,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().testTag("save_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = AmpGold, contentColor = AmpMetallicDark)
                ) { Text("SAVE (COMING SOON)", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                if (saveStatus != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(saveStatus, color = AmpCyanLight, fontSize = 11.sp,
                            modifier = Modifier.weight(1f).testTag("save_status"))
                        if (canUndo) TextButton(onClick = onUndo) {
                            Text("UNDO", color = AmpGold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        TextButton(onClick = onDismissSaveStatus) {
                            Text("OK", color = AmpTextSecondary, fontSize = 11.sp)
                        }
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
    onSubtitleClick: (() -> Unit)? = null,
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
                        color = AmpCyanLight, fontSize = 10.sp,
                        modifier = if (onSubtitleClick != null)
                            Modifier.clickable { onSubtitleClick() }.padding(vertical = 4.dp).testTag("model_$title")
                        else Modifier)
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** "FX1Mix" -> "FX 1 Mix"; "FX1Post" -> "FX 1 after amp"; "ChannelVolume" -> "Channel Volume". */
private fun routingLabel(name: String): String {
    val base = name.removeSuffix("State")
    val spaced = base.replace(Regex("(?<=[a-z])(?=[A-Z0-9])|(?<=[A-Z])(?=[0-9])|(?<=[0-9])(?=[A-Z])"), " ")
    val cap = spaced.replaceFirstChar { it.uppercase() }
    return when {
        cap.endsWith(" Post") -> cap.removeSuffix(" Post").replace("Volume", "Volume Pedal") + " after amp"
        else -> cap
    }
}

/** One editable parameter: a slider, a switch or a choice, depending on its type. */
@Composable
private fun ParamRow(
    blockSymbol: String,
    p: PresetCodec.Param,
    info: ParamInfo?,
    label: String,
    revision: Int,
    isConnected: Boolean,
    symbolName: (Int) -> String,
    onSetFloat: (String, String, Float) -> Unit,
    onSetBool: (String, String, Boolean) -> Unit,
    onSetInt: (String, String, Int) -> Unit
) {
    val k = "$blockSymbol.${p.name}.$revision"
    when {
        p.isFloat -> {
            val v = p.record.asFloat
            ParamSlider(
                label = label,
                initial = v,
                range = info?.sliderRange(v) ?: (if (v in 0f..1f) 0f..1f else null),
                enabled = isConnected,
                key = k,
                onCommit = { nv ->
                    p.record.setFloat(nv)
                    onSetFloat(blockSymbol, p.name, nv)
                }
            )
        }
        p.isBool -> BoolRow(
            label = label,
            initial = p.record.asBool,
            enabled = isConnected,
            key = k,
            onChange = { nv ->
                p.record.setBool(nv)
                onSetBool(blockSymbol, p.name, nv)
            }
        )
        else -> {
            val isEnum = p.record.type == PresetCodec.TYPE_ENUM
            val nameOf: (Int) -> String =
                if (isEnum) { id -> ModelNames.tidy(symbolName(id)) } else { id -> id.toString() }
            ChoiceRow(
                label = label,
                current = p.record.asInt,
                options = (info?.values.orEmpty() + p.record.asInt).distinct().sorted(),
                nameOf = nameOf,
                enabled = isConnected,
                key = k,
                onPick = { nv ->
                    p.record.setInt(nv)
                    onSetInt(blockSymbol, p.name, nv)
                }
            )
        }
    }
}

/**
 * Slider that only pushes on release. A parameter change is a command on the wire, so streaming
 * every pixel of a drag would flood the link; committing on release keeps it responsive.
 * [range] null means the range is not known, so the value is shown but cannot be changed.
 */
@Composable
private fun ParamSlider(
    label: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>?,
    enabled: Boolean,
    key: String,
    onCommit: (Float) -> Unit
) {
    var value by remember(key) { mutableFloatStateOf(initial) }
    if (range == null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = AmpTextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text("%.2f".format(initial), color = AmpTextSecondary,
                style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
        }
        return
    }
    val unit = range.start >= 0f && range.endInclusive <= 1f
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = AmpTextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (unit) "%.2f".format(value) else "%.1f".format(value),
                color = AmpTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp
            )
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value) },
            valueRange = range,
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

@Composable
private fun BoolRow(label: String, initial: Boolean, enabled: Boolean, key: String, onChange: (Boolean) -> Unit) {
    var on by remember(key) { mutableStateOf(initial) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AmpTextPrimary, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = on,
            enabled = enabled,
            onCheckedChange = { on = it; onChange(it) },
            colors = SwitchDefaults.colors(checkedThumbColor = AmpGold, checkedTrackColor = AmpSurfaceVariant),
            modifier = Modifier.testTag("bool_$label")
        )
    }
}

/** A choice-type parameter: tap to pick from the values this amp's tones have used. */
@Composable
private fun ChoiceRow(
    label: String,
    current: Int,
    options: List<Int>,
    nameOf: (Int) -> String,
    enabled: Boolean,
    key: String,
    onPick: (Int) -> Unit
) {
    var chosen by remember(key) { mutableIntStateOf(current) }
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AmpTextPrimary, style = MaterialTheme.typography.bodyMedium)
        Box {
            val canPick = enabled && options.size > 1
            TextButton(onClick = { open = true }, enabled = canPick, modifier = Modifier.testTag("choice_$label")) {
                Text(nameOf(chosen) + if (canPick) "  ▾" else "", color = AmpCyanLight, fontSize = 12.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(nameOf(o)) },
                        onClick = { open = false; chosen = o; onPick(o) }
                    )
                }
            }
        }
    }
}
