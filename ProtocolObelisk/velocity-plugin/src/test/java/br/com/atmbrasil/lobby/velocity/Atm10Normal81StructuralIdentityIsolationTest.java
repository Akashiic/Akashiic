package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cardinal regression fence around the exact ATM10 Normal 8.1 structural identity. */
final class Atm10Normal81StructuralIdentityIsolationTest {
    private static final String ATM10_NORMAL_80_FULL_CONTRACT =
            ReviewedClientContractEvidence.ATM10_NORMAL_8_0.fullClientContractSha256();
    private static final String UNKNOWN_FULL_CONTRACT = "0".repeat(64);
    private static final String RESOURCE_ROOT = "test/atm10-normal-8.1/";

    @Test
    void exactContractIsStructuralEvidenceOnlyForProtocol767() {
        assertEquals(767, Atm10Normal81Contract.MINECRAFT_PROTOCOL);
        assertEquals(
                "9d06683c97b68f2bf5093c15d35a95ab14e8dbde67c6c9e65509cdaebed607a3",
                Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256);
        assertTrue(Atm10Normal81Contract.matchesStructuralIdentity(
                767, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256));

        assertAll(
                () -> assertFalse(Atm10Normal81Contract.matchesStructuralIdentity(
                        766, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256)),
                () -> assertFalse(Atm10Normal81Contract.matchesStructuralIdentity(
                        767, ATM10_NORMAL_80_FULL_CONTRACT)),
                () -> assertFalse(Atm10Normal81Contract.matchesStructuralIdentity(
                        767, UNKNOWN_FULL_CONTRACT)),
                () -> assertFalse(Atm10Normal81Contract.matchesStructuralIdentity(767, null)));
        assertFalse(ATM10_NORMAL_80_FULL_CONTRACT.equals(
                Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256));
    }

    @Test
    void blockStateLookupIsKeyedOnlyByProtocolAndFullContractSha() {
        List<java.lang.reflect.Method> lookupMethods = java.util.Arrays.stream(
                        ReviewedBlockStateProfileCatalog.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("findStructuralEnrichment"))
                .toList();
        assertEquals(1, lookupMethods.size());
        assertArrayEquals(
                new Class<?>[] {int.class, String.class},
                lookupMethods.getFirst().getParameterTypes());

        ReviewedBlockStateProfileCatalog empty = ReviewedBlockStateProfileCatalog.empty();
        assertAll(
                () -> assertTrue(empty.findStructuralEnrichment(
                        767, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256).isEmpty()),
                () -> assertTrue(empty.findStructuralEnrichment(
                        767, ATM10_NORMAL_80_FULL_CONTRACT).isEmpty()),
                () -> assertTrue(empty.findStructuralEnrichment(
                        767, UNKNOWN_FULL_CONTRACT).isEmpty()),
                () -> assertTrue(empty.findStructuralEnrichment(767, "malformed").isEmpty()),
                () -> assertTrue(empty.findStructuralEnrichment(
                        767,
                        Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256.toUpperCase(
                                java.util.Locale.ROOT)).isEmpty()),
                () -> assertTrue(empty.findStructuralEnrichment(
                        766, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256).isEmpty()),
                () -> assertTrue(empty.findStructuralEnrichment(767, null).isEmpty()));
        assertTrue(empty.reviewedProfiles().isEmpty());
    }

    @Test
    void packaged81MapLoadsOnlyForItsExactProtocolAndFullContract() {
        ReviewedBlockStateProfileCatalog.Definition packaged =
                new ReviewedBlockStateProfileCatalog.Definition(
                        "blockstate-profiles/atm10-normal-8.1-neoforge-21.1.249/",
                        Atm10Normal81Contract.ID,
                        Atm10Normal81Contract.MINECRAFT_PROTOCOL,
                        Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256,
                        "394756c142fda290e530318a306bb0c68d108825060b5f02b94c13f2c7418600");
        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        Atm10Normal81StructuralIdentityIsolationTest.class.getClassLoader(),
                        List.of(packaged));

        assertTrue(catalog.diagnostics().isEmpty());
        assertEquals(1, catalog.reviewedProfiles().size());
        BlockStateTranslationProfile exact = catalog.findStructuralEnrichment(
                        767, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256)
                .orElseThrow();
        assertEquals(Atm10Normal81Contract.ID, exact.profileId());
        assertEquals(26_684, exact.sourceStateCount());
        assertEquals(1_543_003, exact.clientGlobalStateCount());
        assertEquals(
                "fddcfc54b25d4986b5e3189b33cb9c1585eb4c651919a3da122757c5447320c3",
                exact.mapSha256());

        assertAll(
                () -> assertTrue(catalog.findStructuralEnrichment(
                        766, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256).isEmpty()),
                () -> assertTrue(catalog.findStructuralEnrichment(
                        767, ATM10_NORMAL_80_FULL_CONTRACT).isEmpty()),
                () -> assertTrue(catalog.findStructuralEnrichment(
                        767, UNKNOWN_FULL_CONTRACT).isEmpty()),
                () -> assertTrue(catalog.findStructuralEnrichment(
                        767,
                        Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256.toUpperCase(
                                java.util.Locale.ROOT)).isEmpty()));
    }

    @Test
    void missingOrCorruptEvidenceCannotPublishAPartialCatalog() throws Exception {
        ReviewedBlockStateProfileCatalog.Definition missing = definition("a".repeat(64));
        ReviewedBlockStateProfileCatalog missingCatalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(null), List.of(missing));
        assertTrue(missingCatalog.reviewedProfiles().isEmpty());
        assertEquals(1, missingCatalog.diagnostics().size());
        assertEquals(
                ReviewedBlockStateProfileCatalog.LoadFailure
                        .MISSING_OR_UNREADABLE_RESOURCE,
                missingCatalog.diagnostics().getFirst().failure());

        byte[] corruptManifest = "format-version=1\n".getBytes(StandardCharsets.UTF_8);
        ReviewedBlockStateProfileCatalog.Definition corrupt = definition(
                sha256(corruptManifest));
        ReviewedBlockStateProfileCatalog corruptCatalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        new ResourceClassLoader(corruptManifest), List.of(corrupt));
        assertTrue(corruptCatalog.reviewedProfiles().isEmpty());
        assertEquals(1, corruptCatalog.diagnostics().size());
        assertEquals(
                ReviewedBlockStateProfileCatalog.LoadFailure.INVALID_RESOURCE,
                corruptCatalog.diagnostics().getFirst().failure());

        // Failure to load enrichment never manufactures a fallback profile.
        assertTrue(missingCatalog.findStructuralEnrichment(
                767, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256).isEmpty());
        assertTrue(corruptCatalog.findStructuralEnrichment(
                767, Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256).isEmpty());
    }

    @Test
    void definitionsRejectEveryNonCanonicalStructuralKey() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ReviewedBlockStateProfileCatalog.Definition(
                                RESOURCE_ROOT,
                                "atm10-normal-8.1",
                                766,
                                Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256,
                                "a".repeat(64))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ReviewedBlockStateProfileCatalog.Definition(
                                RESOURCE_ROOT,
                                "atm10-normal-8.1",
                                767,
                                "invalid",
                                "a".repeat(64))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ReviewedBlockStateProfileCatalog.Definition(
                                RESOURCE_ROOT,
                                "atm10-normal-8.1",
                                767,
                                Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256,
                                "A".repeat(64))));
    }

    private static ReviewedBlockStateProfileCatalog.Definition definition(
            String manifestSha256) {
        return new ReviewedBlockStateProfileCatalog.Definition(
                RESOURCE_ROOT,
                "atm10-normal-8.1",
                767,
                Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256,
                manifestSha256);
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class ResourceClassLoader extends ClassLoader {
        private final byte[] manifest;

        private ResourceClassLoader(byte[] manifest) {
            super(null);
            this.manifest = manifest == null ? null : manifest.clone();
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (manifest == null
                    || !name.equals(RESOURCE_ROOT + "block-state-map.properties")) {
                return null;
            }
            return new ByteArrayInputStream(manifest);
        }
    }
}
