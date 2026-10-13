package io.github.zz9pa.amplifree.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager as SysBluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import io.github.zz9pa.amplifree.data.ConnectionState
import io.github.zz9pa.amplifree.data.HardwareDevice
import io.github.zz9pa.amplifree.data.HardwareState
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
        private const val NAME_CACHE = "preset_names.json"
        private const val SYMBOL_CACHE_PREFIX = "symbols_"
    }

    /** One service's stream plus the plumbing around it. */
    private class Service(val aux: Int) {
        val stream = ServiceStream(aux)
        val parser = RecordParser()
        val responses = Channel<Record>(Channel.UNLIMITED)
        val events = Channel<Record>(Channel.UNLIMITED)
        val connected = CompletableDeferred<Unit>()
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
    private val _tunerHz = MutableStateFlow<Float?>(null)
    val tunerHz: StateFlow<Float?> = _tunerHz.asStateFlow()

    // ---- link state ------------------------------------------------------------

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var isRunning = false
    @Volatile private var stalled = false

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

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: HardwareDevice) {
        appScope.launch {
            disconnect()
            _hardwareState.value = _hardwareState.value.copy(
                connectionState = ConnectionState.SCANNING, connectedDevice = device
            )
            _status.value = "Connecting to ${device.name}…"

            val adapter = systemAdapter()
            if (adapter == null || !adapter.isEnabled) { fail("Bluetooth is off"); return@launch }

            val s = try {
                val dev = adapter.getRemoteDevice(device.macAddress)
                dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
            } catch (e: Exception) {
                // "read failed, socket might closed or timeout" almost always means the amp
                // has no active Bluetooth link: being paired is not enough, it must be
                // connected (it appears as an audio device) and it drops the link whenever
                // it is powered off.
                Log.w(TAG, "RFCOMM connect failed: ${e.message}")
                fail("Can't reach the amp. Connect it in Bluetooth settings until it shows " +
                     "Connected, then try again.")
                return@launch
            }

            sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

            sendRaw(AmplifiProtocol.IAP_OK); delay(300)
            sendRaw(AmplifiProtocol.IAP_START); delay(600)
            if (!syncToPeer()) return@launch
            sessionScope.launch { for (p in outbox) { if (isRunning) sendPayload(p) } }

            startSession(device)
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

    fun disconnect() {
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
        _scanProgress.value = null
        _tunerHz.value = null
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

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(4096)
        while (isRunning) {
            val n = try { input?.read(buf) ?: -1 } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "read failed: ${e.message}")
                -1
            }
            if (n < 0) {
                if (isRunning) { Log.w(TAG, "link closed"); fail("Connection to the amp was lost") }
                break
            }
            for (f in decoder.push(buf, n)) onRsmcFrame(f)
        }
    }

    private suspend fun onRsmcFrame(f: AmplifiProtocol.RsmcFrame) {
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
        while (svc.responses.tryReceive().isSuccess) { /* discard stale */ }
        if (!streamWrite(svc, AmplifiProtocol.command(cmd, args))) return@withLock null
        withTimeoutOrNull(timeoutMs) { svc.responses.receive() }
    }

    // ---- session start --------------------------------------------------------------------

    private suspend fun startSession(device: HardwareDevice) {
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
        if (connected == null) { fail("The amp didn't accept the connection. Power-cycle it and retry."); return }

        if (!streamWrite(lib, AmplifiProtocol.LIBRARIAN_HELLO)) { fail("Couldn't open the preset service"); return }
        withTimeoutOrNull(STEP_TIMEOUT_MS) { while (lib.parser.skip > 0) delay(20) }

        call(lib, AmplifiProtocol.CMD_GET_VERSION) ?: run { fail("The amp isn't answering"); return }
        val count = call(lib, AmplifiProtocol.CMD_LIB_GET_NUM_PRESETS)?.data?.let { le32(it) } ?: 0

        val table = obtainSymbolTable(lib, device) ?: return
        symbols = table

        _status.value = "Handshaking…"
        val crc = table.crcBytes()
        val libHs = call(lib, AmplifiProtocol.CMD_HANDSHAKE, crc)
        if (libHs == null || libHs.result != 0) {
            // The cached table doesn't match this amp (different model or firmware).
            File(context.filesDir, SYMBOL_CACHE_PREFIX + device.macAddress.replace(":", "")).delete()
            fail("The amp rejected the symbol table (result ${libHs?.result}). Reconnect to download it again.")
            return
        }
        call(ed, AmplifiProtocol.CMD_GET_VERSION)
        val edHs = call(ed, AmplifiProtocol.CMD_HANDSHAKE, crc)
        if (edHs == null || edHs.result != 0) { fail("The editor service rejected the handshake"); return }

        // Read-only queries the original app also makes at startup.
        call(ed, AmplifiProtocol.CMD_ED_GET_DIRTY_STATE)
        call(ed, AmplifiProtocol.CMD_ED_GET_FBV_STATUS)

        _ready.value = true
        _status.value = "Connected"
        Log.i(TAG, "session ready: $count presets, ${table.size} symbols")

        readLivePreset()
        loadCachedNames(device.macAddress)
        sessionScope.launch { scanPresets(device.macAddress, if (count in 1..1000) count else 100) }
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
            _livePreset.value = p
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
        return publish(r.body)
    }

    fun refreshLiveTone() { sessionScope.launch { if (_ready.value) readLivePreset() } }

    private suspend fun scanPresets(mac: String, total: Int) {
        val lib = librarian ?: return
        val found = mutableListOf<Pair<Int, String>>()
        for (i in 0 until total) {
            if (!isRunning || stalled) break
            _scanProgress.value = i to total
            val r = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_NAME, AmplifiProtocol.u32(i), 3_000) ?: continue
            if (r.result != 0) continue
            val name = r.body.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()
            if (name.isNotEmpty()) found += i to name
            if (i % 10 == 9) _ampPresets.value = found.toList()
        }
        _scanProgress.value = null
        if (found.isNotEmpty()) { _ampPresets.value = found.toList(); saveCachedNames(mac, found) }
        Log.i(TAG, "preset list: ${found.size} of $total")
    }

    fun rescanPresets() {
        val mac = _hardwareState.value.connectedDevice?.macAddress ?: return
        if (!_ready.value || _scanProgress.value != null) return
        sessionScope.launch { scanPresets(mac, 100) }
    }

    // ---- loading tones --------------------------------------------------------------------------

    /**
     * Load one of the amp's stored presets: read it, make it the live tone, and show
     * it in the editor. Reading a preset into the editor is cheap; writing it to the
     * live slot is what you hear.
     */
    fun loadStoredPreset(index: Int, label: String? = null) {
        sessionScope.launch {
            val lib = librarian ?: return@launch
            if (!_ready.value) { _status.value = "Not connected"; return@launch }
            _status.value = "Loading ${label ?: "preset $index"}…"
            val r = call(lib, AmplifiProtocol.CMD_LIB_GET_PRESET_FILE, AmplifiProtocol.u32(index), 10_000)
            if (r == null || r.result != 0 || r.body.isEmpty()) {
                _status.value = "Couldn't read preset $index"; return@launch
            }
            if (!pushPreset(r.body)) { _status.value = "The amp didn't accept the tone"; return@launch }
            publish(r.body)
            _status.value = "Loaded ${label ?: _activeTone.value ?: "preset $index"}"
        }
    }

    private suspend fun pushPreset(body: ByteArray): Boolean {
        val lib = librarian ?: return false
        suppressPatchEventsUntil = System.currentTimeMillis() + 3_000   // our own push echoes as an event
        val r = call(lib, AmplifiProtocol.CMD_LIB_SET_PRESET_FILE, AmplifiProtocol.setPresetFileArgs(body), 15_000)
        return r != null && r.result == 0
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
        sessionScope.launch {
            val r = call(ed, AmplifiProtocol.CMD_ED_SET_DSP_MODEL_PARAM, args, 2_500)
            Log.i(TAG, "set $block.$editorName = ${value ?: bool ?: int} -> result=${r?.result}")
            if (r == null || r.result != 0) _status.value = "The amp didn't accept that change"
        }
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
            Record.TYPE_VALUE_EVENT -> {
                if (tunerActive && rec.data.size >= 8) {
                    val bb = ByteBuffer.wrap(rec.data).order(ByteOrder.LITTLE_ENDIAN)
                    if (bb.int == AmplifiProtocol.VALUE_TYPE_FLOAT) {
                        _tunerHz.value = AmplifiProtocol.tunerValueToHz(bb.float)
                    }
                }
            }
        }
    }

    // ---- the amp's own tuner ------------------------------------------------------------------------------

    /** Engage the amp's tuner and start receiving its readings. Experimental until verified on hardware. */
    fun startAmpTuner() {
        sessionScope.launch {
            val table = symbols; val ed = editor
            if (table == null || ed == null || !_ready.value) return@launch
            val slot = table.id(AmplifiProtocol.SYM_TUNER)
            val enable = table.id(AmplifiProtocol.SYM_TUNER_ENABLE)
            val note = table.id(AmplifiProtocol.SYM_TUNER_NOTE)
            if (slot == null || enable == null || note == null) { Log.w(TAG, "tuner symbols missing"); return@launch }
            val on = call(ed, AmplifiProtocol.CMD_ED_SET_DSP_MODEL_PARAM, AmplifiProtocol.setDspParamInt(slot, enable, 1))
            val reg = call(ed, AmplifiProtocol.CMD_ED_REGISTER_DSP_EVENT, AmplifiProtocol.dspEventArgs(slot, note))
            tunerActive = on?.result == 0 && reg?.result == 0
            _ampTunerEngaged.value = tunerActive
            Log.i(TAG, "amp tuner: enable=${on?.result} register=${reg?.result} active=$tunerActive")
        }
    }

    fun stopAmpTuner() {
        val table = symbols; val ed = editor
        tunerActive = false
        _ampTunerEngaged.value = false
        _tunerHz.value = null
        if (table == null || ed == null || !_ready.value) return
        val slot = table.id(AmplifiProtocol.SYM_TUNER) ?: return
        val enable = table.id(AmplifiProtocol.SYM_TUNER_ENABLE) ?: return
        val note = table.id(AmplifiProtocol.SYM_TUNER_NOTE) ?: return
        sessionScope.launch {
            call(ed, AmplifiProtocol.CMD_ED_UNREGISTER_DSP_EVENT, AmplifiProtocol.dspEventArgs(slot, note))
            call(ed, AmplifiProtocol.CMD_ED_SET_DSP_MODEL_PARAM, AmplifiProtocol.setDspParamInt(slot, enable, 0))
        }
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

    fun setMasterVolume(vol: Float) {
        _hardwareState.value = _hardwareState.value.copy(masterVolume = vol)
    }

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
