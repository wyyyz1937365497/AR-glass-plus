#!/usr/bin/env python3
"""B0 gate: compare __versions symbol CRCs between stock and rebuilt .ko.

Usage: compare_modversions.py <stock.ko> <rebuilt.ko>
__versions layout (empirically verified on OPD2407 6.1.157):
  stride 64 bytes: u32 crc @0, 4 pad, char name[56] @8
"""
import struct, subprocess, sys

def sec(ko):
    out = subprocess.run(['readelf','-S','-W',ko],capture_output=True,text=True).stdout
    for line in out.splitlines():
        if '__versions' in line:
            p = line.split(); i = p.index('__versions')
            return int(p[i+3],16), int(p[i+4],16)
    raise SystemExit(f'no __versions in {ko}')

def get_versions(ko):
    off, size = sec(ko)
    with open(ko,'rb') as f:
        f.seek(off); raw = f.read(size)
    return {raw[i+8:i+64].split(b'\0')[0].decode('ascii','ignore'):
            struct.unpack('<I',raw[i:i+4])[0]
            for i in range(0,len(raw)-63,64)
            if raw[i+8:i+64].split(b'\0')[0]}

stock, new = get_versions(sys.argv[1]), get_versions(sys.argv[2])
common = set(stock) & set(new)
real = {s for s in common if new[s] != 0xdead0000}
mismatch = sorted(s for s in real if stock[s] != new[s])
print(f"stock={len(stock)} new={len(new)} common={len(common)} "
      f"real={len(real)} match={len(real)-len(mismatch)}")
if mismatch:
    print(f"MISMATCH {len(mismatch)}:")
    for s in mismatch:
        print(f"  {s}: {stock[s]:#010x} vs {new[s]:#010x}")
    sys.exit(1)
print("*** CRC PARITY ***")
