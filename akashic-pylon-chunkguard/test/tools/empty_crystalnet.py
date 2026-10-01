#!/usr/bin/env python3
"""Writes a crystalnet.dat whose data.crystalnet.locs list is empty (gzip NBT), to exercise the 'first registered tile
is not in the saved list' path of CrystalNetworker.load."""
import gzip, struct, sys

def s(x):
    b = x.encode('utf-8'); return struct.pack('>H', len(b)) + b

body = (b'\x0a' + s('') +
        b'\x0a' + s('data') +
        b'\x0a' + s('crystalnet') +
        b'\x09' + s('locs') + b'\x0a' + struct.pack('>i', 0) +
        b'\x00' + b'\x00' + b'\x00')
with gzip.open(sys.argv[1], 'wb') as f:
    f.write(body)
