package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RegistryShimCatalogTest {
    private static final String EXACT_ATM10_NORMAL_81_CONTRACT =
            Atm10Normal81IronsSpellbooksRegistry.FULL_CLIENT_CONTRACT_SHA256;

    @Test
    void adaptiveNamespaceAdvertisementAloneCannotAuthorizeAnyLegacyShim() {
        List<RegistryShimPacket> legacy = legacyPackets();
        Set<String> everyOwningNamespace = Set.of(
                "forbidden_arcanus", "ars_nouveau", "ad_astra", "irons_spellbooks");

        assertTrue(RegistryShimCatalog.selectForProfile(
                null, legacy, everyOwningNamespace, null).isEmpty());
        assertTrue(RegistryShimCatalog.selectForProfile(
                null, legacy, everyOwningNamespace, "0".repeat(64)).isEmpty());
    }

    @Test
    void exactAtm10Normal81ContractAuthorizesOnlyApplicableLegacyShims() {
        List<RegistryShimPacket> legacy = candidatesWithFullEnchantment();

        List<RegistryShimPacket> selected = RegistryShimCatalog.selectForProfile(
                767,
                null,
                legacy,
                Set.of("minecraft", "ars_nouveau", "irons_spellbooks"),
                EXACT_ATM10_NORMAL_81_CONTRACT);

        assertEquals(
                List.of(
                        RegistryShimCatalog.IRONS_SPELLBOOKS_ATM10_8_1,
                        RegistryShimCatalog.NEOVITAE_SENTIENT_ATM10_8_1),
                selected.stream().map(RegistryShimPacket::shimId).toList());
        assertTrue(selected.stream().noneMatch(packet ->
                packet.registryId().equals("minecraft:enchantment")));
    }

    @Test
    void fullEnchantmentIsSelectedOnlyAsAnExactPaperRegistryReplacement() {
        List<RegistryShimPacket> candidates = candidatesWithFullEnchantment();

        assertEquals(
                RegistryShimCatalog.FULL_ENCHANTMENT_ATM10_8_1,
                RegistryShimCatalog.selectPaperRegistryReplacement(
                                767, candidates, EXACT_ATM10_NORMAL_81_CONTRACT)
                        .orElseThrow()
                        .shimId());
        assertTrue(RegistryShimCatalog.selectPaperRegistryReplacement(
                768, candidates, EXACT_ATM10_NORMAL_81_CONTRACT).isEmpty());
        assertTrue(RegistryShimCatalog.selectPaperRegistryReplacement(
                767, candidates, "0".repeat(64)).isEmpty());
        assertTrue(RegistryShimCatalog.selectPaperRegistryReplacement(
                767, legacyPackets(), EXACT_ATM10_NORMAL_81_CONTRACT).isEmpty());
    }

    @Test
    void duplicateRegistryIdsAreRejectedAfterSelection() {
        RegistryShimPacket full = syntheticFullEnchantmentPacket();
        RegistryShimPacket collision = packet(
                "collision",
                "minecraft",
                Atm10Normal81EnchantmentRegistry.REGISTRY_ID);
        assertThrows(IllegalArgumentException.class, () ->
                RegistryShimCatalog.selectPaperRegistryReplacement(
                        767,
                        List.of(full, collision),
                        EXACT_ATM10_NORMAL_81_CONTRACT));

        RegistryShimPacket forbiddenCollision = packet(
                "forbidden-collision",
                "forbidden_arcanus",
                ForbiddenArcanusItemModifierRegistry.REGISTRY_ID);
        ArrayList<RegistryShimPacket> tailCandidates = new ArrayList<>(legacyPackets());
        tailCandidates.add(forbiddenCollision);
        assertThrows(IllegalArgumentException.class, () ->
                RegistryShimCatalog.selectForProfile(
                        767,
                        null,
                        tailCandidates,
                        Set.of("forbidden_arcanus"),
                        EXACT_ATM10_NORMAL_81_CONTRACT));
    }

    @Test
    void exactStructuralProfileWithoutDynamicRegistriesMayNarrowLegacyShims()
            throws Exception {
        SilentGearEmbeddedProfile exactTtsProfile = SilentGearEmbeddedProfile.loadReviewed(
                RegistryShimCatalogTest.class.getClassLoader(),
                767,
                1_048_576,
                3_145_728);

        List<RegistryShimPacket> selected = RegistryShimCatalog.selectForProfile(
                exactTtsProfile,
                legacyPackets(),
                Set.of("ars_nouveau"),
                "0".repeat(64));

        assertEquals(
                List.of(RegistryShimCatalog.ARS_NOUVEAU_5_11_3),
                selected.stream().map(RegistryShimPacket::shimId).toList());
    }

    @Test
    void exactStructuralProfileDynamicTransactionReplacesAllLegacyShims()
            throws Exception {
        SilentGearEmbeddedProfile exactNormal73Profile =
                SilentGearEmbeddedProfile.loadAtm10Normal73(
                        RegistryShimCatalogTest.class.getClassLoader(),
                        767,
                        1_048_576,
                        3_145_728);

        List<RegistryShimPacket> selected = RegistryShimCatalog.selectForProfile(
                exactNormal73Profile,
                legacyPackets(),
                Set.of("forbidden_arcanus", "ars_nouveau", "ad_astra", "irons_spellbooks"),
                EXACT_ATM10_NORMAL_81_CONTRACT);

        assertSame(exactNormal73Profile.dynamicRegistries().packets(), selected);
        assertTrue(selected.stream().noneMatch(packet ->
                RegistryShimCatalog.builtInAtmShimIds().contains(packet.shimId())));
    }

    private static List<RegistryShimPacket> legacyPackets() {
        return RegistryShimCatalog.resolve(
                RegistryShimCatalog.builtInAtmShimIds().stream()
                        .filter(id -> !id.equals(
                                RegistryShimCatalog.FULL_ENCHANTMENT_ATM10_8_1))
                        .toList(),
                65_536);
    }

    private static List<RegistryShimPacket> candidatesWithFullEnchantment() {
        ArrayList<RegistryShimPacket> candidates = new ArrayList<>(legacyPackets());
        candidates.add(syntheticFullEnchantmentPacket());
        return List.copyOf(candidates);
    }

    private static RegistryShimPacket syntheticFullEnchantmentPacket() {
        return packet(
                Atm10Normal81EnchantmentRegistry.SHIM_ID,
                Atm10Normal81EnchantmentRegistry.REQUIRED_NAMESPACE,
                Atm10Normal81EnchantmentRegistry.REGISTRY_ID);
    }

    private static RegistryShimPacket packet(
            String shimId, String requiredNamespace, String registryId) {
        byte[] body = {1};
        return new RegistryShimPacket(
                shimId,
                requiredNamespace,
                registryId,
                1,
                body,
                sha256(body));
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
