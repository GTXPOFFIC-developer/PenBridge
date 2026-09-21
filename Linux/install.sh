#!/usr/bin/env bash
# ============================================================
# Dashboard Linux Host Installer
# Installs DashboardHost (net8.0) + configures firewall rules
# Usage: sudo ./install.sh
# ============================================================

set -euo pipefail

APP_NAME="DashboardHost"
INSTALL_DIR="/opt/dashboard-host"
DESKTOP_DIR="/usr/share/applications"
ICON_DIR="/usr/share/icons/hicolor/256x256/apps"
BIN_LINK="/usr/local/bin/dashboard-host"
SERVICE_FILE="/etc/systemd/system/dashboard-host.service"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "╔══════════════════════════════════════════════════════╗"
echo "║       Dashboard Linux Host — Installer               ║"
echo "║       Android / iOS Tablet Pen Bridge for Linux      ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# Check for root
if [ "$EUID" -ne 0 ]; then
    echo "[ERROR] Please run as root: sudo ./install.sh"
    exit 1
fi

# Check .NET 8 runtime
if ! command -v dotnet >/dev/null 2>&1; then
    echo "[WARN] .NET 8 runtime not found. Attempting to install..."
    if command -v apt-get >/dev/null 2>&1; then
        apt-get install -y dotnet-runtime-8.0
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y dotnet-runtime-8.0
    elif command -v pacman >/dev/null 2>&1; then
        pacman -S --noconfirm dotnet-runtime-8.0
    else
        echo "[ERROR] Cannot install .NET. Please install 'dotnet-runtime-8.0' manually."
        exit 1
    fi
fi

DOTNET_VER=$(dotnet --version 2>/dev/null | cut -d'.' -f1)
if [ "${DOTNET_VER:-0}" -lt 8 ]; then
    echo "[ERROR] .NET 8 or newer is required (found: $(dotnet --version))"
    exit 1
fi

echo "[1/6] Installing application files to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
cp -r "$SCRIPT_DIR/publish/"* "$INSTALL_DIR/"
chmod +x "$INSTALL_DIR/DashboardHost.Linux"

echo "[2/6] Creating launch symlink..."
ln -sf "$INSTALL_DIR/DashboardHost.Linux" "$BIN_LINK"

echo "[3/6] Configuring firewall (ufw / iptables)..."
if command -v ufw >/dev/null 2>&1; then
    ufw allow 41173/udp comment "Dashboard Host Discovery" || true
    ufw allow 41174/udp comment "Dashboard Host Data"     || true
    echo "      UFW rules added."
elif command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port=41173/udp || true
    firewall-cmd --permanent --add-port=41174/udp || true
    firewall-cmd --reload || true
    echo "      firewalld rules added."
else
    iptables -A INPUT -p udp --dport 41173 -j ACCEPT 2>/dev/null || true
    iptables -A INPUT -p udp --dport 41174 -j ACCEPT 2>/dev/null || true
    echo "      iptables rules added."
fi

echo "[4/6] Installing systemd user service (optional auto-start)..."
cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Dashboard Linux Host — Android/iOS Tablet Pen Bridge
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$INSTALL_DIR/DashboardHost.Linux
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
echo "      Service file installed. Enable with: sudo systemctl enable --now dashboard-host"

echo "[5/6] Creating .desktop launcher..."
mkdir -p "$DESKTOP_DIR"
cat > "$DESKTOP_DIR/dashboard-host.desktop" <<EOF
[Desktop Entry]
Name=Dashboard Host
Comment=Android / iOS Tablet Pen Bridge for Linux
Exec=$INSTALL_DIR/DashboardHost.Linux
Icon=application-x-executable
Terminal=true
Type=Application
Categories=Utility;
EOF

echo "[6/6] Installation complete!"
echo ""
echo "  Run now:        dashboard-host"
echo "  Run as service: sudo systemctl enable --now dashboard-host"
echo "  Uninstall:      sudo ./uninstall.sh"
echo ""
echo "  First run with --login to authenticate with Google:"
echo "    dashboard-host --login"
