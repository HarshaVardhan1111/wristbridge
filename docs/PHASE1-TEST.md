# Phase 1 test: pair and log one notification

Goal: the iPhone pairs with the watch, and one real iPhone notification
shows up in Logcat.

## 1. Remove Merge

Merge and WristBridge would fight over the same Bluetooth link.

1. On the watch: uninstall **Merge**.
2. On the iPhone: **Settings > Bluetooth**. Tap the **(i)** next to the
   watch, then **Forget This Device**.
3. On the iPhone: delete the **Merge** app, so it can't reconnect.
4. On the watch: **Settings > Connections > Bluetooth**. If your iPhone is
   in the list, tap it and choose **Unpair**. (If it is not in the list,
   skip this.) An old bond from Merge can make the first pairing fail.

## 2. Install and start WristBridge

1. Install the app (see [INSTALL-ADB-WIFI.md](INSTALL-ADB-WIFI.md)).
2. On the PC, start the log: `adb logcat -s WristBridge`
3. On the watch, open **WristBridge**.
4. Tap **Grant permissions** and allow everything.
5. Tap **Start**.

## 3. Pair from the iPhone

1. On the iPhone: **Settings > Bluetooth**.
2. Under **Other Devices**, tap the watch's name (for example
   "Galaxy Watch6 Classic (XXXX)").
3. Tap **Pair**. Then tap **Allow** when iOS asks to show notifications.
4. If the watch shows a pairing prompt, accept it.

## 4. Make a notification

Send yourself a message from another phone, or set a Reminder for 1 minute
from now.

## 5. Pass

Logcat shows these lines, in this order:

```
Service started
Advertising started (PAIRING)
iPhone connected: …
Bonded
MTU …                      (or "Continuing with the current MTU")
ANCS found
ANCS subscribed
Event ADDED uid=… category=… flags=0x…
Notification: app=… title=… message=…
```

You will also see many `Event ADDED … flags=0x04` lines right after
`ANCS subscribed`. Those are notifications that were already on the iPhone
(flag 0x04 = "pre-existing"). This is normal. The app does not fetch their
text.

## 6. If it fails

Send me the full output of `adb logcat -s WristBridge`. What the first
missing line means:

| Last line you see | Meaning | What to do |
| --- | --- | --- |
| `Service started`, then `Advertising failed: …` | Samsung blocks advertising for apps | Stop and report it: the whole plan depends on this |
| `Advertising started`, but the watch isn't in the iPhone list | iOS doesn't list it | Install **nRF Connect** on the iPhone, scan, find the watch, tap **Connect** |
| `iPhone connected`, but no `Bonded` | Pairing didn't finish | Accept the prompts on both devices; try again |
| `Bonded (already)` on the very first pairing, then `Subscribe failed … status 5` (or 15) | The watch kept an old bond from Merge | Do step 1.4 again, then forget the watch on the iPhone and pair again |
| `Bonded`, but no `ANCS subscribed` | Subscribing failed | Send the log |
| `ANCS subscribed`, but no `Notification:` | Fetching failed | Send the log |

## After you reinstall the app

The iPhone may stay connected while the app restarts. The app tries to pick
up that connection by itself (`iPhone already connected` in the log). If
nothing happens, turn Bluetooth off and on again on the iPhone.

## Tip

If the logs stop when the watch screen turns off, set
**Settings > Apps > WristBridge > Battery** to **Unrestricted**.
