#!/usr/bin/env python3
"""Build and audit the deterministic, lobby-first ProtocolObelisk release.

The 1.9.16 distribution has exactly two executable components: Velocity and
Paper. Standalone Youer and Crucible companion components may remain in the
development tree as historical material, but neither their binaries nor their
operational guides/examples are eligible for the source snapshot or the
extracted release. The pre-existing shared NecroTempus transport inside the two
plugins is not a standalone Crucible publication.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tempfile
import unicodedata
import zipfile


VERSION = "1.9.16-EVOLUTION"
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
GRADLE_FIXED_TIMESTAMP = (1980, 2, 1, 0, 0, 0)
SOURCE_NAME = f"ProtocolObelisk-{VERSION}-source.zip"
RELEASE_NAME = f"ProtocolObelisk-{VERSION}-release.zip"
RELEASE_CHECKSUM_NAME = f"{RELEASE_NAME}.sha256"
JAR_NAMES = (
    f"ProtocolObelisk-Paper-{VERSION}.jar",
    f"ProtocolObelisk-Velocity-{VERSION}.jar",
)

RELEASE_DOCS = (
    "ATM10-NORMAL-FOUNDATION.md",
    "CARDINAL-POLICY.md",
    "CHANGELOG-1.9.16.md",
    "DEPLOY-EVOLUTION-1.9.16-ATM10-8.1.md",
    "EVOLUTION-1.9.16.md",
    "LICENSE",
    "ProtocolObelisk-1.9.16-EVOLUTION-BUILD-AUDIT.md",
    "README-1.9.16.md",
)
RELEASE_INTEGRATIONS = (
    "integrations/ProMenus/LEIA-ME.md",
    "integrations/ProMenus/menus/LOBBIES/LOBBY_ATM_BRASIL.yml",
    "integrations/ProMenus/menus/LOBBIES/LOBBY_FORBIDDEN.yml",
)
RELEASE_EVIDENCE = (
    "build-evidence/1.9.16-EVOLUTION/ATM10-8.1-RUNTIME-EXPORT-AUDIT.md",
    "build-evidence/1.9.16-EVOLUTION/FORENSIC-LOG-AUDIT.md",
    "build-evidence/1.9.16-EVOLUTION/FUNCTIONAL-HARNESSES.txt",
    "build-evidence/1.9.16-EVOLUTION/OFFLINE-COMPILE.txt",
    "build-evidence/1.9.16-EVOLUTION/OFFLINE-TEST-RUNTIME.txt",
    "build-evidence/1.9.16-EVOLUTION/RUNTIME-EXPORT-SHA256.txt",
)
RELEASE_PAYLOAD_NAMES = frozenset((*JAR_NAMES, SOURCE_NAME, *RELEASE_DOCS,
                                   *RELEASE_EVIDENCE, *RELEASE_INTEGRATIONS, "CHECKSUMS.sha256"))
PUBLICATION_NAMES = frozenset((*JAR_NAMES, SOURCE_NAME, RELEASE_NAME,
                               RELEASE_CHECKSUM_NAME, "CHECKSUMS.sha256"))

SOURCE_ROOT_FILES = frozenset((
    *RELEASE_DOCS,
    *RELEASE_INTEGRATIONS,
    "build.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "settings.gradle.kts",
))
SOURCE_ROOTS = frozenset((
    "common",
    "examples",
    "gradle",
    "necro-common",
    "paper-plugin",
    "tools",
    "velocity-plugin",
))
SOURCE_EXAMPLES = frozenset((
    "examples/paper-config.yml",
    "examples/paper-legacy-forwarding.fragment.yml",
    "examples/paper-modern-forwarding.fragment.yml",
    "examples/server.properties.fragment",
    "examples/spigot.yml.fragment",
    "examples/velocity-modern-forwarding.fragment.toml",
    "examples/velocity-start-command-backend-decode-diagnostic.txt",
    "examples/velocity-start-command.txt",
    "examples/velocity.compatibility.toml",
))
EXCLUDED_PARTS = frozenset((
    ".git",
    ".gradle",
    ".idea",
    "__pycache__",
    "build",
    "dist",
    "logs",
    "offline-api",
))
EXCLUDED_SUFFIXES = (".iml", ".log", ".pyc", ".tmp", "~")
EXCLUDED_SOURCE_PREFIXES = (
    "tools/evolution-1.9.11/",
    "crucible-companion/",
    "youer-agent/",
)
EXCLUDED_SOURCE_EXACT = frozenset((
    "examples/bridge-lobby-only.properties",
    "examples/bridge.properties",
    "tools/build-paper-offline.sh",
    "tools/build-velocity-offline.sh",
    "tools/build-youer-offline.sh",
    "tools/evolution-overlay-policy.json",
    "tools/package-evolution-overlay.py",
    "tools/patch-class-utf8.py",
    "tools/test-evolution-tooling.py",
))
ALLOWED_SOURCE_JARS = frozenset((
    "gradle/wrapper/gradle-wrapper.jar",
))
SENSITIVE_SOURCE_NAMES = frozenset((
    ".env",
    "id_dsa",
    "id_ed25519",
    "id_ecdsa",
    "id_rsa",
))
SENSITIVE_SOURCE_SUFFIXES = (
    ".jks",
    ".key",
    ".keystore",
    ".p12",
    ".pem",
    ".pfx",
)

COMMON_FORBIDDEN_JAR_PREFIXES = (
    "META-INF/versions/",
    "com/google/inject/",
    "com/mohistmc/",
    "com/velocitypowered/",
    "cpw/mods/",
    "io/netty/",
    "net/kyori/",
    "net/minecraft/",
    "net/neoforged/",
    "org/bukkit/",
    "org/slf4j/",
    "br/com/atmbrasil/lobby/youer/",
    "br/com/atmbrasil/protocolobelisk/necro/crucible/",
)
FORBIDDEN_OPERATIONAL_ENTRIES = (
    "br/com/atmbrasil/lobby/common/YouerAgentProtocol",
    "br/com/atmbrasil/lobby/velocity/YouerAgentControl",
    "br/com/atmbrasil/lobby/velocity/YouerAgentManifestRegistry",
    "META-INF/neoforge.mods.toml",
)
JAR_POLICIES: dict[str, dict[str, object]] = {
    f"ProtocolObelisk-Paper-{VERSION}.jar": {
        "title": "ProtocolObelisk - Paper",
        "module": "br.com.atmbrasil.lobby.paper",
        "required": (
            "br/com/atmbrasil/lobby/paper/Atm10LobbyPaperPlugin.class",
            "br/com/atmbrasil/lobby/paper/PaperNecroTempusBridge.class",
            "plugin.yml",
        ),
        "forbidden": COMMON_FORBIDDEN_JAR_PREFIXES + ("com/akashic/",),
    },
    f"ProtocolObelisk-Velocity-{VERSION}.jar": {
        "title": "ProtocolObelisk - Velocity",
        "module": "br.com.atmbrasil.lobby.velocity",
        "required": (
            "br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.class",
            "velocity-plugin.json",
        ),
        "forbidden": COMMON_FORBIDDEN_JAR_PREFIXES
        + ("com/akashic/", "me/neznamy/tab/", "org/bukkit/"),
    },
}

MAX_ARCHIVE_ENTRIES = 200_000
MAX_ENTRY_SIZE = 768 * 1024 * 1024
MAX_UNCOMPRESSED_SIZE = 4 * 1024 * 1024 * 1024
MAX_REGULAR_INPUT_SIZE = 2 * 1024 * 1024 * 1024


class ReleaseError(ValueError):
    """An unsafe, incomplete, or non-canonical release input."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--validate-jars-only", metavar="DIRECTORY", type=Path,
                        help="validate the exact Paper/Velocity JAR input tree")
    parser.add_argument("--validate-publication", metavar="DIRECTORY", type=Path,
                        help="validate an already generated publication directory")
    parser.add_argument("--source-sha256", metavar="SOURCE_ROOT", type=Path,
                        help="print the canonical lobby-first source-tree digest")
    parser.add_argument("source_root", nargs="?", type=Path)
    parser.add_argument("jars_directory", nargs="?", type=Path)
    parser.add_argument("output_directory", nargs="?", type=Path)
    args = parser.parse_args()
    validations = (args.validate_jars_only, args.validate_publication, args.source_sha256)
    positional = (args.source_root, args.jars_directory, args.output_directory)
    if sum(value is not None for value in validations) > 1:
        parser.error("validation modes are mutually exclusive")
    if any(value is not None for value in validations):
        if any(value is not None for value in positional):
            parser.error("validation modes cannot be combined with release inputs")
    elif any(value is None for value in positional):
        parser.error("source_root, jars_directory and output_directory are required")
    return args


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def lexical_absolute(path: Path) -> Path:
    return Path(os.path.abspath(os.fspath(path)))


def paths_overlap(first: Path, second: Path) -> bool:
    return first == second or first in second.parents or second in first.parents


def require_no_symlink_components(path: Path, *, missing_leaf_allowed: bool = False) -> None:
    absolute = lexical_absolute(path)
    current = Path(absolute.anchor)
    parts = absolute.parts[1:] if absolute.anchor else absolute.parts
    for index, part in enumerate(parts):
        current /= part
        try:
            metadata = current.lstat()
        except FileNotFoundError:
            if missing_leaf_allowed and index == len(parts) - 1:
                return
            raise ReleaseError(f"path component does not exist: {current}") from None
        if stat.S_ISLNK(metadata.st_mode):
            raise ReleaseError(f"symlinked path component is forbidden: {current}")


def require_directory(path: Path) -> None:
    require_no_symlink_components(path)
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        raise ReleaseError(f"expected a non-symlink directory: {path}") from None
    if not stat.S_ISDIR(metadata.st_mode):
        raise ReleaseError(f"expected a non-symlink directory: {path}")


def read_regular_snapshot(path: Path) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_BINARY", 0)
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise ReleaseError(f"cannot open regular non-symlink file {path}: {exc}") from exc
    try:
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode):
            raise ReleaseError(f"expected a regular non-symlink file: {path}")
        if opened.st_size > MAX_REGULAR_INPUT_SIZE:
            raise ReleaseError(f"regular input exceeds the bounded size: {path}")
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        finished = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    try:
        named = path.lstat()
    except FileNotFoundError:
        raise ReleaseError(f"input disappeared while being read: {path}") from None
    identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)
    if identity(opened) != identity(finished) or identity(finished) != identity(named):
        raise ReleaseError(f"input changed while being read: {path}")
    if stat.S_ISLNK(named.st_mode) or not stat.S_ISREG(named.st_mode):
        raise ReleaseError(f"expected a regular non-symlink file: {path}")
    return b"".join(chunks)


def safe_relative(path: Path, root: Path) -> str:
    try:
        relative = path.relative_to(root).as_posix()
    except ValueError as exc:
        raise ReleaseError(f"path escapes root {root}: {path}") from exc
    pure = PurePosixPath(relative)
    if not relative or relative.startswith("/") or "\\" in relative:
        raise ReleaseError(f"unsafe relative path: {relative!r}")
    if any(part in ("", ".", "..") or ":" in part for part in pure.parts):
        raise ReleaseError(f"non-canonical relative path: {relative!r}")
    if unicodedata.normalize("NFC", relative) != relative:
        raise ReleaseError(f"non-NFC relative path: {relative!r}")
    return relative


def source_excluded(relative: str) -> bool:
    pure = PurePosixPath(relative)
    name = pure.name
    folded_name = name.casefold()
    return (
        any(part in EXCLUDED_PARTS for part in pure.parts)
        or name == ".DS_Store"
        or name.startswith("hs_err_pid")
        or name.endswith(EXCLUDED_SUFFIXES)
        or folded_name.endswith(".class")
        or (folded_name.endswith(".jar") and relative not in ALLOWED_SOURCE_JARS)
        or folded_name in SENSITIVE_SOURCE_NAMES
        or folded_name.startswith(".env.")
        or folded_name.endswith(SENSITIVE_SOURCE_SUFFIXES)
        or relative in EXCLUDED_SOURCE_EXACT
        or relative.startswith(EXCLUDED_SOURCE_PREFIXES)
        or (relative.startswith("examples/") and relative not in SOURCE_EXAMPLES)
    )


def source_mode(relative: str) -> int:
    if PurePosixPath(relative).name == "gradlew" or (
        relative.startswith("tools/")
        and not relative.startswith("tools/tests/")
        and relative.endswith((".py", ".sh"))
    ):
        return 0o755
    return 0o644


def source_entries(root: Path) -> dict[str, tuple[bytes, int]]:
    entries: dict[str, tuple[bytes, int]] = {}
    for name in sorted(SOURCE_ROOT_FILES):
        entries[name] = (read_regular_snapshot(root / name), source_mode(name))

    evidence_root = root / "build-evidence" / "1.9.16-EVOLUTION"
    require_directory(evidence_root)
    scan_roots = [root / name for name in sorted(SOURCE_ROOTS)] + [evidence_root]
    for scan_root in scan_roots:
        require_directory(scan_root)
        for directory, directory_names, file_names in os.walk(scan_root, followlinks=False):
            directory_path = Path(directory)
            for child_name in tuple(directory_names):
                child = directory_path / child_name
                relative = safe_relative(child, root)
                if child.is_symlink():
                    raise ReleaseError(f"source symlink is forbidden: {relative}")
                if source_excluded(relative):
                    directory_names.remove(child_name)
            directory_names.sort()
            file_names.sort()
            for file_name in file_names:
                path = directory_path / file_name
                relative = safe_relative(path, root)
                if path.is_symlink():
                    raise ReleaseError(f"source symlink is forbidden: {relative}")
                if source_excluded(relative):
                    continue
                if relative in entries:
                    continue
                entries[relative] = (read_regular_snapshot(path), source_mode(relative))

    missing = sorted(set(RELEASE_EVIDENCE) - entries.keys())
    if missing:
        raise ReleaseError(f"source evidence is missing: {', '.join(missing)}")
    folded: dict[str, str] = {}
    for name in entries:
        key = name.casefold()
        if key in folded:
            raise ReleaseError(f"case-insensitive source collision: {folded[key]} vs {name}")
        folded[key] = name
    forbidden = sorted(
        name for name in entries
        if name.startswith(("youer-agent/", "crucible-companion/"))
        or name in {"NLOGIN-YOUER.md", "YOUER-AGENT.md", "LEGACY-FORGE-1.7.10.md"}
        or (name.startswith("examples/") and "youer" in name.casefold())
    )
    if forbidden:
        raise ReleaseError(f"operational non-lobby source leaked into snapshot: {forbidden}")
    return entries


def source_tree_sha256(entries: dict[str, tuple[bytes, int]]) -> str:
    digest = hashlib.sha256()
    for name in sorted(entries):
        payload, mode = entries[name]
        encoded_name = name.encode("utf-8")
        digest.update(len(encoded_name).to_bytes(4, "big"))
        digest.update(encoded_name)
        digest.update(mode.to_bytes(4, "big"))
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest()


def zip_info(
    name: str,
    mode: int,
    compression: int,
    timestamp: tuple[int, int, int, int, int, int],
) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, timestamp)
    info.create_system = 3
    info.create_version = 20
    info.extract_version = 20
    info.compress_type = compression
    info.external_attr = (stat.S_IFREG | mode) << 16
    return info


def make_zip(
    entries: dict[str, tuple[bytes, int]],
    *,
    stored: bool,
    timestamp: tuple[int, int, int, int, int, int] = FIXED_TIMESTAMP,
) -> bytes:
    compression = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=compression,
                         compresslevel=None if stored else 9, allowZip64=True) as archive:
        archive.comment = b""
        for name in sorted(entries):
            payload, mode = entries[name]
            archive.writestr(zip_info(name, mode, compression, timestamp), payload)
    payload = buffer.getvalue()
    validate_generated_zip(payload, frozenset(entries), "generated ZIP", timestamp)
    return payload


def canonical_archive_name(name: str) -> tuple[str, bool]:
    if not name or "\x00" in name or "\\" in name:
        raise ReleaseError(f"unsafe archive entry name: {name!r}")
    is_directory = name.endswith("/")
    stripped = name[:-1] if is_directory else name
    if not stripped or stripped.startswith("/"):
        raise ReleaseError(f"unsafe archive entry name: {name!r}")
    parts = stripped.split("/")
    if any(part in ("", ".", "..") or ":" in part for part in parts):
        raise ReleaseError(f"non-canonical archive entry name: {name!r}")
    canonical = PurePosixPath(*parts).as_posix() + ("/" if is_directory else "")
    if canonical != name or unicodedata.normalize("NFC", name) != name:
        raise ReleaseError(f"non-canonical archive entry name: {name!r}")
    return name, is_directory


def inspect_zip(payload: bytes, archive_name: str, *,
                require_canonical_metadata: bool,
                expected_timestamp: tuple[int, int, int, int, int, int] = FIXED_TIMESTAMP,
                allow_data_descriptor: bool = False,
                require_exact_creator_versions: bool = True,
                ) -> tuple[dict[str, bytes], dict[str, zipfile.ZipInfo]]:
    entries: dict[str, bytes] = {}
    infos: dict[str, zipfile.ZipInfo] = {}
    kinds: dict[str, bool] = {}
    folded: dict[str, str] = {}
    total_size = 0
    try:
        with zipfile.ZipFile(io.BytesIO(payload), "r") as archive:
            if archive.comment:
                raise ReleaseError(f"archive comment is forbidden in {archive_name}")
            all_infos = archive.infolist()
            if not all_infos or len(all_infos) > MAX_ARCHIVE_ENTRIES:
                raise ReleaseError(f"invalid archive entry count in {archive_name}")
            for info in all_infos:
                name, is_directory = canonical_archive_name(info.filename)
                if name in infos:
                    raise ReleaseError(f"duplicate archive entry in {archive_name}: {name}")
                folded_name = name.casefold()
                if folded_name in folded:
                    raise ReleaseError(
                        f"case-insensitive archive collision in {archive_name}: "
                        f"{folded[folded_name]} vs {name}")
                folded[folded_name] = name
                bare_name = name[:-1] if is_directory else name
                previous_kind = kinds.get(bare_name)
                if previous_kind is not None and previous_kind != is_directory:
                    raise ReleaseError(
                        f"archive path is both a file and directory in {archive_name}: {bare_name}"
                    )
                kinds[bare_name] = is_directory
                allowed_flags = 0x800 | (0x08 if allow_data_descriptor else 0)
                if info.flag_bits & ~allowed_flags:
                    raise ReleaseError(
                        f"encrypted/non-canonical flags in {archive_name}: "
                        f"{name}=0x{info.flag_bits:x}")
                if info.extra or info.comment:
                    raise ReleaseError(f"extra/comment field is forbidden in {archive_name}: {name}")
                if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise ReleaseError(
                        f"unsupported compression method in {archive_name}: "
                        f"{name}={info.compress_type}"
                    )
                if info.file_size > MAX_ENTRY_SIZE:
                    raise ReleaseError(f"oversized archive entry in {archive_name}: {name}")
                total_size += info.file_size
                if total_size > MAX_UNCOMPRESSED_SIZE:
                    raise ReleaseError(f"archive expands beyond the size bound: {archive_name}")
                if require_canonical_metadata:
                    if info.date_time != expected_timestamp:
                        raise ReleaseError(f"non-deterministic timestamp in {archive_name}: {name}")
                    if info.create_system != 3:
                        raise ReleaseError(
                            f"non-Unix ZIP creator metadata in {archive_name}: {name}"
                        )
                    if require_exact_creator_versions and (
                            info.create_version != 20 or info.extract_version != 20):
                        raise ReleaseError(f"non-canonical ZIP creator metadata in {archive_name}: {name}")
                    mode = (info.external_attr >> 16) & 0xFFFF
                    allowed_modes = {0o40755} if is_directory else {0o100644, 0o100755}
                    if mode not in allowed_modes:
                        raise ReleaseError(
                            f"non-canonical mode in {archive_name}: "
                            f"{name}={mode:o}, expected one of "
                            f"{sorted(format(value, 'o') for value in allowed_modes)}")
                data = archive.read(info)
                if is_directory and data:
                    raise ReleaseError(f"directory entry contains data in {archive_name}: {name}")
                infos[name] = info
                if not is_directory:
                    entries[name] = data
            for bare_name in kinds:
                parts = bare_name.split("/")
                for end in range(1, len(parts)):
                    ancestor = "/".join(parts[:end])
                    if ancestor in kinds and not kinds[ancestor]:
                        raise ReleaseError(
                            f"archive file is also an ancestor in {archive_name}: {ancestor}"
                        )
            bad = archive.testzip()
            if bad is not None:
                raise ReleaseError(f"CRC failure in {archive_name}: {bad}")
    except (zipfile.BadZipFile, RuntimeError, OSError) as exc:
        raise ReleaseError(f"invalid archive {archive_name}: {exc}") from exc
    return entries, infos


def validate_generated_zip(
    payload: bytes,
    expected: frozenset[str],
    label: str,
    timestamp: tuple[int, int, int, int, int, int],
) -> None:
    entries, _ = inspect_zip(
        payload,
        label,
        require_canonical_metadata=True,
        expected_timestamp=timestamp,
    )
    if set(entries) != set(expected):
        raise ReleaseError(
            f"exact ZIP tree mismatch in {label}: expected={sorted(expected)}, "
            f"actual={sorted(entries)}")


def parse_manifest(raw: bytes, jar_name: str) -> dict[str, str]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ReleaseError(f"manifest is not UTF-8 in {jar_name}") from exc
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    main_end = next((index for index, line in enumerate(lines) if not line), len(lines))
    attributes: dict[str, str] = {}
    index = 0
    while index < main_end:
        line = lines[index]
        if line.startswith(" ") or ": " not in line:
            raise ReleaseError(f"malformed manifest in {jar_name} at line {index + 1}")
        key, value = line.split(": ", 1)
        index += 1
        while index < main_end and lines[index].startswith(" "):
            value += lines[index][1:]
            index += 1
        folded = key.casefold()
        if folded in attributes:
            raise ReleaseError(f"duplicate manifest attribute in {jar_name}: {key}")
        attributes[folded] = value
    if attributes.get("manifest-version") != "1.0":
        raise ReleaseError(f"unsupported manifest in {jar_name}")
    return attributes


def validate_class(payload: bytes, entry: str, jar_name: str) -> None:
    if len(payload) < 8 or payload[:4] != b"\xca\xfe\xba\xbe":
        raise ReleaseError(f"invalid class header in {jar_name}: {entry}")
    major = int.from_bytes(payload[6:8], "big")
    if major != 65:
        raise ReleaseError(f"wrong class major in {jar_name}: {entry}={major}, expected=65")


def validate_plugin_descriptor(jar_name: str, entries: dict[str, bytes]) -> None:
    if jar_name.startswith("ProtocolObelisk-Velocity-"):
        def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
            result: dict[str, object] = {}
            for key, value in pairs:
                if key in result:
                    raise ReleaseError(f"duplicate Velocity descriptor key in {jar_name}: {key}")
                result[key] = value
            return result

        try:
            descriptor = json.loads(
                entries["velocity-plugin.json"].decode("utf-8"),
                object_pairs_hook=unique_object,
            )
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ReleaseError(f"invalid Velocity descriptor in {jar_name}") from exc
        expected = {
            "id": "protocolobelisk",
            "name": "ProtocolObelisk",
            "version": VERSION,
            "main": "br.com.atmbrasil.lobby.velocity.Atm10LobbyVelocityPlugin",
        }
        if not isinstance(descriptor, dict) or any(descriptor.get(k) != v for k, v in expected.items()):
            raise ReleaseError(f"Velocity descriptor identity/version mismatch in {jar_name}")
        return
    try:
        text = entries["plugin.yml"].decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ReleaseError(f"invalid Paper descriptor encoding in {jar_name}") from exc
    values: dict[str, str] = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#") or line[0].isspace():
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+):[ \t]*(.*)", line)
        if match:
            key = match.group(1)
            if key in values:
                raise ReleaseError(f"duplicate Paper descriptor key in {jar_name}: {key}")
            values[key] = match.group(2).strip().strip("'\"")
    expected = {
        "name": "ProtocolObelisk",
        "version": VERSION,
        "main": "br.com.atmbrasil.lobby.paper.Atm10LobbyPaperPlugin",
        "api-version": "1.21",
    }
    if any(values.get(key) != value for key, value in expected.items()):
        raise ReleaseError(f"Paper descriptor identity/version mismatch in {jar_name}")


def validate_jar(payload: bytes, jar_name: str) -> None:
    if jar_name not in JAR_POLICIES:
        raise ReleaseError(f"unexpected executable component: {jar_name}")
    entries, infos = inspect_zip(
        payload,
        jar_name,
        require_canonical_metadata=True,
        expected_timestamp=GRADLE_FIXED_TIMESTAMP,
        allow_data_descriptor=True,
        require_exact_creator_versions=False,
    )
    policy = JAR_POLICIES[jar_name]
    required = {"META-INF/MANIFEST.MF", *tuple(policy["required"])}
    missing = sorted(required - entries.keys())
    if missing:
        raise ReleaseError(f"required entries missing from {jar_name}: {missing}")
    class_count = 0
    for name in entries:
        if ((infos[name].external_attr >> 16) & 0xFFFF) != 0o100644:
            raise ReleaseError(f"executable/non-canonical JAR entry mode in {jar_name}: {name}")
        if name.startswith(tuple(policy["forbidden"])):
            raise ReleaseError(f"foreign platform/component leaked into {jar_name}: {name}")
        if any(name.startswith(prefix) for prefix in FORBIDDEN_OPERATIONAL_ENTRIES):
            raise ReleaseError(f"Youer/Crucible operational code leaked into {jar_name}: {name}")
        upper_name = name.upper()
        if upper_name.startswith("META-INF/") and upper_name.endswith(
                (".SF", ".RSA", ".DSA", ".EC")):
            raise ReleaseError(f"signature entry is forbidden in {jar_name}: {name}")
        if name.endswith(".class"):
            class_count += 1
            validate_class(entries[name], name, jar_name)
    if class_count == 0:
        raise ReleaseError(f"JAR contains no classes: {jar_name}")
    manifest = parse_manifest(entries["META-INF/MANIFEST.MF"], jar_name)
    expected_manifest = {
        "implementation-title": str(policy["title"]),
        "implementation-version": VERSION,
        "automatic-module-name": str(policy["module"]),
    }
    if any(manifest.get(key) != value for key, value in expected_manifest.items()):
        raise ReleaseError(f"manifest identity/version mismatch in {jar_name}")
    if "signature-version" in manifest:
        raise ReleaseError(f"signed manifest is forbidden in {jar_name}")
    validate_plugin_descriptor(jar_name, entries)


def canonical_checksums(payloads: dict[str, bytes]) -> bytes:
    return "".join(
        f"{sha256_bytes(payloads[name])}  {name}\n" for name in sorted(payloads)
    ).encode("ascii")


def exact_regular_directory(path: Path, expected_names: frozenset[str]) -> dict[str, bytes]:
    require_directory(path)
    actual: set[str] = set()
    payloads: dict[str, bytes] = {}
    with os.scandir(path) as children:
        for child in children:
            if child.is_symlink() or not child.is_file(follow_symlinks=False):
                raise ReleaseError(f"directory contains non-regular entry: {child.path}")
            actual.add(child.name)
            payloads[child.name] = read_regular_snapshot(Path(child.path))
    if actual != set(expected_names):
        raise ReleaseError(
            f"exact directory tree mismatch at {path}: expected={sorted(expected_names)}, "
            f"actual={sorted(actual)}")
    return payloads


def load_validated_jars(path: Path) -> dict[str, bytes]:
    payloads = exact_regular_directory(path, frozenset((*JAR_NAMES, "CHECKSUMS.sha256")))
    jars = {name: payloads[name] for name in JAR_NAMES}
    if payloads["CHECKSUMS.sha256"] != canonical_checksums(jars):
        raise ReleaseError("CHECKSUMS.sha256 does not exactly bind the two JAR snapshots")
    for name, payload in jars.items():
        validate_jar(payload, name)
    return jars


def validate_version(root: Path) -> None:
    payload = read_regular_snapshot(root / "gradle.properties")
    try:
        lines = payload.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise ReleaseError("gradle.properties is not UTF-8") from exc
    declarations = []
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith(("#", "!")) and "=" in stripped:
            key, value = stripped.split("=", 1)
            if key.strip() == "protocolObeliskVersion":
                declarations.append(value.strip())
    if declarations != [VERSION]:
        raise ReleaseError(
            f"gradle.properties must declare protocolObeliskVersion exactly once as {VERSION}")


def atomic_write(path: Path, payload: bytes) -> None:
    require_directory(path.parent)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=path.parent, prefix=f".{path.name}.", suffix=".tmp", delete=False
        ) as stream:
            temporary = Path(stream.name)
            stream.write(payload)
            stream.flush()
            os.fchmod(stream.fileno(), 0o644)
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def publish_directory(output: Path, files: dict[str, bytes]) -> None:
    require_directory(output.parent)
    if os.path.lexists(output):
        raise ReleaseError(f"output must be a fresh, absent path: {output}")
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}.publish-", dir=output.parent))
    published = False
    try:
        for name, payload in sorted(files.items()):
            if PurePosixPath(name).name != name:
                raise ReleaseError(f"publication output must be a leaf: {name}")
            atomic_write(staging / name, payload)
        os.chmod(staging, 0o755)
        directory = os.open(staging, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
        os.rename(staging, output)
        published = True
        parent = os.open(output.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        if not published:
            shutil.rmtree(staging, ignore_errors=True)


def validate_source_payload(payload: bytes) -> None:
    entries, infos = inspect_zip(payload, SOURCE_NAME, require_canonical_metadata=True)
    if not entries:
        raise ReleaseError("source ZIP is empty")
    required = set(SOURCE_ROOT_FILES) | set(RELEASE_EVIDENCE)
    missing = sorted(required - entries.keys())
    if missing:
        raise ReleaseError(f"source ZIP is missing required files: {missing}")
    for name in entries:
        expected_mode = stat.S_IFREG | source_mode(name)
        actual_mode = (infos[name].external_attr >> 16) & 0xFFFF
        if actual_mode != expected_mode:
            raise ReleaseError(
                f"non-canonical source mode: {name}={actual_mode:o}, expected={expected_mode:o}"
            )
        if name.startswith(("youer-agent/", "crucible-companion/")):
            raise ReleaseError(f"non-lobby component leaked into source ZIP: {name}")
        if name in {"NLOGIN-YOUER.md", "YOUER-AGENT.md", "LEGACY-FORGE-1.7.10.md"}:
            raise ReleaseError(f"non-lobby operational guide leaked into source ZIP: {name}")
        if name.startswith("examples/") and name not in SOURCE_EXAMPLES:
            raise ReleaseError(f"unreviewed/non-lobby example leaked into source ZIP: {name}")


def validate_publication(path: Path) -> dict[str, bytes]:
    files = exact_regular_directory(path, PUBLICATION_NAMES)
    jars = {name: files[name] for name in JAR_NAMES}
    for name, payload in jars.items():
        validate_jar(payload, name)
    checksum_inputs = {**jars, SOURCE_NAME: files[SOURCE_NAME]}
    if files["CHECKSUMS.sha256"] != canonical_checksums(checksum_inputs):
        raise ReleaseError("publication CHECKSUMS.sha256 is not canonical")
    expected_release_checksum = (
        f"{sha256_bytes(files[RELEASE_NAME])}  {RELEASE_NAME}\n".encode("ascii"))
    if files[RELEASE_CHECKSUM_NAME] != expected_release_checksum:
        raise ReleaseError("release ZIP checksum file is not canonical")
    validate_source_payload(files[SOURCE_NAME])
    release_entries, release_infos = inspect_zip(
        files[RELEASE_NAME], RELEASE_NAME, require_canonical_metadata=True)
    if set(release_entries) != set(RELEASE_PAYLOAD_NAMES):
        raise ReleaseError(
            f"exact release tree mismatch: expected={sorted(RELEASE_PAYLOAD_NAMES)}, "
            f"actual={sorted(release_entries)}")
    for name in release_entries:
        mode = (release_infos[name].external_attr >> 16) & 0xFFFF
        if mode != 0o100644:
            raise ReleaseError(f"release payload entry is executable: {name}")
    for name in JAR_NAMES:
        if release_entries[name] != jars[name]:
            raise ReleaseError(f"release ZIP changed the validated JAR snapshot: {name}")
    if release_entries[SOURCE_NAME] != files[SOURCE_NAME]:
        raise ReleaseError("release ZIP changed the validated source snapshot")
    if release_entries["CHECKSUMS.sha256"] != files["CHECKSUMS.sha256"]:
        raise ReleaseError("release ZIP checksum manifest differs from publication")
    forbidden_names = [name for name in release_entries
                       if "youer" in name.casefold() or "crucible" in name.casefold()]
    if forbidden_names:
        raise ReleaseError(f"Youer/Crucible release entries are forbidden: {forbidden_names}")
    return files


def create_release(root: Path, jars_directory: Path, output: Path) -> dict[str, bytes]:
    validate_version(root)
    source_snapshot = source_entries(root)
    source_payload = make_zip(source_snapshot, stored=False)
    jars = load_validated_jars(jars_directory)
    checksum_inputs = {**jars, SOURCE_NAME: source_payload}
    checksums = canonical_checksums(checksum_inputs)
    release_entries: dict[str, tuple[bytes, int]] = {
        name: (payload, 0o644) for name, payload in jars.items()}
    release_entries[SOURCE_NAME] = (source_payload, 0o644)
    release_entries["CHECKSUMS.sha256"] = (checksums, 0o644)
    for name in (*RELEASE_DOCS, *RELEASE_EVIDENCE, *RELEASE_INTEGRATIONS):
        release_entries[name] = (source_snapshot[name][0], 0o644)
    if set(release_entries) != set(RELEASE_PAYLOAD_NAMES):
        raise ReleaseError("internal release inventory construction error")
    release_payload = make_zip(release_entries, stored=True)
    publication = {
        **jars,
        SOURCE_NAME: source_payload,
        RELEASE_NAME: release_payload,
        RELEASE_CHECKSUM_NAME: (
            f"{sha256_bytes(release_payload)}  {RELEASE_NAME}\n".encode("ascii")),
        "CHECKSUMS.sha256": checksums,
    }
    publish_directory(output, publication)
    validate_publication(output)
    return publication


def main() -> int:
    args = parse_args()
    if args.validate_jars_only is not None:
        path = lexical_absolute(args.validate_jars_only)
        load_validated_jars(path)
        print(f"JAR_VALIDATION=PASS jars=2 directory={path}")
        return 0
    if args.validate_publication is not None:
        path = lexical_absolute(args.validate_publication)
        validate_publication(path)
        print(f"PUBLICATION_VALIDATION=PASS files={len(PUBLICATION_NAMES)} directory={path}")
        return 0
    if args.source_sha256 is not None:
        path = lexical_absolute(args.source_sha256)
        require_directory(path)
        validate_version(path)
        entries = source_entries(path)
        print(f"SOURCE_TREE_SHA256={source_tree_sha256(entries)}")
        return 0

    assert args.source_root is not None
    assert args.jars_directory is not None
    assert args.output_directory is not None
    root = lexical_absolute(args.source_root)
    jars = lexical_absolute(args.jars_directory)
    output = lexical_absolute(args.output_directory)
    require_directory(root)
    require_directory(jars)
    require_no_symlink_components(output, missing_leaf_allowed=True)
    if os.path.lexists(output):
        raise ReleaseError(f"output must be a fresh, absent path: {output}")
    if paths_overlap(output, jars):
        raise ReleaseError("output directory must be disjoint from JAR inputs")
    if paths_overlap(output, root):
        try:
            relative_output = output.relative_to(root)
        except ValueError:
            relative_output = None
        if relative_output is None or not relative_output.parts \
                or relative_output.parts[0] not in {"build", "dist"}:
            raise ReleaseError(
                "output inside the source tree is allowed only below the excluded build/dist roots"
            )
    publication = create_release(root, jars, output)
    print(f"SOURCE_SHA256={sha256_bytes(publication[SOURCE_NAME])}")
    print(f"RELEASE_SHA256={sha256_bytes(publication[RELEASE_NAME])}")
    print(f"PUBLICATION_FILES={len(publication)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
