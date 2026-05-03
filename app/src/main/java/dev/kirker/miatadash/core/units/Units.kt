package dev.kirker.miatadash.core.units

/**
 * Display unit system. The OBD wire format and the [TelemetrySnapshot] always carry SI
 * (km/h, °C, kPa); conversion happens at the UI boundary so the rest of the app stays
 * in one canonical unit.
 *
 * Default is [UnitSystem.US] — Imperial. The eventual Settings → Units toggle just
 * mutates [Units.system]; observers should re-read on each render (it's a `var`, but
 * Compose recomposes any screen that reads from MaterialTheme on theme change, and we
 * trigger recomposition by also bumping [Units.tick] when the user flips the switch).
 *
 * For v1 there's no DataStore persistence yet — the choice resets to US on each launch.
 */
enum class UnitSystem { US, METRIC }

object Units {
    /** Mutable: flipped by the Settings screen. */
    @Volatile
    var system: UnitSystem = UnitSystem.US

    // ---- conversions (SI → display) ----
    fun speed(kph: Double): Double = if (system == UnitSystem.US) kph * 0.621371 else kph
    fun temp(c: Double): Double = if (system == UnitSystem.US) c * 9.0 / 5.0 + 32.0 else c
    fun pressure(kPa: Double): Double = if (system == UnitSystem.US) kPa * 0.145038 else kPa

    // ---- unit labels ----
    val speedLabel: String get() = if (system == UnitSystem.US) "mph" else "kph"
    val tempLabel: String get() = if (system == UnitSystem.US) "°F" else "°C"
    val pressureLabel: String get() = if (system == UnitSystem.US) "psi" else "kPa"
}
