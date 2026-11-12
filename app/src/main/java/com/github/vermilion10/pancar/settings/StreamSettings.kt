package com.github.vermilion10.pancar.settings

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class Quality(val label: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
    HD30("720p 30", 1280, 720, 30, 3_000_000),
    HD60("720p 60", 1280, 720, 60, 4_500_000),
    FHD30("1080p 30", 1920, 1080, 30, 4_500_000),
    FHD60("1080p 60", 1920, 1080, 60, 6_000_000),
}

enum class Platform(val label: String, val server: String?) {
    YOUTUBE("YouTube", "rtmp://a.rtmp.youtube.com/live2"),
    FACEBOOK("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp/"),
    TWITCH("Twitch", "rtmp://live.twitch.tv/app"),
    CUSTOM("Custom", null);

    companion object {
        fun of(server: String): Platform = entries.firstOrNull { it.server == server } ?: CUSTOM
    }
}

data class StreamSettings(
    val serverUrl: String = Platform.YOUTUBE.server!!,
    val streamKey: String = "",
    val quality: Quality = Quality.HD60,
    val micEnabled: Boolean = true,
    val micVolume: Float = 1f,
    val gameVolume: Float = 1f,
) {
    val isReady: Boolean get() = serverUrl.isNotBlank() && streamKey.isNotBlank()

    val endpoint: String get() = serverUrl.trim().trimEnd('/') + "/" + streamKey.trim()

    fun writeTo(intent: Intent): Intent = intent
        .putExtra(K_SERVER, serverUrl)
        .putExtra(K_KEY, streamKey)
        .putExtra(K_QUALITY, quality.name)
        .putExtra(K_MIC, micEnabled)
        .putExtra(K_MIC_VOL, micVolume)
        .putExtra(K_GAME_VOL, gameVolume)

    companion object {
        private const val K_SERVER = "server_url"
        private const val K_KEY = "stream_key"
        private const val K_QUALITY = "quality"
        private const val K_MIC = "mic_enabled"
        private const val K_MIC_VOL = "mic_volume"
        private const val K_GAME_VOL = "game_volume"

        fun fromIntent(intent: Intent): StreamSettings {
            val d = StreamSettings()
            return StreamSettings(
                serverUrl = intent.getStringExtra(K_SERVER) ?: d.serverUrl,
                streamKey = intent.getStringExtra(K_KEY) ?: "",
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
        val server = stringPreferencesKey("server_url")
        val key = stringPreferencesKey("stream_key")
        val quality = stringPreferencesKey("quality")
        val mic = booleanPreferencesKey("mic_enabled")
        val micVolume = floatPreferencesKey("mic_volume")
        val gameVolume = floatPreferencesKey("game_volume")
    }

    val settings: Flow<StreamSettings> = context.dataStore.data.map(::read)

    suspend fun save(settings: StreamSettings) {
        context.dataStore.edit { p ->
            p[Keys.server] = settings.serverUrl
            p[Keys.key] = settings.streamKey
            p[Keys.quality] = settings.quality.name
            p[Keys.mic] = settings.micEnabled
            p[Keys.micVolume] = settings.micVolume
            p[Keys.gameVolume] = settings.gameVolume
        }
    }

    private fun read(p: Preferences): StreamSettings {
        val d = StreamSettings()
        return StreamSettings(
            serverUrl = p[Keys.server] ?: d.serverUrl,
            streamKey = p[Keys.key] ?: d.streamKey,
            quality = StreamSettings.qualityOf(p[Keys.quality]),
            micEnabled = p[Keys.mic] ?: d.micEnabled,
            micVolume = p[Keys.micVolume] ?: d.micVolume,
            gameVolume = p[Keys.gameVolume] ?: d.gameVolume,
        )
    }
}
