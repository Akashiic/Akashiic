package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReviewedBlockStateProfileCatalogTest {
    private static final String RESOURCE_ROOT = "blockstate-profiles/test/";
    private static final String PROFILE_ID = "atm10-normal-8.1-test";
    private static final String CONTRACT =
            Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256;

    @Test
    void exactProtocolAndFullContractSelectOnlyStructuralEnrichment() throws Exception {
        Fixture fixture = fixture(RESOURCE_ROOT, PROFILE_ID, CONTRACT);
        ReviewedBlockStateProfileCatalog catalog = load(fixture);

        BlockStateTranslationProfile selected = catalog.findStructuralEnrichment(
                Atm10Normal81Contract.MINECRAFT_PROTOCOL, CONTRACT).orElseThrow();
        assertEquals(PROFILE_ID, selected.profileId());
        assertEquals(6_537, selected.translate(6_537));
        assertEquals(List.of(selected), catalog.reviewedProfiles());

        assertTrue(catalog.findStructuralEnrichment(766, CONTRACT).isEmpty());
        assertTrue(catalog.findStructuralEnrichment(
                767, "0".repeat(64)).isEmpty());
        assertTrue(catalog.findStructuralEnrichment(
                767, CONTRACT.toUpperCase()).isEmpty());
        assertTrue(catalog.findStructuralEnrichment(767, null).isEmpty());
        assertTrue(ReviewedBlockStateProfileCatalog.empty()
                .findStructuralEnrichment(767, CONTRACT)
                .isEmpty());
    }

    @Test
    void exactPinnedLegacy80ResourceRemainsStructuralAndNeverProtocolFallback() {
        String normal80Contract =
                ReviewedClientContractEvidence.ATM10_NORMAL_8_0.fullClientContractSha256();
        ReviewedBlockStateProfileCatalog.Definition definition =
                new ReviewedBlockStateProfileCatalog.Definition(
                        SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT,
                        "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                        767,
                        normal80Contract,
                        "de39e16287bfa83396c9f54b1403cf5937d74b4c2ad819922f5540d7e219e419");

        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        ReviewedBlockStateProfileCatalogTest.class.getClassLoader(),
                        List.of(definition));

        assertTrue(catalog.diagnostics().isEmpty());
        assertEquals(
                9_029,
                catalog.findStructuralEnrichment(767, normal80Contract)
                        .orElseThrow()
                        .translate(6_537));
        assertTrue(catalog.findStructuralEnrichment(767, CONTRACT).isEmpty());
    }

    @Test
    void nonMonotonicButUniqueBoundedMapRemainsValid() throws Exception {
        Fixture fixture = fixture(
                RESOURCE_ROOT, PROFILE_ID, CONTRACT, false);

        BlockStateTranslationProfile selected = load(fixture)
                .findStructuralEnrichment(767, CONTRACT)
                .orElseThrow();

        assertEquals(11, selected.translate(10));
        assertEquals(10, selected.translate(11));
        assertEquals(26_683, selected.translate(26_683));
    }

    @Test
    void duplicateTargetFailsClosedEvenWhenManifestPinsItsBytes() throws Exception {
        Fixture fixture = fixture(
                RESOURCE_ROOT, PROFILE_ID, CONTRACT, false, true);

        ReviewedBlockStateProfileCatalog catalog = load(fixture);

        assertTrue(catalog.reviewedProfiles().isEmpty());
        assertEquals(
                ReviewedBlockStateProfileCatalog.LoadFailure.INVALID_RESOURCE,
                catalog.diagnostics().getFirst().failure());
        assertTrue(catalog.diagnostics().getFirst().detail().contains(
                "not unique and bounded"));
    }

    @Test
    void declaredTargetOrderMustMatchPinnedMap() throws Exception {
        Fixture fixture = fixture(RESOURCE_ROOT, PROFILE_ID, CONTRACT);
        Map<String, byte[]> resources = deepCopy(fixture.resources());
        String manifestResource = RESOURCE_ROOT + "block-state-map.properties";
        String mismatchedManifest = new String(
                resources.get(manifestResource), StandardCharsets.ISO_8859_1)
                .replace(
                        "mapping.target-ids-strictly-increasing=true",
                        "mapping.target-ids-strictly-increasing=false");
        byte[] manifestBytes = mismatchedManifest.getBytes(StandardCharsets.ISO_8859_1);
        resources.put(manifestResource, manifestBytes);
        ReviewedBlockStateProfileCatalog.Definition definition = definition(
                RESOURCE_ROOT, PROFILE_ID, CONTRACT, sha256(manifestBytes));

        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(resources), List.of(definition));

        assertTrue(catalog.reviewedProfiles().isEmpty());
        assertTrue(catalog.diagnostics().getFirst().detail().contains(
                "target-order evidence mismatch"));
    }

    @Test
    void ambiguousRuntimeOrderTableHashIsRejectedByExactV2Schema() throws Exception {
        Fixture fixture = fixture(RESOURCE_ROOT, PROFILE_ID, CONTRACT);
        Map<String, byte[]> resources = deepCopy(fixture.resources());
        String manifestResource = RESOURCE_ROOT + "block-state-map.properties";
        String manifest = new String(
                resources.get(manifestResource), StandardCharsets.ISO_8859_1)
                + "client-state-table.sha256=" + "d".repeat(64) + "\n";
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.ISO_8859_1);
        resources.put(manifestResource, manifestBytes);

        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(resources),
                        List.of(definition(
                                RESOURCE_ROOT,
                                PROFILE_ID,
                                CONTRACT,
                                sha256(manifestBytes))));

        assertTrue(catalog.reviewedProfiles().isEmpty());
        assertTrue(catalog.diagnostics().getFirst().detail().contains(
                "unexpected=[client-state-table.sha256]"));
    }

    @Test
    void descriptorSetCanonicalizationIsExactAndPinned() throws Exception {
        Fixture fixture = fixture(RESOURCE_ROOT, PROFILE_ID, CONTRACT);
        Map<String, byte[]> resources = deepCopy(fixture.resources());
        String manifestResource = RESOURCE_ROOT + "block-state-map.properties";
        String manifest = new String(
                resources.get(manifestResource), StandardCharsets.ISO_8859_1)
                .replace(
                        "client-state-descriptor-set.canonicalization="
                                + "strip-runtime-id-sort-utf8-lf-v1",
                        "client-state-descriptor-set.canonicalization=runtime-id-order");
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.ISO_8859_1);
        resources.put(manifestResource, manifestBytes);

        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(resources),
                        List.of(definition(
                                RESOURCE_ROOT,
                                PROFILE_ID,
                                CONTRACT,
                                sha256(manifestBytes))));

        assertTrue(catalog.reviewedProfiles().isEmpty());
        assertTrue(catalog.diagnostics().getFirst().detail().contains(
                "client-state-descriptor-set.canonicalization mismatch"));
    }

    @Test
    void absentDeclaredManifestIsQuarantinedWithoutPublishingProfile() {
        ReviewedBlockStateProfileCatalog.Definition definition = definition(
                RESOURCE_ROOT, PROFILE_ID, CONTRACT, "0".repeat(64));
        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(Map.of()), List.of(definition));

        assertTrue(catalog.reviewedProfiles().isEmpty());
        assertEquals(
                ReviewedBlockStateProfileCatalog.LoadFailure.MISSING_OR_UNREADABLE_RESOURCE,
                catalog.diagnostics().getFirst().failure());
    }

    @Test
    void absentDeclaredMapIsQuarantinedWithoutPublishingProfile() throws Exception {
        Fixture fixture = fixture(RESOURCE_ROOT, PROFILE_ID, CONTRACT);
        Map<String, byte[]> resources = new HashMap<>(fixture.resources());
        resources.remove(RESOURCE_ROOT + "block-state-map.bin");
        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(resources), List.of(fixture.definition()));

        assertTrue(catalog.reviewedProfiles().isEmpty());
        assertEquals(
                ReviewedBlockStateProfileCatalog.LoadFailure.MISSING_OR_UNREADABLE_RESOURCE,
                catalog.diagnostics().getFirst().failure());
    }

    @Test
    void corruptMapIsQuarantinedWithoutPublishingProfile() throws Exception {
        Fixture fixture = fixture(RESOURCE_ROOT, PROFILE_ID, CONTRACT);
        Map<String, byte[]> resources = deepCopy(fixture.resources());
        resources.get(RESOURCE_ROOT + "block-state-map.bin")[20] ^= 0x01;

        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(resources), List.of(fixture.definition()));

        assertTrue(catalog.reviewedProfiles().isEmpty());
        assertEquals(
                ReviewedBlockStateProfileCatalog.LoadFailure.INVALID_RESOURCE,
                catalog.diagnostics().getFirst().failure());
        assertTrue(catalog.diagnostics().getFirst().detail().contains("map hash mismatch"));
    }

    @Test
    void corruptManifestCannotRepinItsOwnReplacementMap() throws Exception {
        Fixture fixture = fixture(RESOURCE_ROOT, PROFILE_ID, CONTRACT);
        Map<String, byte[]> resources = deepCopy(fixture.resources());
        resources.get(RESOURCE_ROOT + "block-state-map.properties")[10] ^= 0x01;

        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(resources), List.of(fixture.definition()));

        assertTrue(catalog.reviewedProfiles().isEmpty());
        assertEquals(
                ReviewedBlockStateProfileCatalog.LoadFailure.INVALID_RESOURCE,
                catalog.diagnostics().getFirst().failure());
        assertTrue(catalog.diagnostics().getFirst().detail().contains("manifest hash mismatch"));
    }

    @Test
    void duplicateProtocolAndFullContractIdentityFailsClosed() throws Exception {
        Fixture first = fixture(RESOURCE_ROOT, PROFILE_ID, CONTRACT);
        Fixture second = fixture(
                "blockstate-profiles/duplicate/", "atm10-normal-8.1-duplicate", CONTRACT);
        Map<String, byte[]> resources = new HashMap<>(first.resources());
        resources.putAll(second.resources());

        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(resources),
                        List.of(first.definition(), second.definition()));

        assertTrue(catalog.reviewedProfiles().isEmpty());
        assertEquals(2, catalog.diagnostics().size());
        assertTrue(catalog.diagnostics().stream().allMatch(diagnostic ->
                diagnostic.failure()
                        == ReviewedBlockStateProfileCatalog.LoadFailure
                                .DUPLICATE_STRUCTURAL_IDENTITY));
    }

    @Test
    void corruptProfileIsQuarantinedWithoutSuppressingValidEnrichment() throws Exception {
        Fixture valid = fixture(RESOURCE_ROOT, PROFILE_ID, CONTRACT);
        Fixture corrupt = fixture(
                "blockstate-profiles/corrupt/",
                "atm10-normal-8.1-corrupt",
                "1".repeat(64));
        Map<String, byte[]> resources = deepCopy(valid.resources());
        resources.putAll(deepCopy(corrupt.resources()));
        resources.get("blockstate-profiles/corrupt/block-state-map.bin")[20] ^= 0x01;

        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(resources),
                        List.of(valid.definition(), corrupt.definition()));

        assertTrue(catalog.findStructuralEnrichment(767, CONTRACT).isPresent());
        assertTrue(catalog.findStructuralEnrichment(767, "1".repeat(64)).isEmpty());
        assertEquals(1, catalog.reviewedProfiles().size());
        assertEquals(1, catalog.diagnostics().size());
        assertEquals(
                ReviewedBlockStateProfileCatalog.LoadFailure.INVALID_RESOURCE,
                catalog.diagnostics().getFirst().failure());
    }

    @Test
    void malformedDefinitionNeverCreatesAFuzzyIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> definition(
                        RESOURCE_ROOT, PROFILE_ID, CONTRACT.toUpperCase(), "0".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReviewedBlockStateProfileCatalog.Definition(
                        RESOURCE_ROOT, PROFILE_ID, 766, CONTRACT, "0".repeat(64)));
    }

    private static ReviewedBlockStateProfileCatalog load(Fixture fixture) {
        return ReviewedBlockStateProfileCatalog.loadReviewed(
                new ResourceClassLoader(fixture.resources()), List.of(fixture.definition()));
    }

    private static ReviewedBlockStateProfileCatalog.Definition definition(
            String root,
            String profileId,
            String contract,
            String manifestSha256) {
        return new ReviewedBlockStateProfileCatalog.Definition(
                root, profileId, 767, contract, manifestSha256);
    }

    private static Fixture fixture(String root, String profileId, String contract)
            throws IOException, NoSuchAlgorithmException {
        return fixture(root, profileId, contract, true);
    }

    private static Fixture fixture(
            String root, String profileId, String contract, boolean strictlyIncreasing)
            throws IOException, NoSuchAlgorithmException {
        return fixture(root, profileId, contract, strictlyIncreasing, false);
    }

    private static Fixture fixture(
            String root,
            String profileId,
            String contract,
            boolean strictlyIncreasing,
            boolean duplicateTarget)
            throws IOException, NoSuchAlgorithmException {
        byte[] map = stateMap(strictlyIncreasing, duplicateTarget);
        String mapSha256 = sha256(map);
        String manifestText = String.join("\n",
                "format-version=2",
                "profile-id=" + profileId,
                "minecraft-version=1.21.1",
                "minecraft-protocol=767",
                "client-full-contract-sha256=" + contract,
                "vanilla-state-table.count=26684",
                "vanilla-state-table.sha256="
                        + "de9980fae71fecd0112d77cd1c056815dca3a1e1125289efd08b1c0696f499bb",
                "client-global-state.count=30000",
                "client-state-descriptor-set.count=30000",
                "client-state-descriptor-set.canonicalization="
                        + "strip-runtime-id-sort-utf8-lf-v1",
                "client-state-descriptor-set.sha256=" + "c".repeat(64),
                "map.file=block-state-map.bin",
                "map.bytes=" + map.length,
                "map.sha256=" + mapSha256,
                "map.count=26684",
                "map.maximum-target-id=26683",
                "source-global-palette.bits=15",
                "target-global-palette.bits=15",
                "mapping.target-ids-strictly-increasing=" + strictlyIncreasing,
                "rewrite-capabilities=BLOCK_UPDATE,CHUNK_BLOCK_STATES,BLOCK_LEVEL_EVENT,"
                        + "SECTION_BLOCKS_UPDATE",
                "packet.block-update=9",
                "packet.level-chunk-with-light=39",
                "packet.level-event=40",
                "packet.section-blocks-update=73",
                "");
        byte[] manifest = manifestText.getBytes(StandardCharsets.ISO_8859_1);
        Map<String, byte[]> resources = Map.of(
                root + "block-state-map.properties", manifest,
                root + "block-state-map.bin", map);
        return new Fixture(
                resources, definition(root, profileId, contract, sha256(manifest)));
    }

    private static byte[] stateMap(boolean strictlyIncreasing, boolean duplicateTarget)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(80_000);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(new byte[] {'P', 'O', 'B', 'S'});
            output.writeByte(1);
            output.writeInt(26_684);
            output.writeInt(30_000);
            for (int stateId = 0; stateId < 26_684; stateId++) {
                int targetId = stateId;
                if (duplicateTarget && stateId == 10) {
                    targetId = 11;
                } else if (!duplicateTarget && !strictlyIncreasing && stateId == 10) {
                    targetId = 11;
                } else if (!duplicateTarget && !strictlyIncreasing && stateId == 11) {
                    targetId = 10;
                }
                writeVarInt(output, targetId);
            }
        }
        return bytes.toByteArray();
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        int remaining = value;
        do {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            output.writeByte(remaining == 0 ? current : current | 0x80);
        } while (remaining != 0);
    }

    private static String sha256(byte[] value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static Map<String, byte[]> deepCopy(Map<String, byte[]> source) {
        HashMap<String, byte[]> copy = new HashMap<>();
        source.forEach((name, bytes) -> copy.put(name, bytes.clone()));
        return copy;
    }

    private record Fixture(
            Map<String, byte[]> resources,
            ReviewedBlockStateProfileCatalog.Definition definition) {
    }

    private static final class ResourceClassLoader extends ClassLoader {
        private final Map<String, byte[]> resources;

        private ResourceClassLoader(Map<String, byte[]> resources) {
            super(null);
            this.resources = Map.copyOf(resources);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] resource = resources.get(name);
            return resource == null ? null : new ByteArrayInputStream(resource.clone());
        }
    }
}
