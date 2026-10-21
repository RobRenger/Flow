package io.github.aedev.flow.player.cast

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.cast.CastDiscovery.Companion.sortedForPicker
import org.junit.Test

class CastWireTest {
    @Test
    fun `a message survives encoding and decoding`() {
        val message =
            CastMessage(
                sourceId = "sender-flow",
                destinationId = "receiver-0",
                namespace = "urn:x-cast:com.google.cast.receiver",
                payload = """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""",
            )

        assertThat(CastWire.decode(CastWire.encode(message))).isEqualTo(message)
    }

    @Test
    fun `payloads longer than 127 bytes use multi-byte lengths`() {
        val message = CastMessage("a", "b", "c", "x".repeat(5_000) + "ë")

        assertThat(CastWire.decode(CastWire.encode(message))).isEqualTo(message)
    }

    @Test
    fun `the encoding matches the protobuf layout receivers expect`() {
        val bytes = CastWire.encode(CastMessage("s", "d", "n", "p"))

        // field 1 = 0, field 2 = "s", field 3 = "d", field 4 = "n", field 5 = 0, field 6 = "p"
        val expected = byteArrayOf(0x08, 0x00, 0x12, 0x01, 's'.code.toByte(), 0x1a, 0x01, 'd'.code.toByte(), 0x22, 0x01, 'n'.code.toByte(), 0x28, 0x00, 0x32, 0x01, 'p'.code.toByte())
        assertThat(bytes).isEqualTo(expected)
    }

    @Test
    fun `binary payloads and garbage decode to null`() {
        val binary = byteArrayOf(0x08, 0x00, 0x28, 0x01, 0x3a, 0x01, 0x00) // payload_binary only
        assertThat(CastWire.decode(binary)).isNull()
        assertThat(CastWire.decode(byteArrayOf(0x12, 0x7f))).isNull()
    }

    @Test
    fun `speaker groups are listed before single speakers`() {
        val kitchen = CastDevice("1", "Keuken", "Google Nest Mini", "10.0.0.2", 8009)
        val house = CastDevice("2", "Hele huis", CastDevice.GROUP_MODEL, "10.0.0.3", 32187)
        val living = CastDevice("3", "Woonkamer", "Google Nest Audio", "10.0.0.4", 8009)

        assertThat(listOf(kitchen, living, house).sortedForPicker()).containsExactly(house, kitchen, living).inOrder()
    }
}
