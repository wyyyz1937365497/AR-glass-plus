#!/system/bin/sh

MODDIR=${0%/*}

# Watchdog only. The display fix is intentionally never loaded at boot.
"$MODDIR/bin/ar-glass-dpctl" watchdog >/dev/null 2>&1 &
