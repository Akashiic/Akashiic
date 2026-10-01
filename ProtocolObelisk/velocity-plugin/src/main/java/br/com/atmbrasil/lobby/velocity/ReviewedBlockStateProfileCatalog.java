package br.com.atmbrasil.lobby.velocity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Exact structural-enrichment catalog indexed only by protocol and full client contract SHA-256.
 *
 * <p>The catalog never answers an admission or routing question. A miss means only that the
 * session has no reviewed embedded BlockState enrichment. An absent, corrupt or ambiguous
 * definition is quarantined with a diagnostic; it can never disable the plugin or become a fuzzy
 * fallback for another client contract.</p>
 */
final class ReviewedBlockStateProfileCatalog {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final int SUPPORTED_MINECRAFT_PROTOCOL = 767;

    private final Map<StructuralIdentity, BlockStateTranslationProfile> profilesByIdentity;
    private final List<BlockStateTranslationProfile> profiles;
    private final List<LoadDiagnostic> diagnostics;

    private ReviewedBlockStateProfileCatalog(
            Map<StructuralIdentity, BlockStateTranslationProfile> profilesByIdentity,
            List<LoadDiagnostic> diagnostics) {
        this.profilesByIdentity = Map.copyOf(profilesByIdentity);
        this.profiles = List.copyOf(profilesByIdentity.values());
        this.diagnostics = List.copyOf(diagnostics);
    }

    static ReviewedBlockStateProfileCatalog empty() {
        return new ReviewedBlockStateProfileCatalog(Map.of(), List.of());
    }

    static ReviewedBlockStateProfileCatalog loadReviewed(
            ClassLoader loader, Collection<Definition> definitions) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(definitions, "definitions");
        List<Definition> snapshot = List.copyOf(definitions);
        HashMap<StructuralIdentity, Integer> identityCounts = new HashMap<>();
        HashMap<String, Integer> profileIdCounts = new HashMap<>();
        for (Definition definition : snapshot) {
            Objects.requireNonNull(definition, "definition");
            identityCounts.merge(definition.structuralIdentity(), 1, Integer::sum);
            profileIdCounts.merge(definition.profileId(), 1, Integer::sum);
        }

        LinkedHashMap<StructuralIdentity, BlockStateTranslationProfile> loaded =
                new LinkedHashMap<>();
        ArrayList<LoadDiagnostic> diagnostics = new ArrayList<>();
        for (Definition definition : snapshot) {
            if (identityCounts.get(definition.structuralIdentity()) > 1) {
                diagnostics.add(new LoadDiagnostic(
                        definition.profileId(),
                        LoadFailure.DUPLICATE_STRUCTURAL_IDENTITY,
                        "protocol and full client contract are declared more than once"));
                continue;
            }
            if (profileIdCounts.get(definition.profileId()) > 1) {
                diagnostics.add(new LoadDiagnostic(
                        definition.profileId(),
                        LoadFailure.DUPLICATE_PROFILE_ID,
                        "profile id is declared more than once"));
                continue;
            }
            final BlockStateTranslationProfile profile;
            try {
                profile = BlockStateTranslationProfile.loadReviewed(
                        loader,
                        definition.resourceRoot(),
                        definition.profileId(),
                        definition.minecraftProtocol(),
                        definition.fullClientContractSha256(),
                        definition.manifestSha256());
            } catch (IOException exception) {
                diagnostics.add(new LoadDiagnostic(
                        definition.profileId(),
                        LoadFailure.MISSING_OR_UNREADABLE_RESOURCE,
                        diagnosticDetail(exception)));
                continue;
            } catch (RuntimeException exception) {
                diagnostics.add(new LoadDiagnostic(
                        definition.profileId(),
                        LoadFailure.INVALID_RESOURCE,
                        diagnosticDetail(exception)));
                continue;
            }
            StructuralIdentity key = definition.structuralIdentity();
            if (loaded.putIfAbsent(key, profile) != null) {
                throw new AssertionError(
                        "prevalidated BlockState structural identity became duplicate");
            }
        }
        return new ReviewedBlockStateProfileCatalog(loaded, diagnostics);
    }

    /**
     * Finds optional visual/packet enrichment; absence must not change admission or routing.
     */
    Optional<BlockStateTranslationProfile> findStructuralEnrichment(
            int minecraftProtocol, String fullClientContractSha256) {
        if (minecraftProtocol != SUPPORTED_MINECRAFT_PROTOCOL
                || fullClientContractSha256 == null
                || !SHA256.matcher(fullClientContractSha256).matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(profilesByIdentity.get(
                new StructuralIdentity(minecraftProtocol, fullClientContractSha256)));
    }

    List<BlockStateTranslationProfile> reviewedProfiles() {
        return profiles;
    }

    List<LoadDiagnostic> diagnostics() {
        return diagnostics;
    }

    record Definition(
            String resourceRoot,
            String profileId,
            int minecraftProtocol,
            String fullClientContractSha256,
            String manifestSha256) {
        Definition {
            Objects.requireNonNull(resourceRoot, "resourceRoot");
            if (!PROFILE_ID.matcher(Objects.requireNonNull(profileId, "profileId")).matches()) {
                throw new IllegalArgumentException(
                        "reviewed BlockState profile id is not canonical");
            }
            if (minecraftProtocol != SUPPORTED_MINECRAFT_PROTOCOL) {
                throw new IllegalArgumentException(
                        "reviewed BlockState definition requires Minecraft protocol 767");
            }
            requireSha256(fullClientContractSha256, "full client contract");
            requireSha256(manifestSha256, "manifest");
        }

        private StructuralIdentity structuralIdentity() {
            return new StructuralIdentity(minecraftProtocol, fullClientContractSha256);
        }
    }

    private record StructuralIdentity(
            int minecraftProtocol, String fullClientContractSha256) {
    }

    enum LoadFailure {
        MISSING_OR_UNREADABLE_RESOURCE,
        INVALID_RESOURCE,
        DUPLICATE_STRUCTURAL_IDENTITY,
        DUPLICATE_PROFILE_ID
    }

    record LoadDiagnostic(String profileId, LoadFailure failure, String detail) {
        LoadDiagnostic {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(failure, "failure");
            if (Objects.requireNonNull(detail, "detail").isBlank()) {
                throw new IllegalArgumentException(
                        "reviewed BlockState diagnostic detail must not be blank");
            }
        }
    }

    private static void requireSha256(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "reviewed BlockState " + label + " must be lowercase SHA-256");
        }
    }

    private static String diagnosticDetail(Exception failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
