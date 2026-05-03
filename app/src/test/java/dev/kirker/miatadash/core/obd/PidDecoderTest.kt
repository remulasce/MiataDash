package dev.kirker.miatadash.core.obd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Golden-case tests for [PidDecoder]. Strings are real responses captured from the user's
 * NC1 Miata via the Trace Capture flow (see `trace_1777779686019.miatatrace`).
 *
 * Init sequence is `ATH1 ATS0`, so responses come back as one concatenated hex string with
 * the 11-bit CAN header out front, e.g. `7E804410C0ECF` = header `7E8` + length `04` +
 * response `41 0C 0E CF`.
 */
class PidDecoderTest {

    private fun ok(line: String, pid: Int): PidResponse.Ok {
        val r = PidDecoder.decode(line, pid)
        assertThat(r).isInstanceOf(PidResponse.Ok::class.java)
        return r as PidResponse.Ok
    }

    // --- Real responses from the trace, ATH1+ATS0 single-frame form ---

    @Test fun `RPM from concatenated CAN response`() {
        val r = ok("7E804410C0ECF", Pid.RPM.pid)
        assertThat(r.value).isWithin(0.5).of(947.75)
    }

    @Test fun `coolant from concatenated response`() {
        val r = ok("7E803410556", Pid.COOLANT.pid)
        assertThat(r.value).isEqualTo(46.0)   // 0x56 - 40 = 46°C
    }

    @Test fun `throttle position`() {
        val r = ok("7E803411120", Pid.THROTTLE.pid)
        assertThat(r.value).isWithin(0.1).of(12.549) // 0x20 * 100 / 255
    }

    @Test fun `MAF g per s`() {
        val r = ok("7E80441100173", Pid.MAF.pid)
        assertThat(r.value).isWithin(0.001).of(3.71)
    }

    @Test fun `vehicle speed parked`() {
        val r = ok("7E803410D00", Pid.SPEED.pid)
        assertThat(r.value).isEqualTo(0.0)
    }

    @Test fun `intake air temp`() {
        val r = ok("7E803410F3C", Pid.IAT.pid)
        assertThat(r.value).isEqualTo(20.0)  // 0x3C - 40
    }

    @Test fun `timing advance`() {
        val r = ok("7E803410EA8", Pid.TIMING_ADV.pid)
        assertThat(r.value).isEqualTo(20.0)  // 0xA8/2 - 64 = 84 - 64
    }

    @Test fun `battery voltage`() {
        val r = ok("7E8044142360F", Pid.BATTERY.pid)
        assertThat(r.value).isWithin(0.01).of(13.839)
    }

    @Test fun `engine load`() {
        val r = ok("7E80341044B", Pid.ENGINE_LOAD.pid)
        assertThat(r.value).isWithin(0.1).of(29.41)
    }

    // --- Format tolerance ---

    @Test fun `spaced ATH1 ATS1 form decodes the same`() {
        val r = ok("7E8 04 41 0C 0E CF", Pid.RPM.pid)
        assertThat(r.value).isWithin(0.5).of(947.75)
    }

    @Test fun `headers off ATH0 ATS1`() {
        val r = ok("41 0C 0E CF", Pid.RPM.pid)
        assertThat(r.value).isWithin(0.5).of(947.75)
    }

    @Test fun `headers off ATH0 ATS0 concatenated`() {
        val r = ok("410C0ECF", Pid.RPM.pid)
        assertThat(r.value).isWithin(0.5).of(947.75)
    }

    // --- Negative responses ---

    @Test fun `NO DATA maps to NoData`() {
        val r = PidDecoder.decode("NO DATA", Pid.RPM.pid)
        assertThat(r).isInstanceOf(PidResponse.NoData::class.java)
    }

    @Test fun `question mark maps to Garbled`() {
        val r = PidDecoder.decode("?", Pid.RPM.pid)
        assertThat(r).isInstanceOf(PidResponse.Garbled::class.java)
    }

    @Test fun `STOPPED maps to Garbled`() {
        val r = PidDecoder.decode("STOPPED", Pid.RPM.pid)
        assertThat(r).isInstanceOf(PidResponse.Garbled::class.java)
    }

    @Test fun `empty data after sig is Garbled`() {
        val r = PidDecoder.decode("7E80241", Pid.RPM.pid)  // header + length + sig but no data bytes
        assertThat(r).isInstanceOf(PidResponse.Garbled::class.java)
    }

    @Test fun `wrong PID in response is Garbled`() {
        // Response is for PID 0x0D (speed), but we asked for 0x0C (RPM)
        val r = PidDecoder.decode("7E803410D00", Pid.RPM.pid)
        assertThat(r).isInstanceOf(PidResponse.Garbled::class.java)
    }
}
