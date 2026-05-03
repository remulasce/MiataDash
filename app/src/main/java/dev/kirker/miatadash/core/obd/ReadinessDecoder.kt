package dev.kirker.miatadash.core.obd

/**
 * Decodes Mode 01 PID 01 (and PID 41) — readiness monitors for emissions diagnostics.
 *
 * PID 01 returns 4 bytes (A B C D):
 *   A = MIL on + DTC count (bit7 = MIL, bits 0-6 = count)
 *   B = supported continuous + readiness for continuous monitors
 *   C = which non-continuous monitors are supported
 *   D = which non-continuous monitors are *complete* (this drive cycle for PID 41,
 *       since-codes-cleared for PID 01)
 *
 * The non-continuous monitors are the ones smog testers care about:
 *   bit 0: Catalyst
 *   bit 1: Heated catalyst
 *   bit 2: Evaporative system
 *   bit 3: Secondary air system
 *   bit 4: A/C refrigerant
 *   bit 5: Oxygen sensor
 *   bit 6: Oxygen sensor heater
 *   bit 7: EGR system
 *
 * Convention: a monitor is "ready" if supported AND complete (bit set in C AND in D).
 * Smog stations typically allow at most one or two not-ready non-continuous monitors.
 */
object ReadinessDecoder {

    enum class Monitor(val label: String, val mask: Int) {
        Catalyst("Catalyst", 0x01),
        HeatedCat("Heated catalyst", 0x02),
        Evap("Evaporative system", 0x04),
        SecondaryAir("Secondary air", 0x08),
        AcRefrig("A/C refrigerant", 0x10),
        O2Sensor("O2 sensor", 0x20),
        O2Heater("O2 sensor heater", 0x40),
        Egr("EGR system", 0x80),
    }

    enum class Status { NotSupported, NotReady, Ready }

    data class Readiness(
        val milOn: Boolean,
        val dtcCount: Int,
        val monitors: Map<Monitor, Status>,
    )

    fun decode(data: ByteArray): Readiness? {
        if (data.size < 4) return null
        val a = data[0].toInt() and 0xFF
        val c = data[2].toInt() and 0xFF
        val d = data[3].toInt() and 0xFF
        val milOn = (a and 0x80) != 0
        val dtcCount = a and 0x7F
        val statuses = Monitor.values().associateWith { m ->
            val supported = (c and m.mask) != 0
            val incomplete = (d and m.mask) != 0
            when {
                !supported -> Status.NotSupported
                !incomplete -> Status.Ready
                else -> Status.NotReady
            }
        }
        return Readiness(milOn, dtcCount, statuses)
    }
}
