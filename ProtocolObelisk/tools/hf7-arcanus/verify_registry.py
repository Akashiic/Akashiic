#!/usr/bin/env python3
"""Independent Minecraft registry-body/NBT decoder and six-definition semantic check.
Expected data derives from reviewed F&A 2.6.1 ModItemModifiers, not from the encoder.
This does not run Mojang's or NeoForge's runtime codecs.
"""
from __future__ import annotations
import hashlib, io, json, struct
from pathlib import Path

IDS = ['aquatic', 'demolishing', 'eternal', 'fiery', 'magnetized', 'soulbound']
COLORS = {
    'aquatic': ((90,130,243),(35,79,204)),
    'demolishing': ((111,84,80),(78,58,39)),
    'eternal': ((170,181,159),(49,57,56)),
    'fiery': ((255,143,0),(88,6,6)),
    'magnetized': ((200,201,215),(87,105,99)),
    'soulbound': ((166,185,246),(247,184,217)),
}
EXPECTED_SHA = 'bd73a8eb99de591e7bbcf2a16ffef24e69d17f30a6bdcbd691b4dc02c2d26db3'

def verify(path: Path) -> dict:
    data = path.read_bytes()
    assert len(data)==2747
    assert hashlib.sha256(data).hexdigest()==EXPECTED_SHA
    f = io.BytesIO(data)
    def read(n):
        assert 0<=n<=65536
        b=f.read(n)
        if len(b)!=n: raise ValueError('truncated registry')
        return b
    def varint():
        n=0
        for i in range(5):
            b=read(1)[0];n|=(b&127)<<(7*i)
            if b<128:
                assert i==0 or n>=1<<(7*i)
                return n
        raise ValueError('overlong VarInt')
    def mcstr(): return read(varint()).decode('utf-8')
    def utf(): return read(struct.unpack('>H',read(2))[0]).decode('utf-8')
    def nbt(t,depth=0):
        assert depth<16
        if t==3:return struct.unpack('>i',read(4))[0]
        if t==8:return utf()
        if t==9:
            typ=read(1)[0];count=struct.unpack('>i',read(4))[0];assert 0<=count<=128
            return [nbt(typ,depth+1) for _ in range(count)]
        if t==10:
            d={}
            while True:
                typ=read(1)[0]
                if typ==0:return d
                k=utf();assert k not in d
                d[k]=nbt(typ,depth+1)
        raise ValueError('unexpected NBT type '+str(t))
    registry=mcstr();assert registry=='forbidden_arcanus:item_modifier'
    count=varint();assert count==6
    entries={}
    for _ in range(count):
        id=mcstr();assert id not in entries
        assert read(1)==b'\x01', 'all entries must include data'
        assert read(1)==b'\x0a', 'anonymous root compound required'
        entries[id]=nbt(10)
    assert f.read()==b''
    assert list(entries)==['forbidden_arcanus:'+name for name in IDS]
    def argb(rgb): return (255<<24 | rgb[0]<<16 | rgb[1]<<8 | rgb[2]) - (1<<32)
    for name in IDS:
        start,end=COLORS[name]
        expected={
          'display': {
            'name':{'translate':'modifier.forbidden_arcanus.'+name},
            'texture':f'forbidden_arcanus:textures/gui/tooltip/{name}.png',
            'tooltip_color': {'start':argb(start),'end':argb(end)}},
          'incompatible_items':f'#forbidden_arcanus:modifier/{name}_incompatible',
          'incompatible_enchantments':f'#forbidden_arcanus:modifier/{name}_incompatible'}
        if name=='aquatic': expected['predicate']={'items':'#minecraft:head_armor'}
        elif name=='magnetized': expected['predicate']={'items':'#minecraft:foot_armor'}
        elif name=='soulbound': expected['predicate']={'items':'#forbidden_arcanus:modifier/soulbound_applicable'}
        elif name=='eternal':
            expected['predicate']={'predicates':{'valhelsia_core:all_of':{
                'valhelsia_core:has_component':['minecraft:max_damage','minecraft:damage']}}}
            expected['components_to_remove']=['minecraft:damage','minecraft:max_damage']
        else: expected['predicate']={'predicates':{'valhelsia_core:any_of':{
            'neoforge:item_ability':['pickaxe_dig','axe_dig','shovel_dig','hoe_dig']}}}
        assert entries['forbidden_arcanus:'+name]==expected, name
    return {'status':'PASS','packet_sha256':EXPECTED_SHA,'registry_id':registry,
            'entries':entries,'data_definitions_match_reviewed_source':True,
            'decoder':'independent Python VarInt/NBT; no ProtocolObelisk encoder imports',
            'minecraft_runtime_codec_executed':False}

if __name__=='__main__':
    import sys
    print(json.dumps(verify(Path(sys.argv[1])),indent=2))
