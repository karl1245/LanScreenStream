# LAN Screen Stream (Java, Android)

A minimal Android app that shares the device screen to any browser on the same network using **MJPEG over HTTP**.

## Features
- Runs fully on the phone (no PC or server).
- View stream in any browser: `http://<phone-ip>:8080/`
- Foreground service with notification and URL shortcut.
- Captures the device screen using **MediaProjection API**.
- Encodes frames as **JPEG** and serves them over **HTTP/MJPEG**.
- Built-in lightweight HTTP server (default port: **8080**).
- Works on any modern Android device (API 26+).
- Shows the LAN URL inside the app UI so you can copy/paste it easily.
- Foreground service with notification while streaming is active.

## Build & Run (Android Studio)
1. **File → Open**, select the `LanScreenStreamJava` folder.
2. Build & run on a device (Android 8.0+).  
3. Tap **Start Stream** → grant screen capture permission.
4. On another device connected to the same Wi‑Fi/hotspot, open the shown URL.

## Notes
- Default size ~720p @ ~10fps; tweak in `StreamService.java`.
- If you see high CPU/thermals, reduce resolution or JPEG quality.
- This is view-only (no remote control, no audio).