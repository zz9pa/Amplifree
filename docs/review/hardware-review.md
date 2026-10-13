# Independent review: hardware-facing code

Scope: `android/app/src/main/java/io/github/zz9pa/amplifree/bluetooth/*`, `data/HardwareManager.kt`,
`data/TunerEngine.kt`, `ui/AmplifreeViewModel.kt` / `MainActivity.kt` / `ConnectionDialog.kt` where they
drive the link, the manifests and backup rules, the matching unit tests, and `tools/*.py`.
Commit reviewed: `76260c5` (main). No application code was changed by this review.

Paths below are relative to `android/app/src/main/java/io/github/zz9pa/amplifree/` unless they start with
`tools/` or `android/`. `ABM` = `bluetooth/AmplifiBluetoothManager.kt`.

## Executive summary

The protocol layering is sound and the arithmetic is mostly right. 8-bit counters wrap with `and 0xFF`
everywhere I checked. Window maths uses `Long`. The `(txSeq - peerAck) and 0xFF` window test is
wrap-safe. BSP splitting and padding are correct. There is no lock-order inversion: `streamLock`,
`txLock` and `callLock` are never taken in conflicting orders, and no `synchronized` block wraps a
suspend call.

The weakness is **session lifecycle**. Everything that makes a connection *recover* (connect retry,
reconnect, stream reopen, teardown) is built from unsynchronised `var`s that are shared between the UI
thread, `appScope` and a fresh `sessionScope` per connection. Several of the "flaky link" symptoms are
plausibly explained by what follows, not by the amp:

- A retry counter that is silently shadowed, so a refused session is retried forever (F1).
- The session surviving the Activity, so the next launch fights its own predecessor for the amp (F2).
- A permanent "stalled" latch that leaves the UI saying *Connected* while every command is dropped (F3).
- A stream-counter gap that wedges one stream for the rest of the session (F4).
- Reconnects that reuse state from the dead session (F5, F6).

### Three riskiest areas

1. **Connect / reconnect / teardown orchestration** (ABM `connectLoop`, `scheduleReconnect`,
   `disconnect`, `teardown`, `startAttempt`; F1, F5, F6, F12, F13, F14).
2. **No recovery from silent stream or link stalls** (`stalled` latch, `ServiceStream` ctr gap, no RSMC
   retransmit, response mis-pairing after a timeout, `reopenStream` with no limit; F3, F4, F8, F9, F11).
3. **Ownership of the session** (manager lives in a ViewModel, has no `close()`; never unregisters or
   cancels anything; background and Bluetooth-off behaviour; F2, F20, F21).

### Test status

`./gradlew :app:testDebugUnitTest` (JDK 21, Gradle 9.3.1, SDK 36, forced re-run with `cleanTestDebugUnitTest`):
**BUILD SUCCESSFUL, 55 tests, 0 failures, 0 skipped.**

| Suite | Tests |
|---|---|
| LatestWinsQueueTest | 3 |
| ModelNamesTest | 5 |
| ParamStatsTest | 4 |
| PresetCodecTest | 10 |
| PresetNameTest | 3 |
| ReconnectPolicyTest | 2 |
| StreamLayerTest | 23 |
| TunerEngineTest | 5 |

`tools/test_rsmc.py` (18 checks) and `tools/test_bsp.py` (10 checks) also pass (`python test_*.py`, Python 3.8).
They are scripts, not pytest tests (see F31).

Several findings below were confirmed with throw-away JUnit tests that I wrote, ran and deleted. They are not
in this branch. Where that applies the finding says **Verified**. Everything else is from reading the code and
is marked as such. Nothing was run against an amp.

### Counts

Critical 0, High 6, Medium 15, Low 10 (F22-F31, several bundled), Nit 5.

---

## High

### F1. Session-retry counter is shadowed, so a refused session is retried forever
`ABM:254`, `265`, `323-331`

`connectLoop(device, gen, attempt: Int = 0)` declares `var attempt = 0` at line 265, which shadows the
parameter. From then on `attempt` is the number of *RFCOMM connect* failures in this call, and the
parameter is dead. At line 323 `attempt < SESSION_RETRIES` therefore tests the connect-retry count, which
is 0 whenever the first RFCOMM connect worked. The recursive call `connectLoop(device, gen, attempt + 1)`
passes a value the callee immediately discards.

*Scenario.* The amp still holds a half-open session (app was killed, or another phone is connected to
it). RFCOMM connects first time, `startSession` returns `NOT_ACCEPTED`, and the code waits 10 s and
recurses. `attempt` is 0 again, so it never reaches `SESSION_RETRIES`. The "didn't accept the
connection. Power-cycle it" failure message is unreachable. The user sees "Waiting for the amp to be
ready…" indefinitely, and the call stack/continuation chain grows by one frame per 10 s.

*Fix.* Rename the local (`connectTries`) and keep `attempt` as the session-retry count. Better, turn the
recursion into a `while` loop so there is no growing continuation. Add a unit test (T1) by extracting
the decision into a pure function.

### F2. The amp session outlives the Activity; the next launch creates a second manager that contests it
`ui/AmplifreeViewModel.kt:29`, `69-72`; `data/HardwareManager.kt:211`; `ABM:147`, `206`

`AmplifiBluetoothManager` is created inside `HardwareManager`, inside an `AndroidViewModel`.
`onCleared()` only calls `stopAmpTuner()`. There is no `close()`/`shutdown()`: `appScope` is never
cancelled, the socket is never closed, `keepAlive`/`readLoop` keep running, and `LinkMonitor`'s receiver is
never unregistered. `LinkMonitor.stop()` (`bluetooth/LinkMonitor.kt:123`) has no callers.

*Scenario.* User presses Back (Activity finishes, ViewModel cleared) but the process stays cached. The old
manager keeps the RFCOMM socket and session open and keeps heart-beating. User relaunches: a new ViewModel,
new `HardwareManager`, new manager, `autoConnect()`. The amp already has a live session and ignores the new
one (known behaviour), so `startSession` returns `NOT_ACCEPTED`, which then loops forever because of F1.
Meanwhile both managers hold a receiver and `DebugBridge.manager` points at the new one.

*Fix.* Give the manager an explicit `close()` (disconnect, `appScope.cancel()`, `linkMonitor.stop()`) and call
it from `onCleared()`. Or make the manager process-scoped (a singleton in an `Application` subclass) and
decide on purpose whether the link should survive the UI; if it should, that needs a foreground service
(F21).

### F3. `stalled` is a permanent, silent latch
`ABM:154`, `550-559`, `562`, `637`, `814`, `933`

`awaitWindow` sets `stalled = true` after 3 s without RSMC window space. Nothing ever clears it except
`connectLoop`, and nothing triggers a reconnect. `sendPayload` then drops every payload, `streamWrite`
returns false, `call()` returns null, and scans/harvests `break`. RSMC heartbeats still flow, so
`lastRx` keeps refreshing and `keepAlive` never declares the link dead.

*Scenario.* One 3 s hiccup (phone Doze wake-up, Bluetooth coexistence stall, or a long `syncToPeer`
style pause) while 7+ frames are unacked. The UI stays "Connected" and `ready` stays true. Every knob
move produces "The amp didn't accept that change" and nothing recovers until the user manually
reconnects.

*Fix.* Treat a stall as a link failure: on the first stall call `scheduleReconnect()`, or at least clear the
flag when `peerAck` advances again. Dropping a payload whose stream counters were already consumed also
desynchronises the stream (F4), so a retry or reconnect is the only safe response.

### F4. One missing stream segment wedges that stream for the rest of the session
`StreamLayer.kt:176-184`; `ABM:603-613`

`ServiceStream.onFrame` accepts a segment only if `ctr == lastRxCtr + 1`. A segment that fails the test is
discarded without updating `lastRxCtr`, so **every later segment fails the same test**. No resync, no
counter, no escalation. RSMC is reliable so a gap should be rare, but any path that loses a segment inside
the app (a dropped inbound frame, F5/F6 below, a truncated tail, F25) turns into a dead stream.

**Verified.** Feeding ctr 2, 4, 5, 6 delivers the first segment's 10 bytes and **0 bytes for all later
segments**.

*Scenario.* A segment is lost once. Every `call()` on that service times out (4 s each), the preset scan
skips slot after slot, and each slot ends in `badSlots` (F10). The link looks healthy.

*Fix.* Count consecutive out-of-order segments. After N (say 3) or T ms, mark the service `closed` so the
existing `reopenStream` path rebuilds the stream. Log it at `warn`. The same counter is a useful
diagnostic for the owner.

### F5. Connect / disconnect / reconnect are not serialised; `generation` increment is not atomic
`ABM:213-244`, `350-360`, `372-378`, `254-305`

`++generation` is a read-modify-write on a `@Volatile` Int, called from the main thread
(`startAttempt`, `disconnect`) and from IO threads (`scheduleReconnect`, via `readLoop` EOF and `keepAlive`).
Two concurrent `scheduleReconnect()` calls can both read N and both write N+1, so both new jobs consider
themselves current, and `connectJob` keeps only one reference, so the other cannot be cancelled.
`connectLoop` itself runs on `appScope` while `disconnect()` calls `teardown()` on the caller's (main) thread,
so `teardown()` and the tail of `connectLoop` (lines 294-311) can interleave.

*Scenarios.*
- *Double loop.* The amp powers off. `readLoop` sees EOF and `keepAlive` fires within the same moment.
  Two `connectLoop`s run, both calling `teardown()`, assigning `sessionScope`, `socket`, `outbox` and
  `editor`/`librarian`. Result: two sockets, orphaned jobs, a session that half-works.
- *Disconnect race.* User taps Disconnect while `connectLoop` is between `current(gen)` (line 295) and
  `isRunning = true` (line 302). `disconnect()` runs `teardown()` first (nothing to close), then
  `connectLoop` publishes its socket and sets `CONNECTED`. `connectJob` is cancelled but the first
  suspension only happens at `delay(500)` (line 315), after the socket, `readLoop`, and `isRunning` are all live.
  The user sees "Connected" after pressing Disconnect, and `wantConnected == false` means no automatic repair.

*Fix.* Replace the loose flags with a single connection actor: one `Mutex` (or a single-threaded dispatcher)
around "start attempt / teardown / publish session", `AtomicInteger.incrementAndGet()` for the generation,
and re-check `current(gen)` *after* publishing the socket, closing it if superseded. Put the teardown in a
`try/finally` inside the job so cancellation always releases the socket.

### F6. Per-session state is shared across sessions: one `RsmcDecoder`, `readLoop` re-reads fields
`ABM:164`, `517-530`, `301`, `311`; `AmplifiProtocol.kt:256-296`

`decoder` is a `val` created once per manager and never reset. `readLoop` runs `input?.read(buf)` and
re-reads the field each iteration, and `isRunning` is global rather than per session. Cancelling
`sessionScope` does not stop it, because the loop is a blocking read inside `withContext` that never
checks for cancellation.

**Verified (decoder).** Push 40 bytes of a 112-byte frame, then a heartbeat and a complete 42-byte
payload frame: **0 frames** come out of either push. A fresh decoder returns the heartbeat immediately.

*Scenario.* The link drops mid-frame (common when the amp is switched off). The next session's first
frames, including the sync heartbeats, are appended to the old partial frame, then discarded when its payload
CRC fails. `syncToPeer` retries for 4 s, so this often hides as "needed a second try", but it adds delay.
Separately, an old `readLoop` that wakes between `teardown()` and the new `isRunning = true` would start
reading the *new* `input` concurrently with the new `readLoop`, and `RsmcDecoder` (an unsynchronised
`ArrayDeque`) would be pushed from two threads.

*Fix.* Create the decoder, streams, and the input/output references inside a per-session object, and capture
them as locals in `readLoop`. Compare a session token instead of the global `isRunning`.

---

## Medium

### F7. `subscribeToParamEvents` can run concurrently; it races on a `HashSet` and can leave a stale `eventParams`
`ABM:781`, `1297-1317`, `299`, `175-176`

Every `readLivePreset()` launches another `subscribeToParamEvents()`. Patch events (amp buttons) trigger
`readLivePreset` repeatedly. The jobs run on `Dispatchers.IO` and share `subscribed` (a plain `HashSet`),
and each ends with `eventParams = map`.

*Scenario.* Job A (preset A) is part-way through registering ~100 parameters at 20 ms plus a round trip each.
Job B (preset B, read 600 ms later) finds nearly everything already in `subscribed`, finishes at once, and
sets `eventParams` to B's `Param` objects. Job A then finishes and overwrites it with A's stale objects.
From then on knob changes on the amp mutate the *old* preset; the UI shows no change.
Concurrent `HashSet` use can also corrupt the table. Also, `subscribed.add(k)` is recorded before the
register command succeeds, so a failed register is never retried.

*Fix.* Publish `eventParams` before the registration loop, run the subscription in a single serialised job
(cancel the previous one), use a concurrent set, and add to `subscribed` only after a successful reply.

### F8. A late reply is paired with the next command
`ABM:654-661`

Replies carry no command id; `call()` relies on order. After a timeout the call returns null and releases the
lock. The next `call()` only drains replies that *already arrived*. If the late reply for call N lands after
call N+1 has been written, it is consumed as N+1's answer, and N+1's real reply is then consumed by N+2, and so on.

*Scenario.* Slow `GetPresetFile` (10 s timeout) answers at 10.3 s. The following `GetPresetName` is
satisfied with preset-file data, `parsePresetName` returns a garbage or empty name, and each following
reply is shifted by one.

*Fix.* After any timeout mark the service as "needs resync" and either wait a grace period while draining, or
reopen the stream (the machinery exists). Validate reply shape per command (for example result code plus
expected minimum length) so a mismatch is detected.

### F9. `reopenStream` has no back-off, no limit, and `awaitLibrarian` returns before the handshake
`ABM:456-504`, `791-798`

- `checkStreams` re-launches `reopenStream` every second for as long as a service has `closed == true`,
  and a failed attempt sets `svc.closed = true` again (line 482). A stream the amp keeps refusing is
  re-created forever and never escalates to a full reconnect.
- `reopenStream` installs the new `Service` at line 479 and then handshakes. `awaitLibrarian` returns the
  service as soon as `connected.isCompleted`. The scan can therefore send `GetPresetName` before the
  Version/Handshake commands, or between them (the `callLock` serialises calls but not their order).
- `recovering` (line 456) is only reset in a `finally` inside the launched coroutine; if the scope is
  cancelled before the coroutine ever starts, the `finally` never runs, and `recovering` stays true for
  all later sessions because it is never reset in `connectLoop`/`teardown`.

*Fix.* Add a `ready` deferred to `Service`, completed after the handshake, and use it in `awaitLibrarian`.
Count failures: after 3, call `scheduleReconnect()`. Reset `recovering` in `connectLoop`.

### F10. `badSlots` is never cleared, and a partial scan overwrites the cached name list
`ABM:788-826`, `932-949`

`libCallForSlot` adds a slot to `badSlots` after two failures. Failures include timeouts caused by a dying link
(F3, F4, F8), not only damaged slots. The set is a plain `HashSet` shared by several coroutines and is not
reset on reconnect, so names skipped in one bad session stay missing, and model learning skips them for the
life of the process. `scanPresets` also writes whatever it found to `_ampPresets` and the on-disk cache even
when it broke out early (line 814) or skipped slots, replacing a previously complete cache with a partial one.
During the scan the list is also replaced every 10 slots, so entries disappear from the UI.

*Fix.* Clear `badSlots` per session, only count a slot as bad on a definite non-zero result, and merge
instead of replace: keep cached names for slots not re-read. Persist only after a complete pass.

### F11. No RSMC transmit retransmission, and write failures are swallowed
`ABM:561-584`

`sendPayload` increments `txSeq` before the write, and `sendRaw` catches every `Exception` and logs it. If a
write fails or is cut short, `txSeq` has moved but the amp never saw the frame; the amp then ignores every
later frame (`seq != last + 1`), while it still answers heartbeats. Nothing retransmits (there is no unacked
buffer), and `nak` is ignored.

*Scenario.* A transient `IOException` on one write. From then on all commands time out; the link looks alive.

*Fix.* Keep the unacked frames (at most 7) and retransmit when `peerAck` has not advanced for ~1 s; declare
the link dead if it makes no progress for N seconds. At minimum, have `sendRaw` report failure so the caller
can stop and reconnect.

### F12. A failed connect leaks the `BluetoothSocket`
`ABM:272`

`createInsecureRfcommSocketToServiceRecord(...).also { it.connect() }`: if `connect()` throws, the socket
object is lost without `close()`. With up to 5 tries per attempt and automatic retries this leaks a file
descriptor per failure, and an unclosed RFCOMM socket can also delay the amp seeing the failure.

*Fix.* Keep the socket in a local, and `close()` it in the `catch`. Also call `cancelDiscovery()` first if
discovery can be running.

### F13. `fail()` reports "disconnected" but leaves the session half-alive
`ABM:362-369`, `318`, `682-703`, `685`

`fail()` only changes state. After `syncToPeer` fails, or `startSession` returns `FAILED`, `isRunning` stays
true, the socket stays open and, for `startSession` failures, `keepAlive` keeps running. The UI shows
"Disconnected" while the app is still heart-beating a live session; the next tap re-enters `connectLoop`.
The "symbol table rejected" path deletes the (probably fine) cache and asks the user to reconnect by hand
instead of retrying once itself.

*Fix.* Have `fail()` call `teardown()` (keeping the message) and retry the symbol-table download once.

### F14. Nothing resumes after Bluetooth is turned off and on
`ABM:262-263`, `bluetooth/LinkMonitor.kt` (no `ACTION_STATE_CHANGED`)

If Bluetooth is switched off while connected, the read fails, `scheduleReconnect()` runs, `connectLoop` reaches
`adapter.isEnabled == false` and calls `fail("Bluetooth is off…")`. `wantConnected` is still true but nothing is
listening for the adapter coming back, so the user must tap the amp again.

*Fix.* Register for `BluetoothAdapter.ACTION_STATE_CHANGED` in `LinkMonitor` and restart the attempt on
`STATE_ON` when `wantConnected`.

### F15. Unbounded `events` channel that no one reads
`ABM:68`, `627`

`Service.events` is `Channel.UNLIMITED` and `route()` does `svc.events.trySend(rec)` for every event, but
nothing ever receives from it (grep confirms the only reference is the send). Every param-changed and tuner
event is retained until the `Service` is dropped.

*Scenario.* An hour on the tuner tab: tens of events per second, all kept in memory for the session.
(Rough size: ~100 bytes each, so several MB/hour. It is a leak, not an immediate crash.)

*Fix.* Delete the channel (events are already handled via `onEvent`) or use `Channel.CONFLATED`.

### F16. No `CoroutineExceptionHandler`; malformed data can crash the process
`ABM:147-148`; `bluetooth/PresetCodec.kt:54-60`, `117-136`

`appScope` and each `sessionScope` have no handler, so an uncaught exception in any `launch` goes to the
thread's default handler and kills the app. `PresetCodec.Record.asInt` throws on a record whose `raw` is
shorter than four bytes, and `blocks()` calls it for `KEY_SECTION` and `KEY_BLOCK` records.

**Verified.** A `KEY_BLOCK` record with a 2-byte string payload makes `Preset.blocks()` throw
`BufferUnderflowException`. `blocks()` is called outside `runCatching` from `subscribeToParamEvents` (IO
coroutine) and from the UI (composition thread).

*Fix.* Add a handler that logs and fails the session (not the process), make `asInt`/`asFloat` length-safe,
and wrap `blocks()` consumers.

### F17. `PresetCodec.decode` loses six bytes on an unknown or truncated record, so "byte-exact round trip" is not guaranteed
`bluetooth/PresetCodec.kt:155-178`

`i += 6` (key and type) happens before the `when`. For an unknown type (`else -> break`) or a truncated
payload (`break`), the loop exits with `i` already past the header, so the unparsed record's key/type are
not in `tail`. `encode()` then produces a shorter, corrupt body.

**Verified.** A preset with one appended unknown-type record: 216 bytes in, 210 out, not identical; a
truncated float record: 210 in, 204 out.

*Impact today.* `encode()` is only used by `saveLiveToSlot`, which is disabled in the UI but still reachable
via the debug receiver; a future firmware with a new type code would silently corrupt stored tones the moment
Save is re-enabled. *Fix.* Rewind `i` to the record start before `break`, and refuse to `encode()` a preset
whose `tail` is non-empty (or verify `decode(encode(x)) == x` before writing).

### F18. `RecordParser` "resync" produces phantom records; its size limit contradicts the symbol-table limit
`StreamLayer.kt:258-266`; `ABM:738`

On `len > 65536` the parser empties its buffer and `break`s, but the *remaining bytes of the same record*
keep arriving and are parsed as new `{type, len}` headers. There is no framing marker to resync on, so this
cannot recover. Separately `obtainSymbolTable` accepts a table of up to 500 000 bytes while the parser rejects
any record over 65 536.

**Verified.** A single 70 000-byte record fed in 212-byte segments yields **8 724 bogus empty records**
(zeros parsed as `{type 0, len 0}` headers); non-zero data could just as easily yield type-1 "responses".
A header with an implausible length followed by a valid command yields no records at all.

*Scenario.* An amp/firmware whose symbol table exceeds 64 KB: the download fails with a confusing timeout
or "invalid table" message. Any desync leaves wrong data flowing into `responses`.

*Fix.* Raise the limit to match `obtainSymbolTable` (or lower that). Validate `type` against the known set
(1, 2, 3, 4) so garbage is detected early, and on desync mark the service closed so it is reopened.

### F19. Stored data is included in backups
`android/app/src/main/AndroidManifest.xml:10`; `res/xml/backup_rules.xml`, `data_extraction_rules.xml`

`allowBackup="true"` with empty rule files (the unedited templates) means everything under `filesDir` and
SharedPreferences is eligible for Google cloud backup and device-to-device transfer: the amp's MAC address
(`last_mac`), preset names (`preset_names.json`), learned models (`models.json`), the amp's symbol table
(`symbols_<mac>`, data downloaded *from the amp*), tone backups `backups/slot*_*.bin`, and any
`debug_live.bin`/`debug_slot.bin` (`ABM:1206-1207`).

*Fix.* `allowBackup="false"`, or exclude `symbols_*`, `backups/`, `debug_*` and `prefs` explicitly in both
rule files. Excluding the symbol table also keeps manufacturer-derived data out of third-party storage,
which fits the project's own policy. Tone backups are the user's own data and could reasonably be kept.

### F20. The `RECEIVER_NOT_EXPORTED` flag may suppress the Bluetooth broadcasts on Android 12 / 12L (unverified)
`bluetooth/LinkMonitor.kt:87`

On API < 33 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` registers the receiver behind an
app-private signature permission (AndroidX behaviour, from memory; I did not verify it on a device). The
ACL and A2DP broadcasts are sent by the Bluetooth app (a different uid) which cannot hold that permission, so
the receiver may never fire on API 31-32, `linked` would stay empty and every flaky first connect would end in
`awaitUserLink`.

*Check.* Run on an Android 12/12L device and watch `LinkMonitor.linked`. If it is empty, use `RECEIVER_EXPORTED`
(these are protected system broadcasts; spoofing is not possible on API 33+, and the receiver only reads the intent).

### F21. No foreground service and no handling of app backgrounding
`ABM` as a whole; `AndroidManifest.xml`

The connection lives only in a ViewModel. In the background the process becomes cacheable; on Android 12+
(and the cached-app freezer on 14+) its threads can be frozen, so `keepAlive`'s once-a-second heartbeat stops.
The amp then drops the session (it needs the app's heartbeat, per the known behaviours), and on resume the
first `keepAlive` tick sees a huge `quiet` and tears down and reconnects through the "amp ignores new
connects for ~15 s" window.

*Fix.* Decide the intended behaviour: either explicitly disconnect on `onStop` and reconnect on `onStart`
(simple, deterministic), or run a foreground service of type `connectedDevice` (needs the manifest
permission and a notification on Android 14+).

---

## Low

### F22. Wall-clock timers
`ABM:155`, `415`, `443`, `552`, `634`, `793`

Deadlines and the "quiet for N ms" test use `System.currentTimeMillis()`. A clock step (NTP/network time
update, manual change) makes `quiet > LINK_DEAD_MS` true instantly and triggers a needless reconnect, or
suppresses detection. Use `SystemClock.elapsedRealtime()` or `System.nanoTime()`.

### F23. Plain fields shared across threads, and non-atomic `StateFlow` updates
`ABM:148-152`, `158-163`, `166-170`, `1271`; `StreamLayer.kt:123`, `130`

`sessionScope`, `socket`, `input`, `output`, `txSeq`, `rxSeq`, `editor`, `librarian`, `outbox`, `tunerOps`,
`sawFrame` and `ServiceStream.peerEdge`/`rxConsumed` are non-volatile and written on one thread, read on
another. `_hardwareState.value = _hardwareState.value.copy(...)` and `_presetRevision.value + 1` are
read-modify-write. A `setGuitarVolume` on the main thread racing `fail()` on IO can resurrect a stale
`CONNECTED`. Use `@Volatile`/`AtomicReference` for the references and `MutableStateFlow.update {}`.

### F24. RSMC frames are built under a lock but written outside it
`ABM:567-579`

`sendPayload` and `sendHeartbeat` (called from `readLoop`, `keepAlive` and the outbox consumer) build the
frame under `txLock` and `sendRaw` writes later. Two threads can write in the opposite order from the order
the sequence numbers were taken, so the amp can see a heartbeat carrying an older `seq` after a payload. The
risk is small because heartbeats are ignored for sequencing, but build-and-write should be one critical section.
Also, `sendHeartbeat` is called on `readLoop`'s thread: a blocked `write` stalls reading.

### F25. A truncated tail frame is delivered as if complete
`StreamLayer.kt:46`; test `aMalformedTailIsKeptRatherThanDropped`

If a frame claims more bytes than the RSMC payload holds, `splitFrames` keeps the short remainder. For a data
segment the short payload is fed to the record stream and counted in `rxConsumed`, silently corrupting the
byte stream. After a CRC-valid RSMC payload this is a protocol violation, so dropping it (and logging) is safer.

### F26. `disconnect()`/`teardown()` do blocking I/O on the calling thread
`ABM:372-405`; `ui/dialogs/ConnectionDialog.kt:151`

The Disconnect button calls `teardown()` on the main thread, which writes `IAP x STOP` and closes the socket.
If the amp's receive buffer is full the write can block the UI. Move it to `appScope`.

### F27. Acknowledging every RSMC frame, including heartbeats
`ABM:532-547`

`onRsmcFrame` answers every received frame, including header-only ones, with a header-only frame. If the amp does
the same this is a ping-pong at link speed. The known behaviour says the amp stops *initiating* heartbeats, so the
amp probably does not echo, but the frame rate has not been measured. Verify against a log and consider acking
only payload frames (and sending the once-a-second heartbeat separately).

### F28. 32-bit stream window can wrap
`StreamLayer.kt:123`, `136`, `177`

`window()` is `(4096 + rxConsumed).toInt()` and the amp's edge is read as a *signed* Int. After ~2 GiB in one
stream the numbers go negative. **Verified:** an incoming window of `0x80000100` after `0x7FFFFF00` leaves
`peerEdge` unchanged and credit stuck. Unreachable in a normal session; fix by comparing as unsigned (`toLong() and 0xFFFFFFFFL`)
with a wrap-aware comparison.

### F29. Debug facilities ship in release and the debug receiver is open
`ABM:507`, `1163-1224`, `1347-1368`, `1415-1419`; `android/app/src/debug/AndroidManifest.xml:5-9`

`debugWriteBack`, `debugRestoreBackup`, `debugDiffLiveWithSlot`, `probeTuner`, `experimentalHardwareLoad`
and `importSymbolTable` are in the main source set (R8 is off), and `DebugBridge.manager` holds a static
reference in release builds. In *debug* builds the exported receiver has no permission, so any app on the phone
can send `AMPDBG` and trigger `save`/`writeback`/`restore` against the amp's stored slots (the very operations
the owner found risky), or `symbols` to make the app read an arbitrary path. Add
`android:permission="android.permission.DUMP"` (held by `adb shell`) and move the helpers into the debug
source set behind an interface.

### F30. Smaller state-handling issues
- `badSlots`, `subscribed`, `undoBody`, `_ampPresets`, `_models`, `_paramInfo`, `_masterVolume`, `_slotCount` are not
  cleared per connection or per amp (`teardown`, `ABM:380-405`). Names cached for amp A show while connected to amp B,
  and `preset_names.json`/`models.json` hold one MAC at a time, so two amps overwrite each other.
- `harvestModels` and `drainParams` write `_status` over error messages (`ABM:934`, `1069`).
- `scanPresets` can run twice at once: the `_scanProgress == null` guard (`ABM:499`, `830`) is a check-then-act.
- `rescanPresets` scans 100 slots regardless of `_slotCount` (`ABM:831`).
- `drainParams` captures `editor` once (`ABM:1062`); after a stream reopen it keeps calling the closed `Service`
  and drops queued edits with "didn't accept that change".
- `awaitUserLink` leaves `needsLink = true` and a stale message after its 10 min timeout (`ABM:343-346`).
- Echo risk (needs hardware): `onParamChanged` (`ABM:1320-1344`) overwrites the local value from any event. If the
  amp echoes our own `SetDspModelParam`, a fast drag can snap the slider back to an older value.
- `LinkMonitor.set` removes the amp on `ACL_DISCONNECTED` and A2DP/headset events only re-add it, so the "linked"
  view can lag reality (`bluetooth/LinkMonitor.kt:60-69`).

### F31. `tools/` quality
- `tools/parse_snoop.py:196-211` splits only the *first* BSP frame of an RSMC payload (the app fixed the same bug
  in `Bsp.splitFrames`), ignores the 4-byte padding, and prints the first 8 bytes of the stream envelope as
  `cmdID`/`argLen`. Output is misleading for multi-frame payloads and for all type-1 frames.
- `parse_snoop.py:20` does `sys.path.insert(0, ".")`; it imports `bsp`/`rsmc` from the *current directory*, so running
  it in a folder holding someone else's `rsmc.py` runs that code. Use `Path(__file__).parent`.
- `parse_snoop.py:146,165` keeps one L2CAP reassembler per direction, not per ACL handle, so concurrent links
  (the amp is also an audio sink) can interleave fragments.
- `rsmc.py` `Decoder` resets after a bad header (loses the whole 12 bytes and a following frame), while the Kotlin
  `RsmcDecoder` drops one byte and resyncs. **Verified** with the `0x55` + frame case: Kotlin recovers it, Python
  documents losing it (`test_rsmc.py`, "stray 0x55"). The two should be specified to agree, or the Python version
  should be described as "the amp's behaviour" in a comment, as the test already half-does.
- `test_rsmc.py`/`test_bsp.py` execute at import with a hand-rolled `check()`; pytest would collect nothing, and
  there is no CI.

## Nit

- N1. `RsmcDecoder` uses `ArrayDeque<Byte>` (boxing) and `ByteArray(12) { buf[it] }` for every iteration
  (`AmplifiProtocol.kt:257-293`). Fine at these rates; a `ByteArray` ring would be cleaner.
- N2. `RecordParser.feed` reallocates the whole buffer per segment (`buf += data`, `copyOfRange`), O(n²) for large
  records such as a symbol table (`StreamLayer.kt:256-264`).
- N3. `SymbolTable.parse` computes `HEADER + count * ENTRY` in `Int`; harmless today because `looksValid` bounds
  `count`, but `SymbolTable(raw)` is a public constructor (`SymbolTable.kt:63-65`).
- N4. `TunerEngine.publishNote` uses strict `<`, so the two E strings (same pitch class) always resolve to the
  low E in auto mode; octave 4 is never shown (`data/TunerEngine.kt:387-390`). `selectString` and `setActive` use
  `copy` without `update {}`.
- N5. Cache files are written in place (`writeText`/`writeBytes`, `ABM:752`, `1014`, `1404`); write to a temp file
  and rename. A crash mid-write is already tolerated by validation, so this is hygiene only.

---

## Things checked and found correct

- RSMC `seq`/`ack` wrap, window test, expected-seq test (`ABM:540`, `553`, `569`).
- `ServiceStream` ctr wrap (`StreamLayer.kt:190`), duplicate suppression, `Long` credit maths, MTU enforcement,
  and the window-never-shrinks rule. `Bsp.splitFrames` padding rule.
- Lock ordering: `callLock → streamLock`, `txLock` alone, `senderLock` never nests; no suspend call inside
  `synchronized`; `LatestWinsQueue` is correctly synchronised.
- BLUETOOTH_CONNECT handling: `maxSdkVersion=30` on legacy permissions, the runtime request is gated on `S+`, and
  `SecurityException` is caught on the bonded-device and socket paths.
- Receiver flags: `LinkMonitor` passes an export flag (required on 14+); the manifest has no exported receiver in
  release; the Bluetooth profile proxies are closed in `onServiceConnected`.
- App-private files: only `filesDir` and SharedPreferences are used; nothing goes to external storage.
  `Log.d` traffic dumps are gated by `BuildConfig.DEBUG`; release logs contain slot numbers and tone names but no
  MAC addresses or raw bytes.

## Suggested tests

Pure-Kotlin tests need no Android; extract the decision logic where it is buried in the manager.

| # | Test | Guards |
|---|---|---|
| T1 | Extract the connect-retry decision into a pure function; assert `NOT_ACCEPTED` ends in `fail` after `SESSION_RETRIES` | F1 |
| T2 | `ServiceStream`: ctr 2, 4, 5, 6 must either resync or flag the stream broken (currently delivers only the first) | F4 |
| T3 | `RsmcDecoder`: a new instance after a partial frame decodes the next heartbeat (and a `reset()` method does the same); stray `0x55` before sync; header-CRC and payload-CRC fail then recover; random chunking fuzz against the Python vectors (`crc16("123456789") == 0x29B1`) | F6; there are currently **no** Kotlin tests for `RsmcDecoder` or `packRsmcFrame` |
| T4 | `packRsmcFrame` ↔ `RsmcDecoder` round trip, `seq`/`ack` of 0, 255, with 0 and 65 535-byte payloads | codec |
| T5 | `RecordParser`: a 70 000-byte record in 212-byte pieces yields one record (or a deliberate error), never thousands of phantom records; unknown `type` flagged | F18 |
| T6 | `PresetCodec`: unknown-type and truncated record keep `encode(decode(x)) == x` or are rejected; a short `KEY_BLOCK` payload does not throw from `blocks()` | F16, F17 |
| T7 | `ServiceStream.window()`/`credit()` around 2^31 and after 300 segments; ctr wraps 255 → 0 → 1 through `needsAck` | F28 |
| T8 | A fake `Service`/stream driven with `kotlinx-coroutines-test`: extract `call()`, `connectLoop` and `reopenStream` behind an interface (`Transport`) so tests can drive them with virtual time. Cases: reply arrives late, reply never arrives, EOF during handshake, two `scheduleReconnect()` in a row, `disconnect()` during connect, `closed` flag then reopen then another close | F3, F5, F8, F9 |
| T9 | `LatestWinsQueue` under two writers and one reader (threads) | existing class, low risk |
| T10 | `scanPresets` merge behaviour: partial scan must not erase cached names | F10 |
| T11 | Python: make the tools runnable with `pytest`, add a cross-check that both decoders agree on a shared set of byte vectors | F31 |

## Suggested order of work

1. F1 (one-line rename, high value), F15 (delete the channel), F12 (close the socket).
2. Introduce a per-session object (decoder, streams, input/output, token) and one serialised connection
   state machine: F5, F6, F2, F13.
3. Stall handling: F3, F4, F8, F9, F11. This is the core of the "looks connected but is dead" problem.
4. Backup rules (F19), exception handler (F16), `PresetCodec` fix (F17), parser limit (F18).
5. Android lifecycle decisions (F14, F20, F21), then the Low items.
