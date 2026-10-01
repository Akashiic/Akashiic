#!/usr/bin/env python3
"""Prints the network tile locations saved in a ChromatiCraft crystalnet.dat (gzip NBT): data.crystalnet.locs."""
import gzip, struct, sys

def rd(f, n):
    b = f.read(n)
    if len(b) != n:
        raise EOFError
    return b

def payload(f, t):
    if t == 1: return struct.unpack('>b', rd(f, 1))[0]
    if t == 2: return struct.unpack('>h', rd(f, 2))[0]
    if t == 3: return struct.unpack('>i', rd(f, 4))[0]
    if t == 4: return struct.unpack('>q', rd(f, 8))[0]
    if t == 5: return struct.unpack('>f', rd(f, 4))[0]
    if t == 6: return struct.unpack('>d', rd(f, 8))[0]
    if t == 7: n = struct.unpack('>i', rd(f, 4))[0]; return rd(f, n)
    if t == 8: n = struct.unpack('>H', rd(f, 2))[0]; return rd(f, n).decode('utf-8', 'replace')
    if t == 9:
        et = rd(f, 1)[0]; n = struct.unpack('>i', rd(f, 4))[0]
        return [payload(f, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            tt = rd(f, 1)[0]
            if tt == 0: return d
            name = payload(f, 8)
            d[name] = payload(f, tt)
    if t == 11: n = struct.unpack('>i', rd(f, 4))[0]; return list(struct.unpack('>%di' % n, rd(f, 4 * n)))
    raise ValueError(t)

with gzip.open(sys.argv[1], 'rb') as f:
    t = rd(f, 1)[0]; payload(f, 8); root = payload(f, t)
locs = root['data']['crystalnet']['locs']
print(len(locs), 'locations')
for l in locs[: int(sys.argv[2]) if len(sys.argv) > 2 else 0]:
    print(l)
