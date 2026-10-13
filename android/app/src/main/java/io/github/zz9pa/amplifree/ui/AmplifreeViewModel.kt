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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
     * The tuner prefers the amp's own pitch detector (it hears the guitar signal itself,
     * not the room). If the amp is not connected, or its tuner does not engage, the phone
     * microphone is used instead.
     */
    private fun onTunerVisible(visible: Boolean) {
        tunerJob?.cancel()
        if (!visible) {
            hardwareManager.stopAmpTuner()
            tunerEngine.toggleTuner(false)
            return
        }
        tunerJob = viewModelScope.launch {
            if (hardwareManager.ready.value) {
                hardwareManager.startAmpTuner()
                val engaged = withTimeoutOrNull(3_000) { hardwareManager.ampTunerEngaged.first { it } }
                if (engaged == true) {
                    tunerEngine.toggleTuner(false)
                    hardwareManager.tunerHz.collect { tunerEngine.publishExternal(it) }
                    return@launch
                }
            }
            tunerEngine.toggleTuner(true)
        }
    }
}
