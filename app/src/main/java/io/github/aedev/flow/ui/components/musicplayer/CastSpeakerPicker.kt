package io.github.aedev.flow.ui.components.musicplayer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.cast.CastController
import io.github.aedev.flow.player.cast.CastDevice
import io.github.aedev.flow.player.cast.CastState
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog

/** The music player's cast button: picks a Google Cast speaker or speaker group to play on. */
@Composable
fun CastSpeakerButton(contentColor: Color) {
    val state by CastController.state.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    val active = state is CastState.Connected || state is CastState.Connecting
    IconButton(onClick = { showPicker = true }) {
        Icon(
            imageVector = if (active) Icons.Rounded.CastConnected else Icons.Outlined.Cast,
            contentDescription = stringResource(R.string.cast_music_to_speaker),
            tint = if (active) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.9f),
        )
    }
    if (showPicker) {
        CastSpeakerPickerDialog(onDismiss = { showPicker = false })
    }
}

@Composable
private fun CastSpeakerPickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices by remember { CastController.devices(context) }.collectAsState()
    val searching by remember { CastController.searching(context) }.collectAsState()
    val state by CastController.state.collectAsState()

    DisposableEffect(Unit) {
        CastController.startDiscovery(context)
        onDispose { CastController.stopDiscovery() }
    }

    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cast_music_to_speaker))
                if (searching) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        },
        text = {
            Column {
                when (val s = state) {
                    is CastState.Connecting -> StatusLine(stringResource(R.string.cast_connecting_to, s.device.name))
                    is CastState.Connected -> StatusLine(stringResource(R.string.cast_playing_on, s.device.name))
                    is CastState.Failed -> StatusLine(stringResource(R.string.cast_failed, s.device.name))
                    CastState.Off -> Unit
                }
                if (devices.isEmpty()) {
                    Text(
                        text = stringResource(if (searching) R.string.cast_searching_speakers else R.string.cast_no_speakers),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn {
                        items(devices, key = { it.id }) { device ->
                            SpeakerRow(
                                device = device,
                                selected = (state as? CastState.Connected)?.device == device || (state as? CastState.Connecting)?.device == device,
                                onClick = {
                                    CastController.castTo(device)
                                    onDismiss()
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state is CastState.Connected || state is CastState.Connecting) {
                TextButton(
                    onClick = {
                        CastController.stopCasting()
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.dlna_stop_casting)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SpeakerRow(
    device: CastDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (device.isGroup) Icons.Outlined.SpeakerGroup else Icons.Outlined.Speaker,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (device.isGroup) {
                Text(
                    text = stringResource(R.string.cast_speaker_group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
