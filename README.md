# WristBridge

WristBridge is a Wear OS app that connects a Samsung Galaxy Watch to an
iPhone **without any iPhone app**. It uses Apple's public Bluetooth LE
accessory services:

- **ANCS** (Apple Notification Center Service): iPhone notifications on
  the watch, dismiss them, accept or decline calls.
- **AMS** (Apple Media Service): music controls.

Target: Galaxy Watch 6 Classic (Wear OS 4, API 33) with an iPhone 14 Pro.

## Status

| Phase | What | Status |
| --- | --- | --- |
| 1 | iPhone pairs, notifications are logged in Logcat | Ready to test |
| 2 | Notifications shown on the watch, dismiss action | Not started |
| 3 | Incoming call screen (accept / decline) | Not started |
| 4 | Music controls (AMS) | Not started |
| 5 | Status screen, battery-friendly reconnect, settings | Not started |

## Known limits

- **No clock sync (CTS).** A normal app cannot set the watch clock. That
  needs the `SET_TIME` permission, which only system apps get. The watch
  gets the time from the network anyway.
- **No message replies.** ANCS has no reply command.
- **The iPhone shows the watch's own Bluetooth name** (for example
  "Galaxy Watch6 Classic (XXXX)"), not "WristBridge". Android always
  advertises the system Bluetooth name, and the app does not change it.
- **Only one bridge app at a time.** Uninstall other iPhone-bridge apps
  (such as Merge) first.

## Build

Open the project in Android Studio and let Gradle sync. Or, in a terminal:

```
gradlew.bat :app:assembleDebug
```

Run the protocol tests (plain Kotlin, no Android SDK needed):

```
gradlew.bat -p protocol test
```

Install on the watch: see [docs/INSTALL-ADB-WIFI.md](docs/INSTALL-ADB-WIFI.md).
Test Phase 1: see [docs/PHASE1-TEST.md](docs/PHASE1-TEST.md).

## Project layout

- `protocol/`: plain Kotlin. Apple's ANCS byte formats, the per-connection
  ANCS logic, and a one-at-a-time Bluetooth operation queue. Fully
  unit-tested.
- `app/`: the Wear OS app. Advertising, pairing, the foreground service,
  and the screens.
- `docs/superpowers/`: the design spec and the phase plans.

## License

MIT. See [LICENSE](LICENSE).
