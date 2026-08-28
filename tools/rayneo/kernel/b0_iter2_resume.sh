#!/bin/bash
# K2-B0 iteration-2 resume script (exact-source ABI pairing)
# State: OnePlusOSS kernel tree (6.1.134) + device modules tree cloned;
#        AOSP clang r487747c extracted; GKI 6.1.157 built OK; module builds OK;
#        CRC parity: 192/518 (struct-dep syms mismatch — need OPPO-patched headers)
set -e

export PATH=/tmp/toolchain/clang-r487747c/bin:$PATH
export V=/tmp/rayneo/gki_modules_mt6897/kernel/kernel_device_modules-6.1
export K=/tmp/rayneo/kernel_oneplus_mt6897
export ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu-

# 1. OnePlus kernel tree build fixes:
#    a) disable WERROR (frame-size blowups in unrelated drivers)
scripts/config --file $K/out/.config -d WERROR 2>/dev/null || \
    sed -i 's/^CONFIG_WERROR=y/# CONFIG_WERROR is not set/' $K/out/.config
make -C $K O=$K/out ARCH=arm64 olddefconfig

#    b) missing vendor headers in core files:
#       kernel/sched/core.c -> ../kernel/oplus_cpu/sched/sched_tune/tune.h
#       (relative include resolves INSIDE kernel tree: kernel/oplus_cpu missing)
#       FIX: symlink modules-tree oplus_cpu into kernel tree:
ln -sfn $V/kernel/oplus_cpu $K/kernel/oplus_cpu
#       mm/vmscan.c -> mm-trace.h : locate in modules tree:
#         find $V -name 'mm-trace.h'  → add its dir to LINUXINCLUDE (KCFLAGS -I works
#         for angle includes only if kernel tree lacks a shadowing copy)

# 2. Build kernel:
make -C $K O=$K/out ARCH=arm64 LLVM=1 LLVM_IAS=1 -j32 Image.gz modules \
    KCONFIG_EXT_PREFIX=$V/ \
    CONFIG_DRM_DISPLAY_HELPER=y CONFIG_DRM_DISPLAY_DP_HELPER=y \
    KCFLAGS="<mm-trace.h dir and other missing -I paths> -Wno-error"

# 3. Rebuild mediatek-drm against it (same recipe as iteration 1, swap kernel src):
#    make -C $K O=$K/out ... M=$V/drivers/gpu/drm/mediatek/mediatek_v2 modules \
#      DEVICE_MODULES_PATH=$V KBUILD_EXTRA_SYMBOLS=<stub symvers from new Module.symvers>
#      (reuse: /tmp/rayneo/make_config_vars.txt, linuxinclude2.txt recipe, vendor_autoconf.h)

# 4. Compare:
python3 tools/rayneo/kernel/compare/compare_modversions.py \
    tools/rayneo/kernel/stock_module/mediatek-drm.ko \
    $V/drivers/gpu/drm/mediatek/mediatek_v2/mediatek-drm.ko

# PASS: 0 mismatches → B0 PASS → B1 vendor_boot roundtrip
# FAIL: dump mismatch groups; consider fetching exact OTA source from OPPO
#       firmware payload (mediatek source announcement) — device kernel
#       commit c86e8a5e6d02 / build 2026-06-22.
