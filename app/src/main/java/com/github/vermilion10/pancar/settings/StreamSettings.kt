package com.github.vermilion10.pancar.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.vermilion10.pancar.youtube.Privacy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Quality(val label: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
    HD30("720p 30", 1280, 720, 30, 3_000_000),
    HD60("720p 60", 1280, 720, 60, 4_500_000),
    FHD30("1080p 30", 1920, 1080, 30, 4_500_000),
    FHD60("1080p 60", 1920, 1080, 60, 6_000_000),
}

enum class Platform(val label: String, val server: String?, val keyHint: String) {
    YOUTUBE(
        "YouTube", "rtmp://a.rtmp.youtube.com/live2",
        "YouTube Studio → Go live → Stream. Copy the stream key.",
    ),
    FACEBOOK(
        "Facebook", "rtmps://live-api-s.facebook.com:443/rtmp/",
        "Facebook Live Producer → Streaming software. Turn on a persistent stream key so it never changes.",
    ),
    TWITCH(
        "Twitch", "rtmp://live.twitch.tv/app",
        "Twitch Creator Dashboard → Settings → Stream. Copy the primary stream key.",
    ),
    CUSTOM("Custom", null, "Any RTMP or RTMPS server. Paste its URL and key.");

    companion object {
        fun of(server: String): Platform = entries.firstOrNull { it.server == server } ?: CUSTOM
    }
}

// A destination reached with a server URL and stream key (Facebook, Twitch, or any RTMP server).
data class KeyTarget(
    val id: String = UUID.randomUUID().toString(),
    val serverUrl: String = Platform.FACEBOOK.server!!,
    val streamKey: String = "",
    val enabled: Boolean = true,
) {
    val platform: Platform get() = Platform.of(serverUrl)
    val isReady: Boolean get() = serverUrl.isNotBlank() && streamKey.isNotBlank()
    val endpoint: String get() = serverUrl.trim().trimEnd('/') + "/" + streamKey.trim()
    val name: String
        get() = if (platform != Platform.CUSTOM) platform.label
        else Uri.parse(serverUrl.trim()).host?.removePrefix("www.") ?: "Custom"
}

data class StreamSettings(
    val youtubeEnabled: Boolean = true,
    val youtubeChannel: String? = null,
    val broadcastTitle: String = "",
    val privacy: Privacy = Privacy.UNLISTED,
    val targets: List<KeyTarget> = emptyList(),
    val quality: Quality = Quality.HD60,
    val micEnabled: Boolean = true,
    val micVolume: Float = 1f,
    val gameVolume: Float = 1f,
) {
    val useYouTube: Boolean get() = youtubeEnabled && youtubeChannel != null
    val activeTargets: List<KeyTarget> get() = targets.filter { it.enabled && it.isReady }
    val destinationCount: Int get() = (if (useYouTube) 1 else 0) + activeTargets.size
    val isReady: Boolean get() = destinationCount > 0

    val titleOrDefault: String get() = broadcastTitle.trim().ifEmpty { "Live on Pancar" }

    fun writeTo(intent: Intent): Intent = intent
        .putExtra(K_QUALITY, quality.name)
        .putExtra(K_MIC, micEnabled)
        .putExtra(K_MIC_VOL, micVolume)
        .putExtra(K_GAME_VOL, gameVolume)

    companion object {
        const val MAX_TARGETS = 4
        private const val K_QUALITY = "quality"
        private const val K_MIC = "mic_enabled"
        private const val K_MIC_VOL = "mic_volume"
        private const val K_GAME_VOL = "game_volume"

        fun fromIntent(intent: Intent): StreamSettings {
            val d = StreamSettings()
            return StreamSettings(
                quality = qualityOf(intent.getStringExtra(K_QUALITY)),
                micEnabled = intent.getBooleanExtra(K_MIC, d.micEnabled),
                micVolume = intent.getFloatExtra(K_MIC_VOL, d.micVolume),
                gameVolume = intent.getFloatExtra(K_GAME_VOL, d.gameVolume),
            )
        }

        internal fun qualityOf(name: String?) = Quality.entries.firstOrNull { it.name == name } ?: Quality.HD60
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val quality = stringPreferencesKey("quality")
        val mic = booleanPreferencesKey("mic_enabled")
        val micVolume = floatPreferencesKey("mic_volume")
        val gameVolume = floatPreferencesKey("game_volume")
        val youtubeEnabled = booleanPreferencesKey("youtube_enabled")
        val channel = stringPreferencesKey("youtube_channel")
        val title = stringPreferencesKey("broadcast_title")
        val privacy = stringPreferencesKey("privacy")
        val targets = stringPreferencesKey("targets")

        // Single-destination settings from before multistreaming.
        val legacyServer = stringPreferencesKey("server_url")
        val legacyKey = stringPreferencesKey("stream_key")
        val legacyDestination = stringPreferencesKey("destination")
    }

    val settings: Flow<StreamSettings> = context.dataStore.data.map(::read)

    suspend fun save(settings: StreamSettings) {
        context.dataStore.edit { p ->
            p[Keys.quality] = settings.quality.name
            p[Keys.mic] = settings.micEnabled
            p[Keys.micVolume] = settings.micVolume
            p[Keys.gameVolume] = settings.gameVolume
            p[Keys.youtubeEnabled] = settings.youtubeEnabled
            settings.youtubeChannel?.let { p[Keys.channel] = it } ?: p.remove(Keys.channel)
            p[Keys.title] = settings.broadcastTitle
            p[Keys.privacy] = settings.privacy.name
            p[Keys.targets] = encodeTargets(settings.targets)
            p.remove(Keys.legacyServer)
            p.remove(Keys.legacyKey)
            p.remove(Keys.legacyDestination)
        }
    }

    private fun read(p: Preferences): StreamSettings {
        val d = StreamSettings()
        val legacyKeyMode = p[Keys.legacyDestination] == "STREAM_KEY"
        val targets = p[Keys.targets]?.let(::decodeTargets) ?: legacyTargets(p, legacyKeyMode)
        return StreamSettings(
            youtubeEnabled = p[Keys.youtubeEnabled] ?: !legacyKeyMode,
            youtubeChannel = p[Keys.channel],
            broadcastTitle = p[Keys.title] ?: d.broadcastTitle,
            privacy = Privacy.entries.firstOrNull { it.name == p[Keys.privacy] } ?: d.privacy,
            targets = targets,
            quality = StreamSettings.qualityOf(p[Keys.quality]),
            micEnabled = p[Keys.mic] ?: d.micEnabled,
            micVolume = p[Keys.micVolume] ?: d.micVolume,
            gameVolume = p[Keys.gameVolume] ?: d.gameVolume,
        )
    }

    private fun legacyTargets(p: Preferences, enabled: Boolean): List<KeyTarget> {
        val key = p[Keys.legacyKey].orEmpty()
        if (key.isBlank()) return emptyList()
        val server = p[Keys.legacyServer] ?: Platform.YOUTUBE.server!!
        return listOf(KeyTarget(serverUrl = server, streamKey = key, enabled = enabled))
    }

    private fun encodeTargets(targets: List<KeyTarget>): String = JSONArray().apply {
        targets.forEach {
            put(JSONObject().put("id", it.id).put("server", it.serverUrl).put("key", it.streamKey).put("enabled", it.enabled))
        }
    }.toString()

    private fun decodeTargets(json: String): List<KeyTarget> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            KeyTarget(o.getString("id"), o.optString("server"), o.optString("key"), o.optBoolean("enabled", true))
        }
    }.getOrDefault(emptyList())
}
