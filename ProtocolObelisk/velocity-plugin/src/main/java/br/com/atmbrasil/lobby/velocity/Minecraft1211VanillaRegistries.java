package br.com.atmbrasil.lobby.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The exact vanilla Minecraft 1.21.1 entries of every registry synchronized in CONFIGURATION.
 *
 * <p>A Paper 1.21.1 lobby sends exactly these entries (from the {@code minecraft:core} known
 * pack), so they are the ids a compatibility pack must never send again. The list is fixed for
 * protocol 767; its digest is the same known-pack sequence SHA-256 reviewed for the ATM10 8.0
 * profile ({@code known-pack-entry.sequence-sha256}), which proves both were taken from the same
 * official vanilla data.</p>
 */
final class Minecraft1211VanillaRegistries {
    private static final String RESOURCE = "vanilla-registries/minecraft-1.21.1-synchronized.tsv";
    private static final int EXPECTED_ENTRY_COUNT = 313;
    private static final int EXPECTED_REGISTRY_COUNT = 11;
    private static final String EXPECTED_SEQUENCE_SHA256 =
            "56f0ce758b71b436b6c7c69ac671122cc252efa49c5af9ebc2efaf59ed87fa85";
    private static final Pattern RESOURCE_LOCATION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private static final Map<String, Set<String>> ENTRIES = load();

    private Minecraft1211VanillaRegistries() {
    }

    /** True when Paper itself sends this registry, so a pack may only extend it. */
    static boolean isSynchronized(String registryId) {
        return ENTRIES.containsKey(registryId);
    }

    static boolean isVanillaEntry(String registryId, String entryId) {
        Set<String> entries = ENTRIES.get(registryId);
        return entries != null && entries.contains(entryId);
    }

    static Set<String> registries() {
        return ENTRIES.keySet();
    }

    static Set<String> entries(String registryId) {
        return ENTRIES.getOrDefault(registryId, Set.of());
    }

    static int entryCount() {
        return ENTRIES.values().stream().mapToInt(Set::size).sum();
    }

    private static Map<String, Set<String>> load() {
        byte[] bytes;
        try (InputStream input = Minecraft1211VanillaRegistries.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing vanilla registry list " + RESOURCE);
            }
            bytes = input.readNBytes(1_048_576);
        } catch (IOException exception) {
            throw new IllegalStateException("unreadable vanilla registry list " + RESOURCE, exception);
        }
        MessageDigest digest = newSha256();
        LinkedHashMap<String, Set<String>> entries = new LinkedHashMap<>();
        String previous = null;
        int count = 0;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 2
                    || !RESOURCE_LOCATION.matcher(fields[0]).matches()
                    || !RESOURCE_LOCATION.matcher(fields[1]).matches()) {
                throw new IllegalStateException("vanilla registry list line is not canonical");
            }
            if (previous != null && previous.compareTo(line) >= 0) {
                throw new IllegalStateException("vanilla registry list is not in canonical order");
            }
            previous = line;
            entries.computeIfAbsent(fields[0], ignored -> new LinkedHashSet<>()).add(fields[1]);
            digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            count++;
        }
        String sequence = HexFormat.of().formatHex(digest.digest());
        if (count != EXPECTED_ENTRY_COUNT
                || entries.size() != EXPECTED_REGISTRY_COUNT
                || !sequence.equals(EXPECTED_SEQUENCE_SHA256)) {
            throw new IllegalStateException("vanilla registry list differs from reviewed 1.21.1 data");
        }
        LinkedHashMap<String, Set<String>> frozen = new LinkedHashMap<>();
        entries.forEach((registry, ids) ->
                frozen.put(registry, Collections.unmodifiableSet(new LinkedHashSet<>(ids))));
        return Collections.unmodifiableMap(frozen);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
}
