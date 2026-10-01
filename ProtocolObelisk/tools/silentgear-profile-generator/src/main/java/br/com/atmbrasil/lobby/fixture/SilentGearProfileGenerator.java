package br.com.atmbrasil.lobby.fixture;

import dev.shadowsoffire.apothic_enchanting.ApothicEnchanting;
import dev.shadowsoffire.apothic_enchanting.payloads.EnchantmentInfoPayload;
import io.netty.buffer.Unpooled;
import mekanism.common.network.to_client.security.PacketBatchSecurityUpdate;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.ConfigSync;
import net.neoforged.neoforge.network.payload.FrozenRegistryPayload;
import net.neoforged.neoforge.network.payload.FrozenRegistrySyncCompletedPayload;
import net.neoforged.neoforge.network.payload.FrozenRegistrySyncStartPayload;
import net.neoforged.neoforge.network.payload.ConfigFilePayload;
import net.neoforged.neoforge.registries.RegistryManager;
import net.silentchaos512.gear.network.payload.server.SyncMaterialsPayload;
import net.silentchaos512.gear.network.payload.server.SyncPartsPayload;
import net.silentchaos512.gear.network.payload.server.SyncTraitsPayload;
import net.silentchaos512.gear.setup.SgRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Build-only NeoForge mod that serializes the reviewed compatibility payloads with
 * their own codecs and the live ATM10 8.0 registry context.
 * It is never shipped in, or required by, the Velocity/Paper runtime deployment.
 */
@Mod(SilentGearProfileGenerator.MOD_ID)
@EventBusSubscriber(modid = SilentGearProfileGenerator.MOD_ID)
public final class SilentGearProfileGenerator {
    public static final String MOD_ID = "atm10_sg_profile_generator";
    private static final String OUTPUT_PROPERTY = "atm10.sgFixtureOutput";
    private static final String SERVER_FILES_SHA256 =
            "2150885deb54f97a63a17291b34706eaa713f2b0a5e31ac701c846383db306cc";
    private static final String TRUNCATED_CATACLYSM_SHA256 =
            "37e4ff18ce0504c9d21dd4611b021549eaa0da9f5ad6db463953ff13ee04e122";
    private static final String RESTORED_CATACLYSM_SHA256 =
            "679c8687281cdac01e80de1672f27db1baf6ad5c68c74d752806b9d5ce246ac3";
    private static final String CLIENT_QUERY_SHA256 =
            "defcad0781fa208aa5ecb9358831d140eb530a499066e8694833989fb6dbdd4c";
    private static final String FULL_CLIENT_CONTRACT_SHA256 =
            "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f";
    private static final String SILENT_GEAR_CONTRACT_SHA256 =
            "003a1f69d13a92e3a9d6c70288dc38e6fc1e29c0c8df4513cbb06653736e0d44";
    private static final ResourceKey<Registry<Object>> NEOVITAE_SENTIENT_UPGRADES =
            ResourceKey.createRegistryKey(ResourceLocation.parse("neovitae:sentient_upgrades"));
    private static final Set<ResourceLocation> REQUIRED_NEOVITAE_SENTIENT_TAGS = Set.of(
            ResourceLocation.parse("neovitae:sentient_start"),
            ResourceLocation.parse("neovitae:tooltip_order"),
            ResourceLocation.parse("neovitae:trainer")
    );

    private static final int MAXIMUM_SINGLE_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAXIMUM_TOTAL_EXPORT_BYTES = 64 * 1024 * 1024;
    private static final int MAXIMUM_FROZEN_REGISTRIES = 512;
    private static final int MAXIMUM_DYNAMIC_REGISTRIES = 512;
    private static final int MAXIMUM_SERVER_CONFIGS = 1_024;
    private static final int MAXIMUM_REGISTRY_ENTRIES = 1_000_000;
    private static final int MAXIMUM_GLOBAL_BLOCK_STATES = 2_000_000;
    private static final int MAXIMUM_EXPORTED_MINECRAFT_BLOCK_STATES = 100_000;
    private static final int MAXIMUM_BLOCK_STATE_MAP_BYTES = 16 * 1024 * 1024;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        String configuredOutput = System.getProperty(OUTPUT_PROPERTY, "").trim();
        if (configuredOutput.isEmpty()) {
            throw new IllegalStateException("Missing required -D" + OUTPUT_PROPERTY + "=<directory>");
        }

        Path output = Path.of(configuredOutput).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalStateException("Fixture output must have a parent directory");
        }
        Path staging = parent.resolve("." + output.getFileName() + ".tmp-" + UUID.randomUUID());
        RegistryAccess registries = event.getServer().registryAccess();

        try {
            if (Files.exists(output)) {
                throw new IllegalStateException("Refusing to replace existing fixture output " + output);
            }
            Files.createDirectories(parent);
            Files.createDirectory(staging);

            Fixture traits = generate(
                    "traits.bin",
                    SyncTraitsPayload.STREAM_CODEC,
                    () -> {
                        SyncTraitsPayload payload = new SyncTraitsPayload();
                        return new SyncTraitsPayload(sortedResourceMap(payload.traits()));
                    },
                    payload -> payload.traits().size(),
                    payload -> SgRegistries.TRAIT.handleSyncPacket(payload, null),
                    registries
            );
            Fixture materials = generate(
                    "materials.bin",
                    SyncMaterialsPayload.STREAM_CODEC,
                    () -> {
                        SyncMaterialsPayload payload = new SyncMaterialsPayload();
                        return new SyncMaterialsPayload(sortedResourceMap(payload.materials()));
                    },
                    payload -> payload.materials().size(),
                    payload -> SgRegistries.MATERIAL.handleSyncPacket(payload, null),
                    registries
            );
            Fixture parts = generate(
                    "parts.bin",
                    SyncPartsPayload.STREAM_CODEC,
                    () -> {
                        SyncPartsPayload payload = new SyncPartsPayload();
                        return new SyncPartsPayload(sortedResourceMap(payload.parts()));
                    },
                    payload -> payload.parts().size(),
                    payload -> SgRegistries.PART.handleSyncPacket(payload, null),
                    registries
            );

            List<FrozenFixture> frozenRegistries = generateFrozenRegistries();
            DynamicRegistryExport dynamicRegistries = generateDynamicRegistries(
                    event.getServer().registries(),
                    event.getServer().getResourceManager().listPacks().toList()
            );
            RegistryTagFixture dynamicRegistryTags = generateDynamicRegistryTags(
                    event.getServer().registries());
            List<ResourceLocation> registryNames = frozenRegistries.stream()
                    .map(FrozenFixture::registryName)
                    .toList();
            PlainFixture frozenStart = generatePlain(
                    "frozen-registry-start.bin",
                    FrozenRegistrySyncStartPayload.STREAM_CODEC,
                    new FrozenRegistrySyncStartPayload(registryNames)
            );
            PlainFixture frozenCompleted = generatePlain(
                    "frozen-registry-completed.bin",
                    FrozenRegistrySyncCompletedPayload.STREAM_CODEC,
                    FrozenRegistrySyncCompletedPayload.INSTANCE
            );

            List<ConfigFixture> serverConfigs = generateServerConfigs();
            RegistryFixture apothicEnchanting = generateRegistryFixture(
                    "apothic-enchanting-info.bin",
                    EnchantmentInfoPayload.STREAM_CODEC,
                    new EnchantmentInfoPayload(ApothicEnchanting.ENCHANTMENT_INFO.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey(Comparator.comparing(holder -> holder
                                    .unwrapKey().orElseThrow().location().toString())))
                            .collect(LinkedHashMap::new,
                                    (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                                    LinkedHashMap::putAll)),
                    ApothicEnchanting.ENCHANTMENT_INFO.size(),
                    EnchantmentInfoPayload::info,
                    registries
            );
            PlainFixture mekanismSecurity = generatePlain(
                    "mekanism-batch-security.bin",
                    PacketBatchSecurityUpdate.STREAM_CODEC,
                    new PacketBatchSecurityUpdate()
            );
            BlockStateFixture blockStates = generateBlockStateMap();

            validateBounds(
                    traits,
                    materials,
                    parts,
                    frozenStart,
                    frozenRegistries,
                    frozenCompleted,
                    dynamicRegistries,
                    dynamicRegistryTags,
                    serverConfigs,
                    apothicEnchanting,
                    mekanismSecurity,
                    blockStates
            );

            Files.write(staging.resolve(traits.fileName()), traits.bytes());
            Files.write(staging.resolve(materials.fileName()), materials.bytes());
            Files.write(staging.resolve(parts.fileName()), parts.bytes());
            Files.write(staging.resolve(frozenStart.fileName()), frozenStart.bytes());
            Files.createDirectories(staging.resolve("frozen-registries"));
            for (FrozenFixture fixture : frozenRegistries) {
                Files.write(staging.resolve(fixture.fileName()), fixture.bytes());
            }
            Files.createDirectories(staging.resolve("dynamic-registries"));
            for (DynamicRegistryFixture fixture : dynamicRegistries.fixtures()) {
                Files.write(staging.resolve(fixture.fileName()), fixture.bytes());
            }
            Files.writeString(
                    staging.resolve("dynamic-registries.properties"),
                    dynamicRegistryManifest(dynamicRegistries),
                    StandardCharsets.UTF_8
            );
            Files.write(staging.resolve(dynamicRegistryTags.fileName()), dynamicRegistryTags.bytes());
            Files.writeString(
                    staging.resolve("dynamic-registry-tags.properties"),
                    dynamicRegistryTagManifest(dynamicRegistryTags),
                    StandardCharsets.UTF_8
            );
            Files.createDirectories(staging.resolve("server-configs"));
            for (ConfigFixture fixture : serverConfigs) {
                Files.write(staging.resolve(fixture.fileName()), fixture.encodedPayload());
            }
            Files.writeString(
                    staging.resolve("server-configs.properties"),
                    serverConfigManifest(serverConfigs),
                    StandardCharsets.UTF_8
            );
            Files.write(staging.resolve(apothicEnchanting.fileName()), apothicEnchanting.bytes());
            Files.write(staging.resolve(mekanismSecurity.fileName()), mekanismSecurity.bytes());
            Files.write(staging.resolve(blockStates.fileName()), blockStates.bytes());
            Files.write(staging.resolve(frozenCompleted.fileName()), frozenCompleted.bytes());
            Files.writeString(
                    staging.resolve("generated-fixture.properties"),
                    manifest(
                            traits,
                            materials,
                            parts,
                            frozenStart,
                            frozenRegistries,
                            frozenCompleted,
                            dynamicRegistries,
                            dynamicRegistryTags,
                            serverConfigs,
                            apothicEnchanting,
                            mekanismSecurity,
                            blockStates),
                    StandardCharsets.UTF_8
            );

            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException(
                        "Fixture filesystem does not support atomic directory publication", exception);
            }

            System.out.println("[ATM10-SG-FIXTURE] exact profile generated at " + output);
            logFixture("traits", traits);
            logFixture("materials", materials);
            logFixture("parts", parts);
            System.out.println("[ATM10-SG-FIXTURE] frozen registries=" + frozenRegistries.size()
                    + " entries=" + frozenRegistries.stream().mapToInt(FrozenFixture::entries).sum()
                    + " bytes=" + frozenRegistries.stream().mapToInt(fixture -> fixture.bytes().length).sum()
                    + " sequenceSha256=" + sequenceSha256(frozenStart, frozenRegistries, frozenCompleted));
            System.out.println("[ATM10-SG-FIXTURE] modded dynamic registries="
                    + dynamicRegistries.fixtures().size()
                    + " entries=" + dynamicRegistries.fixtures().stream()
                            .mapToInt(DynamicRegistryFixture::entries).sum()
                    + " bytes=" + dynamicRegistries.fixtures().stream()
                            .mapToInt(fixture -> fixture.bytes().length).sum()
                    + " sequenceSha256=" + dynamicRegistries.sequenceSha256()
                    + " knownPacks=" + dynamicRegistries.knownPacks());
            System.out.println("[ATM10-SG-FIXTURE] dynamic registry tags registry="
                    + dynamicRegistryTags.registryId()
                    + " tags=" + dynamicRegistryTags.tags().size()
                    + " members=" + dynamicRegistryTags.tags().stream()
                            .mapToInt(tag -> tag.memberIds().length).sum()
                    + " bytes=" + dynamicRegistryTags.bytes().length
                    + " sha256=" + dynamicRegistryTags.sha256());
            System.out.println("[ATM10-SG-FIXTURE] SERVER configs=" + serverConfigs.size()
                    + " encodedBytes=" + serverConfigs.stream()
                            .mapToInt(fixture -> fixture.encodedPayload().length).sum()
                    + " sequenceSha256=" + configSequenceSha256(serverConfigs));
            System.out.println("[ATM10-SG-FIXTURE] Apothic Enchanting entries="
                    + apothicEnchanting.entries()
                    + " bytes=" + apothicEnchanting.bytes().length
                    + " sha256=" + apothicEnchanting.sha256());
            System.out.println("[ATM10-SG-FIXTURE] Mekanism batch security bytes="
                    + mekanismSecurity.bytes().length
                    + " sha256=" + mekanismSecurity.sha256());
            System.out.println("[ATM10-SG-FIXTURE] global block states="
                    + blockStates.globalEntries()
                    + " exportedMinecraftStates=" + blockStates.entries()
                    + " bytes=" + blockStates.bytes().length
                    + " sha256=" + blockStates.sha256());
            System.out.println("[ATM10-SG-FIXTURE] deterministic source encoding, full decode, and semantic round-trip validation passed");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write compatibility fixture to " + output, exception);
        } finally {
            event.getServer().halt(false);
        }
    }

    /**
     * Exports the exact standard CONFIG registry-data packets that a matching
     * NeoForge server contributes in addition to a vanilla 1.21.1 server.
     * Entries that exist in the exact vanilla known pack are deliberately omitted
     * so these packets can be appended after Paper's own registry stream without
     * duplicating IDs. This is an exact registry/id comparison rather than a
     * namespace shortcut: a mod-added {@code minecraft:*} id that is absent from
     * the vanilla pack remains eligible for export.
     */
    private static DynamicRegistryExport generateDynamicRegistries(
            net.minecraft.core.LayeredRegistryAccess<RegistryLayer> layeredRegistries,
            List<PackResources> requestedPacks
    ) {
        RegistryAccess.Frozen composite = layeredRegistries.compositeAccess();
        // A vanilla Paper lobby only selects the vanilla known pack. NeoForge
        // mod packs advertised by the reference server are not part of that
        // negotiation, so their registry values must remain fully encoded.
        List<PackResources> paperSelectedPackResources = requestedPacks.stream()
                .filter(pack -> pack.knownPackInfo().filter(KnownPack::isVanilla).isPresent())
                .sorted(Comparator.comparing(pack -> pack.knownPackInfo().orElseThrow().toString()))
                .toList();
        List<KnownPack> paperSelectedKnownPacks = paperSelectedPackResources.stream()
                .map(pack -> pack.knownPackInfo().orElseThrow())
                .toList();
        requireExactVanillaKnownPack(paperSelectedKnownPacks);

        Set<DynamicRegistryEntryKey> knownPackEntries = new TreeSet<>();
        Set<DynamicRegistryEntryKey> omittedKnownPackEntries = new TreeSet<>();
        List<DynamicRegistryFixture> fixtures = new ArrayList<>();
        RegistrySynchronization.packRegistries(
                composite.createSerializationContext(NbtOps.INSTANCE),
                layeredRegistries.getAccessFrom(RegistryLayer.WORLDGEN),
                Set.copyOf(paperSelectedKnownPacks),
                (registryKey, packedEntries) -> {
                    Set<ResourceLocation> registryKnownPackEntries = knownPackEntryIds(
                            registryKey, paperSelectedPackResources);
                    for (ResourceLocation entryId : registryKnownPackEntries) {
                        DynamicRegistryEntryKey key = new DynamicRegistryEntryKey(
                                registryKey.location(), entryId);
                        if (!knownPackEntries.add(key)) {
                            throw new IllegalStateException(
                                    "Duplicate known-pack registry/entry pair " + key);
                        }
                    }

                    List<RegistrySynchronization.PackedRegistryEntry> encodedEntries = packedEntries.stream()
                            .filter(entry -> entry.data().isPresent())
                            .toList();
                    List<RegistrySynchronization.PackedRegistryEntry> extensionEntries = encodedEntries.stream()
                            .filter(entry -> !registryKnownPackEntries.contains(entry.id()))
                            .toList();
                    for (RegistrySynchronization.PackedRegistryEntry entry : encodedEntries) {
                        if (!registryKnownPackEntries.contains(entry.id())) {
                            continue;
                        }
                        DynamicRegistryEntryKey key = new DynamicRegistryEntryKey(
                                registryKey.location(), entry.id());
                        if (!omittedKnownPackEntries.add(key)) {
                            throw new IllegalStateException(
                                    "Duplicate omitted known-pack registry/entry pair " + key);
                        }
                    }

                    if (extensionEntries.isEmpty()) {
                        return;
                    }
                    if (extensionEntries.stream()
                            .anyMatch(entry -> registryKnownPackEntries.contains(entry.id()))) {
                        throw new IllegalStateException(
                                "Dynamic registry extension intersects vanilla known pack: "
                                        + registryKey.location());
                    }
                    byte[] bytes = encodeRegistryData(registryKey, extensionEntries);
                    String safeName = registryKey.location().toString().replace(':', '_').replace('/', '_');
                    fixtures.add(new DynamicRegistryFixture(
                            registryKey,
                            "dynamic-registries/" + safeName + ".bin",
                            extensionEntries.size(),
                            (int) extensionEntries.stream().filter(entry -> entry.data().isPresent()).count(),
                            bytes,
                            sha256(bytes)
                    ));
                }
        );
        fixtures.sort(Comparator.comparing(fixture -> fixture.registryKey().location().toString()));
        if (fixtures.isEmpty()) {
            throw new IllegalStateException("NeoForge resolved no modded CONFIG dynamic-registry entries");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fixtures.forEach(fixture -> digest.update(fixture.bytes()));
            return new DynamicRegistryExport(
                    paperSelectedKnownPacks,
                    List.copyOf(knownPackEntries),
                    List.copyOf(omittedKnownPackEntries),
                    List.copyOf(fixtures),
                    HexFormat.of().formatHex(digest.digest())
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireExactVanillaKnownPack(List<KnownPack> knownPacks) {
        if (knownPacks.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one vanilla known pack, found " + knownPacks);
        }
        KnownPack pack = knownPacks.getFirst();
        if (!"minecraft".equals(pack.namespace())
                || !"core".equals(pack.id())
                || !"1.21.1".equals(pack.version())) {
            throw new IllegalStateException("Unexpected vanilla known pack " + pack);
        }
    }

    private static Set<ResourceLocation> knownPackEntryIds(
            ResourceKey<? extends Registry<?>> registryKey,
            List<PackResources> knownPackResources
    ) {
        String directory = registryKey.location().getPath();
        String prefix = directory + '/';
        TreeSet<ResourceLocation> entries = new TreeSet<>();
        for (PackResources pack : knownPackResources) {
            TreeSet<String> namespaces = new TreeSet<>(pack.getNamespaces(PackType.SERVER_DATA));
            for (String namespace : namespaces) {
                pack.listResources(PackType.SERVER_DATA, namespace, directory, (file, ignored) -> {
                    String path = file.getPath();
                    if (!path.startsWith(prefix) || !path.endsWith(".json")) {
                        return;
                    }
                    String entryPath = path.substring(prefix.length(), path.length() - ".json".length());
                    if (entryPath.isEmpty()) {
                        throw new IllegalStateException(
                                "Empty known-pack registry entry path for " + registryKey.location());
                    }
                    ResourceLocation entryId = ResourceLocation.fromNamespaceAndPath(
                            file.getNamespace(), entryPath);
                    if (!entries.add(entryId)) {
                        throw new IllegalStateException(
                                "Duplicate known-pack registry entry "
                                        + registryKey.location() + " / " + entryId);
                    }
                });
            }
        }
        return Set.copyOf(entries);
    }

    private static byte[] encodeRegistryData(
            ResourceKey<? extends Registry<?>> registryKey,
            List<RegistrySynchronization.PackedRegistryEntry> entries
    ) {
        ClientboundRegistryDataPacket packet = new ClientboundRegistryDataPacket(registryKey, entries);
        byte[] first = encodePlain(ClientboundRegistryDataPacket.STREAM_CODEC, packet);
        byte[] second = encodePlain(ClientboundRegistryDataPacket.STREAM_CODEC, packet);
        if (!MessageDigest.isEqual(first, second)) {
            throw new IllegalStateException("Dynamic registry is not deterministic: " + registryKey.location());
        }
        ClientboundRegistryDataPacket decoded = decodePlain(
                ClientboundRegistryDataPacket.STREAM_CODEC, first);
        if (!packet.registry().equals(decoded.registry()) || !packet.entries().equals(decoded.entries())) {
            throw new IllegalStateException(
                    "Dynamic registry changed semantically after decode: " + registryKey.location());
        }
        // CompoundTag preserves semantic equality but may canonicalize nested
        // map iteration order on the first network decode.  Use that canonical
        // form and require it to be byte-stable on a second decode/re-encode.
        byte[] canonical = encodePlain(ClientboundRegistryDataPacket.STREAM_CODEC, decoded);
        ClientboundRegistryDataPacket canonicalDecoded = decodePlain(
                ClientboundRegistryDataPacket.STREAM_CODEC, canonical);
        byte[] canonicalRoundTrip = encodePlain(
                ClientboundRegistryDataPacket.STREAM_CODEC, canonicalDecoded);
        if (!decoded.equals(canonicalDecoded)
                || !MessageDigest.isEqual(canonical, canonicalRoundTrip)) {
            throw new IllegalStateException(
                    "Dynamic registry is not canonical after decode/re-encode: "
                            + registryKey.location());
        }
        return canonical;
    }

    private static String dynamicRegistryManifest(DynamicRegistryExport export) {
        StringBuilder manifest = new StringBuilder("format-version=3\n")
                .append("pack=ATM10-8.0\n")
                .append("minecraft=1.21.1\n")
                .append("neoforge=21.1.247\n")
                .append("selection=encoded-entries-minus-exact-vanilla-known-pack-entry-ids\n")
                .append("known-pack.count=").append(export.knownPacks().size()).append('\n');
        for (int index = 0; index < export.knownPacks().size(); index++) {
            KnownPack pack = export.knownPacks().get(index);
            manifest.append("known-pack.").append(index).append('=')
                    .append(pack.namespace()).append(':').append(pack.id()).append(':')
                    .append(pack.version()).append('\n');
        }
        manifest.append("known-pack-entry.count=").append(export.knownPackEntries().size()).append('\n')
                .append("known-pack-entry.sequence-sha256=")
                .append(entryKeySequenceSha256(export.knownPackEntries())).append('\n')
                .append("omitted-known-pack-entry.count=")
                .append(export.omittedKnownPackEntries().size()).append('\n')
                .append("omitted-known-pack-entry.sequence-sha256=")
                .append(entryKeySequenceSha256(export.omittedKnownPackEntries())).append('\n');
        for (int index = 0; index < export.omittedKnownPackEntries().size(); index++) {
            DynamicRegistryEntryKey entry = export.omittedKnownPackEntries().get(index);
            String key = "omitted-known-pack-entry." + index;
            manifest.append(key).append(".registry=").append(entry.registryId()).append('\n')
                    .append(key).append(".entry=").append(entry.entryId()).append('\n');
        }
        manifest.append("registry.count=").append(export.fixtures().size()).append('\n')
                .append("registry.total-entries=")
                .append(export.fixtures().stream().mapToInt(DynamicRegistryFixture::entries).sum())
                .append('\n')
                .append("registry.total-encoded-data-entries=")
                .append(export.fixtures().stream().mapToInt(DynamicRegistryFixture::encodedDataEntries).sum())
                .append('\n')
                .append("registry.total-bytes=")
                .append(export.fixtures().stream().mapToInt(fixture -> fixture.bytes().length).sum())
                .append('\n')
                .append("registry.sequence-sha256=").append(export.sequenceSha256()).append('\n');
        for (int index = 0; index < export.fixtures().size(); index++) {
            DynamicRegistryFixture fixture = export.fixtures().get(index);
            String key = "registry." + index;
            manifest.append(key).append(".name=").append(fixture.registryKey().location()).append('\n')
                    .append(key).append(".file=").append(fixture.fileName()).append('\n')
                    .append(key).append(".entries=").append(fixture.entries()).append('\n')
                    .append(key).append(".encoded-data-entries=")
                    .append(fixture.encodedDataEntries()).append('\n')
                    .append(key).append(".bytes=").append(fixture.bytes().length).append('\n')
                    .append(key).append(".sha256=").append(fixture.sha256()).append('\n');
        }
        return manifest.toString();
    }

    private static String entryKeySequenceSha256(List<DynamicRegistryEntryKey> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            entries.forEach(entry -> digest.update((entry.registryId()
                    + "\t" + entry.entryId() + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Exports the standard CONFIG Update Tags body for the dynamic NeoVitae registry.
     * Paper cannot advertise tags for a registry that it does not own, while NeoVitae's
     * inventory tabs require these tags immediately after configuration. The complete tag
     * set for this one registry is retained; no tag membership is guessed or synthesized.
     */
    private static RegistryTagFixture generateDynamicRegistryTags(
            net.minecraft.core.LayeredRegistryAccess<RegistryLayer> layeredRegistries
    ) {
        RegistryAccess.Frozen composite = layeredRegistries.compositeAccess();
        Registry<Object> registry = composite.registryOrThrow(NEOVITAE_SENTIENT_UPGRADES);
        List<RegistryTagEntry> tagEntries = registry.getTags()
                .map(pair -> {
                    ResourceLocation tagId = pair.getFirst().location();
                    int[] memberIds = pair.getSecond().stream()
                            .mapToInt(holder -> {
                                int numericId = registry.getId(holder.value());
                                if (numericId < 0) {
                                    throw new IllegalStateException(
                                            "Unregistered member in NeoVitae tag " + tagId);
                                }
                                return numericId;
                            })
                            .toArray();
                    if (memberIds.length == 0) {
                        throw new IllegalStateException("Empty NeoVitae tag " + tagId);
                    }
                    return new RegistryTagEntry(tagId, memberIds);
                })
                .sorted(Comparator.comparing(entry -> entry.tagId().toString()))
                .toList();
        if (tagEntries.isEmpty()) {
            throw new IllegalStateException("NeoVitae sentient-upgrades registry has no bound tags");
        }
        Set<ResourceLocation> actualTagIds = tagEntries.stream()
                .map(RegistryTagEntry::tagId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actualTagIds.containsAll(REQUIRED_NEOVITAE_SENTIENT_TAGS)) {
            Set<ResourceLocation> missing = new TreeSet<>(REQUIRED_NEOVITAE_SENTIENT_TAGS);
            missing.removeAll(actualTagIds);
            throw new IllegalStateException("NeoVitae inventory-required tags are missing: " + missing);
        }
        for (ResourceLocation requiredTag : REQUIRED_NEOVITAE_SENTIENT_TAGS) {
            TagKey<Object> key = TagKey.create(NEOVITAE_SENTIENT_UPGRADES, requiredTag);
            if (registry.getTag(key).orElseThrow(() -> new IllegalStateException(
                    "NeoVitae required tag is not bound: " + requiredTag)).size() == 0) {
                throw new IllegalStateException("NeoVitae required tag is empty: " + requiredTag);
            }
        }

        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> allTags =
                TagNetworkSerialization.serializeTagsToNetwork(layeredRegistries);
        TagNetworkSerialization.NetworkPayload sentientTags = allTags.get(NEOVITAE_SENTIENT_UPGRADES);
        if (sentientTags == null || sentientTags.size() != tagEntries.size()) {
            throw new IllegalStateException(
                    "Serialized NeoVitae tag set differs from the live registry: serialized="
                            + (sentientTags == null ? 0 : sentientTags.size())
                            + ", live=" + tagEntries.size());
        }
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> singleton =
                new LinkedHashMap<>();
        singleton.put(NEOVITAE_SENTIENT_UPGRADES, sentientTags);
        ClientboundUpdateTagsPacket source = new ClientboundUpdateTagsPacket(singleton);
        byte[] first = encodePlain(ClientboundUpdateTagsPacket.STREAM_CODEC, source);
        byte[] second = encodePlain(ClientboundUpdateTagsPacket.STREAM_CODEC, source);
        if (!MessageDigest.isEqual(first, second)) {
            throw new IllegalStateException("dynamic-registry-tags.bin is not deterministic");
        }
        // The vanilla decoder materializes both maps in its own iteration order.
        // Canonicalize once and require the second decode/re-encode to be byte-stable,
        // mirroring the strict handling used for nested dynamic-registry NBT maps.
        ClientboundUpdateTagsPacket decoded = decodePlain(
                ClientboundUpdateTagsPacket.STREAM_CODEC, first);
        byte[] canonical = encodePlain(ClientboundUpdateTagsPacket.STREAM_CODEC, decoded);
        ClientboundUpdateTagsPacket canonicalDecoded = decodePlain(
                ClientboundUpdateTagsPacket.STREAM_CODEC, canonical);
        byte[] canonicalRoundTrip = encodePlain(
                ClientboundUpdateTagsPacket.STREAM_CODEC, canonicalDecoded);
        if (!MessageDigest.isEqual(canonical, canonicalRoundTrip)) {
            throw new IllegalStateException(
                    "dynamic-registry-tags.bin is not canonical after a second decode");
        }
        if (decoded.getTags().size() != 1
                || decoded.getTags().get(NEOVITAE_SENTIENT_UPGRADES) == null
                || decoded.getTags().get(NEOVITAE_SENTIENT_UPGRADES).size() != tagEntries.size()
                || canonicalDecoded.getTags().size() != 1
                || canonicalDecoded.getTags().get(NEOVITAE_SENTIENT_UPGRADES) == null
                || canonicalDecoded.getTags().get(NEOVITAE_SENTIENT_UPGRADES).size()
                        != tagEntries.size()) {
            throw new IllegalStateException("NeoVitae Update Tags changed semantically after decode");
        }
        return new RegistryTagFixture(
                "dynamic-registry-tags.bin",
                NEOVITAE_SENTIENT_UPGRADES.location(),
                tagEntries,
                canonical,
                sha256(canonical));
    }

    private static String dynamicRegistryTagManifest(RegistryTagFixture fixture) {
        StringBuilder manifest = new StringBuilder("format-version=1\n")
                .append("pack=ATM10-8.0\n")
                .append("minecraft=1.21.1\n")
                .append("neoforge=21.1.247\n")
                .append("packet=clientbound-update-tags\n")
                .append("lifecycle=configuration-after-dynamic-registry-tail\n")
                .append("registry.count=1\n")
                .append("registry.name=").append(fixture.registryId()).append('\n')
                .append("tag.count=").append(fixture.tags().size()).append('\n')
                .append("tag.total-members=").append(fixture.tags().stream()
                        .mapToInt(tag -> tag.memberIds().length).sum()).append('\n')
                .append("file=").append(fixture.fileName()).append('\n')
                .append("bytes=").append(fixture.bytes().length).append('\n')
                .append("sha256=").append(fixture.sha256()).append('\n');
        for (int index = 0; index < fixture.tags().size(); index++) {
            RegistryTagEntry tag = fixture.tags().get(index);
            String key = "tag." + index;
            manifest.append(key).append(".name=").append(tag.tagId()).append('\n')
                    .append(key).append(".members=").append(tag.memberIds().length).append('\n')
                    .append(key).append(".member-ids=")
                    .append(java.util.Arrays.stream(tag.memberIds())
                            .mapToObj(Integer::toString)
                            .collect(java.util.stream.Collectors.joining(",")))
                    .append('\n');
        }
        return manifest.toString();
    }

    private static List<FrozenFixture> generateFrozenRegistries() {
        List<FrozenRegistryPayload> payloads = RegistryManager.generateRegistryPackets(false).stream()
                .sorted(Comparator.comparing(payload -> payload.registryName().toString()))
                .toList();
        if (payloads.isEmpty()) {
            throw new IllegalStateException("NeoForge resolved no frozen registries for client sync");
        }

        Set<ResourceLocation> expected = new HashSet<>(RegistryManager.getRegistryNamesForSyncToClient());
        Set<ResourceLocation> actual = new HashSet<>();
        List<FrozenFixture> fixtures = new ArrayList<>(payloads.size());
        for (int index = 0; index < payloads.size(); index++) {
            FrozenRegistryPayload payload = payloads.get(index);
            if (!actual.add(payload.registryName())) {
                throw new IllegalStateException("Duplicate frozen registry " + payload.registryName());
            }

            byte[] first = encodePlain(FrozenRegistryPayload.STREAM_CODEC, payload);
            byte[] second = encodePlain(FrozenRegistryPayload.STREAM_CODEC, payload);
            if (!MessageDigest.isEqual(first, second)) {
                throw new IllegalStateException("Frozen registry is not deterministic: " + payload.registryName());
            }

            FrozenRegistryPayload decoded = decodePlain(FrozenRegistryPayload.STREAM_CODEC, first);
            if (!decoded.registryName().equals(payload.registryName())) {
                throw new IllegalStateException("Frozen registry name changed after decode: " + payload.registryName());
            }
            byte[] roundTrip = encodePlain(FrozenRegistryPayload.STREAM_CODEC, decoded);
            if (!MessageDigest.isEqual(first, roundTrip)) {
                throw new IllegalStateException("Frozen registry changed after decode/re-encode: " + payload.registryName());
            }

            String safeName = payload.registryName().toString().replace(':', '_').replace('/', '_');
            String fileName = "frozen-registries/%03d-%s.bin".formatted(index, safeName);
            fixtures.add(new FrozenFixture(
                    payload.registryName(),
                    fileName,
                    payload.snapshot().getIds().size(),
                    payload.snapshot().getAliases().size(),
                    first,
                    sha256(first)
            ));
        }

        if (!actual.equals(expected)) {
            Set<ResourceLocation> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            Set<ResourceLocation> unexpected = new HashSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalStateException("Frozen registry set differs from NeoForge sync set; missing="
                    + missing + ", unexpected=" + unexpected);
        }
        return List.copyOf(fixtures);
    }

    private static List<ConfigFixture> generateServerConfigs() {
        List<ConfigFilePayload> payloads = ConfigSync.syncConfigs().stream()
                .sorted(Comparator.comparing(ConfigFilePayload::fileName))
                .toList();
        if (payloads.isEmpty()) {
            throw new IllegalStateException("NeoForge resolved no SERVER configs");
        }
        if (payloads.size() > MAXIMUM_SERVER_CONFIGS) {
            throw new IllegalStateException("SERVER config count exceeds build-only bound");
        }

        Set<String> names = new HashSet<>();
        List<ConfigFixture> fixtures = new ArrayList<>(payloads.size());
        for (int index = 0; index < payloads.size(); index++) {
            ConfigFilePayload payload = payloads.get(index);
            if (!names.add(payload.fileName())) {
                throw new IllegalStateException("Duplicate SERVER config " + payload.fileName());
            }
            PlainFixture encoded = generatePlain(
                    "server-configs/%03d.bin".formatted(index),
                    ConfigFilePayload.STREAM_CODEC,
                    payload
            );
            fixtures.add(new ConfigFixture(
                    payload.fileName(),
                    encoded.fileName(),
                    payload.contents().length,
                    sha256(payload.contents()),
                    encoded.bytes(),
                    encoded.sha256()
            ));
        }
        return List.copyOf(fixtures);
    }

    /**
     * Exports the exact numeric IDs of the {@code minecraft:*} states from the
     * global block-state table rebuilt by NeoForge's frozen-registry bake
     * callback. A vanilla lobby cannot emit a mod block, so excluding mod states
     * keeps the build-only evidence bounded without discarding any translatable
     * Paper state. The original global ID is preserved on every exported row.
     */
    private static BlockStateFixture generateBlockStateMap() {
        int globalEntries = Block.BLOCK_STATE_REGISTRY.size();
        if (globalEntries <= 0 || globalEntries > MAXIMUM_GLOBAL_BLOCK_STATES) {
            throw new IllegalStateException("Global block-state count violates build-only bound: "
                    + globalEntries);
        }

        StringBuilder map = new StringBuilder(4 * 1024 * 1024);
        map.append("format-version=1\n")
                .append("scope=minecraft-namespace\n");
        Set<String> canonicalStates = new HashSet<>();
        for (int id = 0; id < globalEntries; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null || Block.getId(state) != id) {
                throw new IllegalStateException("Global block-state table is sparse at id " + id);
            }
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (blockId == null) {
                throw new IllegalStateException("Unregistered block for global state id " + id);
            }
            if (!"minecraft".equals(blockId.getNamespace())) {
                continue;
            }

            StringBuilder canonical = new StringBuilder(blockId.toString());
            List<Property<?>> properties = state.getProperties().stream()
                    .sorted(Comparator.comparing(Property::getName))
                    .toList();
            if (!properties.isEmpty()) {
                canonical.append('[');
                for (int propertyIndex = 0; propertyIndex < properties.size(); propertyIndex++) {
                    if (propertyIndex > 0) {
                        canonical.append(',');
                    }
                    Property<?> property = properties.get(propertyIndex);
                    canonical.append(property.getName()).append('=')
                            .append(propertyValueName(state, property));
                }
                canonical.append(']');
            }
            String canonicalState = canonical.toString();
            if (!canonicalStates.add(canonicalState)) {
                throw new IllegalStateException("Duplicate canonical global block state "
                        + canonicalState);
            }
            map.append(id).append('\t').append(canonicalState).append('\n');
        }

        int entries = canonicalStates.size();
        if (entries <= 0 || entries > MAXIMUM_EXPORTED_MINECRAFT_BLOCK_STATES) {
            throw new IllegalStateException("Exported minecraft block-state count violates "
                    + "build-only bound: " + entries);
        }

        byte[] first = map.toString().getBytes(StandardCharsets.UTF_8);
        byte[] second = map.toString().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(first, second)
                || first.length <= 0
                || first.length > MAXIMUM_BLOCK_STATE_MAP_BYTES) {
            throw new IllegalStateException("Global block-state map violates deterministic byte bound: "
                    + first.length);
        }
        return new BlockStateFixture(
                "minecraft-block-states.tsv", globalEntries, entries, first, sha256(first));
    }

    private static <T extends Comparable<T>> String propertyValueName(
            BlockState state,
            Property<T> property
    ) {
        return property.getName(state.getValue(property));
    }

    private static <T> RegistryFixture generateRegistryFixture(
            String fileName,
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            T value,
            int entries,
            Function<T, ?> semanticValue,
            RegistryAccess registries
    ) {
        if (entries <= 0) {
            throw new IllegalStateException(fileName + " resolved to an empty map");
        }
        byte[] first = encode(codec, value, registries);
        byte[] second = encode(codec, value, registries);
        if (!MessageDigest.isEqual(first, second)) {
            throw new IllegalStateException(fileName + " is not deterministic");
        }
        T decoded = decode(codec, first, registries);
        if (!semanticValue.apply(value).equals(semanticValue.apply(decoded))) {
            throw new IllegalStateException(fileName + " changed semantically after decode");
        }
        byte[] roundTrip = encode(codec, decoded, registries);
        T decodedAgain = decode(codec, roundTrip, registries);
        if (!semanticValue.apply(decoded).equals(semanticValue.apply(decodedAgain))) {
            throw new IllegalStateException(fileName + " changed semantically after a second decode");
        }
        byte[] canonicalAgain = encode(codec, decodedAgain, registries);
        if (!MessageDigest.isEqual(roundTrip, canonicalAgain)) {
            throw new IllegalStateException(fileName + " decoded canonical form is not byte-stable");
        }
        return new RegistryFixture(fileName, entries, first, sha256(first));
    }

    private static <T> Map<ResourceLocation, T> sortedResourceMap(
            Map<ResourceLocation, T> source) {
        LinkedHashMap<ResourceLocation, T> sorted = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static String serverConfigManifest(List<ConfigFixture> fixtures) {
        StringBuilder manifest = new StringBuilder("format-version=1\n")
                .append("pack=ATM10-8.0\n")
                .append("config.count=").append(fixtures.size()).append('\n')
                .append("config.total-content-bytes=")
                .append(fixtures.stream().mapToInt(ConfigFixture::contentBytes).sum()).append('\n')
                .append("config.total-encoded-bytes=")
                .append(fixtures.stream().mapToInt(fixture -> fixture.encodedPayload().length).sum())
                .append('\n')
                .append("config.sequence-sha256=").append(configSequenceSha256(fixtures)).append('\n');
        for (int index = 0; index < fixtures.size(); index++) {
            ConfigFixture fixture = fixtures.get(index);
            String key = "config." + index;
            manifest.append(key).append(".name=").append(fixture.configName()).append('\n')
                    .append(key).append(".file=").append(fixture.fileName()).append('\n')
                    .append(key).append(".content-bytes=").append(fixture.contentBytes()).append('\n')
                    .append(key).append(".content-sha256=").append(fixture.contentSha256()).append('\n')
                    .append(key).append(".encoded-bytes=")
                    .append(fixture.encodedPayload().length).append('\n')
                    .append(key).append(".encoded-sha256=")
                    .append(fixture.encodedSha256()).append('\n');
        }
        return manifest.toString();
    }

    private static String configSequenceSha256(List<ConfigFixture> fixtures) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fixtures.forEach(fixture -> digest.update(fixture.encodedPayload()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static <T> PlainFixture generatePlain(
            String fileName,
            StreamCodec<? super FriendlyByteBuf, T> codec,
            T value
    ) {
        byte[] first = encodePlain(codec, value);
        byte[] second = encodePlain(codec, value);
        if (!MessageDigest.isEqual(first, second)) {
            throw new IllegalStateException(fileName + " is not deterministic");
        }
        T decoded = decodePlain(codec, first);
        byte[] roundTrip = encodePlain(codec, decoded);
        if (!MessageDigest.isEqual(first, roundTrip)) {
            throw new IllegalStateException(fileName + " changed after decode/re-encode");
        }
        return new PlainFixture(fileName, first, sha256(first));
    }

    private static <T> Fixture generate(
            String fileName,
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            Supplier<T> source,
            ToIntFunction<T> entryCounter,
            Consumer<T> decodedInstaller,
            RegistryAccess registries
    ) {
        T firstValue = source.get();
        int entries = entryCounter.applyAsInt(firstValue);
        if (entries <= 0) {
            throw new IllegalStateException(fileName + " resolved to an empty registry");
        }

        byte[] first = encode(codec, firstValue, registries);
        byte[] second = encode(codec, source.get(), registries);
        if (!MessageDigest.isEqual(first, second)) {
            throw new IllegalStateException(fileName + " is not deterministic across two fresh payloads");
        }

        T decoded = decode(codec, first, registries);
        int decodedEntries = entryCounter.applyAsInt(decoded);
        if (decodedEntries != entries) {
            throw new IllegalStateException(fileName + " entry count changed after decode: "
                    + entries + " -> " + decodedEntries);
        }

        // Silent Gear's value codecs resolve their IDs by object identity in
        // SgRegistries. The client installs each decoded map before handling the
        // next payload, so mirror that exact sequence before re-encoding.
        decodedInstaller.accept(decoded);
        byte[] roundTrip = encode(codec, decoded, registries);
        T decodedAgain = decode(codec, roundTrip, registries);
        if (entryCounter.applyAsInt(decodedAgain) != entries) {
            throw new IllegalStateException(fileName + " entry count changed after a second decode");
        }

        return new Fixture(
                fileName,
                entries,
                first,
                sha256(first),
                MessageDigest.isEqual(first, roundTrip)
        );
    }

    private static <T> byte[] encode(
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            T value,
            RegistryAccess registries
    ) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), registries, ConnectionType.NEOFORGE);
        try {
            codec.encode(buffer, value);
            byte[] result = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), result);
            return result;
        } finally {
            buffer.release();
        }
    }

    private static <T> T decode(
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            byte[] encoded,
            RegistryAccess registries
    ) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(encoded), registries, ConnectionType.NEOFORGE);
        try {
            T result = codec.decode(buffer);
            if (buffer.isReadable()) {
                throw new IllegalStateException("Codec left " + buffer.readableBytes() + " trailing byte(s)");
            }
            return result;
        } finally {
            buffer.release();
        }
    }

    private static <T> byte[] encodePlain(
            StreamCodec<? super FriendlyByteBuf, T> codec,
            T value
    ) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, value);
            byte[] result = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), result);
            return result;
        } finally {
            buffer.release();
        }
    }

    private static <T> T decodePlain(
            StreamCodec<? super FriendlyByteBuf, T> codec,
            byte[] encoded
    ) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
        try {
            T result = codec.decode(buffer);
            if (buffer.isReadable()) {
                throw new IllegalStateException("Codec left " + buffer.readableBytes() + " trailing byte(s)");
            }
            return result;
        } finally {
            buffer.release();
        }
    }

    private static String manifest(
            Fixture traits,
            Fixture materials,
            Fixture parts,
            PlainFixture frozenStart,
            List<FrozenFixture> frozenRegistries,
            PlainFixture frozenCompleted,
            DynamicRegistryExport dynamicRegistries,
            RegistryTagFixture dynamicRegistryTags,
            List<ConfigFixture> serverConfigs,
            RegistryFixture apothicEnchanting,
            PlainFixture mekanismSecurity,
            BlockStateFixture blockStates
    ) {
        StringBuilder manifest = new StringBuilder("format-version=4\n")
                .append("generator=atm10_sg_profile_generator\n")
                .append("minecraft=1.21.1\n")
                .append("minecraft-protocol=767\n")
                .append("neoforge=21.1.247\n")
                .append("pack=ATM10-8.0\n")
                .append("curseforge-client-file-id=8649077\n")
                .append("curseforge-server-file-id=8649107\n")
                .append("server-files-sha256=").append(SERVER_FILES_SHA256).append('\n')
                .append("input-repair.count=1\n")
                .append("input-repair.0.reason=server-files-entry-truncated-missing-zip-eocd\n")
                .append("input-repair.0.artifact=L_Ender's Cataclysm 1.21.1-3.32.jar\n")
                .append("input-repair.0.curseforge-file-id=8364209\n")
                .append("input-repair.0.truncated-sha256=")
                .append(TRUNCATED_CATACLYSM_SHA256).append('\n')
                .append("input-repair.0.restored-sha256=")
                .append(RESTORED_CATACLYSM_SHA256).append('\n')
                .append("client-query-sha256=").append(CLIENT_QUERY_SHA256).append('\n')
                .append("full-client-contract-sha256=")
                .append(FULL_CLIENT_CONTRACT_SHA256).append('\n')
                .append("silentgear-channel-contract-sha256=")
                .append(SILENT_GEAR_CONTRACT_SHA256).append('\n')
                .append("silentgear=1.21.1-4.2.1.1\n")
                .append("network=4.2\n")
                .append(line("traits", traits))
                .append(line("materials", materials))
                .append(line("parts", parts));
        manifest.append(plainLine("frozen-registry-start", frozenStart));
        manifest.append("frozen-registry.count=").append(frozenRegistries.size()).append('\n');
        manifest.append("frozen-registry.total-entries=")
                .append(frozenRegistries.stream().mapToInt(FrozenFixture::entries).sum()).append('\n');
        manifest.append("frozen-registry.total-bytes=")
                .append(frozenRegistries.stream().mapToInt(fixture -> fixture.bytes().length).sum()).append('\n');
        manifest.append("frozen-registry.sequence-sha256=")
                .append(sequenceSha256(frozenStart, frozenRegistries, frozenCompleted)).append('\n');
        for (int index = 0; index < frozenRegistries.size(); index++) {
            FrozenFixture fixture = frozenRegistries.get(index);
            String key = "frozen-registry." + index;
            manifest.append(key).append(".name=").append(fixture.registryName()).append('\n');
            manifest.append(key).append(".file=").append(fixture.fileName()).append('\n');
            manifest.append(key).append(".entries=").append(fixture.entries()).append('\n');
            manifest.append(key).append(".aliases=").append(fixture.aliases()).append('\n');
            manifest.append(key).append(".bytes=").append(fixture.bytes().length).append('\n');
            manifest.append(key).append(".sha256=").append(fixture.sha256()).append('\n');
        }
        manifest.append(plainLine("frozen-registry-completed", frozenCompleted));
        manifest.append("dynamic-registry.count=").append(dynamicRegistries.fixtures().size())
                .append('\n')
                .append("dynamic-registry.total-entries=")
                .append(dynamicRegistries.fixtures().stream()
                        .mapToInt(DynamicRegistryFixture::entries).sum())
                .append('\n')
                .append("dynamic-registry.total-bytes=")
                .append(dynamicRegistries.fixtures().stream()
                        .mapToInt(fixture -> fixture.bytes().length).sum())
                .append('\n')
                .append("dynamic-registry.sequence-sha256=")
                .append(dynamicRegistries.sequenceSha256()).append('\n')
                .append("dynamic-registry-tags.registry=")
                .append(dynamicRegistryTags.registryId()).append('\n')
                .append("dynamic-registry-tags.tags=")
                .append(dynamicRegistryTags.tags().size()).append('\n')
                .append("dynamic-registry-tags.members=")
                .append(dynamicRegistryTags.tags().stream()
                        .mapToInt(tag -> tag.memberIds().length).sum()).append('\n')
                .append("dynamic-registry-tags.file=")
                .append(dynamicRegistryTags.fileName()).append('\n')
                .append("dynamic-registry-tags.bytes=")
                .append(dynamicRegistryTags.bytes().length).append('\n')
                .append("dynamic-registry-tags.sha256=")
                .append(dynamicRegistryTags.sha256()).append('\n')
                .append("server-config.count=").append(serverConfigs.size()).append('\n')
                .append("server-config.sequence-sha256=")
                .append(configSequenceSha256(serverConfigs)).append('\n')
                .append("apothic-enchanting.file=").append(apothicEnchanting.fileName()).append('\n')
                .append("apothic-enchanting.entries=").append(apothicEnchanting.entries()).append('\n')
                .append("apothic-enchanting.bytes=")
                .append(apothicEnchanting.bytes().length).append('\n')
                .append("apothic-enchanting.sha256=").append(apothicEnchanting.sha256()).append('\n')
                .append(plainLine("mekanism-batch-security", mekanismSecurity))
                .append("block-state-map.file=").append(blockStates.fileName()).append('\n')
                .append("block-state-map.global-entries=")
                .append(blockStates.globalEntries()).append('\n')
                .append("block-state-map.entries=").append(blockStates.entries()).append('\n')
                .append("block-state-map.bytes=").append(blockStates.bytes().length).append('\n')
                .append("block-state-map.sha256=").append(blockStates.sha256()).append('\n');
        return manifest.toString();
    }

    private static void validateBounds(
            Fixture traits,
            Fixture materials,
            Fixture parts,
            PlainFixture frozenStart,
            List<FrozenFixture> frozenRegistries,
            PlainFixture frozenCompleted,
            DynamicRegistryExport dynamicRegistries,
            RegistryTagFixture dynamicRegistryTags,
            List<ConfigFixture> serverConfigs,
            RegistryFixture apothicEnchanting,
            PlainFixture mekanismSecurity,
            BlockStateFixture blockStates
    ) {
        if (frozenRegistries.size() > MAXIMUM_FROZEN_REGISTRIES) {
            throw new IllegalStateException("Frozen registry count exceeds build-only bound");
        }
        if (dynamicRegistries.fixtures().size() > MAXIMUM_DYNAMIC_REGISTRIES) {
            throw new IllegalStateException("Dynamic registry count exceeds build-only bound");
        }
        int frozenEntries = frozenRegistries.stream().mapToInt(FrozenFixture::entries).sum();
        int dynamicEntries = dynamicRegistries.fixtures().stream()
                .mapToInt(DynamicRegistryFixture::entries).sum();
        if (frozenEntries > MAXIMUM_REGISTRY_ENTRIES
                || dynamicEntries > MAXIMUM_REGISTRY_ENTRIES) {
            throw new IllegalStateException("Registry entry count exceeds build-only bound");
        }

        List<byte[]> allPayloads = new ArrayList<>();
        allPayloads.add(traits.bytes());
        allPayloads.add(materials.bytes());
        allPayloads.add(parts.bytes());
        allPayloads.add(frozenStart.bytes());
        frozenRegistries.forEach(fixture -> allPayloads.add(fixture.bytes()));
        // FrozenRegistrySyncCompletedPayload is a singleton marker whose exact
        // NeoForge 21.1.247 wire body is intentionally empty.
        if (frozenCompleted.bytes().length != 0) {
            throw new IllegalStateException("Frozen registry completed marker is not empty");
        }
        dynamicRegistries.fixtures().forEach(fixture -> allPayloads.add(fixture.bytes()));
        if (!dynamicRegistryTags.registryId().equals(NEOVITAE_SENTIENT_UPGRADES.location())
                || dynamicRegistryTags.tags().isEmpty()) {
            throw new IllegalStateException("Dynamic registry tag fixture is not the reviewed NeoVitae set");
        }
        allPayloads.add(dynamicRegistryTags.bytes());
        serverConfigs.forEach(fixture -> allPayloads.add(fixture.encodedPayload()));
        allPayloads.add(apothicEnchanting.bytes());
        allPayloads.add(mekanismSecurity.bytes());

        long total = 0;
        for (byte[] payload : allPayloads) {
            if (payload.length == 0 || payload.length > MAXIMUM_SINGLE_PAYLOAD_BYTES) {
                throw new IllegalStateException(
                        "Generated payload violates build-only byte bound: " + payload.length);
            }
            total = Math.addExact(total, payload.length);
        }
        if (total > MAXIMUM_TOTAL_EXPORT_BYTES) {
            throw new IllegalStateException("Generated fixture exceeds build-only total byte bound");
        }
        total = Math.addExact(total, blockStates.bytes().length);
        if (blockStates.globalEntries() <= 0
                || blockStates.globalEntries() > MAXIMUM_GLOBAL_BLOCK_STATES
                || blockStates.entries() <= 0
                || blockStates.entries() > MAXIMUM_EXPORTED_MINECRAFT_BLOCK_STATES
                || blockStates.bytes().length <= 0
                || blockStates.bytes().length > MAXIMUM_BLOCK_STATE_MAP_BYTES
                || total > MAXIMUM_TOTAL_EXPORT_BYTES) {
            throw new IllegalStateException("Generated block-state evidence violates build-only bound");
        }
    }

    private static String line(String key, Fixture fixture) {
        return key + ".file=" + fixture.fileName() + "\n"
                + key + ".entries=" + fixture.entries() + "\n"
                + key + ".bytes=" + fixture.bytes().length + "\n"
                + key + ".sha256=" + fixture.sha256() + "\n"
                + key + ".decode-reencode-byte-identical=" + fixture.decodeReencodeByteIdentical() + "\n";
    }

    private static String plainLine(String key, PlainFixture fixture) {
        return key + ".file=" + fixture.fileName() + "\n"
                + key + ".bytes=" + fixture.bytes().length + "\n"
                + key + ".sha256=" + fixture.sha256() + "\n";
    }

    private static String sequenceSha256(
            PlainFixture start,
            List<FrozenFixture> registries,
            PlainFixture completed
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(start.bytes());
            registries.forEach(fixture -> digest.update(fixture.bytes()));
            digest.update(completed.bytes());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void logFixture(String name, Fixture fixture) {
        System.out.println("[ATM10-SG-FIXTURE] " + name
                + " entries=" + fixture.entries()
                + " bytes=" + fixture.bytes().length
                + " sha256=" + fixture.sha256()
                + " decodeReencodeByteIdentical=" + fixture.decodeReencodeByteIdentical());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Fixture(
            String fileName,
            int entries,
            byte[] bytes,
            String sha256,
            boolean decodeReencodeByteIdentical
    ) {
        private Fixture {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record PlainFixture(String fileName, byte[] bytes, String sha256) {
        private PlainFixture {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record RegistryFixture(
            String fileName,
            int entries,
            byte[] bytes,
            String sha256
    ) {
        private RegistryFixture {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record BlockStateFixture(
            String fileName,
            int globalEntries,
            int entries,
            byte[] bytes,
            String sha256
    ) {
        private BlockStateFixture {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record ConfigFixture(
            String configName,
            String fileName,
            int contentBytes,
            String contentSha256,
            byte[] encodedPayload,
            String encodedSha256
    ) {
        private ConfigFixture {
            encodedPayload = encodedPayload.clone();
        }

        @Override
        public byte[] encodedPayload() {
            return encodedPayload.clone();
        }
    }

    private record FrozenFixture(
            ResourceLocation registryName,
            String fileName,
            int entries,
            int aliases,
            byte[] bytes,
            String sha256
    ) {
        private FrozenFixture {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record DynamicRegistryExport(
            List<KnownPack> knownPacks,
            List<DynamicRegistryEntryKey> knownPackEntries,
            List<DynamicRegistryEntryKey> omittedKnownPackEntries,
            List<DynamicRegistryFixture> fixtures,
            String sequenceSha256
    ) {
    }

    private record DynamicRegistryEntryKey(
            ResourceLocation registryId,
            ResourceLocation entryId
    ) implements Comparable<DynamicRegistryEntryKey> {
        @Override
        public int compareTo(DynamicRegistryEntryKey other) {
            int registryComparison = registryId.compareTo(other.registryId);
            return registryComparison != 0 ? registryComparison : entryId.compareTo(other.entryId);
        }
    }

    private record DynamicRegistryFixture(
            ResourceKey<? extends Registry<?>> registryKey,
            String fileName,
            int entries,
            int encodedDataEntries,
            byte[] bytes,
            String sha256
    ) {
        private DynamicRegistryFixture {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record RegistryTagEntry(ResourceLocation tagId, int[] memberIds) {
        private RegistryTagEntry {
            memberIds = memberIds.clone();
        }

        @Override
        public int[] memberIds() {
            return memberIds.clone();
        }
    }

    private record RegistryTagFixture(
            String fileName,
            ResourceLocation registryId,
            List<RegistryTagEntry> tags,
            byte[] bytes,
            String sha256
    ) {
        private RegistryTagFixture {
            tags = List.copyOf(tags);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
