package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds synthetic packs with exactly the layout tools/obelisk-capture produces. */
final class CompatibilityPackFixtures {
    static final String MAP_RESOURCE =
            "blockstate-profiles/atm10-normal-8.1-neoforge-21.1.249/block-state-map.bin";
    static final List<Channel> SERVER_CONFIGURATION = List.of(
            new Channel("neoforge:config_sync", "1", Flow.CLIENTBOUND, false),
            new Channel("testmod:optional_config", "2", Flow.CLIENTBOUND, true));
    static final List<Channel> SERVER_PLAY = List.of(
            new Channel("testmod:keys", "3", Flow.SERVERBOUND, false),
            new Channel("testmod:sync", "1", Flow.CLIENTBOUND, false),
            new Channel("testmod:optional_hud", "1", Flow.BIDIRECTIONAL, true));

    private CompatibilityPackFixtures() {
    }

    static byte[] pack(String packId) {
        return pack(packId, entries -> { });
    }

    /** Builds a pack; {@code tamper} may replace entries before the manifest is computed. */
    static byte[] pack(String packId, Consumer<Map<String, byte[]>> tamper) {
        return zip(entries(packId, tamper, true));
    }

    static byte[] packWithoutManifestUpdate(String packId, Consumer<Map<String, byte[]>> tamper) {
        Map<String, byte[]> entries = entries(packId, ignored -> { }, true);
        tamper.accept(entries);
        return zip(entries);
    }

    static Map<String, byte[]> entries(
            String packId, Consumer<Map<String, byte[]>> tamper, boolean withManifest) {
        TreeMap<String, byte[]> entries = new TreeMap<>();
        byte[] query = query(SERVER_CONFIGURATION, SERVER_PLAY);
        byte[] map = resource(MAP_RESOURCE);

        List<String> configNames = List.of("alpha-server.toml", "Nested/beta.toml");
        StringBuilder configs = new StringBuilder("format-version=2\n")
                .append("config.count=").append(configNames.size()).append('\n');
        MessageDigest names = sha();
        MessageDigest payloads = sha();
        for (int index = 0; index < configNames.size(); index++) {
            String name = configNames.get(index);
            byte[] contents = ("value = " + index + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] encoded = configPayload(name, contents);
            String file = "server-configs/%03d.bin".formatted(index);
            entries.put(file, encoded);
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            names.update(new byte[] {0, 0, 0, (byte) nameBytes.length});
            names.update(nameBytes);
            payloads.update(encoded);
            configs.append("config.").append(index).append(".name=").append(name).append('\n')
                    .append("config.").append(index).append(".file=").append(file).append('\n')
                    .append("config.").append(index).append(".content-sha256=")
                    .append(hex(sha().digest(contents))).append('\n')
                    .append("config.").append(index).append(".encoded-sha256=")
                    .append(hex(sha().digest(encoded))).append('\n');
        }
        String payloadSequence = hex(payloads.digest());
        configs.append("config.name-sequence-sha256=").append(hex(names.digest())).append('\n')
                .append("config.payload-sequence-sha256=").append(payloadSequence).append('\n');
        entries.put("server-configs.properties", utf8(configs.toString()));

        LinkedHashMap<String, byte[]> registries = new LinkedHashMap<>();
        registries.put("minecraft:enchantment", registry("minecraft:enchantment",
                "minecraft:sharpness", "testmod:shiny"));
        // plains is a vanilla override (Paper keeps its own); the other two are what vanilla lacks.
        registries.put("minecraft:worldgen/biome", registry("minecraft:worldgen/biome",
                "minecraft:plains", "testmod:glow_biome", "minecraft:custom_peaks"));
        registries.put("testmod:widgets", registry("testmod:widgets",
                "testmod:first", "testmod:second"));
        // A modded registry naming a modded biome, as Eternal Starlight's biome data does.
        registries.put("testmod:biome_data", rawRegistry("testmod:biome_data", List.of(
                new RawEntry("testmod:glow", Map.of("biome", "testmod:glow_biome")))));
        StringBuilder registryManifest = new StringBuilder("format-version=1\n")
                .append("variant=wire-known-pack\nknown-pack.count=1\n")
                .append("known-pack.0=minecraft:core:1.21.1\n")
                .append("packet.count=").append(registries.size()).append('\n');
        int index = 0;
        for (Map.Entry<String, byte[]> registry : registries.entrySet()) {
            String file = "%03d-%s.bin".formatted(index,
                    registry.getKey().replace(':', '_').replace('/', '_'));
            entries.put("registries/wire-known-pack/" + file, registry.getValue());
            int count = entryCount(registry.getValue());
            registryManifest.append("packet.").append(index).append(".registry=")
                    .append(registry.getKey()).append('\n')
                    .append("packet.").append(index).append(".file=").append(file).append('\n')
                    .append("packet.").append(index).append(".entries=").append(count).append('\n')
                    .append("packet.").append(index).append(".sha256=")
                    .append(hex(sha().digest(registry.getValue()))).append('\n');
            index++;
        }
        entries.put("registries/wire-known-pack.properties", utf8(registryManifest.toString()));
        entries.put("tags/full-update-tags.bin", tags(Map.of(
                "minecraft:block", Map.of("minecraft:mineable", new int[] {500}),
                "minecraft:enchantment", Map.of("minecraft:non_treasure", new int[] {0, 1}),
                "testmod:widgets", Map.of("testmod:all", new int[] {0, 1}))));

        String mapSha = hex(sha().digest(map));
        int globalCount = readInt(map, 9);
        entries.put("block-states/block-state-map.bin", map);
        entries.put("block-states/block-state-map.properties", utf8(
                "format-version=3\n"
                        + "profile-id=" + packId + "\n"
                        + "minecraft-version=1.21.1\n"
                        + "minecraft-protocol=767\n"
                        + "vanilla-state-table.count=26684\n"
                        + "vanilla-state-table.sha256="
                        + "de9980fae71fecd0112d77cd1c056815dca3a1e1125289efd08b1c0696f499bb\n"
                        + "client-global-state.count=" + globalCount + "\n"
                        + "client-state-descriptor-set.count=" + globalCount + "\n"
                        + "client-state-descriptor-set.canonicalization=strip-runtime-id-sort-utf8-lf-v1\n"
                        + "client-state-descriptor-set.sha256=" + "a".repeat(64) + "\n"
                        + "map.file=block-state-map.bin\n"
                        + "map.bytes=" + map.length + "\n"
                        + "map.sha256=" + mapSha + "\n"
                        + "map.count=26684\n"
                        + "map.maximum-target-id=27116\n"
                        + "mapping.target-ids-strictly-increasing=true\n"
                        + "source-global-palette.bits=15\n"
                        + "target-global-palette.bits=21\n"
                        + "rewrite-capabilities=BLOCK_UPDATE,CHUNK_BLOCK_STATES,BLOCK_LEVEL_EVENT,"
                        + "SECTION_BLOCKS_UPDATE\n"
                        + "packet.block-update=9\n"
                        + "packet.level-chunk-with-light=39\n"
                        + "packet.level-event=40\n"
                        + "packet.section-blocks-update=73\n"));
        entries.put("block-states/extra-vanilla-properties.tsv", utf8(
                "# block\tproperty\tdefault\tpossible-values\tkind\n"
                        + "minecraft:skeleton_skull\twaterlogged\tfalse\ttrue,false\tnew-property\n"));
        entries.put("network/server-query.bin", query);
        entries.put("mods.tsv", utf8("# modid\tversion\tdisplay-name\tfile\ntestmod\t1.0\tTest\ttest.jar\n"));
        entries.put("pack.properties", utf8("obelisk-pack-format=1\n"
                + "pack-id=" + packId + "\n"
                + "display-name=Synthetic " + packId + "\n"
                + "minecraft=1.21.1\n"
                + "minecraft-protocol=767\n"
                + "neoforge=21.1.251\n"
                + "runtime-kind=pure-neoforge\n"
                + "mods.count=1\n"
                + "network.channel-count=" + (SERVER_CONFIGURATION.size() + SERVER_PLAY.size()) + "\n"
                + "network.sha256=" + hex(sha().digest(query)) + "\n"
                + "server-configs.payload-sequence-sha256=" + payloadSequence + "\n"
                + "block-states.map-sha256=" + mapSha + "\n"));
        tamper.accept(entries);
        if (withManifest) {
            entries.remove("manifest.sha256");
            StringBuilder manifest = new StringBuilder();
            entries.forEach((name, data) ->
                    manifest.append(hex(sha().digest(data))).append("  ").append(name).append('\n'));
            entries.put("manifest.sha256", utf8(manifest.toString()));
        }
        return entries;
    }

    /** Encodes a NeoForge {@code neoforge:register} query exactly as a client sends it. */
    static byte[] query(List<Channel> configuration, List<Channel> play) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varInt(out, 2);
        writeProtocol(out, NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL, configuration);
        writeProtocol(out, NeoForgeHandshakeCodec.PLAY_PROTOCOL, play);
        return out.toByteArray();
    }

    private static void writeProtocol(ByteArrayOutputStream out, int protocol, List<Channel> channels) {
        varInt(out, protocol);
        varInt(out, channels.size());
        for (Channel channel : channels) {
            string(out, channel.id());
            string(out, channel.version());
            if (channel.flow() == Flow.BIDIRECTIONAL) {
                out.write(0);
            } else {
                out.write(1);
                varInt(out, channel.flow().wireOrdinal());
            }
            out.write(channel.optional() ? 1 : 0);
        }
    }

    static byte[] registry(String registryId, String... entryIds) {
        try {
            List<MinecraftRegistryPacketCodec.Entry> entries = java.util.Arrays.stream(entryIds)
                    .map(id -> new MinecraftRegistryPacketCodec.Entry(
                            id,
                            new MinecraftRegistryPacketCodec.CompoundTag(Map.of(
                                    "name", MinecraftRegistryPacketCodec.stringTag(id)))))
                    .toList();
            return MinecraftRegistryPacketCodec.encode(registryId, entries, 1_048_576);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** One registry entry; {@code null} data encodes a known-pack placeholder. */
    record RawEntry(String id, Map<String, String> data) {
    }

    /** Encodes a registry packet byte by byte, including placeholders the codec never writes. */
    static byte[] rawRegistry(String registryId, List<RawEntry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, registryId);
        varInt(out, entries.size());
        for (RawEntry entry : entries) {
            string(out, entry.id());
            if (entry.data() == null) {
                out.write(0);
                continue;
            }
            out.write(1);
            out.write(10);
            new TreeMap<>(entry.data()).forEach((name, value) -> {
                out.write(8);
                nbtString(out, name);
                nbtString(out, value);
            });
            out.write(0);
        }
        return out.toByteArray();
    }

    static int entryCount(byte[] registryPacket) {
        try {
            return MinecraftRegistryPacketCodec.inspect(registryPacket, 1_048_576).entryIds().size();
        } catch (Exception exception) {
            return 2;
        }
    }

    private static void nbtString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length >>> 8);
        out.write(bytes.length);
        out.writeBytes(bytes);
    }

    /** Replaces one captured registry file and keeps its manifest line consistent. */
    static void replaceRegistry(Map<String, byte[]> entries, String registryId, byte[] body) {
        String manifestName = "registries/wire-known-pack.properties";
        String manifest = new String(entries.get(manifestName), StandardCharsets.UTF_8);
        String prefix = null;
        for (String line : manifest.split("\n")) {
            if (line.endsWith(".registry=" + registryId)) {
                prefix = line.substring(0, line.indexOf(".registry="));
            }
        }
        if (prefix == null) {
            throw new IllegalArgumentException("fixture has no registry " + registryId);
        }
        String file = null;
        for (String line : manifest.split("\n")) {
            if (line.startsWith(prefix + ".file=")) {
                file = line.substring((prefix + ".file=").length());
            }
        }
        entries.put("registries/wire-known-pack/" + file, body);
        manifest = manifest.replaceFirst(java.util.regex.Pattern.quote(prefix) + "\\.sha256=[0-9a-f]{64}",
                prefix + ".sha256=" + hex(sha().digest(body)));
        manifest = manifest.replaceFirst(java.util.regex.Pattern.quote(prefix) + "\\.entries=[0-9]+",
                prefix + ".entries=" + entryCount(body));
        entries.put(manifestName, utf8(manifest));
    }

    static byte[] tags(Map<String, Map<String, int[]>> byRegistry) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varInt(out, byRegistry.size());
        new TreeMap<>(byRegistry).forEach((registry, tags) -> {
            string(out, registry);
            varInt(out, tags.size());
            new TreeMap<>(tags).forEach((tag, members) -> {
                string(out, tag);
                varInt(out, members.length);
                for (int member : members) {
                    varInt(out, member);
                }
            });
        });
        return out.toByteArray();
    }

    static byte[] configPayload(String name, byte[] contents) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        string(out, name);
        varInt(out, contents.length);
        out.writeBytes(contents);
        return out.toByteArray();
    }

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }

    static byte[] resource(String name) {
        try (InputStream input = CompatibilityPackFixtures.class.getClassLoader()
                .getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static void string(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
