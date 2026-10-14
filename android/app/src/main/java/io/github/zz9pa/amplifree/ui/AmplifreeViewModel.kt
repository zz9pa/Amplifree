package io.github.zz9pa.amplifree.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.zz9pa.amplifree.data.HardwareDevice
import io.github.zz9pa.amplifree.data.HardwareManager
import io.github.zz9pa.amplifree.data.TunerEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AmplifreeUiState(
    val activeTab: Int = TAB_EDITOR,
    val isConnectionDialogVisible: Boolean = false
) {
    companion object {
        const val TAB_EDITOR = 0
        const val TAB_TONES = 1
        const val TAB_TUNER = 2
    }
}

class AmplifreeViewModel(
    application: Application,
    private val saved: SavedStateHandle
) : AndroidViewModel(application) {
    val hardwareManager = HardwareManager(application.applicationContext)
    val tunerEngine = TunerEngine()

    // The selected tab and the connection dialog are kept in the saved state, so they also
    // come back if Android recreates the process, not just on rotation.
    private val _uiState = MutableStateFlow(
        AmplifreeUiState(
            activeTab = saved.get<Int>(KEY_TAB)?.takeIf { it in AmplifreeUiState.TAB_EDITOR..AmplifreeUiState.TAB_TUNER }
                ?: AmplifreeUiState.TAB_EDITOR,
            isConnectionDialogVisible = saved.get<Boolean>(KEY_DIALOG) ?: false
        )
    )
    val uiState: StateFlow<AmplifreeUiState> = _uiState.asStateFlow()

    val hardwareState = hardwareManager.state
    val tunerState = tunerEngine.state

    private var tunerJob: Job? = null

    init {
        // Restored on the tuner tab: start the tuner exactly as selecting the tab would.
        if (_uiState.value.activeTab == AmplifreeUiState.TAB_TUNER) onTunerVisible(true)
    }

    fun selectTab(tabIndex: Int) {
        _uiState.update { it.copy(activeTab = tabIndex) }
        saved[KEY_TAB] = tabIndex
        onTunerVisible(tabIndex == AmplifreeUiState.TAB_TUNER)
    }

    fun toggleConnectionDialog(show: Boolean) {
        _uiState.update { it.copy(isConnectionDialogVisible = show) }
        saved[KEY_DIALOG] = show
    }

    fun connectToHardwareDevice(device: HardwareDevice) = hardwareManager.connectDevice(device)

    /**
     * The tuner is the amp's own: it hears the guitar signal directly, so nothing here touches the
     * phone's microphone. It runs only while the Tuner tab is showing and the amp is connected.
     */
    private fun onTunerVisible(visible: Boolean) {
        tunerJob?.cancel()
        tunerEngine.setActive(false)
        if (!visible) { hardwareManager.stopAmpTuner(); return }
        tunerJob = viewModelScope.launch {
            hardwareManager.ready.collectLatest { ready ->
                if (!ready) { tunerEngine.setActive(false); return@collectLatest }
                hardwareManager.startAmpTuner()
                tunerEngine.setActive(true)
                hardwareManager.tunerNote.collect { tunerEngine.publishNote(it) }
            }
        }
    }

    private companion object {
        const val KEY_TAB = "activeTab"
        const val KEY_DIALOG = "connectionDialog"
    }

    override fun onCleared() {
        hardwareManager.close()          // switches the amp's tuner off and closes the link
        super.onCleared()
    }
}
