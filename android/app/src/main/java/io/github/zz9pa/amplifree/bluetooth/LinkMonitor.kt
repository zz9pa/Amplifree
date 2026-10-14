package io.github.zz9pa.amplifree.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager as SysBluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How long to wait between automatic reconnect attempts. */
object ReconnectPolicy {
    private val STEPS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    const val MAX_ATTEMPTS = 5

    /** Delay before retry number [attempt] (0-based); null once we should give up. */
    fun delayMs(attempt: Int): Long? = STEPS_MS.getOrNull(attempt)
}

/** What to do after one attempt to open a session; pure so it can be tested. */
object SessionRetry {
    enum class Outcome { ESTABLISHED, NOT_ACCEPTED, STOPPED, LINK_RESTORED }
    enum class Action { DONE, RETRY_NOW, WAIT_AND_RETRY, GIVE_UP }

    /**
     * [triesSoFar] counts the waits already made after a refused session. A refused session is retried
     * a limited number of times and then reported, instead of being retried for ever.
     */
    fun decide(outcome: Outcome, triesSoFar: Int, maxTries: Int): Action = when (outcome) {
        Outcome.ESTABLISHED, Outcome.STOPPED -> Action.DONE
        Outcome.LINK_RESTORED -> Action.RETRY_NOW
        Outcome.NOT_ACCEPTED -> if (triesSoFar < maxTries) Action.WAIT_AND_RETRY else Action.GIVE_UP
    }
}

/**
 * Tracks which devices Android currently considers connected.
 *
 * An app cannot make Android connect a paired device (that call is reserved for the system), so
 * the best it can do is notice the state and react: tell the user to connect the amp, and carry on
 * by itself the moment they do. Everything here uses public APIs only.
 */
class LinkMonitor(private val context: Context) {

    private val _linked = MutableStateFlow<Set<String>>(emptySet())

    /** Addresses of devices that are connected right now (ACL link up, or audio profile up). */
    val linked: StateFlow<Set<String>> = _linked.asStateFlow()

    private var started = false

    /** Called when Bluetooth is switched back on, so a connection we still want can be resumed. */
    @Volatile var onAdapterOn: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> _linked.value = emptySet()
                    BluetoothAdapter.STATE_ON -> onAdapterOn?.invoke()
                }
                return
            }
            val dev = deviceOf(i) ?: return
            val up = when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> true
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED ->
                    i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
                else -> return
            }
            set(dev.address, up)
        }
    }

    @Synchronized
    private fun set(mac: String, up: Boolean) {
        val cur = _linked.value
        _linked.value = if (up) cur + mac else cur - mac
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** Start listening. Safe to call repeatedly; does nothing without the Bluetooth permission. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (started) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            // These are protected system broadcasts sent by the Bluetooth app. RECEIVER_NOT_EXPORTED
            // would, before Android 13, put the receiver behind an app-private permission the sender
            // cannot hold, so it would never fire; the receiver only reads the intent.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            started = true
            seedFromProfiles()
        } catch (e: Exception) {
            Log.w("AmplifiLink", "link monitor not started: ${e.message}")
        }
    }

    /** Pick up devices that were already connected before we started listening. */
    @SuppressLint("MissingPermission")
    private fun seedFromProfiles() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? SysBluetoothManager)?.adapter
            ?: return
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try { proxy.connectedDevices.forEach { set(it.address, true) } }
                        catch (_: Exception) {}
                        adapter.closeProfileProxy(p, proxy)
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            } catch (e: Exception) {
                Log.w("AmplifiLink", "profile seed failed: ${e.message}")
            }
        }
    }

    fun isLinked(mac: String): Boolean = mac in _linked.value

    /** Suspend until [mac] shows up as connected; false if [timeoutMs] passes first. */
    suspend fun awaitLinked(mac: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { linked.first { mac in it } } != null

    @Synchronized
    fun stop() {
        if (!started) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        started = false
    }
}
