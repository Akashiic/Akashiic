package br.com.atmbrasil.lobby.velocity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Generation-bound LRU cache for immutable results derived from a validated NeoForge query.
 *
 * <p>The cache deliberately does not contain player identity, readiness tokens, acknowledgements,
 * backend advertisements or mutable protocol state. Callers must fully decode the current query
 * before constructing the key. Consequently a hit accelerates deterministic planning only; it can
 * never authorize an unvalidated packet or replace the exact embedded profile catalog.</p>
 */
final class LobbyNegotiationPlanCache {
    static final int DEFAULT_MAXIMUM_ENTRIES = 16;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final long generation;
    private final int maximumEntries;
    private final LinkedHashMap<Key, Plan> entries = new LinkedHashMap<>(16, 0.75f, true);

    private long hits;
    private long misses;
    private long evictions;

    LobbyNegotiationPlanCache(long generation) {
        this(generation, DEFAULT_MAXIMUM_ENTRIES);
    }

    LobbyNegotiationPlanCache(long generation, int maximumEntries) {
        if (generation < 1) {
            throw new IllegalArgumentException("cache generation must be positive");
        }
        if (maximumEntries < 1 || maximumEntries > 256) {
            throw new IllegalArgumentException("cache entry bound must be between 1 and 256");
        }
        this.generation = generation;
        this.maximumEntries = maximumEntries;
    }

    synchronized Lookup resolve(Key key, Supplier<Plan> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        Plan cached = entries.get(key);
        if (cached != null) {
            hits++;
            return new Lookup(cached, true, snapshotLocked());
        }

        Plan computed = Objects.requireNonNull(factory.get(), "computed plan");
        misses++;
        entries.put(key, computed);
        if (entries.size() > maximumEntries) {
            Iterator<Map.Entry<Key, Plan>> iterator = entries.entrySet().iterator();
            iterator.next();
            iterator.remove();
            evictions++;
        }
        return new Lookup(computed, false, snapshotLocked());
    }

    synchronized Snapshot snapshot() {
        return snapshotLocked();
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(generation, entries.size(), maximumEntries, hits, misses, evictions);
    }

    record Key(
            int minecraftProtocol,
            String querySha256,
            String canonicalContractSha256,
            int channelCount,
            int ignoredChannelCount) {
        Key {
            if (minecraftProtocol < 1) {
                throw new IllegalArgumentException("Minecraft protocol must be positive");
            }
            requireSha256(querySha256, "querySha256");
            requireSha256(canonicalContractSha256, "canonicalContractSha256");
            if (channelCount < 1 || ignoredChannelCount < 0) {
                throw new IllegalArgumentException("invalid decoded registry shape");
            }
        }

        private static void requireSha256(String value, String field) {
            if (!SHA256.matcher(Objects.requireNonNull(value, field)).matches()) {
                throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
            }
        }
    }

    record Plan(
            ChannelContractSignature.Signatures contractSignatures,
            SilentGearProtocol.Compatibility silentGearCompatibility,
            Optional<SilentGearEmbeddedProfile> selectedSilentGearProfile,
            PlaySinkPlanner.Plan playSinkPlan,
            TransientServerConfigPlanner.Plan transientConfigPlan,
            Optional<ReviewedBackendNeoForgeAdvertisement.Evidence> backendAdvertisementEvidence,
            boolean strictBackendAdvertisement,
            boolean ae2JeiSessionOptimizationSelected) {
        Plan {
            Objects.requireNonNull(contractSignatures, "contractSignatures");
            Objects.requireNonNull(silentGearCompatibility, "silentGearCompatibility");
            selectedSilentGearProfile = Objects.requireNonNull(
                    selectedSilentGearProfile, "selectedSilentGearProfile");
            Objects.requireNonNull(playSinkPlan, "playSinkPlan");
            Objects.requireNonNull(transientConfigPlan, "transientConfigPlan");
            backendAdvertisementEvidence = Objects.requireNonNull(
                    backendAdvertisementEvidence, "backendAdvertisementEvidence");
            if (playSinkPlan.silentGearProfileSelected()
                    != selectedSilentGearProfile.isPresent()) {
                throw new IllegalArgumentException(
                        "selected profile and PLAY plan must describe the same contract");
            }
            if (ae2JeiSessionOptimizationSelected && selectedSilentGearProfile.isEmpty()) {
                throw new IllegalArgumentException(
                        "AE2-JEI optimization requires an exact selected profile");
            }
        }
    }

    record Lookup(Plan plan, boolean hit, Snapshot snapshot) {
        Lookup {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Snapshot(
            long generation,
            int size,
            int maximumEntries,
            long hits,
            long misses,
            long evictions) {
    }
}
