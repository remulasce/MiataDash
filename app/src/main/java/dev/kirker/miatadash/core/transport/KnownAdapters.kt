package dev.kirker.miatadash.core.transport

/**
 * Known Bluetooth OBD adapters this build is preconfigured for.
 *
 * Match priority: MAC address first (deterministic), then a substring match against the
 * device's friendly name as a fallback (in case the address ever changes after a factory
 * reset of the adapter).
 *
 * Replace or extend this list when changing hardware.
 */
data class KnownAdapter(
    /** Bluetooth MAC. Case-insensitive comparison. */
    val address: String,
    /** Substring match against [BluetoothDevice.name] (also case-insensitive). */
    val nameContains: String,
    /** Friendly label shown in Settings. */
    val label: String,
)

object KnownAdapters {

    /** Fae's OBDLink MX+. Pair via system Bluetooth (PIN 1234) before first run. */
    val MX_PLUS = KnownAdapter(
        address = "00:04:3E:5D:EE:09",
        nameContains = "32866",
        label = "OBDLink MX+",
    )

    val ALL: List<KnownAdapter> = listOf(MX_PLUS)

    fun matches(addressOrName: String): Boolean = ALL.any {
        it.address.equals(addressOrName, ignoreCase = true) ||
            addressOrName.contains(it.nameContains, ignoreCase = true)
    }
}
