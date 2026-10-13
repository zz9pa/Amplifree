package io.github.zz9pa.amplifree.data

import android.content.Context
import io.github.zz9pa.amplifree.bluetooth.AmplifiBluetoothManager
import io.github.zz9pa.amplifree.bluetooth.DebugBridge
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTED
}

data class HardwareDevice(
    val name: String,
    val model: String,
    val macAddress: String
)

data class HardwareState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedDevice: HardwareDevice? = null,
    val masterVolume: Float = 6.5f,
    val guitarVolume: Float = 7.0f,
    val bluetoothAudioConnected: Boolean = false,
    val firmwareUpToDate: Boolean = true
)

/** What the UI sees of the amp. All amp-specific data comes from the connected amp itself. */
class HardwareManager(context: Context) {
    private val bt = AmplifiBluetoothManager(context)

    init { DebugBridge.manager = bt }

    val state: StateFlow<HardwareState> = bt.hardwareState

    /** Human-readable progress / last problem. */
    val status: StateFlow<String> = bt.status

    /** True once the amp session is initialised and commands can be sent. */
    val ready: StateFlow<Boolean> = bt.ready

    /** Name of the tone live on the amp. */
    val activeTone: StateFlow<String?> = bt.activeTone

    /** The tone live on the amp, decoded and editable. */
    val livePreset: StateFlow<PresetCodec.Preset?> = bt.livePreset

    /** Increments on every parameter edit so the UI recomposes. */
    val presetRevision: StateFlow<Int> = bt.presetRevision

    /** Presets stored on the amp: index -> name. */
    val ampPresets: StateFlow<List<Pair<Int, String>>> = bt.ampPresets

    /** (done, total) while the preset list is being read, else null. */
    val scanProgress: StateFlow<Pair<Int, Int>?> = bt.scanProgress

    /** True while the amp's own tuner is engaged. */
    val ampTunerEngaged: StateFlow<Boolean> = bt.ampTunerEngaged

    /** Pitch from the amp's own tuner in Hz, or null when no note is detected. */
    val tunerHz: StateFlow<Float?> = bt.tunerHz

    val availableDevices: List<HardwareDevice>
        get() = bt.getDiscoveredDevices()

    fun connectDevice(device: HardwareDevice) = bt.connectToDevice(device)
    fun disconnect() = bt.disconnect()

    /** Make one of the amp's stored presets the live tone and show it in the editor. */
    fun loadStoredPreset(index: Int, label: String? = null) = bt.loadStoredPreset(index, label)

    /** Re-read the live tone (e.g. after changing it with the amp's own controls). */
    fun refreshLiveTone() = bt.refreshLiveTone()

    /** Download the preset names from the amp again. */
    fun rescanPresets() = bt.rescanPresets()

    /** Change one parameter on the amp. Names are those used inside a preset. */
    fun setLiveFloat(block: String, param: String, value: Float) =
        bt.setLiveParam(block, param, value = value)

    fun setLiveBool(block: String, param: String, value: Boolean) =
        bt.setLiveParam(block, param, bool = value)

    fun startAmpTuner() = bt.startAmpTuner()
    fun stopAmpTuner() = bt.stopAmpTuner()

    fun setMasterVolume(vol: Float) = bt.setMasterVolume(vol)
    fun setGuitarVolume(vol: Float) = bt.setGuitarVolume(vol)
}
