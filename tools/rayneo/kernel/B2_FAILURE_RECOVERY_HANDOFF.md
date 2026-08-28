# Gate 3R-K2 — B2 failure and recovery handoff

Date: 2026-08-28
Priority: **restore stock `vendor_boot_a` before any further kernel work**.

## Current device state

- Device: OPPO/OnePlus OPD2407, MT6897, Android 16 / ColorOS, Magisk root.
- USB host: Arch Linux workstation `10.126.126.2`, tablet serial `JN9PYDTGUSGUPFOZ`.
- Confirmed active slot before flashing: `_a`.
- Confirmed physical partition:
  - `/dev/block/by-name/vendor_boot_a -> /dev/block/sdc41`
  - size `67,108,864` bytes.
- Bootloader state before flashing: `ro.boot.verifiedbootstate=orange`, `ro.boot.vbmeta.device_state=unlocked`.
- B2 image was written directly from rooted Android:

  ```sh
  dd if=/data/local/tmp/b2_vendor_boot.img \
     of=/dev/block/by-name/vendor_boot_a bs=4M conv=fsync
  sync
  ```

- Written bytes: `58,847,232`.
- Post-write readback SHA256 matched the B2 image exactly:
  `f9e0f822575d058879445d75128d7d5ccd91e2d36786bff345c21cb763d3b568`.
- After reboot, device entered an **infinite early reboot loop**. Wired ADB never became visible; failure is before normal adbd availability.
- User unplugged USB temporarily. Current physical state must be re-observed after reconnect.
- Long-press Volume Down + Power shows OPlus bootloader security output, then continues rebooting. Reconstructed text:

  ```text
  [fastboot_...? rsa_verify]
  buf_size = 78  sig_size = 256
  oplus rsa verify pass
  [fastboot_sec] : 0 verify pass
  Magic Not Match
  Magic Not Match
  Failed to verify cdt
  [fastboot_sec] : read_ocdt_info failed
  the serial is not match
  ```

  This is **not a usable bootloader fastboot session**. It is OPlus
  CDT/OCDT/project/serial verification failing before fastboot transport starts.

## Immediate rollback assets — persistent, not `/tmp`

On `10.126.126.2`:

```text
~/rayneo-k2/images/vendor_boot_a_full.img
SHA256 69ac81ad6c26f5431277788cbb8ba22eb2e7dd89146c41b8217ee6fee6ef224a

~/rayneo-k2/images/b2_vendor_boot.img
SHA256 f9e0f822575d058879445d75128d7d5ccd91e2d36786bff345c21cb763d3b568

~/rayneo-k2/images/SHA256SUMS
```

The stock image was verified byte-for-byte against the live `vendor_boot_a`
block partition immediately before B2 flashing:

```text
69ac81ad6c26f5431277788cbb8ba22eb2e7dd89146c41b8217ee6fee6ef224a
```

Therefore it is a valid rollback image for the exact active slot state.

## Recovery order — do not skip

### R0 — Reconnect USB and arm an ADB/recovery catcher locally on 10.126.126.2

Do not poll over SSH. Run the catcher locally in the Arch OMP/session so there
is no network-latency window.

Catch **any** ADB state (`device`, `recovery`, `sideload`), not just `device`.
If recovery adbd appears it is normally already root; normal Android needs `su`.

Normal Android/Magisk rollback:

```sh
adb push ~/rayneo-k2/images/vendor_boot_a_full.img \
  /data/local/tmp/vendor_boot_a_full.img
adb shell su -c '
  dd if=/data/local/tmp/vendor_boot_a_full.img \
     of=/dev/block/by-name/vendor_boot_a bs=4M conv=fsync
  sync
'
adb reboot
```

Recovery rollback:

```sh
adb push ~/rayneo-k2/images/vendor_boot_a_full.img \
  /data/local/tmp/vendor_boot_a_full.img
adb shell '
  dd if=/data/local/tmp/vendor_boot_a_full.img \
     of=/dev/block/by-name/vendor_boot_a bs=4M conv=fsync
  sync
'
adb reboot
```

After write, verify the full 64 MiB block before reboot where time permits:

```sh
adb shell su -c '
  dd if=/dev/block/by-name/vendor_boot_a bs=4M count=16 2>/dev/null |
  sha256sum
'
# must equal 69ac81ad6c26f5431277788cbb8ba22eb2e7dd89146c41b8217ee6fee6ef224a
```

### R1 — Physical recovery attempt

Try Volume Down + Power from a fully powered-off state. The recovery ramdisk
fragment (`vendor_ramdisk01`, type recovery) was kept byte-identical in B2.
If recovery boots, the catcher above should immediately restore stock.

The standard bootloader-fastboot route is currently blocked by OPlus OEM
verification (`Magic Not Match`, `read_ocdt_info failed`, serial mismatch), so
`adb reboot bootloader` is not a viable recovery path.

### R2 — MTK BootROM fallback

If no ADB/recovery state appears, use BootROM mode. Preparation already completed
on `10.126.126.2`:

```text
/tmp/mtkclient
Conda base at /opt/miniconda3
pyusb and mtkclient requirements installed
```

Start with a **read-only detection/handshake**. Confirm MT6897 support and dump
partition table before issuing any write. Never erase. Only write the exact
`vendor_boot_a` partition with the persistent stock image after device/chip/
partition identity is proven.

Use physical BootROM entry sequence required by OPD2407: powered off, hold the
appropriate volume key(s), then connect USB. Determine exact key sequence from
observed preloader/BROM enumeration (`0e8d:*`) before write.

## Failure classification

B2 runtime gate **FAILED**. Treat previous B0 PASS as superseded.

The failed module was accepted enough to cause an early boot failure or kernel
panic; no normal adbd window appeared. This strongly supports the risk the user
identified: post-build overwriting `__versions` can bypass loader checks while
function ABI/config/source semantics remain incompatible.

Do not classify this as an EDID issue. Do not proceed to C1/C2/C3.

Suspect build deviations, in order:

1. `__versions` was overwritten after build: 625/625 matched stock by
   construction, not naturally generated parity.
2. Exact OPPO OTA kernel source `6.1.157-android14-11-o-gc86e8a5e6d02` is not
   published; OnePlusOSS kernel base is only 6.1.134.
3. Vendor CONFIG symbols were injected manually; sanitizers and KASAN modes were
   altered to remove unexpected `__asan_*` imports.
4. A module-local `memmove` shim was added because device GKI does not export a
   MODVERSIONS CRC for `memmove` while OSS source calls it explicitly.
5. `LOCALVERSION` was aligned manually after partial ABI evidence.
6. `depends` contains pseudo-module `stub` for vendor-only symbols resolved at
   modpost with synthetic Module.symvers entries.
7. BTF layout comparisons reduced layout risk but did not prove function
   prototype/typedef/config semantic parity.

## Evidence to collect immediately after stock restoration

Before repeated boots overwrite it:

```sh
adb shell su -c 'ls -la /sys/fs/pstore; cat /sys/fs/pstore/*' 
adb pull /sys/fs/pstore ~/rayneo-k2/logs/B2/pstore
adb shell su -c 'dmesg' > ~/rayneo-k2/logs/B2/dmesg_after_restore.txt
adb shell su -c 'grep "^mediatek_drm " /proc/modules' \
  > ~/rayneo-k2/logs/B2/proc_modules_after_restore.txt
```

Also inspect OPlus ramoops/minidump paths and `/data/vendor/log/` for the failed
boot before another experiment.

## Repository status

Relevant commits:

```text
953b226  K2-C05 upstream MTK DP HPD audit
8530e10  B0 partial build pipeline / CRC comparison tool
767947f  B0 iteration-2 resume script
6054232  B0 PASS claim + built artifacts — SUPERSEDED by runtime failure
4140b86  B1 roundtrip PASS + B2 image readiness
```

`tools/rayneo/kernel/B0_PASS.md` is retained only as historical evidence and is
now explicitly marked superseded. The artifact under
`tools/rayneo/kernel/build_oplus/mediatek-drm.ko` is **unsafe; do not flash it
again**.

## Next Gate after recovery

Return to **B0**, not C1. First reproduce the exact OPPO module build naturally:
no CRC post-patching, no synthetic `stub` dependency, no local compatibility
shim. B2 must be repeated only when the rebuilt zero-patch module naturally
matches the runtime kernel contract.
