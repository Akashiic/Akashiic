#!/usr/bin/env python3
"""Pinned, minimal HF3 -> HF4 coreconfig overlay; Java 21, no external dependencies.
Usage: python3 build_hf4.py path/to/original-HF3.jar fresh-output-directory
This runs focused executable tests, not Gradle, JUnit or a live NeoForge client.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

BASE_SHA = '353590fd581b69a17bba4bb5a0cc4dad288428762a1940d743bfdfc187e52309'
VERSION = '1.9.16-EVOLUTION-ATM10-8.2-HF4-CORECONFIG-CANDIDATE'
CLASS = 'br/com/atmbrasil/lobby/velocity/BridgeConfig.class'
MARKER = 'META-INF/protocolobelisk-hf4-coreconfig.json'

def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def run(argv: list[str], log: Path) -> None:
    done = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    log.write_bytes(done.stdout)
    log.with_suffix('.command.json').write_text(json.dumps(argv, indent=2)+'\n')
    if done.returncode:
        print(done.stdout.decode(errors='replace'))
        raise RuntimeError(f'Command failed with status {done.returncode}: {argv[0]}; {log}')

def manifest(data: bytes) -> bytes:
    logical: list[str] = []
    for line in data.decode('utf-8').replace('\r\n', '\n').splitlines():
        if line.startswith(' ') and logical:
            logical[-1] += line[1:]
        else:
            logical.append(line)
    out: list[str] = []
    seen = False
    for line in logical:
        if line.startswith('Implementation-Version: '):
            line = 'Implementation-Version: '+VERSION
            seen = True
        if not line: continue
        # All manifest values used here are ASCII. Enforce a byte bound, not chars.
        while len(line.encode('utf-8')) > 70:
            out.append(line[:70]); line = ' '+line[70:]
        out.append(line)
    if not seen: raise ValueError('base manifest lacks Implementation-Version')
    return ('\r\n'.join(out)+'\r\n\r\n').encode('utf-8')

def main() -> None:
    if len(sys.argv)!=3: raise SystemExit(__doc__)
    base, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    root = Path(__file__).resolve().parents[2]
    if digest(base.read_bytes()) != BASE_SHA: raise SystemExit('Wrong HF3 SHA256; refusing to patch')
    if out.exists(): raise SystemExit('Output directory must not exist')
    javac, java = shutil.which('javac'), shutil.which('java')
    if not javac or not java: raise SystemExit('Java21 JDK required')
    v = subprocess.check_output([javac, '-version'], text=True).strip()
    if not v.startswith('javac 21.'): raise SystemExit('javac21 required, got '+v)
    out.mkdir(parents=True)
    classes, probes, evidence = out/'classes',out/'probes',out/'evidence'
    classes.mkdir();probes.mkdir();evidence.mkdir()
    flags = [javac,'--release','21','-encoding','UTF-8','-g','-Xlint:all','-Werror']
    source = root/'velocity-plugin/src/main/java/br/com/atmbrasil/lobby/velocity/BridgeConfig.java'
    harness = Path(__file__).parent/'CoreConfigRegressionHarness.java'
    run([*flags,'-cp',str(base),'-d',str(classes),str(source)],evidence/'compile-main.log')
    run([*flags,'-cp',str(base),'-d',str(probes),str(harness)],evidence/'compile-probe.log')
    generated={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
    if set(generated)!={CLASS}: raise ValueError('Unexpected compiled class families')
    marker={'version':VERSION,'base_sha256':BASE_SHA,'source_sha256':digest(source.read_bytes()),
            'scope':'one reviewed loader SERVER filename in fallback baseline',
            'guarantee':'fixes omission, NOT proof that hidden original entity exception is resolved',
            'live_approved':False,'client_mod_required':False,'paper_changed':False}
    jar = out/('ProtocolObelisk-Velocity-'+VERSION+'.jar')
    with zipfile.ZipFile(base) as zin, zipfile.ZipFile(jar,'x') as zout:
        if len(zin.namelist())!=len(set(zin.namelist())):raise ValueError('Duplicate base ZIP entry')
        for info in zin.infolist():
            data=zin.read(info.filename)
            if info.filename==CLASS: data=generated[CLASS]
            elif info.filename=='velocity-plugin.json':
                obj=json.loads(data);obj['version']=VERSION
                data=(json.dumps(obj,indent=2,ensure_ascii=False)+'\n').encode('utf-8')
            elif info.filename=='META-INF/MANIFEST.MF': data=manifest(data)
            zout.writestr(info,data)
        info=zipfile.ZipInfo(MARKER,(2026,9,28,2,0,0));info.compress_type=zipfile.ZIP_DEFLATED
        zout.writestr(info,(json.dumps(marker,indent=2)+'\n').encode('utf-8'))
    import os
    for mode, runtime in [('hf3',base),('hf4',jar)]:
        run([java,'-Xverify:all','-cp',os.pathsep.join([str(probes),str(runtime)]),
             'br.com.atmbrasil.lobby.velocity.CoreConfigRegressionHarness',mode,str(evidence)],
            evidence/(mode+'-probe.log'))
    snapshots=[]
    for mode in ('hf3','hf4'):
        snapshots.append(dict(line.split('=',1) for line in
                              (evidence/(mode+'-config-snapshot.txt')).read_text().splitlines()))
    config_diff=[k for k in snapshots[0] if snapshots[0][k]!=snapshots[1][k]]
    if config_diff!=['transientServerConfigs']:raise ValueError(f'Unexpected default config changes: {config_diff}')
    with zipfile.ZipFile(base) as old,zipfile.ZipFile(jar) as new:
        a,b=set(old.namelist()),set(new.namelist())
        changed=sorted(n for n in a&b if old.read(n)!=new.read(n))
        allowed=sorted([CLASS,'velocity-plugin.json','META-INF/MANIFEST.MF'])
        if changed!=allowed or a-b or b-a!={MARKER}:raise ValueError('Unexpected archive delta')
        if len(new.namelist())!=len(b):raise ValueError('Duplicate output ZIP entries')
        if new.testzip():raise ValueError('ZIP CRC failure')
        resources=sorted(n for n in a if n.startswith('configuration-profiles/atm10-normal-8.1-neoforge-21.1.249/') and not n.endswith('/'))
        unchanged81=all(old.read(n)==new.read(n) for n in resources)
        if not unchanged81:raise ValueError('8.1 resource mutation')
        sha=digest(jar.read_bytes())
        audit={'base_sha256':BASE_SHA,'candidate_sha256':sha,'candidate_bytes':jar.stat().st_size,
               'changed_existing_entries':changed,'added_entries':sorted(b-a),'removed_entries':sorted(a-b),
               'unchanged_existing_entries':len(a)-len(changed),
               'exact_81_resource_files':len(resources),'exact_81_all_byte_identical':unchanged81,
               'default_config_fields_changed':config_diff,'candidate_class_major':int.from_bytes(new.read(CLASS)[6:8],'big'),
               'focused_checks_hf3':50,'focused_checks_hf4':50,'full_junit_netty_suite_rerun':False,
               'live_tested':False,'jdk':v,'compiler_flags':flags[1:]}
    (evidence/'delta-audit.json').write_text(json.dumps(audit,indent=2)+'\n')
    (out/'CHECKSUMS.sha256').write_text(f'{sha}  {jar.name}\n')
    print(json.dumps(audit,indent=2))

if __name__=='__main__':main()
