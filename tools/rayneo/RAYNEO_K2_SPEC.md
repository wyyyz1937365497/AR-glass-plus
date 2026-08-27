# Gate 3R-K2 — MTK DPTX Dynamic EDID Hot-Change Fix

## Technical Specification

### Status: Deferred (built-in → needs full kernel recompile)

### Blocker identified: `mediatek_drm` is BUILT-IN to boot.img

Not vendor_dlkm `.ko`. Cannot use simple module replacement.
Must re-clone OOS kernel source and produce custom boot.img.

---

## Root Cause (confirmed)

RayNeo Air 4 Pro HID cmd `0x06` triggers internal 3D mode switch.
Glasses then assert DP HPD change (no disconnect).
MTK DPTX driver's HPD_INT path calls `mdrv_DPTx_CheckSinkHPDEvent()`
which only checks DPCD sink_count — NOT EDID content.
Since sink_count didn't change (still one display), no action taken.
Stale cached EDID (1920×1080) persists indefinitely.

## Golden Reference (Arch Linux)

| State | EDID SHA256 | Modes |
|---|---|---|
| 2D | a357503f | 1920x1080 x3 |
| 3D | ab5655d5 | 3840x1080 |

HPD behavior on Linux: single DRM `change` uevent (no add/remove).
Connector stays connected throughout transition.

## Existing Driver Infrastructure (from OnePlusOSS mtk_dp.c)

```
/proc/mtkfb handler (mtk_dp_debug.c):
  dptx:setpowermode     = SWInterruptSet(2) -> delay(100) -> SWInterruptSet(4)
  dptx:power:on/off     = mtk_dp_power_save(1/0)
  dptx:debug_log:on/off = mtk_dp_debug_enable(bool)
  dptx:fakecablein:*    = force cable detection test

Key state machine states:
  STARTUP → CHECKCAP → CHECKEDID → TRAINING_PRE → TRAINING → CHECKTIMING → NORMAL

EDID cache location:
  mtk_dp->edid   (struct edid *, freed only on deinit)
  
EDID read function:
  mtk_dp_handle_edid()    returns drm_edid_duplicate if cached,
                          else calls drm_get_edid(connector, aux)
                          ← CRITICAL BUG: never reads fresh unless edid==NULL

HPD_INT handler:
  mdrv_DPTx_CheckSinkHPDEvent()  checks sink_count + ESI flags
                                  does NOT invalidate edid cache
                                  
get_modes callback:
  mtk_dp_conn_get_modes()        calls drm_add_edid_modes(mtk_dp->edid)
                                 ← returns stale modes from old edid

Driver instance pattern:
  g_mtk_dp global singleton pointer
  
Work queue:
  mtk_dp->dptx_work  queued by SWInterruptSet / external trigger
```

## Required Patch Design

### Step 1: Instrumentation only (kernel patch)
```c
// In mdrv_DPTx_CheckSinkHPDEvent(), after reading ubDPCD20x,
// before checking branch/sink_count:

struct edid *fresh;
unsigned char fresh_checksum;
int fresh_pixel_clock;

// Read fresh EDID over AUX without touching mtk_dp->edid
{
    struct edid *tmp = drm_get_edid(&mtk_dp->conn, &mtk_dp->aux.ddc);
    if (!tmp) {
        DPTXERR("force_reprobe: failed to read fresh EDID\n");
    } else {
        // Calculate metrics for comparison
        unsigned char old_checksum = (mtk_dp->edid) ? mtk_dp->edid->checksum : 0;
        fresh_checksum = tmp->checksum;
        
        // Print diagnostic comparison
        DPTXMSG("[R3RK2] cached checksum=%02x fresh=%02x", 
                old_checksum, fresh_checksum);
        DPTXMSG("[R3RK2] EDID_CHANGED=%s",
                old_checksum != fresh_checksum ? "YES" : "NO");
        // If different, store mode info
        kfree(tmp);  // For instrumentation phase: don't swap
    }
}
```

### Step 2: Minimal hot-refresh (kernel patch after step 1 verified)
```c
// After confirming fresh != cached, do the hot refresh:
struct edid *old_edid = mtk_dp->edid;
mtk_dp->edid = tmp;  // Replace cache

drm_connector_update_edid_property(&mtk_dp->conn, mtk_dp->edid);
drm_connector_set_link_status_property(
    &mtk_dp->conn, DRM_LINK_STATUS_GOOD);
kfree(old_edid);
```

### Key constraint
MUST use mutex/delayed_work if concurrent access to edid is possible.
AUX read is process-context only (never ISR).

---

## Build Requirements

Must build the complete GKI kernel because MTK DP is built-in.
Recovery artifacts already saved to `/tmp/rayneo/rollback/`.

Toolchain requirement:
- Android clang version matching `r487747c`
- Architecture: arm64 (aarch64)
- Defconfig: OPD2407 stock defconfig must be extracted from running device

Build output:
- New `Image.gz` inside boot.img patch (Magisk can auto-handle if srcboot same size)
- Or full boot.img replacement using magiskboot repack

Deployment: via fastboot boot (temporary test first) or Magisk overlay
(after PASSED verification).

## Debug Test Verification Checklist

Before firmware upgrade: glasses should power cycle successfully (unplug/replug).

Golden Reference expected EDID hashes:
- 2D EDID md5: `70f6f0bc8b48d61b6baccebb03aa2c52` (= Arch `a357503f` raw bytes)
  Note: tablet-side kernel md5 differs from arch because of slight parser difference,
  but edid-decode output matches conceptually.
- 3D EDID sha256: `ab5655d596b492f608c72c3f58833353d21d6bf4f7207188bfb8a9c557d2663d`
- 3D preferred mode: 3840x1080@60Hz, pixel clock 297 MHz, htotal=4400

Tools/rayneo/ artifacts committed in this repository are evidence of work done.
