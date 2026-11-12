package com.github.vermilion10.pancar.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.github.vermilion10.pancar.settings.Platform
import com.github.vermilion10.pancar.settings.Quality
import com.github.vermilion10.pancar.settings.StreamSettings
import com.github.vermilion10.pancar.stream.StreamStatus
import com.github.vermilion10.pancar.stream.formatBitrate
import com.github.vermilion10.pancar.stream.formatElapsed
import com.github.vermilion10.pancar.stream.isActive
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: StreamSettings,
    status: StreamStatus,
    bitrate: Long,
    micMuted: Boolean,
    overlayAllowed: Boolean,
    onSettingsChange: (StreamSettings) -> Unit,
    onGoLive: () -> Unit,
    onStop: () -> Unit,
    onToggleMic: () -> Unit,
    onEnableOverlay: () -> Unit,
    onDismissError: () -> Unit,
) {
    val active = status.isActive
    Scaffold(
        topBar = { TopAppBar(title = { Text("Pancar", fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.navigationBarsPadding().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active && settings.micEnabled) {
                        FilledTonalIconButton(onClick = onToggleMic) {
                            Icon(
                                if (micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                contentDescription = if (micMuted) "Unmute microphone" else "Mute microphone",
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    if (active) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.weight(1f).height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) { Text("Stop stream") }
                    } else {
                        Button(
                            onClick = onGoLive,
                            enabled = settings.isReady,
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Text("Go live", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(status, bitrate, onDismissError)
            if (!overlayAllowed) OverlayHint(onEnableOverlay)
            DestinationSection(settings, enabled = !active, onSettingsChange)
            QualitySection(settings, enabled = !active, onSettingsChange)
            AudioSection(settings, enabled = !active, onSettingsChange)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusCard(status: StreamStatus, bitrate: Long, onDismissError: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(status) {
        while (status is StreamStatus.Live || status is StreamStatus.Reconnecting) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val scheme = MaterialTheme.colorScheme
    val (title, detail, color) = when (status) {
        StreamStatus.Idle -> Triple("Offline", "Add your stream key, then tap Go live and pick the screen or game to share.", scheme.outline)
        StreamStatus.Connecting -> Triple("Connecting…", "Starting capture and contacting the server.", scheme.tertiary)
        is StreamStatus.Live -> Triple("LIVE  ${formatElapsed(now - status.since)}", "Sending ${formatBitrate(bitrate)}", scheme.primary)
        is StreamStatus.Reconnecting -> Triple("Reconnecting…", "Attempt ${status.attempt}: ${status.reason}", scheme.tertiary)
        is StreamStatus.Failed -> Triple("Stream stopped", status.reason, scheme.error)
    }
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (status is StreamStatus.Failed) TextButton(onClick = onDismissError) { Text("OK") }
        }
    }
}

@Composable
private fun OverlayHint(onEnable: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Allow the floating bubble to see your live time and stop the stream without leaving the game.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEnable) { Text("Allow") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun DestinationSection(settings: StreamSettings, enabled: Boolean, onChange: (StreamSettings) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    val platform = Platform.of(settings.serverUrl)
    Section("Destination") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Platform.entries.forEach { p ->
                FilterChip(
                    selected = p == platform,
                    enabled = enabled,
                    onClick = { p.server?.let { onChange(settings.copy(serverUrl = it)) } },
                    label = { Text(p.label) },
                )
            }
        }
        OutlinedTextField(
            value = settings.serverUrl,
            onValueChange = { onChange(settings.copy(serverUrl = it.trim())) },
            label = { Text("Server URL") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.streamKey,
            onValueChange = { onChange(settings.copy(streamKey = it.trim())) },
            label = { Text("Stream key") },
            singleLine = true,
            enabled = enabled,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showKey) "Hide stream key" else "Show stream key",
                    )
                }
            },
            supportingText = { Text("Copy it from your platform's live dashboard. Never share it.") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QualitySection(settings: StreamSettings, enabled: Boolean, onChange: (StreamSettings) -> Unit) {
    Section("Quality") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q ->
                FilterChip(
                    selected = q == settings.quality,
                    enabled = enabled,
                    onClick = { onChange(settings.copy(quality = q)) },
                    label = { Text(q.label) },
                )
            }
        }
        Text(
            "Up to ${formatBitrate(settings.quality.bitrate.toLong())}. Lowered automatically when your connection struggles.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AudioSection(settings: StreamSettings, enabled: Boolean, onChange: (StreamSettings) -> Unit) {
    Section("Audio") {
        VolumeSlider("Game audio", settings.gameVolume, enabled) { onChange(settings.copy(gameVolume = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Microphone", Modifier.weight(1f))
            Switch(
                checked = settings.micEnabled,
                enabled = enabled,
                onCheckedChange = { onChange(settings.copy(micEnabled = it)) },
            )
        }
        if (settings.micEnabled) {
            VolumeSlider("Mic volume", settings.micVolume, enabled) { onChange(settings.copy(micVolume = it)) }
        }
        Text(
            "Some games block audio capture. If yours is silent on stream, the game has opted out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VolumeSlider(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = Color.Unspecified)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..2f, enabled = enabled)
    }
}
