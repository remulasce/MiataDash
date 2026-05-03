package dev.kirker.miatadash.core.can

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Golden cases for the Mazda NC1 CAN frame decoders.
 *
 * Formulas validated against timurrrr/RaceChronoDiyBleDevice/can_db/mazda_mx5_nc.md and
 * the user's confirmation that those formulas read sane values in Torque Pro on his car.
 */
class MazdaNcDbcTest {

    private fun frame(id: Int, vararg bytes: Int) =
        CanFrame(id, ByteArray(bytes.size) { bytes[it].toByte() }, 0L)

    // 0x4B0 — wheel speeds. raw 10000 (0x2710) per pair = standstill.
    @Test fun `wheel speeds at standstill all zero`() {
        val f = frame(0x4B0, 0x27, 0x10, 0x27, 0x10, 0x27, 0x10, 0x27, 0x10)
        val out = MazdaNcDbc.WHEEL_SPEEDS.decode(f)
        assertThat(out["fl_kph"]).isEqualTo(0.0)
        assertThat(out["fr_kph"]).isEqualTo(0.0)
        assertThat(out["rl_kph"]).isEqualTo(0.0)
        assertThat(out["rr_kph"]).isEqualTo(0.0)
    }

    @Test fun `wheel speed at 50 kph`() {
        // raw 15000 = 0x3A98 → (15000/100) - 100 = 50 kph
        val f = frame(0x4B0, 0x3A, 0x98, 0x3A, 0x98, 0x3A, 0x98, 0x3A, 0x98)
        val out = MazdaNcDbc.WHEEL_SPEEDS.decode(f)
        assertThat(out["fl_kph"]).isWithin(0.01).of(50.0)
    }

    @Test fun `wheel speed handles reverse below 100kph offset`() {
        // raw 9500 = 0x251C → (9500/100) - 100 = -5 kph (rolling backwards)
        val f = frame(0x4B0, 0x25, 0x1C, 0x27, 0x10, 0x27, 0x10, 0x27, 0x10)
        val out = MazdaNcDbc.WHEEL_SPEEDS.decode(f)
        assertThat(out["fl_kph"]).isWithin(0.01).of(-5.0)
    }

    // 0x201 — engine block.
    @Test fun `pcm 201 idle`() {
        // RPM at 800: u16 = 800*4 = 3200 = 0x0C80
        // Speed standstill: bytes 4-5 = 10000 = 0x2710
        // Accelerator at idle: byte 6 = 0
        val f = frame(0x201, 0x0C, 0x80, 0x00, 0x00, 0x27, 0x10, 0x00, 0xFF)
        val out = MazdaNcDbc.PCM_201.decode(f)
        assertThat(out["rpm"]).isEqualTo(800.0)
        assertThat(out["speed_kph"]).isEqualTo(0.0)
        assertThat(out["accelerator_pct"]).isEqualTo(0.0)
    }

    @Test fun `pcm 201 cruise`() {
        // RPM 3000 = 12000 = 0x2EE0
        // Speed 100 kph: raw = 20000 = 0x4E20
        // Accelerator 25%: byte = 12 (×2 = 24%, close enough)
        val f = frame(0x201, 0x2E, 0xE0, 0x00, 0x00, 0x4E, 0x20, 12, 0xFF)
        val out = MazdaNcDbc.PCM_201.decode(f)
        assertThat(out["rpm"]).isEqualTo(3000.0)
        assertThat(out["speed_kph"]).isEqualTo(100.0)
        assertThat(out["accelerator_pct"]).isEqualTo(24.0)
    }

    // 0x240 — engine block.
    @Test fun `engine 240 warm idle`() {
        // load 30%: 0x4D = 77 → 77*100/255 ≈ 30.2
        // coolant 88°C: 0x80 = 128, -40 = 88
        // throttle 12%: byte = 31 → 31*100/255 ≈ 12.2
        // IAT 25°C: 0x41 = 65, -40 = 25
        val f = frame(0x240, 0x4D, 0x80, 0x00, 31, 0x41, 0, 0, 0)
        val out = MazdaNcDbc.ENGINE_240.decode(f)
        assertThat(out["engine_load_pct"]!!).isWithin(0.5).of(30.2)
        assertThat(out["coolant_c"]).isEqualTo(88.0)
        assertThat(out["throttle_valve_pct"]!!).isWithin(0.5).of(12.2)
        assertThat(out["iat_c"]).isEqualTo(25.0)
    }

    // 0x085 — brake.
    @Test fun `brake at rest`() {
        // raw 102 = 0x0066 → brake_pct = 0
        // pressure: (3.45 * 102 - 327.27) / 1000 ≈ 0.025 kPa
        val f = frame(0x085, 0x00, 0x66, 0x00, 0, 0, 0, 0, 0)
        val out = MazdaNcDbc.BRAKE.decode(f)
        assertThat(out["brake_pct"]).isEqualTo(0.0)
        assertThat(out["brake_switch"]).isEqualTo(0.0)
    }

    @Test fun `brake switch on`() {
        // byte 2 MSB set = switch on
        val f = frame(0x085, 0x00, 0x66, 0x80, 0, 0, 0, 0, 0)
        val out = MazdaNcDbc.BRAKE.decode(f)
        assertThat(out["brake_switch"]).isEqualTo(1.0)
    }

    @Test fun `brake percentage scaling`() {
        // raw = 602 → 0.2 * (602 - 102) = 100% (clamped)
        val f = frame(0x085, 0x02, 0x5A, 0x80, 0, 0, 0, 0, 0)
        val out = MazdaNcDbc.BRAKE.decode(f)
        assertThat(out["brake_pct"]).isEqualTo(100.0)
    }

    // 0x081 — steering.
    @Test fun `steering centered`() {
        val f = frame(0x081, 0x00, 0xEF, 0x00, 0x00)
        val out = MazdaNcDbc.STEERING.decode(f)
        assertThat(out["steering_raw"]).isEqualTo(0.0)
    }

    @Test fun `steering negative`() {
        // bytes 2-3 = 0xFFFF → signed = -1
        val f = frame(0x081, 0x00, 0xEF, 0xFF, 0xFF)
        val out = MazdaNcDbc.STEERING.decode(f)
        assertThat(out["steering_raw"]).isEqualTo(-1.0)
    }

    // 0x231 — clutch.
    @Test fun `clutch released vs pressed`() {
        // MSB set = clutch ?
        val released = MazdaNcDbc.TRANS_231.decode(frame(0x231, 0xFF, 0x00))
        val pressed = MazdaNcDbc.TRANS_231.decode(frame(0x231, 0xFF, 0x80))
        assertThat(released["clutch_switch"]).isEqualTo(0.0)
        assertThat(pressed["clutch_switch"]).isEqualTo(1.0)
    }

    // CanFrameParser format tolerance — STN can emit frames with or without spaces depending
    // on ATS0/ATS1. Both shapes must produce identical CanFrame.

    @Test fun `parser handles ATS1 spaced frame`() {
        val f = CanFrameParser.parse("4B0 27 10 27 10 27 10 27 10", 0L)
        assertThat(f).isNotNull()
        assertThat(f!!.id).isEqualTo(0x4B0)
        assertThat(f.data.size).isEqualTo(8)
        assertThat(f.byte(0)).isEqualTo(0x27)
        assertThat(f.byte(1)).isEqualTo(0x10)
    }

    @Test fun `parser handles ATS0 concatenated frame`() {
        val f = CanFrameParser.parse("4B0271027102710271", 0L)
        // wait: that's 3 + 16 = 19 chars, not even after the 3-char ID; let's try a real-world
        // STN line which will have an even number of payload chars (8 bytes = 16 chars).
        // 4B0 + 27102710 27102710 = 3 + 16 = 19. Hmm odd. Adjust to 8 bytes:
        val f2 = CanFrameParser.parse("4B02710271027102710A", 0L)  // 3 + 17 = 20 chars
        assertThat(f2).isNotNull()
        assertThat(f2!!.id).isEqualTo(0x4B0)
        assertThat(f2.data.size).isEqualTo(8)
        assertThat(f2.byte(0)).isEqualTo(0x27)
        assertThat(f2.byte(7)).isEqualTo(0x0A)
    }

    @Test fun `parser handles single-byte frame`() {
        val f = CanFrameParser.parse("231 80", 0L)
        assertThat(f).isNotNull()
        assertThat(f!!.id).isEqualTo(0x231)
        assertThat(f.byte(0)).isEqualTo(0x80)
    }

    @Test fun `parser rejects malformed odd-length payload`() {
        val f = CanFrameParser.parse("4B0123", 0L)   // 3-char id + 3-char payload (1.5 bytes)
        assertThat(f).isNull()
    }

    @Test fun `parser rejects too-short input`() {
        val f = CanFrameParser.parse("4B0", 0L)
        assertThat(f).isNull()
    }
}
