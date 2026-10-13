package io.github.zz9pa.amplifree.bluetooth

/**
 * Lets debug builds reach the live manager. The adb-driven receiver that uses it
 * exists only in the `debug` source set, so release builds ship no exported
 * broadcast receiver.
 */
object DebugBridge {
    @Volatile var manager: AmplifiBluetoothManager? = null
}
