package com.github.vermilion10.pancar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.github.vermilion10.pancar.settings.Destination
import com.github.vermilion10.pancar.settings.SettingsRepository
import com.github.vermilion10.pancar.settings.StreamSettings
import com.github.vermilion10.pancar.stream.StreamController
import com.github.vermilion10.pancar.stream.StreamService
import com.github.vermilion10.pancar.stream.StreamStatus
import com.github.vermilion10.pancar.ui.HomeScreen
import com.github.vermilion10.pancar.ui.theme.PancarTheme
import com.github.vermilion10.pancar.youtube.AuthOutcome
import com.github.vermilion10.pancar.youtube.YouTubeApi
import com.github.vermilion10.pancar.youtube.YouTubeAuth
import com.github.vermilion10.pancar.youtube.YouTubeException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: SettingsRepository
    private var draft by mutableStateOf<StreamSettings?>(null)
    private var signingIn by mutableStateOf(false)
    private var pending: StreamSettings? = null
    private var prepareJob: Job? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val settings = pending ?: return@registerForActivityResult
            pending = null
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult
            when (settings.destination) {
                Destination.STREAM_KEY -> StreamService.start(this, result.resultCode, data, settings)
                Destination.YOUTUBE_ACCOUNT -> startYouTube(settings, result.resultCode, data)
            }
        }

    // Proceeds even if denied: the stream then runs without audio or without the notification.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requestCapture() }

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                signingIn = false
                toast("Sign-in was cancelled.")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                try {
                    finishSignIn(YouTubeAuth.tokenFromConsent(this@MainActivity, result.data))
                } catch (e: YouTubeException) {
                    signingIn = false
                    toast(e.message)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = SettingsRepository(applicationContext)
        lifecycleScope.launch { if (draft == null) draft = repository.settings.first() }

        setContent {
            PancarTheme {
                val status by StreamController.status.collectAsStateWithLifecycle()
                val bitrate by StreamController.bitrate.collectAsStateWithLifecycle()
                val micMuted by StreamController.micMuted.collectAsStateWithLifecycle()
                val watchUrl by StreamController.watchUrl.collectAsStateWithLifecycle()

                var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
                LifecycleResumeEffect(Unit) {
                    overlayAllowed = Settings.canDrawOverlays(this@MainActivity)
                    onPauseOrDispose { }
                }

                val settings = draft ?: return@PancarTheme
                HomeScreen(
                    settings = settings,
                    status = status,
                    bitrate = bitrate,
                    micMuted = micMuted,
                    overlayAllowed = overlayAllowed,
                    watchUrl = watchUrl,
                    signingIn = signingIn,
                    onSignIn = ::signIn,
                    onSignOut = { updateSettings { it.copy(youtubeChannel = null) } },
                    onShareWatchUrl = ::shareLink,
                    onSettingsChange = { updated -> updateSettings { updated } },
                    onGoLive = { goLive(settings) },
                    onStop = ::stopStream,
                    onToggleMic = { StreamService.toggleMic(this) },
                    onEnableOverlay = {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    },
                    onDismissError = StreamController::dismissError,
                )
            }
        }
    }

    private fun updateSettings(transform: (StreamSettings) -> StreamSettings) {
        val current = draft ?: return
        val updated = transform(current)
        draft = updated
        lifecycleScope.launch { repository.save(updated) }
    }

    private fun signIn() {
        signingIn = true
        lifecycleScope.launch {
            try {
                when (val outcome = YouTubeAuth.authorize(this@MainActivity)) {
                    is AuthOutcome.Token -> finishSignIn(outcome.accessToken)
                    is AuthOutcome.NeedsConsent ->
                        consentLauncher.launch(IntentSenderRequest.Builder(outcome.intent).build())
                }
            } catch (e: YouTubeException) {
                signingIn = false
                toast(e.message)
            }
        }
    }

    private suspend fun finishSignIn(token: String) {
        try {
            val channel = YouTubeApi(token).channelTitle()
            updateSettings { it.copy(youtubeChannel = channel) }
        } catch (e: YouTubeException) {
            toast(e.message)
        } finally {
            signingIn = false
        }
    }

    private fun goLive(settings: StreamSettings) {
        StreamController.dismissError()
        pending = settings
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) requestCapture() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    // Creates the broadcast after capture consent, so a cancelled dialog leaves nothing on the channel.
    private fun startYouTube(settings: StreamSettings, resultCode: Int, projectionData: Intent) {
        StreamController.preparing()
        prepareJob = lifecycleScope.launch {
            try {
                val token = when (val outcome = YouTubeAuth.authorize(this@MainActivity)) {
                    is AuthOutcome.Token -> outcome.accessToken
                    is AuthOutcome.NeedsConsent -> {
                        updateSettings { it.copy(youtubeChannel = null) }
                        throw YouTubeException("Your YouTube sign-in expired. Sign in again.")
                    }
                }
                val broadcast = YouTubeApi(token).createBroadcast(settings.titleOrDefault, settings.privacy)
                StreamController.setWatchUrl(broadcast.watchUrl)
                val target = settings.copy(serverUrl = broadcast.ingestUrl, streamKey = broadcast.streamKey)
                StreamService.start(this@MainActivity, resultCode, projectionData, target)
            } catch (e: YouTubeException) {
                StreamController.stopped(e.message)
            }
        }
    }

    private fun stopStream() {
        if (StreamController.status.value is StreamStatus.Preparing) {
            prepareJob?.cancel()
            StreamController.stopped(null)
        } else {
            StreamService.stop(this)
        }
    }

    private fun shareLink(url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, "Share stream link"))
    }

    private fun toast(message: String?) {
        Toast.makeText(this, message ?: "Something went wrong.", Toast.LENGTH_LONG).show()
    }
}
