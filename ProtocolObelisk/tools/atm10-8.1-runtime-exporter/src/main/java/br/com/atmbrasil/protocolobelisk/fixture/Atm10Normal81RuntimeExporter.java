package br.com.atmbrasil.protocolobelisk.fixture;

import com.mojang.serialization.DynamicOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.ConfigSync;
import net.neoforged.neoforge.network.payload.ConfigFilePayload;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Build-only exporter for the exact, pure NeoForge ATM10 8.1 runtime.
 *
 * <p>This mod is deliberately independent of every ATM10 mod API. It records
 * standard Minecraft/NeoForge registry and BlockState evidence only. It must
 * never be shipped with ProtocolObelisk and refuses to run on Youer/Mohist.</p>
 */
@Mod(Atm10Normal81RuntimeExporter.MOD_ID)
@EventBusSubscriber(modid = Atm10Normal81RuntimeExporter.MOD_ID)
public final class Atm10Normal81RuntimeExporter {
    public static final String MOD_ID = "protocolobelisk_atm10_81_exporter";

    private static final String OUTPUT_PROPERTY = "protocolobelisk.atm10_81.exportOutput";
    private static final String SERVER_FILES_PROPERTY = "protocolobelisk.atm10_81.serverFiles";
    private static final String SERVER_FILES_SHA256 =
            "259e4a98888ee6ded0b439113c19ac3c79f6e90eeab1a2c465a1c005d0d5f3c4";
    private static final long SERVER_FILES_BYTES = 1_205_156_753L;
    private static final String VANILLA_STATES_RESOURCE =
            "/protocolobelisk/atm10-8.1/vanilla-block-states-1.21.1.tsv.gz";
    private static final String VANILLA_STATES_GZIP_SHA256 =
            "dd050af1d54069cee2afbb4a81707c5360369981b0ef42bc0e63fd94c683374d";
    private static final String VANILLA_STATES_SHA256 =
            "de9980fae71fecd0112d77cd1c056815dca3a1e1125289efd08b1c0696f499bb";
    private static final String FULL_CLIENT_CONTRACT_SHA256 =
            "9d06683c97b68f2bf5093c15d35a95ab14e8dbde67c6c9e65509cdaebed607a3";
    private static final int VANILLA_STATE_COUNT = 26_684;
    private static final int MAXIMUM_GLOBAL_STATES = 1 << 21;
    private static final int MAXIMUM_REGISTRY_PACKETS = 1_024;
    private static final int MAXIMUM_REGISTRY_ENTRIES = 2_000_000;
    private static final int MAXIMUM_PACKET_BYTES = 64 * 1024 * 1024;
    private static final int MAXIMUM_SERVER_CONFIGS = 1_024;
    private static final int MAXIMUM_CONFIG_NAME_UTF8_BYTES = 128;
    private static final int MAXIMUM_CONFIG_CONTENT_BYTES = 16 * 1024 * 1024;
    private static final int MAXIMUM_TOTAL_CONFIG_CONTENT_BYTES = 256 * 1024 * 1024;
    private static final Pattern SAFE_RELATIVE_TOML_PATH = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_.-]*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)*\\.toml");
    private static final FileTime DETERMINISTIC_TIMESTAMP = FileTime.from(Instant.EPOCH);
    private static final ResourceLocation ENCHANTMENT_REGISTRY =
            ResourceLocation.parse("minecraft:enchantment");
    private static final KnownPack VANILLA_KNOWN_PACK =
            new KnownPack("minecraft", "core", "1.21.1");
    private static final List<ResourceLocation> REQUIRED_ENCHANTMENTS = List.of(
            ResourceLocation.parse("evilcraft:vengeance"),
            ResourceLocation.parse("undergarden:ricochet"),
            ResourceLocation.parse("quarryplus:quarry_pickaxe")
    );

    public Atm10Normal81RuntimeExporter() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            export(server);
        } catch (Throwable failure) {
            System.err.println("[ProtocolObelisk ATM10 8.1 exporter] FAILED: " + failure);
            failure.printStackTrace(System.err);
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("ATM10 8.1 runtime export failed", failure);
        } finally {
            server.halt(false);
        }
    }

    private static void export(MinecraftServer server) throws IOException {
        rejectHybridRuntime(server);
        Path serverFiles = requiredPath(SERVER_FILES_PROPERTY).toRealPath();
        Path output = requiredPath(OUTPUT_PROPERTY);
        validateServerFiles(serverFiles);
        if (Files.exists(output)) {
            throw new IllegalStateException("Refusing to replace existing export " + output);
        }
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalStateException("Export output must have a parent directory");
        }
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + output.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectory(staging);
        boolean published = false;
        try {
            VanillaSource vanillaSource = loadVanillaSource();
            LayeredRegistryAccess<RegistryLayer> layeredRegistries = server.registries();
            List<KnownPack> wireKnownPacks = discoverWireKnownPacks(server);

            RegistryExport wire = exportRegistries(
                    staging,
                    layeredRegistries,
                    "wire-known-pack",
                    wireKnownPacks,
                    Collections.unmodifiableSet(new LinkedHashSet<>(wireKnownPacks))
            );
            RegistryExport selfContained = exportRegistries(
                    staging,
                    layeredRegistries,
                    "self-contained",
                    List.of(),
                    Set.of()
            );
            validateRegistryVariants(wire, selfContained);
            TagsExport tags = exportFullTags(staging, layeredRegistries);
            ServerConfigsExport serverConfigs = exportServerConfigs(staging);
            BlockStatesExport blockStates = exportBlockStates(staging, vanillaSource);
            exportStableEnchantmentPacket(staging, wire);
            writeMainManifest(
                    staging,
                    server,
                    serverFiles,
                    vanillaSource,
                    wire,
                    selfContained,
                    tags,
                    serverConfigs,
                    blockStates
            );
            normalizeTimestamps(staging);
            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException(
                        "Filesystem does not support atomic directory publication", exception);
            }
            published = true;
            System.out.println("[ProtocolObelisk ATM10 8.1 exporter] SUCCESS output=" + output);
            System.out.println("[ProtocolObelisk ATM10 8.1 exporter] wire packets="
                    + wire.packets().size() + " entries=" + wire.totalEntries()
                    + " sha256=" + wire.sequenceSha256());
            System.out.println("[ProtocolObelisk ATM10 8.1 exporter] self-contained packets="
                    + selfContained.packets().size() + " entries="
                    + selfContained.totalEntries() + " sha256="
                    + selfContained.sequenceSha256());
            System.out.println("[ProtocolObelisk ATM10 8.1 exporter] tags registries="
                    + tags.registryCount() + " tags=" + tags.tagCount()
                    + " sha256=" + tags.sha256());
            System.out.println("[ProtocolObelisk ATM10 8.1 exporter] server configs="
                    + serverConfigs.configs().size() + " contentBytes="
                    + serverConfigs.totalContentBytes() + " nameSequenceSha256="
                    + serverConfigs.nameSequenceSha256() + " payloadSequenceSha256="
                    + serverConfigs.payloadSequenceSha256());
            System.out.println("[ProtocolObelisk ATM10 8.1 exporter] block states global="
                    + blockStates.globalCount() + " minecraft=" + blockStates.minecraftCount()
                    + " mapped=" + blockStates.mappingCount() + " extraProperties="
                    + blockStates.extraPropertyCount());
        } finally {
            if (!published) {
                deleteTreeBestEffort(staging);
            }
        }
    }

    private static Path requiredPath(String propertyName) {
        String value = System.getProperty(propertyName, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Missing required -D" + propertyName + "=<path>");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void validateServerFiles(Path serverFiles) throws IOException {
        if (!Files.isRegularFile(serverFiles)) {
            throw new IllegalStateException("ServerFiles input is not a regular file: " + serverFiles);
        }
        long size = Files.size(serverFiles);
        if (size != SERVER_FILES_BYTES) {
            throw new IllegalStateException("Unexpected ServerFiles byte size: " + size
                    + " (expected " + SERVER_FILES_BYTES + ")");
        }
        String hash = sha256(serverFiles);
        if (!SERVER_FILES_SHA256.equals(hash)) {
            throw new IllegalStateException("Unexpected ServerFiles SHA-256: " + hash);
        }
    }

    private static void rejectHybridRuntime(MinecraftServer server) {
        List<String> evidence = new ArrayList<>();
        checkHybridToken("server brand", server.getServerModName(), evidence);
        checkHybridToken("server class", server.getClass().getName(), evidence);
        checkHybridToken("java.class.path", System.getProperty("java.class.path", ""), evidence);
        for (var mod : ModList.get().getMods()) {
            checkHybridToken("mod id", mod.getModId(), evidence);
            checkHybridToken("mod display name", mod.getDisplayName(), evidence);
        }
        for (String marker : List.of(
                "com.mohistmc.launcher.youer.Main",
                "com.mohistmc.youer.Youer",
                "com.mohistmc.MohistMC"
        )) {
            try {
                Class.forName(marker, false, Atm10Normal81RuntimeExporter.class.getClassLoader());
                evidence.add("loaded class " + marker);
            } catch (ClassNotFoundException ignored) {
                // Expected on the required pure NeoForge runtime.
            } catch (LinkageError error) {
                evidence.add("linkable class " + marker + " (" + error.getClass().getSimpleName() + ")");
            }
        }
        if (!evidence.isEmpty()) {
            throw new IllegalStateException("Youer/Mohist runtime is forbidden for this export: "
                    + String.join("; ", evidence));
        }
        String brand = server.getServerModName().toLowerCase(Locale.ROOT);
        if (!brand.contains("neoforge")) {
            throw new IllegalStateException("Expected a pure NeoForge server brand, found: "
                    + server.getServerModName());
        }
    }

    private static void checkHybridToken(String source, String value, List<String> evidence) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("youer") || normalized.contains("mohist")) {
            evidence.add(source + "=" + value);
        }
    }

    private static List<KnownPack> discoverWireKnownPacks(MinecraftServer server) {
        int exactVanillaOccurrences = 0;
        List<PackResources> activePacks = server.getResourceManager().listPacks().toList();
        for (PackResources packResources : activePacks) {
            Optional<KnownPack> known = packResources.knownPackInfo();
            if (known.isEmpty()) {
                continue;
            }
            KnownPack value = known.orElseThrow();
            if (value.isVanilla()) {
                if (!VANILLA_KNOWN_PACK.equals(value)) {
                    throw new IllegalStateException("Unexpected vanilla known pack " + value);
                }
                exactVanillaOccurrences++;
            }
        }
        if (exactVanillaOccurrences != 1) {
            throw new IllegalStateException("Expected exactly one active minecraft:core:1.21.1, found "
                    + exactVanillaOccurrences);
        }
        // This is the exact set a vanilla Paper first hop negotiates on wire.
        // Mod-owned known packs are intentionally ignored here; the independent
        // self-contained variant below supplies all data with an empty set.
        return List.of(VANILLA_KNOWN_PACK);
    }

    private static RegistryExport exportRegistries(
            Path staging,
            LayeredRegistryAccess<RegistryLayer> layeredRegistries,
            String variant,
            List<KnownPack> manifestKnownPacks,
            Set<KnownPack> codecKnownPacks
    ) throws IOException {
        Path directory = staging.resolve("registry-data").resolve(variant);
        Files.createDirectories(directory);
        RegistryAccess.Frozen composite = layeredRegistries.compositeAccess();
        DynamicOps<Tag> ops = composite.createSerializationContext(NbtOps.INSTANCE);
        RegistryAccess worldgen = layeredRegistries.getAccessFrom(RegistryLayer.WORLDGEN);
        List<RegistryPacket> packets = new ArrayList<>();
        RegistrySynchronization.packRegistries(
                ops,
                worldgen,
                codecKnownPacks,
                (registryKey, packedEntries) -> {
                    int index = packets.size();
                    if (index >= MAXIMUM_REGISTRY_PACKETS) {
                        throw new IllegalStateException("Registry packet count exceeds bound");
                    }
                    List<RegistrySynchronization.PackedRegistryEntry> entries =
                            List.copyOf(packedEntries);
                    if ("self-contained".equals(variant)
                            && entries.stream().anyMatch(entry -> entry.data().isEmpty())) {
                        throw new IllegalStateException("Self-contained registry has omitted data: "
                                + registryKey.location());
                    }
                    ClientboundRegistryDataPacket packet =
                            new ClientboundRegistryDataPacket(registryKey, entries);
                    byte[] bytes = encodeDeterministically(
                            ClientboundRegistryDataPacket.STREAM_CODEC,
                            packet,
                            "registry " + variant + " / " + registryKey.location()
                    );
                    validateRegistryRoundTrip(packet, bytes);
                    if (bytes.length > MAXIMUM_PACKET_BYTES) {
                        throw new IllegalStateException("Registry packet exceeds byte bound: "
                                + registryKey.location() + " / " + bytes.length);
                    }
                    String safeName = safeName(registryKey.location());
                    String fileName = "%03d-%s.bin".formatted(index, safeName);
                    packets.add(new RegistryPacket(
                            index,
                            registryKey,
                            fileName,
                            entries,
                            bytes,
                            sha256(bytes),
                            entrySequenceSha256(entries)
                    ));
                }
        );
        if (packets.isEmpty()) {
            throw new IllegalStateException("RegistrySynchronization emitted no packets for " + variant);
        }
        int totalEntries = Math.addExact(0, packets.stream()
                .mapToInt(packet -> packet.entries().size()).sum());
        int encodedEntries = Math.addExact(0, packets.stream()
                .mapToInt(packet -> (int) packet.entries().stream()
                        .filter(entry -> entry.data().isPresent()).count()).sum());
        if (totalEntries <= 0 || totalEntries > MAXIMUM_REGISTRY_ENTRIES) {
            throw new IllegalStateException("Registry entry count violates bound: " + totalEntries);
        }
        for (RegistryPacket packet : packets) {
            writeBytes(directory.resolve(packet.fileName()), packet.bytes());
        }
        String sequenceHash = sequenceSha256(packets.stream().map(RegistryPacket::bytes));
        RegistryExport export = new RegistryExport(
                variant,
                manifestKnownPacks,
                List.copyOf(packets),
                totalEntries,
                encodedEntries,
                sequenceHash
        );
        writeString(staging.resolve("registry-data-" + variant + ".properties"),
                registryManifest(export));
        return export;
    }

    private static void validateRegistryRoundTrip(
            ClientboundRegistryDataPacket source,
            byte[] bytes
    ) {
        ClientboundRegistryDataPacket decoded = decode(
                ClientboundRegistryDataPacket.STREAM_CODEC, bytes);
        if (!source.registry().equals(decoded.registry())
                || source.entries().size() != decoded.entries().size()) {
            throw new IllegalStateException("Registry packet changed shape after decode: "
                    + source.registry().location());
        }
        for (int index = 0; index < source.entries().size(); index++) {
            RegistrySynchronization.PackedRegistryEntry before = source.entries().get(index);
            RegistrySynchronization.PackedRegistryEntry after = decoded.entries().get(index);
            if (!before.id().equals(after.id()) || !before.data().equals(after.data())) {
                throw new IllegalStateException("Registry entry changed at index " + index
                        + " in " + source.registry().location());
            }
        }
    }

    private static void validateRegistryVariants(
            RegistryExport wire,
            RegistryExport selfContained
    ) {
        if (!"wire-known-pack".equals(wire.variant())
                || !"self-contained".equals(selfContained.variant())) {
            throw new IllegalStateException("Unexpected registry export variants");
        }
        if (!wire.knownPacks().equals(List.of(VANILLA_KNOWN_PACK))
                || !selfContained.knownPacks().isEmpty()) {
            throw new IllegalStateException("Registry known-pack sets violate the reviewed contract");
        }
        if (wire.packets().size() != selfContained.packets().size()
                || wire.totalEntries() != selfContained.totalEntries()) {
            throw new IllegalStateException("Registry variants differ in packet/entry counts");
        }
        for (int packetIndex = 0; packetIndex < wire.packets().size(); packetIndex++) {
            RegistryPacket wirePacket = wire.packets().get(packetIndex);
            RegistryPacket fullPacket = selfContained.packets().get(packetIndex);
            if (wirePacket.index() != packetIndex || fullPacket.index() != packetIndex
                    || !wirePacket.registryKey().equals(fullPacket.registryKey())
                    || wirePacket.entries().size() != fullPacket.entries().size()) {
                throw new IllegalStateException("Registry variants diverge at packet " + packetIndex);
            }
            for (int entryIndex = 0; entryIndex < wirePacket.entries().size(); entryIndex++) {
                RegistrySynchronization.PackedRegistryEntry wireEntry =
                        wirePacket.entries().get(entryIndex);
                RegistrySynchronization.PackedRegistryEntry fullEntry =
                        fullPacket.entries().get(entryIndex);
                if (!wireEntry.id().equals(fullEntry.id())) {
                    throw new IllegalStateException("Registry variants diverge at packet/entry "
                            + packetIndex + "/" + entryIndex);
                }
                if (fullEntry.data().isEmpty()) {
                    throw new IllegalStateException("Self-contained registry has a placeholder at "
                            + packetIndex + "/" + entryIndex);
                }
                if (wireEntry.data().isPresent()
                        && !wireEntry.data().equals(fullEntry.data())) {
                    throw new IllegalStateException("Wire registry data differs from self-contained data at "
                            + packetIndex + "/" + entryIndex);
                }
            }
        }
    }

    /**
     * Captures the exact SERVER-config registry exposed by NeoForge after every ATM10 mod has
     * registered and the dedicated server has reached STARTED. The exact ordered payload bytes are
     * retained as build evidence and embedded by ProtocolObelisk for this exact pack contract.
     * Sending the captured contents is intentional: custom specs may require values that cannot be
     * reconstructed safely from an empty TOML payload on the client.
     */
    private static ServerConfigsExport exportServerConfigs(Path staging) throws IOException {
        List<ConfigFilePayload> payloads = ConfigSync.syncConfigs().stream()
                .sorted(Comparator.comparing(ConfigFilePayload::fileName))
                .toList();
        if (payloads.isEmpty()) {
            throw new IllegalStateException("NeoForge resolved no SERVER configs");
        }
        if (payloads.size() > MAXIMUM_SERVER_CONFIGS) {
            throw new IllegalStateException("SERVER config count exceeds bound: "
                    + payloads.size());
        }

        Path directory = staging.resolve("server-configs");
        Files.createDirectories(directory);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        List<ServerConfigEvidence> configs = new ArrayList<>(payloads.size());
        long totalContentBytes = 0;
        long totalPayloadBytes = 0;
        for (int index = 0; index < payloads.size(); index++) {
            ConfigFilePayload payload = payloads.get(index);
            String fileName = payload.fileName();
            requireSafeConfigFileName(fileName);
            if (!names.add(fileName)) {
                throw new IllegalStateException("Duplicate SERVER config " + fileName);
            }
            byte[] contents = payload.contents();
            if (contents.length > MAXIMUM_CONFIG_CONTENT_BYTES) {
                throw new IllegalStateException("SERVER config content exceeds bound: "
                        + fileName + " / " + contents.length);
            }
            totalContentBytes = Math.addExact(totalContentBytes, contents.length);
            if (totalContentBytes > MAXIMUM_TOTAL_CONFIG_CONTENT_BYTES) {
                throw new IllegalStateException("Total SERVER config content exceeds bound: "
                        + totalContentBytes);
            }

            byte[] encoded = encodeDeterministically(
                    ConfigFilePayload.STREAM_CODEC, payload, "SERVER config " + fileName);
            ConfigFilePayload decoded = decode(ConfigFilePayload.STREAM_CODEC, encoded);
            if (!fileName.equals(decoded.fileName())
                    || !MessageDigest.isEqual(contents, decoded.contents())) {
                throw new IllegalStateException("SERVER config changed after decode: " + fileName);
            }
            byte[] roundTrip = encodeDeterministically(
                    ConfigFilePayload.STREAM_CODEC, decoded,
                    "decoded SERVER config " + fileName);
            if (!MessageDigest.isEqual(encoded, roundTrip)) {
                throw new IllegalStateException(
                        "SERVER config changed after decode/re-encode: " + fileName);
            }

            String encodedFile = "%03d.bin".formatted(index);
            writeBytes(directory.resolve(encodedFile), encoded);
            totalPayloadBytes = Math.addExact(totalPayloadBytes, encoded.length);
            configs.add(new ServerConfigEvidence(
                    fileName,
                    "server-configs/" + encodedFile,
                    contents.length,
                    sha256(contents),
                    encoded.length,
                    sha256(encoded),
                    encoded));
        }

        String nameSequenceSha256 = serverConfigNameSequenceSha256(configs);
        String payloadSequenceSha256 = sequenceSha256(
                configs.stream().map(ServerConfigEvidence::encodedPayload));
        ServerConfigsExport export = new ServerConfigsExport(
                List.copyOf(configs),
                totalContentBytes,
                totalPayloadBytes,
                nameSequenceSha256,
                payloadSequenceSha256);
        writeString(staging.resolve("server-configs.properties"), serverConfigManifest(export));
        return export;
    }

    private static void requireSafeConfigFileName(String fileName) {
        if (fileName == null
                || fileName.isEmpty()
                || fileName.length() > MAXIMUM_CONFIG_NAME_UTF8_BYTES
                || fileName.getBytes(StandardCharsets.UTF_8).length
                        > MAXIMUM_CONFIG_NAME_UTF8_BYTES
                || fileName.contains("..")
                || fileName.indexOf('\\') >= 0
                || fileName.startsWith("/")
                || fileName.endsWith("/")
                || fileName.contains("//")
                || !SAFE_RELATIVE_TOML_PATH.matcher(fileName).matches()) {
            throw new IllegalStateException("Unsafe SERVER config filename " + fileName);
        }
    }

    private static String serverConfigManifest(ServerConfigsExport export) {
        StringBuilder manifest = new StringBuilder("format-version=2\n")
                .append("pack=ATM10-8.1\n")
                .append("minecraft=1.21.1\n")
                .append("neoforge=21.1.249\n")
                .append("full-client-contract-sha256=")
                .append(FULL_CLIENT_CONTRACT_SHA256).append('\n')
                .append("config.count=").append(export.configs().size()).append('\n')
                .append("config.total-content-bytes=")
                .append(export.totalContentBytes()).append('\n')
                .append("config.total-encoded-bytes=")
                .append(export.totalPayloadBytes()).append('\n')
                .append("config.name-sequence-sha256=")
                .append(export.nameSequenceSha256()).append('\n')
                .append("config.payload-sequence-sha256=")
                .append(export.payloadSequenceSha256()).append('\n');
        for (int index = 0; index < export.configs().size(); index++) {
            ServerConfigEvidence config = export.configs().get(index);
            String key = "config." + index;
            manifest.append(key).append(".name=").append(config.configName()).append('\n')
                    .append(key).append(".file=").append(config.encodedFile()).append('\n')
                    .append(key).append(".content-bytes=")
                    .append(config.contentBytes()).append('\n')
                    .append(key).append(".content-sha256=")
                    .append(config.contentSha256()).append('\n')
                    .append(key).append(".encoded-bytes=")
                    .append(config.encodedBytes()).append('\n')
                    .append(key).append(".encoded-sha256=")
                    .append(config.encodedSha256()).append('\n');
        }
        return manifest.toString();
    }

    private static String serverConfigNameSequenceSha256(
            List<ServerConfigEvidence> configs) {
        MessageDigest digest = sha256Digest();
        for (ServerConfigEvidence config : configs) {
            byte[] name = config.configName().getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (name.length >>> 24));
            digest.update((byte) (name.length >>> 16));
            digest.update((byte) (name.length >>> 8));
            digest.update((byte) name.length);
            digest.update(name);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String registryManifest(RegistryExport export) {
        StringBuilder manifest = new StringBuilder()
                .append("format-version=1\n")
                .append("pack=ATM10-8.1\n")
                .append("minecraft=1.21.1\n")
                .append("minecraft-protocol=767\n")
                .append("neoforge=21.1.249\n")
                .append("variant=").append(export.variant()).append('\n')
                .append("ordering=RegistrySynchronization.packRegistries-callback-and-entry-order\n")
                .append("filtering=none\n")
                .append("sorting=none\n")
                .append("known-pack.count=").append(export.knownPacks().size()).append('\n');
        for (int index = 0; index < export.knownPacks().size(); index++) {
            KnownPack pack = export.knownPacks().get(index);
            manifest.append("known-pack.").append(index).append('=')
                    .append(pack.namespace()).append(':').append(pack.id()).append(':')
                    .append(pack.version()).append('\n');
        }
        manifest.append("packet.count=").append(export.packets().size()).append('\n')
                .append("packet.total-entries=").append(export.totalEntries()).append('\n')
                .append("packet.total-encoded-data-entries=")
                .append(export.encodedEntries()).append('\n')
                .append("packet.total-bytes=").append(export.packets().stream()
                        .mapToLong(packet -> packet.bytes().length).sum()).append('\n')
                .append("packet.sequence-sha256=").append(export.sequenceSha256()).append('\n');
        for (RegistryPacket packet : export.packets()) {
            String key = "packet." + packet.index();
            manifest.append(key).append(".registry=")
                    .append(packet.registryKey().location()).append('\n')
                    .append(key).append(".file=registry-data/")
                    .append(export.variant()).append('/').append(packet.fileName()).append('\n')
                    .append(key).append(".entries=").append(packet.entries().size()).append('\n')
                    .append(key).append(".encoded-data-entries=")
                    .append(packet.entries().stream().filter(entry -> entry.data().isPresent()).count())
                    .append('\n')
                    .append(key).append(".entry-sequence-sha256=")
                    .append(packet.entrySequenceSha256()).append('\n')
                    .append(key).append(".bytes=").append(packet.bytes().length).append('\n')
                    .append(key).append(".sha256=").append(packet.sha256()).append('\n');
        }
        return manifest.toString();
    }

    private static TagsExport exportFullTags(
            Path staging,
            LayeredRegistryAccess<RegistryLayer> layeredRegistries
    ) throws IOException {
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tagMap =
                TagNetworkSerialization.serializeTagsToNetwork(layeredRegistries);
        if (tagMap.isEmpty()) {
            throw new IllegalStateException("Full tag serialization returned an empty map");
        }
        ClientboundUpdateTagsPacket packet = new ClientboundUpdateTagsPacket(tagMap);
        byte[] bytes = encodeDeterministically(
                ClientboundUpdateTagsPacket.STREAM_CODEC, packet, "full update-tags packet");
        if (bytes.length <= 0 || bytes.length > MAXIMUM_PACKET_BYTES) {
            throw new IllegalStateException("Full tags packet violates byte bound: " + bytes.length);
        }
        ClientboundUpdateTagsPacket decoded = decode(ClientboundUpdateTagsPacket.STREAM_CODEC, bytes);
        List<ResourceKey<? extends Registry<?>>> sourceKeys = new ArrayList<>(tagMap.keySet());
        List<ResourceKey<? extends Registry<?>>> decodedKeys =
                new ArrayList<>(decoded.getTags().keySet());
        if (sourceKeys.size() != decodedKeys.size()
                || !new HashSet<>(sourceKeys).equals(new HashSet<>(decodedKeys))) {
            throw new IllegalStateException("Full tags registry set changed after decode");
        }
        int tagCount = 0;
        for (ResourceKey<? extends Registry<?>> key : sourceKeys) {
            TagNetworkSerialization.NetworkPayload before = tagMap.get(key);
            TagNetworkSerialization.NetworkPayload after = decoded.getTags().get(key);
            if (before == null || after == null || before.size() != after.size()) {
                throw new IllegalStateException("Full tags changed for registry " + key.location());
            }
            tagCount = Math.addExact(tagCount, before.size());
        }
        Path packetPath = staging.resolve("tags/full-update-tags.bin");
        writeBytes(packetPath, bytes);
        StringBuilder manifest = new StringBuilder()
                .append("format-version=1\n")
                .append("pack=ATM10-8.1\n")
                .append("minecraft=1.21.1\n")
                .append("minecraft-protocol=767\n")
                .append("neoforge=21.1.249\n")
                .append("scope=full-live-TagNetworkSerialization-output\n")
                .append("filtering=none\n")
                .append("sorting=none\n")
                .append("registry.count=").append(sourceKeys.size()).append('\n')
                .append("tag.count=").append(tagCount).append('\n')
                .append("file=tags/full-update-tags.bin\n")
                .append("bytes=").append(bytes.length).append('\n')
                .append("sha256=").append(sha256(bytes)).append('\n');
        for (int index = 0; index < sourceKeys.size(); index++) {
            ResourceKey<? extends Registry<?>> key = sourceKeys.get(index);
            manifest.append("registry.").append(index).append(".name=")
                    .append(key.location()).append('\n')
                    .append("registry.").append(index).append(".tags=")
                    .append(tagMap.get(key).size()).append('\n');
        }
        writeString(staging.resolve("tags/full-update-tags.properties"), manifest.toString());
        return new TagsExport(sourceKeys.size(), tagCount, bytes.length, sha256(bytes));
    }

    private static BlockStatesExport exportBlockStates(
            Path staging,
            VanillaSource vanillaSource
    ) throws IOException {
        Path directory = staging.resolve("block-states");
        Files.createDirectories(directory);
        Path globalPath = directory.resolve("global-block-states.tsv");
        Path minecraftPath = directory.resolve("minecraft-block-states.tsv");
        int globalCount = Block.BLOCK_STATE_REGISTRY.size();
        if (globalCount <= 0 || globalCount > MAXIMUM_GLOBAL_STATES) {
            throw new IllegalStateException("Global BlockState count violates bound: " + globalCount);
        }
        int minecraftCount = 0;
        Map<Integer, String> minecraftByGlobalId = new HashMap<>();
        Set<String> minecraftCanonicalStates = new HashSet<>();
        try (BufferedWriter global = Files.newBufferedWriter(
                    globalPath, StandardCharsets.UTF_8);
                BufferedWriter minecraft = Files.newBufferedWriter(
                    minecraftPath, StandardCharsets.UTF_8)) {
            for (int id = 0; id < globalCount; id++) {
                BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
                if (state == null || Block.getId(state) != id) {
                    throw new IllegalStateException("Global BlockState table is sparse at " + id);
                }
                String canonical = canonicalState(state);
                writeTsvLine(global, Integer.toString(id), canonical);
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (blockId == null) {
                    throw new IllegalStateException("Unregistered block at global state " + id);
                }
                if ("minecraft".equals(blockId.getNamespace())) {
                    if (!minecraftCanonicalStates.add(canonical)) {
                        throw new IllegalStateException("Duplicate live minecraft state " + canonical);
                    }
                    minecraftByGlobalId.put(id, canonical);
                    writeTsvLine(minecraft, Integer.toString(id), canonical);
                    minecraftCount++;
                }
            }
        }

        Path sourcePath = directory.resolve("vanilla-source-block-states-1.21.1.tsv");
        writeBytes(sourcePath, vanillaSource.uncompressedBytes());
        Path mapPath = directory.resolve("vanilla-to-atm10-8.1-block-states.tsv");
        Path extraPath = directory.resolve("live-extra-properties.tsv");
        Set<Integer> usedTargets = new HashSet<>();
        int[] mappingTargets = new int[VANILLA_STATE_COUNT];
        LinkedHashMap<ResourceLocation, Set<String>> vanillaPropertyNamesByBlock =
                vanillaPropertiesByBlock(vanillaSource.states());
        int previousTarget = -1;
        int maximumTarget = -1;
        boolean targetsStrictlyIncreasing = true;
        int extraCount = 0;
        try (BufferedWriter mapping = Files.newBufferedWriter(mapPath, StandardCharsets.UTF_8);
                BufferedWriter extras = Files.newBufferedWriter(extraPath, StandardCharsets.UTF_8)) {
            Set<ResourceLocation> describedExtras = new HashSet<>();
            for (CanonicalSource source : vanillaSource.states()) {
                Block block = BuiltInRegistries.BLOCK.getOptional(source.blockId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Live registry lacks vanilla block " + source.blockId()));
                ResourceLocation registeredId = BuiltInRegistries.BLOCK.getKey(block);
                if (!source.blockId().equals(registeredId)) {
                    throw new IllegalStateException("Vanilla block lookup changed identity: "
                            + source.blockId() + " -> " + registeredId);
                }
                BlockState target = block.defaultBlockState();
                Set<String> sourcePropertyNames = vanillaPropertyNamesByBlock.get(source.blockId());
                if (describedExtras.add(source.blockId())) {
                    List<Property<?>> extraProperties = block.getStateDefinition().getProperties().stream()
                            .filter(property -> !sourcePropertyNames.contains(property.getName()))
                            .sorted(Comparator.comparing(Property::getName))
                            .toList();
                    for (Property<?> property : extraProperties) {
                        writeTsvLine(
                                extras,
                                source.blockId().toString(),
                                property.getName(),
                                propertyValueName(block.defaultBlockState(), property)
                        );
                        extraCount++;
                    }
                }
                for (Map.Entry<String, String> propertyValue : source.properties().entrySet()) {
                    Property<?> liveProperty = block.getStateDefinition()
                            .getProperty(propertyValue.getKey());
                    if (liveProperty == null) {
                        throw new IllegalStateException("Live block " + source.blockId()
                                + " lacks vanilla property " + propertyValue.getKey());
                    }
                    target = setProperty(target, liveProperty, propertyValue.getValue());
                }
                for (Property<?> liveProperty : block.getStateDefinition().getProperties()) {
                    if (!sourcePropertyNames.contains(liveProperty.getName())) {
                        String actual = propertyValueName(target, liveProperty);
                        String expected = propertyValueName(block.defaultBlockState(), liveProperty);
                        if (!actual.equals(expected)) {
                            throw new IllegalStateException("Extra live property did not retain default: "
                                    + source.blockId() + " / " + liveProperty.getName());
                        }
                    }
                }
                int targetId = Block.getId(target);
                if (targetId < 0 || targetId >= globalCount
                        || Block.BLOCK_STATE_REGISTRY.byId(targetId) != target) {
                    throw new IllegalStateException("Projected target is not in the dense global table: "
                            + source.canonical());
                }
                if (!usedTargets.add(targetId)) {
                    throw new IllegalStateException("Two vanilla source states map to target " + targetId);
                }
                if (targetId <= previousTarget) {
                    targetsStrictlyIncreasing = false;
                }
                previousTarget = targetId;
                maximumTarget = Math.max(maximumTarget, targetId);
                mappingTargets[source.id()] = targetId;
                String liveCanonical = canonicalState(target);
                if (!liveCanonical.equals(minecraftByGlobalId.get(targetId))) {
                    throw new IllegalStateException("Projected target differs from exported minecraft state "
                            + targetId);
                }
                writeTsvLine(
                        mapping,
                        Integer.toString(source.id()),
                        Integer.toString(targetId),
                        source.canonical()
                );
            }
        }

        byte[] compiledMap = compileBlockStateMap(mappingTargets, globalCount);
        Path compiledMapPath = directory.resolve("block-state-map.bin");
        writeBytes(compiledMapPath, compiledMap);

        FileEvidence globalEvidence = evidence(globalPath);
        FileEvidence minecraftEvidence = evidence(minecraftPath);
        FileEvidence sourceEvidence = evidence(sourcePath);
        FileEvidence mapEvidence = evidence(mapPath);
        FileEvidence compiledMapEvidence = evidence(compiledMapPath);
        FileEvidence extrasEvidence = evidence(extraPath);
        CanonicalBlockStateDescriptorSet.Evidence descriptorSet =
                CanonicalBlockStateDescriptorSet.analyze(globalPath, directory);
        if (descriptorSet.count() != globalCount) {
            throw new IllegalStateException(
                    "Canonical descriptor-set count differs from the dense global table");
        }
        String manifest = new StringBuilder()
                .append("format-version=1\n")
                .append("pack=ATM10-8.1\n")
                .append("minecraft=1.21.1\n")
                .append("minecraft-protocol=767\n")
                .append("neoforge=21.1.249\n")
                .append("vanilla-source.count=").append(VANILLA_STATE_COUNT).append('\n')
                .append("vanilla-source.sha256=").append(VANILLA_STATES_SHA256).append('\n')
                .append("projection.extra-properties=live-defaultBlockState\n")
                .append("global.count=").append(globalCount).append('\n')
                .append(fileLines("global", globalEvidence))
                .append("global.runtime-order=diagnostic-nondeterministic-not-profile-identity\n")
                .append("global.canonical-descriptor-set.count=")
                .append(descriptorSet.count()).append('\n')
                .append("global.canonical-descriptor-set.canonicalization=")
                .append(descriptorSet.canonicalization()).append('\n')
                .append("global.canonical-descriptor-set.sha256=")
                .append(descriptorSet.sha256()).append('\n')
                .append("minecraft.count=").append(minecraftCount).append('\n')
                .append(fileLines("minecraft", minecraftEvidence))
                .append(fileLines("vanilla-source", sourceEvidence))
                .append("mapping.count=").append(vanillaSource.states().size()).append('\n')
                .append(fileLines("mapping", mapEvidence))
                .append("mapping.maximum-target-id=").append(maximumTarget).append('\n')
                .append("mapping.target-ids-strictly-increasing=")
                .append(targetsStrictlyIncreasing).append('\n')
                .append(fileLines("compiled-map", compiledMapEvidence))
                .append("extra-properties.count=").append(extraCount).append('\n')
                .append(fileLines("extra-properties", extrasEvidence))
                .toString();
        writeString(directory.resolve("block-states.properties"), manifest);
        exportStableBlockStateProfile(staging, descriptorSet, compiledMap);
        return new BlockStatesExport(
                globalCount,
                minecraftCount,
                vanillaSource.states().size(),
                extraCount,
                maximumTarget,
                targetsStrictlyIncreasing,
                descriptorSet,
                globalEvidence,
                minecraftEvidence,
                mapEvidence,
                compiledMapEvidence
        );
    }

    private static byte[] compileBlockStateMap(int[] targets, int targetGlobalCount)
            throws IOException {
        if (targets.length != VANILLA_STATE_COUNT) {
            throw new IllegalStateException("BlockState map source count mismatch");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(96 * 1024);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(new byte[] {'P', 'O', 'B', 'S'});
            output.writeByte(1);
            output.writeInt(targets.length);
            output.writeInt(targetGlobalCount);
            for (int target : targets) {
                if (target < 0 || target >= targetGlobalCount) {
                    throw new IllegalStateException("BlockState target is outside compiled map bound");
                }
                writeVarInt(output, target);
            }
        }
        return bytes.toByteArray();
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }

    private static void exportStableBlockStateProfile(
            Path staging,
            CanonicalBlockStateDescriptorSet.Evidence descriptorSet,
            byte[] compiledMap
    ) throws IOException {
        Path directory = staging.resolve(
                "integration/blockstate-profiles/"
                        + "atm10-normal-8.1-neoforge-21.1.249");
        writeBytes(directory.resolve("block-state-map.bin"), compiledMap);
        CanonicalBlockStateDescriptorSet.MapEvidence map =
                CanonicalBlockStateDescriptorSet.inspectPobs(compiledMap);
        writeBytes(
                directory.resolve("block-state-map.properties"),
                CanonicalBlockStateDescriptorSet.reviewedManifest(descriptorSet, map));
    }

    private static LinkedHashMap<ResourceLocation, Set<String>> vanillaPropertiesByBlock(
            List<CanonicalSource> sources
    ) {
        LinkedHashMap<ResourceLocation, Set<String>> result = new LinkedHashMap<>();
        for (CanonicalSource source : sources) {
            Set<String> names = Set.copyOf(source.properties().keySet());
            Set<String> previous = result.putIfAbsent(source.blockId(), names);
            if (previous != null && !previous.equals(names)) {
                throw new IllegalStateException("Vanilla source property set varies for block "
                        + source.blockId());
            }
        }
        return result;
    }

    private static void exportStableEnchantmentPacket(
            Path staging,
            RegistryExport wire
    ) throws IOException {
        if (!"wire-known-pack".equals(wire.variant())
                || !wire.knownPacks().equals(List.of(VANILLA_KNOWN_PACK))) {
            throw new IllegalStateException("Stable enchantment packet requires the exact wire variant");
        }
        RegistryPacket enchantments = wire.packets().stream()
                .filter(packet -> packet.registryKey().location().equals(ENCHANTMENT_REGISTRY))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Wire export lacks minecraft:enchantment"));
        Set<ResourceLocation> entryIds = new HashSet<>();
        Set<ResourceLocation> sentinelsWithData = new HashSet<>();
        for (RegistrySynchronization.PackedRegistryEntry entry : enchantments.entries()) {
            if (!entryIds.add(entry.id())) {
                throw new IllegalStateException("Duplicate enchantment entry " + entry.id());
            }
            if (REQUIRED_ENCHANTMENTS.contains(entry.id()) && entry.data().isPresent()) {
                sentinelsWithData.add(entry.id());
            }
        }
        if (!entryIds.containsAll(REQUIRED_ENCHANTMENTS)) {
            Set<ResourceLocation> missing = new LinkedHashSet<>(REQUIRED_ENCHANTMENTS);
            missing.removeAll(entryIds);
            throw new IllegalStateException("Required ATM10 8.1 enchantments are absent: " + missing);
        }
        if (!sentinelsWithData.containsAll(REQUIRED_ENCHANTMENTS)) {
            Set<ResourceLocation> missingData = new LinkedHashSet<>(REQUIRED_ENCHANTMENTS);
            missingData.removeAll(sentinelsWithData);
            throw new IllegalStateException("Required ATM10 8.1 enchantments are placeholders: "
                    + missingData);
        }
        long entriesWithData = enchantments.entries().stream()
                .filter(entry -> entry.data().isPresent())
                .count();
        long knownPackPlaceholders = enchantments.entries().size() - entriesWithData;
        Path directory = staging.resolve(
                "integration/configuration-profiles/"
                        + "atm10-normal-8.1-neoforge-21.1.249/dynamic-registries");
        writeBytes(directory.resolve("minecraft_enchantment.bin"), enchantments.bytes());
        String sidecar = new StringBuilder()
                .append("format-version=1\n")
                .append("pack=ATM10-8.1\n")
                .append("minecraft=1.21.1\n")
                .append("minecraft-protocol=767\n")
                .append("neoforge=21.1.249\n")
                .append("curseforge-server-file-id=8764245\n")
                .append("server-files-sha256=").append(SERVER_FILES_SHA256).append('\n')
                .append("full-client-contract-sha256=")
                .append(FULL_CLIENT_CONTRACT_SHA256).append('\n')
                .append("source-variant=wire-known-pack\n")
                .append("packet-sequence-index=").append(enchantments.index()).append('\n')
                .append("registry-id=minecraft:enchantment\n")
                .append("entry-count=").append(enchantments.entries().size()).append('\n')
                .append("entries-with-data=").append(entriesWithData).append('\n')
                .append("known-pack-placeholders=").append(knownPackPlaceholders).append('\n')
                .append("entry-sequence-sha256=")
                .append(enchantments.entrySequenceSha256()).append('\n')
                .append("packet-bytes=").append(enchantments.bytes().length).append('\n')
                .append("packet-sha256=").append(enchantments.sha256()).append('\n')
                .append("sentinel-entries=evilcraft:vengeance,undergarden:ricochet,")
                .append("quarryplus:quarry_pickaxe\n")
                .toString();
        writeString(directory.resolve("minecraft_enchantment.properties"), sidecar);
    }

    private static VanillaSource loadVanillaSource() throws IOException {
        byte[] compressed;
        try (InputStream input = Atm10Normal81RuntimeExporter.class
                .getResourceAsStream(VANILLA_STATES_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled vanilla state resource "
                        + VANILLA_STATES_RESOURCE);
            }
            compressed = input.readAllBytes();
        }
        String compressedHash = sha256(compressed);
        if (!VANILLA_STATES_GZIP_SHA256.equals(compressedHash)) {
            throw new IllegalStateException("Bundled vanilla state gzip SHA-256 mismatch: "
                    + compressedHash);
        }
        byte[] uncompressed;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            uncompressed = gzip.readAllBytes();
        }
        String sourceHash = sha256(uncompressed);
        if (!VANILLA_STATES_SHA256.equals(sourceHash)) {
            throw new IllegalStateException("Bundled vanilla state SHA-256 mismatch: " + sourceHash);
        }
        List<CanonicalSource> sources = new ArrayList<>(VANILLA_STATE_COUNT);
        Set<String> canonicalStates = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(uncompressed), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.indexOf('\r') >= 0) {
                    throw new IllegalStateException("CR is forbidden in canonical vanilla states");
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || line.indexOf('\t', tab + 1) >= 0) {
                    throw new IllegalStateException("Vanilla source line is not two-field TSV");
                }
                int id = parseCanonicalNonNegativeInt(line.substring(0, tab), "vanilla source id");
                if (id != sources.size()) {
                    throw new IllegalStateException("Vanilla source IDs are not dense at "
                            + sources.size());
                }
                String canonical = line.substring(tab + 1);
                CanonicalSource parsed = parseCanonicalState(id, canonical);
                if (!canonicalStates.add(canonical)) {
                    throw new IllegalStateException("Duplicate vanilla source state " + canonical);
                }
                sources.add(parsed);
            }
        }
        if (sources.size() != VANILLA_STATE_COUNT) {
            throw new IllegalStateException("Vanilla source count mismatch: " + sources.size());
        }
        return new VanillaSource(List.copyOf(sources), uncompressed, sourceHash, compressedHash);
    }

    private static CanonicalSource parseCanonicalState(int id, String canonical) {
        int bracket = canonical.indexOf('[');
        String blockText;
        String propertiesText = null;
        if (bracket < 0) {
            blockText = canonical;
        } else {
            if (!canonical.endsWith("]") || bracket == 0) {
                throw new IllegalStateException("Invalid canonical state " + canonical);
            }
            blockText = canonical.substring(0, bracket);
            propertiesText = canonical.substring(bracket + 1, canonical.length() - 1);
            if (propertiesText.isEmpty()) {
                throw new IllegalStateException("Empty property list in " + canonical);
            }
        }
        ResourceLocation blockId = ResourceLocation.parse(blockText);
        if (!"minecraft".equals(blockId.getNamespace())) {
            throw new IllegalStateException("Vanilla source contains non-minecraft block " + blockId);
        }
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        if (propertiesText != null) {
            String previousName = null;
            for (String field : propertiesText.split(",", -1)) {
                int equals = field.indexOf('=');
                if (equals <= 0 || equals == field.length() - 1
                        || field.indexOf('=', equals + 1) >= 0) {
                    throw new IllegalStateException("Invalid canonical property in " + canonical);
                }
                String name = field.substring(0, equals);
                String value = field.substring(equals + 1);
                if (previousName != null && previousName.compareTo(name) >= 0) {
                    throw new IllegalStateException("Canonical properties are not sorted in " + canonical);
                }
                if (properties.put(name, value) != null) {
                    throw new IllegalStateException("Duplicate property " + name + " in " + canonical);
                }
                previousName = name;
            }
        }
        return new CanonicalSource(id, blockId, Collections.unmodifiableMap(properties), canonical);
    }

    private static String canonicalState(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (blockId == null) {
            throw new IllegalStateException("Cannot canonicalize an unregistered block");
        }
        StringBuilder canonical = new StringBuilder(blockId.toString());
        List<Property<?>> properties = state.getProperties().stream()
                .sorted(Comparator.comparing(Property::getName))
                .toList();
        if (!properties.isEmpty()) {
            canonical.append('[');
            for (int index = 0; index < properties.size(); index++) {
                if (index > 0) {
                    canonical.append(',');
                }
                Property<?> property = properties.get(index);
                canonical.append(property.getName()).append('=')
                        .append(propertyValueName(state, property));
            }
            canonical.append(']');
        }
        return canonical.toString();
    }

    private static <T extends Comparable<T>> String propertyValueName(
            BlockState state,
            Property<T> property
    ) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState setPropertyTyped(
            BlockState state,
            Property<T> property,
            String encodedValue
    ) {
        T value = property.getValue(encodedValue)
                .orElseThrow(() -> new IllegalStateException("Invalid value " + encodedValue
                        + " for live property " + property.getName()));
        return state.setValue(property, value);
    }

    private static BlockState setProperty(
            BlockState state,
            Property<?> property,
            String encodedValue
    ) {
        return setPropertyCaptured(state, property, encodedValue);
    }

    private static <T extends Comparable<T>> BlockState setPropertyCaptured(
            BlockState state,
            Property<T> property,
            String encodedValue
    ) {
        return setPropertyTyped(state, property, encodedValue);
    }

    private static void writeMainManifest(
            Path staging,
            MinecraftServer server,
            Path serverFiles,
            VanillaSource vanilla,
            RegistryExport wire,
            RegistryExport selfContained,
            TagsExport tags,
            ServerConfigsExport serverConfigs,
            BlockStatesExport blockStates
    ) throws IOException {
        String manifest = new StringBuilder()
                .append("format-version=1\n")
                .append("generator=protocolobelisk_atm10_81_exporter\n")
                .append("generator-version=1.0.0\n")
                .append("pack=ATM10-8.1\n")
                .append("minecraft=1.21.1\n")
                .append("minecraft-protocol=767\n")
                .append("neoforge=21.1.249\n")
                .append("runtime-kind=pure-neoforge\n")
                .append("runtime-brand=").append(server.getServerModName()).append('\n')
                .append("curseforge-server-file-id=8764245\n")
                .append("server-files.name=").append(serverFiles.getFileName()).append('\n')
                .append("server-files.bytes=").append(SERVER_FILES_BYTES).append('\n')
                .append("server-files.sha256=").append(SERVER_FILES_SHA256).append('\n')
                .append("full-client-contract-sha256=")
                .append(FULL_CLIENT_CONTRACT_SHA256).append('\n')
                .append("vanilla-source.count=").append(vanilla.states().size()).append('\n')
                .append("vanilla-source.gzip-sha256=").append(vanilla.gzipSha256()).append('\n')
                .append("vanilla-source.sha256=").append(vanilla.sha256()).append('\n')
                .append("registry.wire.packet-count=").append(wire.packets().size()).append('\n')
                .append("registry.wire.sequence-sha256=").append(wire.sequenceSha256()).append('\n')
                .append("registry.self-contained.packet-count=")
                .append(selfContained.packets().size()).append('\n')
                .append("registry.self-contained.sequence-sha256=")
                .append(selfContained.sequenceSha256()).append('\n')
                .append("tags.registry-count=").append(tags.registryCount()).append('\n')
                .append("tags.tag-count=").append(tags.tagCount()).append('\n')
                .append("tags.sha256=").append(tags.sha256()).append('\n')
                .append("server-config.count=").append(serverConfigs.configs().size()).append('\n')
                .append("server-config.total-content-bytes=")
                .append(serverConfigs.totalContentBytes()).append('\n')
                .append("server-config.total-encoded-bytes=")
                .append(serverConfigs.totalPayloadBytes()).append('\n')
                .append("server-config.name-sequence-sha256=")
                .append(serverConfigs.nameSequenceSha256()).append('\n')
                .append("server-config.payload-sequence-sha256=")
                .append(serverConfigs.payloadSequenceSha256()).append('\n')
                .append("block-states.global-count=").append(blockStates.globalCount()).append('\n')
                .append("block-states.minecraft-count=")
                .append(blockStates.minecraftCount()).append('\n')
                .append("block-states.mapping-count=").append(blockStates.mappingCount()).append('\n')
                .append("block-states.mapping-maximum-target-id=")
                .append(blockStates.maximumTarget()).append('\n')
                .append("block-states.mapping-target-ids-strictly-increasing=")
                .append(blockStates.targetsStrictlyIncreasing()).append('\n')
                .append("block-states.compiled-map-sha256=")
                .append(blockStates.compiledMap().sha256()).append('\n')
                .append("block-states.raw-runtime-order-global-sha256=")
                .append(blockStates.global().sha256()).append('\n')
                .append("block-states.canonical-descriptor-set-sha256=")
                .append(blockStates.descriptorSet().sha256()).append('\n')
                .append("block-states.extra-property-count=")
                .append(blockStates.extraPropertyCount()).append('\n')
                .append("deterministic-content=true\n")
                .append("deterministic-file-mtime=1970-01-01T00:00:00Z\n")
                .append("atomic-publication=required\n")
                .toString();
        writeString(staging.resolve("export.properties"), manifest);
    }

    private static <T> byte[] encodeDeterministically(
            StreamCodec<? super FriendlyByteBuf, T> codec,
            T value,
            String description
    ) {
        byte[] first = encode(codec, value);
        byte[] second = encode(codec, value);
        if (!MessageDigest.isEqual(first, second)) {
            throw new IllegalStateException(description + " is not deterministic");
        }
        return first;
    }

    private static <T> byte[] encode(
            StreamCodec<? super FriendlyByteBuf, T> codec,
            T value
    ) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, value);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static <T> T decode(
            StreamCodec<? super FriendlyByteBuf, T> codec,
            byte[] bytes
    ) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            T decoded = codec.decode(buffer);
            if (buffer.isReadable()) {
                throw new IllegalStateException("Codec left " + buffer.readableBytes()
                        + " trailing bytes");
            }
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static String entrySequenceSha256(
            List<RegistrySynchronization.PackedRegistryEntry> entries
    ) {
        MessageDigest digest = sha256Digest();
        for (RegistrySynchronization.PackedRegistryEntry entry : entries) {
            digest.update(entry.id().toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\t');
            digest.update((byte) (entry.data().isPresent() ? '1' : '0'));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sequenceSha256(Stream<byte[]> byteSequence) {
        MessageDigest digest = sha256Digest();
        byteSequence.forEach(digest::update);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static String safeName(ResourceLocation location) {
        return location.toString().replace(':', '_').replace('/', '_');
    }

    private static int parseCanonicalNonNegativeInt(String value, String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || !Integer.toString(parsed).equals(value)) {
                throw new IllegalStateException(field + " is not canonical: " + value);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(field + " is invalid: " + value, exception);
        }
    }

    private static void writeTsvLine(BufferedWriter writer, String... fields) throws IOException {
        for (int index = 0; index < fields.length; index++) {
            String field = fields[index];
            if (field.indexOf('\t') >= 0 || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0) {
                throw new IllegalStateException("Unsafe TSV field");
            }
            if (index > 0) {
                writer.write('\t');
            }
            writer.write(field);
        }
        writer.write('\n');
    }

    private static void writeBytes(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static void writeString(Path path, String value) throws IOException {
        writeBytes(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static FileEvidence evidence(Path path) throws IOException {
        return new FileEvidence(
                path.getParent().getFileName() + "/" + path.getFileName(),
                Files.size(path),
                sha256(path)
        );
    }

    private static String fileLines(String key, FileEvidence evidence) {
        return key + ".file=" + evidence.fileName() + "\n"
                + key + ".bytes=" + evidence.bytes() + "\n"
                + key + ".sha256=" + evidence.sha256() + "\n";
    }

    private static void normalizeTimestamps(Path root) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.setLastModifiedTime(path, DETERMINISTIC_TIMESTAMP);
        }
    }

    private static void deleteTreeBestEffort(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException cleanupFailure) {
            System.err.println("[ProtocolObelisk ATM10 8.1 exporter] staging cleanup failed: "
                    + cleanupFailure);
        }
    }

    private record VanillaSource(
            List<CanonicalSource> states,
            byte[] uncompressedBytes,
            String sha256,
            String gzipSha256
    ) {
        private VanillaSource {
            states = List.copyOf(states);
            uncompressedBytes = uncompressedBytes.clone();
        }

        @Override
        public byte[] uncompressedBytes() {
            return uncompressedBytes.clone();
        }
    }

    private record CanonicalSource(
            int id,
            ResourceLocation blockId,
            Map<String, String> properties,
            String canonical
    ) {
    }

    private record RegistryPacket(
            int index,
            ResourceKey<? extends Registry<?>> registryKey,
            String fileName,
            List<RegistrySynchronization.PackedRegistryEntry> entries,
            byte[] bytes,
            String sha256,
            String entrySequenceSha256
    ) {
        private RegistryPacket {
            entries = List.copyOf(entries);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record RegistryExport(
            String variant,
            List<KnownPack> knownPacks,
            List<RegistryPacket> packets,
            int totalEntries,
            int encodedEntries,
            String sequenceSha256
    ) {
        private RegistryExport {
            knownPacks = List.copyOf(knownPacks);
            packets = List.copyOf(packets);
        }
    }

    private record TagsExport(int registryCount, int tagCount, int bytes, String sha256) {
    }

    private record ServerConfigEvidence(
            String configName,
            String encodedFile,
            int contentBytes,
            String contentSha256,
            int encodedBytes,
            String encodedSha256,
            byte[] encodedPayload
    ) {
        private ServerConfigEvidence {
            encodedPayload = encodedPayload.clone();
        }

        @Override
        public byte[] encodedPayload() {
            return encodedPayload.clone();
        }
    }

    private record ServerConfigsExport(
            List<ServerConfigEvidence> configs,
            long totalContentBytes,
            long totalPayloadBytes,
            String nameSequenceSha256,
            String payloadSequenceSha256
    ) {
        private ServerConfigsExport {
            configs = List.copyOf(configs);
        }
    }

    private record FileEvidence(String fileName, long bytes, String sha256) {
    }

    private record BlockStatesExport(
            int globalCount,
            int minecraftCount,
            int mappingCount,
            int extraPropertyCount,
            int maximumTarget,
            boolean targetsStrictlyIncreasing,
            CanonicalBlockStateDescriptorSet.Evidence descriptorSet,
            FileEvidence global,
            FileEvidence minecraft,
            FileEvidence mapping,
            FileEvidence compiledMap
    ) {
    }
}
