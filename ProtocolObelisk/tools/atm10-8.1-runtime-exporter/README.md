# ATM10 8.1 runtime exporter (build-only)

This standalone NeoForge mod produces the exact registry, tag and BlockState
evidence used to build the reviewed ProtocolObelisk ATM10 8.1 lobby profile. It
is a build tool, not a Velocity, Paper or backend runtime component. Never ship
the exporter JAR in a ProtocolObelisk release or leave it in a production
server's `mods/` directory.

## Trust boundary

The exporter accepts only a pure NeoForge 21.1.249 boot of the official ATM10
8.1 ServerFiles:

- CurseForge server file ID: `8764245`;
- ZIP byte size: `1205156753`;
- ZIP SHA-256:
  `259e4a98888ee6ded0b439113c19ac3c79f6e90eeab1a2c465a1c005d0d5f3c4`;
- Minecraft: `1.21.1`, protocol `767`;
- NeoForge: `21.1.249`.

The original ServerFiles ZIP path is mandatory at boot; size and SHA-256 are
verified before any output is written. Server brand/class, loaded mods,
classpath and known Youer/Mohist marker classes are inspected. Any Youer or
Mohist evidence aborts the export. Neither Youer nor any runtime it downloads
participates in this evidence or in the published first hop.

The bundled canonical vanilla table has exactly 26,684 dense source states. It
was mechanically derived from columns 1 and 3 of the reviewed ATM10 8.0 mapping;
the ATM10 8.0 target-ID column is absent. Its decompressed SHA-256 is
`de9980fae71fecd0112d77cd1c056815dca3a1e1125289efd08b1c0696f499bb`.

## Reproducible offline build

Use a JDK 21 and an already installed, physically pure NeoForge runtime:

```text
bash ./build-offline.sh /absolute/pure-atm10-8.1-root /absolute/jdk-21-root
```

The script compiles with `-Xlint:all -Werror -proc:none`, builds twice with fixed
ZIP timestamps, requires byte-identical JARs and publishes only the compared
result under `build/libs/`. The standalone Gradle project is also available:

```text
../../gradlew -p . -Patm10RuntimeRoot=/absolute/pure-atm10-8.1-root jar
```

Gradle may require its distribution to be present in the local wrapper cache;
`build-offline.sh` is the network-independent audited path.

## Disposable boot

Copy the built exporter JAR into `mods/` of a disposable copy of the exact
ServerFiles. The export destination must not exist. Preserve the original ZIP
outside the runtime directory and start the pure NeoForge server with both
properties:

```text
java \
  -Dprotocolobelisk.atm10_81.serverFiles=/absolute/ServerFiles-8.1.zip \
  -Dprotocolobelisk.atm10_81.exportOutput=/absolute/export-a \
  @libraries/net/neoforged/neoforge/21.1.249/unix_args.txt nogui
```

On `ServerStartedEvent`, after datapacks and registry baking, the exporter writes
to a staging directory, fixes every output mtime to the Unix epoch and requires
an atomic directory move. It halts the server on both success and failure. It
refuses to overwrite an existing export.

Perform two independent cold boots into `export-a` and `export-b`. Recursively
compare paths, sizes and SHA-256 hashes before integrating any bytes. A mismatch
is a release blocker; do not combine files from different boots.

## Export semantics

- `registry-data/wire-known-pack/` is one unfiltered, unsorted
  `RegistrySynchronization.packRegistries` sequence with exactly
  `minecraft:core:1.21.1` selected. Packet callback order, entry order and
  known-pack placeholders are preserved.
- `registry-data/self-contained/` is the same sequence with an empty known-pack
  set. Every entry must carry data. The exporter compares registry order, IDs and
  counts across both variants.
- `tags/full-update-tags.bin` is the complete live
  `TagNetworkSerialization` packet, without registry/tag filtering or sorting.
- `block-states/global-block-states.tsv` is the complete dense
  `Block.BLOCK_STATE_REGISTRY` table in that boot's runtime ID order. The hard
  upper bound is exactly `1 << 21`. This raw order is retained and hashed as
  diagnostic evidence, but it is explicitly not a reproducible profile identity:
  NeoForge may assign different IDs to modded-only states across equivalent boots.
- The stable profile identity uses
  `strip-runtime-id-sort-utf8-lf-v1`: validate dense runtime IDs, remove the ID
  field, require unique visible-ASCII descriptors, sort descriptor lines
  bytewise and hash each exact descriptor followed by LF. A bounded external
  merge sort avoids retaining the 1.5-million-state table in heap. The canonical
  descriptor-set count must still equal the dense global count.
- `block-states/minecraft-block-states.tsv` retains live numeric IDs for every
  `minecraft:*` state.
- `block-states/vanilla-to-atm10-8.1-block-states.tsv` projects each dense
  vanilla state into the live table. Properties added by mods are not guessed:
  their value is taken from the live block's `defaultBlockState()`.
- `block-states/block-state-map.bin` is the compact POBS v1 map consumed by the
  1.9.14 loader. Source IDs must be dense; target IDs must be unique and bounded.
  Target monotonicity is recorded as evidence but is not assumed.
- `server-configs/` plus `server-configs.properties` is the complete ordered
  NeoForge `ModConfig.Type.SERVER` transaction observed after server startup.
  Each `.bin` retains the exact `ConfigFilePayload` wire bytes, including TOML
  contents; the manifest records per-entry and aggregate hashes and byte counts.
  The production loader does not replace these bytes with empty/default guesses.
- `integration/` contains byte-identical stable copies and strict sidecars for
  the reviewed enchantment and BlockState loaders. The enchantment packet is the
  wire-known-pack form; the three crash sentinels must carry data.

`CanonicalBlockStateIntegrationAudit` applies that exact canonicalization to two
already-produced exporter outputs, validates their existing POBS files without
rewriting them and emits two independently constructed integration manifests.
The command fails unless the canonical descriptor evidence, POBS bytes and final
manifest bytes are identical:

```text
java -cp build/libs/protocolobelisk-atm10-8.1-runtime-exporter-1.0.0.jar \
  br.com.atmbrasil.protocolobelisk.fixture.CanonicalBlockStateIntegrationAudit \
  /absolute/out-a /absolute/out-b /absolute/canonical-schema-audit
```

The integration manifest contains only the reproducible descriptor-set hash,
global count/palette dimensions and the exact vanilla-to-runtime POBS map. Raw
runtime-order hashes remain in `block-states.properties` and `export.properties`
so a divergent boot is visible without falsely invalidating an identical
translation surface.

All manifests use explicit UTF-8 content without generated timestamps. Admission
or routing is never derived from these artifacts: missing or invalid evidence
must withhold translation, not deny a modded client's cardinal lobby route.
