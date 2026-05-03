package dev.kirker.miatadash.core.obd

/**
 * Adapter command strings. Centralized so initialization order is one place to read.
 */
object ElmCommands {
    const val RESET = "ATZ"
    const val ECHO_OFF = "ATE0"
    const val LINEFEEDS_OFF = "ATL0"
    const val SPACES_OFF = "ATS0"
    const val HEADERS_ON = "ATH1"
    const val PROTOCOL_CAN_11_500 = "ATSP6"
    const val ADAPTIVE_TIMING = "ATAT1"
    const val WARM_UP = "0100"
    const val DESCRIBE_PROTOCOL = "ATDP"
    const val READ_VOLTAGE = "ATRV"

    /** STN-specific: monitor with currently-installed acceptance filters. */
    const val STN_MONITOR = "STM"

    /**
     * STN acceptance filter (pass): `STFAP <id>,<mask>`. Passes a frame when (id & mask) matches.
     * Use `7FF` mask for an exact 11-bit ID match.
     */
    fun stnFilterPass(id: Int, mask: Int = 0x7FF): String =
        "STFAP %X,%X".format(id, mask)

    /** STN acceptance filter (block). */
    fun stnFilterBlock(id: Int, mask: Int = 0x7FF): String =
        "STFAB %X,%X".format(id, mask)

    /** STN clear acceptance filters. */
    const val STN_CLEAR_FILTERS = "STFAC"

    val InitSequence: List<String> = listOf(
        RESET, ECHO_OFF, LINEFEEDS_OFF, SPACES_OFF, HEADERS_ON,
        PROTOCOL_CAN_11_500, ADAPTIVE_TIMING, WARM_UP,
    )
}
