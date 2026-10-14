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

    init { if (io.github.zz9pa.amplifree.BuildConfig.DEBUG) DebugBridge.manager = bt }

    /** Release the amp connection and everything behind it; call when the owner is cleared. */
    fun close() {
        DebugBridge.manager = null
        bt.close()
    }

    val state: StateFlow<HardwareState> = bt.hardwareState

    /** Human-readable progress / last problem. */
    val status: StateFlow<String> = bt.status

    /** True once the amp session is initialised and commands can be sent. */
    val ready: StateFlow<Boolean> = bt.ready

    /** The amp's firmware version (for example "2.60.0") once the session has opened, else null. */
    val firmwareVersion: StateFlow<String?> = bt.firmwareVersion

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

    /** Note from the amp's own tuner as semitones above A (modulo 12), or null when no note is played. */
    val tunerNote: StateFlow<Float?> = bt.tunerNote

    val availableDevices: List<HardwareDevice>
        get() = bt.getDiscoveredDevices()

    /** True while the app is waiting for the user to connect the amp in Android's Bluetooth settings. */
    val needsLink: StateFlow<Boolean> = bt.needsLink

    /** Whether Android currently lists this amp as connected. */
    fun isLinked(device: HardwareDevice) = bt.isLinked(device.macAddress)

    fun connectDevice(device: HardwareDevice) = bt.connectToDevice(device)
    fun disconnect() = bt.disconnect()

    /** Reconnect to the amp used last time. Call once the Bluetooth permission is granted. */
    fun autoConnect() = bt.autoConnect()

    /** Make one of the amp's stored presets the live tone and show it in the editor. */
    fun loadStoredPreset(index: Int, label: String? = null) = bt.loadStoredPreset(index, label)

    /** Re-read the live tone (e.g. after changing it with the amp's own controls). */
    fun refreshLiveTone() = bt.refreshLiveTone()

    /** True once the tone has been edited here and not yet saved. */
    val dirty: StateFlow<Boolean> = bt.dirty

    /** Stored slot the current tone came from, when known. */
    val loadedFrom: StateFlow<Int?> = bt.loadedFrom

    val slotCount: StateFlow<Int> = bt.slotCount

    /** Progress / outcome of the last save, or null. */
    val saveStatus: StateFlow<String?> = bt.saveStatus

    /** Slot whose previous contents can be put back, or null. */
    val undoSlot: StateFlow<Int?> = bt.undoSlot

    fun saveLiveToSlot(index: Int, name: String) = bt.saveLiveToSlot(index, name)
    fun undoLastSave() = bt.undoLastSave()
    fun renameStoredTone(index: Int, name: String) = bt.renameStoredTone(index, name)
    fun clearSaveStatus() = bt.clearSaveStatus()

    /** Models each block can hold, learned from the amp's own tones. */
    val models: StateFlow<Map<String, List<String>>> = bt.models

    fun swapModel(block: String, model: String) = bt.swapModel(block, model)

    /** Ranges and choices learned from the amp's tones, keyed "<model>.<param>". */
    val paramInfo: StateFlow<Map<String, io.github.zz9pa.amplifree.bluetooth.ParamInfo>> = bt.paramInfo
    fun symbolName(id: Int): String = bt.symbolName(id)
    fun setLiveInt(block: String, param: String, value: Int) = bt.setLiveParam(block, param, int = value)
    fun modelChoices(block: String): List<String> = bt.modelChoices(block)

    /** The live tone as a shareable file, or null before it has been read. */
    fun exportLiveTone() = bt.exportLiveTone()

    /** Apply a tone file to the live tone (stored tones are untouched). */
    fun importTone(file: io.github.zz9pa.amplifree.bluetooth.ToneFile) = bt.importTone(file)

    /** Download the preset names from the amp again. */
    fun rescanPresets() = bt.rescanPresets()

    /** Change one parameter on the amp. Names are those used inside a preset. */
    fun setLiveFloat(block: String, param: String, value: Float) =
        bt.setLiveParam(block, param, value = value)

    fun setLiveBool(block: String, param: String, value: Boolean) =
        bt.setLiveParam(block, param, bool = value)

    fun startAmpTuner() = bt.startAmpTuner()
    fun stopAmpTuner() = bt.stopAmpTuner()

    /** The amp's master volume, 0..1, or null until read. */
    val masterVolume: StateFlow<Float?> = bt.masterVolume
    fun setMasterVolume(vol: Float) = bt.setMasterVolume(vol)
    fun setGuitarVolume(vol: Float) = bt.setGuitarVolume(vol)
}
