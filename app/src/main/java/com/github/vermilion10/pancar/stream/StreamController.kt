package com.github.vermilion10.pancar.stream

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface StreamStatus {
    data object Idle : StreamStatus
    data object Preparing : StreamStatus
    data object Connecting : StreamStatus
    data class Live(val since: Long) : StreamStatus
    data class Reconnecting(val since: Long?, val attempt: Int, val reason: String) : StreamStatus
    data class Failed(val reason: String) : StreamStatus
}

data class ChatMessage(val id: String, val author: String, val text: String)

sealed interface LinkState {
    data object Connecting : LinkState
    data object Live : LinkState
    data class Reconnecting(val attempt: Int, val reason: String) : LinkState
    data class Failed(val reason: String) : LinkState
}

data class DestinationLink(val name: String, val state: LinkState)

val StreamStatus.isActive: Boolean
    get() = this is StreamStatus.Preparing || this is StreamStatus.Connecting || this is StreamStatus.Live || this is StreamStatus.Reconnecting

// Shared state between StreamService (writer) and the UI and bubble (readers).
object StreamController {
    private val _status = MutableStateFlow<StreamStatus>(StreamStatus.Idle)
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    private val _bitrate = MutableStateFlow(0L)
    val bitrate: StateFlow<Long> = _bitrate.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _watchUrl = MutableStateFlow<String?>(null)
    val watchUrl: StateFlow<String?> = _watchUrl.asStateFlow()

    private val _privacy = MutableStateFlow(false)
    val privacy: StateFlow<Boolean> = _privacy.asStateFlow()

    private val _viewers = MutableStateFlow<Long?>(null)
    val viewers: StateFlow<Long?> = _viewers.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _links = MutableStateFlow<List<DestinationLink>>(emptyList())
    val links: StateFlow<List<DestinationLink>> = _links.asStateFlow()

    private const val CHAT_LIMIT = 100

    private var liveSince: Long? = null

    internal fun preparing() {
        _watchUrl.value = null
        _status.value = StreamStatus.Preparing
    }

    internal fun setWatchUrl(url: String?) {
        _watchUrl.value = url
    }

    internal fun connecting(names: List<String>) {
        liveSince = null
        _links.value = names.map { DestinationLink(it, LinkState.Connecting) }
        _bitrate.value = 0
        _micMuted.value = false
        _privacy.value = false
        _viewers.value = null
        _chat.value = emptyList()
        _status.value = StreamStatus.Connecting
    }

    // Overall status follows the healthiest destination.
    internal fun setLink(index: Int, state: LinkState) {
        val links = _links.value.toMutableList()
        if (index !in links.indices) return
        links[index] = links[index].copy(state = state)
        _links.value = links
        val live = links.any { it.state is LinkState.Live }
        val retrying = links.mapNotNull { it.state as? LinkState.Reconnecting }.maxByOrNull { it.attempt }
        _status.value = when {
            live -> StreamStatus.Live(liveSince ?: SystemClock.elapsedRealtime().also { liveSince = it })
            retrying != null -> StreamStatus.Reconnecting(liveSince, retrying.attempt, retrying.reason)
            else -> StreamStatus.Connecting
        }
    }

    internal fun stopped(failure: String?) {
        liveSince = null
        _bitrate.value = 0
        _watchUrl.value = null
        _privacy.value = false
        _viewers.value = null
        _links.value = emptyList()
        _status.value = failure?.let { StreamStatus.Failed(it) } ?: StreamStatus.Idle
    }

    internal fun setPrivacy(on: Boolean) {
        _privacy.value = on
    }

    internal fun setViewers(count: Long?) {
        _viewers.value = count
    }

    internal fun addChat(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val seen = _chat.value.mapTo(HashSet()) { it.id }
        _chat.value = (_chat.value + messages.filter { it.id !in seen }).takeLast(CHAT_LIMIT)
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
