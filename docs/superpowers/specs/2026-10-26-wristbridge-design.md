# WristBridge — Design Spec

Date: 2026-10-26
Status: Approved in chat, waiting for review of this written spec

## 1. Goal

A Wear OS app for a Samsung Galaxy Watch 6 Classic (Wear OS 4, API 33)
that works with an iPhone 14 Pro **without any iPhone app**. It replaces
the Merge app for daily use, so battery life and reliability matter.

The watch talks to the iPhone over Bluetooth LE using Apple's public
accessory services:

- **ANCS** (Apple Notification Center Service): get iPhone notifications,
  dismiss them, accept/decline incoming calls.
- **AMS** (Apple Media Service): show current track, play/pause,
  next/previous, volume.

### Out of scope

- **CTS clock sync.** A normal app cannot set the watch clock. Changing
  system time needs the `SET_TIME` permission, which only system apps get.
  The watch already gets the time from the network. Dropped.
- Replying to messages. ANCS has no reply command.
- Any iPhone app.

### User context

- The watch was set up with an Android phone once. That phone is no longer
  in use.
- The watch currently works with the iPhone through the Merge app.
  Merge must be removed from the watch, and the watch "forgotten" in the
  iPhone's Bluetooth settings, before testing Phase 1. The exact steps
  go in the Phase 1 test instructions.

## 2. Feasibility risks (only the real watch can answer)

| Risk | What we expect | How Phase 1 shows it |
| --- | --- | --- |
| Samsung lets a normal app advertise over BLE | Likely yes (`isMultipleAdvertisementSupported`) | Log line `Advertising started` |
| iPhone lists the watch in Settings > Bluetooth | Likely yes, because the ad solicits ANCS | You see the watch's Bluetooth name in the list |
| iOS lets the watch subscribe to ANCS after bonding | Yes, this is ANCS's normal use | Log line `ANCS subscribed` |
| Android recognises the iPhone's rotating address after bonding (needed for reconnect option B) | Unknown | Tested in Phase 5 |

Fallback for row 2: a general BLE scanner app on the iPhone (for example,
nRF Connect) can connect to the watch and trigger pairing. This is a
generic tool, not a custom iPhone app.

If a risk turns out to be blocked (for example, Samsung blocks
advertising), we stop and report it. No fake workarounds.

## 3. Verified protocol facts

Source: Apple's ANCS spec and AMS spec (developer.apple.com documentation
archive), checked on 2026-10-26. All numbers are little endian. All strings
are UTF-8.

### ANCS

| Item | UUID |
| --- | --- |
| Service | `7905F431-B5CE-4E99-A40F-4B1E122D00D0` |
| Notification Source (notify) | `9FBF120D-6301-42D9-8C58-25E699A21DBD` |
| Control Point (write with response) | `69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9` |
| Data Source (notify) | `22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB` |

All three characteristics need an encrypted (bonded) link.

**Notification Source packet (8 bytes):**
`EventID(1) EventFlags(1) CategoryID(1) CategoryCount(1) NotificationUID(4)`

- EventID: 0 Added, 1 Modified, 2 Removed
- EventFlags bits: 0 Silent, 1 Important, 2 PreExisting,
  3 PositiveAction, 4 NegativeAction
- CategoryID: 0 Other, 1 IncomingCall, 2 MissedCall, 3 Voicemail,
  4 Social, 5 Schedule, 6 Email, 7 News, 8 HealthAndFitness,
  9 BusinessAndFinance, 10 Location, 11 Entertainment

**Control Point commands:**

- Get Notification Attributes: `0x00, UID(4), [AttrID, (MaxLen(2) for 1,2,3)]...`
- Get App Attributes: `0x01, AppIdentifier (NULL-terminated), AttrID...`
- Perform Notification Action: `0x02, UID(4), ActionID(1)`
  (ActionID 0 Positive, 1 Negative)

NotificationAttributeID: 0 AppIdentifier, 1 Title (+maxlen),
2 Subtitle (+maxlen), 3 Message (+maxlen), 4 MessageSize, 5 Date,
6 PositiveActionLabel, 7 NegativeActionLabel.
AppAttributeID: 0 DisplayName.

**Data Source response:** `CommandID(1)`, then the UID (4 bytes) or the
NULL-terminated AppIdentifier, then for each requested attribute, in request
order: `AttrID(1) Length(2) Value(Length bytes, not NULL-terminated)`.
A response may be split over several GATT notifications. It is complete
when every requested attribute tuple has been fully received.

**Control Point errors:** 0xA0 Unknown command, 0xA1 Invalid command,
0xA2 Invalid parameter, 0xA3 Action failed. On error, nothing comes on
Data Source.

**Sessions:** A session starts when we subscribe to Notification Source
and ends on unsubscribe or disconnect. All UIDs and data are valid only
within one session. On a new session, iOS re-sends existing notifications
with the PreExisting flag.

Apple says to watch the GATT Service Changed characteristic (`0x2A05`),
because ANCS and AMS can appear and disappear. On Android the system
handles Service Changed itself and tells the app through
`BluetoothGattCallback.onServiceChanged()` (API 31+), so the app listens
there instead of subscribing itself.

### AMS

| Item | UUID |
| --- | --- |
| Service | `89D3502B-0F36-433A-8EF4-C502AD55F8DC` |
| Remote Command (write, notify) | `9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2` |
| Entity Update (write with response, notify) | `2F7CABCE-808D-411F-9A0C-BB92BA96C102` |
| Entity Attribute (read, write) | `C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7` |

- RemoteCommandID: 0 Play, 1 Pause, 2 TogglePlayPause, 3 NextTrack,
  4 PreviousTrack, 5 VolumeUp, 6 VolumeDown (7–13 not used by us).
- EntityID: 0 Player, 1 Queue, 2 Track.
- Player attributes: 0 Name, 1 PlaybackInfo (`state,rate,elapsed` as a
  comma-separated string; state 0 Paused, 1 Playing, 2 Rewinding,
  3 FastForwarding), 2 Volume (`"0.0"`–`"1.0"`).
- Track attributes: 0 Artist, 1 Album, 2 Title, 3 Duration.
- Entity Update command: `EntityID, AttrID, AttrID...`
- Entity Update notification: `EntityID(1) AttrID(1) Flags(1) Value(UTF-8)`;
  Flags bit 0 = Truncated.
- To read a truncated value in full, write `EntityID, AttrID` to Entity
  Attribute, then read it.
- Errors: 0xA0 Invalid state, 0xA1 Invalid command, 0xA2 Absent attribute.

### Advertising

- Android API 31+ has `AdvertiseData.Builder.addServiceSolicitationUuid`.
  The watch is API 33, so this works.
- Main advertisement: flags + 128-bit ANCS solicitation UUID
  (3 + 18 = 21 bytes, under the 31-byte limit).
- Scan response: the device name. The name does not fit in the main
  packet together with the 128-bit UUID. Android always advertises the
  watch's own Bluetooth name (for example "Galaxy Watch6 Classic (XXXX)").
  The app does not rename the watch's Bluetooth; the user can rename the
  watch in its own settings if they want.
- Connectable: yes.

## 4. Architecture

Two Gradle modules.

### `:protocol` (pure Kotlin/JVM, no Android)

Unit-tested on a computer. Holds every byte-level rule, plus all
logic that does not need Android, so it can be tested without the watch
or the Android SDK.

- `ancs/AncsUuids` and `ancs/AncsIds`: constants from section 3.
- `ancs/NotificationSourceEvent`: parses the 8-byte packet.
- `ancs/ControlPointCommands`: builds Get Notification Attributes,
  Get App Attributes and Perform Notification Action byte arrays.
- `ancs/DataSourceAssembler`: joins the pieces of a response into a whole
  response. Knows which attributes were requested, so it knows when
  the response is complete.
- `ancs/AncsSession`: the ANCS logic for one session. Decides which
  events to fetch, sends one request at a time, handles errors and
  timeouts, forgets everything when the session ends.
- `gatt/GattQueue`: runs one GATT operation at a time (Android allows only
  one in flight). Each operation has a timeout.
- `ams/` (Phase 4): constants, Entity Update parser, command builders.

### `:app` (Wear OS, Android)

- `ble/Advertiser`: starts and stops the solicitation ad. Two modes:
  *pairing* (balanced, while the user pairs) and *background*
  (low power, the backup reconnect path from option C).
- `ble/GattServer`: an empty `BluetoothGattServer`. Its only job is to
  tell us when the iPhone connects to the watch.
- `ble/IphoneLink`: the state machine.
  `Idle → Advertising → Connected → Bonding → Discovering → Subscribed`,
  plus `Reconnecting` on link loss.
- `service/BridgeService`: foreground service (type `connectedDevice`).
  Owns `IphoneLink` and keeps it alive with the screen off.
- `MainActivity`: asks for permissions and has a Start button.

Added in later phases: `notify/` (Phase 2), `ui/call` (Phase 3),
`ui/media` (Phase 4), `ui/status` and `settings/` (Phase 5).

### Permissions (manifest)

`BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
(with `neverForLocation`), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE` (needed from Android 14; harmless
on 13).

## 5. Data flow

### First pairing (Phase 1)

1. The user taps Start. The app requests runtime permissions, then
   starts `BridgeService`.
2. The service opens the empty GATT server and starts the *pairing*
   advertisement.
3. On the iPhone: Settings > Bluetooth > tap the watch's name.
4. `GattServer` reports the connected device. Advertising stops.
   The watch calls `device.connectGatt(..., TRANSPORT_LE)` on the same
   link.
5. If the device is not bonded, call `createBond()`. Wait for
   `ACTION_BOND_STATE_CHANGED = BONDED`. The iPhone shows a pairing
   popup, then "Allow notifications?".
6. Request MTU 185. Discover services. Find ANCS.
7. Subscribe in this order: Service Changed, then **Data Source**, then
   **Notification Source**. (Apple: events can arrive as soon as Notification
   Source is subscribed.)
8. For each `Added` event without the PreExisting flag, write Get
   Notification Attributes (AppIdentifier, Title 64, Message 256).
   Requests go one at a time.
9. `DataSourceAssembler` returns the complete response. Log app id, title,
   message to Logcat (tag `WristBridge`).

### Reconnect (option C, finished in Phase 5)

- Main path: `connectGatt(autoConnect = true)` on the bonded iPhone.
  The Bluetooth chip watches for it at low power.
- Backup path: *background* advertising at low power, so iOS can reconnect
  to us.
- Whichever connects first wins. The other path is stopped.
- Phase 1 contains only a simple version: on disconnect, go back to
  advertising.

## 6. Error handling

| Situation | Action |
| --- | --- |
| Link lost | Clear all session data (UIDs, pending requests). Go to `Reconnecting`. |
| GATT status 133 or other GATT error | `close()` the GATT object. Retry with backoff 2s, 4s, 8s … max 5 min. |
| Control Point error 0xA0–0xA3 | Log it, drop that request, continue with the next one. |
| Data Source response not complete after 5s | Drop the partial data, continue with the next request. |
| GATT operation gets no callback | `GattQueue` timeout (10s; 30s for subscribe writes, because iOS may wait for the user to tap "Allow notifications"), then treat as GATT error. A late callback for an older operation is ignored. |
| Bluetooth turned off | Stop everything. Restart when `ACTION_STATE_CHANGED = ON`. |
| Many PreExisting events on connect | Phase 1: log only, don't fetch text. |
| ANCS not found after discovery | Log clearly. Wait for `onServiceChanged`, then rediscover. If ANCS is still not subscribed 60s after discovery started, start over. |
| iPhone already connected when the app starts (reinstall, process restart) | Adopt the existing bonded connection instead of waiting for a new one. |
| GATT server fails to open | Treat as a link error (back off and retry). |

## 7. Phases and test checklists

Each phase stops so the user can test on the real watch.

- **Phase 1 – Feasibility.** iPhone pairs, one real notification is
  logged in Logcat.
  Pass: Logcat shows `Advertising started`, `Bonded`, `ANCS subscribed`,
  and a line with app id + title + message of a real notification.
- **Phase 2 – Notifications.** Post each iPhone notification as a Wear OS
  notification (app display name via Get App Attributes, cached per
  session; title; message). Dismiss on the watch sends a Negative action when the event has the
  NegativeAction flag (on iOS this usually clears the notification).
  Removed events cancel the watch notification.
- **Phase 3 – Calls.** CategoryIncomingCall opens a full-screen call
  screen with Accept (Positive action) and Decline (Negative action).
  Labels come from the action label attributes.
- **Phase 4 – Music.** AMS screen: track title/artist, play/pause,
  next/previous, volume up/down.
- **Phase 5 – Polish.** Connection status screen, full option C reconnect,
  per-app mirroring settings, battery check.

## 8. Testing

- `:protocol`: JUnit tests written before the code (test-first). Test data
  is built from the byte layouts in section 3. Run with
  `gradlew -p protocol test` (`:protocol` is an included build, so it builds without the Android SDK).
- `:app`: manual tests on the watch, with the checklists in section 7.
  Logcat tag `WristBridge`.
- Limit: Claude cannot run anything on the watch. Claude will try to build
  the APK in the cloud session. If the Android SDK cannot be installed
  there, the user builds in Android Studio.

## 9. Repository

- `README.md`: what the app does, status per phase, known limits.
- `docs/INSTALL-ADB-WIFI.md`: Windows steps for developer mode, wireless
  ADB pairing, installing, and reading Logcat.
- `.gitignore` (Android), `LICENSE` (MIT, © 2026 Harsha).
- `docs/superpowers/specs/` and `docs/superpowers/plans/`.

## 10. Project settings

- Package `com.wristbridge`, app name "WristBridge".
- `minSdk 33`, Kotlin, Gradle Kotlin DSL with a version catalog.
- Jetpack Compose for Wear OS for screens (from Phase 2 onward; Phase 1
  uses a minimal Compose screen with a Start button).
