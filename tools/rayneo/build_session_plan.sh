#!/bin/bash
# Gate 3R-K2 next-session execution plan
# This file describes the exact sequence of commands to run in a
# dedicated kernel-build session.
#
# Requires: Ubuntu with ~20GB free space, network access, sudo

set -e

# ═══════════════════════════════════════════════════════════
# Step 1: Clone matching GKI kernel source (android14-6.1)
#         Need only the base tree, no history (--depth 1)
# ═══════════════════════════════════════════════════════════
mkdir -p ~/opmt6897_build && cd ~/opmt6897_build

if [ ! -d "common" ]; then
    git clone --depth 1 \
        -b android14-6.1-2024-08_r10 \
        https://android.googlesource.com/kernel/common \
        common
fi

# ═══════════════════════════════════════════════════════════
# Step 2: Clone OnePlusOSS modules on top of GKI tree
# ═══════════════════════════════════════════════════════════
if [ ! -d "mediatek_v2_src" ]; then
    git clone --depth 1 \
        -b oneplus/mt6897_b_16.0.0_oneplus_pad \
        https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_mt6897.git \
        mediatek_v2_src
fi

# ═══════════════════════════════════════════════════════════
# Step 3: Download matching clang (r487747c per /proc/version)
#         Or use Android NDK 28.x llvm which is based on same
# ═══════════════════════════════════════════════════════════
export PATH=/home/wyyyz/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH

# Verify version match with /proc/version output:
clang --version | grep "clang version"

# ═══════════════════════════════════════════════════════════
# Step 4: Extract OPD2407 defconfig from running device OR
#         use OnePlus OSS prebuilt defconfig if available
# ═══════════════════════════════════════════════════════════
# Option A: from device (already captured in this session)
adb shell su -c 'zcat /proc/config.gz' > opd2407_defconfig.txt
cp opd2407_defconfig.txt common/.config
make O=build ARCH=arm64 olddefconfig CC=clang LLVM=1

# ═══════════════════════════════════════════════════════════
# Step 5: Build ONLY mediatek-drm.ko (much faster than full)
# ═══════════════════════════════════════════════════════════
make O=build ARCH=arm64 \
     LLVM=1 LLVM_IAS=1 \
     CC=clang LD=ld.lld AR=llvm-ar NM=llvm-nm STRIP=llvm-strip \
     OBJCOPY=llvm-objcopy OBJDUMP=llvm-objdump READELF=llvm-readelf \
     HOSTCC=clang HOSTCXX=clang++ \
     mediatek-drm.ko \
     CROSS_COMPILE=aarch64-linux-gnu-

# Verify vermagic matches:
modinfo build/../mediatek-drm.ko | grep vermagic

# ═══════════════════════════════════════════════════════════
# Step 6: If build OK, repack vendor_boot with new .ko
#         Replace /lib/modules/mediatek-drm.ko inside cpio, re-lz4,
#         rebuild ramdisk fragments using AOSP mkbootimg.py
# ═══════════════════════════════════════════════════════════
# See tools/rayneo/RAYNEO_K2_SPEC.md for full details.

echo "Build phase complete. Run fastboot to test."
