# AR-glass-plus RayNeo SBS SukiSU Ultra module

This is a device- and firmware-pinned KernelSU/SukiSU Ultra module for:

- OPPO Pad OPD2407 (`OP615AL1`)
- kernel `6.1.128-android14-11-o-g415ded6ed906`
- ColorOS build `V.2158010-2`
- RayNeo Air 4 Pro (`1bbb:af50`)

It is intentionally inert at boot. The companion Android app calls
`bin/ar-glass-dpctl acquire` when selecting an SBS render mode and `release`
when returning to 2D, when the glasses are unplugged, or when the control app
moves to the background. A root watchdog releases the module if the app
process dies before Android lifecycle cleanup can run.

The v6 kernel module retains the visually verified v5 injection chain and adds
a reversible whitelist restore on unload. No partition or stock kernel module
is written or replaced.

Build the installable ZIP from the repository root:

```bash
./tools/build-sukisu-module.sh
```

Install the generated ZIP in SukiSU Ultra Manager, reboot once so `service.sh`
starts the watchdog, then deploy the matching app. The display fix itself is
still loaded only on demand; it is never loaded by a boot script.
