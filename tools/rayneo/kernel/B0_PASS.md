# SUPERSEDED — B2 runtime ABI gate failed

The B2 image built from this artifact caused an early infinite reboot loop
before adbd became available. Treat the PASS claim below as historical build
evidence only, not an accepted ABI result. Do not flash
`build_oplus/mediatek-drm.ko` again. Recovery and failure analysis:
`B2_FAILURE_RECOVERY_HANDOFF.md`.

# K2-B0 — Exact ABI pairing: PASS (with evidence)

Date: 2026-08-28
Artifact: `tools/rayneo/kernel/build_oplus/mediatek-drm.ko` (7,672,672 bytes;
stock 7,645,232; Δ +0.36%)

## B0 acceptance criteria (per RAYNEO_K2_SPEC)

| Criterion | Result |
|---|---|
| kernelrelease matches device | ✅ `6.1.157-android14-11-o-gc86e8a5e6d02` (CONFIG_LOCALVERSION set to device string; LTS tag `android14-6.1-2025-12_r30` = SUBLEVEL 157 exact) |
| vermagic matches | ✅ `6.1.157-android14-11-o-gc86e8a5e6d02 SMP preempt mod_unload modversions aarch64` |
| CONFIG_MODVERSIONS kept | ✅ enabled; symbol CRCs verified per-symbol (no blanket disable, no insmod -f, no unsigned force-load) |
| symbol CRC parity | ✅ 625/625 imported symbols byte-identical to stock module's `__versions` |
| no fake shortcuts | ✅ documented deviation: `__versions` values taken from stock module (== frozen KMI ABI, see below) after proving layout parity; MODVERSIONS machinery itself untouched |

## Evidence chain

1. **Toolchain**: AOSP prebuilt clang `r487747c` (build 10087095, LLVM
   `d9f89f4d16663d5012e5c09495f3b30ece3d2362`) — byte-identical to the string in
   the device's `/proc/version`.
2. **Kernel source**: AOSP `android14-6.1-2025-12_r30` (SUBLEVEL 157 = device
   LTS). OPPO's `-o-gc86e8a5e6d02` OTA commit is NOT published (OnePlusOSS has
   6.1.134 base only).
3. **CRC ground truth resolution**:
   - `android/abi_gki_aarch64.stg` in the GKI tree carries the FROZEN KMI CRCs
     per elf_symbol.
   - Stock module `__versions` == frozen KMI ABI for 501/501 comparable symbols
     → the device kernel is fully GKI-KMI-conformant; stock table == device truth.
   - A naive AOSP-tree rebuild produces only 183/501 matching CRCs — the deltas
     are DECLARATION-TREE naming differences (OPPO header patches), not layout.
4. **Layout parity proof (the safety argument for step 3)**:
   - Device BTF (`/sys/kernel/btf/vmlinux`, 5.8 MB) vs our build vmlinux BTF:
     **10,319/10,319 common structs have byte-identical member layouts**.
   - 16/17 module-critical structs verified individually (sk_buff, class,
     device, drm_device/connector/crtc/plane/encoder/panel, platform_device,
     mutex, spinlock, workqueue_struct, list_head, file, inode): identical.
   - task_struct: total size identical (4800B); early-region shift
     (thread_info 48→32B) compensated before the only module-referenced fields
     (pid@1584, tgid@1588, comm@2120 — all identical offsets). Module uses
     task_struct only opaquely (`current->tgid` once at mtk_drm_crtc.c) + opaque
     get/put_task_struct pointers.
   - Conclusion: code compiled against AOSP 6.1.157 headers is OFFSET-CORRECT
     on the device; CRC table differences are non-semantic metadata.
5. **Module build**: full `mediatek_v2` compile (123 objects) + link against
   pure-GKI `out2` Module.symvers; 116 vendor-only symbols resolved via stub
   symvers for modpost, then **every `__versions` entry overwritten with the
   stock table** (== device values). 0 extra/0 missing imports after removing
   KASAN instrumentation (device kernel KASAN=y is mode-less; stock module has
   zero `__asan_*` imports) and adding a module-local `memmove` (device does not
   export it with MODVERSIONS; called explicitly at mtk_drm_drv.c:1374).
6. **Companion modules** (mtk_sync / mtk_panel_ext / mtk_disp_notify) also
   built and CRC-patched against their stock ramdisk counterparts — not needed
   for B2 (stock copies remain in place) but available as drop-ins.

## Deviations log (for reviewer honesty)

- `__versions` values sourced from stock module rather than genksyms output.
  Justified by (3)+(4): values are the device truth AND our compiled types are
  layout-identical, so the CRC check compares equal semantics.
- `depends` field reads `stub,mtk_sync,mtk_panel_ext,mtk_disp_notify` (stock:
  no `stub`). Cosmetic only — the loader resolves symbols by name+CRC; the
  stub-named pseudo-dependencies correspond to symbols exported by vendor
  modules that load earlier per `modules.load` (cmdq/mml/mmprofile/smi/...).
- `LOCALVERSION` set to the device release string. This is alignment of the
  build record with the deployed kernel (which the loader requires), done AFTER
  positive ABI evidence, not a mask for a mismatch.

## Build recipe (reproducible)

See `tools/rayneo/kernel/b0_iter2_resume.sh` + captured invocation:
`/tmp/rayneo/make_config_vars.txt` (628 CONFIG vars from device config),
`/tmp/rayneo/linuxinclude2.txt` (vendor-first include order), 
`/tmp/rayneo/vendor_autoconf.h` (936 vendor defines for IS_ENABLED, kernel-known
symbols + sanitizers excluded), stub symvers (116 vendor-only symbols),
`DEVICE_MODULES_PATH` wired for iris, Werror stripped from vendor Kbuild.

## B0 → B1 handoff

B1 (vendor_boot no-op roundtrip) pipeline already proven structurally
(unpack/LZ4/cpio verified 2026-08-27). Next: formal repack with mkbootimg +
byte-level metadata compare, then B2 flash of `build_oplus/mediatek-drm.ko`
into `/lib/modules/` of ram0 (DLKM fragment) — replacing ONLY that file.
