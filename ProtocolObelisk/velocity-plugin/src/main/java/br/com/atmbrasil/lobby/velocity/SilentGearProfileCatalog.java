package br.com.atmbrasil.lobby.velocity;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;

/** Selects only an embedded profile whose exact canonical client contract is reviewed. */
final class SilentGearProfileCatalog {
    private final Map<CanonicalProfileKey, SilentGearEmbeddedProfile> canonicalProfiles;
    private final Map<LegacyProfileKey, SilentGearEmbeddedProfile> legacyProfiles;
    private final Set<LegacyProfileKey> canonicalFamilies;

    /** Loads profiles through their explicitly reviewed legacy or canonical selector. */
    SilentGearProfileCatalog(List<SilentGearEmbeddedProfile> profiles) {
        this(profiles.stream().map(Registration::legacy).toList(), true);
    }

    private SilentGearProfileCatalog(List<Registration> registrations, boolean ignored) {
        Objects.requireNonNull(registrations, "registrations");
        LinkedHashMap<CanonicalProfileKey, SilentGearEmbeddedProfile> canonical =
                new LinkedHashMap<>();
        LinkedHashMap<LegacyProfileKey, SilentGearEmbeddedProfile> legacy =
                new LinkedHashMap<>();
        LinkedHashMap<LegacyProfileKey, Boolean> canonicalFamilyMap = new LinkedHashMap<>();
        for (Registration registration : registrations) {
            Registration nonNull = Objects.requireNonNull(registration, "registration");
            SilentGearEmbeddedProfile profile = nonNull.profile();
            if (nonNull.fullClientContractSha256().isPresent()) {
                CanonicalProfileKey key = new CanonicalProfileKey(
                        profile.minecraftProtocol(),
                        nonNull.fullClientContractSha256().orElseThrow(),
                        profile.silentGearChannelContractSha256());
                if (canonical.putIfAbsent(key, profile) != null) {
                    throw new IllegalArgumentException(
                            "duplicate canonical Silent Gear profile selector " + key);
                }
                canonicalFamilyMap.put(new LegacyProfileKey(
                        profile.minecraftProtocol(),
                        profile.silentGearChannelContractSha256()), Boolean.TRUE);
            } else {
                LegacyProfileKey key = new LegacyProfileKey(
                        profile.minecraftProtocol(),
                        profile.silentGearChannelContractSha256());
                if (legacy.putIfAbsent(key, profile) != null) {
                    throw new IllegalArgumentException(
                            "duplicate legacy Silent Gear profile selector " + key);
                }
            }
        }
        canonicalProfiles = Map.copyOf(canonical);
        legacyProfiles = Map.copyOf(legacy);
        canonicalFamilies = Set.copyOf(canonicalFamilyMap.keySet());
    }

    static SilentGearProfileCatalog empty() {
        return new SilentGearProfileCatalog(List.of());
    }

    static SilentGearProfileCatalog loadReviewed(
            ClassLoader loader,
            int expectedMinecraftProtocol,
            int maximumPayloadBytes,
            int maximumTotalBytes) throws IOException {
        SilentGearEmbeddedProfile tts = SilentGearEmbeddedProfile.loadReviewed(
                loader,
                expectedMinecraftProtocol,
                maximumPayloadBytes,
                maximumTotalBytes);
        SilentGearEmbeddedProfile normal = SilentGearEmbeddedProfile.loadAtm10Normal73(
                loader,
                expectedMinecraftProtocol,
                maximumPayloadBytes,
                maximumTotalBytes);
        SilentGearEmbeddedProfile normal80 = SilentGearEmbeddedProfile.loadAtm10Normal80(
                loader,
                expectedMinecraftProtocol,
                maximumPayloadBytes,
                maximumTotalBytes);
        return SilentGearProfileCatalog.fromRegistrations(List.of(
                Registration.legacy(tts),
                Registration.canonical(
                        normal, normal.fullClientContractSha256().orElseThrow()),
                Registration.canonical(
                        normal80, normal80.fullClientContractSha256().orElseThrow())));
    }

    static SilentGearProfileCatalog fromRegistrations(List<Registration> registrations) {
        return new SilentGearProfileCatalog(registrations, true);
    }

    Optional<SilentGearEmbeddedProfile> select(
            int minecraftProtocol,
            ChannelContractSignature.Signatures signatures) {
        Objects.requireNonNull(signatures, "signatures");
        if (signatures.silentGearContractSha256().isEmpty()) {
            return Optional.empty();
        }
        SilentGearEmbeddedProfile canonical = canonicalProfiles.get(new CanonicalProfileKey(
                minecraftProtocol,
                signatures.fullContractSha256(),
                signatures.silentGearContractSha256()));
        if (canonical != null) {
            return Optional.of(canonical);
        }
        LegacyProfileKey family = new LegacyProfileKey(
                minecraftProtocol, signatures.silentGearContractSha256());
        if (canonicalFamilies.contains(family)) {
            return Optional.empty();
        }
        return Optional.ofNullable(legacyProfiles.get(family));
    }

    /**
     * Selects a profile from a fully decoded live registry.
     *
     * <p>Normal 8.0 extensions are first validated structurally, then normalized to the immutable
     * base profile key. A known Normal 8.0 hash whose registry shape does not match its reviewed
     * variant is rejected instead of falling back to the hash-only selector.</p>
     */
    Optional<SilentGearEmbeddedProfile> select(
            int minecraftProtocol,
            Registry registry,
            ChannelContractSignature.Signatures signatures) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(signatures, "signatures");
        Optional<ReviewedClientContractEvidence.Atm10Normal80Variant> normal80 =
                ReviewedClientContractEvidence.matchAtm10Normal80(
                        minecraftProtocol, registry, signatures);
        if (normal80.isPresent()) {
            ReviewedClientContractEvidence.Atm10Normal80Variant variant = normal80.orElseThrow();
            return Optional.ofNullable(canonicalProfiles.get(new CanonicalProfileKey(
                    minecraftProtocol,
                    variant.normalizedFullClientContractSha256(),
                    signatures.silentGearContractSha256())));
        }
        if (ReviewedClientContractEvidence.isAtm10Normal80ObservedHash(signatures)) {
            return Optional.empty();
        }
        if (ReviewedClientContractEvidence.isAtm10Normal73ObservedHash(signatures)
                && !ReviewedClientContractEvidence.matchesAtm10Normal73(
                        minecraftProtocol, registry, signatures)) {
            return Optional.empty();
        }
        return select(minecraftProtocol, signatures);
    }

    Optional<SilentGearEmbeddedProfile> select(
            int minecraftProtocol,
            SilentGearProtocol.Compatibility compatibility) {
        Objects.requireNonNull(compatibility, "compatibility");
        if (!compatibility.exact()) {
            return Optional.empty();
        }
        LegacyProfileKey family = new LegacyProfileKey(
                minecraftProtocol, compatibility.contractSha256());
        if (canonicalFamilies.contains(family)) {
            return Optional.empty();
        }
        return Optional.ofNullable(legacyProfiles.get(family));
    }

    boolean hasProfileFor(
            int minecraftProtocol,
            ChannelContractSignature.Signatures signatures) {
        return select(minecraftProtocol, signatures).isPresent();
    }

    int size() {
        return canonicalProfiles.size() + legacyProfiles.size();
    }

    List<SilentGearEmbeddedProfile> profiles() {
        LinkedHashMap<String, SilentGearEmbeddedProfile> unique = new LinkedHashMap<>();
        legacyProfiles.values().forEach(profile -> unique.put(profile.profileId(), profile));
        canonicalProfiles.values().forEach(profile -> unique.put(profile.profileId(), profile));
        return List.copyOf(unique.values());
    }

    int maximumFrozenRegistryPayloadBytes() {
        return profiles().stream()
                .mapToInt(profile -> profile.frozenRegistries().maximumPayloadBytes())
                .max()
                .orElse(0);
    }

    record Registration(
            SilentGearEmbeddedProfile profile,
            Optional<String> fullClientContractSha256) {
        Registration {
            Objects.requireNonNull(profile, "profile");
            fullClientContractSha256 = Objects.requireNonNull(
                    fullClientContractSha256, "fullClientContractSha256");
            fullClientContractSha256.ifPresent(ProfileKeyValidation::requireSha256);
        }

        static Registration legacy(SilentGearEmbeddedProfile profile) {
            return new Registration(profile, Optional.empty());
        }

        static Registration canonical(
                SilentGearEmbeddedProfile profile,
                String fullClientContractSha256) {
            return new Registration(profile, Optional.of(fullClientContractSha256));
        }
    }

    private record CanonicalProfileKey(
            int minecraftProtocol,
            String fullClientContractSha256,
            String silentGearContractSha256) {
        private CanonicalProfileKey {
            ProfileKeyValidation.requireProtocol(minecraftProtocol);
            ProfileKeyValidation.requireSha256(fullClientContractSha256);
            ProfileKeyValidation.requireSha256(silentGearContractSha256);
        }
    }

    private record LegacyProfileKey(int minecraftProtocol, String silentGearContractSha256) {
        private LegacyProfileKey {
            ProfileKeyValidation.requireProtocol(minecraftProtocol);
            ProfileKeyValidation.requireSha256(silentGearContractSha256);
        }
    }

    private static final class ProfileKeyValidation {
        private ProfileKeyValidation() {
        }

        private static void requireProtocol(int minecraftProtocol) {
            if (minecraftProtocol <= 0) {
                throw new IllegalArgumentException("minecraftProtocol must be positive");
            }
        }

        private static void requireSha256(String value) {
            Objects.requireNonNull(value, "value");
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("profile selector must be a lowercase SHA-256");
            }
        }
    }
}
