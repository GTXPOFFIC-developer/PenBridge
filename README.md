# PenBridge — Universal Tablet-as-Digitizer Bridge

Turn any **Android tablet** (S-Pen / active stylus) or **iPad** (Apple Pencil) into a professional low-latency pen tablet (Wacom-style) for **Windows**, **Linux**, and **macOS**, over **USB** or **Wi-Fi**.

---

## Features

- ⚡ **Ultra-Low Latency & OSU! Mode**: Binary UDP stream up to 240 Hz with sub-millisecond overhead. Dedicated **⚡ OSU! Mode** removes hover throttling for rhythm gaming.
- 🖊️ **Full Stylus Dynamics & S-Pen Customization**: Remap the S-Pen / active stylus barrel button to Right-Click, Middle-Click, Eraser, Double-Click, or Undo (`Ctrl+Z`).
- ✨ **Real-Time Pen Trail**: Live glowing ink trail under the stylus nib with instant response.
- 🖐️ **Stylus-Only Mode**: Active digitizer filtering with 100% rejection of palms, resting hands, and capacitive finger gestures.
- 📐 **Drawing-Area Cropping & Multi-Monitor**: Choose tablet mapping presets (16:9, Top Half, Full) and lock to specific PC monitors with letterbox aspect ratio preservation.
- 🔌 **Dual Connection Modes**:
  - **USB Mode**: Zero jitter, sub-millisecond latency via ADB reverse (Android) or USB tunnel (iOS).
  - **Wi-Fi Mode**: Zero-configuration auto-discovery via mDNS (`_dashboard._udp.local`).
- 🔐 **Zero-Setup Pairing**: Google OAuth auto-pairing allows tablet and PC signed into the same Google account to link instantly without entering codes.
- 💎 **100% Free, Ad-Free & Open-Source**: All features unlocked forever with zero paywalls and zero ads.
- 🎨 **Creative App Ready**: Tested and compatible with Photoshop, Krita, Clip Studio Paint, Blender, Affinity, and OneNote.
- 🌐 **True Cross-Platform**: Native client apps for Android & iOS; native host background services for Windows, Linux, and macOS.

---

## Repository Structure

```
├── Android/                    # Android client app (Kotlin + Jetpack Compose)
│   ├── app/src/main/java/      # PenSurfaceView, MdnsDiscovery, ConnectionManager
│   └── app/src/main/res/       # Material 3 UI, vector assets, launcher icons
├── iOS/                        # iOS / iPadOS client app (SwiftUI + PencilKit)
│   ├── Dashboard.xcodeproj     # Xcode project
│   ├── Package.swift           # Swift Package Manager definition
│   ├── Sources/Dashboard/      # Apple Pencil capture, UDP/TCP networking
│   └── build-ipa.sh            # Automated IPA builder script
├── Windows/                    # Windows host service & UI (.NET 8 WPF)
│   ├── Core/                   # SyntheticPointerInput driver, AdbHelper, Protocol
│   ├── Installer/              # Inno Setup scripts (DashboardX64.iss, DashboardX86.iss)
│   └── MainWindow.xaml         # Glassmorphism tray & status monitor
├── Linux/                      # Linux host daemon (.NET 8)
│   ├── LinuxInputInjector.cs   # Linux /dev/uinput virtual tablet driver
│   ├── install.sh              # Systemd service installer & udev rules setup
│   └── uninstall.sh            # Service and udev cleanup script
├── macOS/                      # macOS host daemon (.NET 8)
│   ├── MacInputInjector.cs     # CoreGraphics tablet event injector
│   └── build-pkg.sh            # Universal binary packager (x64 + Apple Silicon)
├── tools/                      # Diagnostic and debugging utilities
│   └── host-probe.ps1          # Lightweight PowerShell test host
├── .github/workflows/          # CI/CD automated release pipeline
│   └── release.yml             # Builds & publishes releases for all 5 platforms
└── PROTOCOL.md                 # Binary wire protocol specification
```

---

## Downloads & Releases

Pre-compiled releases for all platforms are published on [GitHub Releases](https://github.com/GTXPOFFIC-developer/PenBridge/releases):

| Platform | Type | Release Asset | Description |
|---|---|---|---|
| **Android** | Client | `PenBridge-Android-v*.apk` | Android app with S-Pen & touch digitizer |
| **Windows (x64)** | Host | `PenBridge-Windows-x64-v*.exe` | 64-bit Windows installer with driver & UI |
| **Windows (x86)** | Host | `PenBridge-Windows-x86-v*.exe` | 32-bit Windows installer |
| **Windows (Portable)**| Host | `DashboardHost-Windows-v*.zip` | Standalone portable Windows executable |
| **Linux (x64)** | Host | `DashboardHost-Linux-v*.tar.gz` | Linux daemon with uinput & install scripts |
| **macOS (Universal)** | Host | `DashboardHost-macOS-v*.zip` | macOS daemon (Apple Silicon + Intel) |
| **iOS / iPadOS** | Client | `Dashboard-iOS-v*.zip` | Xcode archive & IPA export for iPad |

---

## Quick Start

### 1. Windows Host Setup
1. Download and run `PenBridge-Windows-x64-v*.exe`.
2. The host will launch and minimize to the system tray, auto-opening firewall ports for UDP `41173` (discovery) and UDP `41174` (data).
3. Connect your tablet via USB or ensure both devices are on the same Wi-Fi.

### 2. Linux Host Setup
```bash
tar -xzvf DashboardHost-Linux-v*.tar.gz
cd DashboardHost-Linux/
sudo ./install.sh
# Runs as a systemd background service with /dev/uinput permissions
```

### 3. macOS Host Setup
```bash
unzip DashboardHost-macOS-v*.zip
# Grant Accessibility & Input Monitoring permissions when prompted
./DashboardHost.macOS
```

### 4. Android Client Setup
1. Install `PenBridge-Android-v*.apk` onto your tablet.
2. Open PenBridge. Select **USB** or **Wi-Fi**.
3. For USB mode: connect USB cable with USB Debugging enabled (the host automatically configures `adb reverse`).
4. Start drawing!

### 5. iOS / iPadOS Client Setup
1. Build and install to iPad using Xcode or `build-ipa.sh`.
2. Select your host computer discovered via mDNS.
3. Use Apple Pencil on the canvas.

---

## Building from Source

### Windows Host
```powershell
cd Windows
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

### Linux Host
```bash
cd Linux
dotnet publish -c Release -r linux-x64 --self-contained true -p:PublishSingleFile=true
```

### macOS Host
```bash
cd macOS
dotnet publish -c Release -r osx-arm64 --self-contained true
dotnet publish -c Release -r osx-x64  --self-contained true
```

### Android Client
```powershell
cd Android
.\gradlew.bat assembleRelease
```

### iOS Client
```bash
cd iOS
./build-ipa.sh
```

---

## Protocol

For wire format, packet headers, event flags, and OAuth exchange details, see [PROTOCOL.md](PROTOCOL.md).

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.