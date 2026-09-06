#!/system/bin/sh

MODDIR=${0%/*}
"$MODDIR/bin/ar-glass-dpctl" force-release module-uninstall >/dev/null 2>&1 || true

runtime_dir=/data/adb/ar_glass_plus_dpfix
if [ -f "$runtime_dir/watchdog.pid" ]; then
    watchdog_pid="$(cat "$runtime_dir/watchdog.pid" 2>/dev/null)"
    case "$watchdog_pid" in
        ''|*[!0-9]*) ;;
        *) kill "$watchdog_pid" 2>/dev/null || true ;;
    esac
fi
rm -rf "$runtime_dir"
