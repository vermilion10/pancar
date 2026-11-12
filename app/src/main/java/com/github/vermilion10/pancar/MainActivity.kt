package com.github.vermilion10.pancar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.vermilion10.pancar.settings.SettingsRepository
import com.github.vermilion10.pancar.settings.StreamSettings
import com.github.vermilion10.pancar.stream.StreamController
import com.github.vermilion10.pancar.stream.StreamService
import com.github.vermilion10.pancar.ui.HomeScreen
import com.github.vermilion10.pancar.ui.theme.PancarTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: SettingsRepository
    private var pending: StreamSettings? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val settings = pending ?: return@registerForActivityResult
            pending = null
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                StreamService.start(this, result.resultCode, data, settings)
            }
        }

    // Proceeds even if denied: the stream then runs without audio or without the notification.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requestCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = SettingsRepository(applicationContext)

        setContent {
            PancarTheme {
                val scope = rememberCoroutineScope()
                var draft by remember { mutableStateOf<StreamSettings?>(null) }
                LaunchedEffect(Unit) { draft = repository.settings.first() }

                val status by StreamController.status.collectAsStateWithLifecycle()
                val bitrate by StreamController.bitrate.collectAsStateWithLifecycle()
                val micMuted by StreamController.micMuted.collectAsStateWithLifecycle()

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
                    onSettingsChange = { updated ->
                        draft = updated
                        scope.launch { repository.save(updated) }
                    },
                    onGoLive = { goLive(settings) },
                    onStop = { StreamService.stop(this) },
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
}
