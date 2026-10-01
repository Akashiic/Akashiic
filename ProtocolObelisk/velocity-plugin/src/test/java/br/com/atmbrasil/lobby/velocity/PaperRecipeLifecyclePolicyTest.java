package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PaperRecipeLifecyclePolicyTest {
    @Test
    void unavailableRuntimeResourcesReportBoundedActionableRequirements() {
        PaperRecipeLifecyclePolicy.Evaluation evaluation = PaperRecipeLifecyclePolicy.decide(
                validExactContext(), Optional.empty(), Optional.empty(), Optional.empty());
        assertFalse(evaluation.release());
        assertEquals(PaperRecipeLifecyclePolicy.Basis.NONE, evaluation.basis());
        assertEquals(List.of(
                PaperRecipeLifecyclePolicy.Requirement.EXACT_SERVER_CONFIG_CATALOG_RESOURCE,
                PaperRecipeLifecyclePolicy.Requirement.EXACT_ENCHANTMENT_RESOURCE,
                PaperRecipeLifecyclePolicy.Requirement.EXACT_NEOVITAE_CLOSURE_RESOURCE),
                evaluation.unsatisfiedRequirements());
    }

    @Test
    void satisfiedRuntimeEvidenceLeavesNoUnsatisfiedDiagnosticRequirements() {
        PaperRecipeLifecyclePolicy.Evaluation evaluation = decideExact(validExactContext());
        assertTrue(evaluation.release());
        assertTrue(evaluation.unsatisfiedRequirements().isEmpty());
    }

    @Test
    void adaptiveApothicSessionReleasesAfterItsReviewedBootstrapWasSent() {
        assertEquals(
                PaperRecipeLifecyclePolicy.Decision.RELEASE,
                PaperRecipeLifecyclePolicy.decide(
                        true,
                        true,
                        Set.of(ApothicEnchantingBootstrapPayload.REQUIRED_NAMESPACE),
                        Set.of(ApothicEnchantingBootstrapPayload.CHANNEL_ID)));
    }

    @Test
    void unknownApothicCodecWithholdsOnlyTheSyntheticRecipeUpdate() {
        assertEquals(
                PaperRecipeLifecyclePolicy.Decision
                        .WITHHOLD_UNSATISFIED_APOTHIC_DEPENDENCY,
                PaperRecipeLifecyclePolicy.decide(
                        true,
                        true,
                        Set.of(ApothicEnchantingBootstrapPayload.REQUIRED_NAMESPACE),
                        Set.of()));
    }

    @Test
    void structuralClientsWithoutApothicPreserveExistingLifecycle() {
        assertEquals(
                PaperRecipeLifecyclePolicy.Decision.RELEASE,
                PaperRecipeLifecyclePolicy.decide(
                        true, true, Set.of("minecraft", "voicechat"), Set.of()));
        assertEquals(
                PaperRecipeLifecyclePolicy.Decision
                        .WITHHOLD_UNREVIEWED_STRUCTURAL_PROFILE,
                PaperRecipeLifecyclePolicy.decide(
                        true, false, Set.of("minecraft", "evilcraft"), Set.of()));
    }

    @Test
    void lifecycleCannotRaceAheadOfSelectedBootstraps() {
        assertThrows(IllegalStateException.class, () ->
                PaperRecipeLifecyclePolicy.decide(false, false, Set.of(), Set.of()));
    }

    @Test
    void exactAtm10Normal81RuntimeCatalogReleasesWithoutAStructuralProfile() {
        PaperRecipeLifecyclePolicy.Evaluation evaluation = decideExact(validExactContext());

        assertTrue(evaluation.release());
        assertEquals(
                PaperRecipeLifecyclePolicy.Basis.EXACT_ATM10_81_RUNTIME_CATALOG,
                evaluation.basis());
        assertEquals(PaperRecipeLifecyclePolicy.Decision.RELEASE, evaluation.decision());
    }

    @Test
    void oldTwoFileClosureAndSameSetInWrongOrderCannotReleaseRecipes() {
        Atm10Normal81ServerConfigCatalog.Catalog catalog = exactCatalog();
        assertNoExact(context(
                true,
                PaperRecipeLifecyclePolicy.ATM10_81_FULL_CLIENT_CONTRACT_SHA256,
                List.of("create_hypertube-server.toml", "justdirethings-server.toml"),
                catalog.id(),
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes(),
                exactRegistryReceipts(),
                Set.of("minecraft", "irons_spellbooks"),
                Set.of()), Optional.of(catalog));

        ArrayList<String> reversed = new ArrayList<>(catalog.fileNames());
        Collections.reverse(reversed);
        assertNoExact(context(
                true,
                PaperRecipeLifecyclePolicy.ATM10_81_FULL_CLIENT_CONTRACT_SHA256,
                reversed,
                catalog.id(),
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes(),
                exactRegistryReceipts(),
                Set.of("minecraft", "irons_spellbooks"),
                Set.of()), Optional.of(catalog));
    }

    @Test
    void everyCatalogIdentityAndWriteProofIsIndependentlyRequired() {
        Atm10Normal81ServerConfigCatalog.Catalog catalog = exactCatalog();
        List<String> names = catalog.fileNames();
        Set<RegistryShimReceipt> receipts = exactRegistryReceipts();
        Set<String> namespaces = Set.of("minecraft", "irons_spellbooks");

        assertNoExact(context(
                false,
                PaperRecipeLifecyclePolicy.ATM10_81_FULL_CLIENT_CONTRACT_SHA256,
                names,
                catalog.id(),
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes(),
                receipts,
                namespaces,
                Set.of()), Optional.of(catalog));
        assertNoExact(context(
                true,
                "0".repeat(64),
                names,
                catalog.id(),
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes(),
                receipts,
                namespaces,
                Set.of()), Optional.of(catalog));
        assertNoExact(context(
                true,
                PaperRecipeLifecyclePolicy.ATM10_81_FULL_CLIENT_CONTRACT_SHA256,
                names,
                "wrong-runtime-catalog",
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes(),
                receipts,
                namespaces,
                Set.of()), Optional.of(catalog));
        assertNoExact(context(
                true,
                PaperRecipeLifecyclePolicy.ATM10_81_FULL_CLIENT_CONTRACT_SHA256,
                names,
                catalog.id(),
                "1".repeat(64),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes(),
                receipts,
                namespaces,
                Set.of()), Optional.of(catalog));
        assertNoExact(context(
                true,
                PaperRecipeLifecyclePolicy.ATM10_81_FULL_CLIENT_CONTRACT_SHA256,
                names,
                catalog.id(),
                catalog.nameSequenceSha256(),
                "2".repeat(64),
                catalog.totalEncodedBytes(),
                receipts,
                namespaces,
                Set.of()), Optional.of(catalog));
        assertNoExact(context(
                true,
                PaperRecipeLifecyclePolicy.ATM10_81_FULL_CLIENT_CONTRACT_SHA256,
                names,
                catalog.id(),
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes() - 1,
                receipts,
                namespaces,
                Set.of()), Optional.of(catalog));
        assertNoExact(validExactContext(), Optional.empty());
    }

    @Test
    void everyRegistryReceiptIsIndependentlyRequired() {
        Atm10Normal81ServerConfigCatalog.Catalog catalog = exactCatalog();
        assertNoExact(validExactContext(Set.of()), Optional.of(catalog));
        assertNoExact(validExactContext(Set.of(exactIronsReceipt())), Optional.of(catalog));
        assertNoExact(
                validExactContext(Set.of(exactFullEnchantmentReceipt())),
                Optional.of(catalog));
        assertNoExact(
                validExactContext(Set.of(
                        exactFullEnchantmentReceipt(), exactIronsReceipt())),
                Optional.of(catalog));
    }

    @Test
    void reviewedBlockStateCannotBypassExactCatalogOrRegistryReceipts() {
        PaperRecipeLifecyclePolicy.Context exact = validExactContext(Set.of(exactIronsReceipt()));
        PaperRecipeLifecyclePolicy.Context structural = new PaperRecipeLifecyclePolicy.Context(
                exact.allSelectedPlayBootstrapsSent(),
                true,
                exact.configurationPrefixWriteComplete(),
                exact.fullClientContractSha256(),
                exact.advertisedNamespaces(),
                exact.sentClientboundBootstrapChannelIds(),
                exact.transmittedServerConfigFileNames(),
                exact.serverConfigCatalogId(),
                exact.serverConfigNameSequenceSha256(),
                exact.serverConfigPayloadSequenceSha256(),
                exact.serverConfigEncodedBytes(),
                exact.registryShimReceipts());

        PaperRecipeLifecyclePolicy.Evaluation withheld =
                PaperRecipeLifecyclePolicy.decide(
                        structural, Optional.empty(), Optional.of(exactCatalog()));
        assertFalse(withheld.release());
        assertEquals(PaperRecipeLifecyclePolicy.Basis.NONE, withheld.basis());
    }

    @Test
    void narrowAtm10Normal82EnchantmentMergeCannotReleaseAtm10Normal81Recipes() {
        RegistryShimReceipt narrowMergeReceipt = RegistryShimReceipt.from(
                Atm10Normal82GiselleEnchantmentExtension.packet(65_536));
        assertNoExact(
                validExactContext(Set.of(
                        narrowMergeReceipt, exactIronsReceipt(), exactNeoVitaeSentientReceipt())),
                Optional.of(exactCatalog()));

        PaperRecipeLifecyclePolicy.Evaluation evaluation = PaperRecipeLifecyclePolicy.decide(
                validExactContext(Set.of(
                        narrowMergeReceipt, exactIronsReceipt(), exactNeoVitaeSentientReceipt())),
                Optional.of(exactFullEnchantmentReceipt()),
                Optional.of(exactNeoVitaeSentientReceipt()),
                Optional.of(exactCatalog()));
        assertFalse(evaluation.release());
        assertTrue(evaluation.unsatisfiedRequirements().contains(
                PaperRecipeLifecyclePolicy.Requirement.EXACT_ENCHANTMENT_RECEIPT));
    }

    @Test
    void forgedOrCollidingReceiptsCannotReleaseRecipes() {
        RegistryShimReceipt irons = exactIronsReceipt();
        RegistryShimReceipt wrongIronDigest = new RegistryShimReceipt(
                irons.shimId(),
                irons.registryId(),
                irons.entryCount(),
                irons.packetBytes(),
                "0".repeat(64));
        assertNoExact(
                validExactContext(Set.of(
                        exactFullEnchantmentReceipt(), wrongIronDigest)),
                Optional.of(exactCatalog()));

        RegistryShimReceipt enchantment = exactFullEnchantmentReceipt();
        RegistryShimReceipt registryCollision = new RegistryShimReceipt(
                "example-collision",
                enchantment.registryId(),
                enchantment.entryCount(),
                enchantment.packetBytes(),
                enchantment.sha256());
        assertNoExact(
                validExactContext(Set.of(irons, enchantment, registryCollision)),
                Optional.of(exactCatalog()));
    }

    @Test
    void apothicDependencyStillGatesTheExactRuntimeCatalog() {
        PaperRecipeLifecyclePolicy.Context missingBootstrap = exactContext(
                exactRegistryReceipts(),
                Set.of(
                        "irons_spellbooks",
                        ApothicEnchantingBootstrapPayload.REQUIRED_NAMESPACE),
                Set.of());
        PaperRecipeLifecyclePolicy.Evaluation withheld = decideExact(missingBootstrap);
        assertFalse(withheld.release());
        assertEquals(
                PaperRecipeLifecyclePolicy.Basis.EXACT_ATM10_81_RUNTIME_CATALOG,
                withheld.basis());
        assertEquals(
                PaperRecipeLifecyclePolicy.Decision
                        .WITHHOLD_UNSATISFIED_APOTHIC_DEPENDENCY,
                withheld.decision());

        PaperRecipeLifecyclePolicy.Evaluation released = decideExact(exactContext(
                exactRegistryReceipts(),
                Set.of(
                        "irons_spellbooks",
                        ApothicEnchantingBootstrapPayload.REQUIRED_NAMESPACE),
                Set.of(ApothicEnchantingBootstrapPayload.CHANNEL_ID)));
        assertTrue(released.release());
    }

    @Test
    void receiptIsDerivedFromExactBytesAndContextRejectsDuplicateConfigs() {
        RegistryShimPacket packet = Atm10Normal81IronsSpellbooksRegistry.packet(65_536);
        assertEquals(exactIronsReceipt(), RegistryShimReceipt.from(packet));

        byte[] tamperedBody = packet.packetBody();
        tamperedBody[tamperedBody.length - 1] ^= 0x01;
        RegistryShimPacket tampered = new RegistryShimPacket(
                packet.shimId(),
                packet.requiredNamespace(),
                packet.registryId(),
                packet.entryCount(),
                tamperedBody,
                packet.sha256());
        assertThrows(IllegalArgumentException.class, () -> RegistryShimReceipt.from(tampered));
        assertFalse(Arrays.equals(packet.packetBody(), tamperedBody));

        Atm10Normal81ServerConfigCatalog.Catalog catalog = exactCatalog();
        assertThrows(IllegalArgumentException.class, () -> context(
                true,
                PaperRecipeLifecyclePolicy.ATM10_81_FULL_CLIENT_CONTRACT_SHA256,
                List.of("same-server.toml", "same-server.toml"),
                catalog.id(),
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes(),
                exactRegistryReceipts(),
                Set.of("minecraft"),
                Set.of()));
    }

    private static Atm10Normal81ServerConfigCatalog.Catalog exactCatalog() {
        return Atm10Normal81ServerConfigCatalog.catalog();
    }

    private static PaperRecipeLifecyclePolicy.Context validExactContext() {
        return validExactContext(exactRegistryReceipts());
    }

    private static PaperRecipeLifecyclePolicy.Context validExactContext(
            Set<RegistryShimReceipt> receipts) {
        return exactContext(
                receipts,
                Set.of("minecraft", "irons_spellbooks"),
                Set.of());
    }

    private static PaperRecipeLifecyclePolicy.Context exactContext(
            Set<RegistryShimReceipt> receipts,
            Set<String> advertisedNamespaces,
            Set<String> sentBootstrapChannels) {
        Atm10Normal81ServerConfigCatalog.Catalog catalog = exactCatalog();
        return context(
                true,
                PaperRecipeLifecyclePolicy.ATM10_81_FULL_CLIENT_CONTRACT_SHA256,
                catalog.fileNames(),
                catalog.id(),
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes(),
                receipts,
                advertisedNamespaces,
                sentBootstrapChannels);
    }

    private static PaperRecipeLifecyclePolicy.Context context(
            boolean prefixWriteComplete,
            String fullContract,
            List<String> configs,
            String catalogId,
            String nameSequenceSha256,
            String payloadSequenceSha256,
            int encodedBytes,
            Set<RegistryShimReceipt> receipts,
            Set<String> advertisedNamespaces,
            Set<String> sentBootstrapChannels) {
        return new PaperRecipeLifecyclePolicy.Context(
                true,
                false,
                prefixWriteComplete,
                fullContract,
                advertisedNamespaces,
                sentBootstrapChannels,
                configs,
                catalogId,
                nameSequenceSha256,
                payloadSequenceSha256,
                encodedBytes,
                receipts);
    }

    private static RegistryShimReceipt exactIronsReceipt() {
        return RegistryShimReceipt.from(
                Atm10Normal81IronsSpellbooksRegistry.packet(65_536));
    }

    private static RegistryShimReceipt exactFullEnchantmentReceipt() {
        return new RegistryShimReceipt(
                Atm10Normal81EnchantmentRegistry.SHIM_ID,
                Atm10Normal81EnchantmentRegistry.REGISTRY_ID,
                3,
                1,
                "2".repeat(64));
    }

    private static RegistryShimReceipt exactNeoVitaeSentientReceipt() {
        return Atm10Normal81NeoVitaeSentientClosure.expectedReceiptIfPresent()
                .orElseThrow();
    }

    private static Set<RegistryShimReceipt> exactRegistryReceipts() {
        return Set.of(
                exactFullEnchantmentReceipt(),
                exactIronsReceipt(),
                exactNeoVitaeSentientReceipt());
    }

    private static PaperRecipeLifecyclePolicy.Evaluation decideExact(
            PaperRecipeLifecyclePolicy.Context context) {
        return PaperRecipeLifecyclePolicy.decide(
                context,
                Optional.of(exactFullEnchantmentReceipt()),
                Optional.of(exactNeoVitaeSentientReceipt()),
                Optional.of(exactCatalog()));
    }

    private static void assertNoExact(
            PaperRecipeLifecyclePolicy.Context context,
            Optional<Atm10Normal81ServerConfigCatalog.Catalog> catalog) {
        PaperRecipeLifecyclePolicy.Evaluation evaluation =
                PaperRecipeLifecyclePolicy.decide(
                        context,
                        Optional.of(exactFullEnchantmentReceipt()),
                        Optional.of(exactNeoVitaeSentientReceipt()),
                        catalog);
        assertFalse(evaluation.release());
        assertEquals(PaperRecipeLifecyclePolicy.Basis.NONE, evaluation.basis());
        assertEquals(
                PaperRecipeLifecyclePolicy.Decision
                        .WITHHOLD_UNREVIEWED_STRUCTURAL_PROFILE,
                evaluation.decision());
    }
}
