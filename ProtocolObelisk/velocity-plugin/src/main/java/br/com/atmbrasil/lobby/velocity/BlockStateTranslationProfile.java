package br.com.atmbrasil.lobby.velocity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Immutable vanilla-to-client BlockState id map from exact reviewed offline evidence. */
final class BlockStateTranslationProfile {
    private static final String MANIFEST_FILE = "block-state-map.properties";
    private static final int LEGACY_MANIFEST_FORMAT_VERSION = 1;
    private static final int REVIEWED_MANIFEST_FORMAT_VERSION = 2;
    private static final int MAP_FORMAT_VERSION = 1;
    private static final byte[] MAGIC = {'P', 'O', 'B', 'S'};
    private static final int MAXIMUM_MANIFEST_BYTES = 32 * 1024;
    private static final int MAXIMUM_MAP_BYTES = 128 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RESOURCE_ROOT =
            Pattern.compile("[a-z0-9](?:[a-z0-9._/-]*[a-z0-9_/])?");
    private static final Pattern RESOURCE_FILE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final int REVIEWED_MINECRAFT_PROTOCOL = 767;
    private static final int VANILLA_STATE_COUNT = 26_684;
    private static final String LEGACY_ATM10_NORMAL_8_0_PROFILE_ID =
            "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247";
    private static final String LEGACY_ATM10_NORMAL_8_0_FULL_CLIENT_CONTRACT_SHA256 =
            "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f";
    private static final String VANILLA_STATE_TABLE_SHA256 =
            "de9980fae71fecd0112d77cd1c056815dca3a1e1125289efd08b1c0696f499bb";
    private static final String REVIEWED_DESCRIPTOR_SET_CANONICALIZATION =
            "strip-runtime-id-sort-utf8-lf-v1";
    private static final Set<RewriteCapability> REQUIRED_REWRITE_CAPABILITIES = Set.of(
            RewriteCapability.BLOCK_UPDATE,
            RewriteCapability.CHUNK_BLOCK_STATES,
            RewriteCapability.BLOCK_LEVEL_EVENT,
            RewriteCapability.SECTION_BLOCKS_UPDATE);
    private static final Set<RewriteCapability> EMBEDDED_EXACT_REWRITE_CAPABILITIES = Set.of(
            RewriteCapability.FALLING_BLOCK_ENTITY,
            RewriteCapability.BLOCK_UPDATE,
            RewriteCapability.CHUNK_BLOCK_STATES,
            RewriteCapability.BLOCK_LEVEL_EVENT,
            RewriteCapability.BLOCK_PARTICLE,
            RewriteCapability.SECTION_BLOCKS_UPDATE);

    enum RewriteCapability {
        FALLING_BLOCK_ENTITY,
        BLOCK_UPDATE,
        CHUNK_BLOCK_STATES,
        BLOCK_LEVEL_EVENT,
        BLOCK_PARTICLE,
        SECTION_BLOCKS_UPDATE
    }

    private final String profileId;
    private final int minecraftProtocol;
    private final int[] vanillaToClient;
    private final int clientGlobalStateCount;
    private final int sourceGlobalPaletteBits;
    private final int targetGlobalPaletteBits;
    private final String mapSha256;
    private final String vanillaStateTableSha256;
    private final String clientStateTableSha256;
    private final int addEntityPacketId;
    private final int blockUpdatePacketId;
    private final int levelChunkWithLightPacketId;
    private final int levelEventPacketId;
    private final int levelParticlesPacketId;
    private final int sectionBlocksUpdatePacketId;
    private final int setEntityDataPacketId;
    private final Set<Integer> blockParticleTypeIds;
    private final OptionalInt fallingBlockEntityTypeId;
    private final Set<RewriteCapability> rewriteCapabilities;
    private final Set<Integer> rewrittenPacketIds;

    private BlockStateTranslationProfile(
            String profileId,
            int minecraftProtocol,
            int[] vanillaToClient,
            int clientGlobalStateCount,
            int sourceGlobalPaletteBits,
            int targetGlobalPaletteBits,
            String mapSha256,
            String vanillaStateTableSha256,
            String clientStateTableSha256,
            int addEntityPacketId,
            int blockUpdatePacketId,
            int levelChunkWithLightPacketId,
            int levelEventPacketId,
            int levelParticlesPacketId,
            int sectionBlocksUpdatePacketId,
            int setEntityDataPacketId,
            Set<Integer> blockParticleTypeIds,
            OptionalInt fallingBlockEntityTypeId,
            Set<RewriteCapability> rewriteCapabilities) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.minecraftProtocol = minecraftProtocol;
        this.vanillaToClient = Objects.requireNonNull(vanillaToClient, "vanillaToClient").clone();
        this.clientGlobalStateCount = clientGlobalStateCount;
        this.sourceGlobalPaletteBits = sourceGlobalPaletteBits;
        this.targetGlobalPaletteBits = targetGlobalPaletteBits;
        this.mapSha256 = Objects.requireNonNull(mapSha256, "mapSha256");
        this.vanillaStateTableSha256 = Objects.requireNonNull(
                vanillaStateTableSha256, "vanillaStateTableSha256");
        this.clientStateTableSha256 = Objects.requireNonNull(
                clientStateTableSha256, "clientStateTableSha256");
        this.addEntityPacketId = addEntityPacketId;
        this.blockUpdatePacketId = blockUpdatePacketId;
        this.levelChunkWithLightPacketId = levelChunkWithLightPacketId;
        this.levelEventPacketId = levelEventPacketId;
        this.levelParticlesPacketId = levelParticlesPacketId;
        this.sectionBlocksUpdatePacketId = sectionBlocksUpdatePacketId;
        this.setEntityDataPacketId = setEntityDataPacketId;
        this.blockParticleTypeIds = Set.copyOf(blockParticleTypeIds);
        this.fallingBlockEntityTypeId = Objects.requireNonNull(
                fallingBlockEntityTypeId, "fallingBlockEntityTypeId");
        this.rewriteCapabilities = Set.copyOf(Objects.requireNonNull(
                rewriteCapabilities, "rewriteCapabilities"));
        if (this.rewriteCapabilities.contains(RewriteCapability.BLOCK_PARTICLE)
                != !this.blockParticleTypeIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "block-particle rewrite capability and structural ids disagree");
        }
        if (this.rewriteCapabilities.contains(RewriteCapability.FALLING_BLOCK_ENTITY)
                != this.fallingBlockEntityTypeId.isPresent()) {
            throw new IllegalArgumentException(
                    "falling-block rewrite capability and structural id disagree");
        }
        this.rewrittenPacketIds = buildRewrittenPacketIds();
    }

    /**
     * Loads one independently reviewed structural profile.
     *
     * <p>The caller pins the exact manifest bytes in code. The manifest in turn pins the binary
     * map, so neither a replaced resource nor a manifest/map pair rewritten together can silently
     * become structural evidence. Selection of the resulting profile belongs to
     * {@link ReviewedBlockStateProfileCatalog}; this loader has no admission or routing effect.</p>
     */
    static BlockStateTranslationProfile loadReviewed(
            ClassLoader loader,
            String resourceRoot,
            String expectedProfileId,
            int expectedMinecraftProtocol,
            String expectedFullClientContractSha256,
            String expectedManifestSha256) throws IOException {
        Objects.requireNonNull(loader, "loader");
        requireCanonicalResourceRoot(resourceRoot);
        if (Objects.requireNonNull(expectedProfileId, "expectedProfileId").isBlank()) {
            throw new IllegalArgumentException("reviewed BlockState profile id must not be blank");
        }
        if (expectedMinecraftProtocol != REVIEWED_MINECRAFT_PROTOCOL) {
            throw new IllegalArgumentException(
                    "reviewed BlockState profiles support only Minecraft protocol 767");
        }
        requireExpectedSha256(
                expectedFullClientContractSha256, "full client contract SHA-256");
        requireExpectedSha256(expectedManifestSha256, "manifest SHA-256");

        byte[] manifestBytes = readBounded(
                loader, resourceRoot + MANIFEST_FILE, MAXIMUM_MANIFEST_BYTES);
        if (!sha256(manifestBytes).equals(expectedManifestSha256)) {
            throw new IllegalArgumentException(
                    "reviewed BlockState manifest hash mismatch");
        }
        Properties manifest = new Properties();
        manifest.load(new ByteArrayInputStream(manifestBytes));

        String manifestFormat = require(manifest, "format-version");
        if (manifestFormat.equals(Integer.toString(LEGACY_MANIFEST_FORMAT_VERSION))) {
            if (!expectedProfileId.equals(LEGACY_ATM10_NORMAL_8_0_PROFILE_ID)
                    || !expectedFullClientContractSha256.equals(
                            LEGACY_ATM10_NORMAL_8_0_FULL_CLIENT_CONTRACT_SHA256)) {
                throw new IllegalArgumentException(
                        "legacy BlockState manifest is not the exact ATM10 Normal 8.0 identity");
            }
            return loadAtm10Normal80(
                    loader,
                    resourceRoot,
                    expectedProfileId,
                    expectedMinecraftProtocol,
                    expectedFullClientContractSha256,
                    requireSha256(manifest, "frozen-registry-sequence-sha256"));
        }
        requireEquals(
                manifest,
                "format-version",
                Integer.toString(REVIEWED_MANIFEST_FORMAT_VERSION));
        requireEquals(manifest, "profile-id", expectedProfileId);
        requireEquals(manifest, "minecraft-version", "1.21.1");
        requireEquals(
                manifest,
                "minecraft-protocol",
                Integer.toString(expectedMinecraftProtocol));
        requireEquals(
                manifest,
                "client-full-contract-sha256",
                expectedFullClientContractSha256);
        requireEquals(
                manifest,
                "vanilla-state-table.count",
                Integer.toString(VANILLA_STATE_COUNT));
        String vanillaStateTableSha256 = requireSha256(
                manifest, "vanilla-state-table.sha256");
        if (!vanillaStateTableSha256.equals(VANILLA_STATE_TABLE_SHA256)) {
            throw new IllegalArgumentException(
                    "reviewed BlockState vanilla state-table identity mismatch");
        }

        int clientGlobalStateCount = positiveInt(manifest, "client-global-state.count");
        if (clientGlobalStateCount < VANILLA_STATE_COUNT) {
            throw new IllegalArgumentException(
                    "reviewed BlockState client state table is smaller than vanilla");
        }
        requireEquals(
                manifest,
                "client-state-descriptor-set.count",
                Integer.toString(clientGlobalStateCount));
        String clientStateTableSha256 = requireSha256(
                manifest, "client-state-descriptor-set.sha256");
        requireEquals(
                manifest,
                "client-state-descriptor-set.canonicalization",
                REVIEWED_DESCRIPTOR_SET_CANONICALIZATION);

        requireEquals(manifest, "map.count", Integer.toString(VANILLA_STATE_COUNT));
        String mapFile = requireResourceFile(manifest, "map.file");
        int mapBytesLength = positiveInt(manifest, "map.bytes");
        if (mapBytesLength > MAXIMUM_MAP_BYTES) {
            throw new IllegalArgumentException(
                    "reviewed BlockState map length exceeds byte bound");
        }
        String mapSha256 = requireSha256(manifest, "map.sha256");
        byte[] mapBytes = readBounded(
                loader, resourceRoot + mapFile, MAXIMUM_MAP_BYTES);
        if (mapBytes.length != mapBytesLength) {
            throw new IllegalArgumentException("reviewed BlockState map length mismatch");
        }
        if (!sha256(mapBytes).equals(mapSha256)) {
            throw new IllegalArgumentException("reviewed BlockState map hash mismatch");
        }
        int[] map = decodeMap(mapBytes, VANILLA_STATE_COUNT, clientGlobalStateCount);
        if (maximumTargetId(map) != positiveInt(manifest, "map.maximum-target-id")) {
            throw new IllegalArgumentException(
                    "reviewed BlockState maximum target mismatch");
        }
        if (strictBoolean(manifest, "mapping.target-ids-strictly-increasing")
                != targetIdsStrictlyIncreasing(map)) {
            throw new IllegalArgumentException(
                    "reviewed BlockState target-order evidence mismatch");
        }

        int sourceBits = positiveInt(manifest, "source-global-palette.bits");
        int targetBits = positiveInt(manifest, "target-global-palette.bits");
        if (sourceBits != ceilLog2(VANILLA_STATE_COUNT)
                || targetBits != ceilLog2(clientGlobalStateCount)) {
            throw new IllegalArgumentException(
                    "reviewed BlockState global palette bits mismatch");
        }

        Set<RewriteCapability> capabilities = parseRewriteCapabilities(manifest);
        int addEntityPacketId = capabilities.contains(RewriteCapability.FALLING_BLOCK_ENTITY)
                ? exactProtocolPacketId(manifest, "packet.add-entity", 1)
                : absentPacketId(manifest, "packet.add-entity");
        int blockUpdatePacketId = exactProtocolPacketId(
                manifest, "packet.block-update", 9);
        int levelChunkWithLightPacketId = exactProtocolPacketId(
                manifest, "packet.level-chunk-with-light", 39);
        int levelEventPacketId = exactProtocolPacketId(
                manifest, "packet.level-event", 40);
        int levelParticlesPacketId = capabilities.contains(RewriteCapability.BLOCK_PARTICLE)
                ? exactProtocolPacketId(manifest, "packet.level-particles", 41)
                : absentPacketId(manifest, "packet.level-particles");
        int sectionBlocksUpdatePacketId = exactProtocolPacketId(
                manifest, "packet.section-blocks-update", 73);
        requireAbsent(manifest, "packet.set-entity-data");

        Set<Integer> blockParticleTypeIds =
                capabilities.contains(RewriteCapability.BLOCK_PARTICLE)
                        ? parseCanonicalNonNegativeInts(
                                manifest, "structural.block-particle-type-ids")
                        : absentIntegerSet(
                                manifest, "structural.block-particle-type-ids");
        OptionalInt fallingBlockEntityTypeId =
                capabilities.contains(RewriteCapability.FALLING_BLOCK_ENTITY)
                        ? OptionalInt.of(nonNegativeInt(
                                manifest, "structural.falling-block-entity-type-id"))
                        : absentOptionalInt(
                                manifest, "structural.falling-block-entity-type-id");
        requireExactReviewedManifestKeys(manifest, capabilities);

        return new BlockStateTranslationProfile(
                expectedProfileId,
                expectedMinecraftProtocol,
                map,
                clientGlobalStateCount,
                sourceBits,
                targetBits,
                mapSha256,
                vanillaStateTableSha256,
                clientStateTableSha256,
                addEntityPacketId,
                blockUpdatePacketId,
                levelChunkWithLightPacketId,
                levelEventPacketId,
                levelParticlesPacketId,
                sectionBlocksUpdatePacketId,
                -1,
                blockParticleTypeIds,
                fallingBlockEntityTypeId,
                capabilities);
    }

    static BlockStateTranslationProfile loadAtm10Normal80(
            ClassLoader loader,
            String resourceRoot,
            String expectedProfileId,
            int expectedMinecraftProtocol,
            String expectedFullClientContract,
            String expectedFrozenRegistrySequence) throws IOException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(resourceRoot, "resourceRoot");
        Properties manifest = loadManifest(loader, resourceRoot);

        requireEquals(
                manifest,
                "format-version",
                Integer.toString(LEGACY_MANIFEST_FORMAT_VERSION));
        requireEquals(manifest, "profile-id", expectedProfileId);
        requireEquals(manifest, "minecraft-version", "1.21.1");
        requireEquals(manifest, "minecraft-protocol", Integer.toString(expectedMinecraftProtocol));
        requireEquals(manifest, "curseforge-client-file-id", "8649077");
        requireEquals(manifest, "curseforge-server-file-id", "8649107");
        requireEquals(manifest, "client-full-contract-sha256", expectedFullClientContract);
        requireEquals(
                manifest,
                "frozen-registry-sequence-sha256",
                expectedFrozenRegistrySequence);
        requireEquals(manifest, "vanilla-block-report.bytes", "5585490");
        requireEquals(
                manifest,
                "vanilla-block-report.sha256",
                "0dde7f869588905763ea6ff2e7e01bec1db740a58d27477fd27c2f07fb029f73");
        requireEquals(manifest, "vanilla-state-table.count", "26684");
        requireEquals(manifest, "vanilla-state-table.bytes", "2353080");
        String vanillaStateTableSha256 = requireSha256(
                manifest, "vanilla-state-table.sha256");
        requireEquals(
                manifest,
                "vanilla-state-table.sha256",
                "de9980fae71fecd0112d77cd1c056815dca3a1e1125289efd08b1c0696f499bb");
        requireEquals(manifest, "client-global-state.count", "1980659");
        requireEquals(manifest, "client-minecraft-state-table.count", "45481");
        requireEquals(manifest, "client-minecraft-state-table.bytes", "4931283");
        String clientStateTableSha256 = requireSha256(
                manifest, "client-minecraft-state-table.sha256");
        requireEquals(
                manifest,
                "client-minecraft-state-table.sha256",
                "ae1d2612f838f8b22f6bc2bd86f46d0cbab26566401f36b6496c53cb2592dc12");
        requireEquals(manifest, "reviewed-map-tsv.count", "26684");
        requireEquals(manifest, "reviewed-map-tsv.bytes", "2505236");
        requireEquals(
                manifest,
                "reviewed-map-tsv.sha256",
                "97a1dd540d706a09c4d59cae8fed0319534db2a385f9d09dc319072a9ca9f07f");
        requireEquals(manifest, "map.file", "block-state-map.bin");
        requireEquals(manifest, "map.bytes", "69272");
        String mapSha256 = requireSha256(manifest, "map.sha256");
        requireEquals(
                manifest,
                "map.sha256",
                "6015f4b9f30e0d680d8d89439fad7f141eb901462a04bf4052865b0fec6fe263");
        requireEquals(manifest, "map.count", "26684");
        requireEquals(manifest, "map.maximum-target-id", "45478");
        requireEquals(manifest, "source-global-palette.bits", "15");
        requireEquals(manifest, "target-global-palette.bits", "21");
        validateNeutralProperties(manifest);
        requireEquals(manifest, "protocol-report.bytes", "16264");
        requireEquals(
                manifest,
                "protocol-report.sha256",
                "6f613707a721fa9dec64f79f35527ec6884ee762fd5620930f9d72543178c3ca");

        int sourceCount = positiveInt(manifest, "map.count");
        int clientGlobalStateCount = positiveInt(manifest, "client-global-state.count");
        byte[] mapBytes = readBounded(
                loader,
                resourceRoot + require(manifest, "map.file"),
                MAXIMUM_MAP_BYTES);
        if (mapBytes.length != positiveInt(manifest, "map.bytes")) {
            throw new IllegalArgumentException("embedded BlockState map length mismatch");
        }
        if (!sha256(mapBytes).equals(mapSha256)) {
            throw new IllegalArgumentException("embedded BlockState map hash mismatch");
        }
        int[] map = decodeMap(mapBytes, sourceCount, clientGlobalStateCount);
        if (maximumTargetId(map) != positiveInt(manifest, "map.maximum-target-id")) {
            throw new IllegalArgumentException("embedded BlockState maximum target mismatch");
        }

        int sourceBits = positiveInt(manifest, "source-global-palette.bits");
        int targetBits = positiveInt(manifest, "target-global-palette.bits");
        if (sourceBits != ceilLog2(sourceCount)
                || targetBits != ceilLog2(clientGlobalStateCount)) {
            throw new IllegalArgumentException("embedded BlockState global palette bits mismatch");
        }

        return new BlockStateTranslationProfile(
                expectedProfileId,
                expectedMinecraftProtocol,
                map,
                clientGlobalStateCount,
                sourceBits,
                targetBits,
                mapSha256,
                vanillaStateTableSha256,
                clientStateTableSha256,
                nonNegativeInt(manifest, "packet.add-entity"),
                nonNegativeInt(manifest, "packet.block-update"),
                nonNegativeInt(manifest, "packet.level-chunk-with-light"),
                nonNegativeInt(manifest, "packet.level-event"),
                nonNegativeInt(manifest, "packet.level-particles"),
                nonNegativeInt(manifest, "packet.section-blocks-update"),
                nonNegativeInt(manifest, "packet.set-entity-data"),
                Set.of(
                        nonNegativeInt(manifest, "particle.block"),
                        nonNegativeInt(manifest, "particle.block-marker"),
                        nonNegativeInt(manifest, "particle.falling-dust"),
                        nonNegativeInt(manifest, "particle.dust-pillar")),
                OptionalInt.of(nonNegativeInt(manifest, "entity.falling-block")),
                EMBEDDED_EXACT_REWRITE_CAPABILITIES);
    }

    private static void validateNeutralProperties(Properties manifest) {
        requireEquals(manifest, "neutral-extra-property.count", "3");
        requireEquals(manifest, "neutral-extra-property.0.name", "essentia_logged");
        requireEquals(manifest, "neutral-extra-property.0.block-count", "337");
        requireEquals(manifest, "neutral-extra-property.0.value", "false");
        requireEquals(manifest, "neutral-extra-property.1.name", "boiling");
        requireEquals(manifest, "neutral-extra-property.1.block-count", "1");
        requireEquals(manifest, "neutral-extra-property.1.value", "false");
        requireEquals(manifest, "neutral-extra-property.2.name", "waterlogged");
        requireEquals(manifest, "neutral-extra-property.2.block-count", "14");
        requireEquals(manifest, "neutral-extra-property.2.value", "false");
    }

    private static int[] decodeMap(
            byte[] bytes, int expectedSourceCount, int clientGlobalStateCount) {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        for (byte expected : MAGIC) {
            if (!input.hasRemaining() || input.get() != expected) {
                throw new IllegalArgumentException("embedded BlockState map magic mismatch");
            }
        }
        if (!input.hasRemaining() || Byte.toUnsignedInt(input.get()) != MAP_FORMAT_VERSION) {
            throw new IllegalArgumentException("embedded BlockState map format mismatch");
        }
        if (input.remaining() < Integer.BYTES * 2) {
            throw new IllegalArgumentException("embedded BlockState map header is truncated");
        }
        int sourceCount = input.getInt();
        int targetCount = input.getInt();
        if (sourceCount != expectedSourceCount || targetCount != clientGlobalStateCount) {
            throw new IllegalArgumentException("embedded BlockState map header count mismatch");
        }
        int[] map = new int[sourceCount];
        HashSet<Integer> uniqueTargets = new HashSet<>(sourceCount * 2);
        for (int index = 0; index < map.length; index++) {
            int target = readCanonicalVarInt(input);
            if (target < 0 || target >= targetCount || !uniqueTargets.add(target)) {
                throw new IllegalArgumentException(
                        "embedded BlockState target ids are not unique and bounded");
            }
            map[index] = target;
        }
        if (input.hasRemaining()) {
            throw new IllegalArgumentException("trailing embedded BlockState map bytes");
        }
        return map;
    }

    private static int maximumTargetId(int[] map) {
        int maximum = -1;
        for (int targetId : map) {
            maximum = Math.max(maximum, targetId);
        }
        return maximum;
    }

    private static boolean targetIdsStrictlyIncreasing(int[] map) {
        int previous = -1;
        for (int targetId : map) {
            if (targetId <= previous) {
                return false;
            }
            previous = targetId;
        }
        return true;
    }

    int translate(int vanillaStateId) {
        if (vanillaStateId < 0 || vanillaStateId >= vanillaToClient.length) {
            throw new IllegalArgumentException(
                    "vanilla BlockState id is outside translation map: " + vanillaStateId);
        }
        return vanillaToClient[vanillaStateId];
    }

    boolean rewritesPacketId(int packetId) {
        // SET_ENTITY_DATA is intentionally excluded from this beta. Its metadata stream may
        // contain ItemStack DataComponentPatch additions whose values are dispatched through
        // non-length-delimited component-specific codecs. Passing the packet through is safer
        // than arming an incomplete skipper that could disconnect on legitimate Paper metadata.
        return rewrittenPacketIds.contains(packetId);
    }

    Set<Integer> rewrittenPacketIds() {
        return rewrittenPacketIds;
    }

    boolean supportsRewriteCapability(RewriteCapability capability) {
        return rewriteCapabilities.contains(Objects.requireNonNull(capability, "capability"));
    }

    String profileId() {
        return profileId;
    }

    int minecraftProtocol() {
        return minecraftProtocol;
    }

    int sourceStateCount() {
        return vanillaToClient.length;
    }

    int clientGlobalStateCount() {
        return clientGlobalStateCount;
    }

    int sourceGlobalPaletteBits() {
        return sourceGlobalPaletteBits;
    }

    int targetGlobalPaletteBits() {
        return targetGlobalPaletteBits;
    }

    String mapSha256() {
        return mapSha256;
    }

    String vanillaStateTableSha256() {
        return vanillaStateTableSha256;
    }

    String clientStateTableSha256() {
        return clientStateTableSha256;
    }

    int addEntityPacketId() {
        return addEntityPacketId;
    }

    int blockUpdatePacketId() {
        return blockUpdatePacketId;
    }

    int levelChunkWithLightPacketId() {
        return levelChunkWithLightPacketId;
    }

    int levelEventPacketId() {
        return levelEventPacketId;
    }

    int levelParticlesPacketId() {
        return levelParticlesPacketId;
    }

    int sectionBlocksUpdatePacketId() {
        return sectionBlocksUpdatePacketId;
    }

    int setEntityDataPacketId() {
        return setEntityDataPacketId;
    }

    boolean isBlockParticleType(int typeId) {
        return blockParticleTypeIds.contains(typeId);
    }

    int fallingBlockEntityTypeId() {
        return fallingBlockEntityTypeId.orElseThrow(() -> new IllegalStateException(
                "falling-block entity structural id is not authenticated for this profile"));
    }

    private Set<Integer> buildRewrittenPacketIds() {
        HashSet<Integer> packetIds = new HashSet<>();
        addPacketId(
                packetIds,
                RewriteCapability.FALLING_BLOCK_ENTITY,
                addEntityPacketId);
        addPacketId(packetIds, RewriteCapability.BLOCK_UPDATE, blockUpdatePacketId);
        addPacketId(
                packetIds,
                RewriteCapability.CHUNK_BLOCK_STATES,
                levelChunkWithLightPacketId);
        addPacketId(packetIds, RewriteCapability.BLOCK_LEVEL_EVENT, levelEventPacketId);
        addPacketId(packetIds, RewriteCapability.BLOCK_PARTICLE, levelParticlesPacketId);
        addPacketId(
                packetIds,
                RewriteCapability.SECTION_BLOCKS_UPDATE,
                sectionBlocksUpdatePacketId);
        if (packetIds.size() != rewriteCapabilities.size()) {
            throw new IllegalArgumentException(
                    "enabled BlockState rewrite packet ids are not one-to-one");
        }
        return Set.copyOf(packetIds);
    }

    private void addPacketId(
            Set<Integer> packetIds, RewriteCapability capability, int packetId) {
        if (rewriteCapabilities.contains(capability)) {
            packetIds.add(packetId);
        }
    }

    private static Properties loadManifest(ClassLoader loader, String resourceRoot)
            throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resourceRoot + MANIFEST_FILE)) {
            if (stream == null) {
                throw new IOException("missing embedded BlockState translation manifest");
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties;
        }
    }

    private static byte[] readBounded(
            ClassLoader loader, String resource, int maximumBytes) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing embedded BlockState resource " + resource);
            }
            byte[] bytes = stream.readNBytes(Math.addExact(maximumBytes, 1));
            if (bytes.length > maximumBytes) {
                throw new IllegalArgumentException(
                        "embedded BlockState resource exceeds byte bound");
            }
            return bytes;
        }
    }

    private static int readCanonicalVarInt(ByteBuffer input) {
        int result = 0;
        int bytes = 0;
        while (bytes < 5) {
            if (!input.hasRemaining()) {
                throw new IllegalArgumentException("truncated embedded BlockState VarInt");
            }
            int current = Byte.toUnsignedInt(input.get());
            result |= (current & 0x7F) << (bytes * 7);
            bytes++;
            if ((current & 0x80) == 0) {
                if (result < 0 || varIntBytes(result) != bytes) {
                    throw new IllegalArgumentException(
                            "non-canonical embedded BlockState VarInt");
                }
                return result;
            }
        }
        throw new IllegalArgumentException("embedded BlockState VarInt exceeds five bytes");
    }

    private static int varIntBytes(int value) {
        if ((value & ~0x7F) == 0) {
            return 1;
        }
        if ((value & ~0x3FFF) == 0) {
            return 2;
        }
        if ((value & ~0x1F_FFFF) == 0) {
            return 3;
        }
        if ((value & ~0x0FFF_FFFF) == 0) {
            return 4;
        }
        return 5;
    }

    private static int ceilLog2(int value) {
        return Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "embedded BlockState manifest is missing " + key);
        }
        return value.strip();
    }

    private static void requireEquals(Properties properties, String key, String expected) {
        String actual = require(properties, key);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "embedded BlockState manifest " + key + " mismatch: " + actual);
        }
    }

    private static int positiveInt(Properties properties, String key) {
        int value = nonNegativeInt(properties, key);
        if (value == 0) {
            throw new IllegalArgumentException(
                    "embedded BlockState manifest integer must be positive: " + key);
        }
        return value;
    }

    private static int nonNegativeInt(Properties properties, String key) {
        String raw = require(properties, key);
        try {
            int value = Integer.parseInt(raw);
            if (value < 0 || !Integer.toString(value).equals(raw)) {
                throw new IllegalArgumentException(
                        "embedded BlockState manifest integer is not canonical: " + key);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "embedded BlockState manifest has invalid integer " + key, exception);
        }
    }

    private static boolean strictBoolean(Properties properties, String key) {
        String raw = require(properties, key);
        if (raw.equals("true")) {
            return true;
        }
        if (raw.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException(
                "embedded BlockState manifest has invalid boolean " + key);
    }

    private static String requireSha256(Properties properties, String key) {
        String value = require(properties, key);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "embedded BlockState manifest has invalid SHA-256: " + key);
        }
        return value;
    }

    private static Set<RewriteCapability> parseRewriteCapabilities(Properties manifest) {
        String raw = require(manifest, "rewrite-capabilities");
        EnumSet<RewriteCapability> parsed = EnumSet.noneOf(RewriteCapability.class);
        for (String token : raw.split(",", -1)) {
            final RewriteCapability capability;
            try {
                capability = RewriteCapability.valueOf(token);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "reviewed BlockState manifest has an unknown rewrite capability",
                        exception);
            }
            if (!parsed.add(capability)) {
                throw new IllegalArgumentException(
                        "reviewed BlockState manifest repeats a rewrite capability");
            }
        }
        if (!parsed.containsAll(REQUIRED_REWRITE_CAPABILITIES)) {
            throw new IllegalArgumentException(
                    "reviewed BlockState manifest omits a cardinal rewrite capability");
        }
        StringJoiner canonical = new StringJoiner(",");
        for (RewriteCapability capability : RewriteCapability.values()) {
            if (parsed.contains(capability)) {
                canonical.add(capability.name());
            }
        }
        if (!raw.equals(canonical.toString())) {
            throw new IllegalArgumentException(
                    "reviewed BlockState rewrite capabilities are not canonical");
        }
        return Set.copyOf(parsed);
    }

    private static Set<Integer> parseCanonicalNonNegativeInts(
            Properties manifest, String key) {
        String raw = require(manifest, key);
        LinkedHashSet<Integer> parsed = new LinkedHashSet<>();
        int previous = -1;
        for (String token : raw.split(",", -1)) {
            final int value;
            try {
                value = Integer.parseInt(token);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "reviewed BlockState manifest has an invalid structural id", exception);
            }
            if (value < 0
                    || !Integer.toString(value).equals(token)
                    || value <= previous
                    || !parsed.add(value)) {
                throw new IllegalArgumentException(
                        "reviewed BlockState structural ids are not unique, ordered and canonical");
            }
            previous = value;
        }
        return Set.copyOf(parsed);
    }

    private static int exactProtocolPacketId(
            Properties manifest, String key, int expectedPacketId) {
        requireEquals(manifest, key, Integer.toString(expectedPacketId));
        return expectedPacketId;
    }

    private static int absentPacketId(Properties manifest, String key) {
        requireAbsent(manifest, key);
        return -1;
    }

    private static Set<Integer> absentIntegerSet(Properties manifest, String key) {
        requireAbsent(manifest, key);
        return Set.of();
    }

    private static OptionalInt absentOptionalInt(Properties manifest, String key) {
        requireAbsent(manifest, key);
        return OptionalInt.empty();
    }

    private static void requireAbsent(Properties properties, String key) {
        if (properties.containsKey(key)) {
            throw new IllegalArgumentException(
                    "reviewed BlockState manifest advertises disabled structure: " + key);
        }
    }

    private static void requireExactReviewedManifestKeys(
            Properties properties,
            Set<RewriteCapability> capabilities) {
        HashSet<String> expected = new HashSet<>(Set.of(
                "format-version",
                "profile-id",
                "minecraft-version",
                "minecraft-protocol",
                "client-full-contract-sha256",
                "vanilla-state-table.count",
                "vanilla-state-table.sha256",
                "client-global-state.count",
                "client-state-descriptor-set.count",
                "client-state-descriptor-set.canonicalization",
                "client-state-descriptor-set.sha256",
                "map.file",
                "map.bytes",
                "map.sha256",
                "map.count",
                "map.maximum-target-id",
                "mapping.target-ids-strictly-increasing",
                "source-global-palette.bits",
                "target-global-palette.bits",
                "rewrite-capabilities",
                "packet.block-update",
                "packet.level-chunk-with-light",
                "packet.level-event",
                "packet.section-blocks-update"));
        if (capabilities.contains(RewriteCapability.FALLING_BLOCK_ENTITY)) {
            expected.add("packet.add-entity");
            expected.add("structural.falling-block-entity-type-id");
        }
        if (capabilities.contains(RewriteCapability.BLOCK_PARTICLE)) {
            expected.add("packet.level-particles");
            expected.add("structural.block-particle-type-ids");
        }
        Set<String> actual = properties.stringPropertyNames();
        if (!actual.equals(expected)) {
            TreeSet<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            TreeSet<String> unexpected = new TreeSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalArgumentException(
                    "reviewed BlockState manifest key set mismatch; missing="
                            + missing + ", unexpected=" + unexpected);
        }
    }

    private static String requireResourceFile(Properties properties, String key) {
        String value = require(properties, key);
        if (!RESOURCE_FILE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "reviewed BlockState manifest has an unsafe resource file: " + key);
        }
        return value;
    }

    private static void requireCanonicalResourceRoot(String resourceRoot) {
        Objects.requireNonNull(resourceRoot, "resourceRoot");
        if (!resourceRoot.endsWith("/")
                || resourceRoot.startsWith("/")
                || resourceRoot.contains("..")
                || resourceRoot.contains("//")
                || !RESOURCE_ROOT.matcher(resourceRoot).matches()) {
            throw new IllegalArgumentException(
                    "reviewed BlockState resource root is not canonical");
        }
    }

    private static void requireExpectedSha256(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "reviewed BlockState " + label + " must be lowercase SHA-256");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
}
