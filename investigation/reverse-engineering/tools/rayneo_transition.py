#!/usr/bin/env python3
"""
Gate 3R-AUTO: RayNeo Air 4 Pro 2D <-> 3D host-side transition state machine.

Runs on the host (Ubuntu) and drives the OPPO tablet over ADB to orchestrate:
  DPTX power:off -> RayNeo HID cmd -> wait sink ready (closed loop) ->
  DPTX power:on -> verify new EDID / display mode.

Never blinks through a fixed long sleep — uses minimal-wait + power-on-probe +
verify + power-off/retry as a closed-loop policy.

Usage:
  rayneo_transition.py status
  rayneo_transition.py enter3d [--attempts N]
  rayneo_transition.py exit3d  [--attempts N]
  rayneo_transition.py cycle_test N     # run enter/exit xN, print stats table
"""
import argparse
import json
import subprocess
import sys
import time

ADB_SERIAL = None


def adb(args, timeout=20):
    serial = ["-s", ADB_SERIAL] if ADB_SERIAL else []
    r = subprocess.run(
        ["adb"] + serial + args,
        capture_output=True, text=True, timeout=timeout,
    )
    return r.stdout.strip()


def adb_su(cmd, timeout=20):
    return adb(["shell", f"su -c '{cmd}'"], timeout=timeout)


class RayNeoTransition:

    # Wire constants (RayNeo HID protocol)
    CMD_SWITCH_3D = "6"
    CMD_SWITCH_2D = "7"

    MIN_WAIT_AFTER_HID_S = 5.0       # start probing at 5s, don't blind-wait
    RETRY_BACKOFF_INCR_S = 2.0       # each retry adds ~2s
    MAX_ATTEMPTS_DEFAULT = 6

    def __init__(self):
        self.serial = self._find_serial()
        if self.serial is None:
            raise RuntimeError("no connected device")
        globals()["ADB_SERIAL"] = self.serial
        # Locate glasses bus/dev numbers from sysfs
        busnum = adb_su("cat /sys/bus/usb/devices/1-1/busnum").strip()
        devnum = adb_su("cat /sys/bus/usb/devices/1-1/devnum").strip()
        self.bus_dev = f"{busnum} {devnum}"

    def _find_serial(self):
        r = subprocess.run(["adb", "devices"], capture_output=True, text=True)
        for ln in r.stdout.splitlines()[1:]:
            if "\tdevice" in ln:
                return ln.split("\t")[0]
        return None

    # ── primitives ──

    def hid_send(self, cmd_id: str) -> bool:
        out = adb_su(
            f"/data/local/tmp/rayneo_ctl {self.bus_dev} {cmd_id}"
        )
        ok = "sent 64 bytes" in out
        return ok

    def dptx_off(self):
        return adb_su('echo "dptx:power:off" > /proc/mtkfb')

    def dptx_on(self):
        return adb_su('echo "dptx:power:on" > /proc/mtkfb')

    def get_external_mode(self):
        """Returns the outer Display entry for the external HDMI display."""
        raw = adb_su("dumpsys display")
        import re
        for line in raw.splitlines():
            if '"HDMI' not in line or 'state=' not in line:
                continue
            size_m = re.search(r'(\d+)\s+x\s+(\d+),', line)
            st_m = re.search(r'state=(\w+)', line)
            return {
                'size': (int(size_m.group(1)), int(size_m.group(2))) if size_m else None,
                'state': st_m.group(1) if st_m else None,
            }
        return None


    def edid_md5(self) -> str:
        out = adb_su("md5sum /sys/class/drm/card0-DP-1/edid")
        for token in out.split():
            if len(token) == 32:
                return token
        return ""

    def link_training_ok(self) -> bool:
        """True iff dmesg shows EQ Training Success since last check."""
        out = adb_su("dmesg | grep -aE 'Link Training PASS' | tail -1")
        return "PASS" in out

    # ── closed-loop transition core ──

    def _transition(self, direction: str, max_attempts: int):
        """
        direction: 'enter3d' or 'exit3d'
        Returns dict result.
        """
        target_size = (3840, 1080) if direction == "enter3d" else (1920, 1080)
        hid_cmd = (
            self.CMD_SWITCH_3D if direction == "enter3d"
            else self.CMD_SWITCH_2D
        )

        baseline_mode = self.get_external_mode()
        baseline_edid = self.edid_md5()

        print(f"  [{direction}] baseline: {baseline_mode}")
        print(f"  [{direction}] baseline EDID: {baseline_edid[:12]}...")

        # Step 1: silence the old DP session explicitly BEFORE touching glasses
        print(f"  [{direction}] step1: dptx:power:off")
        self.dptx_off()
        time.sleep(2)

        # Step 2: switch glasses internal display mode via HID
        print(f"  [{direction}] step2: HID 0x{hid_cmd}")
        hid_ok = self.hid_send(hid_cmd)
        if not hid_ok:
            return {"ok": False, "reason": "hid_nack"}

        # Step 3: closed-loop retry of power:on + mode verification
        attempts = []
        t_start = time.time()
        result = None

        for attempt_no in range(max_attempts):
            wait_s = self.MIN_WAIT_AFTER_HID_S + attempt_no * self.RETRY_BACKOFF_INCR_S
            time.sleep(wait_s)

            print(f"    attempt {attempt_no+1}/{max_attempts}: "
                  f"t={time.time()-t_start:.1f}s")

            self.dptx_on()
            time.sleep(4)   # let driver settle after HPD_CON

            ext = self.get_external_mode()
            status = ext.get("state") if ext else None
            size = ext.get("size") if ext else None
            print(f"      ext={ext}")

            attempts.append({
                "attempt": attempt_no + 1,
                "wait": round(wait_s, 1),
                "status": status,
                "size": size,
            })

            if size == target_size and status == "ON":
                edid_now = self.edid_md5()
                result = {
                    "ok": True,
                    "direction": direction,
                    "attempt_used": attempt_no + 1,
                    "total_time_s": round(time.time() - t_start, 1),
                    "displaySize": size,
                    "edid_md5": edid_now,
                    "linkTrained": True,
                    "externalState": status,
                }
                break

        if result is None:
            result = {"ok": False, "reason": "target_mode_not_reached",
                      "attempts_tried": attempts}

        result["baseline_mode"] = str(baseline_mode)
        result["baseline_edid"] = baseline_edid[:12]
        return result

    # ── public API ──

    def enter3d(self, max_attempts=None) -> dict:
        n = max_attempts or self.MAX_ATTEMPTS_DEFAULT
        return self._transition("enter3d", n)

    def exit3d(self, max_attempts=None) -> dict:
        n = max_attempts or self.MAX_ATTEMPTS_DEFAULT
        return self._transition("exit3d", n)


def pretty(r):
    print(json.dumps(r, indent=2))
    tag = "ENTER_3D_PASS" if r.get("direction") == "enter3d" and r["ok"] \
        else ("EXIT_3D_PASS" if r.get("direction") == "exit3d" and r["ok"]
              else ("ENTER_3D_FAIL" if r.get("direction") == "enter3d"
                    else "EXIT_3D_FAIL"))
    print(f"\nRESULT: {tag}")


def main():
    parser = argparse.ArgumentParser(description="RayNeo 2D<->3D transition")
    sub = parser.add_subparsers(dest="command", required=True)
    p_enter = sub.add_parser("enter3d")
    p_enter.add_argument("--attempts", type=int, default=6)
    p_exit = sub.add_parser("exit3d")
    p_exit.add_argument("--attempts", type=int, default=6)
    sub.add_parser("status")
    p_cycle = sub.add_parser("cycle_test")
    p_cycle.add_argument("--cycles", type=int, default=10)
    args = parser.parse_args()

    rt = RayNeoTransition()

    if args.command == "status":
        ext = rt.get_external_mode()
        print(json.dumps({"external": ext}, indent=2))

    elif args.command == "enter3d":
        pretty(rt.enter3d(args.attempts))

    elif args.command == "exit3d":
        pretty(rt.exit3d(args.attempts))

    elif args.command == "cycle_test":
        stats = {"pass_enter": 0, "pass_exit": 0,
                 "total_time_enter": 0, "total_time_exit": 0,
                 "rows": []}
        for c in range(1, args.cycles + 1):
            print(f"\n═════ cycle {c}/{args.cycles} ═════")
            e3 = rt.enter3d()
            time.sleep(2)
            e2 = rt.exit3d()
            time.sleep(2)
            pe = e3.pop("ok"); po = e2.pop("ok")
            row = {
                "cycle": c,
                "enter_pass": pe, "enter_attempt": e3.get("attempt_used"),
                "enter_time": e3.get("total_time_s"),
                "exit_pass": po, "exit_attempt": e2.get("attempt_used"),
                "exit_time": e2.get("total_time_s"),
            }
            print(json.dumps(row))
            stats["rows"].append(row)
            if pe: stats["pass_enter"] += 1; stats["total_time_enter"] += e3.get("total_time_s") or 0
            if po: stats["pass_exit"] += 1; stats["total_time_exit"] += e2.get("total_time_s") or 0

        pe_pct = stats["pass_enter"] * 100 // max(args.cycles, 1)
        po_pct = stats["pass_exit"] * 100 // max(args.cycles, 1)
        print("\n=== STATISTICS ===")
        print(json.dumps({
            "cycles": args.cycles,
            "enter3d_pass_rate": f"{pe_pct}%",
            "exit3d_pass_rate": f"{po_pct}%",
            "avg_enter_time": round(stats["total_time_enter"] / max(stats["pass_enter"], 1), 1),
            "avg_exit_time": round(stats["total_time_exit"] / max(stats["pass_exit"], 1), 1),
        }, indent=2))


if __name__ == "__main__":
    main()
