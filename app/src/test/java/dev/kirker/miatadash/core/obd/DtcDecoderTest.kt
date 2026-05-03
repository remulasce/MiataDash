package dev.kirker.miatadash.core.obd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DtcDecoderTest {

    @Test fun `single P0133 from CAN single-frame`() {
        // 7E8 = header, 04 = length, 43 = mode 03 response, 01 = count, 01 33 = DTC
        val codes = DtcDecoder.decode(listOf("7E80443010133"))
        assertThat(codes).hasSize(1)
        assertThat(codes[0].code).isEqualTo("P0133")
    }

    @Test fun `two DTCs P0420 P0171`() {
        // length = 06, count = 02, then 04 20, 01 71
        val codes = DtcDecoder.decode(listOf("7E80643020420 0171"))
        assertThat(codes.map { it.code }).containsExactly("P0420", "P0171").inOrder()
    }

    @Test fun `no codes returns empty`() {
        // Length=02, count=00, padding zeros
        val codes = DtcDecoder.decode(listOf("7E80243000000000000"))
        assertThat(codes).isEmpty()
    }

    @Test fun `empty input returns empty`() {
        assertThat(DtcDecoder.decode(emptyList())).isEmpty()
    }

    @Test fun `pending mode 07 also decodes`() {
        val codes = DtcDecoder.decode(listOf("7E80447010142"))   // P0142
        assertThat(codes.map { it.code }).containsExactly("P0142")
    }

    @Test fun `chassis B and U ranges`() {
        // 0x4133 → letter idx = (0x4133 >> 14) & 3 = 1 → C; rest = 0x0133 → C0133
        val codes = DtcDecoder.decode(listOf("7E80443014133"))
        assertThat(codes[0].code).isEqualTo("C0133")

        // 0xC100 → letter idx = 3 → U; rest = 0x0100 → U0100
        val codes2 = DtcDecoder.decode(listOf("7E80443 01 C1 00"))
        assertThat(codes2[0].code).isEqualTo("U0100")
    }

    @Test fun `description lookup falls back gracefully`() {
        assertThat(DtcDecoder.describe("P0420")).contains("Catalyst")
        assertThat(DtcDecoder.describe("P9999")).contains("Unknown")
    }
}
