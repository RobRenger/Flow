package io.github.aedev.flow.player.cast

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** What a Cast receiver reports about the loaded media. */
internal data class CastMediaStatus(
    val mediaSessionId: Int,
    val playerState: String,
    val idleReason: String?,
    val currentTimeSec: Double,
    val durationSec: Double?,
)

internal data class CastStream(
    val url: String,
    val contentType: String,
)

internal data class CastTrackInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
)

/**
 * A Cast v2 sender session: a TLS socket to the receiver, the Default Media Receiver app launched on
 * it, and the media and volume commands Flow needs. All callbacks arrive on an IO thread.
 *
 * Speaker groups work the same way as single speakers: the group's own mDNS record points at the
 * speaker that leads the group.
 */
internal class CastConnection(
    private val device: CastDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()

        fun onMediaStatus(status: CastMediaStatus)

        fun onVolume(
            level: Float,
            muted: Boolean,
        )

        fun onClosed(error: Throwable?)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestIds = AtomicInteger(1)
    private val writeLock = Any()

    @Volatile private var socket: SSLSocket? = null

    @Volatile private var output: DataOutputStream? = null

    @Volatile private var transportId: String? = null

    @Volatile private var appSessionId: String? = null

    @Volatile var mediaSessionId: Int? = null
        private set

    private val closed = AtomicBoolean(false)
    private var heartbeat: Job? = null

    fun open() {
        scope.launch {
            try {
                val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(AcceptAnyCertificate), SecureRandom()) }
                val s = tls.socketFactory.createSocket() as SSLSocket
                s.connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                s.startHandshake()
                socket = s
                output = DataOutputStream(s.outputStream)

                send(NS_CONNECTION, RECEIVER_ID, JSONObject().put("type", "CONNECT"))
                send(NS_RECEIVER, RECEIVER_ID, request("LAUNCH").put("appId", DEFAULT_MEDIA_RECEIVER))
                heartbeat =
                    scope.launch {
                        while (isActive) {
                            delay(HEARTBEAT_MS)
                            send(NS_HEARTBEAT, RECEIVER_ID, JSONObject().put("type", "PING"))
                        }
                    }
                readLoop(DataInputStream(s.inputStream))
            } catch (e: Exception) {
                close(e)
            }
        }
    }

    fun load(
        stream: CastStream,
        track: CastTrackInfo,
        startSec: Double,
        autoplay: Boolean,
    ) {
        val metadata =
            JSONObject()
                .put("metadataType", 3) // MUSIC_TRACK
                .putOpt("title", track.title)
                .putOpt("artist", track.artist)
                .putOpt("albumName", track.album)
        track.artworkUrl?.let { metadata.put("images", JSONArray().put(JSONObject().put("url", it))) }
        val media =
            JSONObject()
                .put("contentId", stream.url)
                .put("contentUrl", stream.url)
                .put("streamType", "BUFFERED")
                .put("contentType", stream.contentType)
                .put("metadata", metadata)
        mediaSessionId = null
        sendMedia(
            request("LOAD")
                .put("sessionId", appSessionId)
                .put("media", media)
                .put("autoplay", autoplay)
                .put("currentTime", startSec),
        )
    }

    fun play() = mediaCommand("PLAY")

    fun pause() = mediaCommand("PAUSE")

    fun stopMedia() = mediaCommand("STOP")

    fun seek(positionSec: Double) {
        val id = mediaSessionId ?: return
        sendMedia(request("SEEK").put("mediaSessionId", id).put("currentTime", positionSec))
    }

    fun setVolume(level: Float) {
        send(NS_RECEIVER, RECEIVER_ID, request("SET_VOLUME").put("volume", JSONObject().put("level", level.coerceIn(0f, 1f).toDouble())))
    }

    fun setMuted(muted: Boolean) {
        send(NS_RECEIVER, RECEIVER_ID, request("SET_VOLUME").put("volume", JSONObject().put("muted", muted)))
    }

    /** Stops the receiver app so the speaker goes quiet, then closes the socket. */
    fun stopAndClose() {
        scope.launch {
            appSessionId?.let { send(NS_RECEIVER, RECEIVER_ID, request("STOP").put("sessionId", it)) }
            close(null)
        }
    }

    fun close(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        heartbeat?.cancel()
        runCatching { socket?.close() }
        if (error != null) Log.w(TAG, "Cast connection to ${device.name} closed", error)
        listener.onClosed(error)
        scope.cancel()
    }

    private fun mediaCommand(type: String) {
        val id = mediaSessionId ?: return
        sendMedia(request(type).put("mediaSessionId", id))
    }

    private fun sendMedia(payload: JSONObject) {
        val destination = transportId ?: return
        send(NS_MEDIA, destination, payload)
    }

    private fun request(type: String) = JSONObject().put("type", type).put("requestId", requestIds.getAndIncrement())

    private fun send(
        namespace: String,
        destination: String,
        payload: JSONObject,
    ) {
        val out = output ?: return
        val bytes = CastWire.encode(CastMessage(SENDER_ID, destination, namespace, payload.toString()))
        try {
            synchronized(writeLock) {
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            close(e)
        }
    }

    private fun readLoop(input: DataInputStream) {
        while (!closed.get()) {
            val length = input.readInt()
            if (length !in 1..MAX_MESSAGE_BYTES) throw IllegalStateException("Bad Cast frame length $length")
            val bytes = ByteArray(length)
            input.readFully(bytes)
            val message = CastWire.decode(bytes) ?: continue
            runCatching { handle(message, JSONObject(message.payload)) }
                .onFailure { Log.w(TAG, "Ignoring Cast message on ${message.namespace}", it) }
        }
    }

    private fun handle(
        message: CastMessage,
        json: JSONObject,
    ) {
        when (json.optString("type")) {
            "PING" -> send(NS_HEARTBEAT, message.sourceId, JSONObject().put("type", "PONG"))
            "CLOSE" -> if (message.sourceId == transportId) close(IllegalStateException("Receiver closed the session"))
            "RECEIVER_STATUS" -> handleReceiverStatus(json.optJSONObject("status") ?: return)
            "MEDIA_STATUS" -> {
                val status = json.optJSONArray("status")?.optJSONObject(0) ?: return
                mediaSessionId = status.optInt("mediaSessionId").takeIf { it != 0 } ?: mediaSessionId
                val id = mediaSessionId ?: return
                listener.onMediaStatus(
                    CastMediaStatus(
                        mediaSessionId = id,
                        playerState = status.optString("playerState"),
                        idleReason = status.optString("idleReason").ifEmpty { null },
                        currentTimeSec = status.optDouble("currentTime", 0.0),
                        durationSec = status.optJSONObject("media")?.optDouble("duration")?.takeIf { !it.isNaN() },
                    ),
                )
            }
            "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" ->
                listener.onMediaStatus(CastMediaStatus(mediaSessionId ?: 0, "IDLE", "ERROR", 0.0, null))
            "LAUNCH_ERROR" -> close(IllegalStateException("Receiver refused to launch: $json"))
        }
    }

    private fun handleReceiverStatus(status: JSONObject) {
        status.optJSONObject("volume")?.let { volume ->
            listener.onVolume(volume.optDouble("level", 1.0).toFloat(), volume.optBoolean("muted", false))
        }
        val apps = status.optJSONArray("applications")
        var app: JSONObject? = null
        for (i in 0 until (apps?.length() ?: 0)) {
            val candidate = apps?.optJSONObject(i) ?: continue
            if (candidate.optString("appId") == DEFAULT_MEDIA_RECEIVER) app = candidate
        }
        if (app == null) {
            // Another sender took the speaker over, or the app stopped.
            if (transportId != null) close(null)
            return
        }
        val newSession = app.optString("sessionId")
        if (transportId != null && newSession == appSessionId) return
        if (transportId != null) {
            close(null)
            return
        }
        appSessionId = newSession
        transportId = app.optString("transportId")
        send(NS_CONNECTION, transportId!!, JSONObject().put("type", "CONNECT"))
        sendMedia(request("GET_STATUS"))
        listener.onReady()
    }

    /** Cast devices present certificates from Google's device CA, which no Android trust store holds. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AcceptAnyCertificate : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val TAG = "CastConnection"
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        private const val SENDER_ID = "sender-flow"
        private const val RECEIVER_ID = "receiver-0"
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val HEARTBEAT_MS = 5_000L
        private const val MAX_MESSAGE_BYTES = 64 * 1024
    }
}
