package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeConfigPath;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact-evidence policy for the synthetic Paper recipe lifecycle. */
final class PaperRecipeLifecyclePolicy {
    static final String ATM10_81_FULL_CLIENT_CONTRACT_SHA256 =
            Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CATALOG_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private PaperRecipeLifecyclePolicy() {
    }

    /**
     * Evaluates exact runtime evidence and preserves its basis for audit logging.
     *
     * <p>Admission is never denied here. ATM10 8.1 can release the one Paper recipe update only
     * after the complete, ordered 288-payload SERVER-config catalog, every required exact
     * registry write, the paired NeoVitae tag closure and every selected PLAY bootstrap have
     * completed. If structural resources are absent or quarantined, only that recipe update
     * remains withheld.</p>
     */
    static Evaluation decide(Context context) {
        return decide(
                context,
                Atm10Normal81EnchantmentRegistry.expectedReceiptIfPresent(),
                Atm10Normal81NeoVitaeSentientClosure.expectedReceiptIfPresent(),
                Atm10Normal81ServerConfigCatalog.runtimeResolution().catalog());
    }

    /** Test seam for an isolated enchantment receipt and the packaged exact catalog. */
    static Evaluation decide(
            Context context,
            Optional<RegistryShimReceipt> exactFullEnchantmentReceipt) {
        return decide(
                context,
                exactFullEnchantmentReceipt,
                Atm10Normal81NeoVitaeSentientClosure.expectedReceiptIfPresent(),
                Atm10Normal81ServerConfigCatalog.runtimeResolution().catalog());
    }

    /** Test seam for independently isolated, fully validated exact resources. */
    static Evaluation decide(
            Context context,
            Optional<RegistryShimReceipt> exactFullEnchantmentReceipt,
            Optional<Atm10Normal81ServerConfigCatalog.Catalog> exactServerConfigCatalog) {
        return decide(
                context,
                exactFullEnchantmentReceipt,
                Atm10Normal81NeoVitaeSentientClosure.expectedReceiptIfPresent(),
                exactServerConfigCatalog);
    }

    /** Test seam for independently isolated, fully validated exact resources. */
    static Evaluation decide(
            Context context,
            Optional<RegistryShimReceipt> exactFullEnchantmentReceipt,
            Optional<RegistryShimReceipt> exactNeoVitaeSentientReceipt,
            Optional<Atm10Normal81ServerConfigCatalog.Catalog> exactServerConfigCatalog) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(
                exactFullEnchantmentReceipt, "exactFullEnchantmentReceipt");
        Objects.requireNonNull(
                exactNeoVitaeSentientReceipt, "exactNeoVitaeSentientReceipt");
        Objects.requireNonNull(exactServerConfigCatalog, "exactServerConfigCatalog");
        if (!context.allSelectedPlayBootstrapsSent()) {
            throw new IllegalStateException(
                    "recipe lifecycle cannot be decided before PLAY bootstraps are sent");
        }

        boolean apothicAdvertised = context.advertisedNamespaces().contains(
                ApothicEnchantingBootstrapPayload.REQUIRED_NAMESPACE);
        boolean apothicBootstrapSent = context.sentClientboundBootstrapChannelIds().contains(
                ApothicEnchantingBootstrapPayload.CHANNEL_ID);
        if (apothicBootstrapSent && !apothicAdvertised) {
            throw new IllegalStateException(
                    "Apothic bootstrap was selected without an advertised namespace");
        }

        List<Requirement> unsatisfied = unsatisfiedStructuralRequirements(
                context,
                exactFullEnchantmentReceipt,
                exactNeoVitaeSentientReceipt,
                exactServerConfigCatalog);
        if (!unsatisfied.isEmpty()) {
            return new Evaluation(
                    Basis.NONE, Decision.WITHHOLD_UNREVIEWED_STRUCTURAL_PROFILE, unsatisfied);
        }
        Basis basis = ATM10_81_FULL_CLIENT_CONTRACT_SHA256.equals(
                context.fullClientContractSha256())
                ? Basis.EXACT_ATM10_81_RUNTIME_CATALOG : Basis.REVIEWED_STRUCTURAL_PROFILE;
        if (apothicAdvertised && !apothicBootstrapSent) {
            return new Evaluation(
                    basis, Decision.WITHHOLD_UNSATISFIED_APOTHIC_DEPENDENCY,
                    List.of(Requirement.APOTHIC_ENCHANTMENT_INFO_BOOTSTRAP));
        }
        return new Evaluation(basis, Decision.RELEASE, List.of());
    }

    /** Bounded diagnostic facts derived from the same checks that control recipe release. */
    private static List<Requirement> unsatisfiedStructuralRequirements(
            Context context,
            Optional<RegistryShimReceipt> exactFullEnchantmentReceipt,
            Optional<RegistryShimReceipt> exactNeoVitaeSentientReceipt,
            Optional<Atm10Normal81ServerConfigCatalog.Catalog> exactServerConfigCatalog) {
        if (ATM10_81_FULL_CLIENT_CONTRACT_SHA256.equals(
                context.fullClientContractSha256())) {
            List<Requirement> missing = new ArrayList<>();
            if (!context.configurationPrefixWriteComplete()) {
                missing.add(Requirement.CONFIGURATION_PREFIX_WRITE);
            }
            if (exactServerConfigCatalog.isEmpty()) {
                missing.add(Requirement.EXACT_SERVER_CONFIG_CATALOG_RESOURCE);
            } else if (!hasExactServerConfigCatalog(context, exactServerConfigCatalog)) {
                missing.add(Requirement.EXACT_SERVER_CONFIG_CATALOG_DELIVERY);
            }
            if (exactFullEnchantmentReceipt.isEmpty()) {
                missing.add(Requirement.EXACT_ENCHANTMENT_RESOURCE);
            } else if (!hasExactFullEnchantmentReceipt(
                    context.registryShimReceipts(), exactFullEnchantmentReceipt)) {
                missing.add(Requirement.EXACT_ENCHANTMENT_RECEIPT);
            }
            if (!hasExactIronsSpellbooksReceipt(context.registryShimReceipts())) {
                missing.add(Requirement.EXACT_IRONS_SPELLBOOKS_RECEIPT);
            }
            if (exactNeoVitaeSentientReceipt.isEmpty()) {
                missing.add(Requirement.EXACT_NEOVITAE_CLOSURE_RESOURCE);
            } else if (!hasExactNeoVitaeSentientReceipt(
                    context.registryShimReceipts(), exactNeoVitaeSentientReceipt)) {
                missing.add(Requirement.EXACT_NEOVITAE_CLOSURE_RECEIPT);
            }
            return List.copyOf(missing);
        }
        return context.structuralProfileReviewed()
                ? List.of() : List.of(Requirement.REVIEWED_STRUCTURAL_EVIDENCE);
    }

    private static boolean hasExactServerConfigCatalog(
            Context context,
            Optional<Atm10Normal81ServerConfigCatalog.Catalog> exactServerConfigCatalog) {
        if (exactServerConfigCatalog.isEmpty()) {
            return false;
        }
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                exactServerConfigCatalog.orElseThrow();
        return context.serverConfigCatalogId().equals(catalog.id())
                && context.serverConfigNameSequenceSha256().equals(
                        catalog.nameSequenceSha256())
                && context.serverConfigPayloadSequenceSha256().equals(
                        catalog.payloadSequenceSha256())
                && context.serverConfigEncodedBytes() == catalog.totalEncodedBytes()
                && context.transmittedServerConfigFileNames().equals(catalog.fileNames());
    }

    private static boolean hasExactFullEnchantmentReceipt(
            Set<RegistryShimReceipt> receipts,
            Optional<RegistryShimReceipt> exactFullEnchantmentReceipt) {
        if (exactFullEnchantmentReceipt.isEmpty()) {
            return false;
        }
        List<RegistryShimReceipt> relevantReceipts = receipts.stream()
                .filter(receipt -> receipt.shimId().equals(
                                Atm10Normal81EnchantmentRegistry.SHIM_ID)
                        || receipt.registryId().equals(
                                Atm10Normal81EnchantmentRegistry.REGISTRY_ID))
                .toList();
        return relevantReceipts.size() == 1
                && relevantReceipts.getFirst().equals(
                        exactFullEnchantmentReceipt.orElseThrow());
    }

    private static boolean hasExactIronsSpellbooksReceipt(
            Set<RegistryShimReceipt> receipts) {
        List<RegistryShimReceipt> relevantReceipts = receipts.stream()
                .filter(receipt -> receipt.shimId().equals(
                                Atm10Normal81IronsSpellbooksRegistry.SHIM_ID)
                        || receipt.registryId().equals(
                                Atm10Normal81IronsSpellbooksRegistry.REGISTRY_ID))
                .toList();
        if (relevantReceipts.size() != 1) {
            return false;
        }
        RegistryShimReceipt receipt = relevantReceipts.getFirst();
        return receipt.shimId().equals(Atm10Normal81IronsSpellbooksRegistry.SHIM_ID)
                && receipt.registryId().equals(
                        Atm10Normal81IronsSpellbooksRegistry.REGISTRY_ID)
                && receipt.entryCount()
                        == Atm10Normal81IronsSpellbooksRegistry.ENTRY_COUNT
                && receipt.packetBytes()
                        == Atm10Normal81IronsSpellbooksRegistry.PACKET_BYTES
                && receipt.sha256().equals(
                        Atm10Normal81IronsSpellbooksRegistry.PACKET_SHA256);
    }

    private static boolean hasExactNeoVitaeSentientReceipt(
            Set<RegistryShimReceipt> receipts,
            Optional<RegistryShimReceipt> exactNeoVitaeSentientReceipt) {
        if (exactNeoVitaeSentientReceipt.isEmpty()) {
            return false;
        }
        List<RegistryShimReceipt> relevantReceipts = receipts.stream()
                .filter(receipt -> receipt.shimId().equals(
                                Atm10Normal81NeoVitaeSentientClosure.SHIM_ID)
                        || receipt.registryId().equals(
                                Atm10Normal81NeoVitaeSentientClosure.REGISTRY_ID))
                .toList();
        return relevantReceipts.size() == 1
                && relevantReceipts.getFirst().equals(
                        exactNeoVitaeSentientReceipt.orElseThrow());
    }

    /** Compatibility seam for callers that predate exact write receipts. */
    static Decision decide(
            boolean allSelectedPlayBootstrapsSent,
            boolean structuralProfileReviewed,
            Set<String> advertisedNamespaces,
            Set<String> sentClientboundBootstrapChannelIds) {
        return decide(new Context(
                allSelectedPlayBootstrapsSent,
                structuralProfileReviewed,
                false,
                "",
                advertisedNamespaces,
                sentClientboundBootstrapChannelIds,
                List.of(),
                TransientServerConfigPlanner.NO_REVIEWED_CATALOG,
                "",
                "",
                0,
                Set.of())).decision();
    }

    record Context(
            boolean allSelectedPlayBootstrapsSent,
            boolean structuralProfileReviewed,
            boolean configurationPrefixWriteComplete,
            String fullClientContractSha256,
            Set<String> advertisedNamespaces,
            Set<String> sentClientboundBootstrapChannelIds,
            List<String> transmittedServerConfigFileNames,
            String serverConfigCatalogId,
            String serverConfigNameSequenceSha256,
            String serverConfigPayloadSequenceSha256,
            int serverConfigEncodedBytes,
            Set<RegistryShimReceipt> registryShimReceipts) {
        Context {
            Objects.requireNonNull(fullClientContractSha256, "fullClientContractSha256");
            if (!fullClientContractSha256.isEmpty()
                    && !SHA256.matcher(fullClientContractSha256).matches()) {
                throw new IllegalArgumentException("full client contract SHA-256 is invalid");
            }
            advertisedNamespaces = Set.copyOf(
                    Objects.requireNonNull(advertisedNamespaces, "advertisedNamespaces"));
            sentClientboundBootstrapChannelIds = Set.copyOf(Objects.requireNonNull(
                    sentClientboundBootstrapChannelIds,
                    "sentClientboundBootstrapChannelIds"));
            transmittedServerConfigFileNames = List.copyOf(Objects.requireNonNull(
                    transmittedServerConfigFileNames,
                    "transmittedServerConfigFileNames"));
            LinkedHashSet<String> uniqueNames = new LinkedHashSet<>();
            for (String fileName : transmittedServerConfigFileNames) {
                if (!NeoForgeConfigPath.isValid(fileName) || !uniqueNames.add(fileName)) {
                    throw new IllegalArgumentException(
                            "transmitted SERVER-config filenames are invalid or duplicated");
                }
            }
            serverConfigCatalogId = Objects.requireNonNull(
                    serverConfigCatalogId, "serverConfigCatalogId");
            serverConfigNameSequenceSha256 = Objects.requireNonNull(
                    serverConfigNameSequenceSha256,
                    "serverConfigNameSequenceSha256");
            serverConfigPayloadSequenceSha256 = Objects.requireNonNull(
                    serverConfigPayloadSequenceSha256,
                    "serverConfigPayloadSequenceSha256");
            if (!CATALOG_ID.matcher(serverConfigCatalogId).matches()
                    || serverConfigEncodedBytes < 0) {
                throw new IllegalArgumentException("SERVER-config catalog identity is invalid");
            }
            boolean catalogAbsent = serverConfigCatalogId.equals(
                    TransientServerConfigPlanner.NO_REVIEWED_CATALOG);
            if (catalogAbsent
                    && (!serverConfigNameSequenceSha256.isEmpty()
                            || !serverConfigPayloadSequenceSha256.isEmpty()
                            || serverConfigEncodedBytes != 0)) {
                throw new IllegalArgumentException(
                        "absent SERVER-config catalog cannot carry exact evidence");
            }
            if (!catalogAbsent
                    && (!SHA256.matcher(serverConfigNameSequenceSha256).matches()
                            || !SHA256.matcher(serverConfigPayloadSequenceSha256).matches()
                            || serverConfigEncodedBytes == 0)) {
                throw new IllegalArgumentException(
                        "selected SERVER-config catalog evidence is incomplete");
            }
            registryShimReceipts = Set.copyOf(
                    Objects.requireNonNull(registryShimReceipts, "registryShimReceipts"));
        }
    }

    record Evaluation(Basis basis, Decision decision, List<Requirement> unsatisfiedRequirements) {
        Evaluation {
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(decision, "decision");
            unsatisfiedRequirements = List.copyOf(Objects.requireNonNull(
                    unsatisfiedRequirements, "unsatisfiedRequirements"));
            if (decision.release() && basis == Basis.NONE) {
                throw new IllegalArgumentException("recipe lifecycle release requires a basis");
            }
            if (decision.release() != unsatisfiedRequirements.isEmpty()) {
                throw new IllegalArgumentException(
                        "recipe lifecycle decision must match its unsatisfied requirements");
            }
        }

        boolean release() {
            return decision.release();
        }

        String reason() {
            return decision.reason();
        }
    }

    enum Basis {
        REVIEWED_STRUCTURAL_PROFILE,
        EXACT_ATM10_81_RUNTIME_CATALOG,
        NONE
    }

    enum Requirement {
        REVIEWED_STRUCTURAL_EVIDENCE,
        CONFIGURATION_PREFIX_WRITE,
        EXACT_SERVER_CONFIG_CATALOG_RESOURCE,
        EXACT_SERVER_CONFIG_CATALOG_DELIVERY,
        EXACT_ENCHANTMENT_RESOURCE,
        EXACT_ENCHANTMENT_RECEIPT,
        EXACT_IRONS_SPELLBOOKS_RECEIPT,
        EXACT_NEOVITAE_CLOSURE_RESOURCE,
        EXACT_NEOVITAE_CLOSURE_RECEIPT,
        APOTHIC_ENCHANTMENT_INFO_BOOTSTRAP
    }

    enum Decision {
        RELEASE(true, "all advertised recipe-time dependencies were satisfied"),
        WITHHOLD_UNREVIEWED_STRUCTURAL_PROFILE(
                false, "UNREVIEWED_STRUCTURAL_PROFILE_RECIPE_SAFETY"),
        WITHHOLD_UNSATISFIED_APOTHIC_DEPENDENCY(
                false, "UNSATISFIED_APOTHIC_ENCHANTMENT_INFO_BOOTSTRAP");

        private final boolean release;
        private final String reason;

        Decision(boolean release, String reason) {
            this.release = release;
            this.reason = reason;
        }

        boolean release() {
            return release;
        }

        String reason() {
            return reason;
        }
    }
}
