# Build-only BlockState map compiler

This tool is deliberately outside the Velocity and Paper Gradle source sets. The comparator first
derives the reviewed, headerless TSV (`vanilla_id<TAB>client_id<TAB>canonical_state`) from two
independent state tables. The compiler then emits the bounded binary resource loaded by
ProtocolObelisk. Neither program contacts a proxy, routes a player, or ships any ATM10 ServerFiles
content.

The ATM10-side canonical table is exported by the offline profile generator after
`ServerStartedEvent`. The vanilla-side table comes from the official Minecraft 1.21.1 data
generator `reports/blocks.json`. The comparison must project only the exact extra properties proven
neutral for the exact 8.0 server pack; the reviewed 8.0 mapping TSV is retained here as audit input.

For the reviewed inputs, the vanilla table is a dense 26,684-line
`id<TAB>canonical_state` file. The ATM table starts with `format-version=1` and
`scope=minecraft-namespace`, followed by `global_id<TAB>canonical_state`. Its exact external
registry evidence is 1,980,659 global states and 45,481 `minecraft` states. Optional
`global-state-count` and `namespace-state-count` headers are checked when a newer generator emits
them; the command-line counts remain mandatory so an older two-header export is never accepted
without its independently recorded evidence.

Compile and run with Java 21:

```text
javac --release 21 -Xlint:all -Werror \
  BlockStateMapComparator.java BlockStateMapCompiler.java
java BlockStateMapComparator \
  /tmp/vanilla-1.21.1-block-states.tsv \
  ../../../serverfiles/blockstate-audit-beta11-boot1/minecraft-block-states.tsv \
  atm10-normal-8.0/vanilla-to-atm10-8.0-block-states.tsv \
  1980659 45481
java BlockStateMapCompiler \
  atm10-normal-8.0/vanilla-to-atm10-8.0-block-states.tsv \
  ../../velocity-plugin/src/main/resources/silentgear-profiles/atm10-normal-8.0/block-state-map.bin
```

The comparator validates strict UTF-8/LF syntax and complete EOF, dense and unique vanilla IDs,
unique/increasing/bounded ATM IDs, stable per-block schemas, the exact table counts, and exact
state correspondence. The only accepted ATM-only properties are the reviewed neutral defaults
`essentia_logged=false`, `boiling=false`, and `waterlogged=false`; their affected-block counts must
be exactly 337, 1, and 14, and both boolean domains must exist in the export. It derives the output
twice, compares the bytes, and publishes only by an atomic move.

Provenance for the beta.11 review:

- vanilla TSV: 2,353,080 bytes, SHA-256
  `de9980fae71fecd0112d77cd1c056815dca3a1e1125289efd08b1c0696f499bb`;
- ATM export: 4,931,283 bytes, SHA-256
  `ae1d2612f838f8b22f6bc2bd86f46d0cbab26566401f36b6496c53cb2592dc12`;
- derived reviewed TSV: 2,505,236 bytes, SHA-256
  `97a1dd540d706a09c4d59cae8fed0319534db2a385f9d09dc319072a9ca9f07f`.

The compiler separately validates dense source IDs, strictly increasing and bounded target IDs,
canonical state syntax, the exact 26,684-entry count, and atomic publication. Runtime loading
independently checks the manifest, bytes, SHA-256, header, bounds, density, uniqueness, and absence
of trailing bytes.
