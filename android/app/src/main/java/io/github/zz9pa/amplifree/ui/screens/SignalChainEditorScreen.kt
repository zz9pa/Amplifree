package io.github.zz9pa.amplifree.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zz9pa.amplifree.R
import io.github.zz9pa.amplifree.bluetooth.ParamInfo
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.ui.FriendlyStatus
import io.github.zz9pa.amplifree.ui.LinkStatus
import io.github.zz9pa.amplifree.ui.ModelNames
import io.github.zz9pa.amplifree.ui.StatusKind
import io.github.zz9pa.amplifree.ui.WIDE_CONTENT_MIN_WIDTH
import io.github.zz9pa.amplifree.ui.components.*
import io.github.zz9pa.amplifree.ui.dialogs.ModelPickerDialog
import io.github.zz9pa.amplifree.ui.theme.*
import kotlinx.coroutines.launch

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

/** Card keys that are not blocks of the chain. */
private val SLIDER_SIDE_MARGIN = 16.dp

/** Ordinary 0..1 knobs are shown on a 0 to 10 dial, like the controls on the amp. */
private const val KNOB_SCALE = 10f
private const val KEY_MASTER = "@master"
private const val KEY_PATH = "@path"
private const val KEY_MIX = "@mix"

/** Pretty names for the blocks we know; anything else falls back to its symbol. */
private val BLOCK_LABELS = mapOf(
    "GuitarProc" to R.string.block_routing,
    "Gate" to R.string.block_gate,
    "VolumePedal" to R.string.block_volume_pedal,
    "Wah" to R.string.block_wah,
    "FX1" to R.string.block_fx1,
    "FX2" to R.string.block_fx2,
    "FX3" to R.string.block_fx3,
    "FX4" to R.string.block_fx4,
    "Amp" to R.string.block_amp,
    "Air" to R.string.block_air,
    "StudioCab" to R.string.block_studio_cab,
    "LiveCab" to R.string.block_live_cab,
    "PostComp" to R.string.block_post_comp,
    "PostEQ" to R.string.block_post_eq
)

@Composable
private fun blockLabel(symbol: String): String =
    BLOCK_LABELS[symbol]?.let { stringResource(it) } ?: symbol

/** "DriveState" -> "Drive"; "Thresh/GState" -> "Thresh/G". */
private fun prettyParam(name: String) = name.removeSuffix("State").ifEmpty { name }

/** Enable switches live on the routing block and are named <thing>EnableState. */
private fun enableLabel(name: String) =
    name.removeSuffix("EnableState")
        .replace("FX", "FX ")
        .replace("Cab", "Cabinet")
        .replace("VolumePedal", "Volume Pedal")

/** One card of the editor: what the jump chips and the two-column split need to know about it. */
private class EditorCard(
    val key: String,
    val title: String,
    /** Rough height, used to balance the two columns. */
    val weight: Int,
    val subtitle: String? = null,
    val onSubtitleClick: (() -> Unit)? = null,
    val content: @Composable ColumnScope.() -> Unit
)

private class JumpChip(val label: String, val cardKey: String)

/** The cards the user has collapsed, saved across rotation and while another tab is showing. */
private val CollapsedSaver = Saver<MutableState<Set<String>>, ArrayList<String>>(
    save = { ArrayList(it.value) },
    restore = { mutableStateOf(it.toSet()) }
)

@Composable
fun SignalChainEditorScreen(
    preset: PresetCodec.Preset?,
    revision: Int,
    link: LinkStatus,
    status: FriendlyStatus,
    onSetFloat: (block: String, param: String, value: Float) -> Unit,
    onSetBool: (block: String, param: String, value: Boolean) -> Unit,
    onOpenPresets: () -> Unit,
    onReload: () -> Unit,
    onConnect: () -> Unit = {},
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
    onImportTone: () -> Unit = {},
    onExportTone: () -> Unit = {},
    /** A newly loaded tone starts with its cards folded, so a stray touch while scrolling cannot change it. */
    initiallyCollapsed: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isConnected = link.isReady
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    if (preset == null) {
        EmptyEditor(link, status, onOpenPresets, onConnect, modifier)
        return
    }

    // `revision` is read so Compose recomposes after an in-place record edit.
    val blocks = remember(preset, revision) { preset.blocks() }
    val routing = blocks.firstOrNull { it.symbol == ROUTING }
    val chain = blocks.filter { it.symbol != ROUTING }

    picking?.let { sym ->
        ModelPickerDialog(
            title = blockLabel(sym),
            current = blocks.firstOrNull { it.symbol == sym }?.flow,
            options = modelChoices(sym),
            onPick = { m -> picking = null; onSwapModel(sym, m) },
            onDismiss = { picking = null }
        )
    }

    // ---- the cards, in reading order ---------------------------------------
    val masterTitle = stringResource(R.string.section_master)
    val pathTitle = stringResource(R.string.section_path)
    val mixTitle = stringResource(R.string.section_mix)
    val masterLabel = stringResource(R.string.param_master)
    val chainTitles = chain.associate { it.symbol to blockLabel(it.symbol).uppercase() }

    val cards = mutableListOf<EditorCard>()

    if (masterVolume != null) {
        cards += EditorCard(KEY_MASTER, masterTitle, weight = 2) {
            ParamSlider(
                label = masterLabel,
                initial = masterVolume,
                range = 0f..1f,
                enabled = isConnected,
                key = "master.${masterVolume}",
                // The amp's own knob is marked 0 to 11, so the number shown matches it.
                display = ::masterDisplay,
                onCommit = onMasterVolume
            )
        }
    }

    if (routing != null) {
        val switches = routing.params.filter { it.isBool && it.name.endsWith("EnableState") }
        if (switches.isNotEmpty()) {
            cards += EditorCard(KEY_PATH, pathTitle, weight = switches.size + 1) {
                switches.forEach { p ->
                    var on by rememberSaveable(preset, revision, p.name) { mutableStateOf(p.record.asBool) }
                    SwitchRow(
                        label = enableLabel(p.name),
                        checked = on,
                        enabled = isConnected,
                        tag = "enable_${p.name}",
                        emphasise = true
                    ) {
                        on = it
                        p.record.setBool(it)
                        onSetBool(ROUTING, p.name, it)
                    }
                }
            }
        }

        // ---- mix levels and routing (they live on the routing block) ---------
        val others = routing.params.filter {
            (it.isFloat || it.isBool || it.isInt) && !(it.isBool && it.name.endsWith("EnableState")) &&
                // Not a user control: each effect model sets how it blends in, and nothing says what
                // the values mean. The value is still read and passed through untouched.
                !it.name.endsWith("MixTypeState")
        }
        if (others.isNotEmpty()) {
            cards += EditorCard(KEY_MIX, mixTitle, weight = others.size + 1) {
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

    // ---- one card per module, with its real parameters ----------------------
    chain.forEach { block ->
        val choices = modelChoices(block.symbol)
        val canSwap = choices.size > 1 && isConnected
        val editable = block.params.filter { it.isFloat || it.isBool || it.isInt }
        cards += EditorCard(
            key = block.symbol,
            title = chainTitles.getValue(block.symbol),
            weight = editable.size.coerceAtLeast(1) + 1,
            subtitle = block.flow?.let { ModelNames.name(it) },
            onSubtitleClick = if (canSwap) ({ picking = block.symbol }) else null
        ) {
            if (editable.isEmpty()) {
                Text(
                    stringResource(R.string.card_no_params),
                    color = AmpTextSecondary,
                    style = MaterialTheme.typography.bodySmall
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

    // ---- shortcut chips: Amp, Cab, FX 1-4, Mix -------------------------------
    val present = cards.map { it.key }.toSet()
    val chips = buildList {
        if ("Amp" in present) add(JumpChip(stringResource(R.string.chip_amp), "Amp"))
        val hasStudio = "StudioCab" in present
        val hasLive = "LiveCab" in present
        if (hasStudio) add(JumpChip(stringResource(if (hasLive) R.string.chip_studio_cab else R.string.chip_cab), "StudioCab"))
        if (hasLive) add(JumpChip(stringResource(if (hasStudio) R.string.chip_live_cab else R.string.chip_cab), "LiveCab"))
        for (n in 1..4) if ("FX$n" in present) add(JumpChip(stringResource(R.string.chip_fx, n), "FX$n"))
        if (KEY_MIX in present) add(JumpChip(stringResource(R.string.chip_mix), KEY_MIX))
    }

    // "Collapse all" folds every card except the master volume, which is a device setting rather
    // than part of the tone and is always worth having to hand. A different tone starts folded again.
    val foldable = cards.map { it.key }.filter { it != KEY_MASTER }.toSet()
    val collapsed = rememberSaveable(preset, saver = CollapsedSaver) {
        mutableStateOf(if (initiallyCollapsed) foldable else emptySet())
    }
    val allCollapsed = foldable.isNotEmpty() && foldable.all { it in collapsed.value }
    val scope = rememberCoroutineScope()
    val firstState = rememberLazyListState()
    val secondState = rememberLazyListState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(AmpMetallicDark)
    ) {
        val wide = maxWidth >= WIDE_CONTENT_MIN_WIDTH

        // Wide: cards are split in reading order over two columns of about equal height.
        val total = cards.sumOf { it.weight }
        var running = 0
        val splitAt = if (!wide) cards.size else cards.indexOfFirst { c ->
            running += c.weight
            running * 2 >= total
        }.let { if (it < 0) cards.size else (it + 1).coerceAtMost(cards.size) }
        val firstCards = cards.subList(0, splitAt)
        val secondCards = cards.subList(splitAt, cards.size)

        fun jumpTo(key: String) {
            collapsed.value = collapsed.value - key
            val inFirst = firstCards.indexOfFirst { it.key == key }
            if (inFirst >= 0) {
                scope.launch { firstState.animateScrollToItem(inFirst) }
            } else {
                val inSecond = secondCards.indexOfFirst { it.key == key }
                if (inSecond >= 0) scope.launch { secondState.animateScrollToItem(inSecond) }
            }
        }

        val header: @Composable () -> Unit = {
            EditorHeader(
                name = preset.name,
                dirty = dirty,
                link = link,
                status = status,
                chips = chips,
                wide = wide,
                allCollapsed = allCollapsed,
                onToggleCollapseAll = {
                    collapsed.value = if (allCollapsed) collapsed.value - foldable else collapsed.value + foldable
                },
                onChip = ::jumpTo,
                onConnect = onConnect
            )
        }
        val footer: @Composable () -> Unit = {
            EditorFooter(
                wide = wide,
                isConnected = isConnected,
                saveStatus = saveStatus,
                canUndo = canUndo,
                onSave = onSave,
                onUndo = onUndo,
                onDismissSaveStatus = onDismissSaveStatus,
                onOpenPresets = onOpenPresets,
                onReload = onReload,
                onImportTone = onImportTone,
                onExportTone = onExportTone
            )
        }

        if (!wide) {
            Column(Modifier.fillMaxSize()) {
                header()
                CardList(
                    cards = firstCards,
                    collapsed = collapsed,
                    state = firstState,
                    footer = footer,
                    modifier = Modifier.weight(1f).testTag("signal_chain_editor")
                )
            }
        } else {
            Column(Modifier.fillMaxSize().testTag("signal_chain_editor")) {
                header()
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CardList(firstCards, collapsed, firstState, null,
                        Modifier.weight(1f).testTag("editor_column_first"))
                    CardList(secondCards, collapsed, secondState, null,
                        Modifier.weight(1f).testTag("editor_column_second"))
                }
                footer()
            }
        }
    }
}

@Composable
private fun CardList(
    cards: List<EditorCard>,
    collapsed: MutableState<Set<String>>,
    state: LazyListState,
    footer: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = state,
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        items(cards, key = { it.key }) { card ->
            val isCollapsed = card.key in collapsed.value
            SectionCard(
                title = card.title,
                subtitle = card.subtitle,
                onSubtitleClick = card.onSubtitleClick,
                collapsed = isCollapsed,
                onToggleCollapsed = {
                    collapsed.value = if (isCollapsed) collapsed.value - card.key else collapsed.value + card.key
                },
                tag = card.key,
                content = card.content
            )
        }
        if (footer != null) item(key = "@footer") { footer() }
    }
}

/**
 * The part that stays put while the cards scroll: tone name, the MODIFIED marker, connection
 * or status line, and the chips that jump to a card.
 */
@Composable
private fun EditorHeader(
    name: String,
    dirty: Boolean,
    link: LinkStatus,
    status: FriendlyStatus,
    chips: List<JumpChip>,
    wide: Boolean,
    allCollapsed: Boolean,
    onToggleCollapseAll: () -> Unit,
    onChip: (String) -> Unit,
    onConnect: () -> Unit
) {
    val title: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name.ifBlank { stringResource(R.string.editor_live_tone) },
                    style = MaterialTheme.typography.headlineSmall,
                    color = AmpGold,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() }.testTag("tone_title")
                )
                val foldLabel = stringResource(if (allCollapsed) R.string.editor_expand_all else R.string.editor_collapse_all)
                IconButton(onClick = onToggleCollapseAll, modifier = Modifier.testTag("collapse_all")) {
                    Icon(
                        if (allCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                        contentDescription = foldLabel,
                        tint = AmpTextSecondary
                    )
                }
            }
            if (dirty) {
                val modified = stringResource(R.string.editor_modified_description)
                Text(
                    stringResource(R.string.editor_modified),
                    color = AmpOrangeGlow,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .semantics { contentDescription = modified }
                        .testTag("modified_chip")
                )
            }
            StatusLine(link, status, onConnect)
        }
    }

    Surface(color = AmpMetallicDark, modifier = Modifier.fillMaxWidth().testTag("editor_header")) {
        if (wide) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                title(Modifier.weight(1f))
                JumpChips(chips, onChip, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp)) {
                title(Modifier)
                JumpChips(chips, onChip, Modifier)
            }
        }
    }
}

/** Connection banner while the amp is away, otherwise the (friendly) status line. */
@Composable
private fun StatusLine(link: LinkStatus, status: FriendlyStatus, onConnect: () -> Unit) {
    if (!link.isReady) {
        LinkBanner(
            link = link,
            message = stringResource(R.string.editor_banner_disconnected),
            onConnect = onConnect,
            modifier = Modifier.padding(top = 4.dp).testTag("editor_banner")
        )
        return
    }
    if (status.text.isBlank()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        if (status.isBusy) {
            CircularProgressIndicator(
                color = AmpGold, trackColor = AmpSurfaceVariant, strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = status.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.kind == StatusKind.ERROR) AmpRedLED else AmpCyanLight,
            modifier = Modifier.testTag("editor_status")
        )
    }
}

@Composable
private fun JumpChips(chips: List<JumpChip>, onChip: (String) -> Unit, modifier: Modifier) {
    if (chips.isEmpty()) return
    val rowDescription = stringResource(R.string.editor_jump_row)
    LazyRow(
        modifier = modifier.semantics { contentDescription = rowDescription }.testTag("jump_chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(chips, key = { it.cardKey }) { chip ->
            val description = stringResource(R.string.editor_jump_to, chip.label)
            // The visible pill is about 32dp high; the clickable box around it is 48dp.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .clickable(role = Role.Button) { onChip(chip.cardKey) }
                    .semantics { contentDescription = description }
                    .testTag("jump_${chip.cardKey}")
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AmpSurface,
                    border = BorderStroke(1.dp, AmpSurfaceVariant)
                ) {
                    Text(
                        chip.label,
                        color = AmpGold,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorFooter(
    wide: Boolean,
    isConnected: Boolean,
    saveStatus: String?,
    canUndo: Boolean,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onDismissSaveStatus: () -> Unit,
    onOpenPresets: () -> Unit,
    onReload: () -> Unit,
    onImportTone: () -> Unit,
    onExportTone: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (wide) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier
    ) {
        if (saveStatus != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(saveStatus, color = AmpCyanLight, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).testTag("save_status"))
                if (canUndo) TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.editor_undo), color = AmpGold, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismissSaveStatus, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.editor_dismiss), color = AmpTextSecondary)
                }
            }
        }
        val actions: @Composable (Modifier) -> Unit = { mod ->
            // Saving is switched off until the stored-tone format is handled correctly: writing the
            // live tone's layout into a slot makes the amp go silent (see the hardware notes).
            Button(
                onClick = onSave,
                enabled = false,
                modifier = mod.heightIn(min = MinTouchTarget).testTag("save_button"),
                colors = ButtonDefaults.buttonColors(containerColor = AmpGold, contentColor = AmpMetallicDark)
            ) { Text(stringResource(R.string.editor_save_coming_soon), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                actions(Modifier.weight(1f))
                AmpOutlinedButton(stringResource(R.string.editor_browse_tones), onOpenPresets, Modifier.weight(1f))
                AmpOutlinedButton(stringResource(R.string.editor_reload), onReload, Modifier.weight(1f), enabled = isConnected)
            }
            ToneShareRow(isConnected, onImportTone, onExportTone)
        } else {
            actions(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AmpOutlinedButton(stringResource(R.string.editor_browse_tones), onOpenPresets, Modifier.weight(1f))
                AmpOutlinedButton(stringResource(R.string.editor_reload), onReload, Modifier.weight(1f), enabled = isConnected)
            }
            ToneShareRow(isConnected, onImportTone, onExportTone)
        }
    }
}

/** Share a tone as a file, or apply one someone else shared. */
@Composable
private fun ToneShareRow(isConnected: Boolean, onImportTone: () -> Unit, onExportTone: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        AmpOutlinedButton(stringResource(R.string.editor_import_tone), onImportTone,
            Modifier.weight(1f).testTag("import_tone"), enabled = isConnected)
        AmpOutlinedButton(stringResource(R.string.editor_export_tone), onExportTone,
            Modifier.weight(1f).testTag("export_tone"))
    }
}

@Composable
private fun EmptyEditor(
    link: LinkStatus,
    status: FriendlyStatus,
    onOpenPresets: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier
) {
    val base = modifier.background(AmpMetallicDark)
    if (!link.isReady) {
        ConnectPrompt(
            link = link,
            body = stringResource(R.string.editor_disconnected_body),
            detail = status.text.takeIf { it.isNotBlank() },
            onConnect = onConnect,
            modifier = base.testTag("editor_empty")
        )
    } else {
        StateMessage(
            icon = null,
            busy = true,
            title = stringResource(R.string.editor_reading_title),
            body = stringResource(R.string.editor_reading_body),
            detail = status.text.takeIf { it.isNotBlank() },
            modifier = base.testTag("editor_empty"),
            action = { AmpOutlinedButton(stringResource(R.string.editor_browse_tones), onOpenPresets) }
        )
    }
}

/**
 * A card with a collapsible body. The model name (when the block has one) opens the model
 * picker; the chevron folds the card away.
 */
@Composable
private fun SectionCard(
    title: String,
    subtitle: String?,
    onSubtitleClick: (() -> Unit)?,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    tag: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = AmpSurface,
        border = BorderStroke(1.dp, AmpSurfaceVariant),
        modifier = Modifier.fillMaxWidth().testTag("card_$tag")
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp).animateContentSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = AmpGold,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.semantics { heading() }
                    )
                    if (!subtitle.isNullOrBlank()) {
                        val modelDescription = stringResource(R.string.card_model_description, title, subtitle)
                        val changeModel = stringResource(R.string.card_change_model)
                        Text(
                            if (onSubtitleClick != null) "$subtitle  ▾" else subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = AmpCyanLight,
                            modifier = if (onSubtitleClick != null) {
                                Modifier
                                    .heightIn(min = MinTouchTarget)
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .clickable(onClickLabel = changeModel, role = Role.Button) { onSubtitleClick() }
                                    .semantics { contentDescription = modelDescription }
                                    .testTag("model_$title")
                            } else Modifier.semantics { contentDescription = modelDescription }
                        )
                    }
                }
                val toggleLabel = stringResource(if (collapsed) R.string.card_expand else R.string.card_collapse, title)
                IconButton(onClick = onToggleCollapsed, modifier = Modifier.testTag("toggle_$tag")) {
                    Icon(
                        if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = toggleLabel,
                        tint = AmpTextSecondary
                    )
                }
            }
            if (!collapsed) {
                Column(Modifier.padding(end = 8.dp)) {
                    Spacer(Modifier.height(4.dp))
                    content()
                }
            }
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

/** The master volume on the amp's 0 to 11 dial: whole numbers show without a decimal, so 11 reads "11". */
internal fun masterDisplay(volume: Float): String = scaledDisplay(volume, 11f)

/** A 0..1 value shown on a 0..[scale] dial, with a decimal only when it is not a whole number. */
internal fun scaledDisplay(value: Float, scale: Float): String {
    val tenths = Math.round(value.coerceIn(0f, 1f) * scale * 10f)
    return if (tenths % 10 == 0) (tenths / 10).toString() else "%d.%d".format(tenths / 10, tenths % 10)
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
 * The value being dragged is saved, so a rotation in the middle of a drag does not lose it.
 */
@Composable
private fun ParamSlider(
    label: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>?,
    enabled: Boolean,
    key: String,
    display: ((Float) -> String)? = null,
    onCommit: (Float) -> Unit
) {
    var value by rememberSaveable(key) { mutableFloatStateOf(initial) }
    if (range == null) {
        val shown = display?.invoke(initial) ?: "%.2f".format(initial)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = AmpTextSecondary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            Text(shown, color = AmpTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val unit = range.start >= 0f && range.endInclusive <= 1f
    val shown = display?.invoke(value) ?: if (unit) scaledDisplay(value, KNOB_SCALE) else "%.1f".format(value)
    // The side margin keeps the ends of the slider clear of the edge-swipe zone where Android's
    // back gesture lives, so dragging to full or zero cannot dismiss the app.
    Column(Modifier.padding(horizontal = SLIDER_SIDE_MARGIN, vertical = 2.dp)) {
        // The slider below says its own name and value to TalkBack, so this line is visual only.
        Row(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = AmpTextPrimary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            Text(shown, color = AmpTextSecondary, style = MaterialTheme.typography.bodySmall)
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
            modifier = Modifier
                .semantics {
                    contentDescription = label
                    stateDescription = shown
                }
                .testTag("param_$label")
        )
    }
}

/** A whole-row switch: the label and the switch are one 48dp-high control with a light tick on change. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    emphasise: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            }
            // The tag sits on the toggle itself (the row), not on the switch inside it.
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (emphasise && !checked) AmpTextSecondary else AmpTextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasise && checked) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        // The row handles the tap; the switch is just the picture of the state.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = AmpGold, checkedTrackColor = AmpSurfaceVariant)
        )
    }
}

@Composable
private fun BoolRow(label: String, initial: Boolean, enabled: Boolean, key: String, onChange: (Boolean) -> Unit) {
    var on by rememberSaveable(key) { mutableStateOf(initial) }
    SwitchRow(label = label, checked = on, enabled = enabled, tag = "bool_$label") {
        on = it
        onChange(it)
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
    var chosen by rememberSaveable(key) { mutableStateOf(current) }
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val canPick = enabled && options.size > 1
    val action = stringResource(R.string.choice_open_action)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable(enabled = canPick, onClickLabel = action, role = Role.Button) { open = true }
                .semantics(mergeDescendants = true) {}
                .testTag("choice_$label"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = AmpTextPrimary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(
                nameOf(chosen) + if (canPick) "  ▾" else "",
                color = AmpCyanLight,
                style = MaterialTheme.typography.bodyMedium
            )
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
