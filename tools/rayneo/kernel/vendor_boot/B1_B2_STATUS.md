# K2-B1 / B2 pipeline status (2026-08-28)

## B1 no-op roundtrip: PASS
Rebuilt vendor_boot from stock fragments with mkbootimg.py using exact
unpack_bootimg --format=mkbootimg args:
  --header_version 4 --pagesize 0x1000 --base 0x0 --kernel_offset 0x40000000
  --ramdisk_offset 0x66f00000 --tags_offset 0x47c80000
  --dtb_offset 0x47c80000 --vendor_cmdline "bootopt=64S3,32N2,64N2"
  fragment0: type 1 name "" (DLKM, 43834559B LZ4)
  fragment1: type 2 name "recovery"
Rebuild metadata: header v4/size 2128, cmdline, dtb addr, total ramdisk size
58041454 — all identical; fragments+dtb md5 identical.
(rebuilt image is smaller only by trailing partition padding; fastboot pads.)

## B2 test image: BUILT, not flashed
b2_vendor_boot.img (58.8MB) = stock fragments with ONLY
lib/modules/mediatek-drm.ko replaced by tools/rayneo/kernel/build_oplus/
mediatek-drm.ko (md5 cca8670e...; LZ4 -9 recompressed ram0, other fragment
byte-identical). Verified by re-unpack: embedded .ko md5 == our build.

Location: /tmp/rayneo/roundtrip/b2_vendor_boot.img
Recovery: fastboot flash vendor_boot_a vendor_boot_a_full.img (stock copy in
tools-side rollback: /tmp/rayneo/rollback/vendor_boot_a_full.img, 64MB).

## Flash gate (needs explicit go-ahead)
fastboot flash vendor_boot_a b2_vendor_boot.img → boot → verify:
  /proc/modules mediatek_drm present, no 'Unknown symbol'/
  'disagrees about version'/'invalid module format' in dmesg;
  internal display + RayNeo 2D 1920x1080 normal.
