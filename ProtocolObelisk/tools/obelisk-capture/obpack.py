#!/usr/bin/env python3
"""ProtocolObelisk compatibility-pack tool (standard library only).

Commands:
  compare <capture-a> <capture-b>         require two capture directories to be byte-identical
  pack <capture-dir> <out.obpack>          build a deterministic, hash-manifested pack
  inspect <pack.obpack|capture-dir>        print what the pack provides
  diff <old> <new> [--out report.md]       report new/changed mods, channels, configs,
                                            registries, tags and vanilla BlockState properties

A pack is a ZIP with fixed timestamps and sorted entries. ``manifest.sha256`` lists the SHA-256
of every other entry; ProtocolObelisk refuses a pack whose manifest does not match.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import os
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
MANIFEST = "manifest.sha256"
REPORT = "report.md"
CAPTURE = "capture.properties"
# Files from a capture directory that belong in a runtime pack. The self-contained registry
# variant is diagnostic only: a Paper lobby negotiates minecraft:core, so the wire variant is
# exactly what a real server would send.
PACK_PREFIXES = (
    "pack.properties",
    "mods.tsv",
    "network/",
    "registries/wire-known-pack/",
    "registries/wire-known-pack.properties",
    "tags/",
    "server-configs/",
    "server-configs.properties",
    "block-states/",
)
MAXIMUM_PACK_ENTRY_BYTES = 64 * 1024 * 1024
# The exact vanilla 1.21.1 entries a Paper lobby sends; the plugin pins the same file by SHA-256.
VANILLA_REGISTRIES = (Path(__file__).resolve().parents[2] / "velocity-plugin" / "src" / "main"
                      / "resources" / "vanilla-registries" / "minecraft-1.21.1-synchronized.tsv")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------------------------------------
# Pack access


class PackView:
    """Uniform read access to a capture directory or a .obpack file."""

    def __init__(self, location: Path):
        self.location = location
        self._zip = None
        if location.is_dir():
            self._files = {
                p.relative_to(location).as_posix(): p
                for p in sorted(location.rglob("*"))
                if p.is_file()
            }
        else:
            self._zip = zipfile.ZipFile(location)
            self._files = {name: name for name in self._zip.namelist() if not name.endswith("/")}

    def names(self) -> list[str]:
        return sorted(self._files)

    def has(self, name: str) -> bool:
        return name in self._files

    def read(self, name: str) -> bytes:
        if name not in self._files:
            raise KeyError(f"{self.location}: missing {name}")
        if self._zip is not None:
            return self._zip.read(name)
        return Path(self._files[name]).read_bytes()

    def text(self, name: str) -> str:
        return self.read(name).decode("utf-8")

    def properties(self, name: str) -> dict[str, str]:
        result: dict[str, str] = {}
        for line in self.text(name).splitlines():
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            result[key] = value
        return result

    def tsv(self, name: str) -> list[list[str]]:
        return [
            line.split("\t")
            for line in self.text(name).splitlines()
            if line and not line.startswith("#")
        ]


# ---------------------------------------------------------------------------------------------
# Minimal Minecraft wire decoding (bounded, for reports only)


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def byte(self) -> int:
        if self.pos >= len(self.data):
            raise ValueError("truncated")
        value = self.data[self.pos]
        self.pos += 1
        return value

    def take(self, count: int) -> bytes:
        if count < 0 or self.pos + count > len(self.data):
            raise ValueError("truncated")
        value = self.data[self.pos : self.pos + count]
        self.pos += count
        return value

    def varint(self) -> int:
        result = 0
        for shift in range(0, 35, 7):
            b = self.byte()
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                return result
        raise ValueError("VarInt too long")

    def string(self) -> str:
        return self.take(self.varint()).decode("utf-8")

    def done(self) -> bool:
        return self.pos == len(self.data)


def skip_nbt_payload(reader: Reader, tag: int, depth: int = 0) -> None:
    if depth > 512:
        raise ValueError("NBT too deep")
    if tag == 0:
        return
    if tag == 1:
        reader.take(1)
    elif tag == 2:
        reader.take(2)
    elif tag in (3, 5):
        reader.take(4)
    elif tag in (4, 6):
        reader.take(8)
    elif tag == 7:
        reader.take(struct.unpack(">i", reader.take(4))[0])
    elif tag == 8:
        reader.take(struct.unpack(">H", reader.take(2))[0])
    elif tag == 9:
        element = reader.byte()
        count = struct.unpack(">i", reader.take(4))[0]
        for _ in range(max(count, 0)):
            skip_nbt_payload(reader, element, depth + 1)
    elif tag == 10:
        while True:
            child = reader.byte()
            if child == 0:
                return
            reader.take(struct.unpack(">H", reader.take(2))[0])
            skip_nbt_payload(reader, child, depth + 1)
    elif tag == 11:
        reader.take(4 * struct.unpack(">i", reader.take(4))[0])
    elif tag == 12:
        reader.take(8 * struct.unpack(">i", reader.take(4))[0])
    else:
        raise ValueError(f"unknown NBT tag {tag}")


def registry_entries(packet: bytes) -> tuple[str, list[tuple[str, bool]]]:
    """Decodes ClientboundRegistryDataPacket: registry key + (id, has-data) entries."""
    reader = Reader(packet)
    registry = reader.string()
    entries = []
    for _ in range(reader.varint()):
        entry_id = reader.string()
        has_data = reader.byte() != 0
        if has_data:
            skip_nbt_payload(reader, reader.byte())  # network NBT: unnamed root
        entries.append((entry_id, has_data))
    if not reader.done():
        raise ValueError(f"trailing bytes in registry {registry}")
    return registry, entries


def update_tags(packet: bytes) -> dict[str, dict[str, list[int]]]:
    reader = Reader(packet)
    result: dict[str, dict[str, list[int]]] = {}
    for _ in range(reader.varint()):
        registry = reader.string()
        tags: dict[str, list[int]] = {}
        for _ in range(reader.varint()):
            name = reader.string()
            tags[name] = [reader.varint() for _ in range(reader.varint())]
        result[registry] = tags
    if not reader.done():
        raise ValueError("trailing bytes in tags packet")
    return result


def pobs_targets(data: bytes) -> tuple[int, list[int]]:
    """Decodes a POBS v1 BlockState map: (target global count, vanilla-indexed targets)."""
    if data[:4] != b"POBS" or data[4] != 1:
        raise ValueError("not a POBS v1 map")
    count, global_count = struct.unpack(">ii", data[5:13])
    reader = Reader(data[13:])
    targets = [reader.varint() for _ in range(count)]
    if not reader.done():
        raise ValueError("trailing POBS bytes")
    return global_count, targets


def config_payload(payload: bytes) -> tuple[str, bytes]:
    reader = Reader(payload)
    name = reader.string()
    contents = reader.take(reader.varint())
    if not reader.done():
        raise ValueError(f"trailing bytes in config {name}")
    return name, contents


# ---------------------------------------------------------------------------------------------
# Pack model


@dataclass
class PackModel:
    meta: dict[str, str]
    mods: dict[str, str] = field(default_factory=dict)
    channels: dict[tuple[str, str], tuple[str, str, str]] = field(default_factory=dict)
    configs: dict[str, str] = field(default_factory=dict)
    registries: dict[str, list[tuple[str, bool]]] = field(default_factory=dict)
    tags: dict[str, dict[str, list[int]]] = field(default_factory=dict)
    extra_properties: dict[tuple[str, str], tuple[str, str, str]] = field(default_factory=dict)


def load_model(view: PackView) -> PackModel:
    model = PackModel(meta=view.properties("pack.properties"))
    for row in view.tsv("mods.tsv"):
        model.mods[row[0]] = row[1]
    for row in view.tsv("network/channels.tsv"):
        model.channels[(row[0], row[1])] = (row[2], row[3], row[4])
    config_meta = view.properties("server-configs.properties")
    for index in range(int(config_meta["config.count"])):
        model.configs[config_meta[f"config.{index}.name"]] = config_meta[f"config.{index}.content-sha256"]
    wire = view.properties("registries/wire-known-pack.properties")
    for index in range(int(wire["packet.count"])):
        name = wire[f"packet.{index}.file"]
        registry, entries = registry_entries(view.read(f"registries/wire-known-pack/{name}"))
        if registry != wire[f"packet.{index}.registry"]:
            raise ValueError(f"registry manifest mismatch for {name}")
        model.registries[registry] = entries
    model.tags = update_tags(view.read("tags/full-update-tags.bin"))
    for row in view.tsv("block-states/extra-vanilla-properties.tsv"):
        kind = row[4] if len(row) > 4 else "new-property"
        model.extra_properties[(row[0], row[1])] = (row[2], row[3], kind)
    return model


# ---------------------------------------------------------------------------------------------
# Commands


def file_digests(root: Path) -> dict[str, tuple[int, str]]:
    return {
        p.relative_to(root).as_posix(): (p.stat().st_size, sha256(p.read_bytes()))
        for p in sorted(root.rglob("*"))
        if p.is_file()
    }


def nbt_value(reader: Reader, tag: int, depth: int = 0):
    """Decodes network NBT into a comparable value; lists compare as multisets."""
    if depth > 512:
        raise ValueError("NBT too deep")
    if tag in (1, 2, 3, 4, 5, 6):
        fmt = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}[tag]
        return (tag, struct.unpack(fmt, reader.take(struct.calcsize(fmt)))[0])
    if tag == 7:
        return (tag, reader.take(struct.unpack(">i", reader.take(4))[0]))
    if tag == 8:
        return (tag, reader.take(struct.unpack(">H", reader.take(2))[0]).decode("utf-8"))
    if tag == 9:
        element = reader.byte()
        count = struct.unpack(">i", reader.take(4))[0]
        items = [nbt_value(reader, element, depth + 1) for _ in range(max(count, 0))]
        return (tag, tuple(sorted(items, key=repr)))
    if tag == 10:
        values = {}
        while True:
            child = reader.byte()
            if child == 0:
                return (tag, tuple(sorted(values.items())))
            key = reader.take(struct.unpack(">H", reader.take(2))[0]).decode("utf-8")
            values[key] = nbt_value(reader, child, depth + 1)
    if tag in (11, 12):
        width = 4 if tag == 11 else 8
        count = struct.unpack(">i", reader.take(4))[0]
        return (tag, reader.take(width * count))
    raise ValueError(f"unknown NBT tag {tag}")


def registry_semantics(packet: bytes):
    reader = Reader(packet)
    registry = reader.string()
    entries = []
    for _ in range(reader.varint()):
        entry_id = reader.string()
        data = nbt_value(reader, reader.byte()) if reader.byte() else None
        entries.append((entry_id, data))
    if not reader.done():
        raise ValueError("trailing registry bytes")
    return registry, entries


def dynamic_tag_semantics(packet: bytes, dynamic: set[str]):
    return {
        registry: {tag: sorted(members) for tag, members in tags.items()}
        for registry, tags in update_tags(packet).items()
        if registry in dynamic
    }


# pack.properties keys whose values hash files compared semantically below.
SEMANTIC_PROPERTY_KEYS = (
    "registries.wire.sequence-sha256",
    "registries.self-contained.sequence-sha256",
    "tags.sha256",
)


def command_compare(args: argparse.Namespace) -> int:
    """Requires two independent captures to agree.

    Everything is compared byte-for-byte except data a real NeoForge server itself serializes
    nondeterministically: NBT lists backed by hash sets inside registry entries (compared as
    multisets with identical entry ids and order) and tags of static registries, whose modded
    numeric ids NeoForge may assign differently per boot (ProtocolObelisk never sends those).
    """
    root_a, root_b = Path(args.a), Path(args.b)
    first, second = file_digests(root_a), file_digests(root_b)
    problems = []
    if set(first) != set(second):
        problems += [f"only in A: {n}" for n in sorted(set(first) - set(second))]
        problems += [f"only in B: {n}" for n in sorted(set(second) - set(first))]
    semantic = 0
    view_a = PackView(root_a)
    dynamic = set(registry_entries(view_a.read(f"registries/wire-known-pack/{name}"))[0]
                  for name in [n.split("/")[-1] for n in first
                               if n.startswith("registries/wire-known-pack/") and n.endswith(".bin")])
    for name in sorted(set(first) & set(second)):
        if first[name] == second[name]:
            continue
        a, b = (root_a / name).read_bytes(), (root_b / name).read_bytes()
        if name.startswith("registries/") and name.endswith(".bin"):
            if registry_semantics(a) == registry_semantics(b):
                semantic += 1
                continue
        elif name in ("registries/wire-known-pack.properties", "registries/self-contained.properties"):
            strip = lambda d: [l for l in d.decode().splitlines()
                               if not l.endswith("sequence-sha256=") and ".sha256=" not in l
                               and not l.startswith("packet.sequence-sha256=")]
            if strip(a) == strip(b):
                semantic += 1
                continue
        elif name == "tags/full-update-tags.bin":
            if dynamic_tag_semantics(a, dynamic) == dynamic_tag_semantics(b, dynamic):
                semantic += 1
                continue
        elif name == "tags/full-update-tags.properties":
            strip = lambda d: [l for l in d.decode().splitlines() if not l.startswith(("sha256=", "bytes="))]
            if strip(a) == strip(b):
                semantic += 1
                continue
        elif name == "pack.properties":
            strip = lambda d: [l for l in d.decode().splitlines() if not l.startswith(SEMANTIC_PROPERTY_KEYS)]
            if strip(a) == strip(b):
                semantic += 1
                continue
        problems.append(f"changed: {name}")
    if problems:
        print("CAPTURES DIFFER", file=sys.stderr)
        for item in problems[:80]:
            print(f"  {item}", file=sys.stderr)
        return 1
    print(f"captures agree: {len(first)} files, {len(first) - semantic} byte-identical, "
          f"{semantic} semantically identical (set-ordered NBT lists / static-registry tag ids)")
    return 0


def selected_capture_files(root: Path) -> dict[str, bytes]:
    selected: dict[str, bytes] = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if any(relative == prefix or relative.startswith(prefix) for prefix in PACK_PREFIXES):
            data = path.read_bytes()
            if len(data) > MAXIMUM_PACK_ENTRY_BYTES:
                raise ValueError(f"{relative} exceeds the pack entry bound")
            selected[relative] = data
    for required in (
        "pack.properties",
        "mods.tsv",
        "network/server-query.bin",
        "network/channels.tsv",
        "registries/wire-known-pack.properties",
        "tags/full-update-tags.bin",
        "server-configs.properties",
        "block-states/block-state-map.bin",
        "block-states/block-state-map.properties",
        "block-states/extra-vanilla-properties.tsv",
    ):
        if required not in selected:
            raise ValueError(f"capture is missing {required}")
    return selected


def write_deterministic_zip(out: Path, entries: dict[str, bytes]) -> None:
    temporary = out.with_name(out.name + ".partial")
    with zipfile.ZipFile(temporary, "w") as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            info.create_system = 3
            archive.writestr(info, entries[name], compresslevel=9)
    os.replace(temporary, out)


def command_pack(args: argparse.Namespace) -> int:
    root = Path(args.capture)
    out = Path(args.out)
    if out.exists():
        print(f"refusing to overwrite {out}", file=sys.stderr)
        return 2
    entries = selected_capture_files(root)
    meta = PackView(root).properties("pack.properties")
    if meta.get("obelisk-pack-format") != "1":
        raise ValueError("unsupported capture format")
    entries[CAPTURE] = (
        "format-version=1\n"
        f"independent-boots={args.boots}\n"
        f"boots-agree={'semantic-and-byte-identical-surfaces' if args.boots > 1 else 'not-verified'}\n"
    ).encode()
    view = PackView(root)
    entries[REPORT] = render_inspect(view, load_model(view)).encode()
    manifest = "".join(f"{sha256(entries[name])}  {name}\n" for name in sorted(entries))
    entries[MANIFEST] = manifest.encode()
    write_deterministic_zip(out, entries)
    print(f"pack written: {out} ({out.stat().st_size} bytes, sha256 {sha256(out.read_bytes())})")
    return 0


def verify_manifest(view: PackView) -> None:
    if not view.has(MANIFEST):
        return
    listed = {}
    for line in view.text(MANIFEST).splitlines():
        digest, name = line.split("  ", 1)
        listed[name] = digest
    actual = {name for name in view.names() if name != MANIFEST}
    if set(listed) != actual:
        raise ValueError("manifest does not list exactly the pack entries")
    for name, digest in listed.items():
        if sha256(view.read(name)) != digest:
            raise ValueError(f"manifest hash mismatch for {name}")


def load_vanilla_entries() -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for line in VANILLA_REGISTRIES.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            registry, entry = line.split("\t")
            result.setdefault(registry, set()).add(entry)
    return result


def render_inspect(view: PackView, model: PackModel) -> str:
    meta = model.meta
    required = [c for c, v in model.channels.items() if v[2] == "required"]
    vanilla = load_vanilla_entries()
    modded_registries = [r for r in model.registries if r not in vanilla]
    extended = {
        r: [e for e, _ in entries if e not in vanilla[r]]
        for r, entries in model.registries.items() if r in vanilla
    }
    vanilla_with_modded = [r for r, added in extended.items() if added]
    lines = [
        f"# Pack de compatibilidade `{meta['pack-id']}`",
        "",
        f"- Nome: {meta.get('display-name', '')}",
        f"- Minecraft {meta['minecraft']} (protocolo {meta['minecraft-protocol']}), NeoForge {meta['neoforge']}",
        f"- Origem: {meta.get('source.file', 'não registrada')} "
        f"({meta.get('source.bytes', '?')} bytes, sha256 {meta.get('source.sha256', '?')})",
        f"- Mods carregados no servidor: {len(model.mods)}",
        f"- Canais NeoForge do servidor: {len(model.channels)} ({len(required)} obrigatórios)",
        f"- SERVER configs: {len(model.configs)}",
        f"- Registries sincronizados: {len(model.registries)} "
        f"({len(modded_registries)} que o Paper não envia; {len(vanilla_with_modded)} do Paper "
        f"estendidos com {sum(len(a) for a in extended.values())} entradas)",
        f"- Registries com tags: {len(model.tags)}",
        f"- BlockStates globais: {meta['block-states.global-count']}; "
        f"propriedades extras em blocos vanilla: {len(model.extra_properties)}",
        "",
        "## Registries que o Paper não envia (anexados inteiros após os registries do Paper)",
        "",
    ]
    for registry in modded_registries:
        lines.append(f"- `{registry}`: {len(model.registries[registry])} entradas")
    lines += [
        "",
        "## Registries do Paper estendidos",
        "",
        "Só as entradas que o vanilla 1.21.1 não tem são anexadas, depois das do Paper: os ids "
        "numéricos do Paper continuam valendo e overrides de entradas vanilla ficam com a definição "
        "do Paper. `minecraft:enchantment` é substituído inteiro quando "
        "`compatibility-pack-replace-enchantment-registry=true`.",
        "",
    ]
    for registry in vanilla_with_modded:
        entries = model.registries[registry]
        overrides = sum(1 for e, has in entries if e in vanilla[registry] and has)
        lines.append(f"- `{registry}`: {len(entries)} entradas no servidor; "
                     f"{len(extended[registry])} anexadas; {overrides} overrides vanilla mantidos do Paper")
    lines += ["", "## O que mods acrescentam a blocos vanilla (decide o mapa de BlockState)", ""]
    for (block, prop), (default, values, kind) in sorted(model.extra_properties.items()):
        if kind == "extra-values":
            lines.append(f"- `{block}`: propriedade `{prop}` ganhou valores `{values}`")
        else:
            lines.append(f"- `{block}` + propriedade nova `{prop}` (padrão `{default}`; valores `{values}`)")
    lines.append("")
    return "\n".join(lines)


def command_inspect(args: argparse.Namespace) -> int:
    view = PackView(Path(args.pack))
    verify_manifest(view)
    print(render_inspect(view, load_model(view)))
    return 0


def section(title: str, added: list[str], removed: list[str], changed: list[str]) -> list[str]:
    lines = [f"## {title}", ""]
    if not (added or removed or changed):
        return lines + ["Sem alterações.", ""]
    for label, items in (("Novos", added), ("Removidos", removed), ("Alterados", changed)):
        if items:
            lines.append(f"**{label} ({len(items)}):**")
            lines.append("")
            lines.extend(f"- {item}" for item in items)
            lines.append("")
    return lines


def command_diff(args: argparse.Namespace) -> int:
    old_view, new_view = PackView(Path(args.old)), PackView(Path(args.new))
    verify_manifest(old_view)
    verify_manifest(new_view)
    old, new = load_model(old_view), load_model(new_view)
    lines = [
        f"# Diferenças `{old.meta['pack-id']}` → `{new.meta['pack-id']}`",
        "",
        f"NeoForge {old.meta['neoforge']} → {new.meta['neoforge']}; "
        f"mods {len(old.mods)} → {len(new.mods)}; canais {len(old.channels)} → {len(new.channels)}; "
        f"SERVER configs {len(old.configs)} → {len(new.configs)}; "
        f"registries {len(old.registries)} → {len(new.registries)}; "
        f"BlockStates globais {old.meta['block-states.global-count']} → {new.meta['block-states.global-count']}.",
        "",
    ]
    lines += section(
        "Mods",
        sorted(f"`{m}` {new.mods[m]}" for m in set(new.mods) - set(old.mods)),
        sorted(f"`{m}` {old.mods[m]}" for m in set(old.mods) - set(new.mods)),
        sorted(f"`{m}` {old.mods[m]} → {new.mods[m]}"
               for m in set(old.mods) & set(new.mods) if old.mods[m] != new.mods[m]),
    )
    lines += section(
        "Canais NeoForge (protocolo, canal: versão/fluxo/requisito)",
        sorted(f"{p} `{c}`: {'/'.join(new.channels[(p, c)])}"
               for p, c in set(new.channels) - set(old.channels)),
        sorted(f"{p} `{c}`: {'/'.join(old.channels[(p, c)])}"
               for p, c in set(old.channels) - set(new.channels)),
        sorted(f"{p} `{c}`: {'/'.join(old.channels[(p, c)])} → {'/'.join(new.channels[(p, c)])}"
               for p, c in set(old.channels) & set(new.channels)
               if old.channels[(p, c)] != new.channels[(p, c)]),
    )
    lines += section(
        "SERVER configs",
        sorted(f"`{c}`" for c in set(new.configs) - set(old.configs)),
        sorted(f"`{c}`" for c in set(old.configs) - set(new.configs)),
        sorted(f"`{c}` (conteúdo)" for c in set(old.configs) & set(new.configs)
               if old.configs[c] != new.configs[c]),
    )
    changed_registries = []
    for registry in sorted(set(old.registries) & set(new.registries)):
        before = [e for e, _ in old.registries[registry]]
        after = [e for e, _ in new.registries[registry]]
        if before != after:
            added = sorted(set(after) - set(before))
            removed = sorted(set(before) - set(after))
            detail = f"+{len(added)} −{len(removed)}"
            if added:
                detail += "; novos: " + ", ".join(f"`{e}`" for e in added[:25])
                detail += " …" if len(added) > 25 else ""
            if removed:
                detail += "; removidos: " + ", ".join(f"`{e}`" for e in removed[:25])
            if not added and not removed:
                detail += "; mesma lista, ordem diferente"
            changed_registries.append(f"`{registry}`: {detail}")
    lines += section(
        "Registries sincronizados",
        sorted(f"`{r}` ({len(new.registries[r])} entradas)" for r in set(new.registries) - set(old.registries)),
        sorted(f"`{r}`" for r in set(old.registries) - set(new.registries)),
        changed_registries,
    )
    lines += section(
        "Registries com tags",
        sorted(f"`{r}`" for r in set(new.tags) - set(old.tags)),
        sorted(f"`{r}`" for r in set(old.tags) - set(new.tags)),
        sorted(f"`{r}` ({len(old.tags[r])} → {len(new.tags[r])} tags)"
               for r in set(old.tags) & set(new.tags) if old.tags[r] != new.tags[r]),
    )
    lines += section(
        "Propriedades extras em blocos vanilla (afetam o mapa de BlockState)",
        sorted(f"`{b}` + `{p}`" for b, p in set(new.extra_properties) - set(old.extra_properties)),
        sorted(f"`{b}` + `{p}`" for b, p in set(old.extra_properties) - set(new.extra_properties)),
        sorted(f"`{b}` + `{p}`: {old.extra_properties[(b, p)]} → {new.extra_properties[(b, p)]}"
               for b, p in set(old.extra_properties) & set(new.extra_properties)
               if old.extra_properties[(b, p)] != new.extra_properties[(b, p)]),
    )
    old_count, old_targets = pobs_targets(old_view.read("block-states/block-state-map.bin"))
    new_count, new_targets = pobs_targets(new_view.read("block-states/block-state-map.bin"))
    changed_targets = sum(1 for a, b in zip(old_targets, new_targets) if a != b)
    bits = lambda count: max(1, (count - 1).bit_length())
    if changed_targets == 0:
        verdict = (f"Projeção vanilla → cliente idêntica nas {len(new_targets)} entradas. "
                   f"Total global {old_count} → {new_count}; bits da paleta global "
                   f"{bits(old_count)} → {bits(new_count)}.")
    else:
        verdict = (f"Projeção diferente em {changed_targets} de {len(new_targets)} estados vanilla: "
                   "o mapa antigo mostraria blocos trocados neste cliente.")
    lines += ["## Mapa de BlockState vanilla → cliente", "", verdict, ""]
    report = "\n".join(lines)
    if args.out:
        Path(args.out).write_text(report, encoding="utf-8")
        print(f"report written: {args.out}")
    else:
        print(report)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    compare = sub.add_parser("compare")
    compare.add_argument("a")
    compare.add_argument("b")
    compare.set_defaults(func=command_compare)
    pack = sub.add_parser("pack")
    pack.add_argument("capture")
    pack.add_argument("out")
    pack.add_argument("--boots", type=int, default=1)
    pack.set_defaults(func=command_pack)
    inspect = sub.add_parser("inspect")
    inspect.add_argument("pack")
    inspect.set_defaults(func=command_inspect)
    diff = sub.add_parser("diff")
    diff.add_argument("old")
    diff.add_argument("new")
    diff.add_argument("--out")
    diff.set_defaults(func=command_diff)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
