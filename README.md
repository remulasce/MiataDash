# MiataDash

Live telemetry dashboard for a 2006 NC Miata, talking to an OBDLink MX+ over Bluetooth. Native Android, Kotlin, Jetpack Compose, single-module Gradle.

See `../Miata_Telemetry_App_Design.md` for the full design rationale. This README focuses on getting the project running.

## Targets

- Pixel 10 Pro (Android 16). `compileSdk` = 35, `minSdk` = 31. Bump the SDKs in `gradle/libs.versions.toml` once Android Studio prompts; the rest of the project tracks toolchain versions there.
- Kotlin 2.0 with the K2 compose compiler.
- Hilt for DI, Material 3 for UI.

## Importing into Android Studio

1. Open Android Studio Ladybug (or newer).
2. **File → Open** → select this `MiataDash/` folder (the one with `settings.gradle.kts`).
3. Let Gradle sync. First sync downloads ~250 MB of dependencies; be patient.
4. If Studio prompts to upgrade AGP / Gradle / Kotlin, accept the upgrades — version pins in `libs.versions.toml` are conservative.
5. Run on the Pixel 10 Pro emulator. The debug build defaults to `MockTransport` so you'll see synthesized data on the dashboard immediately.

## App layout

```
dev.kirker.miatadash
├── app/                      Application, MainActivity, navigation
├── ui/theme/                 Material 3 theme (sun + night variants)
├── ui/components/            Gauge, BarMeter, ConnectionChip, ...
├── feature/connect/          Pairing + adapter selection
├── feature/dashboard/        Live gauges
├── feature/smog/             Readiness, cat efficiency, DTCs
├── feature/diagnostics/      Six debug screens
├── feature/settings/         Transport / units / theme
├── core/transport/           Transport interface + Bluetooth/Mock/Replay impls
├── core/obd/                 ELM327/STN protocol, PID registry, decoders
├── core/can/                 CAN frame parser, Mazda DBC table (TODO_VERIFY)
├── core/telemetry/           TelemetryRepository, snapshots, scheduler
└── core/service/             Foreground TelemetryService
```

## Daily-driver flow

The app is designed so 90% of development happens on the emulator with the Mock transport. In-car visits are for *validating*, not *building*. Workflow:

1. **Make changes on a laptop**, run on the Pixel emulator. `MockTransport` synthesizes plausible OBD chatter — every gauge, every diagnostic screen exercises off the same data path.
2. **Switch to ReplayTransport** when iterating on parser issues. Drop a `.miatatrace` (our format) or a Torque Pro CSV export into `Documents/MiataDash/traces/`, open the Trace Capture screen, tap a trace to load it.
3. **In the car**: Settings → Bluetooth, pick the OBDLink MX+ from the paired list (pair it once via system Settings, PIN 1234). Open the dashboard. If gauges don't move, hop to Diagnostics → Raw Console and watch the wire traffic.

## Mazda CAN map status

Every entry in `core/can/MazdaNcDbc.kt` is a *guess* drawn from public sources (commaai/opendbc, Miata.net, RX-8/CX-7 community DBCs). All carry `verified = false` — the dashboard ignores them until confirmed.

To verify:
- Diagnostics → CAN Monitor.
- Apply a candidate frame ID as a filter, drive briefly, watch the data bytes.
- Correlate: turn the wheel — do bytes 0–1 of `0x4DA` change? Hit the brakes — does `0x4D2` flicker? Spool the tach — does `0x201` track RPM?
- Once confirmed, flip `verified = true` in the DBC file.

If you've already pulled raw CAN traces with Torque Pro, drop the CSV in the traces folder and use Replay to inspect offline.

## Known TODOs (post-import)

- DataStore-backed settings persistence (units, theme, transport choice across launches).
- Tighten `Mode06Decoder` once we see real NC1 responses — current row layout is the J1979 "modern" form; pre-2008 cars sometimes use a different framing.
- Auto-reconnect strategy in `ObdSession` (currently transitions to `Reconnecting` on transport drop but doesn't itself retry).
- Replace placeholder app icons (`mipmap/ic_launcher` references in the manifest — Studio will prompt to create them on first build).
- Multi-frame ISO-TP reassembly for Mode 09 PID 02 (VIN). Single-frame works on most ECUs but full handling is one PR away.

## Build status caveat

This codebase was written end-to-end without an interactive Gradle sync. First import may surface trivial touch-ups: an unused import here, an SDK-version warning there. The shape of the project is solid; treat the first compile errors (if any) as nudges from the toolchain, not fundamental problems.
