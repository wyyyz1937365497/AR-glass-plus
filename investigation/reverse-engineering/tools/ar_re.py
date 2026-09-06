#!/usr/bin/env python3
# Targeted AArch64 RE: ELF(.so/.ko) + capstone + string xref + reloc annotate
import struct, sys, subprocess, re
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

class ELF:
    def __init__(self, path):
        self.path = path
        self.d = open(path, 'rb').read()
        d = self.d
        shoff, = struct.unpack_from('<Q', d, 0x28)
        shentsize, shnum, shstrndx = struct.unpack_from('<HHH', d, 0x3a)
        self.secs = []
        for i in range(shnum):
            o = shoff + i * shentsize
            vals = struct.unpack_from('<IIQQQQIIQQ', d, o)
            self.secs.append(dict(name=vals[0], typ=vals[1], flags=vals[2], addr=vals[3],
                                  off=vals[4], size=vals[5], link=vals[6], entsz=vals[9]))
        sh = self.secs[shstrndx]
        for h in self.secs:
            e = d.index(b'\0', sh['off'] + h['name'])
            h['sname'] = d[sh['off'] + h['name']:e].decode()
    def sec(self, n):
        for h in self.secs:
            if h['sname'] == n: return h
    def v2o(self, va):
        for h in self.secs:
            if h['typ'] != 8 and h['size'] and h['addr'] <= va < h['addr'] + h['size']:
                return h['off'] + (va - h['addr'])
        return None
    def symbols(self):
        out = {}
        r = subprocess.run(['nm', self.path], capture_output=True, text=True).stdout
        for line in r.splitlines():
            p = line.split()
            if len(p) >= 3:
                try: out[int(p[0], 16)] = p[-1]
                except ValueError: pass
        return out
    def strings(self):
        m = {}
        for h in self.secs:
            if h['sname'].startswith('.rodata'):
                blob = self.d[h['off']:h['off'] + h['size']]
                i = 0
                while i < len(blob):
                    j = blob.find(b'\0', i)
                    if j < 0: break
                    if j - i >= 4:
                        try:
                            s = blob[i:j].decode()
                            if all(32 <= c < 127 or c in (9, 10) for c in s):
                                m[h['addr'] + i] = s
                        except UnicodeDecodeError: pass
                    i = j + 1
        return m
    def relocs(self, secname='.text'):
        text = self.sec(secname)
        if not text: return {}
        rela = self.sec('.rela' + secname)
        if not rela: return {}
        symtab = self.secs[rela['link']]
        stoff = self.secs[symtab['link']]['off']
        m = {}
        for i in range(rela['size'] // 24):
            r_off, r_info, r_add = struct.unpack_from('<QQq', self.d, rela['off'] + i * 24)
            so = symtab['off'] + symtab['entsz'] * (r_info >> 32)
            no, = struct.unpack_from('<I', self.d, so)
            e = self.d.index(b'\0', stoff + no)
            m[r_off] = (self.d[stoff + no:e].decode(), r_add)
        return m

def sym_around(syms, a):
    best = None
    for s, n in syms.items():
        if s <= a and (best is None or s > best[0]): best = (s, n)
    if best and a - best[0] < 0x800:
        return f"<{best[1]}{'+' if a != best[0] else ''}{a - best[0]:#x}>" if a != best[0] else f"<{best[1]}>"
    return ""

def run(path, start, length, extra_syms=None, secname='.text'):
    e = ELF(path)
    syms = e.symbols()
    if extra_syms: syms.update(extra_syms)
    strs = e.strings()
    relocs = e.relocs(secname)
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    off0 = e.v2o(start)
    code = e.d[off0:off0 + length]
    regv = {}
    print(f"===== {path} @{start:#x} len={length:#x} =====")
    for ins in md.disasm(code, start):
        line = f"{ins.address:8x}: {ins.mnemonic:10s} {ins.op_str}"
        note = ""
        if ins.mnemonic == 'bl':
            try:
                t = int(ins.op_str, 16)
                note = sym_around(syms, t)
            except ValueError: pass
        elif ins.mnemonic in ('adrp', 'adr'):
            try:
                rd, imm = ins.op_str.split(', ')
                imm = int(imm, 16) if imm.startswith('0x') else int(imm, 0)
                regv[rd] = imm if ins.mnemonic == 'adr' else (imm & ~0xfff if ins.mnemonic == 'adrp' else imm)
            except Exception: pass
        elif ins.mnemonic == 'add':
            m = re.match(r'(\w+), (\w+), #(0x[0-9a-f]+|\d+)$', ins.op_str)
            if m and m.group(2) in regv:
                regv[m.group(1)] = regv[m.group(2)] + int(m.group(3), 0)
        elif ins.mnemonic in ('ldr',):
            m = re.match(r'\w+, \[(\w+)\]$', ins.op_str)
            if m and m.group(1) in regv:
                s = strs.get(regv[m.group(1)])
                if s: note = f'-> "{s[:70]}"'
        if ins.address in relocs:
            sname, addend = relocs[ins.address]
            note += f" | reloc {sname}+{addend:#x}"
            for base, s in strs.items():
                if base <= addend < base + len(s):
                    note += f' -> "{s[addend - base:][:60]}"'
        print(f"{line:60s} {note}")

if __name__ == '__main__':
    path = sys.argv[1]
    start = int(sys.argv[2], 16)
    length = int(sys.argv[3], 16)
    secname = sys.argv[4] if len(sys.argv) > 4 else '.text'
    run(path, start, length, secname=secname)
