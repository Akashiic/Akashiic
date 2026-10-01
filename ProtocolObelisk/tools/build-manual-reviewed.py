#!/usr/bin/env python3
"""Independent javac/JUnit build; deliberately does not claim to execute Gradle.

Requires a reviewed Java 21 JDK and bounded compile-only APIs plus real Netty
and JUnit dependencies. Outputs are disposable build evidence and deployable
JAR candidates; release validation remains the existing packager's job.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time
import zipfile


def run(command: list[str], cwd: Path, log: Path) -> None:
    log.with_suffix(".command.json").write_text(
        json.dumps({"cwd": str(cwd), "argv": command}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    print(f"RUN {command[0]} argumentCount={len(command) - 1} evidence={log}", flush=True)
    start = time.monotonic()
    with log.open("wb") as output:
        completed = subprocess.run(command, cwd=cwd, stdout=output,
                                   stderr=subprocess.STDOUT, check=False)
    print(log.read_text(encoding="utf-8", errors="replace"), flush=True)
    print(f"ELAPSED_SECONDS={time.monotonic() - start:.3f}", flush=True)
    if completed.returncode:
        raise SystemExit(f"command failed with exit={completed.returncode}; evidence={log}")


def regular_files(root: Path) -> list[Path]:
    result = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"symlink forbidden: {path}")
        if path.is_file():
            result.append(path)
    return result


def write_resources(source: Path, target: Path, version: str) -> None:
    target.mkdir()
    for path in regular_files(source):
        relative = path.relative_to(source)
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        data = path.read_bytes()
        if relative.as_posix() in ("plugin.yml", "velocity-plugin.json"):
            data = data.replace(b"${version}", version.encode("ascii"))
        destination.write_bytes(data)


def jar_candidate(root: Path, output: Path, component: str, version: str,
                  classes: Path, resources: Path) -> None:
    payloads: dict[str, bytes] = {}
    for tree in (classes, resources):
        for path in regular_files(tree):
            name = path.relative_to(tree).as_posix()
            if name in payloads:
                raise ValueError(f"JAR path collision: {name}")
            payloads[name] = path.read_bytes()
    payloads["META-INF/LICENSE.txt"] = (root / "LICENSE").read_bytes()
    manifest = (
        "Manifest-Version: 1.0\r\n"
        f"Implementation-Title: ProtocolObelisk - {component}\r\n"
        f"Implementation-Version: {version}\r\n"
        f"Automatic-Module-Name: br.com.atmbrasil.lobby.{component.lower()}\r\n\r\n"
    )
    payloads["META-INF/MANIFEST.MF"] = manifest.encode("utf-8")
    with zipfile.ZipFile(output, "x") as archive:
        for name in sorted(payloads):
            # Existing JAR validator requires this canonical Gradle-compatible
            # archive epoch; using its format does not execute Gradle.
            entry = zipfile.ZipInfo(name, (1980, 2, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (stat.S_IFREG | 0o644) << 16
            entry.compress_type = zipfile.ZIP_STORED
            archive.writestr(entry, payloads[name])
    print(f"JAR={output.name} bytes={output.stat().st_size} "
          f"sha256={hashlib.sha256(output.read_bytes()).hexdigest()}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--jdk", required=True, type=Path)
    parser.add_argument("--api-dir", required=True, type=Path)
    args = parser.parse_args()
    root, output = args.source.resolve(), args.output.absolute()
    jdk, api = args.jdk.resolve(), args.api_dir.resolve()
    if output.exists() or output.is_symlink():
        raise SystemExit("output must be a fresh dedicated directory")
    if output == root or output in root.parents or root in output.parents:
        raise SystemExit("output must be disjoint from the source tree")
    if not output.parent.is_dir():
        raise SystemExit("output parent must exist")
    properties = (root / "gradle.properties").read_text(encoding="utf-8")
    version_match = re.search(r"^protocolObeliskVersion=(\d+\.\d+\.\d+-EVOLUTION)$",
                              properties, re.MULTILINE)
    if not version_match:
        raise SystemExit("bounded release version missing from gradle.properties")
    version = version_match.group(1)
    java, javac = jdk / "bin/java", jdk / "bin/javac"
    javac_version = subprocess.check_output([str(javac), "-version"], text=True).strip()
    if not javac_version.startswith("javac 21."):
        raise SystemExit(f"Java21 required, got {javac_version}")
    for name in ("obelisk-compile-only-api.jar", "netty-common-4.1.97.Final.jar",
                 "netty-buffer-4.1.97.Final.jar", "netty-transport-4.1.97.Final.jar",
                 "netty-resolver-4.1.97.Final.jar",
                 "junit-platform-console-standalone-1.13.4.jar"):
        if not (api / name).is_file():
            raise SystemExit(f"missing build input: {api / name}")
    output.mkdir()
    jars = output / "jars"
    jars.mkdir()
    print(f"BUILD_METHOD=MANUAL_JAVAC_JUNIT GRADLE_EXECUTED=NO VERSION={version}", flush=True)
    print(f"COMPILER={javac_version} POLICY=--release21,-Xlint:all,-Werror", flush=True)
    print("LIMIT=compile-only platform descriptors do not prove exact live platform linkage", flush=True)
    digest_command = ["python3", str(root / "tools/make-evolution-release.py"),
                      "--source-sha256", str(root)]
    source_digest = subprocess.check_output(digest_command, cwd=root, text=True).strip()
    if not re.fullmatch(r"SOURCE_TREE_SHA256=[0-9a-f]{64}", source_digest):
        raise SystemExit(f"invalid source digest: {source_digest}")
    (output / "source-sha256.txt").write_text(source_digest + "\n", encoding="ascii")
    print(source_digest, flush=True)
    dependencies = os.pathsep.join(str(p) for p in sorted(api.glob("*.jar")))
    for module, component in (("velocity-plugin", "Velocity"), ("paper-plugin", "Paper")):
        module_root = root / module
        build = output / module
        build.mkdir()
        main_classes, test_classes = build / "main", build / "test"
        main_classes.mkdir()
        test_classes.mkdir()
        sources = sorted([*module_root.glob("src/main/java/**/*.java"),
                          *root.glob("common/src/main/java/**/*.java"),
                          *root.glob("necro-common/src/main/java/**/*.java")])
        common = [str(javac), "--release", "21", "-encoding", "UTF-8",
                  "-g", "-Xlint:all", "-Werror"]
        run([*common, "-classpath", dependencies, "-d", str(main_classes),
             *map(str, sources)], module_root, build / "compile-main.log")
        test_sources = sorted(module_root.glob("src/test/java/**/*.java"))
        run([*common, "-classpath", f"{main_classes}{os.pathsep}{dependencies}",
             "-d", str(test_classes), *map(str, test_sources)],
            module_root, build / "compile-tests.log")
        resources = build / "resources"
        write_resources(module_root / "src/main/resources", resources, version)
        test_resources = module_root / "src/test/resources"
        classpath = os.pathsep.join((str(test_classes), str(main_classes),
                                    str(resources), str(test_resources), dependencies))
        reports = build / "test-results"
        run([str(java), "-Xverify:all", f"-DprotocolObeliskVersion={version}",
             "-cp", classpath, "org.junit.platform.console.ConsoleLauncher", "execute",
             f"--scan-class-path={test_classes}", "--details=summary", "--disable-banner",
             "--disable-ansi-colors", "--fail-if-no-tests", f"--reports-dir={reports}"],
            module_root, build / "tests.log")
        print(f"{component.upper()}_MAIN_CLASSES={len(list(main_classes.rglob('*.class')))}", flush=True)
        print(f"{component.upper()}_TEST_CLASSES={len(list(test_classes.rglob('*.class')))}", flush=True)
        jar_candidate(root, jars / f"ProtocolObelisk-{component}-{version}.jar",
                      component, version, main_classes, resources)
    checksum_lines = []
    for path in sorted(jars.glob("*.jar")):
        checksum_lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n")
    (jars / "CHECKSUMS.sha256").write_text("".join(checksum_lines), encoding="ascii")
    run(["python3", str(root / "tools/make-evolution-release.py"),
         "--validate-jars-only", str(jars)], root, output / "jar-validation.log")
    final_digest = subprocess.check_output(digest_command, cwd=root, text=True).strip()
    if final_digest != source_digest:
        raise SystemExit(f"source changed during build: {source_digest} -> {final_digest}")
    print("MANUAL_COMPILE_AND_TEST=PASS JARS=2", flush=True)


if __name__ == "__main__":
    main()
