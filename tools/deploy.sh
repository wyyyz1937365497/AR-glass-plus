#!/usr/bin/env bash
set -euo pipefail

# Target device. Required — never auto-pick, multiple transports may exist
# (USB + wireless + other devices on the LAN).
#   export AR_DEVICE=192.168.0.102:34271   # wireless
#   export AR_DEVICE=JN9PYDTGUSGUPFOZ      # USB serial
DEVICE="${AR_DEVICE:?Set AR_DEVICE first (e.g. export AR_DEVICE=192.168.0.102:34271)}"

PKG="com.example.ar_glass_plus"
APK="app/build/outputs/apk/debug/app-debug.apk"
REMOTE="/data/local/tmp/ar-glass-plus.apk"

echo "[1/4] Building..."
./gradlew assembleDebug

echo "[2/4] Uploading to $DEVICE..."
adb -s "$DEVICE" push "$APK" "$REMOTE"

echo "[3/4] Installing..."
adb -s "$DEVICE" shell su -c "pm install -r '$REMOTE'"

echo "[4/4] Launching..."
adb -s "$DEVICE" shell am force-stop "$PKG"
adb -s "$DEVICE" shell monkey \
    -p "$PKG" \
    -c android.intent.category.LAUNCHER 1 >/dev/null

echo "Done."
