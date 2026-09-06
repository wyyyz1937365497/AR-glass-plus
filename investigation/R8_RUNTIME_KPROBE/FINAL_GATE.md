# R8 Runtime Kprobe Final Gate — PASS (2026-09-06)

## Outcome

`rayneo_dp_fix_v5.ko` produced a visually correct RayNeo Air 4 Pro SBS image on
the OPPO OPD2407. The user confirmed success after the kernel, DRM, and
underflow checks below. No partition was flashed and no on-device kernel file
was replaced; the fix exists only as a runtime-loaded module and its in-memory
patches.

## Final correction beyond the original handoff

The first SetMSA-hook build reached the nominal MSA but produced no image:

```text
[ 1452.395893] rayneo_v5: SBS row active for SetMSA
[ 1452.395926] [DPTX]MSA:Htt=4400 Vtt=1125 Hact=3840 Vact=1080, fps=60
```

That was not a pass. The same submission immediately entered a sustained OVL
underflow storm. Disassembly then showed that the first steering probe changed
`w22` from 3840 to 1920 to select the 1080p120 clock branch, but `w22` was later
reused for both `DP_SIZE` and `DP_BUF_RW_TIMES`. The compositor/merge path was
still 1920+1920 pixels wide while DP_INTF accepted only 1920 and SetMSA
advertised 3840.

The final module adds a kprobe at the intf-config common join (internal anchor
`+0x218`, true function offset `+0x250`). It runs only for the validated steered
call and restores:

```text
width = 3840
HSW = 22, HFP = 44, HBP = 74
```

DP_INTF operates at four pixels per clock, so those horizontal values are the
3840x1080 DTD values 88/176/296 divided by four. The selected resolution enum
remains `SINK_1920_1080_120`, preserving the required 297 MHz clock class.

## Final runtime evidence

One controlled v1 SW cycle forced the EDID reread because the second physical
toggle did not itself generate a new HPD event:

```text
[ 2156.974534] [DPTX]Get edid from RX!
[ 2157.011733] [DPTX]Link Training PASS
[ 2157.073598] [DPTX]mtk_dp_intf_config w 3840, h, 1080, clock 297000, fps 60!
[ 2157.073610] rayneo_v5: DP_INTF SBS fixup width=3840 hsw=22 hfp=44 hbp=74
[ 2157.107418] [DPTX]MSA:Htt=4400 Vtt=1125 Hact=3840 Vact=1080, fps=60
[ 2157.107510] [DPTX]DPTX calc pixel clock = 297 MHz, dp_intf clock = 74MHz
```

Only the normal transition underflows appeared at 2157.090614/2157.091019.
At uptime 2268.75 (more than 111 seconds later), neither counter had emitted
another line. In the failed build the counters had grown continuously at about
120 per second.

Stable-state checks:

```text
connector: connected
enabled:   enabled
modes:     3840x1080
module:    rayneo_dp_fix_v5 Live
visual:    PASS (user-confirmed)
```

No `warn_kprobe_rereg`, `BUG`, or `Oops` was observed in the successful run.

## Final injection chain

1. Patch `dp_plat_limit` row 2 to admit 3840x1080@60 at 297 MHz.
2. At the 3840 comparison inside `mtk_dp_intf_config`, temporarily steer into
   `SINK_1920_1080_120` to select the 297 MHz clock class.
3. At the intf-config common join, restore width 3840 and DP_INTF /4 horizontal
   timing before `DP_SIZE`, TGEN, and buffer programming.
4. Keep the video-config return row overwrite as a late, harmless fallback.
5. At `mhal_DPTx_SetMSA+0x14`, replace the 1080p120 OUTBL row with the real
   4400/3840/176/88/296/1125/1080/4/5/36/60 row before register reads.

The init/unload fix also preserves the `kp_v` registration result, treats an
MSA-probe registration failure as a warning, gates `msa_pre` by the captured
`mtk_dp` instance, tracks successful MSA registration, and unregisters it on
all applicable cleanup paths. The previously omitted unregister was the cause
of `warn_kprobe_rereg` during the crash-producing reload loop.

## Verified artifact

```text
source sha256: a68575899353b63a534cb706802443975f98bdf5f8351a7a6e561e48ed2bece1
module sha256: 889dad2a9e78888afe8d972c14b637ca7aa8635c74a288c1b29c2d891ebb32b6
size:          167360 bytes
vermagic:      6.1.128-android14-11-o-g415ded6ed906 SMP preempt mod_unload modversions aarch64
```

The build log contained only the expected `Skipping BTF generation ... due to
unavailability of vmlinux` message. Host, staged `/tmp` file, and device
`/data/local/tmp/rayneo_dp_fix_v5.ko` hashes matched.

`deploy_v5.sh` deliberately stages and verifies only. It never loads or unloads
a module. The tested module is currently loaded; its exact final-version unload
path was not exercised after visual success and remains **NOT OBSERVED**.
