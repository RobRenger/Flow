package io.github.aedev.flow.player.cast

/** A Google Cast receiver on the local network. A speaker group is a receiver of its own. */
data class CastDevice(
    val id: String,
    val name: String,
    val model: String,
    val host: String,
    val port: Int,
) {
    val isGroup: Boolean get() = model.equals(GROUP_MODEL, ignoreCase = true)

    companion object {
        const val GROUP_MODEL = "Google Cast Group"
    }
}
