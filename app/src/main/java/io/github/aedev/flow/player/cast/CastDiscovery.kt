package io.github.aedev.flow.player.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Finds Cast receivers with mDNS (`_googlecast._tcp`). Speakers and speaker groups both show up,
 * groups with the model "Google Cast Group" and usually a port other than 8009.
 *
 * Android before 14 resolves one service at a time, so found services wait in a queue.
 */
internal class CastDiscovery(
    context: Context,
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** mDNS service name → device id, to drop a device when its service goes away. */
    private val idsByService = mutableMapOf<String, String>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val discovery =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    main.post { _searching.value = true }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    main.post { _searching.value = false }
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "mDNS discovery failed to start: $errorCode")
                    main.post {
                        listener = null
                        _searching.value = false
                    }
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post {
                        pending.addLast(info)
                        resolveNext()
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post {
                        val id = idsByService.remove(info.serviceName) ?: return@post
                        _devices.update { list -> list.filterNot { it.id == id } }
                    }
                }
            }
        listener = discovery
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure {
                Log.w(TAG, "mDNS discovery unavailable", it)
                listener = null
            }
    }

    fun stop() {
        val discovery = listener ?: return
        listener = null
        runCatching { nsd.stopServiceDiscovery(discovery) }
        pending.clear()
        _searching.value = false
    }

    @Suppress("DEPRECATION") // resolveService is the only resolver below Android 14.
    private fun resolveNext() {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    main.post {
                        resolving = false
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            pending.addLast(serviceInfo)
                            main.postDelayed(::resolveNext, RETRY_MS)
                        } else {
                            resolveNext()
                        }
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        serviceInfo.toCastDevice()?.let { device ->
                            idsByService[serviceInfo.serviceName] = device.id
                            _devices.update { list -> (list.filterNot { it.id == device.id } + device).sortedForPicker() }
                        }
                        resolveNext()
                    }
                }
            },
        )
    }

    @Suppress("DEPRECATION") // host is the only address accessor below Android 14.
    private fun NsdServiceInfo.toCastDevice(): CastDevice? {
        val address = host?.hostAddress ?: return null
        val attributes = attributes.mapValues { (_, value) -> value?.decodeToString().orEmpty() }
        return CastDevice(
            id = attributes["id"].orEmpty().ifEmpty { serviceName },
            name = attributes["fn"].orEmpty().ifEmpty { serviceName },
            model = attributes["md"].orEmpty(),
            host = address,
            port = port,
        )
    }

    companion object {
        private const val TAG = "CastDiscovery"
        private const val SERVICE_TYPE = "_googlecast._tcp"
        private const val RETRY_MS = 300L

        /** Groups first, since playing to a group is what Flow's casting is mostly for. */
        fun List<CastDevice>.sortedForPicker(): List<CastDevice> = sortedWith(compareBy<CastDevice> { !it.isGroup }.thenBy { it.name.lowercase() })
    }
}
