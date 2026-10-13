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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zz9pa.amplifree.data.ConnectionState
import io.github.zz9pa.amplifree.ui.AmplifreeUiState
import io.github.zz9pa.amplifree.ui.AmplifreeViewModel
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
                val connected = hardwareState.connectionState == ConnectionState.CONNECTED && ready
                val dirty by hw.dirty.collectAsStateWithLifecycle()
                val saveStatus by hw.saveStatus.collectAsStateWithLifecycle()
                val undoSlot by hw.undoSlot.collectAsStateWithLifecycle()
                val loadedFrom by hw.loadedFrom.collectAsStateWithLifecycle()
                val slotCount by hw.slotCount.collectAsStateWithLifecycle()
                val models by hw.models.collectAsStateWithLifecycle()
                val paramInfo by hw.paramInfo.collectAsStateWithLifecycle()
                val masterVolume by hw.masterVolume.collectAsStateWithLifecycle()
                var showSaveDialog by remember { mutableStateOf(false) }

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
                        BottomNavigation(
                            selectedTab = uiState.activeTab,
                            onSelectTab = { viewModel.selectTab(it) }
                        )
                    },
                    containerColor = AmpMetallicDark,
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding)) {
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

private data class NavItem(val label: String, val icon: ImageVector, val index: Int)

@Composable
private fun BottomNavigation(selectedTab: Int, onSelectTab: (Int) -> Unit) {
    val items = listOf(
        NavItem("Editor", Icons.Default.Equalizer, AmplifreeUiState.TAB_EDITOR),
        NavItem("Tones", Icons.Default.LibraryMusic, AmplifreeUiState.TAB_TONES),
        NavItem("Tuner", Icons.Default.Tune, AmplifreeUiState.TAB_TUNER)
    )
    NavigationBar(
        containerColor = AmpSurface,
        contentColor = AmpGold,
        windowInsets = WindowInsets.navigationBars
    ) {
        items.forEach { item ->
            val selected = selectedTab == item.index
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(item.index) },
                icon = {
                    Icon(item.icon, contentDescription = item.label,
                        tint = if (selected) AmpGold else AmpTextSecondary)
                },
                label = {
                    Text(item.label,
                        color = if (selected) AmpGold else AmpTextSecondary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp)
                },
                colors = NavigationBarItemDefaults.colors(indicatorColor = AmpSurfaceVariant),
                modifier = Modifier.testTag("nav_tab_${item.index}")
            )
        }
    }
}
