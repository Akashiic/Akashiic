package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded generation cache for exact, immutable CONFIGURATION prefix wire encodings. */
final class LobbyConfigurationPrefixCache {
    static final int DEFAULT_MAXIMUM_ENTRIES = 16;
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final long generation;
    private final int maximumEntries;
    private final LinkedHashMap<Key, VelocityPluginMessageBatchSender.Batch> entries =
            new LinkedHashMap<>(16, 0.75f, true);

    private long hits;
    private long misses;
    private long evictions;

    LobbyConfigurationPrefixCache(long generation) {
        this(generation, DEFAULT_MAXIMUM_ENTRIES);
    }

    LobbyConfigurationPrefixCache(long generation, int maximumEntries) {
        if (generation < 1) {
            throw new IllegalArgumentException("cache generation must be positive");
        }
        if (maximumEntries < 1 || maximumEntries > 256) {
            throw new IllegalArgumentException("cache entry bound must be between 1 and 256");
        }
        this.generation = generation;
        this.maximumEntries = maximumEntries;
    }

    synchronized Lookup resolve(Key key, Compiler compiler)
            throws ProtocolViolationException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(compiler, "compiler");
        VelocityPluginMessageBatchSender.Batch cached = entries.get(key);
        if (cached != null) {
            hits++;
            return new Lookup(cached, true, snapshotLocked());
        }

        VelocityPluginMessageBatchSender.Batch compiled = Objects.requireNonNull(
                compiler.compile(), "compiled prefix");
        misses++;
        entries.put(key, compiled);
        if (entries.size() > maximumEntries) {
            Iterator<Map.Entry<Key, VelocityPluginMessageBatchSender.Batch>> iterator =
                    entries.entrySet().iterator();
            iterator.next();
            iterator.remove();
            evictions++;
        }
        return new Lookup(compiled, false, snapshotLocked());
    }

    synchronized Snapshot snapshot() {
        return snapshotLocked();
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(generation, entries.size(), maximumEntries, hits, misses, evictions);
    }

    record Key(
            int minecraftProtocol,
            List<Channel> playChannels,
            List<Channel> configurationChannels,
            List<String> advertisedBuiltInChannels,
            List<String> transientServerConfigs,
            String serverConfigPayloadSequenceSha256,
            String exactProfileId,
            String ae2ConfigSha256) {
        Key {
            if (minecraftProtocol < 1) {
                throw new IllegalArgumentException("Minecraft protocol must be positive");
            }
            playChannels = List.copyOf(Objects.requireNonNull(
                    playChannels, "playChannels"));
            configurationChannels = List.copyOf(Objects.requireNonNull(
                    configurationChannels, "configurationChannels"));
            advertisedBuiltInChannels = List.copyOf(Objects.requireNonNull(
                    advertisedBuiltInChannels, "advertisedBuiltInChannels"));
            transientServerConfigs = List.copyOf(Objects.requireNonNull(
                    transientServerConfigs, "transientServerConfigs"));
            serverConfigPayloadSequenceSha256 = Objects.requireNonNull(
                    serverConfigPayloadSequenceSha256,
                    "serverConfigPayloadSequenceSha256");
            exactProfileId = Objects.requireNonNull(exactProfileId, "exactProfileId");
            ae2ConfigSha256 = Objects.requireNonNull(ae2ConfigSha256, "ae2ConfigSha256");
            if (configurationChannels.isEmpty()
                    || advertisedBuiltInChannels.isEmpty()
                    || advertisedBuiltInChannels.stream().anyMatch(String::isBlank)
                    || transientServerConfigs.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("invalid configuration prefix cache key");
            }
            if ((!serverConfigPayloadSequenceSha256.isEmpty()
                            && !SHA256.matcher(serverConfigPayloadSequenceSha256).matches())
                    || (!exactProfileId.isEmpty()
                            && !PROFILE_ID.matcher(exactProfileId).matches())
                    || (!ae2ConfigSha256.isEmpty()
                            && !SHA256.matcher(ae2ConfigSha256).matches())) {
                throw new IllegalArgumentException(
                        "invalid SERVER-config, exact profile or AE2 identity in prefix cache key");
            }
            List<String> canonicalBuiltIns = advertisedBuiltInChannels.stream()
                    .distinct()
                    .sorted()
                    .toList();
            if (!advertisedBuiltInChannels.equals(canonicalBuiltIns)) {
                throw new IllegalArgumentException(
                        "advertised built-in channels must be sorted and unique");
            }
        }
    }

    record Lookup(
            VelocityPluginMessageBatchSender.Batch batch,
            boolean hit,
            Snapshot snapshot) {
        Lookup {
            Objects.requireNonNull(batch, "batch");
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

    @FunctionalInterface
    interface Compiler {
        VelocityPluginMessageBatchSender.Batch compile() throws ProtocolViolationException;
    }
}
