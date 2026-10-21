package io.github.aedev.flow.player.cast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where music casting stands, as the player screen shows it. */
sealed interface CastState {
    data object Off : CastState

    data class Connecting(
        val device: CastDevice,
    ) : CastState

    data class Connected(
        val device: CastDevice,
    ) : CastState

    data class Failed(
        val device: CastDevice,
    ) : CastState
}

/**
 * The meeting point between the player screen and the music service. The screen picks a speaker
 * here, and the service watches [target] and moves playback to it or back to the phone.
 */
object CastController {
    private var discovery: CastDiscovery? = null

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())

    private val _target = MutableStateFlow<CastDevice?>(null)

    /** The speaker or group the music service should play on, or null for the phone. */
    val target: StateFlow<CastDevice?> = _target.asStateFlow()

    private val _state = MutableStateFlow<CastState>(CastState.Off)
    val state: StateFlow<CastState> = _state.asStateFlow()

    fun devices(context: Context): StateFlow<List<CastDevice>> = discovery(context).devices

    fun searching(context: Context): StateFlow<Boolean> = discovery(context).searching

    fun startDiscovery(context: Context) = discovery(context).start()

    fun stopDiscovery() {
        discovery?.stop()
    }

    fun castTo(device: CastDevice) {
        _state.value = CastState.Connecting(device)
        _target.value = device
    }

    fun stopCasting() {
        _target.value = null
        _state.value = CastState.Off
    }

    internal fun onConnected(device: CastDevice) {
        if (_target.value == device) _state.value = CastState.Connected(device)
    }

    /** The connection dropped or never came up: playback returns to the phone. */
    internal fun onLost(device: CastDevice) {
        if (_target.value != device) return
        _target.value = null
        _state.value = CastState.Failed(device)
    }

    private fun discovery(context: Context): CastDiscovery =
        discovery ?: CastDiscovery(context).also { discovery = it }
}
