#!/system/bin/sh

TARGET_KERNEL="6.1.128-android14-11-o-g415ded6ed906"

[ "$KSU" = "true" ] || abort "This package requires KernelSU / SukiSU Ultra"
[ "$ARCH" = "arm64" ] || abort "Unsupported architecture: $ARCH (arm64 required)"

device="$(getprop ro.product.device)"
model="$(getprop ro.product.model)"
kernel="$(uname -r)"

if [ "$device" != "OP615AL1" ] || [ "$model" != "OPD2407" ]; then
    abort "Unsupported device: $model/$device (OPD2407/OP615AL1 required)"
fi
[ "$kernel" = "$TARGET_KERNEL" ] || abort "Unsupported kernel: $kernel"
[ -r "$MODPATH/kernel/rayneo_dp_fix_v6.ko" ] || abort "rayneo_dp_fix_v6.ko missing"
[ -r "$MODPATH/kernel/rayneo_dp_reprobe.ko" ] || abort "rayneo_dp_reprobe.ko missing"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/ar-glass-dpctl" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755

ui_print "- Device and exact kernel ABI verified"
ui_print "- Runtime module will NOT be loaded at boot"
ui_print "- AR-glass-plus owns SBS acquire/release"
if grep -q '^rayneo_dp_fix_v5 ' /proc/modules 2>/dev/null; then
    ui_print "! Legacy test module v5 is currently loaded"
    ui_print "! Unplug glasses and unload v5 before first App-controlled SBS switch"
fi
