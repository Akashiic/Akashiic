package br.com.atmbrasil.lobby.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/** Immutable, reviewed client profile bundled inside the Velocity JAR. */
final class SilentGearEmbeddedProfile {
    static final String RESOURCE_ROOT = "silentgear-profiles/atm10-tts-2.0.2/";
    static final String ATM10_NORMAL_7_3_RESOURCE_ROOT =
            "silentgear-profiles/atm10-normal-7.3/";
    static final String ATM10_NORMAL_8_0_RESOURCE_ROOT =
            "silentgear-profiles/atm10-normal-8.0/";

    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9._-]{1,96}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> REVIEWED_FILES = List.of(
            "traits.bin",
            "materials.bin",
            "parts.bin");
    private static final ReviewedProfile ATM10_TTS_2_0_2 = new ReviewedProfile(
            RESOURCE_ROOT,
            SilentGearProtocol.ATM10_TTS_4_1_3,
            SilentGearProtocol.reviewedContractSha256(),
            Optional.empty(),
            false,
            false);
    private static final ReviewedProfile ATM10_NORMAL_7_3 = new ReviewedProfile(
            ATM10_NORMAL_7_3_RESOURCE_ROOT,
            SilentGearProtocol.ATM10_NORMAL_4_2,
            SilentGearProtocol.ATM10_NORMAL_4_2.canonicalContractSha256(),
            Optional.of(ReviewedClientContractEvidence.ATM10_NORMAL_7_3
                    .fullClientContractSha256()),
            true,
            true);
    private static final ReviewedProfile ATM10_NORMAL_8_0 = new ReviewedProfile(
            ATM10_NORMAL_8_0_RESOURCE_ROOT,
            SilentGearProtocol.ATM10_NORMAL_4_2,
            SilentGearProtocol.ATM10_NORMAL_4_2.canonicalContractSha256(),
            Optional.of(ReviewedClientContractEvidence.ATM10_NORMAL_8_0
                    .fullClientContractSha256()),
            true,
            true);

    private final String profileId;
    private final String packRelease;
    private final String packInternalVersion;
    private final String neoForgeVersion;
    private final int minecraftProtocol;
    private final String reviewedContractSha256;
    private final String silentGearChannelContractSha256;
    private final Optional<String> fullClientContractSha256;
    private final String payloadSequenceSha256;
    private final Map<String, Payload> payloads;
    private final int payloadBytes;
    private final NeoForgeFrozenRegistryProfile frozenRegistries;
    private final EmbeddedDynamicRegistryProfile dynamicRegistries;
    private final EmbeddedRegistryTagsProfile dynamicRegistryTags;
    private final Optional<BlockStateTranslationProfile> blockStateTranslation;

    private SilentGearEmbeddedProfile(
            String profileId,
            String packRelease,
            String packInternalVersion,
            String neoForgeVersion,
            int minecraftProtocol,
            String reviewedContractSha256,
            String silentGearChannelContractSha256,
            Optional<String> fullClientContractSha256,
            String payloadSequenceSha256,
            Map<String, Payload> payloads,
            int payloadBytes,
            NeoForgeFrozenRegistryProfile frozenRegistries,
            EmbeddedDynamicRegistryProfile dynamicRegistries,
            EmbeddedRegistryTagsProfile dynamicRegistryTags,
            Optional<BlockStateTranslationProfile> blockStateTranslation) {
        this.profileId = profileId;
        this.packRelease = packRelease;
        this.packInternalVersion = packInternalVersion;
        this.neoForgeVersion = neoForgeVersion;
        this.minecraftProtocol = minecraftProtocol;
        this.reviewedContractSha256 = reviewedContractSha256;
        this.silentGearChannelContractSha256 = Objects.requireNonNull(
                silentGearChannelContractSha256, "silentGearChannelContractSha256");
        this.fullClientContractSha256 = Objects.requireNonNull(
                fullClientContractSha256, "fullClientContractSha256");
        this.payloadSequenceSha256 = payloadSequenceSha256;
        this.payloads = Collections.unmodifiableMap(new LinkedHashMap<>(payloads));
        this.payloadBytes = payloadBytes;
        this.frozenRegistries = Objects.requireNonNull(
                frozenRegistries, "frozenRegistries");
        this.dynamicRegistries = Objects.requireNonNull(
                dynamicRegistries, "dynamicRegistries");
        this.dynamicRegistryTags = Objects.requireNonNull(
                dynamicRegistryTags, "dynamicRegistryTags");
        this.blockStateTranslation = Objects.requireNonNull(
                blockStateTranslation, "blockStateTranslation");
    }

    static SilentGearEmbeddedProfile loadReviewed(
            ClassLoader loader,
            int expectedMinecraftProtocol,
            int maximumPayloadBytes,
            int maximumTotalBytes) throws IOException {
        return load(
                loader,
                ATM10_TTS_2_0_2,
                expectedMinecraftProtocol,
                maximumPayloadBytes,
                maximumTotalBytes);
    }

    static SilentGearEmbeddedProfile loadAtm10Normal73(
            ClassLoader loader,
            int expectedMinecraftProtocol,
            int maximumPayloadBytes,
            int maximumTotalBytes) throws IOException {
        return load(
                loader,
                ATM10_NORMAL_7_3,
                expectedMinecraftProtocol,
                maximumPayloadBytes,
                maximumTotalBytes);
    }

    static SilentGearEmbeddedProfile loadAtm10Normal80(
            ClassLoader loader,
            int expectedMinecraftProtocol,
            int maximumPayloadBytes,
            int maximumTotalBytes) throws IOException {
        return load(
                loader,
                ATM10_NORMAL_8_0,
                expectedMinecraftProtocol,
                maximumPayloadBytes,
                maximumTotalBytes);
    }

    private static SilentGearEmbeddedProfile load(
            ClassLoader loader,
            ReviewedProfile reviewedProfile,
            int expectedMinecraftProtocol,
            int maximumPayloadBytes,
            int maximumTotalBytes) throws IOException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(reviewedProfile, "reviewedProfile");
        if (maximumPayloadBytes < 1 || maximumTotalBytes < maximumPayloadBytes) {
            throw new IllegalArgumentException("invalid Silent Gear profile limits");
        }

        Properties manifest = loadManifest(loader, reviewedProfile.resourceRoot());
        SilentGearProtocol.Contract contract = reviewedProfile.contract();
        requireEquals(manifest, "format-version", "2");
        requireEquals(manifest, "minecraft-version", "1.21.1");
        requireEquals(manifest, "silentgear-version", contract.modVersion());
        requireEquals(manifest, "silentgear-network-version", contract.networkVersion());
        requireEquals(manifest, "payload-count", Integer.toString(
                SilentGearProtocol.SYNC_CHANNELS.size()));
        validateDeclaredContract(manifest, reviewedProfile);
        validateExactCollectionInputs(manifest, reviewedProfile);

        String profileId = require(manifest, "profile-id");
        if (!PROFILE_ID.matcher(profileId).matches()) {
            throw new IllegalArgumentException("invalid embedded Silent Gear profile id");
        }
        int minecraftProtocol = positiveInt(manifest, "minecraft-protocol");
        if (minecraftProtocol != expectedMinecraftProtocol) {
            throw new IllegalArgumentException(
                    "embedded Silent Gear profile protocol " + minecraftProtocol
                            + " does not match configured protocol " + expectedMinecraftProtocol);
        }

        String contractSha256 = requireSha256(manifest, "reviewed-contract-sha256");
        if (!contractSha256.equals(reviewedProfile.manifestContractSha256())) {
            throw new IllegalArgumentException(
                    "embedded Silent Gear profile contract hash does not match reviewed source");
        }
        reviewedProfile.fullClientContractSha256().ifPresent(expected -> {
            if (!requireSha256(manifest, "full-client-contract-sha256").equals(expected)) {
                throw new IllegalArgumentException(
                        "embedded Silent Gear full client contract hash mismatch");
            }
        });

        MessageDigest sequenceDigest = newSha256();
        LinkedHashMap<String, Payload> payloads = new LinkedHashMap<>();
        int totalBytes = 0;
        for (int index = 0; index < SilentGearProtocol.SYNC_CHANNELS.size(); index++) {
            String key = "payload." + index + '.';
            String expectedChannel = SilentGearProtocol.SYNC_CHANNELS.get(index);
            String expectedFile = REVIEWED_FILES.get(index);
            requireEquals(manifest, key + "channel", expectedChannel);
            requireEquals(manifest, key + "file", expectedFile);

            int declaredEntries = positiveInt(manifest, key + "entries");
            int declaredBytes = positiveInt(manifest, key + "bytes");
            if (declaredBytes > maximumPayloadBytes) {
                throw new IllegalArgumentException(
                        "embedded Silent Gear payload exceeds configured per-payload limit: "
                                + expectedChannel);
            }

            byte[] bytes = readBounded(
                    loader,
                    reviewedProfile.resourceRoot() + expectedFile,
                    maximumPayloadBytes);
            if (bytes.length != declaredBytes) {
                throw new IllegalArgumentException(
                        "embedded Silent Gear payload length mismatch: " + expectedChannel);
            }
            int actualEntries = SilentGearProtocol.validateNonEmptyMapPayload(
                    bytes, maximumPayloadBytes);
            if (actualEntries != declaredEntries) {
                throw new IllegalArgumentException(
                        "embedded Silent Gear entry count mismatch: " + expectedChannel);
            }

            String declaredSha256 = requireSha256(manifest, key + "sha256");
            String actualSha256 = SilentGearProtocol.sha256(bytes);
            if (!actualSha256.equals(declaredSha256)) {
                throw new IllegalArgumentException(
                        "embedded Silent Gear payload hash mismatch: " + expectedChannel);
            }

            totalBytes = Math.addExact(totalBytes, bytes.length);
            if (totalBytes > maximumTotalBytes) {
                throw new IllegalArgumentException(
                        "embedded Silent Gear profile exceeds configured total byte limit");
            }
            sequenceDigest.update(bytes);
            payloads.put(expectedChannel, new Payload(
                    expectedChannel, declaredEntries, bytes, actualSha256));
        }

        if (totalBytes != positiveInt(manifest, "payload-total-bytes")) {
            throw new IllegalArgumentException("embedded Silent Gear total byte count mismatch");
        }
        String sequenceSha256 = requireSha256(manifest, "payload-sequence-sha256");
        String actualSequenceSha256 = HexFormat.of().formatHex(sequenceDigest.digest());
        if (!actualSequenceSha256.equals(sequenceSha256)) {
            throw new IllegalArgumentException("embedded Silent Gear sequence hash mismatch");
        }

        NeoForgeFrozenRegistryProfile frozenRegistries =
                NeoForgeFrozenRegistryProfile.loadReviewed(
                        loader, manifest, reviewedProfile.resourceRoot());
        EmbeddedDynamicRegistryProfile dynamicRegistries =
                reviewedProfile.loadsDynamicRegistries()
                        ? reviewedProfile.resourceRoot().equals(ATM10_NORMAL_8_0_RESOURCE_ROOT)
                                ? EmbeddedDynamicRegistryProfile.loadAtm10Normal80(loader)
                                : EmbeddedDynamicRegistryProfile.loadAtm10Normal73(loader)
                        : EmbeddedDynamicRegistryProfile.empty();
        EmbeddedRegistryTagsProfile dynamicRegistryTags =
                reviewedProfile.resourceRoot().equals(ATM10_NORMAL_8_0_RESOURCE_ROOT)
                        ? EmbeddedRegistryTagsProfile.loadAtm10Normal80(loader)
                        : EmbeddedRegistryTagsProfile.empty();

        Optional<BlockStateTranslationProfile> blockStateTranslation =
                reviewedProfile.resourceRoot().equals(ATM10_NORMAL_8_0_RESOURCE_ROOT)
                        ? Optional.of(BlockStateTranslationProfile.loadAtm10Normal80(
                                loader,
                                reviewedProfile.resourceRoot(),
                                profileId,
                                minecraftProtocol,
                                reviewedProfile.fullClientContractSha256().orElseThrow(),
                                frozenRegistries.sequenceSha256()))
                        : Optional.empty();

        return new SilentGearEmbeddedProfile(
                profileId,
                require(manifest, "pack-release"),
                require(manifest, "pack-internal-version"),
                require(manifest, "neoforge-version"),
                minecraftProtocol,
                contractSha256,
                contract.canonicalContractSha256(),
                reviewedProfile.fullClientContractSha256(),
                sequenceSha256,
                payloads,
                totalBytes,
                frozenRegistries,
                dynamicRegistries,
                dynamicRegistryTags,
                blockStateTranslation);
    }

    private static void validateDeclaredContract(
            Properties manifest, ReviewedProfile reviewedProfile) {
        if (!reviewedProfile.declaresCanonicalChannels()) {
            return;
        }
        SilentGearProtocol.Contract contract = reviewedProfile.contract();
        requireEquals(
                manifest,
                "silentgear-channel-contract-sha256",
                contract.canonicalContractSha256());
        requireEquals(
                manifest,
                "silentgear-channel-count",
                Integer.toString(contract.channels().size()));
        for (int index = 0; index < contract.channels().size(); index++) {
            br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel channel =
                    contract.channels().get(index);
            String key = "silentgear-channel." + index + '.';
            requireEquals(manifest, key + "id", channel.id());
            requireEquals(manifest, key + "version", channel.version());
            requireEquals(manifest, key + "flow", channel.flow().name());
            requireEquals(manifest, key + "optional", Boolean.toString(channel.optional()));
        }
    }

    private static void validateExactCollectionInputs(
            Properties manifest, ReviewedProfile reviewedProfile) {
        if (!reviewedProfile.resourceRoot().equals(ATM10_NORMAL_8_0_RESOURCE_ROOT)) {
            return;
        }
        requireEquals(manifest, "curseforge-client-file-id", "8649077");
        requireEquals(manifest, "curseforge-server-file-id", "8649107");
        requireEquals(
                manifest,
                "server-files-sha256",
                "2150885deb54f97a63a17291b34706eaa713f2b0a5e31ac701c846383db306cc");
        requireEquals(
                manifest,
                "client-query-sha256",
                "defcad0781fa208aa5ecb9358831d140eb530a499066e8694833989fb6dbdd4c");
        requireEquals(manifest, "dynamic-registry.count", "50");
        requireEquals(manifest, "dynamic-registry.total-entries", "1946");
        requireEquals(manifest, "dynamic-registry.total-bytes", "651622");
        requireEquals(
                manifest,
                "dynamic-registry.sequence-sha256",
                "e78c792986feed4fe861994cceb7ffb6dc24401feda7a600f272c6eef74a0935");
        requireEquals(
                manifest,
                "dynamic-registry-tags.registry",
                "neovitae:sentient_upgrades");
        requireEquals(manifest, "dynamic-registry-tags.tags", "6");
        requireEquals(manifest, "dynamic-registry-tags.members", "101");
        requireEquals(manifest, "dynamic-registry-tags.file", "dynamic-registry-tags.bin");
        requireEquals(manifest, "dynamic-registry-tags.bytes", "267");
        requireEquals(
                manifest,
                "dynamic-registry-tags.sha256",
                "3d11e2231295e07d3a5bbd1326defa18e1b519cc64f75b6e8f2903e9101c6001");
        requireEquals(manifest, "input-repair.count", "1");
        requireEquals(manifest, "input-repair.0.curseforge-file-id", "8364209");
        requireEquals(
                manifest,
                "input-repair.0.truncated-sha256",
                "37e4ff18ce0504c9d21dd4611b021549eaa0da9f5ad6db463953ff13ee04e122");
        requireEquals(
                manifest,
                "input-repair.0.restored-sha256",
                "679c8687281cdac01e80de1672f27db1baf6ad5c68c74d752806b9d5ce246ac3");
        requireEquals(manifest, "server-config.count", "286");
        requireEquals(
                manifest,
                "server-config.sequence-sha256",
                "4537a91cdd364d0c8e942a9901811454ffe443ce9f3f8bfc0b33d92ec4956c38");
        requireEquals(manifest, "apothic-enchanting.entries", "135");
        requireEquals(manifest, "apothic-enchanting.bytes", "1643");
        requireEquals(
                manifest,
                "apothic-enchanting.sha256",
                ApothicEnchantingBootstrapPayload.ATM10_NORMAL_8_0_OFFICIAL_PAYLOAD_SHA256);
        requireEquals(manifest, "mekanism-batch-security.bytes", "2");
        requireEquals(
                manifest,
                "mekanism-batch-security.sha256",
                "96a296d224f285c67bee93c30f8a309157f0daa35dc5b87e410b78630a09cfc7");
    }

    private static Properties loadManifest(ClassLoader loader, String resourceRoot)
            throws IOException {
        try (InputStream stream = loader.getResourceAsStream(
                resourceRoot + "profile.properties")) {
            if (stream == null) {
                throw new IOException("missing embedded Silent Gear profile manifest");
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
                throw new IOException("missing embedded Silent Gear resource " + resource);
            }
            byte[] bytes = stream.readNBytes(Math.addExact(maximumBytes, 1));
            if (bytes.length > maximumBytes) {
                throw new IllegalArgumentException(
                        "embedded Silent Gear resource exceeds configured limit: " + resource);
            }
            return bytes;
        }
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "embedded Silent Gear manifest is missing " + key);
        }
        return value.strip();
    }

    private static void requireEquals(Properties properties, String key, String expected) {
        String actual = require(properties, key);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "embedded Silent Gear manifest " + key + " mismatch: " + actual);
        }
    }

    private static int positiveInt(Properties properties, String key) {
        final int value;
        try {
            value = Integer.parseInt(require(properties, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "embedded Silent Gear manifest has invalid integer " + key, exception);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "embedded Silent Gear manifest integer must be positive: " + key);
        }
        return value;
    }

    private static String requireSha256(Properties properties, String key) {
        String value = require(properties, key);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "embedded Silent Gear manifest has invalid SHA-256: " + key);
        }
        return value;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    String profileId() {
        return profileId;
    }

    String packRelease() {
        return packRelease;
    }

    String packInternalVersion() {
        return packInternalVersion;
    }

    String neoForgeVersion() {
        return neoForgeVersion;
    }

    int minecraftProtocol() {
        return minecraftProtocol;
    }

    String reviewedContractSha256() {
        return reviewedContractSha256;
    }

    String silentGearChannelContractSha256() {
        return silentGearChannelContractSha256;
    }

    Optional<String> fullClientContractSha256() {
        return fullClientContractSha256;
    }

    String payloadSequenceSha256() {
        return payloadSequenceSha256;
    }

    List<String> channels() {
        return SilentGearProtocol.SYNC_CHANNELS;
    }

    byte[] payload(String channelId) {
        Payload payload = payloads.get(channelId);
        if (payload == null) {
            throw new IllegalArgumentException("profile has no payload for " + channelId);
        }
        return payload.bytes();
    }

    int entryCount(String channelId) {
        Payload payload = requirePayload(channelId);
        return payload.entryCount();
    }

    int payloadBytes(String channelId) {
        return requirePayload(channelId).bytes().length;
    }

    String payloadSha256(String channelId) {
        return requirePayload(channelId).sha256();
    }

    int payloadBytes() {
        return payloadBytes;
    }

    NeoForgeFrozenRegistryProfile frozenRegistries() {
        return frozenRegistries;
    }

    EmbeddedDynamicRegistryProfile dynamicRegistries() {
        return dynamicRegistries;
    }

    EmbeddedRegistryTagsProfile dynamicRegistryTags() {
        return dynamicRegistryTags;
    }

    Optional<BlockStateTranslationProfile> blockStateTranslation() {
        return blockStateTranslation;
    }

    private Payload requirePayload(String channelId) {
        Payload payload = payloads.get(channelId);
        if (payload == null) {
            throw new IllegalArgumentException("profile has no payload for " + channelId);
        }
        return payload;
    }

    private record Payload(String channelId, int entryCount, byte[] bytes, String sha256) {
        private Payload {
            Objects.requireNonNull(channelId, "channelId");
            Objects.requireNonNull(sha256, "sha256");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record ReviewedProfile(
            String resourceRoot,
            SilentGearProtocol.Contract contract,
            String manifestContractSha256,
            Optional<String> fullClientContractSha256,
            boolean declaresCanonicalChannels,
            boolean loadsDynamicRegistries) {
        private ReviewedProfile {
            if (Objects.requireNonNull(resourceRoot, "resourceRoot").isBlank()
                    || !resourceRoot.endsWith("/")) {
                throw new IllegalArgumentException(
                        "embedded Silent Gear resource root must end with '/'");
            }
            Objects.requireNonNull(contract, "contract");
            if (!SHA256.matcher(Objects.requireNonNull(
                    manifestContractSha256, "manifestContractSha256")).matches()) {
                throw new IllegalArgumentException(
                        "embedded Silent Gear reviewed contract must be a SHA-256");
            }
            fullClientContractSha256 = Objects.requireNonNull(
                    fullClientContractSha256, "fullClientContractSha256");
            fullClientContractSha256.ifPresent(value -> {
                if (!SHA256.matcher(value).matches()) {
                    throw new IllegalArgumentException(
                            "embedded Silent Gear full client contract must be a SHA-256");
                }
            });
        }
    }
}
