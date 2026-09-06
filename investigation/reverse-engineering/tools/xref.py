#!/usr/bin/env python3
import subprocess, re, sys
k = "mediatek-drm.ko"
out = subprocess.run(["readelf", "-SW", k], capture_output=True, text=True).stdout
secs = []
for line in out.splitlines():
    m = re.match(r"\s*\[\s*\d+\]\s+(\S+)\s+(\S+)\s+([0-9a-f]+)\s+([0-9a-f]+)\s+([0-9a-f]+)", line)
    if m and m.group(2) != "NOBITS":
        secs.append((m.group(1), int(m.group(3), 16), int(m.group(4), 16), int(m.group(5), 16)))
targets = {"Get edid from RX!": 0x28ba0b, "NTSTATE_CHECKEDID!": 0x2a57ea,
           "NTSTATE_TRAINING_PRE!": 0x28ea3e, "NTSTATE_TRAINING!": 0x29b7c0,
           "NTSTATE_DPIDLE!": 0x2b825b}
rel = open("out/mtk_relocs.txt").read().splitlines()
nm = open("out/mtk_nm.txt").read().splitlines()
addrs = [(int(l.split()[0], 16), l.split()[-1]) for l in nm if len(l.split()) >= 3]
def fn_of(a):
    best = "?"
    for ad, n in addrs:
        if ad <= a: best = n
        else: break
    return best
for name, foff in targets.items():
    for sname, addr, off, size in secs:
        if sname.startswith(".rodata") and off <= foff < off + size:
            soff = foff - off
            page, lo = soff >> 12, soff & 0xfff
            pagere = re.compile(r"ADR_PREL_PG_HI21\s+\S+" + re.escape(sname) + r"\s+\+\s*" + format(page,"x") + r"$")
            lore = re.compile(r"ADD_ABS_LO12_NC\s+\S+" + re.escape(sname) + r"\s+\+\s*" + format(lo,"x") + r"$")
            pages = []
            for l in rel:
                if pagere.search(l):
                    pages.append(int(l.split()[0], 16))
            los = []
            for l in rel:
                if lore.search(l):
                    los.append(int(l.split()[0], 16))
            # pair: adrp followed by nearest add within 8 instrs
            pairs = []
            for pa in pages:
                for la in los:
                    if 0 < la - pa <= 32:
                        pairs.append(pa)
                        break
            print(f"{name!r}: sec={sname} soff={soff:#x} adrp@{[hex(p) for p in pages]} add@{[hex(p) for p in los]}")
            for p in pairs:
                print(f"   -> function: {fn_of(p)} (ref @{p:#x})")
            break
