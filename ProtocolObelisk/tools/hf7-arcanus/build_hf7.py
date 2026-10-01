#!/usr/bin/env python3
"""Reproducible, pinned HF6 -> HF7 single-registry selection overlay.
Usage: python3 tools/hf7-arcanus/build_hf7.py original-HF6.jar fresh-output-directory
Requires Python3 and JDK21. No network, stubs, Gradle, or live server modification.
"""
from __future__ import annotations
import hashlib, json, os, re, shutil, subprocess, sys, zipfile
from pathlib import Path
from verify_registry import verify

BASE_SHA='c43a58a634d02a2b877dfb3d01c021d7091e6e90d6a418b2deaca5dd6297c9e6'
VERSION='1.9.16-EVOLUTION-ATM10-8.2-HF7-ARCANUS-CANDIDATE'
PREFIX='br/com/atmbrasil/lobby/velocity/'
CATALOG=PREFIX+'RegistryShimCatalog.class'
HELPER=PREFIX+'Atm10Normal82ArcanusEvidence.class'
MARKER='META-INF/protocolobelisk-hf7-arcanus.json'
CONTRACT='65ddebdbecf3a09d57c2fcc3ce39099df539fdf9260181c1e31b90afdc3f9577'

def digest(data):return hashlib.sha256(data).hexdigest()

def run(argv, log):
    result=subprocess.run(argv,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=False,timeout=40)
    log.write_bytes(result.stdout)
    log.with_suffix('.command.json').write_text(json.dumps(argv,indent=2)+'\n')
    if result.returncode:
        print(result.stdout.decode(errors='replace'))
        raise RuntimeError(f'{argv[0]} failed: {result.returncode}; {log}')

def manifest(data):
    logical=[]
    for line in data.decode().replace('\r\n','\n').splitlines():
        if line.startswith(' ') and logical:logical[-1]+=line[1:]
        else:logical.append(line)
    out=[];found=False
    for line in logical:
        if line.startswith('Implementation-Version: '):line='Implementation-Version: '+VERSION;found=True
        if not line:continue
        while len(line.encode())>70:
            out.append(line[:70]);line=' '+line[70:]
        out.append(line)
    if not found:raise ValueError('Missing base implementation version')
    return ('\r\n'.join(out)+'\r\n\r\n').encode()

def main():
    if len(sys.argv)!=3:raise SystemExit(__doc__)
    base,out=map(lambda p:Path(p).resolve(),sys.argv[1:])
    if digest(base.read_bytes())!=BASE_SHA:raise SystemExit('Wrong HF6 SHA-256; refusing to patch')
    if out.exists():raise SystemExit('Output directory must not exist')
    java,javac,javap=[shutil.which(n) for n in ('java','javac','javap')]
    if not all((java,javac,javap)):raise SystemExit('JDK21 is required')
    jdk=subprocess.check_output([javac,'-version'],text=True).strip()
    if not jdk.startswith('javac 21.'):raise SystemExit('Expected javac21, got '+jdk)
    tool=Path(__file__).resolve().parent;root=tool.parents[1]
    src=root/'velocity-plugin/src/main/java'/PREFIX
    out.mkdir(parents=True)
    classes,probes,evidence=out/'classes',out/'probes',out/'evidence'
    for p in (classes,probes,evidence):p.mkdir()
    source_files=[src/'RegistryShimCatalog.java',src/'Atm10Normal82ArcanusEvidence.java']
    flags=[javac,'--release','21','-encoding','UTF-8','-g','-Xlint:all','-Werror']
    run([*flags,'-cp',str(base),'-d',str(classes),*map(str,source_files)],evidence/'compile-main.log')
    harnesses=[tool/'ArcanusRegistryRegressionHarness.java',root/'tools/hf6-create/CreateConfigRegressionHarness.java']
    run([*flags,'-cp',str(base),'-d',str(probes),*map(str,harnesses)],evidence/'compile-harness.log')
    generated={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
    if set(generated)!={CATALOG,HELPER}:raise ValueError('Unexpected generated production classes')
    marker={'version':VERSION,'base_sha256':BASE_SHA,
      'source_files_sha256':{p.relative_to(root).as_posix():digest(p.read_bytes()) for p in source_files},
      'scope':'Existing six-entry Forbidden Arcanus 2.6.1 item_modifier registry only',
      'observed_client_channel_contract_sha256':CONTRACT,'minecraft_protocol':767,
      'namespace_required':'forbidden_arcanus','packet_bytes':2747,'packet_entries':6,
      'packet_sha256':'bd73a8eb99de591e7bbcf2a16ffef24e69d17f30a6bdcbd691b4dc02c2d26db3',
      'source_commit':'f62af610d550e0d033f6c5cd166e40062638c44b',
      'attestation':'Channel fingerprint correlates observed session; does NOT attest client JARs/datapacks',
      'admission_policy_changed':False,'full_pack_evidence_granted':False,
      'full_81_catalog_reused_for_82':False,'client_mod_required':False,'paper_changed':False,'live_tested':False}
    jar=out/('ProtocolObelisk-Velocity-'+VERSION+'.jar')
    with zipfile.ZipFile(base) as zin,zipfile.ZipFile(jar,'x') as zout:
        if len(zin.namelist())!=len(set(zin.namelist())):raise ValueError('Base duplicate ZIP entries')
        if HELPER in zin.namelist() or MARKER in zin.namelist():raise ValueError('Unexpected HF7 entries in base')
        for info in zin.infolist():
            data=zin.read(info.filename)
            if info.filename==CATALOG:data=generated[CATALOG]
            elif info.filename=='velocity-plugin.json':
                obj=json.loads(data);obj['version']=VERSION;data=(json.dumps(obj,indent=2,ensure_ascii=False)+'\n').encode()
            elif info.filename=='META-INF/MANIFEST.MF':data=manifest(data)
            zout.writestr(info,data)
        for name,data in [(HELPER,generated[HELPER]),(MARKER,(json.dumps(marker,indent=2)+'\n').encode())]:
            info=zipfile.ZipInfo(name,(2026,9,28,3,45,0));info.compress_type=zipfile.ZIP_DEFLATED
            zout.writestr(info,data)
    counts={}
    for mode,runtime in [('hf6',base),('hf7',jar)]:
        ev=evidence/mode;ev.mkdir()
        cp=os.pathsep.join([str(probes),str(runtime)])
        run([java,'-Xverify:all','-cp',cp,PREFIX.replace('/','.')+'ArcanusRegistryRegressionHarness',mode,str(ev)],ev/'registry-regression.log')
        run([java,'-Xverify:all','-cp',cp,PREFIX.replace('/','.')+'CreateConfigRegressionHarness','hf6',str(ev)],ev/'config-regression.log')
        counts[mode]={}
        for kind in ('registry','config'):
            match=re.search(r'CHECKS=(\d+) RESULT=PASS',(ev/(kind+'-regression.log')).read_text())
            if not match:raise ValueError('Missing successful executable result')
            counts[mode][kind]=int(match.group(1))
        run([javap,'-private','-classpath',str(runtime),PREFIX.replace('/','.')+'RegistryShimCatalog'],ev/'catalog-abi.txt')
        (ev/'registry-semantic-verification.json').write_text(json.dumps(verify(ev/'forbidden-item-modifier.bin'),indent=2)+'\n')
    same=['catalog-abi.txt','exact-81-selection.txt','forbidden-item-modifier.bin','hf6-config-snapshot.txt',
          'hf6-observed-config-names.txt','hf6-observed-config-payloads.bin','hf6-core-config-payload.bin']
    for name in same:
        if (evidence/'hf6'/name).read_bytes()!=(evidence/'hf7'/name).read_bytes():raise ValueError('Unexpected regression '+name)
    with zipfile.ZipFile(base) as old,zipfile.ZipFile(jar) as new:
        a,b=set(old.namelist()),set(new.namelist())
        changed=sorted(n for n in a&b if old.read(n)!=new.read(n))
        if changed!=sorted([CATALOG,'velocity-plugin.json','META-INF/MANIFEST.MF']):raise ValueError('Unexpected delta '+str(changed))
        if a-b or b-a!={HELPER,MARKER}:raise ValueError('Unexpected added/removed entries')
        if len(new.namelist())!=len(b) or new.testzip():raise ValueError('Candidate integrity failure')
        for name in generated:
            if int.from_bytes(new.read(name)[6:8],'big')!=65:raise ValueError('Not Java21 bytecode')
        resource_names=[n for n in a if not n.endswith('.class') and n not in ('META-INF/MANIFEST.MF','velocity-plugin.json')]
        if any(old.read(n)!=new.read(n) for n in resource_names):raise ValueError('Existing resource mutation')
        audit={'version':VERSION,'base_sha256':BASE_SHA,'candidate_sha256':digest(jar.read_bytes()),
          'candidate_bytes':jar.stat().st_size,'changed_existing_entries':changed,
          'added_entries':sorted(b-a),'removed_entries':sorted(a-b),'unchanged_existing_entries':len(a)-len(changed),
          'unchanged_preexisting_resources':len(resource_names),'compiled_class_major':65,
          'catalog_public_private_abi_identical':True,'focused_checks':counts,
          'config_count_in_observed_fixture':21,'configs_changed_by_hf7':False,
          'selected_arcanus_registry_packets_hf6':0,'selected_arcanus_registry_packets_hf7':1,
          'selected_arcanus_entries':6,'selected_arcanus_bytes':2747,
          'six_entry_independent_nbt_semantic_verification':'PASS','live_tested':False,
          'full_junit_netty_suite_executed':False,'minecraft_neoforge_codec_executed':False,'jdk':jdk}
    (evidence/'delta-audit.json').write_text(json.dumps(audit,indent=2)+'\n')
    (out/'CHECKSUMS.sha256').write_text(f'{audit["candidate_sha256"]}  {jar.name}\n')
    print(json.dumps(audit,indent=2))

if __name__=='__main__':main()
