# pluginmeshtastic

ATAK plugin that talks to Meshtastic devices directly over BLE / USB.
No standalone Meshtastic Android app required.

## How this differs from the other Meshtastic ATAK plugin

| | this plugin | atak-plugin (legacy) |
|---|---|---|
| Talks to radio via | BLE/USB GATT, in-process | IMeshService AIDL on the Meshtastic Android app |
| Meshtastic Android app required | no | yes |
| Process boundary | runs inside ATAK | bridges across two apps |
| Provisioning | self-contained | through the Meshtastic app |
| Lockdown firmware | supported | not yet |

## Features

- Direct BLE / USB-Serial PhoneAPI client (no IMeshService dependency)
- One-tap TAK-optimized device config (SHORT_TURBO, LOCAL_ONLY, no telemetry, US region)
- PSK channel setup from a password
- ATAK port 72 + chunked port 257 with GZIP
- Auto-reconnect and queue-aware sending
- Lockdown (MESHTASTIC_LOCKDOWN): passphrase provisioning, auto-replay on reconnect, Lock Now

## Architecture

The plugin embeds its own PhoneAPI client: GATT writes go to the ToRadio
characteristic and FromRadio packets come back through GATT notifications,
all inside the ATAK process. This avoids the two-app handoff (and its
lifecycle quirks) that the IMeshService-based plugin inherits from the
Meshtastic Android app.

## Quick start

1. Install the APK, enable the plugin in ATAK.
2. Connection tab → Scan → pick your device.
3. Settings tab → set channel password.
4. Plugin auto-applies TAK config; the device reboots once and comes back ready.

## Build

Requires the ATAK CIV SDK (5.5+).

```
cp template.local.properties local.properties   # fill sdk.dir + takdev.plugin
./gradlew :app:assembleCivDebug
```

## Troubleshooting

- Stuck on "Connecting…": confirm the radio is on and not paired to the Meshtastic Android app or another client (BLE allows only one).
- Region wrong after auto-config: the plugin defaults to US. Edit `MeshtasticConfigManager.setRegion(...)` to change.
- Lockdown device wants a passphrase: the dialog appears the first time; the plugin caches it (encrypted) per BLE MAC after that.

## Dependencies

- ATAK-CIV 5.5.0+, Android 5.0+, Bluetooth LE
- no.nordicsemi.android:ble, usb-serial-for-android, protobuf-javalite, kotlinx-coroutines

## License

GPL-3.0
