# RayNeo mode command used by this module

The 64-byte endpoint payload is independently reconstructed from
`libFFalconXRServer.so` in the locally retained reference APK; no code or
library from that APK is shipped.

`XRUsbController::SendCommand` zeroes 64 bytes and writes `0x66` at byte 0.
The `SendHidCommand` callback then writes the command at byte 1, the parameter
at byte 2, and optional payload beginning at byte 3. The observed JNI paths
call it as:

- 3D: command `0x06`, parameter `0x00`, empty payload.
- 2D: command `0x07`, parameter `0x00`, empty payload.

The Linux hidraw API requires an extra leading report-ID byte. RayNeo's HID
report is unnumbered, so the module writes a 65-byte userspace buffer beginning
with `0x00`; the following 64 bytes are exactly the endpoint payload above.

Static command reconstruction is verified. End-to-end hidraw transmission on
OPD2407 is a device gate and must not be called verified until exercised with
the glasses connected.
