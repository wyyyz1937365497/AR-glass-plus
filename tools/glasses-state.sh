#!/usr/bin/env bash
# Snapshot device state around RayNeo Air 4 Pro attach/detach. Run once per state:
#   before-attach | after-attach | app-running | glasses-active
set -euo pipefail

DEVICE="${AR_DEVICE:?Set AR_DEVICE first (e.g. export AR_DEVICE=192.168.0.102:34271)}"
STATE="${1:?Usage: $0 <state-name>}"
OUT="build/captures/glasses/$STATE"

mkdir -p "$OUT"
S="adb -s $DEVICE"

echo "== snapshot: $STATE -> $OUT"
$S shell dumpsys display    > "$OUT/dumpsys-display.txt" &
$S shell dumpsys usb        > "$OUT/dumpsys-usb.txt"     &
$S shell dumpsys input      > "$OUT/dumpsys-input.txt"   &
$S shell dumpsys battery    > "$OUT/dumpsys-battery.txt" &
$S shell dumpsys window     > "$OUT/dumpsys-window.txt"  &
$S shell dumpsys package cn.axi.cast > "$OUT/dumpsys-package.txt" &
wait

# USB topology (kernel + usbmanager-level)
$S shell su -c 'cat /sys/kernel/debug/usb/devices 2>/dev/null || echo no-debugfs' > "$OUT/usb-kernel.txt" || true
$S shell su -c 'lsusb 2>/dev/null || echo no-lsusb' > "$OUT/usb-lsusb.txt" || true
$S shell dumpsys usb > "$OUT/usb-dumpsys.txt"

# App process + sockets
$S shell "pidof cn.axi.cast || echo not-running" > "$OUT/app-pid.txt"
$S shell su -c 'ss -tunap 2>/dev/null | grep -Ei "axi|LISTEN" || true' > "$OUT/sockets.txt" || true

echo "done: $(wc -l "$OUT"/*.txt | tail -1)"
