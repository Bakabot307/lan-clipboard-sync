# LAN Clipboard Sync

Android app for sharing copied text between phones on the same Wi-Fi network.

The app discovers nearby devices over LAN, connects them into a sharing group, and relays text clipboard updates between connected devices.

## Features

- LAN device discovery over UDP broadcast.
- WebSocket transport for clipboard sync.
- Device/group view: standalone devices appear individually; connected devices appear as one sharing group.
- Incoming connection requests with optional auto-accept.
- Foreground clipboard monitor with notification controls.
- Notification action to send the current clipboard.
- In-app button to send the current clipboard after connecting.
- Optional auto-copy of received text to the local clipboard.
- Optional system notification when clipboard text is received.
- Clipboard history limited to the latest 10 entries, featuring separate **Copy** (local copy only, suppressed from sync) and **Send** (direct broadcast to other devices) buttons.

## How It Works

1. Open the app on two or more Android devices on the same Wi-Fi.
2. Tap **Find devices**.
3. Select a nearby device or sharing group and tap **Connect**.
4. The receiving device either accepts the request manually or auto-accepts it if **Auto-accept requests** is enabled.
5. Once connected, clipboard text can be sent from the app or notification.

When two devices connect, they become a sharing group. Other devices scanning the LAN will see that group as one item instead of separate connected devices.

## Clipboard Behavior On Android

Android 10+ restricts background clipboard reads for normal apps. Because of that:

- Automatic sending works only while this app is focused.
- The foreground service notification does not bypass Android clipboard restrictions.
- Tapping the monitor notification opens/focuses the app, then sends the current clipboard.
- The monitor notification includes **Open app** and **Exit app** actions.
- Receiving clipboard text over LAN can still work while the app is running.
- Auto-copying received text to this device clipboard is controlled by the app setting.

## Settings

- **Auto-accept requests**: automatically accepts incoming connection requests from other devices.
- **Auto-copy received text**: copies received text into this device clipboard.
- **Received notifications**: shows or hides system notifications for received clipboard text.
- **Clipboard monitor**: controls the foreground monitor notification.

## Build

Requirements:

- Android Studio
- Android SDK with compile SDK 35
- JDK 17

Build from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

## Windows Companion App

A lightweight Windows companion app is available under:

```text
windows_app/
```

It uses only Python's standard library (`tkinter`, `socket`, `json`) and does not require Electron, .NET, or extra Python packages.

You can run it directly with Python:

```powershell
.\windows_app\run_windows_app.bat
```

Or run the compiled standalone executable:

```text
windows_app/dist/lan_clipboard_windows.exe
```

To compile the script into a standalone executable yourself, install PyInstaller (`pip install pyinstaller`) and run:

```powershell
pyinstaller --noconsole --onefile windows_app/lan_clipboard_windows.py
```

If Android can see the PC but the PC does not show requests, run this once as administrator:

```powershell
.\windows_app\allow_windows_firewall.bat
```

If LAN discovery still does not show the Android device, enter the Android device IP in **Android IP**, enter the Android room code in **Room code**, then click **Connect IP**.

The Windows app supports:

- LAN discovery compatible with the Android app.
- Joining or hosting a clipboard sharing room.
- Accepting or rejecting the latest incoming request directly from the Requests controls.
- Sending the current Windows clipboard.
- Copying a history item by clicking it, without re-sending it.
- Sending the selected history item with **Send Selected**.
- Optional clipboard monitoring and auto-copy for received text.
- Optional auto-accept from the Requests controls.
- Disconnect notification for connected Android clients when the PC closes a hosted room.

If Windows Firewall asks for network access, allow access on the LAN/Wi-Fi network.

## Project Details

- Application ID: `com.gnaht.phoneclipboardsync`
- Minimum SDK: 28
- Compile SDK: 35
- UI: Jetpack Compose + Material 3
- Networking: Java-WebSocket, UDP broadcast discovery

## Limitations

- Text clipboard only.
- Devices must be on the same reachable LAN/Wi-Fi network.
- Some networks block broadcast or peer-to-peer traffic.
- Reliable background clipboard monitoring would require a different Android surface such as an input method; this app intentionally stays within normal app permissions.
