package br.com.atmbrasil.protocolobelisk.capture;

import com.mojang.serialization.DynamicOps;
import io.netty.buffer.Unpooled;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
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
import net.minecraft.SharedConstants;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.ConnectionProtocol;
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
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import net.neoforged.neoforge.network.ConfigSync;
import net.neoforged.neoforge.network.payload.ConfigFilePayload;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryComponent;
import net.neoforged.neoforge.network.payload.ModdedNetworkQueryPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

/**
 * Build-time capture of everything a real NeoForge server would hand a client during
 * CONFIGURATION, for any NeoForge 1.21.1 modpack.
 *
 * <p>This is the universal successor of the ATM10 8.1 runtime exporter. It carries no modpack
 * pins: the pack identity is supplied by the operator and the ServerFiles hash is recorded as
 * metadata, never required. The output is a compatibility pack consumed by ProtocolObelisk at
 * runtime, so a modpack update means "capture again", not "change the plugin source".</p>
 *
 * <p>The mod must only run on a disposable, pure NeoForge copy of the ServerFiles. It halts the
 * server after the export (success or failure) and refuses Youer/Mohist hybrids.</p>
 */
@Mod(ObeliskCapture.MOD_ID)
@EventBusSubscriber(modid = ObeliskCapture.MOD_ID)
public final class ObeliskCapture {
    public static final String MOD_ID = "protocolobelisk_capture";
    static final String TOOL_VERSION = "1.0.0";
    static final int PACK_FORMAT_VERSION = 1;

    private static final String OUTPUT_PROPERTY = "protocolobelisk.capture.output";
    private static final String PACK_ID_PROPERTY = "protocolobelisk.capture.packId";
    private static final String DISPLAY_NAME_PROPERTY = "protocolobelisk.capture.displayName";
    private static final String SERVER_FILES_PROPERTY = "protocolobelisk.capture.serverFiles";
    private static final String SOURCE_URL_PROPERTY = "protocolobelisk.capture.sourceUrl";
    private static final String LOG_PREFIX = "[ProtocolObelisk capture] ";

    private static final String VANILLA_STATES_RESOURCE =
            "/protocolobelisk/capture/vanilla-block-states-1.21.1.tsv.gz";
    private static final String VANILLA_STATES_GZIP_SHA256 =
            "dd050af1d54069cee2afbb4a81707c5360369981b0ef42bc0e63fd94c683374d";
    private static final String VANILLA_STATES_SHA256 =
            "de9980fae71fecd0112d77cd1c056815dca3a1e1125289efd08b1c0696f499bb";
    private static final int VANILLA_STATE_COUNT = 26_684;
    private static final int MAXIMUM_GLOBAL_STATES = 1 << 21;
    private static final int MAXIMUM_REGISTRY_PACKETS = 1_024;
    private static final int MAXIMUM_REGISTRY_ENTRIES = 2_000_000;
    private static final int MAXIMUM_PACKET_BYTES = 64 * 1024 * 1024;
    private static final int MAXIMUM_SERVER_CONFIGS = 1_024;
    private static final int MAXIMUM_CONFIG_NAME_UTF8_BYTES = 128;
    private static final int MAXIMUM_CONFIG_CONTENT_BYTES = 16 * 1024 * 1024;
    private static final int MAXIMUM_TOTAL_CONFIG_CONTENT_BYTES = 256 * 1024 * 1024;
    private static final int MAXIMUM_CHANNELS = 16_384;
    private static final Pattern PACK_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern SAFE_RELATIVE_TOML_PATH = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_.-]*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)*\\.toml");
    private static final FileTime DETERMINISTIC_TIMESTAMP = FileTime.from(Instant.EPOCH);
    private static final KnownPack VANILLA_KNOWN_PACK =
            new KnownPack("minecraft", "core", "1.21.1");

    public ObeliskCapture() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            export(server);
        } catch (Throwable failure) {
            System.err.println(LOG_PREFIX + "FAILED: " + failure);
            failure.printStackTrace(System.err);
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("ProtocolObelisk capture failed", failure);
        } finally {
            server.halt(false);
        }
    }

    private static void export(MinecraftServer server) throws IOException {
        rejectHybridRuntime(server);
        String packId = requiredText(PACK_ID_PROPERTY);
        if (!PACK_ID.matcher(packId).matches()) {
            throw new IllegalStateException("Pack id must match " + PACK_ID.pattern() + ": " + packId);
        }
        String displayName = System.getProperty(DISPLAY_NAME_PROPERTY, packId).strip();
        String sourceUrl = System.getProperty(SOURCE_URL_PROPERTY, "").strip();
        Optional<SourceEvidence> source = optionalSource();
        Path output = requiredPath(OUTPUT_PROPERTY);
        if (Files.exists(output)) {
            throw new IllegalStateException("Refusing to replace existing capture " + output);
        }
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalStateException("Capture output must have a parent directory");
        }
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + output.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectory(staging);
        boolean published = false;
        try {
            VanillaSource vanillaSource = loadVanillaSource();
            LayeredRegistryAccess<RegistryLayer> layeredRegistries = server.registries();
            List<KnownPack> wireKnownPacks = discoverWireKnownPacks(server);

            NetworkExport network = exportNetwork(staging);
            ModsExport mods = exportMods(staging);
            RegistryExport wire = exportRegistries(
                    staging,
                    layeredRegistries,
                    "wire-known-pack",
                    wireKnownPacks,
                    Collections.unmodifiableSet(new LinkedHashSet<>(wireKnownPacks)));
            RegistryExport selfContained = exportRegistries(
                    staging,
                    layeredRegistries,
                    "self-contained",
                    List.of(),
                    Set.of());
            validateRegistryVariants(wire, selfContained);
            TagsExport tags = exportFullTags(staging, layeredRegistries);
            ServerConfigsExport serverConfigs = exportServerConfigs(staging);
            BlockStatesExport blockStates = exportBlockStates(staging, vanillaSource, packId);
            writePackManifest(
                    staging,
                    server,
                    packId,
                    displayName,
                    sourceUrl,
                    source,
                    vanillaSource,
                    network,
                    mods,
                    wire,
                    selfContained,
                    tags,
                    serverConfigs,
                    blockStates);
            normalizeTimestamps(staging);
            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException(
                        "Filesystem does not support atomic directory publication", exception);
            }
            published = true;
            System.out.println(LOG_PREFIX + "SUCCESS output=" + output);
            System.out.println(LOG_PREFIX + "pack=" + packId + " mods=" + mods.count()
                    + " channels=" + network.channelCount()
                    + " serverConfigs=" + serverConfigs.configs().size()
                    + " registries=" + wire.packets().size()
                    + " registryEntries=" + wire.totalEntries()
                    + " tagRegistries=" + tags.registryCount()
                    + " blockStates=" + blockStates.globalCount()
                    + " extraVanillaProperties=" + blockStates.extraPropertyCount());
        } finally {
            if (!published) {
                deleteTreeBestEffort(staging);
            }
        }
    }

    private static String requiredText(String propertyName) {
        String value = System.getProperty(propertyName, "").strip();
        if (value.isEmpty()) {
            throw new IllegalStateException("Missing required -D" + propertyName + "=<value>");
        }
        return value;
    }

    private static Path requiredPath(String propertyName) {
        return Path.of(requiredText(propertyName)).toAbsolutePath().normalize();
    }

    private static Optional<SourceEvidence> optionalSource() throws IOException {
        String value = System.getProperty(SERVER_FILES_PROPERTY, "").strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("ServerFiles input is not a regular file: " + path);
        }
        return Optional.of(new SourceEvidence(
                path.getFileName().toString(), Files.size(path), sha256(path)));
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
                "com.mohistmc.MohistMC")) {
            try {
                Class.forName(marker, false, ObeliskCapture.class.getClassLoader());
                evidence.add("loaded class " + marker);
            } catch (ClassNotFoundException ignored) {
                // Expected on the required pure NeoForge runtime.
            } catch (LinkageError error) {
                evidence.add("linkable class " + marker + " (" + error.getClass().getSimpleName() + ")");
            }
        }
        if (!evidence.isEmpty()) {
            throw new IllegalStateException("Youer/Mohist runtime is forbidden for a capture: "
                    + String.join("; ", evidence));
        }
        String brand = server.getServerModName().toLowerCase(Locale.ROOT);
        if (!brand.contains("neoforge")) {
            throw new IllegalStateException("Expected a pure NeoForge server brand, found: "
                    + server.getServerModName());
        }
        if (SharedConstants.getProtocolVersion() != 767) {
            throw new IllegalStateException("Capture supports Minecraft protocol 767 only, found "
                    + SharedConstants.getProtocolVersion());
        }
    }

    private static void checkHybridToken(String source, String value, List<String> evidence) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("youer") || normalized.contains("mohist")) {
            evidence.add(source + "=" + value);
        }
    }

    /**
     * Records the server's own NeoForge channel registrations in the exact wire format a client
     * uses to answer {@code neoforge:register}. ProtocolObelisk decodes it with its existing,
     * bounded query decoder and selects a pack only when NeoForge's own negotiation rules say this
     * server would have accepted the connecting client.
     */
    private static NetworkExport exportNetwork(Path staging) throws IOException {
        Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> registrations =
                payloadRegistrations();
        LinkedHashMap<ConnectionProtocol, Set<ModdedNetworkQueryComponent>> ordered =
                new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        int channelCount = 0;
        for (ConnectionProtocol protocol : List.of(
                ConnectionProtocol.CONFIGURATION, ConnectionProtocol.PLAY)) {
            Map<ResourceLocation, PayloadRegistration<?>> byId =
                    registrations.getOrDefault(protocol, Map.of());
            LinkedHashSet<ModdedNetworkQueryComponent> components = new LinkedHashSet<>();
            byId.values().stream()
                    .map(ModdedNetworkQueryComponent::new)
                    .sorted(Comparator.comparing((ModdedNetworkQueryComponent c) -> c.id().toString())
                            .thenComparing(ModdedNetworkQueryComponent::version))
                    .forEach(component -> {
                        components.add(component);
                        lines.add(protocol.id() + '\t' + component.id() + '\t' + component.version()
                                + '\t' + component.flow().map(flow -> flow.name().toLowerCase(Locale.ROOT))
                                        .orElse("bidirectional")
                                + '\t' + (component.optional() ? "optional" : "required"));
                    });
            channelCount = Math.addExact(channelCount, components.size());
            ordered.put(protocol, components);
        }
        if (channelCount == 0 || channelCount > MAXIMUM_CHANNELS) {
            throw new IllegalStateException("Server channel count violates bound: " + channelCount);
        }
        ModdedNetworkQueryPayload payload = new ModdedNetworkQueryPayload(
                Collections.unmodifiableMap(ordered));
        byte[] bytes = encodeDeterministically(
                ModdedNetworkQueryPayload.STREAM_CODEC, payload, "server network query");
        ModdedNetworkQueryPayload decoded = decode(ModdedNetworkQueryPayload.STREAM_CODEC, bytes);
        for (ConnectionProtocol protocol : ordered.keySet()) {
            if (!new HashSet<>(ordered.get(protocol)).equals(
                    new HashSet<>(decoded.queries().getOrDefault(protocol, Set.of())))) {
                throw new IllegalStateException("Server network query changed after decode");
            }
        }
        writeBytes(staging.resolve("network/server-query.bin"), bytes);
        StringBuilder tsv = new StringBuilder("# protocol\tchannel\tversion\tflow\trequirement\n");
        lines.forEach(line -> tsv.append(line).append('\n'));
        writeString(staging.resolve("network/channels.tsv"), tsv.toString());
        return new NetworkExport(channelCount, bytes.length, sha256(bytes));
    }

    @SuppressWarnings("unchecked")
    private static Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>
            payloadRegistrations() {
        try {
            Field field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
            field.setAccessible(true);
            return (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>)
                    field.get(null);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException(
                    "NeoForge NetworkRegistry.PAYLOAD_REGISTRATIONS is unavailable", failure);
        }
    }

    private static ModsExport exportMods(Path staging) throws IOException {
        List<String> lines = new ArrayList<>();
        for (var mod : ModList.get().getMods()) {
            String file = mod.getOwningFile() == null
                    ? ""
                    : mod.getOwningFile().getFile().getFileName();
            lines.add(sanitizeTsv(mod.getModId()) + '\t' + sanitizeTsv(mod.getVersion().toString())
                    + '\t' + sanitizeTsv(mod.getDisplayName()) + '\t' + sanitizeTsv(file));
        }
        lines.sort(Comparator.naturalOrder());
        StringBuilder tsv = new StringBuilder("# modid\tversion\tdisplay-name\tfile\n");
        lines.forEach(line -> tsv.append(line).append('\n'));
        writeString(staging.resolve("mods.tsv"), tsv.toString());
        return new ModsExport(lines.size());
    }

    private static String sanitizeTsv(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
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
        // A vanilla Paper lobby negotiates exactly this known pack with the client. Mod-owned
        // known packs are intentionally ignored; the self-contained variant carries all data.
        return List.of(VANILLA_KNOWN_PACK);
    }

    private static RegistryExport exportRegistries(
            Path staging,
            LayeredRegistryAccess<RegistryLayer> layeredRegistries,
            String variant,
            List<KnownPack> manifestKnownPacks,
            Set<KnownPack> codecKnownPacks) throws IOException {
        Path directory = staging.resolve("registries").resolve(variant);
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
                            "registry " + variant + " / " + registryKey.location());
                    validateRegistryRoundTrip(packet, bytes);
                    if (bytes.length > MAXIMUM_PACKET_BYTES) {
                        throw new IllegalStateException("Registry packet exceeds byte bound: "
                                + registryKey.location() + " / " + bytes.length);
                    }
                    String fileName = "%03d-%s.bin".formatted(index, safeName(registryKey.location()));
                    packets.add(new RegistryPacket(
                            index,
                            registryKey,
                            fileName,
                            entries,
                            bytes,
                            sha256(bytes),
                            entrySequenceSha256(entries)));
                });
        if (packets.isEmpty()) {
            throw new IllegalStateException("RegistrySynchronization emitted no packets for " + variant);
        }
        int totalEntries = packets.stream().mapToInt(packet -> packet.entries().size()).sum();
        int encodedEntries = packets.stream()
                .mapToInt(packet -> (int) packet.entries().stream()
                        .filter(entry -> entry.data().isPresent()).count())
                .sum();
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
                sequenceHash);
        writeString(staging.resolve("registries/" + variant + ".properties"),
                registryManifest(export));
        return export;
    }

    private static void validateRegistryRoundTrip(ClientboundRegistryDataPacket source, byte[] bytes) {
        ClientboundRegistryDataPacket decoded =
                decode(ClientboundRegistryDataPacket.STREAM_CODEC, bytes);
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

    private static void validateRegistryVariants(RegistryExport wire, RegistryExport selfContained) {
        if (!wire.knownPacks().equals(List.of(VANILLA_KNOWN_PACK))
                || !selfContained.knownPacks().isEmpty()) {
            throw new IllegalStateException("Registry known-pack sets violate the capture contract");
        }
        if (wire.packets().size() != selfContained.packets().size()
                || wire.totalEntries() != selfContained.totalEntries()) {
            throw new IllegalStateException("Registry variants differ in packet/entry counts");
        }
        for (int packetIndex = 0; packetIndex < wire.packets().size(); packetIndex++) {
            RegistryPacket wirePacket = wire.packets().get(packetIndex);
            RegistryPacket fullPacket = selfContained.packets().get(packetIndex);
            if (!wirePacket.registryKey().equals(fullPacket.registryKey())
                    || wirePacket.entries().size() != fullPacket.entries().size()) {
                throw new IllegalStateException("Registry variants diverge at packet " + packetIndex);
            }
            for (int entryIndex = 0; entryIndex < wirePacket.entries().size(); entryIndex++) {
                var wireEntry = wirePacket.entries().get(entryIndex);
                var fullEntry = fullPacket.entries().get(entryIndex);
                if (!wireEntry.id().equals(fullEntry.id()) || fullEntry.data().isEmpty()) {
                    throw new IllegalStateException("Registry variants diverge at packet/entry "
                            + packetIndex + "/" + entryIndex);
                }
                if (wireEntry.data().isPresent() && !wireEntry.data().equals(fullEntry.data())) {
                    throw new IllegalStateException("Wire registry data differs at "
                            + packetIndex + "/" + entryIndex);
                }
            }
        }
    }

    private static String registryManifest(RegistryExport export) {
        StringBuilder manifest = new StringBuilder()
                .append("format-version=1\n")
                .append("variant=").append(export.variant()).append('\n')
                .append("ordering=RegistrySynchronization.packRegistries-callback-and-entry-order\n")
                .append("known-pack.count=").append(export.knownPacks().size()).append('\n');
        for (int index = 0; index < export.knownPacks().size(); index++) {
            KnownPack pack = export.knownPacks().get(index);
            manifest.append("known-pack.").append(index).append('=')
                    .append(pack.namespace()).append(':').append(pack.id()).append(':')
                    .append(pack.version()).append('\n');
        }
        manifest.append("packet.count=").append(export.packets().size()).append('\n')
                .append("packet.total-entries=").append(export.totalEntries()).append('\n')
                .append("packet.total-encoded-data-entries=").append(export.encodedEntries()).append('\n')
                .append("packet.sequence-sha256=").append(export.sequenceSha256()).append('\n');
        for (RegistryPacket packet : export.packets()) {
            String key = "packet." + packet.index();
            long withData = packet.entries().stream().filter(entry -> entry.data().isPresent()).count();
            long modded = packet.entries().stream()
                    .filter(entry -> !"minecraft".equals(entry.id().getNamespace())).count();
            manifest.append(key).append(".registry=").append(packet.registryKey().location()).append('\n')
                    .append(key).append(".file=").append(packet.fileName()).append('\n')
                    .append(key).append(".entries=").append(packet.entries().size()).append('\n')
                    .append(key).append(".encoded-data-entries=").append(withData).append('\n')
                    .append(key).append(".non-minecraft-entries=").append(modded).append('\n')
                    .append(key).append(".entry-sequence-sha256=").append(packet.entrySequenceSha256()).append('\n')
                    .append(key).append(".bytes=").append(packet.bytes().length).append('\n')
                    .append(key).append(".sha256=").append(packet.sha256()).append('\n');
        }
        return manifest.toString();
    }

    private static TagsExport exportFullTags(
            Path staging, LayeredRegistryAccess<RegistryLayer> layeredRegistries) throws IOException {
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tagMap =
                TagNetworkSerialization.serializeTagsToNetwork(layeredRegistries);
        if (tagMap.isEmpty()) {
            throw new IllegalStateException("Full tag serialization returned an empty map");
        }
        // Deterministic registry order; the client indexes the payload by registry key.
        LinkedHashMap<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload>
                ordered = new LinkedHashMap<>();
        tagMap.keySet().stream()
                .sorted(Comparator.comparing(key -> key.location().toString()))
                .forEach(key -> ordered.put(key, tagMap.get(key)));
        ClientboundUpdateTagsPacket packet = new ClientboundUpdateTagsPacket(ordered);
        byte[] bytes = encodeDeterministically(
                ClientboundUpdateTagsPacket.STREAM_CODEC, packet, "full update-tags packet");
        if (bytes.length > MAXIMUM_PACKET_BYTES) {
            throw new IllegalStateException("Full tags packet violates byte bound: " + bytes.length);
        }
        ClientboundUpdateTagsPacket decoded = decode(ClientboundUpdateTagsPacket.STREAM_CODEC, bytes);
        if (!new HashSet<>(ordered.keySet()).equals(new HashSet<>(decoded.getTags().keySet()))) {
            throw new IllegalStateException("Full tags registry set changed after decode");
        }
        int tagCount = 0;
        StringBuilder manifest = new StringBuilder()
                .append("format-version=1\n")
                .append("scope=full-live-TagNetworkSerialization-output\n")
                .append("file=tags/full-update-tags.bin\n")
                .append("bytes=").append(bytes.length).append('\n')
                .append("sha256=").append(sha256(bytes)).append('\n')
                .append("registry.count=").append(ordered.size()).append('\n');
        int index = 0;
        for (var key : ordered.keySet()) {
            int size = ordered.get(key).size();
            if (decoded.getTags().get(key).size() != size) {
                throw new IllegalStateException("Full tags changed for registry " + key.location());
            }
            tagCount = Math.addExact(tagCount, size);
            manifest.append("registry.").append(index).append(".name=").append(key.location()).append('\n')
                    .append("registry.").append(index).append(".tags=").append(size).append('\n');
            index++;
        }
        manifest.append("tag.count=").append(tagCount).append('\n');
        writeBytes(staging.resolve("tags/full-update-tags.bin"), bytes);
        writeString(staging.resolve("tags/full-update-tags.properties"), manifest.toString());
        return new TagsExport(ordered.size(), tagCount, bytes.length, sha256(bytes));
    }

    /**
     * Captures the complete NeoForge SERVER-config transaction a real server sends during
     * CONFIGURATION, with contents, in name order. NeoForge indexes synced configs by filename.
     */
    private static ServerConfigsExport exportServerConfigs(Path staging) throws IOException {
        List<ConfigFilePayload> payloads = ConfigSync.syncConfigs().stream()
                .sorted(Comparator.comparing(ConfigFilePayload::fileName))
                .toList();
        if (payloads.isEmpty()) {
            throw new IllegalStateException("NeoForge resolved no SERVER configs");
        }
        if (payloads.size() > MAXIMUM_SERVER_CONFIGS) {
            throw new IllegalStateException("SERVER config count exceeds bound: " + payloads.size());
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
                throw new IllegalStateException("Total SERVER config content exceeds bound");
            }
            byte[] encoded = encodeDeterministically(
                    ConfigFilePayload.STREAM_CODEC, payload, "SERVER config " + fileName);
            ConfigFilePayload decoded = decode(ConfigFilePayload.STREAM_CODEC, encoded);
            if (!fileName.equals(decoded.fileName())
                    || !MessageDigest.isEqual(contents, decoded.contents())) {
                throw new IllegalStateException("SERVER config changed after decode: " + fileName);
            }
            String encodedFile = "%03d.bin".formatted(index);
            writeBytes(directory.resolve(encodedFile), encoded);
            totalPayloadBytes = Math.addExact(totalPayloadBytes, encoded.length);
            configs.add(new ServerConfigEvidence(
                    fileName, encodedFile, contents.length, sha256(contents),
                    encoded.length, sha256(encoded), encoded));
        }
        String nameSequenceSha256 = serverConfigNameSequenceSha256(configs);
        String payloadSequenceSha256 = sequenceSha256(
                configs.stream().map(ServerConfigEvidence::encodedPayload));
        ServerConfigsExport export = new ServerConfigsExport(
                List.copyOf(configs), totalContentBytes, totalPayloadBytes,
                nameSequenceSha256, payloadSequenceSha256);
        writeString(staging.resolve("server-configs.properties"), serverConfigManifest(export));
        return export;
    }

    private static void requireSafeConfigFileName(String fileName) {
        if (fileName == null
                || fileName.isEmpty()
                || fileName.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_CONFIG_NAME_UTF8_BYTES
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
                .append("config.count=").append(export.configs().size()).append('\n')
                .append("config.total-content-bytes=").append(export.totalContentBytes()).append('\n')
                .append("config.total-encoded-bytes=").append(export.totalPayloadBytes()).append('\n')
                .append("config.name-sequence-sha256=").append(export.nameSequenceSha256()).append('\n')
                .append("config.payload-sequence-sha256=").append(export.payloadSequenceSha256()).append('\n');
        for (int index = 0; index < export.configs().size(); index++) {
            ServerConfigEvidence config = export.configs().get(index);
            String key = "config." + index;
            manifest.append(key).append(".name=").append(config.configName()).append('\n')
                    .append(key).append(".file=server-configs/").append(config.encodedFile()).append('\n')
                    .append(key).append(".content-bytes=").append(config.contentBytes()).append('\n')
                    .append(key).append(".content-sha256=").append(config.contentSha256()).append('\n')
                    .append(key).append(".encoded-bytes=").append(config.encodedBytes()).append('\n')
                    .append(key).append(".encoded-sha256=").append(config.encodedSha256()).append('\n');
        }
        return manifest.toString();
    }

    private static String serverConfigNameSequenceSha256(List<ServerConfigEvidence> configs) {
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

    /**
     * Projects every vanilla 1.21.1 state into this runtime's dense BlockState table.
     *
     * <p>Vanilla blocks are registered first, but mods may add properties to them (or extensible
     * enum values such as note-block instruments). The client numbers states with those additions,
     * so a vanilla Paper lobby must be translated. Properties added by mods take the live block's
     * {@code defaultBlockState()} value; nothing is guessed.</p>
     */
    private static BlockStatesExport exportBlockStates(
            Path staging, VanillaSource vanillaSource, String packId) throws IOException {
        Path directory = staging.resolve("block-states");
        Files.createDirectories(directory);
        Path globalPath = staging.resolve(".work-global-block-states.tsv");
        int globalCount = Block.BLOCK_STATE_REGISTRY.size();
        if (globalCount <= 0 || globalCount > MAXIMUM_GLOBAL_STATES) {
            throw new IllegalStateException("Global BlockState count violates bound: " + globalCount);
        }
        Map<Integer, String> minecraftByGlobalId = new HashMap<>();
        int minecraftCount = 0;
        try (BufferedWriter global = Files.newBufferedWriter(globalPath, StandardCharsets.UTF_8)) {
            for (int id = 0; id < globalCount; id++) {
                BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
                if (state == null || Block.getId(state) != id) {
                    throw new IllegalStateException("Global BlockState table is sparse at " + id);
                }
                String canonical = canonicalState(state);
                writeTsvLine(global, Integer.toString(id), canonical);
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if ("minecraft".equals(blockId.getNamespace())) {
                    if (minecraftByGlobalId.put(id, canonical) != null) {
                        throw new IllegalStateException("Duplicate live minecraft state id " + id);
                    }
                    minecraftCount++;
                }
            }
        }

        Path extraPath = directory.resolve("extra-vanilla-properties.tsv");
        Set<Integer> usedTargets = new HashSet<>();
        int[] mappingTargets = new int[VANILLA_STATE_COUNT];
        LinkedHashMap<ResourceLocation, Set<String>> vanillaPropertyNamesByBlock =
                vanillaPropertiesByBlock(vanillaSource.states());
        int previousTarget = -1;
        int maximumTarget = -1;
        boolean targetsStrictlyIncreasing = true;
        int extraCount = 0;
        try (BufferedWriter extras = Files.newBufferedWriter(extraPath, StandardCharsets.UTF_8)) {
            extras.write("# block\tproperty\tdefault\tpossible-values\tkind\n");
            Map<ResourceLocation, Map<String, Set<String>>> vanillaValues =
                    vanillaValuesByBlockProperty(vanillaSource.states());
            Set<ResourceLocation> describedExtras = new HashSet<>();
            for (CanonicalSource source : vanillaSource.states()) {
                Block block = BuiltInRegistries.BLOCK.getOptional(source.blockId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Live registry lacks vanilla block " + source.blockId()));
                BlockState target = block.defaultBlockState();
                Set<String> sourcePropertyNames = vanillaPropertyNamesByBlock.get(source.blockId());
                if (describedExtras.add(source.blockId())) {
                    for (Property<?> property : block.getStateDefinition().getProperties().stream()
                            .sorted(Comparator.comparing(Property::getName)).toList()) {
                        if (!sourcePropertyNames.contains(property.getName())) {
                            writeTsvLine(extras, source.blockId().toString(), property.getName(),
                                    propertyValueName(block.defaultBlockState(), property),
                                    possibleValues(property), "new-property");
                            extraCount++;
                            continue;
                        }
                        // Extensible enums (e.g. note-block instruments) add values, not properties.
                        Set<String> vanilla = vanillaValues
                                .getOrDefault(source.blockId(), Map.of())
                                .getOrDefault(property.getName(), Set.of());
                        if (possibleValueSet(property).size() != vanilla.size()) {
                            writeTsvLine(extras, source.blockId().toString(), property.getName(),
                                    propertyValueName(block.defaultBlockState(), property),
                                    String.join(",", addedValues(property, vanilla)), "extra-values");
                            extraCount++;
                        }
                    }
                }
                for (Map.Entry<String, String> propertyValue : source.properties().entrySet()) {
                    Property<?> liveProperty =
                            block.getStateDefinition().getProperty(propertyValue.getKey());
                    if (liveProperty == null) {
                        throw new IllegalStateException("Live block " + source.blockId()
                                + " lacks vanilla property " + propertyValue.getKey());
                    }
                    target = setProperty(target, liveProperty, propertyValue.getValue());
                }
                int targetId = Block.getId(target);
                if (targetId < 0 || targetId >= globalCount
                        || Block.BLOCK_STATE_REGISTRY.byId(targetId) != target) {
                    throw new IllegalStateException("Projected target is not dense: " + source.canonical());
                }
                if (!usedTargets.add(targetId)) {
                    throw new IllegalStateException("Two vanilla source states map to target " + targetId);
                }
                if (!canonicalState(target).equals(minecraftByGlobalId.get(targetId))) {
                    throw new IllegalStateException("Projected target differs from live state " + targetId);
                }
                if (targetId <= previousTarget) {
                    targetsStrictlyIncreasing = false;
                }
                previousTarget = targetId;
                maximumTarget = Math.max(maximumTarget, targetId);
                mappingTargets[source.id()] = targetId;
            }
        }

        byte[] compiledMap = compileBlockStateMap(mappingTargets, globalCount);
        writeBytes(directory.resolve("block-state-map.bin"), compiledMap);
        CanonicalBlockStateDescriptorSet.Evidence descriptorSet =
                CanonicalBlockStateDescriptorSet.analyze(globalPath, staging);
        Files.delete(globalPath);
        if (descriptorSet.count() != globalCount) {
            throw new IllegalStateException(
                    "Canonical descriptor-set count differs from the dense global table");
        }
        CanonicalBlockStateDescriptorSet.MapEvidence map =
                CanonicalBlockStateDescriptorSet.inspectPobs(compiledMap);
        writeBytes(directory.resolve("block-state-map.properties"),
                CanonicalBlockStateDescriptorSet.packManifest(descriptorSet, map, packId));
        return new BlockStatesExport(
                globalCount,
                minecraftCount,
                extraCount,
                maximumTarget,
                targetsStrictlyIncreasing,
                descriptorSet.sha256(),
                sha256(compiledMap));
    }

    private static Map<ResourceLocation, Map<String, Set<String>>> vanillaValuesByBlockProperty(
            List<CanonicalSource> sources) {
        Map<ResourceLocation, Map<String, Set<String>>> result = new HashMap<>();
        for (CanonicalSource source : sources) {
            Map<String, Set<String>> byProperty =
                    result.computeIfAbsent(source.blockId(), ignored -> new HashMap<>());
            source.properties().forEach((name, value) ->
                    byProperty.computeIfAbsent(name, ignored -> new HashSet<>()).add(value));
        }
        return result;
    }

    private static <T extends Comparable<T>> Set<String> possibleValueSet(Property<T> property) {
        Set<String> values = new LinkedHashSet<>();
        for (T value : property.getPossibleValues()) {
            values.add(property.getName(value));
        }
        return values;
    }

    private static List<String> addedValues(Property<?> property, Set<String> vanilla) {
        return possibleValueSet(property).stream().filter(value -> !vanilla.contains(value)).toList();
    }

    private static <T extends Comparable<T>> String possibleValues(Property<T> property) {
        List<String> values = new ArrayList<>();
        for (T value : property.getPossibleValues()) {
            values.add(property.getName(value));
        }
        return String.join(",", values);
    }

    private static byte[] compileBlockStateMap(int[] targets, int targetGlobalCount) throws IOException {
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
                int remaining = target;
                while ((remaining & ~0x7F) != 0) {
                    output.writeByte((remaining & 0x7F) | 0x80);
                    remaining >>>= 7;
                }
                output.writeByte(remaining);
            }
        }
        return bytes.toByteArray();
    }

    private static LinkedHashMap<ResourceLocation, Set<String>> vanillaPropertiesByBlock(
            List<CanonicalSource> sources) {
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

    private static VanillaSource loadVanillaSource() throws IOException {
        byte[] compressed;
        try (InputStream input = ObeliskCapture.class.getResourceAsStream(VANILLA_STATES_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled vanilla state resource");
            }
            compressed = input.readAllBytes();
        }
        if (!VANILLA_STATES_GZIP_SHA256.equals(sha256(compressed))) {
            throw new IllegalStateException("Bundled vanilla state gzip SHA-256 mismatch");
        }
        byte[] uncompressed;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            uncompressed = gzip.readAllBytes();
        }
        if (!VANILLA_STATES_SHA256.equals(sha256(uncompressed))) {
            throw new IllegalStateException("Bundled vanilla state SHA-256 mismatch");
        }
        List<CanonicalSource> sources = new ArrayList<>(VANILLA_STATE_COUNT);
        Set<String> canonicalStates = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(uncompressed), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || line.indexOf('\t', tab + 1) >= 0 || line.indexOf('\r') >= 0) {
                    throw new IllegalStateException("Vanilla source line is not two-field TSV");
                }
                int id = Integer.parseInt(line.substring(0, tab));
                if (id != sources.size()) {
                    throw new IllegalStateException("Vanilla source IDs are not dense at " + sources.size());
                }
                String canonical = line.substring(tab + 1);
                if (!canonicalStates.add(canonical)) {
                    throw new IllegalStateException("Duplicate vanilla source state " + canonical);
                }
                sources.add(parseCanonicalState(id, canonical));
            }
        }
        if (sources.size() != VANILLA_STATE_COUNT) {
            throw new IllegalStateException("Vanilla source count mismatch: " + sources.size());
        }
        return new VanillaSource(List.copyOf(sources));
    }

    private static CanonicalSource parseCanonicalState(int id, String canonical) {
        int bracket = canonical.indexOf('[');
        String blockText = bracket < 0 ? canonical : canonical.substring(0, bracket);
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        if (bracket >= 0) {
            if (!canonical.endsWith("]") || bracket == 0) {
                throw new IllegalStateException("Invalid canonical state " + canonical);
            }
            String previousName = null;
            for (String field : canonical.substring(bracket + 1, canonical.length() - 1).split(",", -1)) {
                int equals = field.indexOf('=');
                if (equals <= 0 || equals == field.length() - 1 || field.indexOf('=', equals + 1) >= 0) {
                    throw new IllegalStateException("Invalid canonical property in " + canonical);
                }
                String name = field.substring(0, equals);
                if (previousName != null && previousName.compareTo(name) >= 0) {
                    throw new IllegalStateException("Canonical properties are not sorted in " + canonical);
                }
                properties.put(name, field.substring(equals + 1));
                previousName = name;
            }
        }
        ResourceLocation blockId = ResourceLocation.parse(blockText);
        if (!"minecraft".equals(blockId.getNamespace())) {
            throw new IllegalStateException("Vanilla source contains non-minecraft block " + blockId);
        }
        return new CanonicalSource(id, blockId, Collections.unmodifiableMap(properties), canonical);
    }

    private static String canonicalState(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
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
                canonical.append(property.getName()).append('=').append(propertyValueName(state, property));
            }
            canonical.append(']');
        }
        return canonical.toString();
    }

    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static BlockState setProperty(BlockState state, Property<?> property, String encodedValue) {
        return setPropertyTyped(state, property, encodedValue);
    }

    private static <T extends Comparable<T>> BlockState setPropertyTyped(
            BlockState state, Property<T> property, String encodedValue) {
        T value = property.getValue(encodedValue)
                .orElseThrow(() -> new IllegalStateException("Invalid value " + encodedValue
                        + " for live property " + property.getName()));
        return state.setValue(property, value);
    }

    private static void writePackManifest(
            Path staging,
            MinecraftServer server,
            String packId,
            String displayName,
            String sourceUrl,
            Optional<SourceEvidence> source,
            VanillaSource vanilla,
            NetworkExport network,
            ModsExport mods,
            RegistryExport wire,
            RegistryExport selfContained,
            TagsExport tags,
            ServerConfigsExport serverConfigs,
            BlockStatesExport blockStates) throws IOException {
        StringBuilder manifest = new StringBuilder()
                .append("obelisk-pack-format=").append(PACK_FORMAT_VERSION).append('\n')
                .append("pack-id=").append(packId).append('\n')
                .append("display-name=").append(sanitizeProperty(displayName)).append('\n')
                .append("generator=").append(MOD_ID).append('\n')
                .append("generator-version=").append(TOOL_VERSION).append('\n')
                .append("minecraft=").append(SharedConstants.getCurrentVersion().getName()).append('\n')
                .append("minecraft-protocol=").append(SharedConstants.getProtocolVersion()).append('\n')
                .append("neoforge=").append(NeoForgeVersion.getVersion()).append('\n')
                .append("runtime-kind=pure-neoforge\n")
                .append("runtime-brand=").append(sanitizeProperty(server.getServerModName())).append('\n')
                .append("source.url=").append(sanitizeProperty(sourceUrl)).append('\n');
        if (source.isPresent()) {
            manifest.append("source.file=").append(sanitizeProperty(source.get().name())).append('\n')
                    .append("source.bytes=").append(source.get().bytes()).append('\n')
                    .append("source.sha256=").append(source.get().sha256()).append('\n');
        }
        manifest.append("mods.count=").append(mods.count()).append('\n')
                .append("network.file=network/server-query.bin\n")
                .append("network.channel-count=").append(network.channelCount()).append('\n')
                .append("network.bytes=").append(network.bytes()).append('\n')
                .append("network.sha256=").append(network.sha256()).append('\n')
                .append("vanilla-source.count=").append(vanilla.states().size()).append('\n')
                .append("vanilla-source.sha256=").append(VANILLA_STATES_SHA256).append('\n')
                .append("registries.wire.packet-count=").append(wire.packets().size()).append('\n')
                .append("registries.wire.total-entries=").append(wire.totalEntries()).append('\n')
                .append("registries.wire.sequence-sha256=").append(wire.sequenceSha256()).append('\n')
                .append("registries.self-contained.sequence-sha256=")
                .append(selfContained.sequenceSha256()).append('\n')
                .append("tags.registry-count=").append(tags.registryCount()).append('\n')
                .append("tags.tag-count=").append(tags.tagCount()).append('\n')
                .append("tags.sha256=").append(tags.sha256()).append('\n')
                .append("server-configs.count=").append(serverConfigs.configs().size()).append('\n')
                .append("server-configs.total-encoded-bytes=")
                .append(serverConfigs.totalPayloadBytes()).append('\n')
                .append("server-configs.name-sequence-sha256=")
                .append(serverConfigs.nameSequenceSha256()).append('\n')
                .append("server-configs.payload-sequence-sha256=")
                .append(serverConfigs.payloadSequenceSha256()).append('\n')
                .append("block-states.global-count=").append(blockStates.globalCount()).append('\n')
                .append("block-states.minecraft-count=").append(blockStates.minecraftCount()).append('\n')
                .append("block-states.extra-vanilla-property-count=")
                .append(blockStates.extraPropertyCount()).append('\n')
                .append("block-states.map-maximum-target-id=").append(blockStates.maximumTarget()).append('\n')
                .append("block-states.map-target-ids-strictly-increasing=")
                .append(blockStates.targetsStrictlyIncreasing()).append('\n')
                .append("block-states.map-sha256=").append(blockStates.mapSha256()).append('\n')
                .append("block-states.descriptor-set-sha256=")
                .append(blockStates.descriptorSetSha256()).append('\n')
                .append("deterministic-content=true\n");
        writeString(staging.resolve("pack.properties"), manifest.toString());
    }

    private static String sanitizeProperty(String value) {
        StringBuilder safe = new StringBuilder();
        for (char character : value.toCharArray()) {
            safe.append(character >= 0x20 && character < 0x7F && character != '\\' ? character : '_');
        }
        return safe.toString();
    }

    private static <T> byte[] encodeDeterministically(
            StreamCodec<? super FriendlyByteBuf, T> codec, T value, String description) {
        byte[] first = encode(codec, value);
        byte[] second = encode(codec, value);
        if (!MessageDigest.isEqual(first, second)) {
            throw new IllegalStateException(description + " is not deterministic");
        }
        return first;
    }

    private static <T> byte[] encode(StreamCodec<? super FriendlyByteBuf, T> codec, T value) {
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

    private static <T> T decode(StreamCodec<? super FriendlyByteBuf, T> codec, byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            T decoded = codec.decode(buffer);
            if (buffer.isReadable()) {
                throw new IllegalStateException("Codec left " + buffer.readableBytes() + " trailing bytes");
            }
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static String entrySequenceSha256(List<RegistrySynchronization.PackedRegistryEntry> entries) {
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
                digest.update(buffer, 0, read);
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
            System.err.println(LOG_PREFIX + "staging cleanup failed: " + cleanupFailure);
        }
    }

    private record SourceEvidence(String name, long bytes, String sha256) {
    }

    private record NetworkExport(int channelCount, int bytes, String sha256) {
    }

    private record ModsExport(int count) {
    }

    private record VanillaSource(List<CanonicalSource> states) {
    }

    private record CanonicalSource(
            int id, ResourceLocation blockId, Map<String, String> properties, String canonical) {
    }

    private record RegistryPacket(
            int index,
            ResourceKey<? extends Registry<?>> registryKey,
            String fileName,
            List<RegistrySynchronization.PackedRegistryEntry> entries,
            byte[] bytes,
            String sha256,
            String entrySequenceSha256) {
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
            String sequenceSha256) {
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
            byte[] encodedPayload) {
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
            String payloadSequenceSha256) {
    }

    private record BlockStatesExport(
            int globalCount,
            int minecraftCount,
            int extraPropertyCount,
            int maximumTarget,
            boolean targetsStrictlyIncreasing,
            String descriptorSetSha256,
            String mapSha256) {
    }
}
