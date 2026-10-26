# How to test WristBridge Phase 1 (step by step)

Do the steps in this order. **Keep Merge until Part 4**, so you still get
notifications if the build fails.

## Part 1: Get the code on your PC

1. Open **Command Prompt** on Windows.
2. Run:

   ```
   git clone -b claude/wristbridge https://github.com/HarshaVardhan1111/wristbridge.git
   ```

   If you don't have Git, go to the repo on GitHub, switch to the branch
   `claude/wristbridge`, and choose **Code > Download ZIP**. Then unzip it.

## Part 2: Build in Android Studio

1. Android Studio: **File > Open**. Choose the `wristbridge` folder.
2. Wait for **Gradle sync** to finish (bottom bar). If it asks to install
   SDK 35 or other tools, click **Install**.
3. Menu: **Build > Make Project**.
4. Success = "BUILD SUCCESSFUL". **If it fails, stop and send the error
   text.** This is the first real check of the app code.

## Part 3: Connect the watch to the PC

1. Watch: **Settings > About watch > Software information**. Tap
   **Software version** 7 times.
2. Watch: **Settings > Developer options**. Turn on **ADB debugging** and
   **Wireless debugging**.
3. Wireless debugging > **Pair new device**. Note the IP:port and the
   6-digit code.
4. PC, in Command Prompt:

   ```
   cd %LOCALAPPDATA%\Android\Sdk\platform-tools
   adb pair IP:PORT
   ```

   Type the code.
5. Go back to the **Wireless debugging** screen. It shows a **different**
   port. Run:

   ```
   adb connect IP:PORT
   adb devices
   ```

   The watch should be listed as `device`.

Tip: keep the watch on its charger, so Wi-Fi stays on.

## Part 4: Remove Merge

1. Watch: uninstall **Merge**.
2. iPhone: **Settings > Bluetooth** > **(i)** next to the watch >
   **Forget This Device**.
3. iPhone: delete the **Merge** app.
4. Watch: **Settings > Connections > Bluetooth**. If your iPhone is listed,
   tap it and choose **Unpair**.

## Part 5: Install and start

1. Android Studio: pick the watch at the top, then press **Run ▶**.
2. In a second Command Prompt, start the log:

   ```
   cd %LOCALAPPDATA%\Android\Sdk\platform-tools
   adb logcat -s WristBridge
   ```

3. Watch: open **WristBridge**, tap **Grant permissions**, allow
   everything, then tap **Start**.

## Part 6: Pair from the iPhone

1. iPhone: **Settings > Bluetooth**. Under **Other Devices**, tap the
   watch's name.
2. Tap **Pair**, then **Allow** notifications.
3. If the watch shows a pairing prompt, accept it.

Not in the list? Install **nRF Connect** on the iPhone, scan, find the
watch, and tap **Connect**.

## Part 7: Send a test notification

Send yourself a message from another phone, or set a Reminder for 1 minute
from now.

**It passes if** the log shows `ANCS subscribed`, and then a line like:

```
Notification: app=com.apple.MobileSMS title=… message=…
```

## Part 8: Send the result

Whether it passes or fails, copy the whole log window and send it. What
each failure means is in [PHASE1-TEST.md](PHASE1-TEST.md).
