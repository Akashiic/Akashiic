package br.com.atmbrasil.lobby.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, exact CONFIG registry-data extension for one reviewed client profile.
 *
 * <p>Paper supplies the vanilla entries. These packets contain only non-vanilla entry IDs from
 * the matching NeoForge backend, with every value encoded because the Paper negotiation selects
 * only {@code minecraft:core}. The loader validates the reviewed aggregate as well as every file
 * before any player session can use it.</p>
 */
final class EmbeddedDynamicRegistryProfile {
    private static final String RESOURCE_ROOT =
            SilentGearEmbeddedProfile.ATM10_NORMAL_7_3_RESOURCE_ROOT;
    private static final String MANIFEST_FILE = "dynamic-registries.properties";
    private static final String EXPECTED_PACK = "ATM10-7.3";
    private static final String EXPECTED_MINECRAFT = "1.21.1";
    private static final String EXPECTED_NEOFORGE = "21.1.247";
    private static final String EXPECTED_KNOWN_PACK = "minecraft:core:1.21.1";

    // Replaced only from a deterministic export of the reviewed official backend.
    private static final int EXPECTED_REGISTRY_COUNT = 46;
    private static final int EXPECTED_TOTAL_ENTRIES = 1_826;
    private static final int EXPECTED_TOTAL_BYTES = 577_165;
    private static final String EXPECTED_SEQUENCE_SHA256 =
            "69cbe809ad82bd56c45cf01d8f96a434c614736780360d42129bb3fbc927bb5f";
    private static final KnownPackEvidence ATM10_NORMAL_8_0_KNOWN_PACK_EVIDENCE =
            new KnownPackEvidence(
                    313,
                    "56f0ce758b71b436b6c7c69ac671122cc252efa49c5af9ebc2efaf59ed87fa85",
                    List.of(
                            new KnownPackEntry("minecraft:dimension_type", "minecraft:the_end"),
                            new KnownPackEntry("minecraft:enchantment", "minecraft:channeling"),
                            new KnownPackEntry("minecraft:enchantment", "minecraft:protection"),
                            new KnownPackEntry("minecraft:enchantment", "minecraft:sharpness"),
                            new KnownPackEntry("minecraft:wolf_variant", "minecraft:ashen"),
                            new KnownPackEntry("minecraft:wolf_variant", "minecraft:black"),
                            new KnownPackEntry("minecraft:wolf_variant", "minecraft:chestnut"),
                            new KnownPackEntry("minecraft:wolf_variant", "minecraft:pale"),
                            new KnownPackEntry("minecraft:wolf_variant", "minecraft:rusty"),
                            new KnownPackEntry("minecraft:wolf_variant", "minecraft:snowy"),
                            new KnownPackEntry("minecraft:wolf_variant", "minecraft:spotted"),
                            new KnownPackEntry("minecraft:wolf_variant", "minecraft:striped"),
                            new KnownPackEntry("minecraft:wolf_variant", "minecraft:woods"),
                            new KnownPackEntry(
                                    "minecraft:worldgen/biome", "minecraft:end_barrens"),
                            new KnownPackEntry(
                                    "minecraft:worldgen/biome", "minecraft:end_highlands"),
                            new KnownPackEntry(
                                    "minecraft:worldgen/biome", "minecraft:end_midlands"),
                            new KnownPackEntry(
                                    "minecraft:worldgen/biome", "minecraft:small_end_islands"),
                            new KnownPackEntry(
                                    "minecraft:worldgen/biome", "minecraft:the_end")),
                    "b9dd6e9c6e783f8a2773dda779e0aea965204e2334e490041c794f5f59a3cab8");

    private static final int MAXIMUM_REGISTRIES = 128;
    private static final int MAXIMUM_ENTRIES_PER_REGISTRY = 65_535;
    private static final int MAXIMUM_PACKET_BYTES = 1_024 * 1_024;
    private static final int MAXIMUM_TOTAL_BYTES = 2 * 1_024 * 1_024;
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern RESOURCE_FILE = Pattern.compile(
            "dynamic-registries/[a-z0-9_.-]+\\.bin");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final EmbeddedDynamicRegistryProfile EMPTY =
            new EmbeddedDynamicRegistryProfile(List.of(), 0, 0, sha256(new byte[0]));

    private final List<RegistryShimPacket> packets;
    private final int totalEntries;
    private final int totalBytes;
    private final String sequenceSha256;

    private EmbeddedDynamicRegistryProfile(
            List<RegistryShimPacket> packets,
            int totalEntries,
            int totalBytes,
            String sequenceSha256) {
        this.packets = List.copyOf(Objects.requireNonNull(packets, "packets"));
        this.totalEntries = totalEntries;
        this.totalBytes = totalBytes;
        this.sequenceSha256 = Objects.requireNonNull(sequenceSha256, "sequenceSha256");
    }

    static EmbeddedDynamicRegistryProfile empty() {
        return EMPTY;
    }

    static EmbeddedDynamicRegistryProfile loadAtm10Normal73(ClassLoader loader)
            throws IOException {
        return load(
                loader,
                RESOURCE_ROOT,
                "atm10-normal-7.3",
                "1",
                EXPECTED_PACK,
                "non-minecraft-entry-ids",
                EXPECTED_REGISTRY_COUNT,
                EXPECTED_TOTAL_ENTRIES,
                EXPECTED_TOTAL_BYTES,
                EXPECTED_SEQUENCE_SHA256,
                Optional.empty());
    }

    static EmbeddedDynamicRegistryProfile loadAtm10Normal80(ClassLoader loader)
            throws IOException {
        return load(
                loader,
                SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT,
                "atm10-normal-8.0",
                "3",
                "ATM10-8.0",
                "encoded-entries-minus-exact-vanilla-known-pack-entry-ids",
                50,
                1_946,
                651_622,
                "e78c792986feed4fe861994cceb7ffb6dc24401feda7a600f272c6eef74a0935",
                Optional.of(ATM10_NORMAL_8_0_KNOWN_PACK_EVIDENCE));
    }

    private static EmbeddedDynamicRegistryProfile load(
            ClassLoader loader,
            String resourceRoot,
            String packetIdPrefix,
            String formatVersion,
            String expectedPack,
            String expectedSelection,
            int expectedRegistryCount,
            int expectedTotalEntries,
            int expectedTotalBytes,
            String expectedSequenceSha256,
            Optional<KnownPackEvidence> expectedKnownPackEvidence) throws IOException {
        Objects.requireNonNull(loader, "loader");
        Properties manifest = loadManifest(loader, resourceRoot);
        requireEquals(manifest, "format-version", formatVersion);
        requireEquals(manifest, "pack", expectedPack);
        requireEquals(manifest, "minecraft", EXPECTED_MINECRAFT);
        requireEquals(manifest, "neoforge", EXPECTED_NEOFORGE);
        requireEquals(manifest, "selection", expectedSelection);
        requireEquals(manifest, "known-pack.count", "1");
        requireEquals(manifest, "known-pack.0", EXPECTED_KNOWN_PACK);
        expectedKnownPackEvidence.ifPresent(evidence ->
                validateKnownPackEvidence(manifest, evidence));

        int registryCount = boundedPositiveInt(
                manifest, "registry.count", MAXIMUM_REGISTRIES);
        requireExact(registryCount, expectedRegistryCount, "registry count");
        int declaredTotalEntries = boundedPositiveInt(
                manifest, "registry.total-entries", Integer.MAX_VALUE);
        int declaredEncodedEntries = boundedPositiveInt(
                manifest, "registry.total-encoded-data-entries", Integer.MAX_VALUE);
        if (declaredEncodedEntries != declaredTotalEntries) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry profile omits entry data");
        }
        int declaredTotalBytes = boundedPositiveInt(
                manifest, "registry.total-bytes", MAXIMUM_TOTAL_BYTES);
        String declaredSequenceSha256 = requireSha256(
                manifest, "registry.sequence-sha256");

        ArrayList<RegistryShimPacket> packets = new ArrayList<>(registryCount);
        HashSet<String> registryNames = new HashSet<>();
        HashSet<String> resourceFiles = new HashSet<>();
        MessageDigest sequenceDigest = newSha256();
        int actualTotalEntries = 0;
        int actualTotalBytes = 0;
        String previousRegistryName = null;
        for (int index = 0; index < registryCount; index++) {
            String key = "registry." + index + '.';
            String registryName = requireResourceLocation(manifest, key + "name");
            if (previousRegistryName != null
                    && previousRegistryName.compareTo(registryName) >= 0) {
                throw new IllegalArgumentException(
                        "embedded dynamic registries are not in canonical order");
            }
            previousRegistryName = registryName;
            if (!registryNames.add(registryName)) {
                throw new IllegalArgumentException(
                        "duplicate embedded dynamic registry " + registryName);
            }

            String file = require(manifest, key + "file");
            if (!RESOURCE_FILE.matcher(file).matches() || !resourceFiles.add(file)) {
                throw new IllegalArgumentException(
                        "invalid or duplicate embedded dynamic registry file " + file);
            }
            int entries = boundedPositiveInt(
                    manifest, key + "entries", MAXIMUM_ENTRIES_PER_REGISTRY);
            int encodedEntries = boundedPositiveInt(
                    manifest, key + "encoded-data-entries", MAXIMUM_ENTRIES_PER_REGISTRY);
            if (encodedEntries != entries) {
                throw new IllegalArgumentException(
                        "embedded dynamic registry has omitted entry data: " + registryName);
            }
            int declaredBytes = boundedPositiveInt(
                    manifest, key + "bytes", MAXIMUM_PACKET_BYTES);
            byte[] packetBody = readBounded(
                    loader, resourceRoot + file, MAXIMUM_PACKET_BYTES);
            if (packetBody.length != declaredBytes) {
                throw new IllegalArgumentException(
                        "embedded dynamic registry length mismatch: " + registryName);
            }
            PacketHeader header = decodeHeader(packetBody);
            if (!header.registryName().equals(registryName)
                    || header.entryCount() != entries) {
                throw new IllegalArgumentException(
                        "embedded dynamic registry packet header mismatch: " + registryName);
            }
            String declaredSha256 = requireSha256(manifest, key + "sha256");
            String actualSha256 = sha256(packetBody);
            if (!actualSha256.equals(declaredSha256)) {
                throw new IllegalArgumentException(
                        "embedded dynamic registry hash mismatch: " + registryName);
            }

            actualTotalEntries = Math.addExact(actualTotalEntries, entries);
            actualTotalBytes = Math.addExact(actualTotalBytes, packetBody.length);
            if (actualTotalBytes > MAXIMUM_TOTAL_BYTES) {
                throw new IllegalArgumentException(
                        "embedded dynamic registry profile exceeds total byte bound");
            }
            sequenceDigest.update(packetBody);
            packets.add(new RegistryShimPacket(
                    packetIdPrefix + "-dynamic-%03d".formatted(index),
                    registryName.substring(0, registryName.indexOf(':')),
                    registryName,
                    entries,
                    packetBody,
                    actualSha256));
        }

        String actualSequenceSha256 = HexFormat.of().formatHex(sequenceDigest.digest());
        requireExact(actualTotalEntries, declaredTotalEntries, "declared total entries");
        requireExact(actualTotalBytes, declaredTotalBytes, "declared total bytes");
        requireExact(actualTotalEntries, expectedTotalEntries, "reviewed total entries");
        requireExact(actualTotalBytes, expectedTotalBytes, "reviewed total bytes");
        requireExact(
                actualSequenceSha256, declaredSequenceSha256, "declared sequence SHA-256");
        requireExact(
                actualSequenceSha256, expectedSequenceSha256, "reviewed sequence SHA-256");
        return new EmbeddedDynamicRegistryProfile(
                packets, actualTotalEntries, actualTotalBytes, actualSequenceSha256);
    }

    List<RegistryShimPacket> packets() {
        return packets;
    }

    int registryCount() {
        return packets.size();
    }

    int totalEntries() {
        return totalEntries;
    }

    int totalBytes() {
        return totalBytes;
    }

    String sequenceSha256() {
        return sequenceSha256;
    }

    boolean isEmpty() {
        return packets.isEmpty();
    }

    private static Properties loadManifest(ClassLoader loader, String resourceRoot)
            throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resourceRoot + MANIFEST_FILE)) {
            if (stream == null) {
                throw new IOException("missing embedded dynamic registry manifest");
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties;
        }
    }

    private static byte[] readBounded(
            ClassLoader loader, String resource, int maximumBytes) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing embedded dynamic registry resource " + resource);
            }
            byte[] bytes = stream.readNBytes(Math.addExact(maximumBytes, 1));
            if (bytes.length > maximumBytes) {
                throw new IllegalArgumentException(
                        "embedded dynamic registry exceeds byte bound: " + resource);
            }
            return bytes;
        }
    }

    private static PacketHeader decodeHeader(byte[] packetBody) {
        ByteCursor cursor = new ByteCursor(packetBody);
        int stringBytes = cursor.readVarInt();
        if (stringBytes < 1 || stringBytes > 256) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry name length is outside bounds");
        }
        byte[] encodedName = cursor.readBytes(stringBytes);
        final String registryName;
        try {
            registryName = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encodedName))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry name is not UTF-8", exception);
        }
        if (!RESOURCE_LOCATION.matcher(registryName).matches()) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry packet has invalid registry name");
        }
        int entries = cursor.readVarInt();
        if (entries < 1 || entries > MAXIMUM_ENTRIES_PER_REGISTRY) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry packet entry count is outside bounds");
        }
        return new PacketHeader(registryName, entries);
    }

    private static String requireResourceLocation(Properties manifest, String key) {
        String value = require(manifest, key);
        if (!RESOURCE_LOCATION.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry manifest has invalid resource location " + key);
        }
        return value;
    }

    private static void validateKnownPackEvidence(
            Properties manifest, KnownPackEvidence expected) {
        int knownPackEntryCount = boundedPositiveInt(
                manifest, "known-pack-entry.count", MAXIMUM_ENTRIES_PER_REGISTRY);
        requireExact(
                knownPackEntryCount, expected.knownPackEntryCount(),
                "known-pack entry count");
        requireExact(
                requireSha256(manifest, "known-pack-entry.sequence-sha256"),
                expected.knownPackEntrySequenceSha256(),
                "known-pack entry sequence SHA-256");

        int omittedCount = boundedPositiveInt(
                manifest, "omitted-known-pack-entry.count", MAXIMUM_ENTRIES_PER_REGISTRY);
        requireExact(
                omittedCount, expected.omittedEntries().size(),
                "omitted known-pack entry count");
        requireExact(
                requireSha256(manifest, "omitted-known-pack-entry.sequence-sha256"),
                expected.omittedEntrySequenceSha256(),
                "omitted known-pack entry sequence SHA-256");

        MessageDigest omittedDigest = newSha256();
        HashSet<KnownPackEntry> uniqueEntries = new HashSet<>();
        for (int index = 0; index < omittedCount; index++) {
            String key = "omitted-known-pack-entry." + index + '.';
            KnownPackEntry actual = new KnownPackEntry(
                    requireResourceLocation(manifest, key + "registry"),
                    requireResourceLocation(manifest, key + "entry"));
            requireExact(
                    actual, expected.omittedEntries().get(index),
                    "omitted known-pack entry " + index);
            if (!uniqueEntries.add(actual)) {
                throw new IllegalArgumentException(
                        "embedded dynamic registry duplicate omitted known-pack entry " + actual);
            }
            omittedDigest.update((actual.registryId() + '\t' + actual.entryId() + '\n')
                    .getBytes(StandardCharsets.UTF_8));
        }
        String actualOmittedSequenceSha256 =
                HexFormat.of().formatHex(omittedDigest.digest());
        requireExact(
                actualOmittedSequenceSha256,
                expected.omittedEntrySequenceSha256(),
                "computed omitted known-pack entry sequence SHA-256");

        Set<String> expectedEvidenceKeys = new HashSet<>();
        expectedEvidenceKeys.add("known-pack-entry.count");
        expectedEvidenceKeys.add("known-pack-entry.sequence-sha256");
        expectedEvidenceKeys.add("omitted-known-pack-entry.count");
        expectedEvidenceKeys.add("omitted-known-pack-entry.sequence-sha256");
        for (int index = 0; index < omittedCount; index++) {
            expectedEvidenceKeys.add("omitted-known-pack-entry." + index + ".registry");
            expectedEvidenceKeys.add("omitted-known-pack-entry." + index + ".entry");
        }
        for (String key : manifest.stringPropertyNames()) {
            if ((key.startsWith("known-pack-entry.")
                    || key.startsWith("omitted-known-pack-entry."))
                    && !expectedEvidenceKeys.contains(key)) {
                throw new IllegalArgumentException(
                        "embedded dynamic registry manifest has unexpected evidence " + key);
            }
        }
    }

    private static int boundedPositiveInt(
            Properties properties, String key, int maximum) {
        final int value;
        try {
            value = Integer.parseInt(require(properties, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry manifest has invalid integer " + key,
                    exception);
        }
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry integer is outside bounds: " + key);
        }
        return value;
    }

    private static String requireSha256(Properties properties, String key) {
        String value = require(properties, key);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry manifest has invalid SHA-256 " + key);
        }
        return value;
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry manifest is missing " + key);
        }
        return value.strip();
    }

    private static void requireEquals(
            Properties properties, String key, String expected) {
        requireExact(require(properties, key), expected, key);
    }

    private static void requireExact(int actual, int expected, String label) {
        if (actual != expected) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry " + label + " mismatch: " + actual);
        }
    }

    private static void requireExact(String actual, String expected, String label) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry " + label + " mismatch: " + actual);
        }
    }

    private static void requireExact(
            KnownPackEntry actual, KnownPackEntry expected, String label) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "embedded dynamic registry " + label + " mismatch: " + actual);
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    private record PacketHeader(String registryName, int entryCount) {
    }

    private record KnownPackEntry(String registryId, String entryId) {
        private KnownPackEntry {
            Objects.requireNonNull(registryId, "registryId");
            Objects.requireNonNull(entryId, "entryId");
        }
    }

    private record KnownPackEvidence(
            int knownPackEntryCount,
            String knownPackEntrySequenceSha256,
            List<KnownPackEntry> omittedEntries,
            String omittedEntrySequenceSha256) {
        private KnownPackEvidence {
            knownPackEntrySequenceSha256 = Objects.requireNonNull(
                    knownPackEntrySequenceSha256, "knownPackEntrySequenceSha256");
            omittedEntries = List.copyOf(Objects.requireNonNull(
                    omittedEntries, "omittedEntries"));
            omittedEntrySequenceSha256 = Objects.requireNonNull(
                    omittedEntrySequenceSha256, "omittedEntrySequenceSha256");
        }
    }

    private static final class ByteCursor {
        private final byte[] bytes;
        private int index;

        private ByteCursor(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
        }

        private int readVarInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                if (index >= bytes.length) {
                    throw new IllegalArgumentException(
                            "truncated embedded dynamic registry packet header");
                }
                int current = bytes[index++] & 0xFF;
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException(
                    "embedded dynamic registry packet has oversized VarInt");
        }

        private byte[] readBytes(int length) {
            if (length < 0 || index > bytes.length - length) {
                throw new IllegalArgumentException(
                        "truncated embedded dynamic registry packet header");
            }
            byte[] copy = java.util.Arrays.copyOfRange(bytes, index, index + length);
            index += length;
            return copy;
        }
    }
}
