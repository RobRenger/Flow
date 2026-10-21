package io.github.aedev.flow.service

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.player.cast.CastController
import io.github.aedev.flow.player.cast.CastDevice
import io.github.aedev.flow.player.cast.CastPlayer
import io.github.aedev.flow.player.cast.CastStream
import io.github.aedev.flow.player.dlna.StreamProxyServer
import io.github.aedev.flow.utils.MusicPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Moves the music session between the phone's player and a Cast speaker or speaker group, as
 * [CastController.target] asks. The queue, position and repeat mode travel along both ways.
 */
internal class MusicCastHandover(
    private val context: Context,
    private val session: MediaSession,
    private val localPlayer: ExoPlayer,
    private val localSessionPlayer: () -> Player,
) {
    private var castPlayer: CastPlayer? = null

    val isCasting: Boolean get() = castPlayer != null

    fun attach(scope: CoroutineScope) {
        scope.launch {
            CastController.target.collect { device ->
                if (device == null) toPhone() else toCast(device)
            }
        }
    }

    fun release() {
        castPlayer?.release()
        castPlayer = null
    }

    private fun toCast(device: CastDevice) {
        if (castPlayer?.device == device) return
        toPhone()
        StreamProxyServer.getInstance().start(context)

        val items = (0 until localPlayer.mediaItemCount).map(localPlayer::getMediaItemAt)
        val wasPlaying = localPlayer.playWhenReady
        val cast =
            CastPlayer(
                context = context,
                device = device,
                resolveStream = ::resolveStream,
                onConnectionLost = { CastController.onLost(device) },
                onReady = { CastController.onConnected(device) },
            )
        cast.repeatMode = localPlayer.repeatMode
        cast.shuffleModeEnabled = localPlayer.shuffleModeEnabled
        if (items.isNotEmpty()) {
            cast.setMediaItems(items, localPlayer.currentMediaItemIndex, localPlayer.currentPosition)
        }
        cast.playWhenReady = wasPlaying
        cast.prepare()

        castPlayer = cast
        localPlayer.pause()
        session.player = cast
        Log.i(TAG, "Casting ${items.size} tracks to ${device.name}")
    }

    private fun toPhone() {
        val cast = castPlayer ?: return
        castPlayer = null
        val (items, index, position) = cast.snapshot()
        session.player = localSessionPlayer()

        val localIds = (0 until localPlayer.mediaItemCount).map { localPlayer.getMediaItemAt(it).mediaId }
        if (items.map(MediaItem::mediaId) == localIds) {
            if (items.isNotEmpty()) localPlayer.seekTo(index, position)
        } else if (items.isNotEmpty()) {
            localPlayer.setMediaItems(items, index, position)
        }
        localPlayer.repeatMode = cast.repeatMode
        localPlayer.prepare()
        // Stopping the cast leaves the music paused rather than suddenly playing from the phone.
        localPlayer.pause()
        cast.release()
        Log.i(TAG, "Casting to ${cast.device.name} ended")
    }

    /** The track's audio stream, relayed through the phone's proxy so the speaker can reach it. */
    private suspend fun resolveStream(item: MediaItem): CastStream? {
        val videoId = item.mediaId
        if (LocalMediaIds.audioUri(videoId) != null) return null // Files on the phone are not served yet.
        val data = MusicPlayerUtils.playerResponseForPlayback(videoId).getOrNull() ?: return null
        val contentType = data.format.mimeType.substringBefore(';').trim().ifEmpty { "audio/mp4" }
        val proxy = StreamProxyServer.getInstance()
        repeat(PROXY_START_POLLS) {
            if (proxy.isStarted()) return CastStream(proxy.registerStream(data.streamUrl, contentType), contentType)
            delay(PROXY_START_POLL_MS)
        }
        // Without the proxy the speaker fetches the stream itself, which works for most tracks.
        return CastStream(data.streamUrl, contentType)
    }

    companion object {
        private const val TAG = "MusicCastHandover"
        private const val PROXY_START_POLLS = 20
        private const val PROXY_START_POLL_MS = 100L
    }
}
