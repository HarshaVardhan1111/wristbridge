# Install WristBridge on the watch (ADB over Wi-Fi, Windows)

You only do steps 1–6 once. After that, start at step 6 (connect) each time.

## 1. Put the watch and the PC on the same Wi-Fi

On the watch: **Settings > Connections > Wi-Fi**. Join the same network as
your PC.

## 2. Turn on developer mode on the watch

1. **Settings > About watch > Software information**.
2. Tap **Software version** 7 times.
3. You see "Developer mode turned on".

## 3. Turn on debugging

1. **Settings > Developer options**.
2. Turn on **ADB debugging**.
3. Turn on **Wireless debugging** (it may be called "Debug over Wi-Fi").

## 4. Find adb on your PC

Android Studio installs `adb` here:

```
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
```

Open **Command Prompt** and go there:

```
cd %LOCALAPPDATA%\Android\Sdk\platform-tools
```

## 5. Pair the PC with the watch

1. On the watch: **Developer options > Wireless debugging > Pair new device**.
2. The watch shows an **IP address and port** (for example
   `192.168.1.23:37421`) and a **6-digit code**.
3. On the PC:

   ```
   adb pair 192.168.1.23:37421
   ```

4. Type the 6-digit code. You see "Successfully paired".

## 6. Connect

1. On the watch, go back to the **Wireless debugging** screen. It shows
   another **IP address and port**. This port is **different** from the
   pairing port.
2. On the PC:

   ```
   adb connect 192.168.1.23:41235
   adb devices
   ```

3. `adb devices` should list the watch as `device`. If it says
   `unauthorized`, look at the watch and tap **Allow**.

## 7. Install the app

- **Android Studio:** pick the watch in the device list at the top, then
  press **Run ▶**.
- **Or a terminal**, in the project folder:

  ```
  gradlew.bat :app:installDebug
  ```

## 8. Read the logs

```
adb logcat -s WristBridge
```

To save the logs to a file you can send:

```
adb logcat -s WristBridge > wristbridge-log.txt
```

## Problems

- **Can't connect after a reboot or a Wi-Fi change:** the port changes. Look
  at the Wireless debugging screen again and repeat step 6.
- **The connection drops after a few minutes:** the watch turns Wi-Fi off to
  save battery. Keep the watch on its charger while testing.
- **`adb` is not recognized:** you are not in the `platform-tools` folder
  (see step 4).
