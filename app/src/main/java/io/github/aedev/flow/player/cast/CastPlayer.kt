package io.github.aedev.flow.player.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A Media3 player whose output is a Cast receiver. The queue stays on the phone: the speaker gets
 * one track at a time, and when it reports a track finished the next one is loaded. That keeps
 * Flow's own queue, radio and repeat logic in charge, and lets the media session, notification and
 * hardware volume keys work as they do for local playback.
 *
 * The phone serves the audio through its stream proxy, so it holds a wake lock and a Wi-Fi lock
 * while a track plays or loads on the speaker. Paused, stopped or at the end of the queue it lets
 * them go, as local playback does.
 */
@OptIn(UnstableApi::class)
internal class CastPlayer(
    context: Context,
    val device: CastDevice,
    private val resolveStream: suspend (MediaItem) -> CastStream?,
    private val onConnectionLost: (Throwable?) -> Unit,
    private val onReady: () -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val items = mutableListOf<MediaItem>()
    private val uids = mutableListOf<Long>()
    private var nextUid = 0L
    private var index = 0

    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var shuffle = false

    private var positionMs = 0L
    private var positionAt = SystemClock.elapsedRealtime()
    private var durationMs = C.TIME_UNSET

    private var volume = 1f
    private var muted = false

    /** Bumped on every load, so a stream that resolves late cannot replace a newer track. */
    private var loadGeneration = 0
    private var loadedGeneration = -1
    private var consecutiveFailures = 0

    /** A play or pause asked for before the receiver confirmed the load, sent once it does. */
    private var syncPlayOnStatus = false

    /** Receivers can report a track finished more than once. */
    private var lastFinishedSessionId: Int? = null
    private var ready = false
    private var released = false
    private var locksHeld = false

    private val wakeLock =
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flow:Cast")
            .apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is the mode that survives screen-off below Android 10.
    private val wifiLock =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Flow:Cast")
            .apply { setReferenceCounted(false) }

    private val connection =
        CastConnection(
            device,
            object : CastConnection.Listener {
                override fun onReady() {
                    main.post { handleReady() }
                }

                override fun onMediaStatus(status: CastMediaStatus) {
                    main.post { handleMediaStatus(status) }
                }

                override fun onVolume(
                    level: Float,
                    muted: Boolean,
                ) {
                    main.post {
                        volume = level
                        this@CastPlayer.muted = muted
                        invalidateState()
                    }
                }

                override fun onClosed(error: Throwable?) {
                    main.post { if (!released) onConnectionLost(error) }
                }
            },
        )

    init {
        connection.open()
    }

    val isConnected: Boolean get() = ready && !released

    /** The queue as it stands, for handing playback back to the phone. */
    fun snapshot(): Triple<List<MediaItem>, Int, Long> = Triple(items.toList(), index, currentPositionMs())

    override fun getState(): State {
        // Every state change passes through here, so the locks follow playback without a call at
        // each place that changes it.
        updateLocks()
        val playlist =
            items.mapIndexed { i, item ->
                MediaItemData
                    .Builder(uids[i])
                    .setMediaItem(item)
                    .setDurationUs(if (i == index && durationMs != C.TIME_UNSET) durationMs * 1000 else C.TIME_UNSET)
                    .setIsSeekable(true)
                    .build()
            }
        return State
            .Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else playbackState)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else index)
            .setContentPositionMs { currentPositionMs() }
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffle)
            .setDeviceInfo(DEVICE_INFO)
            .setDeviceVolume((volume * MAX_VOLUME).roundToInt())
            .setIsDeviceMuted(muted)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        positionMs = currentPositionMs()
        positionAt = SystemClock.elapsedRealtime()
        when {
            loadedGeneration == loadGeneration && connection.mediaSessionId != null ->
                if (playWhenReady) connection.play() else connection.pause()
            loadedGeneration == loadGeneration -> syncPlayOnStatus = true
            playWhenReady && playbackState == Player.STATE_ENDED -> {
                index = 0
                load(0)
            }
        }
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty() && playbackState == Player.STATE_IDLE) load(positionMs)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        connection.stopMedia()
        loadGeneration++
        playbackState = Player.STATE_IDLE
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (!released) {
            released = true
            loadGeneration++
            connection.stopAndClose()
            scope.cancel()
            releaseLocks()
        }
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        return done()
    }

    /** Flow shuffles by reordering the queue itself, so the flag only needs to be remembered. */
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffle = shuffleModeEnabled
        return done()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items.clear()
        uids.clear()
        mediaItems.forEach { add(items.size, it) }
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val start = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        if (playbackState != Player.STATE_IDLE && items.isNotEmpty()) {
            load(start)
        } else {
            setPosition(start)
            durationMs = C.TIME_UNSET
        }
        return done()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        val wasEmpty = items.isEmpty()
        mediaItems.forEachIndexed { offset, item -> add(index + offset, item) }
        if (wasEmpty) {
            this.index = 0
        } else if (index <= this.index) {
            this.index += mediaItems.size
        }
        if (wasEmpty && playbackState != Player.STATE_IDLE) load(0)
        return done()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> {
        val currentUid = uids.getOrNull(index)
        val movedItems = items.subList(fromIndex, toIndex).toList()
        val movedUids = uids.subList(fromIndex, toIndex).toList()
        repeat(toIndex - fromIndex) {
            items.removeAt(fromIndex)
            uids.removeAt(fromIndex)
        }
        items.addAll(newIndex, movedItems)
        uids.addAll(newIndex, movedUids)
        if (currentUid != null) index = uids.indexOf(currentUid).coerceAtLeast(0)
        return done()
    }

    override fun handleRemoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ): ListenableFuture<*> {
        val removesCurrent = index in fromIndex until toIndex
        repeat(toIndex - fromIndex) {
            items.removeAt(fromIndex)
            uids.removeAt(fromIndex)
        }
        when {
            items.isEmpty() -> {
                index = 0
                connection.stopMedia()
                loadGeneration++
                playbackState = Player.STATE_ENDED
            }
            removesCurrent -> {
                index = fromIndex.coerceAtMost(items.lastIndex)
                if (playbackState != Player.STATE_IDLE) load(0)
            }
            index >= toIndex -> index -= toIndex - fromIndex
        }
        return done()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (items.isEmpty()) return done()
        val target = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex.coerceIn(0, items.lastIndex)
        val position = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        if (target == index && loadedGeneration == loadGeneration && connection.mediaSessionId != null) {
            setPosition(position)
            connection.seek(position / 1000.0)
        } else {
            index = target
            load(position)
        }
        return done()
    }

    override fun handleSetDeviceVolume(
        deviceVolume: Int,
        flags: Int,
    ): ListenableFuture<*> {
        volume = (deviceVolume.toFloat() / MAX_VOLUME).coerceIn(0f, 1f)
        connection.setVolume(volume)
        return done()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        handleSetDeviceVolume(((volume * MAX_VOLUME).roundToInt() + 1).coerceAtMost(MAX_VOLUME), flags)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        handleSetDeviceVolume(((volume * MAX_VOLUME).roundToInt() - 1).coerceAtLeast(0), flags)

    override fun handleSetDeviceMuted(
        muted: Boolean,
        flags: Int,
    ): ListenableFuture<*> {
        this.muted = muted
        connection.setMuted(muted)
        return done()
    }

    private fun add(
        at: Int,
        item: MediaItem,
    ) {
        items.add(at, item)
        uids.add(at, nextUid++)
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun currentPositionMs(): Long {
        if (playbackState != Player.STATE_READY || !playWhenReady) return positionMs
        val elapsed = SystemClock.elapsedRealtime() - positionAt
        val position = positionMs + elapsed
        return if (durationMs != C.TIME_UNSET) position.coerceAtMost(durationMs) else position
    }

    private fun setPosition(ms: Long) {
        positionMs = ms
        positionAt = SystemClock.elapsedRealtime()
    }

    /** Resolves the current item's stream and sends it to the receiver once connected. */
    private fun load(startMs: Long) {
        val item = items.getOrNull(index) ?: return
        val generation = ++loadGeneration
        playbackState = Player.STATE_BUFFERING
        durationMs = C.TIME_UNSET
        setPosition(startMs)
        if (!ready) return // handleReady loads the current item once the receiver is up.
        scope.launch {
            val stream = withContext(Dispatchers.IO) { runCatching { resolveStream(item) }.getOrNull() }
            if (generation != loadGeneration || released) return@launch
            if (stream == null) {
                Log.w(TAG, "No castable stream for ${item.mediaId}")
                onTrackFailed()
                return@launch
            }
            loadedGeneration = generation
            syncPlayOnStatus = false
            val metadata = item.mediaMetadata
            connection.load(
                stream = stream,
                track =
                    CastTrackInfo(
                        title = metadata.title?.toString(),
                        artist = metadata.artist?.toString(),
                        album = metadata.albumTitle?.toString(),
                        artworkUrl = metadata.artworkUri?.takeIf { it.scheme?.startsWith("http") == true }?.toString(),
                    ),
                startSec = startMs / 1000.0,
                autoplay = playWhenReady,
            )
        }
    }

    private fun handleReady() {
        if (released) return
        ready = true
        onReady()
        if (items.isNotEmpty() && playbackState != Player.STATE_IDLE) load(positionMs)
        invalidateState()
    }

    private fun handleMediaStatus(status: CastMediaStatus) {
        if (released || loadedGeneration != loadGeneration) return
        when (status.playerState) {
            "PLAYING", "PAUSED" -> {
                consecutiveFailures = 0
                playbackState = Player.STATE_READY
                val receiverPlaying = status.playerState == "PLAYING"
                if (syncPlayOnStatus) {
                    syncPlayOnStatus = false
                    if (playWhenReady != receiverPlaying) {
                        if (playWhenReady) connection.play() else connection.pause()
                    }
                } else {
                    playWhenReady = receiverPlaying
                }
                status.durationSec?.let { durationMs = (it * 1000).toLong() }
                setPosition((status.currentTimeSec * 1000).toLong())
            }
            "BUFFERING", "LOADING" -> {
                playbackState = Player.STATE_BUFFERING
                setPosition((status.currentTimeSec * 1000).toLong())
            }
            "IDLE" ->
                when (status.idleReason) {
                    "FINISHED" ->
                        if (status.mediaSessionId != lastFinishedSessionId) {
                            lastFinishedSessionId = status.mediaSessionId
                            advance()
                        }
                    "ERROR" -> onTrackFailed()
                    // CANCELLED and INTERRUPTED follow our own STOP or LOAD.
                }
        }
        invalidateState()
    }

    private fun advance() {
        consecutiveFailures = 0
        val next =
            when {
                repeatMode == Player.REPEAT_MODE_ONE -> index
                index < items.lastIndex -> index + 1
                repeatMode == Player.REPEAT_MODE_ALL && items.isNotEmpty() -> 0
                else -> null
            }
        if (next == null) {
            loadGeneration++
            playbackState = Player.STATE_ENDED
            playWhenReady = false
            return
        }
        index = next
        load(0)
    }

    private fun onTrackFailed() {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.w(TAG, "Stopping after $consecutiveFailures tracks failed in a row on ${device.name}")
            loadGeneration++
            playbackState = Player.STATE_ENDED
            playWhenReady = false
            consecutiveFailures = 0
        } else {
            advance()
        }
        invalidateState()
    }

    private fun updateLocks() {
        val busy =
            !released && playWhenReady &&
                (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
        if (busy) acquireLocks() else releaseLocks()
    }

    @SuppressLint("WakelockTimeout") // Released as soon as playback pauses, stops or ends.
    private fun acquireLocks() {
        if (locksHeld) return
        locksHeld = true
        runCatching { wakeLock.acquire() }
        runCatching { wifiLock.acquire() }
    }

    private fun releaseLocks() {
        if (!locksHeld) return
        locksHeld = false
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        runCatching { if (wifiLock.isHeld) wifiLock.release() }
    }

    companion object {
        private const val TAG = "CastPlayer"
        private const val MAX_VOLUME = 20
        private const val MAX_CONSECUTIVE_FAILURES = 3

        private val DEVICE_INFO =
            DeviceInfo
                .Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(MAX_VOLUME)
                .build()

        private val COMMANDS =
            Player.Commands
                .Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SET_REPEAT_MODE,
                    Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_GET_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                ).build()
    }
}
