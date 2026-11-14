package com.github.vermilion10.pancar.youtube

import android.util.Log
import com.github.vermilion10.pancar.stream.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class YouTubeException(message: String, val httpCode: Int = 0) : Exception(message)

enum class Privacy(val label: String, val apiValue: String) {
    PUBLIC("Public", "public"),
    UNLISTED("Unlisted", "unlisted"),
    PRIVATE("Private", "private"),
}

data class Broadcast(val id: String, val ingestUrl: String, val streamKey: String, val liveChatId: String?) {
    val watchUrl: String get() = "https://youtu.be/$id"
}

// Minimal YouTube Data API v3 client for live streaming.
class YouTubeApi(private val accessToken: String) {

    companion object {
        private const val TAG = "YouTubeApi"
        private const val BASE = "https://www.googleapis.com/youtube/v3"
        private const val STREAM_TITLE = "Pancar"
        private const val CATEGORY_GAMING = "20"
    }

    suspend fun channelTitle(): String {
        val items = request("GET", "/channels?part=snippet&mine=true").optJSONArray("items")
        if (items == null || items.length() == 0) {
            throw YouTubeException("This Google account has no YouTube channel yet. Create one on YouTube first.")
        }
        return items.getJSONObject(0).getJSONObject("snippet").getString("title")
    }

    // Creates a broadcast that starts as soon as video arrives and ends when the stream stops.
    suspend fun createBroadcast(title: String, privacy: Privacy): Broadcast {
        val stream = reusableStream()
        releaseStream(stream.id)
        val body = JSONObject()
            .put("snippet", JSONObject()
                .put("title", title)
                .put("scheduledStartTime", Instant.now().toString()))
            .put("status", JSONObject()
                .put("privacyStatus", privacy.apiValue)
                .put("selfDeclaredMadeForKids", false))
            .put("contentDetails", JSONObject()
                .put("enableAutoStart", true)
                .put("enableAutoStop", true)
                .put("latencyPreference", "low")
                .put("monitorStream", JSONObject().put("enableMonitorStream", false)))
        val created = request("POST", "/liveBroadcasts?part=snippet,status,contentDetails", body)
        val broadcastId = created.getString("id")
        val chatId = created.optJSONObject("snippet")?.optString("liveChatId")?.takeIf { it.isNotBlank() }
        request("POST", "/liveBroadcasts/bind?id=$broadcastId&streamId=${stream.id}&part=id")
        setGamingCategory(broadcastId, title)
        return Broadcast(broadcastId, stream.ingestUrl, stream.key, chatId)
    }

    // Video goes to whichever broadcast on the stream is already live, so older ones must let go first.
    private suspend fun releaseStream(streamId: String) {
        for (filter in listOf("active", "upcoming")) {
            val items = runCatching {
                request("GET", "/liveBroadcasts?part=id,status,contentDetails&broadcastStatus=$filter&broadcastType=all&maxResults=50")
            }.getOrNull()?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optJSONObject("contentDetails")?.optString("boundStreamId") != streamId) continue
                val id = item.getString("id")
                val lifeCycle = item.optJSONObject("status")?.optString("lifeCycleStatus")
                runCatching {
                    if (lifeCycle == "live" || lifeCycle == "testing") {
                        request("POST", "/liveBroadcasts/transition?broadcastStatus=complete&id=$id&part=id")
                    } else {
                        // Unbinding keeps the broadcast but stops it from catching Pancar's video.
                        request("POST", "/liveBroadcasts/bind?id=$id&part=id")
                    }
                }.onFailure { Log.w(TAG, "Couldn't release broadcast $id: ${it.message}") }
            }
        }
    }

    data class ChatPage(val messages: List<ChatMessage>, val nextPageToken: String?, val pollIntervalMs: Long)

    suspend fun chatMessages(liveChatId: String, pageToken: String?): ChatPage {
        val page = pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val chat = URLEncoder.encode(liveChatId, "UTF-8")
        val json = request("GET", "/liveChat/messages?liveChatId=$chat&part=snippet,authorDetails&maxResults=200$page")
        val items = json.optJSONArray("items") ?: JSONArray()
        val messages = (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val text = item.optJSONObject("snippet")?.optString("displayMessage").orEmpty()
            val author = item.optJSONObject("authorDetails")?.optString("displayName").orEmpty()
            if (text.isBlank()) null else ChatMessage(item.getString("id"), author, text)
        }
        return ChatPage(
            messages,
            json.optString("nextPageToken").takeIf { it.isNotBlank() },
            json.optLong("pollingIntervalMillis", 5_000),
        )
    }

    suspend fun concurrentViewers(videoId: String): Long? {
        val items = request("GET", "/videos?part=liveStreamingDetails&id=$videoId").optJSONArray("items")
        val details = items?.optJSONObject(0)?.optJSONObject("liveStreamingDetails") ?: return null
        // YouTube omits the field when nobody is watching.
        return details.optString("concurrentViewers").toLongOrNull()
            ?: if (details.has("actualStartTime") && !details.has("actualEndTime")) 0L else null
    }

    private data class Stream(val id: String, val ingestUrl: String, val key: String)

    // One reusable ingest stream per channel, so the key never changes between broadcasts.
    private suspend fun reusableStream(): Stream {
        val items = request("GET", "/liveStreams?part=id,snippet,cdn&mine=true&maxResults=50")
            .optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val cdn = item.getJSONObject("cdn")
            if (item.getJSONObject("snippet").optString("title") == STREAM_TITLE && cdn.optString("ingestionType") == "rtmp") {
                return item.toStream()
            }
        }
        val body = JSONObject()
            .put("snippet", JSONObject().put("title", STREAM_TITLE))
            .put("cdn", JSONObject()
                .put("ingestionType", "rtmp")
                .put("resolution", "variable")
                .put("frameRate", "variable"))
            .put("contentDetails", JSONObject().put("isReusable", true))
        return request("POST", "/liveStreams?part=id,snippet,cdn,contentDetails", body).toStream()
    }

    private fun JSONObject.toStream(): Stream {
        val info = getJSONObject("cdn").getJSONObject("ingestionInfo")
        // RTMPS keeps the stream key off the wire in plain text.
        val address = info.optString("rtmpsIngestionAddress").ifBlank { info.getString("ingestionAddress") }
        return Stream(getString("id"), address, info.getString("streamName"))
    }

    private suspend fun setGamingCategory(videoId: String, title: String) {
        val body = JSONObject()
            .put("id", videoId)
            .put("snippet", JSONObject().put("title", title).put("categoryId", CATEGORY_GAMING))
        // Not critical for going live.
        runCatching { request("PUT", "/videos?part=snippet", body) }
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (body != null || method == "POST") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw YouTubeException(errorMessage(code, text), code)
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (e: IOException) {
                throw YouTubeException("Couldn't reach YouTube: ${e.message}")
            } finally {
                conn.disconnect()
            }
        }

    private fun errorMessage(code: Int, body: String): String {
        val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        return when {
            code == 401 -> "Your YouTube sign-in expired. Sign in again."
            reason == "liveStreamingNotEnabled" ->
                "Live streaming isn't enabled on this channel. Enable it in YouTube Studio (it can take up to 24 hours)."
            reason == "quotaExceeded" -> "YouTube API quota for today is used up. Use a stream key instead."
            reason == "insufficientLivePermissions" || reason == "insufficientPermissions" ->
                "Pancar wasn't given permission to manage your YouTube live streams. Sign in again and allow it."
            else -> error?.optString("message")?.takeIf { it.isNotBlank() } ?: "YouTube error $code"
        }
    }
}
