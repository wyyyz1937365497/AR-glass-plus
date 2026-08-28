# C0.5 — MTK DP HPD Semantic Audit (vendor vs upstream)

Status: COMPLETE (2026-08-28)
Verdict: vendor `mtk_dp.c` is missing the entire "HPD → sink re-evaluation" path that upstream
implements via the DRM connector infrastructure. Three missing pieces, one confirmed non-issue.

## Compared trees

| Tree | File | Source |
|---|---|---|
| A: OnePlus vendor | `kernel/kernel_device_modules-6.1/drivers/gpu/drm/mediatek/mediatek_v2/mtk_dp.c` | OnePlusOSS `android_kernel_modules_and_devicetree_oneplus_mt6897` branch `oneplus/mt6897_b_16.0.0_oneplus_pad` |
| B: Linux upstream | `drivers/gpu/drm/mediatek/mtk_dp.c` (2970 lines) | torvalds/linux master, 2026-08 |

## The RayNeo failure path (vendor, exact)

```
rayneo_ctl 0x06 (3D) → glasses swap EDID, assert HPD IRQ (link stays up, sink count stays 1)
  → vendor ISR: usPHY_STS |= HPD_INT_EVNET; training thread sees it
    → mdrv_DPTx_CheckSinkHPDEvent():
        - reads DPCD 0x200/0x2002/0x200C (sink count, ESI)
        - sink_cnt == 1 == cached, no branch IRQ bits
        → falls through to CheckSinkLock/CheckSinkESI
        → RETURNS. EDID NEVER RE-READ.
  → mtk_dp_handle_edid() would ALSO return cache:
        if (mtk_dp->edid) return drm_edid_duplicate(mtk_dp->edid);
  → Android HWC keeps cached 1920×1080 → glasses black
```

Double-cache problem: HPD handler ignores EDID **and** even a full state-machine restart
(NTSTATE_STARTUP → CHECKEDID) returns the stale `mtk_dp->edid` pointer because
`mtk_dp_handle_edid()` short-circuits on cache presence. Only `mdrv_DPTx_deinit()` +
physical reconnect clears it.

## Upstream design (what vendor lacks)

```
mtk_dp_hpd_event (hard IRQ): classify IRQ status
  - MTK_DP_HPD_INTERRUPT           → MTK_DP_THREAD_HPD_EVENT
  - anything else (cable state)    → MTK_DP_THREAD_CABLE_STATE_CHG + update cable_plugged_in
  → IRQ_WAKE_THREAD

mtk_dp_hpd_event_thread:
  CABLE_STATE_CHG:
    plugged:  panel poweron → mtk_dp_parse_capabilities → mtk_dp_training
    unplugged: poweroff + debounce timer
    BOTH: drm_helper_hpd_irq_event(bridge.dev)
          → DRM core reprobe → bridge get_modes → drm_edid_read_ddc(connector, aux) FRESH
  HPD_EVENT: dev_dbg only (sink IRQ; reprobe above covers EDID change case)
```

Key architectural difference: upstream does NOT cache EDID in driver state. Every
`get_modes` (bridge op) does a fresh `drm_edid_read_ddc()`. DRM core re-runs `get_modes`
on every `drm_helper_hpd_irq_event()`. The vendor tree inverted this: EDID cached at
CHECKEDID, `get_edid`/connector path reads cache, and nothing ever invalidates.

2025–2026 upstream direction confirms the semantics:
- "drm/mediatek: DP uses software IRQ; HPD handling wrong; cable_plugged_in fix"
- "drm/mediatek: Move DP training to hotplug thread" — hotplug thread owns
  capability/training refresh, separate from eDP.

## Missing pieces checklist

| Piece | Vendor has? | Where it should go (vendor tree) |
|---|---|---|
| software HPD IRQ forwarding | partial — HPD_INT_EVNET bit cleared, then ignored | `mdrv_DPTx_CheckSinkHPDEvent` |
| EDID invalidation on HPD_INT | **NO** | new: fresh `drm_get_edid` in HPD thread path |
| connector reprobe / modes refresh | **NO** | after swap: connector path re-run |
| DRM hotplug notification | **NO** (vendor only `mtk_dp_hotplug_uevent(0/1)` to HWC on deinit/connect) | after swap |
| capability refresh | NO (only via full deinit) | optional, low priority |
| link retrain | NO — but likely **not needed**: RayNeo keeps link up; HBR2 carries 297 MHz | C3 branch only |

## Backport candidates

1. **Primary (C2)**: upstream semantics ported into vendor structure —
   on HPD_INT_EVNET in `mdrv_DPTx_HPD_HandleInThread` (NOT ISR): delayed fresh
   `drm_get_edid(&mtk_dp->conn, &mtk_dp->aux.ddc)`; if valid && different →
   atomic swap `mtk_dp->edid`, `drm_connector_update_edid_property`,
   connector mode reprobe, `drm_helper_hpd_irq_event` + existing
   `mtk_dp_hotplug_uevent(1)` so vendor HWC path also fires.
2. **Debounce (C1.5)**: upstream `need_debounce` + `mod_delayed_work` pattern;
   100–300 ms initial; AUX retry ladder 100/200/400/800 ms.
3. **Rejected**: replacing vendor uevent mechanism with pure DRM helpers — vendor HWC
   integration (bUeventToHwc) must keep working for normal plug/unplug.

## Memory-order rule for C2 patch (from plan §8)

```c
old = mtk_dp->edid;
fresh = drm_get_edid(...);       /* read BEFORE touching cache */
if (!fresh) return;              /* keep cache on AUX failure */
if (edid_equal(old, fresh)) { kfree(fresh); return; }
mtk_dp->edid = fresh;            /* atomic-ish: fresh fully validated first */
/* then DRM property update + notify; free(old) after */
```

Never `kfree(cache); cache = NULL; read;` — one AUX timeout must not kill a live display.
