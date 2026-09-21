# Dashboard — tablet as a pen tablet

Turn any stylus-capable Android tablet into a (Wacom-style) pen tablet for
your PC, over **USB** or **Wi-Fi**.

This repository contains the **Android app** (Kotlin + Jetpack Compose,
Material 3, dark glassmorphism UI). It captures stylus samples at up to the
display's refresh rate and streams them with a compact binary protocol
defined in [PROTOCOL.md](PROTOCOL.md).

> Windows host app: currently on hold (skipped by request). A tiny
> PowerShell *test host* lives in `tools/host-probe.ps1` so you can verify
> the tablet end-to-end without building anything.

## What's inside

```
Android/            Android Studio project (Gradle, Kotlin, Compose)
  app/src/main/java/com/dashboard/
    core/           protocol, connection manager, discovery, pen capture,
                    Google OAuth sync
    ui/             Home/Connect, Drawing surface, Settings screens
builds/             built APK(s) land here
tools/              host-probe.ps1 (test host for USB/Wi-Fi verification)
PROTOCOL.md         binary wire protocol shared by both ends
```

## Prerequisites

- Android tablet with a stylus (S-Pen, Apple Pencil-style EMR/active pens).
- Android Studio or JDK 17 + Android SDK (platform 34).
- A PC on the same Wi-Fi (for Wi-Fi mode) or a USB cable (for USB mode).
- For USB mode: `adb` (platform-tools) with **USB debugging** enabled on the
  tablet.

## Build the app

```powershell
cd Android
.\gradlew.bat assembleDebug
# APK lands at: build\outputs\apk\debug\app-debug.apk
```

Already-built APK: `builds\Dashboard-debug.apk`.

## Optional: Google account link (Chrome-Remote-Desktop-style pairing)

When both the tablet and PC are signed into the **same Google account**,
the PC auto-authorizes the tablet — no 6-digit code needed. Zero backend;
the tablet just proves its identity over the live link (see PROTOCOL.md,
`GOOGLE_AUTH`).

1. Create an OAuth client:
   - Google Cloud Console → APIs & Services → Credentials → Create OAuth
     client ID.
   - Type **Desktop app** (no secret, PKCE only — best) or **Web
     application** (requires the secret below).
   - Add redirect URI: `http://localhost` (loopback, any port is fine).
2. Put the values into
   `Android/app/src/main/java/com/dashboard/core/GoogleAccountSync.kt`
   (`GoogleConfig`).

> Security note: a Web-client secret embedded in an APK can be extracted by
> anyone who installs it. For personal LAN use that's acceptable, but a
> **Desktop** OAuth client has no secret at all and is the safer choice.

## Test it (no Windows app needed)

1. Build & install: `adb install builds\Dashboard-debug.apk`
2. Run the test host on your PC:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\tools\host-probe.ps1
   ```
3. **Wi-Fi:** tablet → toggle **Wi-Fi** → tap the discovered PC (or enter its
   IP) → **Connected (ms chip turns green)**. Draw on the tablet; the host
   prints live x/y/pressure/tilt + a per-minute sample rate.
4. **USB:** with the cable plugged and USB debugging on:
   ```
   adb reverse tcp:41174 tcp:41174
   ```
   tablet → toggle **USB** → one-tap connect → draw.

You should see the console counting pen samples at 60–240 Hz with sane
y-flipped coordinates and 0–1 pressure.

## Using it in Krita / Photoshop / Clip Studio

A full `InjectSyntheticPointerInput` pen driver is part of the (skipped)
Windows host app. The protocol carries pen **down / move / hover / up**,
pressure and tilt explicitly so those apps' stroke engines can register
strokes cleanly once the host wires them to synthetic `POINTER_PEN_INFO`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Wi-Fi: no PC discovered | Router blocks broadcast; use **manual IP**. |
| USB: stuck "Waiting for adb reverse" | Run `adb reverse tcp:41174 tcp:41174` after plugging in; keep tablet unlocked. |
| `adb devices` shows `unauthorized` | Tap **Allow** on the tablet's USB-debugging dialog. |
| Latency high | Use the wired channel; keep tablet on 5 GHz Wi-Fi; enable "force GPU" not required — samples are socket-direct. |