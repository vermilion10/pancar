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
import androidx.compose.material3.OutlinedButton
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
import com.github.vermilion10.pancar.settings.KeyTarget
import com.github.vermilion10.pancar.settings.Platform
import com.github.vermilion10.pancar.settings.Quality
import com.github.vermilion10.pancar.settings.StreamSettings
import com.github.vermilion10.pancar.stream.StreamStatus
import com.github.vermilion10.pancar.stream.formatBitrate
import com.github.vermilion10.pancar.stream.formatElapsed
import com.github.vermilion10.pancar.stream.ChatMessage
import com.github.vermilion10.pancar.stream.DestinationLink
import com.github.vermilion10.pancar.stream.LinkState
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.key
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import com.github.vermilion10.pancar.stream.isActive
import com.github.vermilion10.pancar.youtube.Privacy
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
    watchUrl: String?,
    signingIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onShareWatchUrl: (String) -> Unit,
    privacy: Boolean,
    viewers: Long?,
    chat: List<ChatMessage>,
    links: List<DestinationLink>,
    onTogglePrivacy: () -> Unit,
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
                    if (active && status !is StreamStatus.Preparing) {
                        FilledTonalIconButton(onClick = onTogglePrivacy) {
                            Icon(
                                if (privacy) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
                                contentDescription = if (privacy) "Show your screen again" else "Hide your screen",
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    if (status is StreamStatus.Preparing) {
                        Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text("Preparing…")
                        }
                    } else if (active) {
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
            StatusCard(status, bitrate, watchUrl, privacy, viewers, links, onShareWatchUrl, onDismissError)
            if (active && settings.useYouTube) ChatCard(chat)
            if (!overlayAllowed) OverlayHint(onEnableOverlay)
            DestinationSection(settings, enabled = !active, signingIn, onSignIn, onSignOut, onSettingsChange)
            QualitySection(settings, enabled = !active, onSettingsChange)
            AudioSection(settings, enabled = !active, onSettingsChange)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusCard(
    status: StreamStatus,
    bitrate: Long,
    watchUrl: String?,
    privacy: Boolean,
    viewers: Long?,
    links: List<DestinationLink>,
    onShare: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(status) {
        while (status is StreamStatus.Live || status is StreamStatus.Reconnecting) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val scheme = MaterialTheme.colorScheme
    val (title, detail, color) = when (status) {
        StreamStatus.Idle -> Triple("Offline", "Tap Go live, then pick the screen or game to share.", scheme.outline)
        StreamStatus.Preparing -> Triple("Preparing…", "Creating your YouTube broadcast.", scheme.tertiary)
        StreamStatus.Connecting -> Triple("Connecting…", "Starting capture and contacting the server.", scheme.tertiary)
        is StreamStatus.Live -> {
            val parts = buildList {
                add(if (privacy) "Screen hidden: viewers see Be right back" else "Sending ${formatBitrate(bitrate)}")
                if (viewers != null) add("$viewers watching")
            }
            Triple(
                "LIVE  ${formatElapsed(now - status.since)}",
                parts.joinToString("  ·  "),
                if (privacy) scheme.tertiary else scheme.primary,
            )
        }
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
            if (watchUrl != null && status.isActive) TextButton(onClick = { onShare(watchUrl) }) { Text("Share link") }
        }
        if (links.size > 1 && status.isActive) {
            Column(
                Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                links.forEach { LinkRow(it) }
            }
        }
    }
}

@Composable
private fun LinkRow(link: DestinationLink) {
    val scheme = MaterialTheme.colorScheme
    val (text, color) = when (val state = link.state) {
        LinkState.Connecting -> "Connecting…" to scheme.outline
        LinkState.Live -> "Live" to scheme.primary
        is LinkState.Reconnecting -> "Reconnecting, attempt ${state.attempt}" to scheme.tertiary
        is LinkState.Failed -> "Stopped: ${state.reason}" to scheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(link.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatCard(chat: List<ChatMessage>) {
    val recent = chat.takeLast(20)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Live chat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (recent.isEmpty()) {
                Text("No messages yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            recent.forEach { m ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)) {
                            append(m.author)
                        }
                        append("  ")
                        append(m.text)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
private fun DestinationSection(
    settings: StreamSettings,
    enabled: Boolean,
    signingIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onChange: (StreamSettings) -> Unit,
) {
    Section("Destinations") {
        DestinationCard(
            title = "YouTube",
            subtitle = "Your channel, no stream key needed",
            checked = settings.youtubeEnabled,
            enabled = enabled,
            onCheckedChange = { onChange(settings.copy(youtubeEnabled = it)) },
        ) {
            if (settings.youtubeEnabled) YouTubeAccountFields(settings, enabled, signingIn, onSignIn, onSignOut, onChange)
        }
        settings.targets.forEachIndexed { i, target ->
            fun update(t: KeyTarget) = onChange(settings.copy(targets = settings.targets.toMutableList().also { it[i] = t }))
            key(target.id) {
                DestinationCard(
                    title = target.name,
                    subtitle = if (target.isReady) "Stream key" else "Needs a stream key",
                    checked = target.enabled,
                    enabled = enabled,
                    onCheckedChange = { update(target.copy(enabled = it)) },
                    onRemove = { onChange(settings.copy(targets = settings.targets.filterNot { it.id == target.id })) },
                ) {
                    if (target.enabled) StreamKeyFields(target, enabled, ::update)
                }
            }
        }
        if (settings.targets.size < StreamSettings.MAX_TARGETS) {
            OutlinedButton(
                onClick = { onChange(settings.copy(targets = settings.targets + KeyTarget())) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Facebook, Twitch or another server")
            }
        }
        if (settings.useYouTube && settings.activeTargets.any { it.platform == Platform.YOUTUBE }) {
            Text(
                "Your channel will get two separate live streams: one from your account and one from the YouTube stream key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun DestinationCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRemove: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove, enabled = enabled) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remove $title")
                    }
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            }
            content()
        }
    }
}

@Composable
private fun YouTubeAccountFields(
    settings: StreamSettings,
    enabled: Boolean,
    signingIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onChange: (StreamSettings) -> Unit,
) {
    val channel = settings.youtubeChannel
    if (channel == null) {
        Text(
            "Sign in once and Pancar creates the YouTube broadcast for you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onSignIn, enabled = enabled && !signingIn, modifier = Modifier.fillMaxWidth()) {
            Text(if (signingIn) "Signing in…" else "Sign in with Google")
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Channel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(channel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        TextButton(onClick = onSignOut, enabled = enabled) { Text("Sign out") }
    }
    OutlinedTextField(
        value = settings.broadcastTitle,
        onValueChange = { onChange(settings.copy(broadcastTitle = it.take(100))) },
        label = { Text("Stream title") },
        placeholder = { Text(settings.titleOrDefault) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Privacy.entries.forEach { p ->
            FilterChip(
                selected = p == settings.privacy,
                enabled = enabled,
                onClick = { onChange(settings.copy(privacy = p)) },
                label = { Text(p.label) },
            )
        }
    }
}

@Composable
private fun StreamKeyFields(target: KeyTarget, enabled: Boolean, onChange: (KeyTarget) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    val platform = target.platform
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Platform.entries.forEach { p ->
            FilterChip(
                selected = p == platform,
                enabled = enabled,
                onClick = { if (p != platform) onChange(target.copy(serverUrl = p.server ?: "rtmp://")) },
                label = { Text(p.label) },
            )
        }
    }
    OutlinedTextField(
        value = target.serverUrl,
        onValueChange = { onChange(target.copy(serverUrl = it.trim())) },
        label = { Text("Server URL") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = target.streamKey,
        onValueChange = { onChange(target.copy(streamKey = it.trim())) },
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
        supportingText = { Text(platform.keyHint + " Never share your key.") },
        modifier = Modifier.fillMaxWidth(),
    )
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
        val count = settings.destinationCount
        val bitrate = settings.quality.bitrate.toLong()
        Text(
            if (count > 1) {
                "Up to ${formatBitrate(bitrate)} to each of $count destinations, about ${formatBitrate(bitrate * count)} " +
                    "of upload in total. Lowered automatically when your connection struggles."
            } else {
                "Up to ${formatBitrate(bitrate)}. Lowered automatically when your connection struggles."
            },
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
