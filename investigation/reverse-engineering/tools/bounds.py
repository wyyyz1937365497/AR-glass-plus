#!/usr/bin/env python3
import sys
lines = [l.split() for l in open(sys.argv[1]) if len(l.split()) >= 3]
want = set(sys.argv[2:])
addrs = [(int(l[0], 16), l[-1]) for l in lines]
for i, (a, n) in enumerate(addrs):
    if n in want:
        nxt = addrs[i + 1][0] if i + 1 < len(addrs) else a
        print(f"{n} {a:x} {nxt:x}")
