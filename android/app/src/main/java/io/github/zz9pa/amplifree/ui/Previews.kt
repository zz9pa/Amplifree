package io.github.zz9pa.amplifree.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.data.ConnectionState
import io.github.zz9pa.amplifree.data.HardwareDevice
import io.github.zz9pa.amplifree.data.HardwareState
import io.github.zz9pa.amplifree.data.TunerEngine
import io.github.zz9pa.amplifree.data.TunerState
import io.github.zz9pa.amplifree.ui.components.TopHardwareHeader
import io.github.zz9pa.amplifree.ui.screens.SignalChainEditorScreen
import io.github.zz9pa.amplifree.ui.screens.ToneBrowserScreen
import io.github.zz9pa.amplifree.ui.screens.TunerScreen
import io.github.zz9pa.amplifree.ui.theme.AmpMetallicDark
import io.github.zz9pa.amplifree.ui.theme.AmplifreeTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Android Studio previews of the main screens. They use made-up data only (a tone built here
 * from the documented record layout, invented tone names), so nothing needs an amp.
 */

/** Phone, phone with the largest font setting, and a wide window (tablet or phone on its side). */
@Preview(name = "Phone", widthDp = 393, heightDp = 820)
@Preview(name = "Large font", widthDp = 393, heightDp = 820, fontScale = 2f)
@Preview(name = "Wide", widthDp = 900, heightDp = 420)
private annotation class ScreenPreviews

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    AmplifreeTheme {
        Surface(color = AmpMetallicDark, modifier = Modifier.fillMaxSize(), content = content)
    }
}

/** Builds a small tone with the real record layout; the names are invented for the previews. */
private object PreviewData {
    private val symbols = listOf(
        "GuitarProc", "FX1EnableState", "CabEnableState", "FX1MixState",
        "Amp", "DriveState", "BassState", "TrebleState",
        "StudioCab", "MicState", "FX1", "DepthState", "FX2", "RateState",
        "ModelA", "ModelB", "ModelC", "ModelD"
    )

    private fun id(name: String) = symbols.indexOf(name)

    fun preset(): PresetCodec.Preset {
        val bb = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun rec(key: Int, type: Int, value: Int) { bb.putInt(key); bb.putShort(type.toShort()); bb.putInt(value) }
        fun f(name: String, v: Float) { bb.putInt(id(name)); bb.putShort(PresetCodec.TYPE_FLOAT.toShort()); bb.putFloat(v) }
        fun end() { bb.putInt(PresetCodec.KEY_SECTION_END); bb.putShort(0) }
        fun block(name: String, flow: String?, params: Int) {
            rec(PresetCodec.KEY_BLOCK, PresetCodec.TYPE_ENUM, id(name))
            if (flow != null) rec(PresetCodec.KEY_FLOW, PresetCodec.TYPE_ENUM, id(flow))
            rec(PresetCodec.KEY_PARAM_COUNT, PresetCodec.TYPE_INT, params)
        }

        rec(PresetCodec.KEY_SECTION, PresetCodec.TYPE_INT, PresetCodec.SECTION_PS)
        rec(PresetCodec.KEY_MAGIC, PresetCodec.TYPE_INT, 0)
        val name = "Preview Tone\u0000".toByteArray(Charsets.US_ASCII)
        bb.putInt(0); bb.putShort(PresetCodec.TYPE_STRING.toShort()); bb.putInt(name.size); bb.put(name)
        end()

        rec(PresetCodec.KEY_SECTION, PresetCodec.TYPE_INT, PresetCodec.SECTION_DSP)
        rec(PresetCodec.KEY_MAGIC, PresetCodec.TYPE_INT, 0)
        block("GuitarProc", null, 3)
        rec(id("FX1EnableState"), PresetCodec.TYPE_BOOL, 1)
        rec(id("CabEnableState"), PresetCodec.TYPE_BOOL, 1)
        f("FX1MixState", 0.4f)
        block("Amp", "ModelA", 3)
        f("DriveState", 0.62f); f("BassState", 0.5f); f("TrebleState", 0.35f)
        block("StudioCab", "ModelB", 1)
        f("MicState", 0.2f)
        block("FX1", "ModelC", 1)
        f("DepthState", 0.75f)
        block("FX2", "ModelD", 1)
        f("RateState", 0.3f)
        end()
        return PresetCodec.decode(bb.array().copyOf(bb.position()), symbols)
    }
}

private val previewTones = listOf(0 to "Clean Sparkle", 1 to "Crunch Rhythm", 2 to "Lead Boost", 3 to "Ambient Wash")

@ScreenPreviews
@Composable
private fun EditorConnectedPreview() = PreviewFrame {
    SignalChainEditorScreen(
        preset = PreviewData.preset(), revision = 0, link = LinkStatus.READY,
        status = FriendlyStatus("Connected and ready.", StatusKind.INFO),
        onSetFloat = { _, _, _ -> }, onSetBool = { _, _, _ -> }, onOpenPresets = {}, onReload = {},
        dirty = true, masterVolume = 0.6f,
        modelChoices = { listOf("ModelA", "ModelB") }
    )
}

@Preview(name = "Editor, no amp", widthDp = 393, heightDp = 820)
@Composable
private fun EditorDisconnectedPreview() = PreviewFrame {
    SignalChainEditorScreen(
        preset = null, revision = 0, link = LinkStatus.DISCONNECTED,
        status = FriendlyStatus("Not connected to your amp.", StatusKind.INFO),
        onSetFloat = { _, _, _ -> }, onSetBool = { _, _, _ -> }, onOpenPresets = {}, onReload = {}
    )
}

@Preview(name = "Editor, stale tone", widthDp = 393, heightDp = 820)
@Composable
private fun EditorStalePreview() = PreviewFrame {
    SignalChainEditorScreen(
        preset = PreviewData.preset(), revision = 0, link = LinkStatus.DISCONNECTED,
        status = FriendlyStatus("", StatusKind.INFO),
        onSetFloat = { _, _, _ -> }, onSetBool = { _, _, _ -> }, onOpenPresets = {}, onReload = {}
    )
}

@ScreenPreviews
@Composable
private fun TonesPreview() = PreviewFrame {
    ToneBrowserScreen(
        ampPresets = previewTones, activeTone = "Crunch Rhythm", link = LinkStatus.READY,
        scanProgress = null, status = FriendlyStatus("", StatusKind.INFO),
        onRescan = {}, onLoad = { _, _ -> }, onConnect = {}
    )
}

@Preview(name = "Tones, scanning", widthDp = 393, heightDp = 820)
@Composable
private fun TonesScanningPreview() = PreviewFrame {
    ToneBrowserScreen(
        ampPresets = previewTones.take(2), activeTone = null, link = LinkStatus.READY,
        scanProgress = 2 to 40, status = FriendlyStatus("", StatusKind.INFO),
        onRescan = {}, onLoad = { _, _ -> }, onConnect = {}
    )
}

@Preview(name = "Tones, empty", widthDp = 393, heightDp = 820)
@Composable
private fun TonesEmptyPreview() = PreviewFrame {
    ToneBrowserScreen(
        ampPresets = emptyList(), activeTone = null, link = LinkStatus.READY,
        scanProgress = null, status = FriendlyStatus("", StatusKind.INFO),
        onRescan = {}, onLoad = { _, _ -> }, onConnect = {}
    )
}

@Preview(name = "Tones, no amp", widthDp = 393, heightDp = 820)
@Composable
private fun TonesDisconnectedPreview() = PreviewFrame {
    ToneBrowserScreen(
        ampPresets = emptyList(), activeTone = null, link = LinkStatus.DISCONNECTED,
        scanProgress = null, status = FriendlyStatus("Not connected to your amp.", StatusKind.INFO),
        onRescan = {}, onLoad = { _, _ -> }, onConnect = {}
    )
}

@ScreenPreviews
@Composable
private fun TunerPreview() = PreviewFrame {
    TunerScreen(
        tunerState = TunerState(
            isTunerActive = true, noteName = "A", octave = 2, currentFrequency = 109.2f,
            centsOffset = -12f, signalDetected = true, activeStringIndex = 1
        ),
        tunerEngine = TunerEngine(), link = LinkStatus.READY,
        status = FriendlyStatus("", StatusKind.INFO), onConnect = {}
    )
}

@Preview(name = "Tuner, no amp", widthDp = 393, heightDp = 820)
@Composable
private fun TunerDisconnectedPreview() = PreviewFrame {
    TunerScreen(
        tunerState = TunerState(), tunerEngine = TunerEngine(), link = LinkStatus.NEEDS_LINK,
        status = FriendlyStatus("Connect your amp in Bluetooth settings.", StatusKind.INFO), onConnect = {}
    )
}

@Preview(name = "Header", widthDp = 393)
@Composable
private fun HeaderPreview() = PreviewFrame {
    TopHardwareHeader(
        hardwareState = HardwareState(
            connectionState = ConnectionState.CONNECTED,
            connectedDevice = HardwareDevice("Preview Amp", "preview", "00:00:00:00:00:00")
        ),
        ready = true, toneName = "Crunch Rhythm", onOpenConnectionDialog = {}
    )
}
