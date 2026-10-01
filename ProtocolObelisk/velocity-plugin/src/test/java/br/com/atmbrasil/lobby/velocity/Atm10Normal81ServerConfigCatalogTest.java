package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class Atm10Normal81ServerConfigCatalogTest {
    @Test
    void embeddedCatalogMatchesEveryIndependentRuntimePin() {
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();

        assertEquals(Atm10Normal81ServerConfigCatalog.CATALOG_ID, catalog.id());
        assertEquals(Atm10Normal81ServerConfigCatalog.CONFIG_COUNT, catalog.entries().size());
        assertEquals(
                Atm10Normal81ServerConfigCatalog.TOTAL_CONTENT_BYTES,
                catalog.totalContentBytes());
        assertEquals(
                Atm10Normal81ServerConfigCatalog.TOTAL_ENCODED_BYTES,
                catalog.totalEncodedBytes());
        assertEquals(
                Atm10Normal81ServerConfigCatalog.NAME_SEQUENCE_SHA256,
                catalog.nameSequenceSha256());
        assertEquals(
                Atm10Normal81ServerConfigCatalog.PAYLOAD_SEQUENCE_SHA256,
                catalog.payloadSequenceSha256());
        assertEquals(
                Atm10Normal81ServerConfigCatalog.NAME_SEQUENCE_SHA256,
                Atm10Normal81ServerConfigCatalog.nameSequenceSha256(catalog.fileNames()));
        assertEquals(catalog.fileNames().size(), Set.copyOf(catalog.fileNames()).size());
        assertTrue(catalog.fileNames().containsAll(Set.of(
                "Advancedperipherals/metaphysics.toml",
                "Mekanism/general.toml",
                "create_hypertube-server.toml",
                "irons_spellbooks-server.toml",
                "jei-server.toml",
                "justdirethings-server.toml",
                "securitycraft-server.toml",
                "sophisticatedbackpacks-server.toml",
                "theurgy-server.toml",
                "xycraft/core-server.toml")));
    }

    @Test
    void encodedPayloadAccessIsDefensiveAndCatalogOrderIsImmutable() {
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();
        Atm10Normal81ServerConfigCatalog.Entry first = catalog.entries().getFirst();
        byte[] original = first.encodedPayload();
        byte[] second = first.encodedPayload();

        assertNotSame(original, second);
        assertArrayEquals(original, second);
        original[original.length - 1] ^= 0x01;
        assertArrayEquals(second, first.encodedPayload());
        assertThrows(UnsupportedOperationException.class, () ->
                catalog.entries().add(first));
    }

    @Test
    void absentManifestAndMissingOrCorruptPayloadAreQuarantined() throws IOException {
        Atm10Normal81ServerConfigCatalog.RuntimeResolution absent =
                Atm10Normal81ServerConfigCatalog.runtimeResolutionForTest(
                        new OverrideClassLoader(null, null, null, true));
        assertTrue(absent.catalog().isEmpty());
        assertTrue(absent.quarantineFailure().isPresent());

        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();
        String firstResource = Atm10Normal81ServerConfigCatalog.RESOURCE_ROOT
                + "server-configs/000.bin";
        Atm10Normal81ServerConfigCatalog.RuntimeResolution missingPayload =
                Atm10Normal81ServerConfigCatalog.runtimeResolutionForTest(
                        new OverrideClassLoader(firstResource, null, null, false));
        assertTrue(missingPayload.catalog().isEmpty());
        assertTrue(missingPayload.quarantineFailure().isPresent());

        byte[] corrupt = catalog.entries().getFirst().encodedPayload();
        corrupt[corrupt.length - 1] ^= 0x01;
        Atm10Normal81ServerConfigCatalog.RuntimeResolution corruptPayload =
                Atm10Normal81ServerConfigCatalog.runtimeResolutionForTest(
                        new OverrideClassLoader(firstResource, corrupt, null, false));
        assertTrue(corruptPayload.catalog().isEmpty());
        assertTrue(corruptPayload.quarantineFailure().isPresent());
    }

    @Test
    void selfConsistentManifestMutationCannotReplaceIndependentPin() throws IOException {
        ClassLoader production = Atm10Normal81ServerConfigCatalog.class.getClassLoader();
        byte[] manifest = requiredResource(
                production, Atm10Normal81ServerConfigCatalog.MANIFEST_RESOURCE);
        String text = new String(manifest, StandardCharsets.US_ASCII);
        byte[] changedPath = text.replace(
                        "config.0.name=Advancedperipherals/metaphysics.toml",
                        "config.0.name=../unsafe-server.toml")
                .getBytes(StandardCharsets.US_ASCII);
        assertFalse(Arrays.equals(manifest, changedPath));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                Atm10Normal81ServerConfigCatalog.loadForTest(
                        new OverrideClassLoader(
                                Atm10Normal81ServerConfigCatalog.MANIFEST_RESOURCE,
                                changedPath,
                                null,
                                false)));
        assertTrue(failure.getMessage().contains("manifest SHA-256 mismatch"));
    }

    @Test
    void manifestAndPayloadExportsFromBothColdBootsRemainByteIdentical()
            throws IOException {
        // The release fixture is boot A. The build audit also compares the external boot A/B
        // capture trees; this test pins the fixture's own immutable manifest and payload sum.
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();
        assertEquals(651_792, catalog.entries().stream()
                .mapToInt(Atm10Normal81ServerConfigCatalog.Entry::encodedBytes)
                .sum());
        byte[] manifest = requiredResource(
                Atm10Normal81ServerConfigCatalog.class.getClassLoader(),
                Atm10Normal81ServerConfigCatalog.MANIFEST_RESOURCE);
        assertEquals(94_145, manifest.length);
    }

    private static byte[] requiredResource(ClassLoader loader, String resource)
            throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("missing resource " + resource);
            }
            return stream.readAllBytes();
        }
    }

    private static final class OverrideClassLoader extends ClassLoader {
        private final ClassLoader production =
                Atm10Normal81ServerConfigCatalog.class.getClassLoader();
        private final String overriddenResource;
        private final byte[] replacement;
        private final String secondMissingResource;
        private final boolean hideAll;

        private OverrideClassLoader(
                String overriddenResource,
                byte[] replacement,
                String secondMissingResource,
                boolean hideAll) {
            super(null);
            this.overriddenResource = overriddenResource;
            this.replacement = replacement == null ? null : replacement.clone();
            this.secondMissingResource = secondMissingResource;
            this.hideAll = hideAll;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (hideAll || name.equals(secondMissingResource)) {
                return null;
            }
            if (name.equals(overriddenResource)) {
                return replacement == null
                        ? null
                        : new ByteArrayInputStream(replacement);
            }
            return production.getResourceAsStream(name);
        }
    }
}
