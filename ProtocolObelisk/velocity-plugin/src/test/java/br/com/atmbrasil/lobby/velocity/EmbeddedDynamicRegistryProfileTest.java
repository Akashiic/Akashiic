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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class EmbeddedDynamicRegistryProfileTest {
    private static final String ROOT =
            SilentGearEmbeddedProfile.ATM10_NORMAL_7_3_RESOURCE_ROOT;
    private static final String ROOT_80 =
            SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT;

    @Test
    void reviewedNormal73DynamicRegistryBundleLoadsExactly() throws Exception {
        EmbeddedDynamicRegistryProfile profile =
                EmbeddedDynamicRegistryProfile.loadAtm10Normal73(defaultLoader());

        assertEquals(46, profile.registryCount());
        assertEquals(1_826, profile.totalEntries());
        assertEquals(577_165, profile.totalBytes());
        assertEquals(
                "69cbe809ad82bd56c45cf01d8f96a434c614736780360d42129bb3fbc927bb5f",
                profile.sequenceSha256());
        assertEquals(46, profile.packets().stream()
                .map(RegistryShimPacket::registryId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new))
                .size());

        RegistryShimPacket enchantments = profile.packets().stream()
                .filter(packet -> packet.registryId().equals("minecraft:enchantment"))
                .findFirst()
                .orElseThrow();
        assertEquals(93, enchantments.entryCount());
        assertEquals(58_821, enchantments.packetBytes());
        assertEquals(
                "5117d9faa34235df997088f16209b42e258f33133c83caaa100eaadf340b3a96",
                enchantments.sha256());

        RegistryShimPacket largest = profile.packets().stream()
                .max(java.util.Comparator.comparingInt(RegistryShimPacket::packetBytes))
                .orElseThrow();
        assertEquals("moonlight:soft_fluid", largest.registryId());
        assertEquals(455, largest.entryCount());
        assertEquals(204_247, largest.packetBytes());
        assertEquals(
                "afdf6a94982b80409ee4864ab69a43bd6eb7b5e40232acecb1f119f29b6d0640",
                largest.sha256());
    }

    @Test
    void reviewedNormal80DynamicRegistryBundleLoadsExactly() throws Exception {
        EmbeddedDynamicRegistryProfile profile =
                EmbeddedDynamicRegistryProfile.loadAtm10Normal80(defaultLoader());

        assertEquals(50, profile.registryCount());
        assertEquals(1_946, profile.totalEntries());
        assertEquals(651_622, profile.totalBytes());
        assertEquals(
                "e78c792986feed4fe861994cceb7ffb6dc24401feda7a600f272c6eef74a0935",
                profile.sequenceSha256());
        assertEquals(50, profile.packets().stream()
                .map(RegistryShimPacket::registryId)
                .distinct()
                .count());
        assertReviewedPacket(
                profile,
                "minecraft:dimension_type",
                16,
                7_027,
                "e09e9893ef2e9b8e8cc31e9372cfec90340646c047739bc276b87e5ee0df2a52");
        assertReviewedPacket(
                profile,
                "minecraft:enchantment",
                93,
                58_821,
                "5117d9faa34235df997088f16209b42e258f33133c83caaa100eaadf340b3a96");
        assertReviewedPacket(
                profile,
                "minecraft:wolf_variant",
                1,
                245,
                "72cc7cc3751a59ca8c45fac01f9c7a4915779f50dfb62d2a494c321582998a21");
        assertReviewedPacket(
                profile,
                "minecraft:worldgen/biome",
                234,
                99_087,
                "912377c52b2f317066b4e01023abc8190ac218ad093720086626f6cd363cc0a5");
    }

    @Test
    void packetBodiesRemainDefensivelyOwned() throws Exception {
        EmbeddedDynamicRegistryProfile profile =
                EmbeddedDynamicRegistryProfile.loadAtm10Normal73(defaultLoader());
        byte[] first = profile.packets().getFirst().packetBody();
        byte[] original = first.clone();
        first[first.length - 1] ^= 0x01;

        assertArrayEquals(original, profile.packets().getFirst().packetBody());
    }

    @Test
    void normalProfileExtensionReplacesRatherThanCombinesLegacyTtsShims()
            throws Exception {
        SilentGearEmbeddedProfile normal = SilentGearEmbeddedProfile.loadAtm10Normal73(
                defaultLoader(), 767, 1_048_576, 3_145_728);
        SilentGearEmbeddedProfile tts = SilentGearEmbeddedProfile.loadReviewed(
                defaultLoader(), 767, 1_048_576, 3_145_728);
        List<RegistryShimPacket> legacy = RegistryShimCatalog.resolve(
                RegistryShimCatalog.builtInAtmShimIds(), 1_048_576);
        Set<String> namespaces = Set.of("forbidden_arcanus", "ars_nouveau");

        List<RegistryShimPacket> selectedNormal = RegistryShimCatalog.selectForProfile(
                normal, legacy, namespaces, "0".repeat(64));
        List<RegistryShimPacket> selectedTts = RegistryShimCatalog.selectForProfile(
                tts, legacy, namespaces, "0".repeat(64));

        assertEquals(46, selectedNormal.size());
        assertEquals(46, selectedNormal.stream()
                .map(RegistryShimPacket::registryId)
                .distinct()
                .count());
        assertTrue(selectedNormal.stream().noneMatch(packet ->
                RegistryShimCatalog.builtInAtmShimIds().contains(packet.shimId())));
        assertEquals(2, selectedTts.size());
        assertTrue(selectedTts.stream().allMatch(packet ->
                RegistryShimCatalog.builtInAtmShimIds().contains(packet.shimId())));
    }

    @Test
    void omittedNbtOrCorruptedPacketFailsClosed() {
        ClassLoader omittedNbt = mutatingLoader(
                ROOT + "dynamic-registries.properties",
                bytes -> new String(bytes, StandardCharsets.ISO_8859_1)
                        .replace(
                                "registry.total-encoded-data-entries=1826",
                                "registry.total-encoded-data-entries=1825")
                        .getBytes(StandardCharsets.ISO_8859_1));
        ClassLoader corruptedPacket = mutatingLoader(
                ROOT + "dynamic-registries/moonlight_soft_fluid.bin",
                bytes -> {
                    bytes[bytes.length - 1] ^= 0x01;
                    return bytes;
                });

        IllegalArgumentException omitted = assertThrows(
                IllegalArgumentException.class,
                () -> EmbeddedDynamicRegistryProfile.loadAtm10Normal73(omittedNbt));
        assertTrue(omitted.getMessage().contains("omits entry data"));
        IllegalArgumentException corrupted = assertThrows(
                IllegalArgumentException.class,
                () -> EmbeddedDynamicRegistryProfile.loadAtm10Normal73(corruptedPacket));
        assertTrue(corrupted.getMessage().contains("hash mismatch"));
    }

    @Test
    void normal80KnownPackEvidenceMutationsFailClosed() {
        ClassLoader countMutation = manifestMutation(
                "known-pack-entry.count=313", "known-pack-entry.count=312");
        ClassLoader omittedCountMutation = manifestMutation(
                "omitted-known-pack-entry.count=18", "omitted-known-pack-entry.count=17");
        ClassLoader omittedOrderMutation = manifestMutation(
                "omitted-known-pack-entry.0.entry=minecraft:the_end",
                "omitted-known-pack-entry.0.entry=minecraft:channeling");
        ClassLoader omittedHashMutation = manifestMutation(
                "b9dd6e9c6e783f8a2773dda779e0aea965204e2334e490041c794f5f59a3cab8",
                "0".repeat(64));
        ClassLoader unexpectedEvidence = manifestMutation(
                "registry.count=50",
                "omitted-known-pack-entry.18.entry=minecraft:unexpected\nregistry.count=50");

        for (ClassLoader mutation : List.of(
                countMutation,
                omittedCountMutation,
                omittedOrderMutation,
                omittedHashMutation,
                unexpectedEvidence)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> EmbeddedDynamicRegistryProfile.loadAtm10Normal80(mutation));
        }
    }

    @Test
    void missingManifestFailsClosed() {
        ClassLoader missing = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        };

        assertThrows(
                IOException.class,
                () -> EmbeddedDynamicRegistryProfile.loadAtm10Normal73(missing));
    }

    private static ClassLoader defaultLoader() {
        return EmbeddedDynamicRegistryProfileTest.class.getClassLoader();
    }

    private static void assertReviewedPacket(
            EmbeddedDynamicRegistryProfile profile,
            String registryId,
            int entries,
            int bytes,
            String sha256) {
        RegistryShimPacket packet = profile.packets().stream()
                .filter(candidate -> candidate.registryId().equals(registryId))
                .findFirst()
                .orElseThrow();
        assertEquals(entries, packet.entryCount());
        assertEquals(bytes, packet.packetBytes());
        assertEquals(sha256, packet.sha256());
    }

    private static ClassLoader manifestMutation(String target, String replacement) {
        return mutatingLoader(
                ROOT_80 + "dynamic-registries.properties",
                bytes -> new String(bytes, StandardCharsets.ISO_8859_1)
                        .replace(target, replacement)
                        .getBytes(StandardCharsets.ISO_8859_1));
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
