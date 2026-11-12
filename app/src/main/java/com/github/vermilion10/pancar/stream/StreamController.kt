package com.github.vermilion10.pancar.stream

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface StreamStatus {
    data object Idle : StreamStatus
    data object Connecting : StreamStatus
    data class Live(val since: Long) : StreamStatus
    data class Reconnecting(val since: Long?, val attempt: Int, val reason: String) : StreamStatus
    data class Failed(val reason: String) : StreamStatus
}

val StreamStatus.isActive: Boolean
    get() = this is StreamStatus.Connecting || this is StreamStatus.Live || this is StreamStatus.Reconnecting

// Shared state between StreamService (writer) and the UI and bubble (readers).
object StreamController {
    private val _status = MutableStateFlow<StreamStatus>(StreamStatus.Idle)
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    private val _bitrate = MutableStateFlow(0L)
    val bitrate: StateFlow<Long> = _bitrate.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private var liveSince: Long? = null

    internal fun connecting() {
        liveSince = null
        _bitrate.value = 0
        _micMuted.value = false
        _status.value = StreamStatus.Connecting
    }

    internal fun live() {
        val since = liveSince ?: SystemClock.elapsedRealtime().also { liveSince = it }
        _status.value = StreamStatus.Live(since)
    }

    internal fun reconnecting(attempt: Int, reason: String) {
        _status.value = StreamStatus.Reconnecting(liveSince, attempt, reason)
    }

    internal fun stopped(failure: String?) {
        liveSince = null
        _bitrate.value = 0
        _status.value = failure?.let { StreamStatus.Failed(it) } ?: StreamStatus.Idle
    }

    internal fun setBitrate(bps: Long) {
        _bitrate.value = bps
    }

    internal fun setMicMuted(muted: Boolean) {
        _micMuted.value = muted
    }

    fun dismissError() {
        if (_status.value is StreamStatus.Failed) _status.value = StreamStatus.Idle
    }
}

fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatBitrate(bps: Long): String =
    if (bps >= 1_000_000) String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
    else "${bps / 1000} kbps"
