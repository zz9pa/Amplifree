package io.github.zz9pa.amplifree.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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

class AmplifreeViewModel(application: Application) : AndroidViewModel(application) {
    val hardwareManager = HardwareManager(application.applicationContext)
    val tunerEngine = TunerEngine()

    private val _uiState = MutableStateFlow(AmplifreeUiState())
    val uiState: StateFlow<AmplifreeUiState> = _uiState.asStateFlow()

    val hardwareState = hardwareManager.state
    val tunerState = tunerEngine.state

    private var tunerJob: Job? = null

    fun selectTab(tabIndex: Int) {
        _uiState.update { it.copy(activeTab = tabIndex) }
        onTunerVisible(tabIndex == AmplifreeUiState.TAB_TUNER)
    }

    fun toggleConnectionDialog(show: Boolean) {
        _uiState.update { it.copy(isConnectionDialogVisible = show) }
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

    override fun onCleared() {
        hardwareManager.stopAmpTuner()
        super.onCleared()
    }
}
