#!/usr/bin/env bash
# ============================================================
# Dashboard Linux Host — Uninstaller
# Usage: sudo ./uninstall.sh
# ============================================================

set -euo pipefail

INSTALL_DIR="/opt/dashboard-host"
BIN_LINK="/usr/local/bin/dashboard-host"
SERVICE_FILE="/etc/systemd/system/dashboard-host.service"
DESKTOP_FILE="/usr/share/applications/dashboard-host.desktop"

echo "═══ Dashboard Host — Uninstaller ═══"

if [ "$EUID" -ne 0 ]; then
    echo "[ERROR] Please run as root: sudo ./uninstall.sh"
    exit 1
fi

echo "[1/4] Stopping and disabling service..."
systemctl stop dashboard-host 2>/dev/null || true
systemctl disable dashboard-host 2>/dev/null || true
rm -f "$SERVICE_FILE"
systemctl daemon-reload

echo "[2/4] Removing files..."
rm -rf "$INSTALL_DIR"
rm -f "$BIN_LINK"
rm -f "$DESKTOP_FILE"

echo "[3/4] Removing firewall rules..."
if command -v ufw >/dev/null 2>&1; then
    ufw delete allow 41173/udp 2>/dev/null || true
    ufw delete allow 41174/udp 2>/dev/null || true
elif command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --remove-port=41173/udp 2>/dev/null || true
    firewall-cmd --permanent --remove-port=41174/udp 2>/dev/null || true
    firewall-cmd --reload 2>/dev/null || true
fi

echo "[4/4] Done. Dashboard Host has been removed."
