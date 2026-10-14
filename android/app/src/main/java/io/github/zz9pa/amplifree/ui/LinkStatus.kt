package io.github.zz9pa.amplifree.ui

import android.content.res.Resources
import io.github.zz9pa.amplifree.R
import io.github.zz9pa.amplifree.data.ConnectionState

/** What the screens need to know about the amp link, in one value. */
enum class LinkStatus {
    /** Nothing connected and nothing in progress. */
    DISCONNECTED,

    /** Paired in Android but not yet linked: the user has to tap the amp in Bluetooth settings. */
    NEEDS_LINK,

    /** Connecting, or connected and still setting up the session. */
    CONNECTING,

    /** Session is up; commands can be sent. */
    READY;

    val isReady: Boolean get() = this == READY

    companion object {
        fun of(state: ConnectionState, ready: Boolean, needsLink: Boolean): LinkStatus = when {
            state == ConnectionState.CONNECTED && ready -> READY
            needsLink -> NEEDS_LINK
            state == ConnectionState.SCANNING || state == ConnectionState.CONNECTED -> CONNECTING
            else -> DISCONNECTED
        }
    }
}

enum class StatusKind { INFO, BUSY, ERROR }

/** A status message ready to show: [text] is readable, [kind] says how to present it. */
data class FriendlyStatus(val text: String, val kind: StatusKind) {
    val isBusy: Boolean get() = kind == StatusKind.BUSY
    val isError: Boolean get() = kind == StatusKind.ERROR
}

private val MODELS_PROGRESS = Regex("""(\d+)/(\d+)""")

/**
 * Turns the connection layer's status line into something readable and classifies it.
 * The wording comes from the Bluetooth code, so this only recognises messages it knows and
 * passes every other message through untouched.
 */
fun friendlyStatus(raw: String, res: Resources): FriendlyStatus {
    val s = raw.trim()
    val lower = s.lowercase()
    return when {
        s.isEmpty() -> FriendlyStatus("", StatusKind.INFO)
        s == "Not connected" -> FriendlyStatus(res.getString(R.string.status_not_connected), StatusKind.INFO)
        s == "Connected" -> FriendlyStatus(res.getString(R.string.status_connected), StatusKind.INFO)
        s == "Connection lost. Reconnecting…" ->
            FriendlyStatus(res.getString(R.string.status_reconnecting), StatusKind.BUSY)
        s == "Opening session…" || s == "Handshaking…" || s == "Waiting for the amp to be ready…" ||
            s.startsWith("Downloading the amp") ->
            FriendlyStatus(res.getString(R.string.status_getting_ready), StatusKind.BUSY)
        s.startsWith("Learning your amp's models") -> {
            val m = MODELS_PROGRESS.find(s)
            val text = if (m != null) {
                res.getString(R.string.status_learning_models, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            } else s
            FriendlyStatus(text, StatusKind.BUSY)
        }
        s.startsWith("Connecting to") || s.startsWith("Loading") -> FriendlyStatus(s, StatusKind.BUSY)
        "didn't" in lower || "couldn't" in lower || "bluetooth is off" in lower ->
            FriendlyStatus(s, StatusKind.ERROR)
        else -> FriendlyStatus(s, StatusKind.INFO)
    }
}
