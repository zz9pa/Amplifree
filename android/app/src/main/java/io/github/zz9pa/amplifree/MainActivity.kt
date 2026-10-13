package io.github.zz9pa.amplifree

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zz9pa.amplifree.ui.AmplifreeUiState
import io.github.zz9pa.amplifree.ui.AmplifreeViewModel
import io.github.zz9pa.amplifree.ui.LinkStatus
import io.github.zz9pa.amplifree.ui.components.TopHardwareHeader
import io.github.zz9pa.amplifree.ui.dialogs.ConnectionDialog
import io.github.zz9pa.amplifree.ui.dialogs.SaveToneDialog
import io.github.zz9pa.amplifree.ui.screens.SignalChainEditorScreen
import io.github.zz9pa.amplifree.ui.screens.ToneBrowserScreen
import io.github.zz9pa.amplifree.ui.screens.TunerScreen
import io.github.zz9pa.amplifree.ui.theme.*

/** Window width from which the app switches to its wide layout (side rail, two-column editor). */
val WIDE_LAYOUT_MIN_WIDTH: Dp = 720.dp

class MainActivity : ComponentActivity() {
    private val viewModel: AmplifreeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                val dirty by hw.dirty.collectAsStateWithLifecycle()
                val saveStatus by hw.saveStatus.collectAsStateWithLifecycle()
                val undoSlot by hw.undoSlot.collectAsStateWithLifecycle()
                val loadedFrom by hw.loadedFrom.collectAsStateWithLifecycle()
                val slotCount by hw.slotCount.collectAsStateWithLifecycle()
                val models by hw.models.collectAsStateWithLifecycle()
                val paramInfo by hw.paramInfo.collectAsStateWithLifecycle()
                val masterVolume by hw.masterVolume.collectAsStateWithLifecycle()
                var showSaveDialog by rememberSaveable { mutableStateOf(false) }
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
                                onOpenConnectionDialog = { viewModel.toggleConnectionDialog(true) }
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
                                            isConnected = connected,
                                            statusText = status,
                                            onSetFloat = { b, p, v -> hw.setLiveFloat(b, p, v) },
                                            onSetBool = { b, p, v -> hw.setLiveBool(b, p, v) },
                                            onOpenPresets = { viewModel.selectTab(AmplifreeUiState.TAB_TONES) },
                                            onReload = { hw.refreshLiveTone() },
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
                                            onSwapModel = { b, m -> hw.swapModel(b, m) }
                                        )
                                        AmplifreeUiState.TAB_TONES -> ToneBrowserScreen(
                                            ampPresets = ampPresets,
                                            activeTone = activeTone,
                                            isReady = connected,
                                            scanProgress = scanProgress,
                                            statusText = status,
                                            onRescan = { hw.rescanPresets() },
                                            onLoad = { i, n ->
                                                hw.loadStoredPreset(i, n)
                                                viewModel.selectTab(AmplifreeUiState.TAB_EDITOR)
                                            }
                                        )
                                        AmplifreeUiState.TAB_TUNER -> TunerScreen(
                                            tunerState = tunerState,
                                            tunerEngine = viewModel.tunerEngine,
                                            ampReady = connected
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
