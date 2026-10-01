#!/usr/bin/env python3
"""Pinned minimal HF8 -> HF9 ElevatorMod SERVER-config overlay.
Usage: python3 build_hf9.py path/to/original-HF8.jar fresh-output-directory
Runs focused Java 21 executable checks; it is not a live Minecraft integration test.
"""
from __future__ import annotations
import hashlib, json, os, re, shutil, subprocess, sys, zipfile
from pathlib import Path

BASE_SHA='67099ca4fddf70013e9b171fcb3760a3f9fced7f669dc7006557d93fc7b85827'
VERSION='1.9.16-EVOLUTION-ATM10-8.2-HF9-ELEVATOR-CANDIDATE'
CLASS='br/com/atmbrasil/lobby/velocity/BridgeConfig.class'
MARKER='META-INF/protocolobelisk-hf9-elevator.json'
TARGET='elevatorid-server.toml'

def digest(data: bytes)->str:
    return hashlib.sha256(data).hexdigest()

def run(argv:list[str], log:Path)->None:
    done=subprocess.run(argv,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=False,timeout=90)
    log.parent.mkdir(parents=True,exist_ok=True)
    log.write_bytes(done.stdout)
    log.with_suffix(log.suffix+'.command.json').write_text(json.dumps(argv,indent=2)+'\n')
    if done.returncode:
        print(done.stdout.decode(errors='replace'))
        raise RuntimeError(f'command failed {done.returncode}: {argv[0]} -> {log}')

def rewrite_manifest(data:bytes)->bytes:
    logical=[]
    for line in data.decode('utf-8').replace('\r\n','\n').splitlines():
        if line.startswith(' ') and logical:
            logical[-1]+=line[1:]
        else:
            logical.append(line)
    out=[]; seen=False
    for line in logical:
        if line.startswith('Implementation-Version: '):
            line='Implementation-Version: '+VERSION; seen=True
        if not line: continue
        # manifest continuation lines; ASCII values here
        while len(line.encode())>70:
            out.append(line[:70]); line=' '+line[70:]
        out.append(line)
    if not seen: raise ValueError('Implementation-Version missing')
    return ('\r\n'.join(out)+'\r\n\r\n').encode()

def split_empty_config_payloads(data:bytes):
    pos=0; out=[]
    while pos<len(data):
        start=pos
        # filenames are under 128 in this harness, so one-byte VarInt by construction
        n=data[pos]; pos+=1
        if n & 0x80: raise ValueError('unexpected multi-byte filename VarInt')
        name=data[pos:pos+n].decode(); pos+=n
        content=data[pos]; pos+=1
        if content!=0: raise ValueError('non-empty config in focused harness')
        out.append((name,data[start:pos]))
    return out

def main():
    if len(sys.argv)!=3: raise SystemExit(__doc__)
    base,out=Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve()
    root=Path(__file__).resolve().parents[2]
    if digest(base.read_bytes())!=BASE_SHA: raise SystemExit('wrong HF8 SHA256; refusing to patch')
    if out.exists(): raise SystemExit('output directory must not exist')
    javac,java,javap=map(shutil.which,['javac','java','javap'])
    if not javac or not java or not javap: raise SystemExit('JDK 21 javac/java/javap required')
    v=subprocess.check_output([javac,'-version'],text=True).strip()
    if not v.startswith('javac 21.'): raise SystemExit('javac 21 required, got '+v)
    out.mkdir(parents=True)
    classes=out/'classes'; probes=out/'probes'; evidence=out/'evidence'
    classes.mkdir(); probes.mkdir(); evidence.mkdir()
    flags=[javac,'--release','21','-encoding','UTF-8','-g','-Xlint:all','-Werror']
    source=root/'velocity-plugin/src/main/java/br/com/atmbrasil/lobby/velocity/BridgeConfig.java'
    harness=Path(__file__).parent/'ElevatorConfigRegressionHarness.java'
    arcanus=root/'tools/hf7-arcanus/ArcanusRegistryRegressionHarness.java'
    run([*flags,'-cp',str(base),'-d',str(classes),str(source)],evidence/'compile-main.log')
    run([*flags,'-cp',str(base),'-d',str(probes),str(harness),str(arcanus)],evidence/'compile-harness.log')
    generated={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
    if set(generated)!={CLASS}: raise ValueError(f'unexpected compiled classes: {sorted(generated)}')

    marker={
        'version':VERSION,
        'base_sha256':BASE_SHA,
        'source_sha256':digest(source.read_bytes()),
        'scope':'Prepare elevatorid-server.toml in profile-free lobby CONFIGURATION baseline',
        'evidence':'ElevatorMod 1.21.1-1.11.4 getOriginElevator reads General.activationRange before block lookup on jump/sneak input',
        'guarantee':'fixes the evidenced SERVER-config omission only; not full ATM10 8.2 homologation',
        'live_approved':False,'client_mod_required':False,'paper_changed':False
    }
    jar=out/('ProtocolObelisk-Velocity-'+VERSION+'.jar')
    with zipfile.ZipFile(base) as zin, zipfile.ZipFile(jar,'x') as zout:
        if len(zin.namelist())!=len(set(zin.namelist())): raise ValueError('duplicate base ZIP entry')
        for info in zin.infolist():
            data=zin.read(info.filename)
            if info.filename==CLASS: data=generated[CLASS]
            elif info.filename=='velocity-plugin.json':
                obj=json.loads(data); obj['version']=VERSION
                data=(json.dumps(obj,indent=2,ensure_ascii=False)+'\n').encode()
            elif info.filename=='META-INF/MANIFEST.MF': data=rewrite_manifest(data)
            zout.writestr(info,data)
        info=zipfile.ZipInfo(MARKER,(2026,9,28,4,0,0)); info.compress_type=zipfile.ZIP_DEFLATED
        zout.writestr(info,(json.dumps(marker,indent=2)+'\n').encode())

    # Focused config checks against base and candidate.
    modes=[('hf8',base),('hf9',jar)]
    counts={}
    for mode,runtime in modes:
        ev=evidence/mode; ev.mkdir()
        log=ev/'config-regression.log'
        run([java,'-Xverify:all','-cp',os.pathsep.join([str(probes),str(runtime)]),
             'br.com.atmbrasil.lobby.velocity.ElevatorConfigRegressionHarness',mode,str(ev)],log)
        m=re.search(r'CHECKS=(\d+) RESULT=PASS',log.read_text())
        if not m: raise ValueError('config harness missing PASS: '+mode)
        counts[mode]=int(m.group(1))
        # Run HF7 registry harness in patched mode against both JARs to prove HF8 behavior stays intact.
        reg=evidence/(mode+'-arcanus'); reg.mkdir()
        rlog=reg/'registry-regression.log'
        run([java,'-Xverify:all','-cp',os.pathsep.join([str(probes),str(runtime)]),
             'br.com.atmbrasil.lobby.velocity.ArcanusRegistryRegressionHarness','hf7',str(reg)],rlog)
        if not re.search(r'CHECKS=(\d+) RESULT=PASS',rlog.read_text()):
            raise ValueError('HF7 Arcanus regression missing PASS: '+mode)

    # ABI must not change.
    for mode,runtime in modes:
        run([javap,'-private','-classpath',str(runtime),'br.com.atmbrasil.lobby.velocity.BridgeConfig'],
            evidence/(mode+'-bridgeconfig-abi.txt'))
    if (evidence/'hf8-bridgeconfig-abi.txt').read_bytes()!=(evidence/'hf9-bridgeconfig-abi.txt').read_bytes():
        raise ValueError('BridgeConfig ABI changed')

    # Verify the observed-session payload delta is exactly one empty target config and all old bytes remain unchanged.
    old=split_empty_config_payloads((evidence/'hf8'/'hf8-incident-config-payloads.bin').read_bytes())
    new=split_empty_config_payloads((evidence/'hf9'/'hf9-incident-config-payloads.bin').read_bytes())
    oldmap=dict(old); newmap=dict(new)
    if [n for n,_ in new if n!=TARGET] != [n for n,_ in old]: raise ValueError('old config order changed')
    if set(newmap)-set(oldmap)!={TARGET}: raise ValueError('unexpected new config names')
    for n,b in old:
        if newmap[n]!=b: raise ValueError('old config payload changed: '+n)
    if len(newmap[TARGET])!=24: raise ValueError('target payload not 24 bytes')

    with zipfile.ZipFile(base) as oldzip, zipfile.ZipFile(jar) as newzip:
        a,b=set(oldzip.namelist()),set(newzip.namelist())
        changed=sorted(n for n in a&b if oldzip.read(n)!=newzip.read(n))
        allowed=sorted([CLASS,'velocity-plugin.json','META-INF/MANIFEST.MF'])
        if changed!=allowed or a-b or b-a!={MARKER}: raise ValueError('unexpected archive delta')
        if len(newzip.namelist())!=len(b): raise ValueError('duplicate output ZIP entries')
        if newzip.testzip(): raise ValueError('ZIP CRC failure')
        resources=sorted(n for n in a if n.startswith('configuration-profiles/atm10-normal-8.1-neoforge-21.1.249/') and not n.endswith('/'))
        if not all(oldzip.read(n)==newzip.read(n) for n in resources): raise ValueError('8.1 resource mutation')
        # HF7 registry classes must remain byte-identical.
        registry_classes=[n for n in a if n.startswith('br/com/atmbrasil/lobby/velocity/') and ('RegistryShimCatalog' in n or 'Atm10Normal82ArcanusEvidence' in n)]
        if not registry_classes or not all(oldzip.read(n)==newzip.read(n) for n in registry_classes):
            raise ValueError('HF7 Arcanus class mutation')
        sha=digest(jar.read_bytes())
        audit={
            'base_sha256':BASE_SHA,'candidate_sha256':sha,'candidate_bytes':jar.stat().st_size,
            'changed_existing_entries':changed,'added_entries':sorted(b-a),'removed_entries':sorted(a-b),
            'unchanged_existing_entries':len(a)-len(changed),'exact_81_resource_files':len(resources),
            'exact_81_all_byte_identical':True,'hf7_arcanus_classes_byte_identical':True,
            'private_and_public_abi_identical':True,'observed_config_count_before':len(old),
            'observed_config_count_after':len(new),'added_config_name':TARGET,
            'wire_config_payload_delta_bytes':len(newmap[TARGET]),'focused_config_checks_by_mode':counts,
            'full_junit_netty_suite_rerun':False,'live_tested':False,'jdk':v,'compiler_flags':flags[1:]
        }
    (evidence/'delta-audit.json').write_text(json.dumps(audit,indent=2)+'\n')
    (out/'CHECKSUMS.sha256').write_text(f'{sha}  {jar.name}\n')
    print(json.dumps(audit,indent=2))

if __name__=='__main__': main()
