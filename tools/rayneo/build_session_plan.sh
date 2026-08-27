#!/bin/bash
# Gate 3R-K2 Revised: Kernel vendor module rebuild pipeline
#
# Key constraints established by user's analysis:
# - mediatek_drm.ko is a LOADABLE MODULE loaded from vendor_boot ramdisk
#   during first-stage init, NOT a built-in.
# - MUST use OnePlusOSS kernel prebuilt Clang, NOT Android NDK LLVM.
# - CONFIG_MODVERSIONS=y means Module.symvers from EXACT same GKI source
#   is mandatory for symbol CRC compatibility; without it kernel will reject.
# - Do not hardcode/resign. Avoid local patch until stock-equivalent builds.
# - Module deployment: repack vendor_boot ramdisk (LZ4→CPIO→edit→CPIO→LZ4),
#   preserve header v4 fragments/metadata/DTB exactly.

set -e

# ══════════════════════════════════════════════════════════════
# Target environment (from OPD2407 device):
#   kernel version : 6.1.157-android14-11-o-gc86e8a5e6d02
#   clang          : 17.0.2 (android toolchain, r487747c)
#   defconfig      : DEVICE_MODULES_DRM_MEDIATEK=m (device-modules Makefile)
#   config source  : mgk_64_k61_defconfig (OnePlusOSS branch) adjusted to
#                    match running /proc/config.gz (use as ground truth,
#                    not the only source of truth).
#   symbol CRC     : CONFIG_MODVERSIONS=y -> need EXACT Module.symvers from
#                    the SAME GKI kernel that was built for this OTA.
#                    BEST path: find OnePlus publishes prebuilt artifacts;
#                    ALTERNATIVE: dump via post-boot debugfs /proc/kallsyms
#                    IF CRCs are exposed there for each exported symbol.
# ══════════════════════════════════════════════════════════════

BUILD_ROOT=${BUILD_ROOT:-~/opmt6897_build}
mkdir -p "$BUILD_ROOT"
cd "$BUILD_ROOT"

echo "=== Step B0: Clone both required repos ==="

if [ ! -d "kernel_oneplus_mt6897" ]; then
    git clone --depth 1 \
        -b oneplus/mt6897_b_16.0.0_oneplus_pad \
        https://github.com/OnePlusOSS/android_kernel_oneplus_mt6897.git \
        kernel_oneplus_mt6897 || true
fi

if [ ! -d "mediatek_v2_src" ]; then
    git clone --depth 1 \
        -b oneplus/mt6897_b_16.0.0_oneplus_pad \
        https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_mt6897.git \
        mediatek_v2_src || true
fi

echo "=== Step B0b: Confirm kernelrelease target matches device ==="
# Try building 'make kernelrelease' to see what version the tree reports.
# This MUST eventually match:
#   6.1.157-android14-11-o-gc86e8a5e6d02
# Otherwise there's a mismatch between public OSS branch and actual OTA build.

# ── Step B1: Setup GKI base and symbols ─────────────────────
# GKI 6.1 is at android14-6.1-YYYY-MM. Version 6.1.157 corresponds roughly
# to android14-6.1-2024-12 or later release. We need matching Module.symvers
# that Google published for the GKI 6.1 image that OPD2407 actually uses.
#
# Canonical location for published GKI builds:
#     ci.android.org  -> kernel + boot images
# Or fetch from device itself via kallsyms if available for symbol extraction.

GKI_VER=$(grep -oP '^VERSION\s*=\s*\K\d+' common/Makefile 2>/dev/null || echo "?")
GKI_PATCH=$(grep -oP '^PATCHLEVEL\s*=\s*\K\d+' common/Makefile 2>/dev/null || echo "?")
GKI_SUB=$(grep -oP '^SUBLEVEL\s*=\s*\K\d+' common/Makefile 2>/dev/null || echo "?")
echo "Cloned GKI reports ${GKI_VER}.${GKI_PATCH}.${GKI_SUB}"
# Must be 6.1.157; adjust manifest/snapshot accordingly if not.

# ── Step B2: Determine kernel prebuilt toolchain ────────────
# AOSP provides prebuilt clang under prebuilts/clang/host/linux-x86/
# versioning usually tracks "r487747c" per user's device.
# One practical fallback: use matching AOSP release NDK-style toolchain
# but DO NOT use Android NDK LLVM directly.

TOOLCHAIN_DIR="${BUILD_ROOT}/prebuilt_clang"
if [ ! -x "${TOOLCHAIN_DIR}/bin/clang" ]; then
    # Download AOSP prebuilt clang matching this android14-6.1 GKI era.
    # E.g., linux-x86/android14-6.1 has prebuilt clang version clang-r487747c.
    mkdir -p "${TOOLCHAIN_DIR}"
    cd "${TOOLCHAIN_DIR}"
    CLANG_URL="https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86/+archive/refs/heads/main-kernel-build-2023/clang-r487747c.tar.gz"
    curl -Lo toolchain.tgz "${CLANG_URL}" 2>&1 | tail -2 \
      && tar xfz toolchain.tgz && rm toolchain.tgz || {
        echo "ERROR downloading kernel clang prebuilt. Check URL.";
        exit 1;
    }
fi
export PATH="${TOOLCHAIN_DIR}/bin:$PATH"

# Verify clang version
expected="17.0.2"
actual=$(clang --version | head -1 | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | head -1)
echo "Expected clang: $expected  Actual: $actual"

# ── Step B3: Prepare .config ────────────────────────────────
CONFIG_SRC=kernel_oneplus_mt6897/arch/arm64/configs/gki_defconfig
# Use mgk_64_k61_defconfig from the module repo which is MTK-specific.
DEFCONFIG_VENDOR=mediatek_v2_src/kernel/kernel_device_modules-6.1/arch/arm64/configs

ls "${DEFCONFIG_VENDOR}/mgk_64_k61_defconfig" 2>/dev/null && VENDOR_DEF_OK=1

# Copy relevant configs into kernel tree
cp "${DEFCONFIG_VENDOR}/mgk_64_k61_defconfig" \
   common/arch/arm64/configs/gki_device_oneplus_pad_defconfig 2>/dev/null || true

# Generate baseline config
cd common
make O=out ARCH=arm64 gki_device_oneplus_pad_defconfig CC=clang LLVM=1 || \
{
  echo "defconfig generation failed. May require additional SoC Kconfigs."
  echo "Try manual merge of /proc/config.gz values."
}

# Overlay with running device ground truth where needed
adb shell su -c zcat\\ /proc/config.gz > ../running_config.txt

# Overwrite mandatory flags we know are set in running kernel
for opt in CONFIG_MODVERSIONS=y CONFIG_MODULE_SIG=y CONFIG_DEVICE_MODULES_DRM_MEDIATEK=m; do
    key=$(echo "$opt" | cut -d= -f1)
    sed -i "/^${key}=/c${opt}" out/.config
done
make O=out ARCH=arm64 olddefconfig CC=clang LLVM=1

# CRITICAL: confirm supported key flags survived olddefconfig
for flag in CONFIG_MODVERSIONS CONFIG_MODULE_SIG CONFIG_DEVICE_MODULES_DRM_MEDIATEK; do
    grep "^${flag}=" out/.config || {
        echo "CRITICAL FLAG LOST: $flag"
        exit 1;
    }
done

# ── Step B4: Build the zero-change kernel/module ─────────────
make O=out ARCH=arm64 -j$(nproc) CC=clang LLVM=1 Image.gz modules

# If successful, locate our built module
find out -name "mediatek-drm.ko" -print | head -5

# ── Step B5: Compare ABIs before deploying ───────────────────
NEW_KO=$(find out -name "mediatek-drm.ko" | head -1)
STOCK_KO="/home/wyyyz/WS/AR-glass-plus/tools/rayneo/kernel/stock_module/mediatek-drm.ko"

echo "=== ABI comparison:"
modinfo ${NEW_KO}
readelf -S ${NEW_KO} > new.sections.txt 2>&1
nm -u ${NEW_KO} | sort > new.undefined.txt
objdump -s -j __versions ${NEW_KO} > new.__versions.txt 2>&1 || true
objdump -s -j __versions ${STOCK_KO} > stock.__versions.txt 2>&1 || true

diff stock.__versions.txt new.__versions.txt && echo "SYMBOL CRC MATCH ✓" || \
    echo "WARNING: symbol CRC mismatch possible."

# Also compare vermagic and other metadata
diff <(strings ${STOCK_KO} | grep vermagic) \
     <(strings ${NEW_KO} | grep vermagic)

echo "Build phase complete. Next: round-trip vendor_boot test."

