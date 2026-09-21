#!/usr/bin/env bash
# ============================================================
# Dashboard iOS — IPA Installer Builder
# Run on macOS with Xcode installed
# Usage: ./build-ipa.sh [--device-id <UDID>]
# ============================================================

set -euo pipefail

SCHEME="Dashboard"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARCHIVE_PATH="$PROJECT_DIR/build/Dashboard.xcarchive"
IPA_OUTPUT="$PROJECT_DIR/build/ipa"
IPA_FILE="$IPA_OUTPUT/Dashboard.ipa"

echo "╔══════════════════════════════════════════════════════╗"
echo "║       Dashboard iOS — IPA Installer Builder          ║"
echo "║     Apple Pencil Bridge · Google OAuth Auto-Pair     ║"
echo "╚══════════════════════════════════════════════════════╝"

# Check Xcode
if ! command -v xcodebuild >/dev/null 2>&1; then
    echo "[ERROR] Xcode not found. Install Xcode from the App Store."
    exit 1
fi

echo ""
echo "[1/4] Archiving Dashboard for iOS..."
xcodebuild archive \
    -project "$PROJECT_DIR/Dashboard.xcodeproj" \
    -scheme "$SCHEME" \
    -sdk iphoneos \
    -archivePath "$ARCHIVE_PATH" \
    CODE_SIGN_STYLE=Automatic \
    DEVELOPMENT_TEAM="" \
    | grep -E "^(Build|Archive|error:|warning:)" || true

echo "[2/4] Exporting IPA..."
mkdir -p "$IPA_OUTPUT"

cat > "$PROJECT_DIR/build/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>development</string>
    <key>compileBitcode</key>
    <false/>
    <key>thinning</key>
    <string>&lt;none&gt;</string>
    <key>iCloudContainerEnvironment</key>
    <string>Development</string>
</dict>
</plist>
EOF

xcodebuild -exportArchive \
    -archivePath "$ARCHIVE_PATH" \
    -exportPath "$IPA_OUTPUT" \
    -exportOptionsPlist "$PROJECT_DIR/build/ExportOptions.plist" \
    | grep -E "^(Export|error:|warning:)" || true

echo "[3/4] Verifying IPA..."
if [ -f "$IPA_OUTPUT/Dashboard.ipa" ]; then
    IPA_FILE="$IPA_OUTPUT/Dashboard.ipa"
elif ls "$IPA_OUTPUT/"*.ipa 1>/dev/null 2>&1; then
    IPA_FILE=$(ls "$IPA_OUTPUT/"*.ipa | head -1)
fi

if [ ! -f "$IPA_FILE" ]; then
    echo "[ERROR] IPA not found. Check signing and provisioning profile."
    exit 1
fi

echo "[4/4] Build complete!"
echo ""
echo "  IPA:            $IPA_FILE"
echo ""
echo "  Install to device:"
echo "    • Use Apple Configurator 2 (drag-drop IPA onto connected device)"
echo "    • Or: xcrun devicectl device install app --device <UDID> '$IPA_FILE'"
echo ""
echo "  For Simulator testing:"
echo "    • Build for simulator: xcodebuild -scheme Dashboard -sdk iphonesimulator -destination 'name=iPad Pro (12.9-inch)'"
