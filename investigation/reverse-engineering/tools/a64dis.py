#!/usr/bin/env python3
# Minimal AArch64 disassembler for targeted RE (subset decoder).
# usage: a64dis.py <elf> <start_hex> <len_hex> [--syms nm_output_file]
import struct, sys, subprocess

f = open(sys.argv[1], 'rb').read()
start = int(sys.argv[2], 16)
length = int(sys.argv[3], 16)
so_file = sys.argv[1].endswith('.so')

# ---- ELF parse: sections ----
def sections(data):
    shoff, = struct.unpack_from('<Q', data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from('<HHH', data, 0x3a)
    hdrs = []
    for i in range(shnum):
        o = shoff + i*shentsize
        name, typ, flags, addr, off, size, link, info, align, entsz = struct.unpack_from('<IIQQQQIIQQ', data, o)
        hdrs.append(dict(name=name, typ=typ, flags=flags, addr=addr, off=off, size=size, link=link, entsz=entsz))
    strtab = hdrs[shstrndx]
    def sname(n):
        e = data.index(b'\0', strtab['off']+n)
        return data[strtab['off']+n:e].decode()
    for h in hdrs: h['sname'] = sname(h['name'])
    return hdrs

secs = sections(f)
def sec_by_name(n):
    for h in secs:
        if h['sname'] == n: return h
    return None

# vaddr -> file offset (for .so use program headers; approximate via sections)
def v2o(vaddr):
    for h in secs:
        if h['typ'] != 8 and h['addr'] <= vaddr < h['addr'] + h['size'] and h['size'] > 0:
            return h['off'] + (vaddr - h['addr'])
    return None

# ---- symbols from nm ----
syms = {}
txt = subprocess.run(['nm', sys.argv[1]], capture_output=True, text=True).stdout
for line in txt.splitlines():
    parts = line.split()
    if len(parts) >= 3 and parts[0] != '':
        try: syms[int(parts[0], 16)] = parts[-1]
        except ValueError: pass
def sym_at(a):
    best = None
    for s, n in syms.items():
        if s <= a and (best is None or s > best[0]): best = (s, n)
    if best and a - best[0] < 0x400: return f"<{best[1]}+0x{a-best[0]:x}>"
    return ""

# ---- string xrefs ----
strings = {}
for h in secs:
    if h['sname'].startswith('.rodata') or h['sname'] == '.modinfo':
        blob = f[h['off']:h['off']+h['size']]
        i = 0
        while i < len(blob):
            j = blob.find(b'\0', i)
            if j < 0: break
            s = blob[i:j]
            if len(s) >= 5 and all(32 <= c < 127 or c in (9,10) for c in s):
                base = h['addr'] if h['addr'] else h['off']
                strings[(h['addr'] if so_file else h['off']) + i] = s.decode()
            i = j + 1

# relocations for .ko: addr -> (symbol, addend)
relocs = {}
rela = sec_by_name('.rela.text')
if rela:
    symtab = secs[rela['link']]
    stroff = secs[symtab['link']]['off']
    cnt = rela['size'] // 24
    for i in range(cnt):
        o = rela['off'] + i*24
        r_off, r_info, r_add = struct.unpack_from('<QQq', f, o)
        symidx = r_info >> 32
        so = symtab['off'] + symtab['entsz']*symidx
        nameoff, = struct.unpack_from('<I', f, so)
        e = f.index(b'\0', stroff+nameoff)
        sname = f[stroff+nameoff:e].decode()
        relocs[r_off] = (sname, r_add)

def str_for(val):
    # direct candidate
    if val in strings: return strings[val]
    cands = [s for a, s in strings.items() if a <= val < a+64 and strings[a].encode().count(b'\0')==0]
    for a, s in strings.items():
        if a <= val < a + len(s):
            return s[val-a:]
    return None

def sx(v, bits):
    return v - (1<<bits) if v & (1<<(bits-1)) else v

def dis(off, word):
    op = word & 0x1f
    # b / bl
    if (word >> 26) in (0b000101, 0b100101):
        imm = sx(word & 0x3ffffff, 26) << 2
        t = off + imm
        return f"{'bl' if (word>>26)&1 else 'b'} {t:x} {sym_at(t)}"
    # b.cond
    if (word & 0xff000010) == 0x54000000:
        imm = sx((word >> 5) & 0x7ffff, 19) << 2
        cond = ["eq","ne","cs","cc","mi","pl","vs","vc","hi","ls","ge","lt","gt","le","al"][(word>>12)&0xf]
        return f"b.{cond} {off+imm:x}"
    # cbz/cbnz
    if (word & 0x7e000000) == 0x34000000:
        sf = 'x' if word>>31 else 'w'
        imm = sx((word>>5)&0x7ffff, 19) << 2
        rt = word & 31
        return f"cb{'nz' if (word>>24)&1 else 'z'} {sf}{rt}, {off+imm:x}"
    # tbz/tbnz
    if (word & 0x7e000000) == 0x36000000:
        sf = 'x' if word>>31 else 'w'
        b40 = (word>>19)&0x1f; bit=(word>>31&1)<<5 | b40
        imm = sx((word>>5)&0x3fff, 14) << 2
        return f"tb{'nz' if (word>>24)&1 else 'z'} {sf}{word&31}, #{bit}, {off+imm:x}"
    # movz/movk
    if (word & 0x7f800000) in (0x52800000, 0x72800000):
        kind = 'movz' if (word & 0x7f800000)==0x52800000 else 'movk'
        sf = 'x' if word>>31 else 'w'
        hw = (word>>21)&3; imm = (word>>5)&0xffff
        return f"{kind} {sf}{word&31}, #{imm:#x}" + (f", lsl #{hw*16}" if hw else "")
    # adr/adrp
    if (word & 0x9f000000) == 0x10000000:
        immlo = (word>>29)&3; immhi = (word>>5)&0x7ffff
        imm = sx(immhi<<2 | immlo, 21)
        rd = word & 31
        if word & (1<<31):
            base = (off & ~0xfff)
            return f"adrp x{rd}, {base+ (imm<<12):#x}"
        return f"adr x{rd}, {off+imm:#x}"
    # add/sub imm
    if (word & 0x1f000000) == 0x11000000:
        sf = 'x' if word>>31 else 'w'
        opc = (word>>30)&1; sflag = (word>>29)&1
        imm12 = (word>>10)&0xfff; rn = (word>>5)&31; rd = word&31
        mn = {0:'add',1:'sub'}[opc] + ('s' if sflag else '')
        rdv = 'sp' if rd==31 else f'{sf}{rd}'
        rnv = 'sp' if rn==31 else f'{sf}{rn}'
        if mn.startswith('subs'): mn = 'cmp' if rd==31 else 'subs'
        return f"{mn} {rdv}, {rnv}, #{imm12}" if rd != 31 or not mn.startswith('cmp') else f"cmp {rnv}, #{imm12}"
    # logical imm (and/orr/eor/ands) — approximate decode
    if (word & 0x1f800000) in (0x12000000,):
        sf = 'x' if word>>31 else 'w'
        opc = (word>>29)&3
        mn = {0:'and',1:'orr',2:'eor',3:'ands'}[opc]
        n = (word>>22)&1; immr = (word>>16)&0x3f; imms = (word>>10)&0x3f
        rn = (word>>5)&31; rd = word&31
        if n == (0 if sf=='w' else 1):
            size = 32 if sf=='w' else 64
            # decode bitmask immediate
            lsb = 0
            for b in range(size):
                if not (imms>>b)&1: lsb = b+1
                else: break
            width = 0
            for b in range(size):
                if (imms>>b)&1: width = b+1
                else: break
            immval = ((1<<width)-1) << lsb
        else:
            immval = -1
        rnv = 'sp' if rn==31 else f'{sf}{rn}'
        if mn=='ands' and rd==31: return f"tst {rnv}, #{immval:#x} (N{n} immr{immr} imms{imms:#x})"
        return f"{mn} {sf}{rd}, {rnv}, #{immval:#x} (N{n} immr{immr} imms{imms:#x})"
    # ldr/str imm unsigned
    if (word & 0x3b000000) == 0x39000000:
        sf = (word>>30)&1; opc=(word>>22)&3
        size = 2<<sf
        imm12 = (word>>10)&0xfff
        rn=(word>>5)&31; rt=word&31
        load = (word>>22)&1
        ld = 'ldr' if load else 'str'
        rnvs = f"x{rn}"
        return f"{ld} {('x' if sf else 'w')}{rt}, [{rnvs}, #{imm12*(size//8 if (word>>24)&3!=2 else 1):#x}]"
    # mov reg (orr shifted)
    if (word & 0x7f800000) == 0x2a000000 or (word & 0xff800000) == 0xaa000000:
        sf = 'x' if word>>31 else 'w'
        rm=(word>>16)&31; rn=(word>>5)&31; rd=word&31
        if rn == 31:
            return f"mov {sf}{rd}, {sf}{rm}"
        return f"orr {sf}{rd}, {sf}{rn}, {sf}{rm}"
    if word == 0xd65f03c0: return "ret"
    if (word & 0xfffffc1f) == 0xd63f0000: return f"blr x{(word>>5)&31}"
    if (word & 0xfffffc1f) == 0xd61f0000: return f"br x{(word>>5)&31}"
    if (word & 0xffe00000) == 0x94000000: pass
    # stp/ldp
    if (word & 0x7fc00000) in (0x29000000, 0xa9000000, 0xa9800000, 0x29800000):
        sf = (word>>31)&1
        opc=(word>>30)&3; mode=(word>>23)&3
        rt=word&31; rn=(word>>5)&31; rt2=(word>>10)&31; imm7=sx((word>>15)&0x7f,7)
        scale = 8 if sf else 4
        mn = 'ldp' if opc in (1,3) else 'stp'
        return f"{mn} {('x' if sf else 'w')}{rt}, {('x' if sf else 'w')}{rt2}, [x{rn}, #{imm7*scale:#x}]"
    return f".word 0x{word:08x}"

# file offset for start
off_file = v2o(start) if so_file else start
print(f"; {sys.argv[1]} vaddr={start:#x} fileoff={off_file:#x} len={length:#x}")
for i in range(0, length, 4):
    va = start + i
    fo = v2o(va) if so_file else start + i
    if fo is None: break
    word, = struct.unpack_from('<I', f, fo)
    note = ""
    if va in relocs:
        sname, addend = relocs[va]
        if 'TEXT' in sname or 'TEXT' in str(addend): pass
        note = f"; reloc {sname}+{addend:#x}"
        s = None
        for h in secs:
            if h['sname'] in ('.rodata.str1.1','.rodata.str1.8','.rodata'):
                sa = h['addr'] if so_file else h['off']
                if sa <= addend < sa + h['size']:
                    fo2 = h['off'] + (addend - sa)
                    e = f.find(b'\0', fo2)
                    try: s = f[fo2:e].decode()
                    except: s = None
        if s: note += f' -> "{s[:70]}"'
    txt = dis(va, word)
    print(f"{va:8x}: {word:08x}  {txt:48s} {note}")
