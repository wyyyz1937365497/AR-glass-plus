#!/usr/bin/env bash
set -euo pipefail

# Build on the performance host, then use this laptop only as the explicit
# ADB transport to the tablet.
DEVICE="${AR_DEVICE:?Set AR_DEVICE to the exact wireless endpoint or USB serial}"
BUILD_HOST="${AR_BUILD_HOST:-wyyyz@10.126.126.10}"
REMOTE_REPO="${AR_REMOTE_REPO:-/home/wyyyz/WS/AR-glass-plus}"
PACKAGE="com.example.ar_glass_plus"
REMOTE_APK="$REMOTE_REPO/app/build/outputs/apk/debug/app-debug.apk"

stage_dir="$(mktemp -d /tmp/ar-glass-plus-remote-deploy.XXXXXX)"
trap 'rm -rf -- "$stage_dir"' EXIT

echo "[1/4] Testing and building on $BUILD_HOST..."
ssh "$BUILD_HOST" "cd '$REMOTE_REPO' && ./gradlew testDebugUnitTest assembleDebug"

echo "[2/4] Fetching APK to the ADB laptop..."
scp "$BUILD_HOST:$REMOTE_APK" "$stage_dir/app-debug.apk"

echo "[3/4] Installing on $DEVICE..."
adb -s "$DEVICE" get-state >/dev/null
adb -s "$DEVICE" install -r "$stage_dir/app-debug.apk"

echo "[4/4] Launching control panel on tablet display 0..."
adb -s "$DEVICE" shell am force-stop "$PACKAGE"
adb -s "$DEVICE" shell am start -W -n "$PACKAGE/.MainActivity"

echo "Done."
