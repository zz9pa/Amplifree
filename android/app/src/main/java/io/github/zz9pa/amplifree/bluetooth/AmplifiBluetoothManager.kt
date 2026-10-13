package io.github.zz9pa.amplifree.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager as SysBluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import io.github.zz9pa.amplifree.BuildConfig
import io.github.zz9pa.amplifree.data.ConnectionState
import io.github.zz9pa.amplifree.data.HardwareDevice
import io.github.zz9pa.amplifree.data.HardwareState
import io.github.zz9pa.amplifree.ui.ModelNames
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Talks to an AMPLIFi amplifier over Bluetooth SPP (unofficial; not affiliated
 * with Line 6).
 *
 * Nothing from the manufacturer ships with this app. On connect it:
 *   1. opens the Editor and Librarian streams (see [ServiceStream]),
 *   2. downloads the amp's symbol table (cached in app-private storage),
 *   3. handshakes both services with that table's CRC,
 *   4. reads the tone that is live on the amp right now, and
 *   5. downloads the names of the presets stored on the amp.
 *
 * After that, tones are loaded by reading a stored preset and writing it to the
 * live slot, and individual knobs/switches are changed with single Editor commands.
 */
class AmplifiBluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "AmplifiBT"
        private val SPP_UUID = UUID.fromString(AmplifiProtocol.SPP_UUID_STRING)
        private const val STEP_TIMEOUT_MS = 4_000L
        private const val KEEPALIVE_QUIET_MS = 2_500L
        private const val LINK_DEAD_MS = 10_000L
        private const val MAX_NAME_LENGTH = 32
        private const val SESSION_RETRIES = 3
        private const val SESSION_RETRY_WAIT_MS = 10_000L
        /** Slot id the amp uses for device-wide settings such as master volume. */
        private const val GLOBAL_SLOT = -1
        private const val MAX_BACKUPS = 30
        private const val NAME_CACHE = "preset_names.json"
        private const val MODEL_CACHE = "models.json"
        private const val SYMBOL_CACHE_PREFIX = "symbols_"
    }

    /** One service's stream plus the plumbing around it. */
    private class Service(val aux: Int) {
        val stream = ServiceStream(aux)
        val parser = RecordParser()
        val responses = Channel<Record>(Channel.UNLIMITED)
        val events = Channel<Record>(Channel.UNLIMITED)
        val connected = CompletableDeferred<Unit>()
        /** Set when the amp has closed this stream; it answers nothing more until it is reopened. */
        @Volatile var closed = false
        /** Commands are answered strictly in order, so only one may be in flight. */
        val callLock = Mutex()
    }

    // ---- observable state ----------------------------------------------------

    private val _hardwareState = MutableStateFlow(
        HardwareState(
            connectionState = ConnectionState.DISCONNECTED,
            connectedDevice = null,
            masterVolume = 6.5f,
            guitarVolume = 7.0f,
            bluetoothAudioConnected = false,
            firmwareUpToDate = true
        )
    )
    val hardwareState: StateFlow<HardwareState> = _hardwareState.asStateFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    /** True once both services are initialised and commands can be sent. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The tone currently live on the amp, decoded and editable. */
    private val _livePreset = MutableStateFlow<PresetCodec.Preset?>(null)
    val livePreset: StateFlow<PresetCodec.Preset?> = _livePreset.asStateFlow()

    /** Bumped on every change so Compose re-reads the (mutated-in-place) preset. */
    private val _presetRevision = MutableStateFlow(0)
    val presetRevision: StateFlow<Int> = _presetRevision.asStateFlow()

    private val _activeTone = MutableStateFlow<String?>(null)
    val activeTone: StateFlow<String?> = _activeTone.asStateFlow()

    /** Presets stored on the amp: index -> name. */
    private val _ampPresets = MutableStateFlow<List<Pair<Int, String>>>(emptyList())
    val ampPresets: StateFlow<List<Pair<Int, String>>> = _ampPresets.asStateFlow()

    /** (done, total) while the preset list is downloading, else null. */
    private val _scanProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val scanProgress: StateFlow<Pair<Int, Int>?> = _scanProgress.asStateFlow()

    /** True while the amp's own tuner is engaged and reporting. */
    private val _ampTunerEngaged = MutableStateFlow(false)
    val ampTunerEngaged: StateFlow<Boolean> = _ampTunerEngaged.asStateFlow()

    /** Pitch reported by the amp's own tuner, in Hz; null when no note is detected. */
    private val _tunerNote = MutableStateFlow<Float?>(null)
    val tunerNote: StateFlow<Float?> = _tunerNote.asStateFlow()

    /** True once the tone has been changed here and not yet saved (or re-read from the amp). */
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** The stored slot the current tone was loaded from, if we know it. */
    private val _loadedFrom = MutableStateFlow<Int?>(null)
    val loadedFrom: StateFlow<Int?> = _loadedFrom.asStateFlow()

    /** How many preset slots the amp has. */
    private val _slotCount = MutableStateFlow(100)
    val slotCount: StateFlow<Int> = _slotCount.asStateFlow()

    /** Progress and outcome of the last save; null when idle. */
    private val _saveStatus = MutableStateFlow<String?>(null)
    val saveStatus: StateFlow<String?> = _saveStatus.asStateFlow()

    /** Slot and bytes of the tone that the last save replaced, so it can be put back. */
    private val _undoSlot = MutableStateFlow<Int?>(null)
    val undoSlot: StateFlow<Int?> = _undoSlot.asStateFlow()
    private var undoBody: ByteArray? = null

    // ---- link state ------------------------------------------------------------

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var isRunning = false
    @Volatile private var stalled = false
    @Volatile private var lastRx = 0L

    // RSMC: one sequence counter, advanced only by payload frames.
    private val txLock = Mutex()
    private var txSeq = 0
    private var rxSeq = 0
    @Volatile private var peerAck = 0
    @Volatile private var synced = false
    private var sawFrame = CompletableDeferred<Unit>()
    private val decoder = AmplifiProtocol.RsmcDecoder()

    // Streams. Frames are produced under [streamLock] so counters match send order.
    private var editor: Service? = null
    private var librarian: Service? = null
    private val streamLock = Mutex()
    private var outbox = Channel<ByteArray>(Channel.UNLIMITED)

    private var symbols: SymbolTable? = null
    @Volatile private var tunerActive = false
    /** (slot, param) pairs we have subscribed to, and which preset parameter each one is. */
    private val subscribed = HashSet<Long>()
    @Volatile private var eventParams: Map<Long, PresetCodec.Param> = emptyMap()
    @Volatile private var tunerSlotId = -1
    @Volatile private var tunerNoteId = -1
    @Volatile private var suppressPatchEventsUntil = 0L
    private var patchRefreshJob: Job? = null

    // ---- devices -----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun getDiscoveredDevices(): List<HardwareDevice> {
        val adapter = systemAdapter() ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .filter {
                    (it.name ?: "").contains("AMPLIFi", true) ||
                    (it.name ?: "").contains("Line 6", true)
                }
                .map { HardwareDevice(it.name ?: it.address, "AMPLIFi amp", it.address) }
        } catch (e: SecurityException) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted: ${e.message}"); emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "bonded lookup failed: ${e.message}"); emptyList()
        }
    }

    private fun systemAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? SysBluetoothManager)?.adapter

    // ---- connection ----------------------------------------------------------------

    private val linkMonitor = LinkMonitor(context)
    private val prefs = context.getSharedPreferences("amplifree", Context.MODE_PRIVATE)

    /** True while we are waiting for the user to connect the amp in Android's Bluetooth settings. */
    private val _needsLink = MutableStateFlow(false)
    val needsLink: StateFlow<Boolean> = _needsLink.asStateFlow()

    /** Bumped for every connection attempt so a stale attempt can tell it has been superseded. */
    @Volatile private var generation = 0
    private var connectJob: Job? = null
    /** False after the user taps Disconnect: no automatic reconnects until they connect again. */
    @Volatile private var wantConnected = false

    /** Is the amp connected as far as Android is concerned? */
    fun isLinked(mac: String): Boolean = linkMonitor.isLinked(mac)

    /** Reconnect to the amp used last time, if it is still paired. Call once permissions are in. */
    fun autoConnect() {
        linkMonitor.start()
        if (_hardwareState.value.connectionState != ConnectionState.DISCONNECTED) return
        val mac = prefs.getString("last_mac", null) ?: return
        val dev = getDiscoveredDevices().firstOrNull { it.macAddress == mac } ?: return
        connectToDevice(dev)
    }

    /** Called by the app when the user connects. */
    fun connectToDevice(device: HardwareDevice) {
        wantConnected = true
        prefs.edit().putString("last_mac", device.macAddress).apply()
        startAttempt(device)
    }

    private fun startAttempt(device: HardwareDevice) {
        val gen = ++generation
        connectJob?.cancel()
        connectJob = appScope.launch { connectLoop(device, gen) }
    }

    private fun current(gen: Int) = gen == generation && wantConnected

    /**
     * Open the link, retrying quietly. A first RFCOMM attempt right after the amp powers up, or
     * just after Android has connected it, often fails and then succeeds; giving up at the first
     * error is a big part of why the manufacturer's app felt flaky. If it still will not open and
     * Android does not list the amp as connected, ask the user to connect it and carry on
     * automatically when they do.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectLoop(device: HardwareDevice, gen: Int, attempt: Int = 0) {
        teardown()
        _needsLink.value = false
        _hardwareState.value = _hardwareState.value.copy(
            connectionState = ConnectionState.SCANNING, connectedDevice = device
        )
        linkMonitor.start()

        val adapter = systemAdapter()
        if (adapter == null || !adapter.isEnabled) { fail("Bluetooth is off. Turn it on and tap the amp again."); return }

        var attempt = 0
        var opened: BluetoothSocket? = null
        while (current(gen) && opened == null) {
            _status.value = if (attempt == 0) "Connecting to ${device.name}…"
                            else "Connecting to ${device.name}… (try ${attempt + 1})"
            try {
                val dev = adapter.getRemoteDevice(device.macAddress)
                opened = dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            } catch (e: SecurityException) {
                // Retrying cannot fix a missing permission, so say so instead of "can't reach".
                Log.w(TAG, "Bluetooth permission problem: ${e.message}")
                fail("Amplifree needs the Nearby devices permission. Allow it in the app's settings.")
                return
            } catch (e: Exception) {
                Log.w(TAG, "RFCOMM connect failed (try ${attempt + 1}): ${e.message}")
                val wait = ReconnectPolicy.delayMs(attempt)
                attempt++
                // Not connected in Android and a quiet retry did not help: stop hammering,
                // ask the user, and resume on their connect event.
                if (attempt >= 2 && !linkMonitor.isLinked(device.macAddress)) {
                    awaitUserLink(device, gen); return
                }
                if (wait == null || attempt >= ReconnectPolicy.MAX_ATTEMPTS) {
                    fail("Can't reach the amp. Check it is switched on and in range, then tap it again.")
                    return
                }
                delay(wait)
            }
        }
        val s = opened ?: return
        if (!current(gen)) { try { s.close() } catch (_: Exception) {}; return }

        sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        tunerOps = Channel(Channel.UNLIMITED)
        subscribed.clear(); eventParams = emptyMap()
        sessionScope.launch { for (on in tunerOps) { if (on) doStartTuner() else doStopTuner() } }
        socket = s; input = s.inputStream; output = s.outputStream
        isRunning = true; stalled = false; synced = false
        txSeq = 0; rxSeq = 0; peerAck = 0
        outbox = Channel(Channel.UNLIMITED)
        editor = null; librarian = null

        _hardwareState.value = _hardwareState.value.copy(
            connectionState = ConnectionState.CONNECTED,
            connectedDevice = device, bluetoothAudioConnected = true
        )
        sessionScope.launch { readLoop() }

        // A previous session that ended badly (app killed mid-transfer) can leave the amp
        // half-way through a conversation. End it explicitly before starting ours.
        sendRaw(AmplifiProtocol.IAP_STOP); delay(500)
        sendRaw(AmplifiProtocol.IAP_OK); delay(300)
        sendRaw(AmplifiProtocol.IAP_START); delay(600)
        if (!syncToPeer()) return
        sessionScope.launch { for (p in outbox) { if (isRunning) sendPayload(p) } }
        sessionScope.launch { keepAlive() }

        val outcome = startSession(device)
        if (outcome == SessionStart.NOT_ACCEPTED && attempt < SESSION_RETRIES && current(gen)) {
            // The amp keeps a session that was cut off (app killed) open for a while and ignores a
            // new one until it times out. Give it time and try again.
            _status.value = "Waiting for the amp to be ready…"
            delay(SESSION_RETRY_WAIT_MS)
            if (current(gen)) connectLoop(device, gen, attempt + 1)
            return
        }
        if (outcome == SessionStart.NOT_ACCEPTED) fail("The amp didn't accept the connection. Power-cycle it and retry.")
    }

    /** The amp is paired but Android has not connected it: prompt, then wait for the connection. */
    private suspend fun awaitUserLink(device: HardwareDevice, gen: Int) {
        _needsLink.value = true
        _status.value = "Connect ${device.name} in Bluetooth settings (tap it until it shows " +
                        "Connected). Amplifree carries on by itself."
        _hardwareState.value = _hardwareState.value.copy(
            connectionState = ConnectionState.DISCONNECTED, bluetoothAudioConnected = false
        )
        // Wait up to ten minutes, e.g. while the user switches the amp on or visits settings.
        if (linkMonitor.awaitLinked(device.macAddress, 10 * 60_000L) && current(gen)) {
            delay(1_500)                       // let the amp finish bringing its services up
            if (current(gen)) { _needsLink.value = false; startAttempt(device) }
        }
    }

    /** The link dropped by itself: try to get back without bothering the user. */
    private fun scheduleReconnect() {
        val device = _hardwareState.value.connectedDevice ?: return
        if (!wantConnected) return
        val gen = ++generation
        connectJob?.cancel()
        connectJob = appScope.launch {
            _status.value = "Connection lost. Reconnecting…"
            delay(1_000)
            if (current(gen)) connectLoop(device, gen)
        }
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        _status.value = msg
        _ready.value = false
        _hardwareState.value = _hardwareState.value.copy(
            connectionState = ConnectionState.DISCONNECTED, bluetoothAudioConnected = false
        )
    }

    /** The user asked to disconnect: stay disconnected until they connect again. */
    fun disconnect() {
        wantConnected = false
        generation++
        connectJob?.cancel()
        _needsLink.value = false
        teardown()
    }

    private fun teardown() {
        val wasRunning = isRunning
        isRunning = false
        _ready.value = false
        tunerActive = false
        try { if (wasRunning) { output?.write(AmplifiProtocol.IAP_STOP); output?.flush() } } catch (_: Exception) {}
        sessionScope.cancel()
        outbox.close()
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null; output = null; socket = null
        editor = null; librarian = null
        synchronized(senderLock) { pendingParams.clear(); senderJob = null }
        _dirty.value = false
        _loadedFrom.value = null
        _undoSlot.value = null; undoBody = null
        _scanProgress.value = null
        _tunerNote.value = null
        _ampTunerEngaged.value = false
        _hardwareState.value = _hardwareState.value.copy(
            connectionState = ConnectionState.DISCONNECTED,
            connectedDevice = null, bluetoothAudioConnected = false
        )
        _status.value = "Not connected"
    }

    // ---- RSMC link ---------------------------------------------------------------------

    /**
     * Adopt the amp's live RSMC counters. It does not reset them when a client
     * reconnects, so starting at seq 1 would be ignored; and it only speaks in reply to
     * a frame from us, so we probe rather than wait.
     */
    private suspend fun syncToPeer(timeoutMs: Long = 4000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var heard = false
        while (System.currentTimeMillis() < deadline) {
            sawFrame = CompletableDeferred()
            sendHeartbeat()
            if (withTimeoutOrNull(400) { sawFrame.await() } != null) { heard = true; break }
        }
        if (!heard) {
            fail("The amp isn't responding. Power-cycle it, reconnect it in Bluetooth settings, and try again.")
            return false
        }
        txLock.withLock { txSeq = peerAck }
        synced = true
        Log.i(TAG, "synced: txSeq=$txSeq rxSeq=$rxSeq")
        return true
    }

    /**
     * The amp only speaks when spoken to: its idle heartbeats are replies to ours, so a single
     * lost frame ends the exchange and the link goes quiet while looking connected. Probe it
     * when it falls silent, and treat a long silence as a dead link.
     */
    private suspend fun keepAlive() {
        lastRx = System.currentTimeMillis()
        while (isRunning) {
            delay(1_000)
            sendStreamHeartbeats()
            checkStreams()
            val quiet = System.currentTimeMillis() - lastRx
            if (quiet > KEEPALIVE_QUIET_MS) {
                if (BuildConfig.DEBUG) Log.d(TAG, "quiet for ${quiet}ms, probing")
                sendHeartbeat()
            }
            if (quiet > LINK_DEAD_MS && isRunning) {
                Log.w(TAG, "no word from the amp for ${quiet}ms")
                scheduleReconnect()
                return
            }
        }
    }

    @Volatile private var recovering = false

    /** If the amp closed a stream, open it again (once at a time) instead of staying silent for good. */
    private fun checkStreams() {
        if (recovering || !_ready.value) return
        val dead = listOfNotNull(librarian, editor).firstOrNull { it.closed } ?: return
        recovering = true
        sessionScope.launch {
            try { reopenStream(dead.aux) } finally { recovering = false }
        }
    }

    /**
     * Open [aux]'s stream again with a fresh connect and repeat that service's handshake. The
     * amp is happy to start a new stream on a port it has closed.
     */
    private suspend fun reopenStream(aux: Int): Boolean {
        val table = symbols ?: return false
        val isEditor = aux == AmplifiProtocol.AUX_EDITOR
        val name = if (isEditor) "editor" else "preset"
        Log.i(TAG, "reopening the $name stream")
        val svc = Service(aux)
        if (!isEditor) svc.parser.skip = AmplifiProtocol.LIBRARIAN_HELLO.size
        if (isEditor) editor = svc else librarian = svc
        streamLock.withLock { outbox.trySend(svc.stream.connectSegment()) }
        if (withTimeoutOrNull(STEP_TIMEOUT_MS) { svc.connected.await() } == null) {
            Log.w(TAG, "the amp did not accept a new $name stream"); svc.closed = true; return false
        }
        if (!isEditor) {
            if (!streamWrite(svc, AmplifiProtocol.LIBRARIAN_HELLO)) return false
            withTimeoutOrNull(STEP_TIMEOUT_MS) { while (svc.parser.skip > 0) delay(20) }
        }
        call(svc, AmplifiProtocol.CMD_GET_VERSION) ?: run { Log.w(TAG, "$name stream: no version reply"); return false }
        val hs = call(svc, AmplifiProtocol.CMD_HANDSHAKE, table.crcBytes())
        if (hs == null || hs.result != 0) { Log.w(TAG, "$name stream: handshake failed"); return false }
        Log.i(TAG, "the $name stream is back")
        if (isEditor) {
            subscribed.clear()
            subscribeToParamEvents()
            readMaster()
        } else {
            // The tone list may have been cut short when the stream closed; finish it.
            val mac = connectedMac()
            if (mac != null && _ampPresets.value.size < _slotCount.value && _scanProgress.value == null) {
                sessionScope.launch { scanPresets(mac, _slotCount.value) }
            }
        }
        return true
    }

    /** Debug aid: force the librarian to be reopened. */
    fun debugReopen(aux: Int) { sessionScope.launch { reopenStream(aux) } }

    /** Keep both streams alive from our side too; see [ServiceStream.heartbeatSegment]. */
    private suspend fun sendStreamHeartbeats() {
        for (svc in listOfNotNull(editor, librarian)) {
            if (!svc.connected.isCompleted) continue
            streamLock.withLock { outbox.trySend(svc.stream.heartbeatSegment()) }
        }
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(4096)
        while (isRunning) {
            val n = try { input?.read(buf) ?: -1 } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "read failed: ${e.message}")
                -1
            }
            if (n < 0) {
                if (isRunning) { Log.w(TAG, "link closed"); scheduleReconnect() }
                break
            }
            for (f in decoder.push(buf, n)) onRsmcFrame(f)
        }
    }

    private suspend fun onRsmcFrame(f: AmplifiProtocol.RsmcFrame) {
        lastRx = System.currentTimeMillis()
        if (BuildConfig.DEBUG) Log.d("AmplifiRX",
            "rsmc seq=${f.seq} ack=${f.ack} len=${f.payload.size} rxSeq=$rxSeq txSeq=$txSeq")
        peerAck = f.ack
        if (!synced) {
            rxSeq = f.seq                     // before sync we just track the amp's position
        } else if (f.payload.isNotEmpty()) {
            val expected = (rxSeq + 1) and 0xFF
            if (f.seq == expected) { rxSeq = f.seq; dispatchPayload(f.payload) }
            // seq == rxSeq is a retransmission and anything else is a gap; either way we
            // just re-ack and let the amp resend.
        }
        sendHeartbeat()
        if (!sawFrame.isCompleted) sawFrame.complete(Unit)
    }

    /** Never send more than the amp's 8-frame window beyond its last ack. */
    private suspend fun awaitWindow(timeoutMs: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (((txSeq - peerAck) and 0xFF) < AmplifiProtocol.WINDOW - 1) return true
            delay(10)
        }
        Log.w(TAG, "RSMC window stalled: txSeq=$txSeq peerAck=$peerAck")
        stalled = true
        return false
    }

    private suspend fun sendPayload(payload: ByteArray) {
        if (stalled || !awaitWindow()) return
        if (BuildConfig.DEBUG) for (f in Bsp.splitFrames(payload)) Segment.parse(f)?.let {
            Log.d("AmplifiRX", "out aux=${it.aux.toString(16)} type=${it.type} ctr=${it.ctr} " +
                "fl=${it.flags.toString(16)} win=${it.window} len=${it.payload.size}")
        }
        val frame = txLock.withLock {
            txSeq = (txSeq + 1) and 0xFF
            AmplifiProtocol.packRsmcFrame(txSeq, rxSeq, (rxSeq + AmplifiProtocol.WINDOW) and 0xFF, payload)
        }
        sendRaw(frame)
    }

    private suspend fun sendHeartbeat() {
        val frame = txLock.withLock {
            AmplifiProtocol.packRsmcFrame(txSeq, rxSeq, (rxSeq + AmplifiProtocol.WINDOW) and 0xFF)
        }
        sendRaw(frame)
    }

    private fun sendRaw(bytes: ByteArray) {
        try { output?.write(bytes); output?.flush() }
        catch (e: Exception) { Log.e(TAG, "write failed: ${e.message}") }
    }

    // ---- streams -------------------------------------------------------------------------

    private fun serviceForSrc(src: Int): Service? = when (src) {
        AmplifiProtocol.SRC_EDITOR -> editor
        AmplifiProtocol.SRC_LIBRARIAN -> librarian
        else -> null
    }

    /**
     * An RSMC payload may carry several BSP frames back to back (about one in eight
     * does), so split before routing: reading only the first drops the rest.
     */
    private suspend fun dispatchPayload(payload: ByteArray) {
        val touched = linkedSetOf<Service>()
        for (frame in Bsp.splitFrames(payload)) {
            val seg = Segment.parse(frame) ?: continue
            val svc = serviceForSrc(seg.aux) ?: continue
            val inbound = svc.stream.onFrame(frame)
            if ((seg.flags and ServiceStream.FLAG_CLOSED) != 0 && seg.type == 1 && !svc.closed) {
                Log.w(TAG, "the amp closed the ${if (svc.aux == AmplifiProtocol.AUX_EDITOR) "editor" else "preset"} stream")
                svc.closed = true
            }
            if (BuildConfig.DEBUG) Log.d("AmplifiRX", "in  aux=${seg.aux.toString(16)} type=${seg.type} ctr=${seg.ctr} " +
                "fl=${seg.flags.toString(16)} win=${seg.window} len=${seg.payload.size} new=${inbound.newBytes.size}")
            if (inbound.isConnect && !svc.connected.isCompleted) svc.connected.complete(Unit)
            if (inbound.newBytes.isEmpty()) continue
            touched += svc
            for (rec in svc.parser.feed(inbound.newBytes)) route(svc, rec)
        }
        // Tell the amp how much we have consumed, or it stops sending after 4 KB.
        for (svc in touched) {
            if (svc.stream.needsAck()) streamLock.withLock { outbox.trySend(svc.stream.ackSegment()) }
        }
    }

    private fun route(svc: Service, rec: Record) {
        if (rec.type == Record.TYPE_RESPONSE) {
            svc.responses.trySend(rec)
        } else {
            if (BuildConfig.DEBUG) Log.d("AmplifiRX", "event svc=${svc.aux.toString(16)} type=${rec.type} len=${rec.data.size} " +
                rec.data.take(24).joinToString("") { "%02x".format(it) })
            svc.events.trySend(rec)
            onEvent(svc, rec)
        }
    }

    /** Write bytes to a service's stream, respecting the MTU and the amp's flow-control credit. */
    private suspend fun streamWrite(svc: Service, data: ByteArray, timeoutMs: Long = 10_000): Boolean {
        var off = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (off < data.size) {
            if (stalled || !isRunning) return false
            var sent = 0
            streamLock.withLock {
                val n = minOf(data.size - off, svc.stream.credit(), svc.stream.maxSegment())
                if (n > 0) { outbox.trySend(svc.stream.dataSegment(data, off, n)); sent = n }
            }
            if (sent > 0) { off += sent; continue }
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "no flow-control credit from the amp for ${timeoutMs}ms")
                return false
            }
            delay(10)
        }
        return true
    }

    /** Send one command and wait for its response (they are answered in order). */
    private suspend fun call(
        svc: Service, cmd: Int, args: ByteArray = ByteArray(0), timeoutMs: Long = STEP_TIMEOUT_MS
    ): Record? = svc.callLock.withLock {
        if (svc.closed) return@withLock null
        while (svc.responses.tryReceive().isSuccess) { /* discard stale */ }
        if (!streamWrite(svc, AmplifiProtocol.command(cmd, args))) return@withLock null
        withTimeoutOrNull(timeoutMs) { svc.responses.receive() }
    }

    // ---- session start --------------------------------------------------------------------

    private enum class SessionStart { OK, NOT_ACCEPTED, FAILED }

    private suspend fun startSession(device: HardwareDevice): SessionStart {
        _status.value = "Opening session…"
        val ed = Service(AmplifiProtocol.AUX_EDITOR)
        val lib = Service(AmplifiProtocol.AUX_LIBRARIAN)
        editor = ed; librarian = lib

        // The Librarian stream opens with a 4-byte version word in each direction.
        lib.parser.skip = AmplifiProtocol.LIBRARIAN_HELLO.size
        streamLock.withLock {
            outbox.trySend(ed.stream.connectSegment())
            outbox.trySend(lib.stream.connectSegment())
        }
        val connected = withTimeoutOrNull(STEP_TIMEOUT_MS) { ed.connected.await(); lib.connected.await() }
        if (connected == null) return SessionStart.NOT_ACCEPTED

        if (!streamWrite(lib, AmplifiProtocol.LIBRARIAN_HELLO)) { fail("Couldn't open the preset service"); return SessionStart.FAILED }
        withTimeoutOrNull(STEP_TIMEOUT_MS) { while (lib.parser.skip > 0) delay(20) }

        call(lib, AmplifiProtocol.CMD_GET_VERSION) ?: run { fail("The amp isn't answering"); return SessionStart.FAILED }
        val count = call(lib, AmplifiProtocol.CMD_LIB_GET_NUM_PRESETS)?.data?.let { le32(it) } ?: 0
        if (count in 1..1000) _slotCount.value = count

        val table = obtainSymbolTable(lib, device) ?: return SessionStart.FAILED
        symbols = table

        _status.value = "Handshaking…"
        val crc = table.crcBytes()
        val libHs = call(lib, AmplifiProtocol.CMD_HANDSHAKE, crc)
        if (libHs == null || libHs.result != 0) {
            // The cached table doesn't match this amp (different model or firmware).
            File(context.filesDir, SYMBOL_CACHE_PREFIX + device.macAddress.replace(":", "")).delete()
            fail("The amp rejected the symbol table (result ${libHs?.result}). Reconnect to download it again.")
            return SessionStart.FAILED
        }
        call(ed, AmplifiProtocol.CMD_GET_VERSION)
        val edHs = call(ed, AmplifiProtocol.CMD_HANDSHAKE, crc)
        if (edHs == null || edHs.result != 0) { fail("The editor service rejected the handshake"); return SessionStart.FAILED }

        // Read-only queries the original app also makes at startup.
        call(ed, AmplifiProtocol.CMD_ED_GET_DIRTY_STATE)
        call(ed, AmplifiProtocol.CMD_ED_GET_FBV_STATUS)

        _ready.value = true
        _status.value = "Connected"
        Log.i(TAG, "session ready: $count presets, ${table.size} symbols")
        sessionScope.launch { readMaster() }

        readLivePreset()
        loadCachedNames(device.macAddress)
        loadCachedModels(device.macAddress)
        sessionScope.launch {
            scanPresets(device.macAddress, if (count in 1..1000) count else 100)
            if (_models.value.isEmpty() || _paramInfo.value.isEmpty()) harvestModels(device.macAddress)
        }
        return SessionStart.OK
    }

    private fun le32(b: ByteArray): Int =
        if (b.size >= 4) ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int else 0

    /** Cached table if we have one, otherwise download it from the amp. */
    private suspend fun obtainSymbolTable(lib: Service, device: HardwareDevice): SymbolTable? {
        val cache = File(context.filesDir, SYMBOL_CACHE_PREFIX + device.macAddress.replace(":", ""))
        if (cache.exists()) {
            val raw = cache.readBytes()
            if (SymbolTable.looksValid(raw)) return SymbolTable(raw)
            cache.delete()
        }
        _status.value = "Downloading the amp's symbol table…"
        val sizeRec = call(lib, AmplifiProtocol.CMD_GET_SYMBOL_TABLE_SIZE)
        val expected = sizeRec?.data?.let { le32(it) } ?: 0
        if (expected !in 64..500_000) { fail("The amp wouldn't report its symbol table size"); return null }

        val rec = call(lib, AmplifiProtocol.CMD_GET_SYMBOL_TABLE, timeoutMs = 60_000)
        if (rec == null) { fail("Symbol table download timed out"); return null }
        // Depending on the command the response may or may not carry a leading result code.
        val raw = when (rec.data.size) {
            expected -> rec.data
            expected + 4 -> rec.data.copyOfRange(4, rec.data.size)
            else -> rec.data
        }
        if (!SymbolTable.looksValid(raw)) {
            fail("Received an invalid symbol table (${rec.data.size} bytes, expected $expected)")
            return null
        }
        cache.writeBytes(raw)
        return SymbolTable(raw)
    }

    // ---- reading tones -----------------------------------------------------------------------

    private fun publish(body: ByteArray): Boolean {
        val table = symbols ?: return false
        return runCatching {
            val p = PresetCodec.decode(body, table.names)
            if (BuildConfig.DEBUG) p.blocks().firstOrNull { it.symbol == AmplifiProtocol.ROUTING_BLOCK }?.params?.forEach {
                Log.d("AmplifiRX", "routing param ${it.name} type=${it.record.type} value=${it.record.asInt}/${it.record.asFloat}")
            }
            _livePreset.value = p
            _dirty.value = false              // what we show is what the amp has
            _activeTone.value = p.name.ifBlank { null }
            _presetRevision.value = _presetRevision.value + 1
        }.onFailure { Log.w(TAG, "could not decode preset: ${it.message}") }.isSuccess
    }

    /** Read the tone that is live on the amp right now into the editor. */
    suspend fun readLivePreset(): Boolean {
        val lib = librarian ?: return false
        val r = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_FILE, AmplifiProtocol.u32(AmplifiProtocol.LIVE_PRESET), 10_000)
        if (r == null || r.result != 0 || r.body.isEmpty()) {
            Log.w(TAG, "readLivePreset failed: ${r?.result}"); return false
        }
        _loadedFrom.value = null              // the live tone's origin is unknown from here
        val ok = publish(r.body)
        if (ok) sessionScope.launch { subscribeToParamEvents() }
        return ok
    }

    fun refreshLiveTone() { sessionScope.launch { if (_ready.value) readLivePreset() } }

    /** Slots that would not answer even after a retry (damaged?); skipped so they cannot stall the rest. */
    private val badSlots = HashSet<Int>()

    /** The preset stream once it is usable, waiting a little while it is being reopened. */
    private suspend fun awaitLibrarian(timeoutMs: Long = 10_000): Service? {
        val end = System.currentTimeMillis() + timeoutMs
        while (isRunning && System.currentTimeMillis() < end) {
            librarian?.let { if (!it.closed && it.connected.isCompleted) return it }
            delay(100)
        }
        return null
    }

    /** Ask for [cmd] on the preset stream, once more after a failure; null if the slot never answers. */
    private suspend fun libCallForSlot(slot: Int, cmd: Int, timeoutMs: Long): Record? {
        repeat(2) {
            val lib = awaitLibrarian() ?: return null
            call(lib, cmd, AmplifiProtocol.u32(slot), timeoutMs)?.let { return it }
        }
        Log.w(TAG, "slot $slot does not answer; skipping it")
        badSlots.add(slot)
        return null
    }

    private suspend fun scanPresets(mac: String, total: Int) {
        val found = mutableListOf<Pair<Int, String>>()
        for (i in 0 until total) {
            if (!isRunning || stalled) break
            if (i in badSlots) continue
            _scanProgress.value = i to total
            val r = libCallForSlot(i, AmplifiProtocol.CMD_LIB_GET_PRESET_NAME, 3_000) ?: continue
            if (r.result != 0) continue
            val name = AmplifiProtocol.parsePresetName(r.body)
            if (name.isNotEmpty()) found += i to name
            if (i % 10 == 9) _ampPresets.value = found.toList()
        }
        _scanProgress.value = null
        if (found.isNotEmpty()) { _ampPresets.value = found.toList(); saveCachedNames(mac, found) }
        Log.i(TAG, "preset list: ${found.size} of $total" + if (badSlots.isNotEmpty()) " (skipped $badSlots)" else "")
    }

    fun rescanPresets() {
        val mac = _hardwareState.value.connectedDevice?.macAddress ?: return
        if (!_ready.value || _scanProgress.value != null) return
        sessionScope.launch { scanPresets(mac, 100) }
    }

    // ---- loading tones --------------------------------------------------------------------------

    /**
     * Make one of the amp's stored presets the live tone, then read the live tone back into the
     * editor. The amp loads the preset itself (LoadHardwarePreset). Uploading the tone with
     * SetPresetFile instead was tried first: on a real amp it left the preset service silent.
     */
    fun loadStoredPreset(index: Int, label: String? = null) {
        sessionScope.launch {
            val lib = librarian ?: return@launch
            if (!_ready.value) { _status.value = "Not connected"; return@launch }
            _status.value = "Loading ${label ?: "preset $index"}…"
            suppressPatchEventsUntil = System.currentTimeMillis() + 2_000   // our own load echoes as an event
            val r = call(lib, AmplifiProtocol.CMD_LIB_LOAD_HARDWARE_PRESET, AmplifiProtocol.u32(index), 8_000)
            if (r == null || r.result != 0) {
                Log.w(TAG, "load $index: reply=${r != null} result=${r?.result}")
                _status.value = "The amp didn't load ${label ?: "preset $index"}"; return@launch
            }
            delay(300)
            if (!readLivePreset()) { _status.value = "Loaded, but couldn't read the tone back"; return@launch }
            _loadedFrom.value = index
            _status.value = "Loaded ${_activeTone.value ?: label ?: "preset $index"}"
        }
    }

    /**
     * Experimental: ask the amp to load stored preset [index] itself. Whether this
     * changes what you hear is unconfirmed, so it is not used by the UI.
     */
    fun experimentalHardwareLoad(index: Int) {
        sessionScope.launch {
            val lib = librarian ?: return@launch
            val r = call(lib, AmplifiProtocol.CMD_LIB_LOAD_HARDWARE_PRESET, AmplifiProtocol.u32(index))
            Log.i(TAG, "LoadHardwarePreset($index) -> result=${r?.result}")
            delay(300); readLivePreset()
        }
    }

    // ---- models (which amp / cab / pedal a block can hold) -------------------------------------------

    /** block symbol -> model names seen in this amp's own stored tones. Learned at runtime, nothing bundled. */
    private val _models = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val models: StateFlow<Map<String, List<String>>> = _models.asStateFlow()

    /** The amp's master volume (a device setting, not part of a tone), 0..1; null until read. */
    private val _masterVolume = MutableStateFlow<Float?>(null)
    val masterVolume: StateFlow<Float?> = _masterVolume.asStateFlow()
    @Volatile private var masterParamId = -1

    private suspend fun readMaster() {
        val table = symbols ?: return
        val ed = editor ?: return
        val pid = table.id("Master") ?: return
        masterParamId = pid
        val r = call(ed, AmplifiProtocol.CMD_ED_GET_DSP_MODEL_PARAM, AmplifiProtocol.dspEventArgs(GLOBAL_SLOT, pid), 2_000)
        if (r != null && r.result == 0 && r.body.size >= 8) {
            val bb = ByteBuffer.wrap(r.body).order(ByteOrder.LITTLE_ENDIAN)
            val type = bb.int
            if (type == AmplifiProtocol.VALUE_TYPE_FLOAT) _masterVolume.value = bb.float
        }
        call(ed, AmplifiProtocol.CMD_ED_REGISTER_DSP_EVENT, AmplifiProtocol.dspEventArgs(GLOBAL_SLOT, pid), 1_500)
        Log.i(TAG, "master volume = ${_masterVolume.value}")
    }

    fun setMasterVolume(vol: Float) {
        val pid = masterParamId
        if (pid < 0 || !_ready.value) return
        _masterVolume.value = vol
        enqueueParam("master", AmplifiProtocol.setDspParamFloat(GLOBAL_SLOT, pid, vol))
    }

    /** What the amp's stored tones tell us about each parameter, keyed "<model>.<param>". */
    private val _paramInfo = MutableStateFlow<Map<String, ParamInfo>>(emptyMap())
    val paramInfo: StateFlow<Map<String, ParamInfo>> = _paramInfo.asStateFlow()

    /** Key under which a block's parameter is remembered: by model where there is one. */
    private fun paramKey(b: PresetCodec.Block, name: String) = (b.flow ?: b.symbol) + "." + name

    private fun record(stats: ParamStatsBuilder, blocks: List<PresetCodec.Block>) {
        for (b in blocks) for (p in b.params) when {
            p.isFloat -> stats.addFloat(paramKey(b, p.name), p.record.asFloat)
            p.isInt -> stats.addChoice(paramKey(b, p.name), p.record.type, p.record.asInt)
            p.isBool -> stats.addChoice(paramKey(b, p.name), PresetCodec.TYPE_BOOL, p.record.asInt)
        }
    }

    /** Symbol name for an id (choice parameters store their value as a symbol id). */
    fun symbolName(id: Int): String = symbols?.name(id) ?: "#$id"

    /** Read every stored tone once and note which model each block has held, and how parameters range. */
    private suspend fun harvestModels(mac: String) {
        val table = symbols ?: return
        val found = LinkedHashMap<String, MutableSet<String>>()
        val stats = ParamStatsBuilder()
        _livePreset.value?.blocks()?.let { bl ->
            bl.forEach { b -> b.flow?.let { found.getOrPut(b.symbol) { sortedSetOf() }.add(it) } }
            record(stats, bl)
        }
        for (i in 0 until _slotCount.value) {
            if (!isRunning || stalled) return
            _status.value = "Learning your amp's models… ${i + 1}/${_slotCount.value}"
            if (i in badSlots) continue
            val r = libCallForSlot(i, AmplifiProtocol.CMD_LIB_GET_PRESET_FILE, 10_000) ?: continue
            if (r.result != 0 || r.body.isEmpty()) continue
            runCatching { PresetCodec.decode(r.body, table.names).blocks() }.getOrNull()?.let { bl ->
                bl.forEach { b -> b.flow?.let { found.getOrPut(b.symbol) { sortedSetOf() }.add(it) } }
                record(stats, bl)
            }
            if (i % 10 == 9) _models.value = found.mapValues { it.value.toList() }
        }
        _models.value = found.mapValues { it.value.toList() }
        _paramInfo.value = stats.build()
        saveCachedModels(mac)
        if (_ready.value) _status.value = "Connected"
        Log.i(TAG, "models learned: " + _models.value.entries.joinToString { "${it.key}=${it.value.size}" })
    }

    /** Models to offer for [block]: those seen in this amp's tones plus all the amp's table knows. */
    fun modelChoices(block: String): List<String> {
        val learned = _models.value[block].orEmpty()
        val table = symbols?.names ?: return learned
        return ModelNames.choices(block, table, learned)
    }

    /**
     * Swap the model in [block] (e.g. "Amp", "FX1") for [model]. The amp loads the model with its own
     * defaults; the live tone is then read back so the editor shows the new controls. Unverified on
     * hardware until the first real run.
     */
    fun swapModel(block: String, model: String) {
        sessionScope.launch {
            val table = symbols; val ed = editor
            if (table == null || ed == null || !_ready.value) { _status.value = "Not connected"; return@launch }
            val slot = table.id(block); val mid = table.id(model)
            if (slot == null || mid == null) { Log.w(TAG, "unknown symbol: $block($slot) $model($mid)"); return@launch }
            awaitParamsSent()
            _status.value = "Loading $model…"
            val args = AmplifiProtocol.u32(slot) + AmplifiProtocol.u32(mid)
            val r = call(ed, AmplifiProtocol.CMD_ED_LOAD_DSP_MODEL, args, 5_000)
            Log.i(TAG, "LoadDspModel($block, $model) -> reply=${r != null} result=${r?.result}")
            if (r == null || r.result != 0) { _status.value = "The amp didn't accept $model"; return@launch }
            delay(300)
            val keepFrom = _loadedFrom.value
            readLivePreset()
            _loadedFrom.value = keepFrom
            Log.i(TAG, "after swap: $block is " + _livePreset.value?.blocks()?.firstOrNull { it.symbol == block }?.flow)
            _dirty.value = true                  // the live tone no longer matches the stored one
            _status.value = "$block is now $model"
        }
    }

    private fun loadCachedModels(mac: String) {
        runCatching {
            val f = File(context.filesDir, MODEL_CACHE)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            if (o.optString("mac") != mac) return
            val m = o.getJSONObject("models")
            _models.value = m.keys().asSequence().associateWith { k ->
                m.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
            }
            o.optJSONObject("params")?.let { pi ->
                _paramInfo.value = pi.keys().asSequence().associateWith { k ->
                    val e = pi.getJSONObject(k)
                    ParamInfo(e.getInt("t"), e.getDouble("lo").toFloat(), e.getDouble("hi").toFloat(),
                        e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getInt(it) } })
                }
            }
        }
    }

    private fun saveCachedModels(mac: String) {
        runCatching {
            val m = JSONObject()
            _models.value.forEach { (k, v) -> m.put(k, JSONArray(v)) }
            val pi = JSONObject()
            _paramInfo.value.forEach { (k, v) ->
                pi.put(k, JSONObject().put("t", v.type).put("lo", v.min.toDouble()).put("hi", v.max.toDouble())
                    .put("v", JSONArray(v.values)))
            }
            File(context.filesDir, MODEL_CACHE).writeText(
                JSONObject().put("mac", mac).put("models", m).put("params", pi).toString()
            )
        }
    }

    // ---- live editing ------------------------------------------------------------------------------

    /**
     * Change one parameter on the amp. [block] and [param] are the names used inside a
     * preset (e.g. "Amp", "DriveState" or "GuitarProc", "FX1EnableState").
     */
    fun setLiveParam(block: String, param: String, value: Float? = null, bool: Boolean? = null, int: Int? = null) {
        val table = symbols
        val ed = editor
        if (table == null || ed == null || !_ready.value) { _status.value = "Not connected"; return }
        val slotId = table.id(block)
        val editorName = AmplifiProtocol.editorParamName(block, param)
        val paramId = table.id(editorName)
        if (slotId == null || paramId == null) {
            Log.w(TAG, "unknown symbol: $block($slotId) $editorName($paramId)"); return
        }
        val args = when {
            value != null -> AmplifiProtocol.setDspParamFloat(slotId, paramId, value)
            bool != null -> AmplifiProtocol.setDspParamBool(slotId, paramId, bool)
            int != null -> AmplifiProtocol.setDspParamInt(slotId, paramId, int)
            else -> return
        }
        _dirty.value = true
        enqueueParam("$block.$editorName", args)
    }

    // Changes are sent by one worker, newest value per control only. Fired as separate jobs
    // they could reach the amp out of order (leaving it different from the screen), and a
    // fast drag would queue a command for every value passed through.
    private val pendingParams = LatestWinsQueue<ByteArray>()
    private val senderLock = Any()
    private var senderJob: Job? = null

    private fun enqueueParam(key: String, args: ByteArray) {
        pendingParams.put(key, args)
        synchronized(senderLock) {
            if (senderJob?.isActive == true) return
            senderJob = sessionScope.launch { drainParams() }
        }
    }

    private suspend fun drainParams() {
        val ed = editor ?: return
        while (true) {
            val next = synchronized(senderLock) {
                pendingParams.take().also { if (it == null) senderJob = null }
            } ?: return
            val r = call(ed, AmplifiProtocol.CMD_ED_SET_DSP_MODEL_PARAM, next.second, 2_500)
            Log.i(TAG, "set ${next.first} -> result=${r?.result}")
            if (r == null || r.result != 0) _status.value = "The amp didn't accept that change"
        }
    }

    /** Wait (briefly) until every queued change has been sent. */
    private suspend fun awaitParamsSent(timeoutMs: Long = 4_000) {
        withTimeoutOrNull(timeoutMs) {
            while (synchronized(senderLock) { !pendingParams.isEmpty() || senderJob?.isActive == true }) delay(20)
        }
    }

    // ---- saving ------------------------------------------------------------------------------------

    /**
     * Write the tone shown in the editor into stored slot [index], under [newName].
     *
     * Overwriting a stored tone is hard to take back, so first the slot's current contents are
     * kept in app-private storage and in memory (for [undoLastSave]), the write is read back, and
     * the result is reported honestly. Storing to a slot other than the live one is not yet
     * verified on hardware.
     */
    fun saveLiveToSlot(index: Int, newName: String) {
        sessionScope.launch {
            val lib = librarian
            val preset = _livePreset.value
            if (lib == null || preset == null || !_ready.value) { _saveStatus.value = "Not connected"; return@launch }
            if (index !in 0 until _slotCount.value) { _saveStatus.value = "No such slot"; return@launch }
            _saveStatus.value = "Saving to slot $index…"
            awaitParamsSent()

            val clean = newName.filter { it.code in 32..126 }.trim().take(MAX_NAME_LENGTH)
            if (clean.isNotEmpty() && clean != preset.name) preset.name = clean
            val body = preset.encode()

            // Keep what is there now. A timeout means we can't tell, so don't overwrite blind;
            // a clean "no such preset" answer means the slot is empty.
            val old = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_FILE, AmplifiProtocol.u32(index), 10_000)
            if (old == null) { Log.w(TAG, "save: no answer reading slot $index"); _saveStatus.value = "The amp didn't answer, so nothing was changed"; return@launch }
            val previous = old.body.takeIf { old.result == 0 && it.isNotEmpty() }
            if (previous != null) backUp(index, previous)

            suppressPatchEventsUntil = System.currentTimeMillis() + 3_000
            val set = call(lib, AmplifiProtocol.CMD_LIB_SET_PRESET_FILE, AmplifiProtocol.setPresetFileArgs(body, index), 15_000)
            if (set == null || set.result != 0) {
                Log.w(TAG, "save: SetPresetFile refused: reply=${set != null} result=${set?.result} bytes=${body.size}")
                _saveStatus.value = "The amp refused to store it (result ${set?.result}). Nothing was changed."
                return@launch
            }

            val back = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_FILE, AmplifiProtocol.u32(index), 10_000)
            val verified = back != null && back.result == 0 && back.body.contentEquals(body)

            undoBody = previous
            _undoSlot.value = if (previous != null) index else null
            val name = preset.name.ifBlank { "Slot $index" }
            val list = _ampPresets.value.filter { it.first != index } + (index to name)
            _ampPresets.value = list.sortedBy { it.first }
            connectedMac()?.let { saveCachedNames(it, _ampPresets.value) }
            _loadedFrom.value = index
            _activeTone.value = name
            _presetRevision.value = _presetRevision.value + 1
            _dirty.value = false
            Log.i(TAG, "save: slot $index name='$name' verified=$verified bytes=${body.size}")
            _saveStatus.value = if (verified) "Saved to slot $index as \"$name\""
                                else "Stored in slot $index, but reading it back didn't match. Check it on the amp."
        }
    }

    /** Put back whatever the last save replaced. */
    fun undoLastSave() {
        val index = _undoSlot.value ?: return
        val body = undoBody ?: return
        sessionScope.launch {
            val lib = librarian ?: return@launch
            _saveStatus.value = "Restoring slot $index…"
            val set = call(lib, AmplifiProtocol.CMD_LIB_SET_PRESET_FILE, AmplifiProtocol.setPresetFileArgs(body, index), 15_000)
            if (set == null || set.result != 0) { _saveStatus.value = "Couldn't restore slot $index"; return@launch }
            undoBody = null; _undoSlot.value = null
            _saveStatus.value = "Slot $index restored"
            // The list name may have changed; refresh just that entry.
            call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_NAME, AmplifiProtocol.u32(index), 3_000)?.let { r ->
                val n = AmplifiProtocol.parsePresetName(r.body)
                if (r.result == 0 && n.isNotEmpty()) {
                    _ampPresets.value = (_ampPresets.value.filter { it.first != index } + (index to n)).sortedBy { it.first }
                    connectedMac()?.let { saveCachedNames(it, _ampPresets.value) }
                }
            }
        }
    }

    /**
     * Debug aid: read slot [index] and write exactly the same bytes back, then read it again.
     * Harmless if it works, and it shows whether writing to a stored slot is accepted at all.
     */
    fun debugWriteBack(index: Int) {
        sessionScope.launch {
            val lib = librarian ?: return@launch
            val a = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_FILE, AmplifiProtocol.u32(index), 10_000)
            if (a == null || a.result != 0 || a.body.isEmpty()) { Log.w(TAG, "writeback: couldn't read slot $index"); return@launch }
            backUp(index, a.body)
            Log.i(TAG, "writeback: read slot $index (${a.body.size} bytes), writing it back")
            val t0 = System.currentTimeMillis()
            val w = call(lib, AmplifiProtocol.CMD_LIB_SET_PRESET_FILE, AmplifiProtocol.setPresetFileArgs(a.body, index), 20_000)
            Log.i(TAG, "writeback: SetPresetFile -> reply=${w != null} result=${w?.result} in ${System.currentTimeMillis() - t0} ms")
            val b = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_FILE, AmplifiProtocol.u32(index), 10_000)
            Log.i(TAG, "writeback: read again -> reply=${b != null} same=${b != null && b.body.contentEquals(a.body)}")
            val n = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_NAME, AmplifiProtocol.u32(index), 3_000)
            Log.i(TAG, "writeback: name -> reply=${n != null} '${n?.let { AmplifiProtocol.parsePresetName(it.body) }}'")
        }
    }

    /** Debug aid: write the newest backup of slot [index] back into it. */
    fun debugRestoreBackup(index: Int) {
        sessionScope.launch {
            val dir = File(context.filesDir, "backups")
            val f = dir.listFiles()?.filter { it.name.startsWith("slot${index}_") }?.maxByOrNull { it.lastModified() }
            if (f == null) { Log.w(TAG, "restore: no backup for slot $index"); return@launch }
            val body = f.readBytes()
            val lib = awaitLibrarian() ?: run { Log.w(TAG, "restore: no preset stream"); return@launch }
            Log.i(TAG, "restore: writing ${f.name} (${body.size} bytes) into slot $index")
            val w = call(lib, AmplifiProtocol.CMD_LIB_SET_PRESET_FILE, AmplifiProtocol.setPresetFileArgs(body, index), 20_000)
            Log.i(TAG, "restore: SetPresetFile -> reply=${w != null} result=${w?.result}")
            badSlots.remove(index)
            val n = libCallForSlot(index, AmplifiProtocol.CMD_LIB_GET_PRESET_NAME, 3_000)
            Log.i(TAG, "restore: slot $index now reads '${n?.let { AmplifiProtocol.parsePresetName(it.body) }}'")
        }
    }

    /** Debug aid: compare the live tone's bytes with stored slot [index] (read-only). */
    fun debugDiffLiveWithSlot(index: Int) {
        sessionScope.launch {
            val lib = librarian ?: return@launch
            val live = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_FILE, AmplifiProtocol.u32(AmplifiProtocol.LIVE_PRESET), 10_000)
            val slot = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_FILE, AmplifiProtocol.u32(index), 10_000)
            if (live == null || slot == null) { Log.w(TAG, "diff: read failed"); return@launch }
            val a = live.body; val b = slot.body
            runCatching {
                File(context.filesDir, "debug_live.bin").writeBytes(a)
                File(context.filesDir, "debug_slot.bin").writeBytes(b)
            }
            Log.i(TAG, "diff: live ${a.size} bytes, slot $index ${b.size} bytes, equal=${a.contentEquals(b)}")
            val n = minOf(a.size, b.size)
            val diffs = (0 until n).filter { a[it] != b[it] }
            Log.i(TAG, "diff: ${diffs.size} differing bytes in the common part; first at " + diffs.take(8))
            fun hex(x: ByteArray, from: Int, len: Int) =
                x.copyOfRange(from.coerceAtLeast(0), minOf(x.size, from + len)).joinToString("") { "%02x".format(it) }
            diffs.firstOrNull()?.let { d ->
                Log.i(TAG, "diff: live  @${d - 8}: " + hex(a, d - 8, 32))
                Log.i(TAG, "diff: slot  @${d - 8}: " + hex(b, d - 8, 32))
            }
            Log.i(TAG, "diff: live head " + hex(a, 0, 40))
            Log.i(TAG, "diff: slot head " + hex(b, 0, 40))
            Log.i(TAG, "diff: live tail " + hex(a, a.size - 24, 24))
            Log.i(TAG, "diff: slot tail " + hex(b, b.size - 24, 24))
        }
    }

    fun clearSaveStatus() { _saveStatus.value = null }

    private fun connectedMac(): String? = _hardwareState.value.connectedDevice?.macAddress

    /** Copy of a tone about to be overwritten, in app-private storage (the user's own data). */
    private fun backUp(index: Int, body: ByteArray) {
        runCatching {
            val dir = File(context.filesDir, "backups").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            File(dir, "slot${index}_$stamp.bin").writeBytes(body)
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_BACKUPS)?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "backup failed: ${it.message}") }
    }

    // ---- events from the amp -------------------------------------------------------------------------

    private fun onEvent(svc: Service, rec: Record) {
        if (svc.aux != AmplifiProtocol.AUX_EDITOR) return
        when (rec.type) {
            Record.TYPE_PATCH_EVENT -> {
                // The tone changed on the amp itself (e.g. its own buttons). Re-read it so
                // the editor shows what is really playing -- unless we caused it.
                if (System.currentTimeMillis() < suppressPatchEventsUntil) return
                patchRefreshJob?.cancel()
                patchRefreshJob = sessionScope.launch { delay(500); readLivePreset() }
            }
            Record.TYPE_PARAM_EVENT, Record.TYPE_PARAM_CHANGED -> {
                if (rec.data.size >= 16) {
                    val bb = ByteBuffer.wrap(rec.data).order(ByteOrder.LITTLE_ENDIAN)
                    val slot = bb.int; val param = bb.int; val valueType = bb.int; val raw = bb.int
                    if (tunerActive && slot == tunerSlotId && param == tunerNoteId && valueType == AmplifiProtocol.VALUE_TYPE_FLOAT) {
                        val v = java.lang.Float.intBitsToFloat(raw)
                        _tunerNote.value = if (v.isNaN() || v < 0f) null else v   // -1 means no note
                    } else {
                        onParamChanged(slot, param, valueType, raw)
                    }
                }
            }
        }
    }

    // ---- the amp's own tuner ------------------------------------------------------------------------------

    // Tuner commands go through one queue so they reach the amp in the order they were asked for.
    // As separate background jobs, "stop" (leaving the tab) could land after a later "start".
    private var tunerOps = Channel<Boolean>(Channel.UNLIMITED)

    /** Engage the amp's tuner and start receiving its readings. */
    fun startAmpTuner() { tunerOps.trySend(true) }

    private suspend fun doStartTuner() {
        val table = symbols; val ed = editor
        if (table == null || ed == null || !_ready.value) return
        val slot = table.id(AmplifiProtocol.SYM_TUNER)
        val enable = table.id(AmplifiProtocol.SYM_TUNER_ENABLE)
        val note = table.id(AmplifiProtocol.SYM_TUNER_NOTE)
        if (slot == null || enable == null || note == null) { Log.w(TAG, "tuner symbols missing"); return }
        tunerSlotId = slot; tunerNoteId = note
        val on = call(ed, AmplifiProtocol.CMD_ED_SET_DSP_MODEL_PARAM, AmplifiProtocol.setDspParamInt(slot, enable, 1))
        val reg = call(ed, AmplifiProtocol.CMD_ED_REGISTER_DSP_EVENT, AmplifiProtocol.dspEventArgs(slot, note))
        tunerActive = on?.result == 0 && reg?.result == 0
        _ampTunerEngaged.value = tunerActive
        Log.i(TAG, "amp tuner: enable=${on?.result} register=${reg?.result} active=$tunerActive")
    }

    private fun eventKey(slot: Int, param: Int): Long = (slot.toLong() shl 32) or (param.toLong() and 0xFFFFFFFFL)

    /**
     * Ask the amp to tell us when any parameter of the live tone changes, so turning a knob on the
     * amp moves the matching control here. Each (slot, parameter) is subscribed once per session.
     */
    private suspend fun subscribeToParamEvents() {
        val table = symbols ?: return
        val ed = editor ?: return
        val preset = _livePreset.value ?: return
        val map = HashMap<Long, PresetCodec.Param>()
        for (b in preset.blocks()) {
            val slot = table.id(b.symbol) ?: continue
            for (pr in b.params) {
                if (!(pr.isFloat || pr.isBool || pr.isInt)) continue
                val pid = table.id(AmplifiProtocol.editorParamName(b.symbol, pr.name)) ?: continue
                val k = eventKey(slot, pid)
                map[k] = pr
                if (subscribed.add(k) && isRunning) {
                    call(ed, AmplifiProtocol.CMD_ED_REGISTER_DSP_EVENT, AmplifiProtocol.dspEventArgs(slot, pid), 1_500)
                    delay(20)           // don't burst: the amp has been seen to close a stream under load
                }
            }
        }
        eventParams = map
        Log.i(TAG, "subscribed to ${subscribed.size} parameters")
    }

    /** A parameter changed on the amp itself: mirror it into the editor. */
    private fun onParamChanged(slot: Int, param: Int, valueType: Int, raw: Int) {
        if (slot == GLOBAL_SLOT && param == masterParamId && valueType == AmplifiProtocol.VALUE_TYPE_FLOAT) {
            _masterVolume.value = java.lang.Float.intBitsToFloat(raw)
            return
        }
        val pr = eventParams[eventKey(slot, param)] ?: return
        val changed = when (valueType) {
            AmplifiProtocol.VALUE_TYPE_FLOAT -> {
                val v = java.lang.Float.intBitsToFloat(raw)
                (pr.record.asFloat != v).also { if (it) pr.record.setFloat(v) }
            }
            AmplifiProtocol.VALUE_TYPE_BOOL -> {
                (pr.record.asBool != (raw != 0)).also { if (it) pr.record.setBool(raw != 0) }
            }
            AmplifiProtocol.VALUE_TYPE_INT -> {
                (pr.record.asInt != raw).also { if (it) pr.record.setInt(raw) }
            }
            else -> false
        }
        if (changed) {
            if (BuildConfig.DEBUG) Log.d("AmplifiRX", "amp changed ${pr.name} (type $valueType)")
            _dirty.value = true
            _presetRevision.value = _presetRevision.value + 1
        }
    }

    /** Debug aid: poll the tuner block's values directly and log them, to see what the amp reports. */
    fun probeTuner(seconds: Int) {
        sessionScope.launch {
            val table = symbols; val ed = editor
            if (table == null || ed == null || !_ready.value) return@launch
            val slot = table.id(AmplifiProtocol.SYM_TUNER) ?: return@launch
            val names = listOf("Enable", "Note", "Period", "Reference")
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end && isRunning) {
                val line = StringBuilder()
                for (n in names) {
                    val id = table.id(n)
                    if (id == null) { line.append("$n=?  "); continue }
                    val r = call(ed, AmplifiProtocol.CMD_ED_GET_DSP_MODEL_PARAM, AmplifiProtocol.dspEventArgs(slot, id), 1_500)
                    line.append("$n=").append(
                        r?.let { "r${it.result}:" + it.body.joinToString("") { b -> "%02x".format(b) } } ?: "timeout"
                    ).append("  ")
                }
                Log.i(TAG, "tuner probe: $line")
                delay(250)
            }
        }
    }

    fun stopAmpTuner() {
        tunerActive = false
        _ampTunerEngaged.value = false
        _tunerNote.value = null
        tunerOps.trySend(false)
    }

    private suspend fun doStopTuner() {
        val table = symbols; val ed = editor
        if (table == null || ed == null || !_ready.value) return
        val slot = table.id(AmplifiProtocol.SYM_TUNER) ?: return
        val enable = table.id(AmplifiProtocol.SYM_TUNER_ENABLE) ?: return
        val note = table.id(AmplifiProtocol.SYM_TUNER_NOTE) ?: return
        call(ed, AmplifiProtocol.CMD_ED_UNREGISTER_DSP_EVENT, AmplifiProtocol.dspEventArgs(slot, note))
        call(ed, AmplifiProtocol.CMD_ED_SET_DSP_MODEL_PARAM, AmplifiProtocol.setDspParamInt(slot, enable, 0))
    }

    // ---- preset name cache (the user's own data, in app-private storage) --------------------------------

    private fun loadCachedNames(mac: String) {
        runCatching {
            val f = File(context.filesDir, NAME_CACHE)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            if (o.optString("mac") != mac) return
            val arr = o.getJSONArray("names")
            _ampPresets.value = (0 until arr.length()).map { arr.getJSONObject(it).let { e -> e.getInt("i") to e.getString("n") } }
        }
    }

    private fun saveCachedNames(mac: String, names: List<Pair<Int, String>>) {
        runCatching {
            val arr = JSONArray()
            names.forEach { (i, n) -> arr.put(JSONObject().put("i", i).put("n", n)) }
            File(context.filesDir, NAME_CACHE).writeText(JSONObject().put("mac", mac).put("names", arr).toString())
        }
    }

    // ---- UI contract kept for the existing screens ---------------------------------------------------------

    fun setGuitarVolume(vol: Float) {
        _hardwareState.value = _hardwareState.value.copy(guitarVolume = vol)
    }

    /** Local symbol-table import for development (see DebugCommandReceiver). */
    fun importSymbolTable(mac: String, raw: ByteArray): Boolean {
        if (!SymbolTable.looksValid(raw)) return false
        File(context.filesDir, SYMBOL_CACHE_PREFIX + mac.replace(":", "")).writeBytes(raw)
        return true
    }
}
