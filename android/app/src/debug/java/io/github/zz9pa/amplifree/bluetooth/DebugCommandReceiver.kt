package io.github.zz9pa.amplifree.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Debug builds only: drive the amp session from adb instead of tapping through the UI.
 * Send as an EXPLICIT broadcast (Android drops implicit ones to manifest receivers):
 *
 *   adb shell am broadcast -n <pkg>/io.github.zz9pa.amplifree.bluetooth.DebugCommandReceiver \
 *       -a io.github.zz9pa.amplifree.AMPDBG --es cmd <cmd> [--ei arg N] [--es s TEXT] [--ef f 0.5]
 *
 *   connect                  connect to the first bonded amp
 *   disconnect
 *   live                     re-read the live tone
 *   load  --ei arg N         load stored preset N
 *   rescan                   re-download preset names
 *   hwload --ei arg N        experimental: ask the amp to load preset N itself
 *   param --es s Block.Param --ef f 0.5     float edit, e.g. s=Amp.DriveState
 *   switch --es s Block.Param --ei arg 1    bool edit, e.g. s=GuitarProc.FX1EnableState
 *   tuner --ei arg 1|0       amp tuner on/off
 *   symbols --es s /data/local/tmp/table.bin  seed the symbol-table cache from a file
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mgr = DebugBridge.manager
        if (mgr == null) { Log.w(TAG, "DBG: no manager bound yet"); return }
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getIntExtra("arg", 0)
        val s = intent.getStringExtra("s") ?: ""
        Log.i(TAG, "DBG cmd=$cmd arg=$arg s=$s")

        when (cmd) {
            "connect" -> mgr.getDiscoveredDevices().firstOrNull()?.let { mgr.connectToDevice(it) }
                ?: Log.w(TAG, "DBG: no bonded amp found")
            "disconnect" -> mgr.disconnect()
            "live" -> mgr.refreshLiveTone()
            "load" -> mgr.loadStoredPreset(arg)
            "rescan" -> mgr.rescanPresets()
            "hwload" -> mgr.experimentalHardwareLoad(arg)
            "param", "switch" -> {
                val (block, param) = s.split(".", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if (cmd == "param") mgr.setLiveParam(block, param, value = intent.getFloatExtra("f", 0f))
                else mgr.setLiveParam(block, param, bool = arg != 0)
            }
            "model" -> {
                val (block, model) = s.split(".", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                mgr.swapModel(block, model)
            }
            "tunerprobe" -> mgr.probeTuner(if (arg > 0) arg else 10)
            "tuner" -> if (arg != 0) mgr.startAmpTuner() else mgr.stopAmpTuner()
            "symbols" -> {
                val dev = mgr.getDiscoveredDevices().firstOrNull()
                val f = File(s)
                if (dev == null || !f.exists()) Log.w(TAG, "DBG: need a bonded amp and an existing file")
                else Log.i(TAG, "DBG: symbol table import ok=${mgr.importSymbolTable(dev.macAddress, f.readBytes())}")
            }
            else -> Log.w(TAG, "DBG: unknown cmd $cmd")
        }
    }

    private companion object { const val TAG = "AmplifiBT" }
}
