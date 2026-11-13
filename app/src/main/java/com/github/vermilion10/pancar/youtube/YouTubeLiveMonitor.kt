package com.github.vermilion10.pancar.youtube

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.github.vermilion10.pancar.stream.StreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Polls live chat and viewer count while streaming, at quota-friendly intervals.
class YouTubeLiveMonitor(
    private val context: Context,
    private val videoId: String,
    private val liveChatId: String?,
) {
    companion object {
        private const val TAG = "YouTubeLiveMonitor"
        // liveChatMessages.list costs 5 units; 10 s keeps a day's quota good for about 5 hours.
        private const val MIN_CHAT_INTERVAL_MS = 10_000L
        private const val VIEWERS_INTERVAL_MS = 30_000L
        private const val RETRY_MS = 15_000L
        private const val TOKEN_TTL_MS = 40 * 60_000L
    }

    private var job: Job? = null
    private var token: String? = null
    private var tokenAt = 0L

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            launch { chatLoop() }
            launch { viewersLoop() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun api(): YouTubeApi? {
        val now = SystemClock.elapsedRealtime()
        if (token == null || now - tokenAt > TOKEN_TTL_MS) {
            val outcome = runCatching { YouTubeAuth.authorize(context) }.getOrNull()
            token = (outcome as? AuthOutcome.Token)?.accessToken ?: return null
            tokenAt = now
        }
        return token?.let(::YouTubeApi)
    }

    private suspend fun chatLoop() {
        val chatId = liveChatId ?: return
        var pageToken: String? = null
        while (currentCoroutineContext().isActive) {
            val wait = try {
                val api = api()
                if (api == null) {
                    RETRY_MS
                } else {
                    val page = api.chatMessages(chatId, pageToken)
                    pageToken = page.nextPageToken
                    StreamController.addChat(page.messages)
                    maxOf(page.pollIntervalMs, MIN_CHAT_INTERVAL_MS)
                }
            } catch (e: YouTubeException) {
                if (e.httpCode == 401) token = null
                Log.w(TAG, "chat: ${e.message}")
                RETRY_MS
            }
            delay(wait)
        }
    }

    private suspend fun viewersLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                api()?.concurrentViewers(videoId)?.let(StreamController::setViewers)
            } catch (e: YouTubeException) {
                if (e.httpCode == 401) token = null
                Log.w(TAG, "viewers: ${e.message}")
            }
            delay(VIEWERS_INTERVAL_MS)
        }
    }
}
