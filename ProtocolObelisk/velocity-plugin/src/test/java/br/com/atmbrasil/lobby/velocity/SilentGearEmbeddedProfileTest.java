package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class SilentGearEmbeddedProfileTest {
    private static final int PROTOCOL = 767;
    private static final int PAYLOAD_LIMIT = 1_048_576;
    private static final int TOTAL_LIMIT = 3_145_728;
    private static final String NORMAL_FULL_CONTRACT =
            "cfce57a5a93240f97d570e952c3b4d71e5fac1f11504289f5ea4265371da559a";
    private static final String NORMAL_8_0_FULL_CONTRACT =
            "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f";
    private static final String NORMAL_SILENT_GEAR_CONTRACT =
            "003a1f69d13a92e3a9d6c70288dc38e6fc1e29c0c8df4513cbb06653736e0d44";

    @Test
    void reviewedFixtureLoadsWithExactMetadataHashesAndOrder() throws Exception {
        SilentGearEmbeddedProfile profile = load(defaultLoader());

        assertEquals(
                "atm10-tts-2.0.2_silentgear-4.1.3.1_neoforge-21.1.221",
                profile.profileId());
        assertEquals("2.0.2", profile.packRelease());
        assertEquals("2.0.1", profile.packInternalVersion());
        assertEquals("21.1.221", profile.neoForgeVersion());
        assertEquals(PROTOCOL, profile.minecraftProtocol());
        assertEquals(SilentGearProtocol.reviewedContractSha256(),
                profile.reviewedContractSha256());
        assertEquals(
                "994cb14de20346ab71e51ffdf378333eaa0068cbc8fad1faeda5ef0b5e2aa72f",
                profile.silentGearChannelContractSha256());
        assertEquals(SilentGearProtocol.SYNC_CHANNELS, profile.channels());
        assertEquals(94, profile.entryCount(SilentGearProtocol.SYNC_TRAITS));
        assertEquals(160, profile.entryCount(SilentGearProtocol.SYNC_MATERIALS));
        assertEquals(48, profile.entryCount(SilentGearProtocol.SYNC_PARTS));
        assertEquals(87_544, profile.payloadBytes());
        assertEquals(
                "d49d834a6dda527213fd5d31b694954e9c479a8fcd0b7ec280bed82556bd1a62",
                profile.payloadSha256(SilentGearProtocol.SYNC_TRAITS));
        assertEquals(
                "acd2447822d2353bd57f73ea6b1f9c5f945cbe1306ec7f9c83ff27353af2c73d",
                profile.payloadSha256(SilentGearProtocol.SYNC_MATERIALS));
        assertEquals(
                "4f0377775be78326d21abd8c8ed6c6e33ef435115d8f7bf2e85f4c0546c4fcb3",
                profile.payloadSha256(SilentGearProtocol.SYNC_PARTS));
        assertEquals(
                "f5aa04b8f7ad7769a7183115490248b89aed4501afbb158c01806011d448f980",
                profile.payloadSequenceSha256());
        assertEquals(85, profile.frozenRegistries().registryCount());
        assertEquals(87_540, profile.frozenRegistries().totalEntries());
        assertEquals(3_238_425, profile.frozenRegistries().totalRegistryBytes());
        assertEquals(3_240_838, profile.frozenRegistries().totalTransactionBytes());
        assertEquals(1_571_511, profile.frozenRegistries().maximumPayloadBytes());
        assertEquals(
                "77f7340a9cdfcad60a54ee8415026b77a34070702c997bbd861e1da21d93681e",
                profile.frozenRegistries().sequenceSha256());
        assertTrue(profile.dynamicRegistries().isEmpty());
        assertTrue(profile.dynamicRegistryTags().isEmpty());
        assertTrue(profile.blockStateTranslation().isEmpty());
    }

    @Test
    void profilePayloadsAreDefensivelyCopied() throws Exception {
        SilentGearEmbeddedProfile profile = load(defaultLoader());
        byte[] first = profile.payload(SilentGearProtocol.SYNC_TRAITS);
        byte[] original = first.clone();
        first[0] ^= 0x7F;

        assertArrayEquals(original, profile.payload(SilentGearProtocol.SYNC_TRAITS));
    }

    @Test
    void reviewedNormal73FixtureLoadsWithExactMetadataHashesAndBounds() throws Exception {
        SilentGearEmbeddedProfile profile = loadNormal(defaultLoader());

        assertEquals(
                "atm10-normal-7.3_silentgear-4.2.1.1_neoforge-21.1.247",
                profile.profileId());
        assertEquals("7.3", profile.packRelease());
        assertEquals("7.3", profile.packInternalVersion());
        assertEquals("21.1.247", profile.neoForgeVersion());
        assertEquals(PROTOCOL, profile.minecraftProtocol());
        assertEquals(NORMAL_SILENT_GEAR_CONTRACT, profile.reviewedContractSha256());
        assertEquals(NORMAL_SILENT_GEAR_CONTRACT,
                profile.silentGearChannelContractSha256());
        assertEquals(NORMAL_FULL_CONTRACT,
                profile.fullClientContractSha256().orElseThrow());
        assertEquals(94, profile.entryCount(SilentGearProtocol.SYNC_TRAITS));
        assertEquals(160, profile.entryCount(SilentGearProtocol.SYNC_MATERIALS));
        assertEquals(48, profile.entryCount(SilentGearProtocol.SYNC_PARTS));
        assertEquals(91_022, profile.payloadBytes());
        assertEquals(
                "e06a59339155f5dcfd04e6f9ebb76cf7c4bf59c41802aa20481946da98c07146",
                profile.payloadSha256(SilentGearProtocol.SYNC_TRAITS));
        assertEquals(
                "456383fc14617387c91ea66aa70f0add6cda38a698f3933010c9c8a3c5345398",
                profile.payloadSha256(SilentGearProtocol.SYNC_MATERIALS));
        assertEquals(
                "493ec5991e22c1f14650c19ff7a88c590a003d9a84a500790aa695da3ec77eb5",
                profile.payloadSha256(SilentGearProtocol.SYNC_PARTS));
        assertEquals(
                "fd87de4da9ec19e25684f1faa9e6caeecab92c205f755fdc12e0e2cd97de8028",
                profile.payloadSequenceSha256());
        assertEquals(116, profile.frozenRegistries().registryCount());
        assertEquals(134_191, profile.frozenRegistries().totalEntries());
        assertEquals(5_438_455, profile.frozenRegistries().totalRegistryBytes());
        assertEquals(5_441_756, profile.frozenRegistries().totalTransactionBytes());
        assertEquals(2_593_088, profile.frozenRegistries().maximumPayloadBytes());
        assertEquals(
                "3ffdf5503e657f3ce1f891a7d8e2236185e12bcc54cfc0d0c27fe42fcf5a4893",
                profile.frozenRegistries().sequenceSha256());
        assertEquals(46, profile.dynamicRegistries().registryCount());
        assertEquals(1_826, profile.dynamicRegistries().totalEntries());
        assertEquals(577_165, profile.dynamicRegistries().totalBytes());
        assertEquals(
                "69cbe809ad82bd56c45cf01d8f96a434c614736780360d42129bb3fbc927bb5f",
                profile.dynamicRegistries().sequenceSha256());
        assertTrue(profile.dynamicRegistryTags().isEmpty());
        assertTrue(profile.blockStateTranslation().isEmpty());
    }

    @Test
    void reviewedNormal80FixtureLoadsWithExactMetadataHashesAndBounds() throws Exception {
        SilentGearEmbeddedProfile profile = loadNormal80(defaultLoader());

        assertEquals(
                "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                profile.profileId());
        assertEquals("8.0", profile.packRelease());
        assertEquals("8.0", profile.packInternalVersion());
        assertEquals("21.1.247", profile.neoForgeVersion());
        assertEquals(PROTOCOL, profile.minecraftProtocol());
        assertEquals(NORMAL_SILENT_GEAR_CONTRACT, profile.reviewedContractSha256());
        assertEquals(NORMAL_SILENT_GEAR_CONTRACT,
                profile.silentGearChannelContractSha256());
        assertEquals(NORMAL_8_0_FULL_CONTRACT,
                profile.fullClientContractSha256().orElseThrow());
        assertEquals(94, profile.entryCount(SilentGearProtocol.SYNC_TRAITS));
        assertEquals(160, profile.entryCount(SilentGearProtocol.SYNC_MATERIALS));
        assertEquals(48, profile.entryCount(SilentGearProtocol.SYNC_PARTS));
        assertEquals(91_037, profile.payloadBytes());
        assertEquals(
                "2aca1a24e137d69efd0a76a8985adac191f52ec0cc6990eb1edaa8c56483f438",
                profile.payloadSha256(SilentGearProtocol.SYNC_TRAITS));
        assertEquals(
                "593fe3790033905953f34be7a1e2cb7ce9d2bf982c05627d6305c8e2cfd1c700",
                profile.payloadSha256(SilentGearProtocol.SYNC_MATERIALS));
        assertEquals(
                "7e698a2e7cbd140b00164b143af81382a28f1ff96513c7000aa4223932c8c7d2",
                profile.payloadSha256(SilentGearProtocol.SYNC_PARTS));
        assertEquals(
                "7100aec605d3b8c88db8f28a93ba7e1188a65ec0fd4adca3dac3edd2797ef56d",
                profile.payloadSequenceSha256());
        assertEquals(118, profile.frozenRegistries().registryCount());
        assertEquals(135_564, profile.frozenRegistries().totalEntries());
        assertEquals(5_479_746, profile.frozenRegistries().totalRegistryBytes());
        assertEquals(5_483_089, profile.frozenRegistries().totalTransactionBytes());
        assertEquals(2_619_214, profile.frozenRegistries().maximumPayloadBytes());
        assertEquals(
                "8fda0099a55ad36898adbf43dc77d5b702c6d4c5c26c1bcddb3039669dd00269",
                profile.frozenRegistries().sequenceSha256());
        assertEquals(50, profile.dynamicRegistries().registryCount());
        assertEquals(1_946, profile.dynamicRegistries().totalEntries());
        assertEquals(651_622, profile.dynamicRegistries().totalBytes());
        assertEquals(
                "e78c792986feed4fe861994cceb7ffb6dc24401feda7a600f272c6eef74a0935",
                profile.dynamicRegistries().sequenceSha256());
        assertEquals("neovitae:sentient_upgrades", profile.dynamicRegistryTags().registryId());
        assertEquals(6, profile.dynamicRegistryTags().tags().size());
        assertEquals(101, profile.dynamicRegistryTags().totalMembers());
        BlockStateTranslationProfile blockStates =
                profile.blockStateTranslation().orElseThrow();
        assertEquals(profile.profileId(), blockStates.profileId());
        assertEquals(PROTOCOL, blockStates.minecraftProtocol());
        assertEquals(26_684, blockStates.sourceStateCount());
        assertEquals(1_980_659, blockStates.clientGlobalStateCount());
        assertEquals(15, blockStates.sourceGlobalPaletteBits());
        assertEquals(21, blockStates.targetGlobalPaletteBits());
        assertEquals(0, blockStates.translate(0));
        assertEquals(46, blockStates.translate(46));
        assertEquals(55, blockStates.translate(47));
        assertEquals(9_029, blockStates.translate(6_537));
        assertEquals(45_478, blockStates.translate(26_683));
        assertEquals(
                "6015f4b9f30e0d680d8d89439fad7f141eb901462a04bf4052865b0fec6fe263",
                blockStates.mapSha256());
        assertThrows(IllegalArgumentException.class, () -> blockStates.translate(-1));
        assertThrows(IllegalArgumentException.class, () -> blockStates.translate(26_684));
    }

    @Test
    void normal80BlockStateMapCorruptionFailsClosed() {
        ClassLoader corruptedMap = mutatingLoader(
                SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT
                        + "block-state-map.bin",
                bytes -> {
                    bytes[bytes.length - 1] ^= 0x01;
                    return bytes;
                });

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> loadNormal80(corruptedMap));
        assertTrue(exception.getMessage().contains("BlockState map hash mismatch"));
    }

    @Test
    void normal80ProfileManifestDynamicAggregateMismatchFailsClosed() {
        ClassLoader corruptedMetadata = mutatingLoader(
                SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT
                        + "profile.properties",
                bytes -> new String(bytes, StandardCharsets.ISO_8859_1)
                        .replace(
                                "dynamic-registry.total-entries=1946",
                                "dynamic-registry.total-entries=1947")
                        .getBytes(StandardCharsets.ISO_8859_1));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> loadNormal80(corruptedMetadata));
        assertTrue(exception.getMessage().contains(
                "dynamic-registry.total-entries mismatch"));
    }

    @Test
    void catalogSelectsOnlyExactProtocolAndReviewedSilentGearContract() throws Exception {
        SilentGearEmbeddedProfile profile = load(defaultLoader());
        SilentGearProfileCatalog catalog = new SilentGearProfileCatalog(List.of(profile));
        SilentGearProtocol.Compatibility exact = SilentGearProtocol.inspect(
                SilentGearProtocol.reviewedPlayContract());
        List<br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel> missing =
                new java.util.ArrayList<>(SilentGearProtocol.reviewedPlayContract());
        missing.removeLast();

        assertEquals(profile, catalog.select(PROTOCOL, exact).orElseThrow());
        assertTrue(catalog.select(PROTOCOL + 1, exact).isEmpty());
        assertTrue(catalog.select(
                PROTOCOL, SilentGearProtocol.inspect(missing)).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new SilentGearProfileCatalog(List.of(profile, profile)));
    }

    @Test
    void canonicalCatalogRequiresTheCompleteClientSignature() throws Exception {
        SilentGearEmbeddedProfile profile = load(defaultLoader());
        String fullContract = "1".repeat(64);
        SilentGearProfileCatalog catalog = SilentGearProfileCatalog.fromRegistrations(List.of(
                SilentGearProfileCatalog.Registration.canonical(profile, fullContract)));
        ChannelContractSignature.Signatures exact = new ChannelContractSignature.Signatures(
                fullContract, profile.silentGearChannelContractSha256());
        ChannelContractSignature.Signatures otherClient = new ChannelContractSignature.Signatures(
                "2".repeat(64), profile.silentGearChannelContractSha256());

        assertEquals(profile, catalog.select(PROTOCOL, exact).orElseThrow());
        assertTrue(catalog.select(PROTOCOL, otherClient).isEmpty());
        assertTrue(catalog.select(PROTOCOL + 1, exact).isEmpty());
    }

    @Test
    void canonicalFamilyNeverFallsBackToSilentGearOnlyLegacyMatch() throws Exception {
        SilentGearEmbeddedProfile profile = load(defaultLoader());
        String fullContract = "1".repeat(64);
        SilentGearProfileCatalog catalog = SilentGearProfileCatalog.fromRegistrations(List.of(
                SilentGearProfileCatalog.Registration.legacy(profile),
                SilentGearProfileCatalog.Registration.canonical(profile, fullContract)));
        ChannelContractSignature.Signatures exact = new ChannelContractSignature.Signatures(
                fullContract, profile.silentGearChannelContractSha256());
        ChannelContractSignature.Signatures mutated = new ChannelContractSignature.Signatures(
                "2".repeat(64), profile.silentGearChannelContractSha256());

        assertEquals(profile, catalog.select(PROTOCOL, exact).orElseThrow());
        assertTrue(catalog.select(PROTOCOL, mutated).isEmpty());
        assertTrue(catalog.select(PROTOCOL,
                SilentGearProtocol.inspect(SilentGearProtocol.reviewedPlayContract())).isEmpty());
    }

    @Test
    void reviewedCatalogSeparatesLegacyTtsAndCanonicalNormalContracts() throws Exception {
        SilentGearProfileCatalog catalog = SilentGearProfileCatalog.loadReviewed(
                defaultLoader(), PROTOCOL, PAYLOAD_LIMIT, TOTAL_LIMIT);
        ChannelContractSignature.Signatures normal = new ChannelContractSignature.Signatures(
                NORMAL_FULL_CONTRACT, NORMAL_SILENT_GEAR_CONTRACT);
        ChannelContractSignature.Signatures normal80 = new ChannelContractSignature.Signatures(
                NORMAL_8_0_FULL_CONTRACT, NORMAL_SILENT_GEAR_CONTRACT);
        ChannelContractSignature.Signatures wrongNormalClient =
                new ChannelContractSignature.Signatures(
                        "2".repeat(64), NORMAL_SILENT_GEAR_CONTRACT);
        ChannelContractSignature.Signatures tts = new ChannelContractSignature.Signatures(
                "3".repeat(64),
                SilentGearProtocol.ATM10_TTS_4_1_3.canonicalContractSha256());

        assertEquals(3, catalog.size());
        assertEquals(2_619_214, catalog.maximumFrozenRegistryPayloadBytes());
        assertEquals(
                "atm10-normal-7.3_silentgear-4.2.1.1_neoforge-21.1.247",
                catalog.select(PROTOCOL, normal).orElseThrow().profileId());
        assertEquals(
                "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                catalog.select(PROTOCOL, normal80).orElseThrow().profileId());
        assertTrue(catalog.select(PROTOCOL, wrongNormalClient).isEmpty());
        assertEquals(
                "atm10-tts-2.0.2_silentgear-4.1.3.1_neoforge-21.1.221",
                catalog.select(PROTOCOL, tts).orElseThrow().profileId());
        assertTrue(catalog.select(PROTOCOL + 1, normal).isEmpty());
    }

    @Test
    void neoForgeClientWithoutSilentGearDoesNotConstructAnInvalidProfileKey()
            throws Exception {
        SilentGearProfileCatalog catalog = SilentGearProfileCatalog.loadReviewed(
                defaultLoader(), PROTOCOL, PAYLOAD_LIMIT, TOTAL_LIMIT);
        ChannelContractSignature.Signatures withoutSilentGear =
                new ChannelContractSignature.Signatures("4".repeat(64), "");

        assertTrue(catalog.select(PROTOCOL, withoutSilentGear).isEmpty());
    }

    @Test
    void reviewedTtsProfileResourceTreeIsByteIdentical() throws Exception {
        ClassLoader loader = defaultLoader();
        byte[] manifestBytes = readResourceBytes(
                loader, SilentGearEmbeddedProfile.RESOURCE_ROOT + "profile.properties");
        Properties manifest = new Properties();
        try (InputStream stream = new ByteArrayInputStream(manifestBytes)) {
            manifest.load(stream);
        }

        List<String> resources = new ArrayList<>();
        resources.add("profile.properties");
        int payloadCount = Integer.parseInt(manifest.getProperty("payload-count"));
        for (int index = 0; index < payloadCount; index++) {
            resources.add(manifest.getProperty("payload." + index + ".file"));
        }
        resources.add(manifest.getProperty("frozen-registry-start.file"));
        int frozenRegistryCount = Integer.parseInt(
                manifest.getProperty("frozen-registry.count"));
        for (int index = 0; index < frozenRegistryCount; index++) {
            resources.add(manifest.getProperty("frozen-registry." + index + ".file"));
        }
        resources.add(manifest.getProperty("frozen-registry-completed.file"));
        resources.sort(String::compareTo);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String relative : resources) {
            byte[] name = relative.getBytes(StandardCharsets.UTF_8);
            byte[] content = relative.equals("profile.properties")
                    ? manifestBytes
                    : readResourceBytes(loader, SilentGearEmbeddedProfile.RESOURCE_ROOT + relative);
            updateInt(digest, name.length);
            digest.update(name);
            updateInt(digest, content.length);
            digest.update(content);
        }

        assertEquals(91, resources.size());
        assertEquals(
                "e51ec65d47a622056a344af90681cbbb540e065aa3a8bce6b9d4afc5cae116bd",
                HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    void corruptedPayloadHashFailsClosed() {
        ClassLoader corrupted = mutatingLoader(
                SilentGearEmbeddedProfile.RESOURCE_ROOT + "materials.bin",
                bytes -> {
                    bytes[bytes.length - 1] ^= 0x01;
                    return bytes;
                });

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> load(corrupted));
        assertTrue(exception.getMessage().contains("hash mismatch"));
    }

    @Test
    void missingProfileWrongProtocolAndTightLimitsFailClosed() {
        ClassLoader missing = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        };

        assertThrows(IOException.class, () -> load(missing));
        assertThrows(IllegalArgumentException.class, () ->
                SilentGearEmbeddedProfile.loadReviewed(
                        defaultLoader(), PROTOCOL + 1, PAYLOAD_LIMIT, TOTAL_LIMIT));
        assertThrows(IllegalArgumentException.class, () ->
                SilentGearEmbeddedProfile.loadReviewed(
                        defaultLoader(), PROTOCOL, 50_000, TOTAL_LIMIT));
        assertThrows(IllegalArgumentException.class, () ->
                SilentGearEmbeddedProfile.loadReviewed(
                        defaultLoader(), PROTOCOL, 70_000, 80_000));
    }

    @Test
    void invalidManifestContractHashFailsClosed() {
        ClassLoader corrupted = mutatingLoader(
                SilentGearEmbeddedProfile.RESOURCE_ROOT + "profile.properties",
                bytes -> new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                        .replace(
                                SilentGearProtocol.reviewedContractSha256(),
                                "0".repeat(64))
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> load(corrupted));
        assertTrue(exception.getMessage().contains("contract hash"));
    }

    @Test
    void normalProfileCorruptionAndChannelMetadataMismatchFailClosed() {
        ClassLoader corruptedPayload = mutatingLoader(
                SilentGearEmbeddedProfile.ATM10_NORMAL_7_3_RESOURCE_ROOT + "materials.bin",
                bytes -> {
                    bytes[bytes.length - 1] ^= 0x01;
                    return bytes;
                });
        ClassLoader corruptedMetadata = mutatingLoader(
                SilentGearEmbeddedProfile.ATM10_NORMAL_7_3_RESOURCE_ROOT
                        + "profile.properties",
                bytes -> new String(bytes, StandardCharsets.ISO_8859_1)
                        .replace(
                                "silentgear-channel.10.id=silentgear:toggle_work_mode",
                                "silentgear-channel.10.id=silentgear:ack")
                        .getBytes(StandardCharsets.ISO_8859_1));

        IllegalArgumentException payloadException = assertThrows(
                IllegalArgumentException.class, () -> loadNormal(corruptedPayload));
        assertTrue(payloadException.getMessage().contains("hash mismatch"));
        IllegalArgumentException metadataException = assertThrows(
                IllegalArgumentException.class, () -> loadNormal(corruptedMetadata));
        assertTrue(metadataException.getMessage().contains(
                "silentgear-channel.10.id mismatch"));
    }

    private static SilentGearEmbeddedProfile load(ClassLoader loader) throws IOException {
        return SilentGearEmbeddedProfile.loadReviewed(
                loader, PROTOCOL, PAYLOAD_LIMIT, TOTAL_LIMIT);
    }

    private static SilentGearEmbeddedProfile loadNormal(ClassLoader loader) throws IOException {
        return SilentGearEmbeddedProfile.loadAtm10Normal73(
                loader, PROTOCOL, PAYLOAD_LIMIT, TOTAL_LIMIT);
    }

    private static SilentGearEmbeddedProfile loadNormal80(ClassLoader loader) throws IOException {
        return SilentGearEmbeddedProfile.loadAtm10Normal80(
                loader, PROTOCOL, PAYLOAD_LIMIT, TOTAL_LIMIT);
    }

    private static byte[] readResourceBytes(ClassLoader loader, String resource)
            throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing test resource " + resource);
            }
            return stream.readAllBytes();
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static ClassLoader defaultLoader() {
        return SilentGearEmbeddedProfileTest.class.getClassLoader();
    }

    private static ClassLoader mutatingLoader(
            String targetResource, UnaryOperator<byte[]> mutation) {
        ClassLoader delegate = defaultLoader();
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                try (InputStream original = delegate.getResourceAsStream(name)) {
                    if (original == null) {
                        return null;
                    }
                    byte[] bytes = original.readAllBytes();
                    if (name.equals(targetResource)) {
                        bytes = mutation.apply(bytes);
                    }
                    return new ByteArrayInputStream(bytes);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        };
    }
}
