package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeConfigPath;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Builds the bounded NeoForge {@code SERVER} config transaction sent to a Paper lobby client. */
final class TransientServerConfigPlanner {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    static final String NO_REVIEWED_CATALOG = "none";

    private TransientServerConfigPlanner() {
    }

    /**
     * Uses the complete, authenticated SERVER config set reported by an exact backend contract.
     *
     * <p>No baseline or namespace-derived candidate is merged into this list. A Silent Gear
     * profile is not part of SERVER-config identity.</p>
     */
    static Plan exact(List<String> agentConfigs) {
        Objects.requireNonNull(agentConfigs, "agentConfigs");
        LinkedHashSet<String> effective = new LinkedHashSet<>();
        for (String fileName : agentConfigs) {
            requireConfigFileName(fileName, "agentConfigs");
            if (!effective.add(fileName)) {
                throw new IllegalArgumentException(
                        "agentConfigs contains a duplicate filename " + fileName);
            }
        }
        if (effective.isEmpty()) {
            throw new IllegalArgumentException("agentConfigs must not be empty");
        }
        return withoutCatalog(List.copyOf(effective), 0, 0, 0);
    }

    static Plan plan(
            List<String> baselineConfigs,
            List<String> advertisedNamespaces,
            boolean deriveFromClientChannels,
            int maximumDerivedConfigs) {
        Objects.requireNonNull(baselineConfigs, "baselineConfigs");
        Objects.requireNonNull(advertisedNamespaces, "advertisedNamespaces");
        if (maximumDerivedConfigs < 0) {
            throw new IllegalArgumentException("maximumDerivedConfigs must not be negative");
        }

        LinkedHashSet<String> effective = new LinkedHashSet<>(baselineConfigs);
        if (effective.size() != baselineConfigs.size()) {
            throw new IllegalArgumentException("baselineConfigs contains a duplicate filename");
        }
        for (String baseline : effective) {
            requireConfigFileName(baseline, "baselineConfigs");
        }
        if (!deriveFromClientChannels) {
            return withoutCatalog(List.copyOf(effective), 0, 0, 0);
        }

        int derived = 0;
        int omitted = 0;
        int ignored = 0;
        TreeSet<String> namespaces = new TreeSet<>();
        for (String namespace : advertisedNamespaces) {
            if (namespace == null) {
                ignored++;
            } else {
                namespaces.add(namespace);
            }
        }
        for (String namespace : namespaces) {
            if (!NAMESPACE.matcher(namespace).matches()) {
                ignored++;
                continue;
            }
            String candidate = namespace + "-server.toml";
            if (!isConfigFileName(candidate) || effective.contains(candidate)) {
                if (!isConfigFileName(candidate)) {
                    ignored++;
                }
                continue;
            }
            if (derived >= maximumDerivedConfigs) {
                omitted++;
                continue;
            }
            effective.add(candidate);
            derived++;
        }
        return withoutCatalog(List.copyOf(effective), derived, omitted, ignored);
    }

    /**
     * Replaces guesses with the reproducible 288-payload ATM10 Normal 8.1 runtime catalog.
     *
     * <p>The Minecraft protocol, complete client contract and independently validated embedded
     * catalog must all match. The catalog replaces, rather than extends, baseline/derived names:
     * NeoForge indexes synchronized SERVER configs by exact case-sensitive relative filename.
     * Missing or quarantined catalog evidence returns the base plan unchanged and therefore never
     * affects admission.</p>
     */
    static Plan withReviewedContractCatalog(
            Plan base,
            int minecraftProtocol,
            String fullClientContractSha256,
            Optional<Atm10Normal81ServerConfigCatalog.Catalog> reviewedCatalog) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(reviewedCatalog, "reviewedCatalog");
        if (minecraftProtocol != Atm10Normal81ServerConfigCatalog.PROTOCOL_VERSION
                || !Atm10Normal81ServerConfigCatalog.FULL_CLIENT_CONTRACT_SHA256.equals(
                        fullClientContractSha256)
                || reviewedCatalog.isEmpty()) {
            return base;
        }

        Atm10Normal81ServerConfigCatalog.Catalog catalog = reviewedCatalog.orElseThrow();
        if (base.reviewedCatalogApplied()) {
            if (!base.reviewedCatalogId().equals(catalog.id())
                    || !base.configs().equals(catalog.fileNames())) {
                throw new IllegalArgumentException(
                        "a different reviewed SERVER-config catalog is already applied");
            }
            return base;
        }

        LinkedHashSet<String> baseNames = new LinkedHashSet<>(base.configs());
        int retained = 0;
        for (String fileName : catalog.fileNames()) {
            requireConfigFileName(fileName, "reviewed ATM10 Normal 8.1 catalog");
            if (baseNames.contains(fileName)) {
                retained++;
            }
        }
        int added = catalog.entries().size() - retained;
        int discarded = base.configs().size() - retained;
        return new Plan(
                catalog.fileNames(),
                base.derivedConfigCount(),
                base.omittedDerivedConfigCount(),
                base.ignoredNamespaceCount(),
                catalog.id(),
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.entries().size(),
                added,
                retained,
                discarded,
                catalog.totalEncodedBytes());
    }

    private static Plan withoutCatalog(
            List<String> configs,
            int derivedConfigCount,
            int omittedDerivedConfigCount,
            int ignoredNamespaceCount) {
        return new Plan(
                configs,
                derivedConfigCount,
                omittedDerivedConfigCount,
                ignoredNamespaceCount,
                NO_REVIEWED_CATALOG,
                "",
                "",
                0,
                0,
                0,
                0,
                0);
    }

    private static void requireConfigFileName(String fileName, String source) {
        if (!isConfigFileName(fileName)) {
            throw new IllegalArgumentException(source + " contains invalid filename " + fileName);
        }
    }

    private static boolean isConfigFileName(String fileName) {
        return NeoForgeConfigPath.isValid(fileName);
    }

    record Plan(
            List<String> configs,
            int derivedConfigCount,
            int omittedDerivedConfigCount,
            int ignoredNamespaceCount,
            String reviewedCatalogId,
            String reviewedCatalogNameSequenceSha256,
            String reviewedCatalogPayloadSequenceSha256,
            int reviewedCatalogCandidateCount,
            int addedReviewedCatalogConfigCount,
            int retainedReviewedCatalogConfigCount,
            int discardedBaseConfigCount,
            int reviewedCatalogEncodedBytes) {
        Plan {
            configs = List.copyOf(Objects.requireNonNull(configs, "configs"));
            reviewedCatalogId = Objects.requireNonNull(
                    reviewedCatalogId, "reviewedCatalogId");
            reviewedCatalogNameSequenceSha256 = Objects.requireNonNull(
                    reviewedCatalogNameSequenceSha256,
                    "reviewedCatalogNameSequenceSha256");
            reviewedCatalogPayloadSequenceSha256 = Objects.requireNonNull(
                    reviewedCatalogPayloadSequenceSha256,
                    "reviewedCatalogPayloadSequenceSha256");
            if (derivedConfigCount < 0
                    || omittedDerivedConfigCount < 0
                    || ignoredNamespaceCount < 0
                    || reviewedCatalogCandidateCount < 0
                    || addedReviewedCatalogConfigCount < 0
                    || retainedReviewedCatalogConfigCount < 0
                    || discardedBaseConfigCount < 0
                    || reviewedCatalogEncodedBytes < 0) {
                throw new IllegalArgumentException("planner counters must not be negative");
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String config : configs) {
                requireConfigFileName(config, "configs");
                if (!unique.add(config)) {
                    throw new IllegalArgumentException(
                            "configs contains a duplicate filename " + config);
                }
            }

            boolean catalogApplied = !reviewedCatalogId.equals(NO_REVIEWED_CATALOG);
            if (!catalogApplied
                    && (!reviewedCatalogNameSequenceSha256.isEmpty()
                            || !reviewedCatalogPayloadSequenceSha256.isEmpty()
                            || reviewedCatalogCandidateCount != 0
                            || addedReviewedCatalogConfigCount != 0
                            || retainedReviewedCatalogConfigCount != 0
                            || discardedBaseConfigCount != 0
                            || reviewedCatalogEncodedBytes != 0)) {
                throw new IllegalArgumentException(
                        "a plan without a reviewed catalog cannot carry catalog evidence");
            }
            if (catalogApplied
                    && (!reviewedCatalogId.equals(
                                    Atm10Normal81ServerConfigCatalog.CATALOG_ID)
                            || !reviewedCatalogNameSequenceSha256.equals(
                                    Atm10Normal81ServerConfigCatalog.NAME_SEQUENCE_SHA256)
                            || !reviewedCatalogPayloadSequenceSha256.equals(
                                    Atm10Normal81ServerConfigCatalog.PAYLOAD_SEQUENCE_SHA256)
                            || reviewedCatalogCandidateCount
                                    != Atm10Normal81ServerConfigCatalog.CONFIG_COUNT
                            || configs.size() != Atm10Normal81ServerConfigCatalog.CONFIG_COUNT
                            || reviewedCatalogEncodedBytes
                                    != Atm10Normal81ServerConfigCatalog.TOTAL_ENCODED_BYTES
                            || addedReviewedCatalogConfigCount
                                            + retainedReviewedCatalogConfigCount
                                    != reviewedCatalogCandidateCount
                            || !Atm10Normal81ServerConfigCatalog.nameSequenceSha256(configs)
                                    .equals(Atm10Normal81ServerConfigCatalog
                                            .NAME_SEQUENCE_SHA256))) {
                throw new IllegalArgumentException(
                        "reviewed SERVER-config catalog evidence differs from exact pins");
            }
        }

        boolean reviewedCatalogApplied() {
            return !reviewedCatalogId.equals(NO_REVIEWED_CATALOG);
        }
    }
}
