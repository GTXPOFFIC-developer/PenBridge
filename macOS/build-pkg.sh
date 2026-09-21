#!/usr/bin/env bash
# ============================================================
# Dashboard macOS Host — .pkg Installer Builder
# Run on macOS: ./build-pkg.sh
# Requires: Xcode Command Line Tools, dotnet 8
# ============================================================

set -euo pipefail

APP_NAME="Dashboard Host"
APP_VERSION="1.0.0"
BUNDLE_ID="com.dashboard.host"
INSTALL_LOCATION="/Applications/Dashboard Host.app"
PKG_OUTPUT="DashboardHost-macOS-1.0.0.pkg"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
PAYLOAD_DIR="$BUILD_DIR/payload"
SCRIPTS_DIR="$BUILD_DIR/scripts"

echo "╔══════════════════════════════════════════════════════╗"
echo "║       Dashboard macOS Host — PKG Installer Builder   ║"
echo "╚══════════════════════════════════════════════════════╝"

# Publish .NET binary
echo "[1/5] Publishing .NET binary for macOS (arm64 + x64)..."
cd "$SCRIPT_DIR"
dotnet publish DashboardHost.macOS.csproj -c Release -r osx-arm64 --self-contained false -o "$BUILD_DIR/publish-arm64"
dotnet publish DashboardHost.macOS.csproj -c Release -r osx-x64  --self-contained false -o "$BUILD_DIR/publish-x64"

# Create fat binary if lipo is available
if command -v lipo >/dev/null 2>&1; then
    echo "[2/5] Creating universal fat binary..."
    mkdir -p "$BUILD_DIR/publish-universal"
    cp -r "$BUILD_DIR/publish-arm64/"* "$BUILD_DIR/publish-universal/"
    lipo -create -output "$BUILD_DIR/publish-universal/DashboardHost.macOS" \
        "$BUILD_DIR/publish-arm64/DashboardHost.macOS" \
        "$BUILD_DIR/publish-x64/DashboardHost.macOS" 2>/dev/null || \
        cp "$BUILD_DIR/publish-arm64/DashboardHost.macOS" "$BUILD_DIR/publish-universal/"
else
    echo "[2/5] lipo not available — using arm64 binary..."
    cp -r "$BUILD_DIR/publish-arm64" "$BUILD_DIR/publish-universal"
fi

# Build .app bundle structure
echo "[3/5] Assembling .app bundle..."
APP_BUNDLE="$BUILD_DIR/Dashboard Host.app"
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
mkdir -p "$APP_BUNDLE/Contents/Resources"

cp -r "$BUILD_DIR/publish-universal/"* "$APP_BUNDLE/Contents/MacOS/"
chmod +x "$APP_BUNDLE/Contents/MacOS/DashboardHost.macOS"

cat > "$APP_BUNDLE/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>              <string>Dashboard Host</string>
    <key>CFBundleDisplayName</key>      <string>Dashboard Host</string>
    <key>CFBundleIdentifier</key>       <string>$BUNDLE_ID</string>
    <key>CFBundleVersion</key>          <string>$APP_VERSION</string>
    <key>CFBundleShortVersionString</key><string>$APP_VERSION</string>
    <key>CFBundleExecutable</key>       <string>DashboardHost.macOS</string>
    <key>CFBundleIconFile</key>         <string>AppIcon</string>
    <key>LSMinimumSystemVersion</key>   <string>13.0</string>
    <key>NSHighResolutionCapable</key>  <true/>
    <key>LSApplicationCategoryType</key><string>public.app-category.utilities</string>
    <key>NSHumanReadableCopyright</key> <string>Copyright © 2026 Dashboard</string>
</dict>
</plist>
EOF

# Create LaunchAgent plist for optional auto-start
mkdir -p "$HOME/Library/LaunchAgents"
cat > "$HOME/Library/LaunchAgents/com.dashboard.host.plist" <<LAUNCHEOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>               <string>com.dashboard.host</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Applications/Dashboard Host.app/Contents/MacOS/DashboardHost.macOS</string>
    </array>
    <key>RunAtLoad</key>           <false/>
    <key>KeepAlive</key>           <false/>
    <key>StandardOutPath</key>     <string>/tmp/dashboard-host.log</string>
    <key>StandardErrorPath</key>   <string>/tmp/dashboard-host.err</string>
</dict>
</plist>
LAUNCHEOF

echo "[4/5] Building .pkg installer..."
mkdir -p "$PAYLOAD_DIR/Applications"
cp -r "$APP_BUNDLE" "$PAYLOAD_DIR/Applications/"

mkdir -p "$SCRIPTS_DIR"
cat > "$SCRIPTS_DIR/postinstall" <<'POSTEOF'
#!/bin/bash
# Open firewall ports (macOS pf)
PF_CONF="/etc/pf.anchors/com.dashboard.host"
cat > "$PF_CONF" <<EOF
pass in proto udp to any port 41173
pass in proto udp to any port 41174
pass in proto tcp from 127.0.0.1 to 127.0.0.1 port 41174
EOF

# Load pf rules
pfctl -a "com.dashboard.host" -f "$PF_CONF" 2>/dev/null || true
echo "Dashboard Host installed successfully."
POSTEOF
chmod +x "$SCRIPTS_DIR/postinstall"

cat > "$SCRIPTS_DIR/preuninstall" <<'PREEOF'
#!/bin/bash
pfctl -a "com.dashboard.host" -F all 2>/dev/null || true
launchctl unload ~/Library/LaunchAgents/com.dashboard.host.plist 2>/dev/null || true
PREEOF
chmod +x "$SCRIPTS_DIR/preuninstall"

pkgbuild \
    --root "$PAYLOAD_DIR" \
    --scripts "$SCRIPTS_DIR" \
    --identifier "$BUNDLE_ID" \
    --version "$APP_VERSION" \
    --install-location "/" \
    "$SCRIPT_DIR/$PKG_OUTPUT"

echo "[5/5] Done!"
echo ""
echo "  Installer created: $PKG_OUTPUT"
echo "  Install:           sudo installer -pkg $PKG_OUTPUT -target /"
echo "  Run:               open '/Applications/Dashboard Host.app'"
echo "  Google sign-in:    '/Applications/Dashboard Host.app/Contents/MacOS/DashboardHost.macOS' --login"
