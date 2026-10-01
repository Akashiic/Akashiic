package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Reviewed, profile-specific NeoForge frozen-registry transaction bundled in the JAR. */
final class NeoForgeFrozenRegistryProfile {
    static final String CONFIG_FILE_CHANNEL = "neoforge:config_file";
    static final String START_CHANNEL = "neoforge:frozen_registry_sync_start";
    static final String REGISTRY_CHANNEL = "neoforge:frozen_registry";
    static final String COMPLETED_CHANNEL = "neoforge:frozen_registry_sync_completed";

    private static final int MAXIMUM_REGISTRY_COUNT = 128;
    private static final int MAXIMUM_REGISTRY_ENTRIES = 200_000;
    private static final int MAXIMUM_RESOURCE_LOCATION_BYTES = 512;
    private static final int MAXIMUM_START_BYTES = 16_384;
    private static final int MAXIMUM_REGISTRY_PAYLOAD_BYTES = 4_194_304;
    private static final int MAXIMUM_TOTAL_REGISTRY_BYTES = 8_388_608;
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final List<Channel> REVIEWED_CONFIGURATION_CHANNELS = List.of(
            new Channel(CONFIG_FILE_CHANNEL, "1", Flow.CLIENTBOUND, true),
            new Channel(START_CHANNEL, "1", Flow.CLIENTBOUND, true),
            new Channel(REGISTRY_CHANNEL, "1", Flow.CLIENTBOUND, true),
            new Channel(COMPLETED_CHANNEL, "1", Flow.BIDIRECTIONAL, true));

    private final byte[] startPayload;
    private final List<RegistryPayload> registryPayloads;
    private final byte[] completedPayload;
    private final int totalEntries;
    private final int totalRegistryBytes;
    private final String sequenceSha256;

    private NeoForgeFrozenRegistryProfile(
            byte[] startPayload,
            List<RegistryPayload> registryPayloads,
            byte[] completedPayload,
            int totalEntries,
            int totalRegistryBytes,
            String sequenceSha256) {
        this.startPayload = Objects.requireNonNull(startPayload, "startPayload").clone();
        this.registryPayloads = List.copyOf(registryPayloads);
        this.completedPayload = Objects.requireNonNull(
                completedPayload, "completedPayload").clone();
        this.totalEntries = totalEntries;
        this.totalRegistryBytes = totalRegistryBytes;
        this.sequenceSha256 = Objects.requireNonNull(sequenceSha256, "sequenceSha256");
    }

    static NeoForgeFrozenRegistryProfile loadReviewed(
            ClassLoader loader, Properties manifest, String resourceRoot) throws IOException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(resourceRoot, "resourceRoot");

        int registryCount = positiveInt(manifest, "frozen-registry.count");
        if (registryCount > MAXIMUM_REGISTRY_COUNT) {
            throw new IllegalArgumentException("embedded frozen registry count exceeds bound");
        }

        String startFile = require(manifest, "frozen-registry-start.file");
        int declaredStartBytes = positiveInt(manifest, "frozen-registry-start.bytes");
        byte[] start = readBounded(
                loader, resourceRoot + startFile, MAXIMUM_START_BYTES);
        requireLengthAndHash(
                manifest, "frozen-registry-start.", START_CHANNEL, start, declaredStartBytes);

        MessageDigest sequenceDigest = newSha256();
        sequenceDigest.update(start);
        List<String> declaredNames = new ArrayList<>(registryCount);
        List<RegistryPayload> payloads = new ArrayList<>(registryCount);
        Set<String> uniqueNames = new HashSet<>();
        int totalEntries = 0;
        int totalBytes = 0;
        String previousName = null;
        for (int index = 0; index < registryCount; index++) {
            String key = "frozen-registry." + index + '.';
            String name = requireResourceLocation(manifest, key + "name");
            if (!uniqueNames.add(name)) {
                throw new IllegalArgumentException("duplicate embedded frozen registry " + name);
            }
            if (previousName != null && previousName.compareTo(name) >= 0) {
                throw new IllegalArgumentException(
                        "embedded frozen registries are not in canonical order");
            }
            previousName = name;
            declaredNames.add(name);

            int entries = nonNegativeInt(manifest, key + "entries");
            int aliases = nonNegativeInt(manifest, key + "aliases");
            if (entries > MAXIMUM_REGISTRY_ENTRIES || aliases > MAXIMUM_REGISTRY_ENTRIES) {
                throw new IllegalArgumentException(
                        "embedded frozen registry entry count exceeds bound: " + name);
            }
            int declaredBytes = positiveInt(manifest, key + "bytes");
            byte[] bytes = readBounded(
                    loader,
                    resourceRoot + require(manifest, key + "file"),
                    MAXIMUM_REGISTRY_PAYLOAD_BYTES);
            requireLengthAndHash(manifest, key, name, bytes, declaredBytes);
            SnapshotShape shape = validateRegistryPayload(bytes);
            if (!shape.registryName().equals(name)
                    || shape.entries() != entries
                    || shape.aliases() != aliases) {
                throw new IllegalArgumentException(
                        "embedded frozen registry shape mismatch: " + name);
            }

            totalEntries = Math.addExact(totalEntries, entries);
            totalBytes = Math.addExact(totalBytes, bytes.length);
            if (totalBytes > MAXIMUM_TOTAL_REGISTRY_BYTES) {
                throw new IllegalArgumentException(
                        "embedded frozen registry transaction exceeds total byte bound");
            }
            sequenceDigest.update(bytes);
            payloads.add(new RegistryPayload(
                    name,
                    entries,
                    aliases,
                    bytes,
                    requireSha256(manifest, key + "sha256")));
        }

        validateStartPayload(start, declaredNames);
        if (totalEntries != positiveInt(manifest, "frozen-registry.total-entries")) {
            throw new IllegalArgumentException("embedded frozen registry entry total mismatch");
        }
        if (totalBytes != positiveInt(manifest, "frozen-registry.total-bytes")) {
            throw new IllegalArgumentException("embedded frozen registry byte total mismatch");
        }

        String completedFile = require(manifest, "frozen-registry-completed.file");
        int declaredCompletedBytes = nonNegativeInt(
                manifest, "frozen-registry-completed.bytes");
        byte[] completed = readBounded(loader, resourceRoot + completedFile, 1);
        requireLengthAndHash(
                manifest,
                "frozen-registry-completed.",
                COMPLETED_CHANNEL,
                completed,
                declaredCompletedBytes);
        if (completed.length != 0) {
            throw new IllegalArgumentException(
                    "embedded frozen registry completed payload must be empty");
        }
        sequenceDigest.update(completed);

        String sequenceSha256 = requireSha256(
                manifest, "frozen-registry.sequence-sha256");
        String actualSequenceSha256 = HexFormat.of().formatHex(sequenceDigest.digest());
        if (!actualSequenceSha256.equals(sequenceSha256)) {
            throw new IllegalArgumentException(
                    "embedded frozen registry sequence hash mismatch");
        }

        return new NeoForgeFrozenRegistryProfile(
                start,
                payloads,
                completed,
                totalEntries,
                totalBytes,
                sequenceSha256);
    }

    static List<Channel> reviewedConfigurationChannels() {
        return REVIEWED_CONFIGURATION_CHANNELS;
    }

    static Compatibility inspectConfigurationChannels(List<Channel> advertisedChannels) {
        Objects.requireNonNull(advertisedChannels, "advertisedChannels");
        Map<String, Channel> byId = new LinkedHashMap<>();
        advertisedChannels.forEach(channel -> byId.putIfAbsent(channel.id(), channel));
        for (Channel expected : REVIEWED_CONFIGURATION_CHANNELS) {
            Channel actual = byId.get(expected.id());
            if (actual == null) {
                return new Compatibility(false, "missing " + expected.id());
            }
            if (!actual.version().equals(expected.version())) {
                return new Compatibility(false, "version mismatch on " + expected.id());
            }
            if (actual.flow() != expected.flow()) {
                return new Compatibility(false, "flow mismatch on " + expected.id());
            }
            if (actual.optional() != expected.optional()) {
                return new Compatibility(false, "optional flag mismatch on " + expected.id());
            }
        }
        return new Compatibility(true, "");
    }

    byte[] startPayload() {
        return startPayload.clone();
    }

    List<RegistryPayload> registryPayloads() {
        return registryPayloads;
    }

    byte[] completedPayload() {
        return completedPayload.clone();
    }

    int registryCount() {
        return registryPayloads.size();
    }

    int totalEntries() {
        return totalEntries;
    }

    int totalRegistryBytes() {
        return totalRegistryBytes;
    }

    int totalTransactionBytes() {
        return Math.addExact(
                Math.addExact(startPayload.length, totalRegistryBytes),
                completedPayload.length);
    }

    int maximumPayloadBytes() {
        int maximum = Math.max(startPayload.length, completedPayload.length);
        for (RegistryPayload payload : registryPayloads) {
            maximum = Math.max(maximum, payload.byteLength());
        }
        return maximum;
    }

    String sequenceSha256() {
        return sequenceSha256;
    }

    private static void validateStartPayload(byte[] bytes, List<String> expectedNames) {
        Reader reader = new Reader(bytes);
        int count = reader.readCount(MAXIMUM_REGISTRY_COUNT, "frozen registry start count");
        List<String> actualNames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            actualNames.add(reader.readResourceLocation());
        }
        reader.requireFullyConsumed();
        if (!actualNames.equals(expectedNames)) {
            throw new IllegalArgumentException(
                    "embedded frozen registry start list does not match payload order");
        }
    }

    private static SnapshotShape validateRegistryPayload(byte[] bytes) {
        Reader reader = new Reader(bytes);
        String registryName = reader.readResourceLocation();
        int entries = reader.readCount(MAXIMUM_REGISTRY_ENTRIES, "registry entry count");
        int previousId = -1;
        Set<Integer> uniqueIds = new HashSet<>(Math.min(entries, 4_096));
        Set<String> uniqueValues = new HashSet<>(Math.min(entries, 4_096));
        for (int index = 0; index < entries; index++) {
            int id = reader.readNonNegativeVarInt("registry entry id");
            if (id <= previousId || !uniqueIds.add(id)) {
                throw new IllegalArgumentException(
                        "embedded frozen registry ids are not strictly increasing");
            }
            previousId = id;
            if (!uniqueValues.add(reader.readResourceLocation())) {
                throw new IllegalArgumentException(
                        "embedded frozen registry contains a duplicate value");
            }
        }
        int aliases = reader.readCount(MAXIMUM_REGISTRY_ENTRIES, "registry alias count");
        Set<String> aliasKeys = new HashSet<>(Math.min(aliases, 4_096));
        for (int index = 0; index < aliases; index++) {
            if (!aliasKeys.add(reader.readResourceLocation())) {
                throw new IllegalArgumentException(
                        "embedded frozen registry contains a duplicate alias");
            }
            reader.readResourceLocation();
        }
        reader.requireFullyConsumed();
        return new SnapshotShape(registryName, entries, aliases);
    }

    private static void requireLengthAndHash(
            Properties manifest,
            String keyPrefix,
            String description,
            byte[] bytes,
            int declaredBytes) {
        if (bytes.length != declaredBytes) {
            throw new IllegalArgumentException(
                    "embedded payload length mismatch: " + description);
        }
        String expectedSha256 = requireSha256(manifest, keyPrefix + "sha256");
        String actualSha256 = sha256(bytes);
        if (!actualSha256.equals(expectedSha256)) {
            throw new IllegalArgumentException(
                    "embedded payload hash mismatch: " + description);
        }
    }

    private static byte[] readBounded(
            ClassLoader loader, String resource, int maximumBytes) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing embedded frozen registry resource " + resource);
            }
            byte[] bytes = stream.readNBytes(Math.addExact(maximumBytes, 1));
            if (bytes.length > maximumBytes) {
                throw new IllegalArgumentException(
                        "embedded frozen registry resource exceeds byte bound: " + resource);
            }
            return bytes;
        }
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("embedded profile manifest is missing " + key);
        }
        return value.strip();
    }

    private static String requireResourceLocation(Properties properties, String key) {
        String value = require(properties, key);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_RESOURCE_LOCATION_BYTES
                || !RESOURCE_LOCATION.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "embedded profile manifest has invalid resource location " + key);
        }
        return value;
    }

    private static int positiveInt(Properties properties, String key) {
        int value = nonNegativeInt(properties, key);
        if (value == 0) {
            throw new IllegalArgumentException(
                    "embedded profile manifest integer must be positive: " + key);
        }
        return value;
    }

    private static int nonNegativeInt(Properties properties, String key) {
        final int value;
        try {
            value = Integer.parseInt(require(properties, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "embedded profile manifest has invalid integer " + key, exception);
        }
        if (value < 0) {
            throw new IllegalArgumentException(
                    "embedded profile manifest integer must not be negative: " + key);
        }
        return value;
    }

    private static String requireSha256(Properties properties, String key) {
        String value = require(properties, key);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "embedded profile manifest has invalid SHA-256: " + key);
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    record RegistryPayload(
            String registryName,
            int entryCount,
            int aliasCount,
            byte[] bytes,
            String sha256) {
        RegistryPayload {
            Objects.requireNonNull(registryName, "registryName");
            Objects.requireNonNull(sha256, "sha256");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        int byteLength() {
            return bytes.length;
        }
    }

    record Compatibility(boolean exact, String rejectionReason) {
        Compatibility {
            Objects.requireNonNull(rejectionReason, "rejectionReason");
        }
    }

    private record SnapshotShape(String registryName, int entries, int aliases) {
    }

    private static final class Reader {
        private final byte[] bytes;
        private int index;

        private Reader(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
        }

        private int readCount(int maximum, String field) {
            int value = readNonNegativeVarInt(field);
            if (value > maximum) {
                throw new IllegalArgumentException(field + " exceeds bound");
            }
            return value;
        }

        private int readNonNegativeVarInt(String field) {
            int value = readVarInt(field);
            if (value < 0) {
                throw new IllegalArgumentException(field + " must not be negative");
            }
            return value;
        }

        private int readVarInt(String field) {
            int value = 0;
            for (int position = 0; position < 5; position++) {
                if (index >= bytes.length) {
                    throw new IllegalArgumentException("truncated " + field);
                }
                int current = bytes[index++] & 0xFF;
                value |= (current & 0x7F) << (position * 7);
                if ((current & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("oversized VarInt in " + field);
        }

        private String readResourceLocation() {
            int length = readCount(MAXIMUM_RESOURCE_LOCATION_BYTES, "resource location length");
            if (length == 0 || length > bytes.length - index) {
                throw new IllegalArgumentException("truncated resource location");
            }
            String value = decodeUtf8(bytes, index, length);
            index += length;
            if (!RESOURCE_LOCATION.matcher(value).matches()) {
                throw new IllegalArgumentException("invalid resource location");
            }
            return value;
        }

        private void requireFullyConsumed() {
            if (index != bytes.length) {
                throw new IllegalArgumentException("trailing frozen registry payload bytes");
            }
        }

        private static String decodeUtf8(byte[] bytes, int offset, int length) {
            try {
                CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, offset, length));
                return decoded.toString();
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("invalid UTF-8 resource location", exception);
            }
        }
    }
}
