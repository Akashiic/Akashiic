package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class Atm10Normal81NeoVitaeSentientClosureTest {
    private static final String REGISTRY_RESOURCE =
            "configuration-profiles/atm10-normal-8.1-neoforge-21.1.249/"
                    + "dynamic-registries/neovitae_sentient_upgrades.bin";
    private static final String TAG_RESOURCE =
            "configuration-profiles/atm10-normal-8.1-neoforge-21.1.249/"
                    + "dynamic-registry-tags/dynamic-registry-tags.bin";

    @Test
    void exactRegistryAndCrashSentinelTagsLoadAsOneClosure() throws Exception {
        Atm10Normal81NeoVitaeSentientClosure.Closure closure =
                Atm10Normal81NeoVitaeSentientClosure.loadForTest(
                        defaultLoader(), 65_536);

        RegistryShimPacket packet = closure.packet();
        assertEquals("neovitae-sentient-atm10-8.1", packet.shimId());
        assertEquals("neovitae:sentient_upgrades", packet.registryId());
        assertEquals(44, packet.entryCount());
        assertEquals(7_229, packet.packetBytes());
        assertEquals(
                "da417ff57a7a822ac29f843fbd9c41d15cdffbea150f560a2772e54a683b8d76",
                packet.sha256());

        EmbeddedRegistryTagsProfile tags = closure.tags();
        assertEquals(packet.registryId(), tags.registryId());
        assertEquals(6, tags.tags().size());
        assertEquals(101, tags.totalMembers());
        assertEquals(267, tags.packetBytes());
        assertEquals(
                "a9e49f656de774d49518902fda08e846f93eae1abc2cde919b93bfbc778d5195",
                tags.sha256());
        EmbeddedRegistryTagsProfile.TagEntry sentinel = tags.tags().stream()
                .filter(tag -> tag.tagId().equals("neovitae:sentient_start"))
                .findFirst()
                .orElseThrow();
        assertEquals(15, sentinel.memberIds().length);
        assertTrue(java.util.Arrays.stream(sentinel.memberIds())
                .allMatch(member -> member >= 0 && member < packet.entryCount()));
    }

    @Test
    void registryOrTagCorruptionQuarantinesTheWholeClosure() {
        ClassLoader corruptRegistry = mutatingLoader(REGISTRY_RESOURCE, bytes -> {
            bytes[bytes.length - 1] ^= 0x01;
            return bytes;
        });
        ClassLoader corruptTags = mutatingLoader(TAG_RESOURCE, bytes -> {
            bytes[bytes.length - 1] ^= 0x01;
            return bytes;
        });

        for (ClassLoader loader : List.of(corruptRegistry, corruptTags, missingLoader())) {
            Atm10Normal81NeoVitaeSentientClosure.RuntimeResolution resolution =
                    Atm10Normal81NeoVitaeSentientClosure.runtimeResolutionForTest(
                            loader, 65_536);
            assertTrue(resolution.closure().isEmpty());
            assertTrue(resolution.quarantineFailure().isPresent());
        }
        Atm10Normal81NeoVitaeSentientClosure.RuntimeResolution tightBudget =
                Atm10Normal81NeoVitaeSentientClosure.runtimeResolutionForTest(
                        defaultLoader(), 7_228);
        assertTrue(tightBudget.closure().isEmpty());
        assertTrue(tightBudget.quarantineFailure().isPresent());
    }

    @Test
    void exactTransactionAloneCanAcquireItsPairedTags() throws Exception {
        RegistryShimPacket packet = Atm10Normal81NeoVitaeSentientClosure
                .loadForTest(defaultLoader(), 65_536)
                .packet();
        EmbeddedRegistryTagsProfile exact = RegistryShimCatalog.selectTagsForTransaction(
                767,
                null,
                List.of(packet),
                Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256);
        assertFalse(exact.isEmpty());

        assertTrue(RegistryShimCatalog.selectTagsForTransaction(
                767, null, List.of(packet), "0".repeat(64)).isEmpty());
        RegistryShimPacket forged = new RegistryShimPacket(
                packet.shimId(),
                packet.requiredNamespace(),
                packet.registryId(),
                packet.entryCount(),
                packet.packetBody(),
                "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () ->
                RegistryShimCatalog.selectTagsForTransaction(
                        767,
                        null,
                        List.of(forged),
                        Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256));
    }

    @Test
    void exactContractSelectsTheClosureWithoutTreatingNamespaceAsAcl() throws Exception {
        RegistryShimPacket packet = Atm10Normal81NeoVitaeSentientClosure
                .loadForTest(defaultLoader(), 65_536)
                .packet();
        assertEquals(
                List.of(packet),
                RegistryShimCatalog.selectForProfile(
                        767,
                        null,
                        List.of(packet),
                        Set.of("minecraft"),
                        Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256));
        assertTrue(RegistryShimCatalog.selectForProfile(
                767,
                null,
                List.of(packet),
                Set.of("neovitae"),
                "0".repeat(64)).isEmpty());
    }

    private static ClassLoader defaultLoader() {
        return Atm10Normal81NeoVitaeSentientClosureTest.class.getClassLoader();
    }

    private static ClassLoader missingLoader() {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        };
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
