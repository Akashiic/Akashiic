#!/usr/bin/env python3
"""Stamps the HF10 OBPACK identity onto the Gradle-built Velocity JAR.

Usage: python3 package_hf10.py velocity-plugin/build/libs/ProtocolObelisk-Velocity-1.9.16-EVOLUTION.jar fresh-output-directory

Build the input first with `./gradlew check :velocity-plugin:jar` from this source tree. Like the
HF overlays, only velocity-plugin.json, the manifest Implementation-Version and a marker change;
every class and resource is copied byte for byte. It is not a live Minecraft integration test.
"""
from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path

VERSION = "1.9.16-EVOLUTION-HF10-OBPACK-CANDIDATE"
BASE_VERSION = "1.9.16-EVOLUTION"
MARKER = "META-INF/protocolobelisk-hf10-obpack.json"
FIXED_TIME = (1980, 2, 1, 0, 0, 0)
REQUIRED = {
    "br/com/atmbrasil/lobby/velocity/CompatibilityPack.class",
    "br/com/atmbrasil/lobby/velocity/CompatibilityPackSelector.class",
    "br/com/atmbrasil/lobby/velocity/Minecraft1211VanillaRegistries.class",
}
VANILLA_LIST = "vanilla-registries/minecraft-1.21.1-synchronized.tsv"
VANILLA_SEQUENCE_SHA256 = "56f0ce758b71b436b6c7c69ac671122cc252efa49c5af9ebc2efaf59ed87fa85"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def rewrite_manifest(data: bytes) -> bytes:
    logical: list[str] = []
    for line in data.decode("utf-8").replace("\r\n", "\n").splitlines():
        if line.startswith(" ") and logical:
            logical[-1] += line[1:]
        else:
            logical.append(line)
    out: list[str] = []
    seen = False
    for line in logical:
        if line.startswith("Implementation-Version: "):
            if line != "Implementation-Version: " + BASE_VERSION:
                raise ValueError("input JAR is not a 1.9.16-EVOLUTION Gradle build: " + line)
            line = "Implementation-Version: " + VERSION
            seen = True
        if not line:
            continue
        while len(line.encode()) > 70:
            out.append(line[:70])
            line = " " + line[70:]
        out.append(line)
    if not seen:
        raise ValueError("Implementation-Version missing")
    return ("\r\n".join(out) + "\r\n\r\n").encode()


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    base, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    if out.exists():
        raise SystemExit("output directory must not exist")
    with zipfile.ZipFile(base) as zin:
        names = zin.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate input ZIP entry")
        missing = REQUIRED - set(names)
        if missing:
            raise ValueError(f"input JAR lacks compatibility-pack classes: {sorted(missing)}")
        vanilla = zin.read(VANILLA_LIST).decode("utf-8")
        lines = "".join(line + "\n" for line in vanilla.splitlines() if line and not line.startswith("#"))
        if digest(lines.encode()) != VANILLA_SEQUENCE_SHA256:
            raise ValueError("vanilla registry list differs from the reviewed 1.21.1 data")
        out.mkdir(parents=True)
        jar = out / f"ProtocolObelisk-Velocity-{VERSION}.jar"
        with zipfile.ZipFile(jar, "x") as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                if info.filename == "velocity-plugin.json":
                    metadata = json.loads(data)
                    if metadata.get("version") != BASE_VERSION:
                        raise ValueError("unexpected velocity-plugin.json version")
                    metadata["version"] = VERSION
                    data = (json.dumps(metadata, indent=2, ensure_ascii=False) + "\n").encode()
                elif info.filename == "META-INF/MANIFEST.MF":
                    data = rewrite_manifest(data)
                zout.writestr(info, data)
            marker = {
                "version": VERSION,
                "base_jar_sha256": digest(base.read_bytes()),
                "scope": "Universal compatibility packs (.obpack) captured from official ServerFiles",
                "guarantee": "unit/integration tests against real ATM10 8.1 and 8.2 captures; "
                             "not a live Minecraft join test",
                "live_approved": False,
                "client_mod_required": False,
                "paper_changed": False,
            }
            info = zipfile.ZipInfo(MARKER, FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(info, (json.dumps(marker, indent=2) + "\n").encode())
    sha = digest(jar.read_bytes())
    (out / "CHECKSUMS.sha256").write_text(f"{sha}  {jar.name}\n", encoding="ascii")
    print(f"{jar} sha256 {sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
