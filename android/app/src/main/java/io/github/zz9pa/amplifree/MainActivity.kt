package io.github.zz9pa.amplifree

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.zz9pa.amplifree.bluetooth.ToneFile
import io.github.zz9pa.amplifree.ui.AmplifreeUiState
import io.github.zz9pa.amplifree.ui.AmplifreeViewModel
import io.github.zz9pa.amplifree.ui.LinkStatus
import io.github.zz9pa.amplifree.ui.WIDE_LAYOUT_MIN_WIDTH
import io.github.zz9pa.amplifree.ui.friendlyStatus
import io.github.zz9pa.amplifree.ui.components.TopHardwareHeader
import io.github.zz9pa.amplifree.ui.dialogs.ConnectionDialog
import io.github.zz9pa.amplifree.ui.dialogs.SaveToneDialog
import io.github.zz9pa.amplifree.ui.screens.SignalChainEditorScreen
import io.github.zz9pa.amplifree.ui.screens.ToneBrowserScreen
import io.github.zz9pa.amplifree.ui.screens.TunerScreen
import io.github.zz9pa.amplifree.ui.theme.*

class MainActivity : ComponentActivity() {
    private val viewModel: AmplifreeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark only: light system-bar icons keep the clock and battery readable even
        // when the phone itself is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            AmplifreeTheme {
                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    // Permission just arrived (first run): now we can look for the amp.
                    if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                        viewModel.hardwareManager.autoConnect()
                    }
                }

                // BLUETOOTH_CONNECT is needed to talk to the amp (Android 12+). The app asks for
                // nothing else: the tuner uses the amp, not the microphone.
                LaunchedEffect(Unit) {
                    val wanted = mutableListOf<String>()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    val missing = wanted.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                    else viewModel.hardwareManager.autoConnect()
                }

                val hw = viewModel.hardwareManager
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val hardwareState by viewModel.hardwareState.collectAsStateWithLifecycle()
                val tunerState by viewModel.tunerState.collectAsStateWithLifecycle()
                val ready by hw.ready.collectAsStateWithLifecycle()
                val livePreset by hw.livePreset.collectAsStateWithLifecycle()
                val presetRevision by hw.presetRevision.collectAsStateWithLifecycle()
                val ampPresets by hw.ampPresets.collectAsStateWithLifecycle()
                val scanProgress by hw.scanProgress.collectAsStateWithLifecycle()
                val activeTone by hw.activeTone.collectAsStateWithLifecycle()
                val status by hw.status.collectAsStateWithLifecycle()
                val needsLink by hw.needsLink.collectAsStateWithLifecycle()
                val link = LinkStatus.of(hardwareState.connectionState, ready, needsLink)
                val connected = link.isReady
                val friendly = friendlyStatus(status, context.resources)
                val openConnection = { viewModel.toggleConnectionDialog(true) }
                val dirty by hw.dirty.collectAsStateWithLifecycle()
                val saveStatus by hw.saveStatus.collectAsStateWithLifecycle()
                val undoSlot by hw.undoSlot.collectAsStateWithLifecycle()
                val loadedFrom by hw.loadedFrom.collectAsStateWithLifecycle()
                val slotCount by hw.slotCount.collectAsStateWithLifecycle()
                val models by hw.models.collectAsStateWithLifecycle()
                val paramInfo by hw.paramInfo.collectAsStateWithLifecycle()
                val masterVolume by hw.masterVolume.collectAsStateWithLifecycle()
                var showSaveDialog by rememberSaveable { mutableStateOf(false) }

                // Sharing tones: a tone file is saved to / read from wherever the user picks, and a
                // tone read from a file is shown to the user before it replaces the live tone.
                val scope = rememberCoroutineScope()

                // Loading a tone, reloading, or importing replaces the live tone. If it has changes
                // that are not saved on the amp, ask first.
                var pendingDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }
                val confirmDiscard: (() -> Unit) -> Unit = { action ->
                    if (dirty) pendingDiscard = action else action()
                }
                var pendingTone by rememberSaveable { mutableStateOf<String?>(null) }
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri != null) scope.launch {
                        val written = withContext(Dispatchers.IO) {
                            runCatching {
                                val json = hw.exportLiveTone()?.toJson() ?: error("no tone")
                                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                            }.isSuccess
                        }
                        if (!written) Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
                    }
                }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching { context.contentResolver.openInputStream(uri)!!.use { ToneFile.readLimited(it) } }.getOrNull()
                        }
                        when (val r = text?.let { ToneFile.parse(it) }) {
                            is ToneFile.Parsed.Ok -> pendingTone = text
                            is ToneFile.Parsed.Refused -> Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                            null -> Toast.makeText(context, R.string.import_unreadable, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                val screenStates = rememberSaveableStateHolder()

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Wide windows (tablets, phones on their side) get a side rail instead of a
                    // bottom bar, which gives the content more height.
                    val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
                    Scaffold(
                        topBar = {
                            TopHardwareHeader(
                                hardwareState = hardwareState,
                                ready = ready,
                                toneName = activeTone,
                                onOpenConnectionDialog = openConnection
                            )
                        },
                        bottomBar = {
                            if (!wide) {
                                BottomNavigation(
                                    selectedTab = uiState.activeTab,
                                    onSelectTab = { viewModel.selectTab(it) }
                                )
                            }
                        },
                        containerColor = AmpMetallicDark,
                        contentWindowInsets = WindowInsets.safeDrawing
                    ) { innerPadding ->
                        Row(Modifier.fillMaxSize().padding(innerPadding)) {
                            if (wide) {
                                SideNavigation(
                                    selectedTab = uiState.activeTab,
                                    onSelectTab = { viewModel.selectTab(it) }
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                // Each tab keeps its own saved state (scroll position, search text,
                                // slider drags, open pickers) while another tab is showing.
                                screenStates.SaveableStateProvider(uiState.activeTab) {
                                    when (uiState.activeTab) {
                                        AmplifreeUiState.TAB_EDITOR -> SignalChainEditorScreen(
                                            preset = livePreset,
                                            revision = presetRevision,
                                            link = link,
                                            status = friendly,
                                            onConnect = openConnection,
                                            onSetFloat = { b, p, v -> hw.setLiveFloat(b, p, v) },
                                            onSetBool = { b, p, v -> hw.setLiveBool(b, p, v) },
                                            onOpenPresets = { viewModel.selectTab(AmplifreeUiState.TAB_TONES) },
                                            onReload = { confirmDiscard { hw.refreshLiveTone() } },
                                            dirty = dirty,
                                            saveStatus = saveStatus,
                                            canUndo = undoSlot != null,
                                            onSave = { showSaveDialog = true },
                                            onUndo = { hw.undoLastSave() },
                                            onDismissSaveStatus = { hw.clearSaveStatus() },
                                            modelChoices = { blk -> models.size; hw.modelChoices(blk) },
                                            paramInfo = paramInfo,
                                            symbolName = { id -> hw.symbolName(id) },
                                            onSetInt = { b, pm, v -> hw.setLiveInt(b, pm, v) },
                                            masterVolume = masterVolume,
                                            onMasterVolume = { v -> hw.setMasterVolume(v) },
                                            onSwapModel = { b, m -> hw.swapModel(b, m) },
                                            onImportTone = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                                            onExportTone = {
                                                val base = livePreset?.name.orEmpty().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
                                                    .trim().ifEmpty { "tone" }
                                                exportLauncher.launch("$base.json")
                                            }
                                        )
                                        AmplifreeUiState.TAB_TONES -> ToneBrowserScreen(
                                            ampPresets = ampPresets,
                                            activeTone = activeTone,
                                            link = link,
                                            scanProgress = scanProgress,
                                            status = friendly,
                                            onConnect = openConnection,
                                            onRescan = { hw.rescanPresets() },
                                            onLoad = { i, n ->
                                                confirmDiscard {
                                                    hw.loadStoredPreset(i, n)
                                                    viewModel.selectTab(AmplifreeUiState.TAB_EDITOR)
                                                }
                                            }
                                        )
                                        AmplifreeUiState.TAB_TUNER -> TunerScreen(
                                            tunerState = tunerState,
                                            tunerEngine = viewModel.tunerEngine,
                                            link = link,
                                            status = friendly,
                                            onConnect = openConnection
                                        )
                                    }
                                }

                                if (showSaveDialog) {
                                    SaveToneDialog(
                                        currentName = livePreset?.name.orEmpty(),
                                        suggestedSlot = loadedFrom,
                                        slotCount = slotCount,
                                        existing = ampPresets,
                                        onSave = { slot, name -> hw.saveLiveToSlot(slot, name); showSaveDialog = false },
                                        onDismiss = { showSaveDialog = false }
                                    )
                                }

                                pendingDiscard?.let { action ->
                                    AlertDialog(
                                        onDismissRequest = { pendingDiscard = null },
                                        title = { Text(stringResource(R.string.discard_title)) },
                                        text = { Text(stringResource(R.string.discard_body, livePreset?.name.orEmpty().ifBlank { "This tone" })) },
                                        confirmButton = {
                                            TextButton(onClick = { pendingDiscard = null; action() }) {
                                                Text(stringResource(R.string.discard_confirm), fontWeight = FontWeight.Bold)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { pendingDiscard = null }) { Text(stringResource(R.string.import_cancel)) }
                                        }
                                    )
                                }

                                (pendingTone?.let { ToneFile.parse(it) } as? ToneFile.Parsed.Ok)?.let { ok ->
                                    AlertDialog(
                                        onDismissRequest = { pendingTone = null },
                                        title = { Text(stringResource(R.string.import_title)) },
                                        text = {
                                            Text(stringResource(if (dirty) R.string.import_body_dirty else R.string.import_body, ok.tone.name))
                                        },
                                        confirmButton = {
                                            TextButton(onClick = { hw.importTone(ok.tone); pendingTone = null }) {
                                                Text(stringResource(R.string.import_apply), fontWeight = FontWeight.Bold)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { pendingTone = null }) { Text(stringResource(R.string.import_cancel)) }
                                        }
                                    )
                                }

                                if (uiState.isConnectionDialogVisible) {
                                    ConnectionDialog(
                                        hardwareState = hardwareState,
                                        hardwareManager = hw,
                                        onDismiss = { viewModel.toggleConnectionDialog(false) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class NavItem(val labelRes: Int, val icon: ImageVector, val index: Int)

private val NAV_ITEMS = listOf(
    NavItem(R.string.tab_editor, Icons.Default.Equalizer, AmplifreeUiState.TAB_EDITOR),
    NavItem(R.string.tab_tones, Icons.Default.LibraryMusic, AmplifreeUiState.TAB_TONES),
    NavItem(R.string.tab_tuner, Icons.Default.Tune, AmplifreeUiState.TAB_TUNER)
)

@Composable
internal fun BottomNavigation(selectedTab: Int, onSelectTab: (Int) -> Unit) {
    NavigationBar(
        containerColor = AmpSurface,
        contentColor = AmpGold,
        windowInsets = WindowInsets.navigationBars
    ) {
        NAV_ITEMS.forEach { item ->
            val selected = selectedTab == item.index
            val label = stringResource(item.labelRes)
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(item.index) },
                // The icon is decorative: the visible label already names the tab, so describing
                // the icon too would make TalkBack say it twice.
                icon = { Icon(item.icon, contentDescription = null, tint = if (selected) AmpGold else AmpTextSecondary) },
                label = {
                    Text(label,
                        color = if (selected) AmpGold else AmpTextSecondary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp)
                },
                colors = NavigationBarItemDefaults.colors(indicatorColor = AmpSurfaceVariant),
                modifier = Modifier.testTag("nav_tab_${item.index}")
            )
        }
    }
}

/** The same three tabs as [BottomNavigation], as a rail down the left edge for wide windows. */
@Composable
internal fun SideNavigation(selectedTab: Int, onSelectTab: (Int) -> Unit) {
    NavigationRail(containerColor = AmpSurface, contentColor = AmpGold) {
        Spacer(Modifier.weight(1f))
        NAV_ITEMS.forEach { item ->
            val selected = selectedTab == item.index
            val label = stringResource(item.labelRes)
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(item.index) },
                icon = { Icon(item.icon, contentDescription = null, tint = if (selected) AmpGold else AmpTextSecondary) },
                label = {
                    Text(label,
                        color = if (selected) AmpGold else AmpTextSecondary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp)
                },
                colors = NavigationRailItemDefaults.colors(indicatorColor = AmpSurfaceVariant),
                modifier = Modifier.testTag("nav_tab_${item.index}")
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
