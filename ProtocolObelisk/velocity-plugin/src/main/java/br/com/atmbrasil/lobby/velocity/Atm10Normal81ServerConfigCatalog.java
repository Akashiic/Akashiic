package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeConfigPath;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact NeoForge {@code SERVER} config transaction exported from ATM10 Normal 8.1.
 *
 * <p>The resource is an ordered wire capture, not a namespace-derived guess. Every manifest
 * field, filename, length, content digest, encoded payload digest and aggregate sequence digest
 * is verified before the catalog can be selected. Runtime failure quarantines only this
 * enrichment; cardinal admission and Velocity routing remain independent.</p>
 */
final class Atm10Normal81ServerConfigCatalog {
    static final int PROTOCOL_VERSION = 767;
    static final String CATALOG_ID =
            "atm10-normal-8.1-neoforge-21.1.249-server-configs-v1";
    static final String FULL_CLIENT_CONTRACT_SHA256 =
            Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256;
    static final String SERVER_FILES_SHA256 =
            "259e4a98888ee6ded0b439113c19ac3c79f6e90eeab1a2c465a1c005d0d5f3c4";
    static final int CONFIG_COUNT = 288;
    static final int TOTAL_CONTENT_BYTES = 642_062;
    static final int TOTAL_ENCODED_BYTES = 651_792;
    static final String NAME_SEQUENCE_SHA256 =
            "333cacc99f7dba60cd852804301cc3f48e2b1d78a697cf316bda3eaed6a07127";
    static final String PAYLOAD_SEQUENCE_SHA256 =
            "305e26b71ea175cb8e527d498dd51b61ca6e62951f533bc57f5cdbe6ed410521";
    static final String MANIFEST_SHA256 =
            "f007d0d5d787aabcd1f8cfdce65618a3c6c69481255a6dc7e2c683dd5f19ec9a";

    static final String RESOURCE_ROOT =
            "configuration-profiles/atm10-normal-8.1-neoforge-21.1.249/";
    static final String MANIFEST_RESOURCE = RESOURCE_ROOT + "server-configs.properties";

    private static final int MAXIMUM_MANIFEST_BYTES = 131_072;
    private static final int MAXIMUM_CONFIG_PAYLOAD_BYTES = 65_536;
    private static final int MAXIMUM_TOTAL_PAYLOAD_BYTES = 1_048_576;
    private static final int FIXED_PROPERTY_COUNT = 10;
    private static final int PROPERTIES_PER_CONFIG = 6;
    private static final Pattern PROPERTY_KEY = Pattern.compile("[a-z0-9.-]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> FIXED_PROPERTY_KEYS = Set.of(
            "format-version",
            "pack",
            "minecraft",
            "neoforge",
            "full-client-contract-sha256",
            "config.count",
            "config.total-content-bytes",
            "config.total-encoded-bytes",
            "config.name-sequence-sha256",
            "config.payload-sequence-sha256");

    private Atm10Normal81ServerConfigCatalog() {
    }

    static Catalog catalog() {
        return loadIfPresent(Atm10Normal81ServerConfigCatalog.class.getClassLoader())
                .orElseThrow(() -> new IllegalStateException(
                        CATALOG_ID + " reviewed SERVER-config resources are missing"));
    }

    static Optional<Catalog> catalogIfPresent() {
        return loadIfPresent(Atm10Normal81ServerConfigCatalog.class.getClassLoader());
    }

    static Catalog loadForTest(ClassLoader loader) {
        return loadIfPresent(loader).orElseThrow(() -> new IllegalStateException(
                CATALOG_ID + " reviewed SERVER-config resources are missing"));
    }

    static Optional<Catalog> loadIfPresentForTest(ClassLoader loader) {
        return loadIfPresent(loader);
    }

    /** Runtime quarantine seam: absence or corruption never becomes an admission decision. */
    static RuntimeResolution runtimeResolution() {
        return RuntimeHolder.REVIEWED;
    }

    static RuntimeResolution runtimeResolutionForTest(ClassLoader loader) {
        return captureRuntimeResolution(Objects.requireNonNull(loader, "loader"));
    }

    private static Optional<Catalog> loadIfPresent(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        byte[] manifestBytes;
        try (InputStream manifestStream = loader.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (manifestStream == null) {
                return Optional.empty();
            }
            manifestBytes = manifestStream.readNBytes(MAXIMUM_MANIFEST_BYTES + 1);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest could not be read", exception);
        }
        if (manifestBytes.length > MAXIMUM_MANIFEST_BYTES) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest exceeds byte bound");
        }
        String actualManifestSha256 = sha256(manifestBytes);
        if (!actualManifestSha256.equals(MANIFEST_SHA256)) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest SHA-256 mismatch: "
                            + actualManifestSha256);
        }

        Map<String, String> properties = readCanonicalProperties(manifestBytes);
        validatePropertySet(properties);
        validateFixedProperties(properties);

        ArrayList<Entry> entries = new ArrayList<>(CONFIG_COUNT);
        HashSet<String> names = new HashSet<>(CONFIG_COUNT * 2);
        int totalContentBytes = 0;
        int totalEncodedBytes = 0;
        for (int index = 0; index < CONFIG_COUNT; index++) {
            String key = "config." + index;
            String fileName = requireValue(properties, key + ".name");
            if (!NeoForgeConfigPath.isValid(fileName)) {
                throw new IllegalStateException(
                        CATALOG_ID + " contains an unsafe SERVER-config filename: " + fileName);
            }
            if (!names.add(fileName)) {
                throw new IllegalStateException(
                        CATALOG_ID + " contains a duplicate SERVER-config filename: " + fileName);
            }
            String expectedResourceFile = "server-configs/%03d.bin".formatted(index);
            requireEquals(properties, key + ".file", expectedResourceFile);
            int contentBytes = canonicalInt(
                    properties, key + ".content-bytes", 0, MAXIMUM_CONFIG_PAYLOAD_BYTES);
            int encodedBytes = canonicalInt(
                    properties, key + ".encoded-bytes", 1, MAXIMUM_CONFIG_PAYLOAD_BYTES);
            String contentSha256 = requireSha256(properties, key + ".content-sha256");
            String encodedSha256 = requireSha256(properties, key + ".encoded-sha256");
            byte[] encoded = readPayload(
                    loader, RESOURCE_ROOT + expectedResourceFile, encodedBytes);
            String actualEncodedSha256 = sha256(encoded);
            if (!actualEncodedSha256.equals(encodedSha256)) {
                throw new IllegalStateException(
                        CATALOG_ID + " encoded payload SHA-256 mismatch for " + fileName);
            }
            DecodedPayload decoded = decodePayload(encoded);
            if (!decoded.fileName().equals(fileName)
                    || decoded.contents().length != contentBytes
                    || !sha256(decoded.contents()).equals(contentSha256)) {
                throw new IllegalStateException(
                        CATALOG_ID + " decoded payload identity differs for " + fileName);
            }
            totalContentBytes = Math.addExact(totalContentBytes, contentBytes);
            totalEncodedBytes = Math.addExact(totalEncodedBytes, encodedBytes);
            if (totalEncodedBytes > MAXIMUM_TOTAL_PAYLOAD_BYTES) {
                throw new IllegalStateException(
                        CATALOG_ID + " aggregate SERVER-config payload exceeds byte bound");
            }
            entries.add(new Entry(
                    fileName,
                    contentBytes,
                    contentSha256,
                    encoded,
                    encodedSha256));
        }

        List<String> fileNames = entries.stream().map(Entry::fileName).toList();
        String actualNameSequenceSha256 = nameSequenceSha256(fileNames);
        String actualPayloadSequenceSha256 = payloadSequenceSha256(entries);
        if (totalContentBytes != TOTAL_CONTENT_BYTES
                || totalEncodedBytes != TOTAL_ENCODED_BYTES
                || !actualNameSequenceSha256.equals(NAME_SEQUENCE_SHA256)
                || !actualPayloadSequenceSha256.equals(PAYLOAD_SEQUENCE_SHA256)) {
            throw new IllegalStateException(
                    CATALOG_ID + " aggregate SERVER-config evidence differs from pins");
        }
        return Optional.of(new Catalog(
                CATALOG_ID,
                entries,
                totalContentBytes,
                totalEncodedBytes,
                actualNameSequenceSha256,
                actualPayloadSequenceSha256));
    }

    private static Map<String, String> readCanonicalProperties(byte[] encoded) {
        for (byte value : encoded) {
            int unsigned = value & 0xFF;
            if (unsigned == '\r' || unsigned > 0x7F) {
                throw new IllegalStateException(
                        CATALOG_ID + " SERVER-config manifest is not canonical ASCII");
            }
        }
        String text = new String(encoded, StandardCharsets.US_ASCII);
        if (text.isEmpty() || !text.endsWith("\n")) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest lacks a final newline");
        }
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (line.isEmpty()
                    || line.startsWith("#")
                    || line.startsWith("!")
                    || separator < 1
                    || separator == line.length() - 1
                    || line.indexOf('=', separator + 1) >= 0) {
                throw new IllegalStateException(
                        CATALOG_ID + " SERVER-config manifest contains a malformed line");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!PROPERTY_KEY.matcher(key).matches()
                    || value.chars().anyMatch(character -> character <= 0x20)) {
                throw new IllegalStateException(
                        CATALOG_ID + " SERVER-config manifest contains a non-canonical value");
            }
            if (properties.putIfAbsent(key, value) != null) {
                throw new IllegalStateException(
                        CATALOG_ID + " SERVER-config manifest property is duplicated: " + key);
            }
        }
        return Map.copyOf(properties);
    }

    private static void validatePropertySet(Map<String, String> properties) {
        LinkedHashSet<String> expected = new LinkedHashSet<>(FIXED_PROPERTY_KEYS);
        for (int index = 0; index < CONFIG_COUNT; index++) {
            String key = "config." + index;
            expected.add(key + ".name");
            expected.add(key + ".file");
            expected.add(key + ".content-bytes");
            expected.add(key + ".content-sha256");
            expected.add(key + ".encoded-bytes");
            expected.add(key + ".encoded-sha256");
        }
        int expectedCount = FIXED_PROPERTY_COUNT + CONFIG_COUNT * PROPERTIES_PER_CONFIG;
        if (expected.size() != expectedCount || !properties.keySet().equals(expected)) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest property set differs from contract");
        }
    }

    private static void validateFixedProperties(Map<String, String> properties) {
        requireEquals(properties, "format-version", "2");
        requireEquals(properties, "pack", "ATM10-8.1");
        requireEquals(properties, "minecraft", "1.21.1");
        requireEquals(properties, "neoforge", "21.1.249");
        requireEquals(
                properties, "full-client-contract-sha256", FULL_CLIENT_CONTRACT_SHA256);
        requirePinnedInt(properties, "config.count", 1, 1_024, CONFIG_COUNT);
        requirePinnedInt(
                properties,
                "config.total-content-bytes",
                1,
                MAXIMUM_TOTAL_PAYLOAD_BYTES,
                TOTAL_CONTENT_BYTES);
        requirePinnedInt(
                properties,
                "config.total-encoded-bytes",
                1,
                MAXIMUM_TOTAL_PAYLOAD_BYTES,
                TOTAL_ENCODED_BYTES);
        requireEquals(properties, "config.name-sequence-sha256", NAME_SEQUENCE_SHA256);
        requireEquals(
                properties, "config.payload-sequence-sha256", PAYLOAD_SEQUENCE_SHA256);
        requireSha256(properties, "full-client-contract-sha256");
        requireSha256(properties, "config.name-sequence-sha256");
        requireSha256(properties, "config.payload-sequence-sha256");
    }

    private static byte[] readPayload(
            ClassLoader loader, String resource, int expectedBytes) {
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        CATALOG_ID + " SERVER-config payload is missing: " + resource);
            }
            byte[] payload = stream.readNBytes(MAXIMUM_CONFIG_PAYLOAD_BYTES + 1);
            if (payload.length != expectedBytes) {
                throw new IllegalStateException(
                        CATALOG_ID + " SERVER-config payload length mismatch: " + resource);
            }
            return payload;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config payload could not be read: " + resource,
                    exception);
        }
    }

    private static DecodedPayload decodePayload(byte[] encoded) {
        Cursor cursor = new Cursor(encoded);
        int fileNameBytes = cursor.readCanonicalVarInt();
        if (fileNameBytes < 1 || fileNameBytes > NeoForgeConfigPath.MAXIMUM_UTF8_BYTES) {
            throw new IllegalStateException(
                    CATALOG_ID + " encoded SERVER-config filename length is invalid");
        }
        String fileName = decodeUtf8(cursor.readBytes(fileNameBytes));
        int contentBytes = cursor.readCanonicalVarInt();
        if (contentBytes < 0 || contentBytes != cursor.remaining()) {
            throw new IllegalStateException(
                    CATALOG_ID + " encoded SERVER-config content length is invalid");
        }
        byte[] contents = cursor.readBytes(contentBytes);
        if (cursor.remaining() != 0) {
            throw new IllegalStateException(
                    CATALOG_ID + " encoded SERVER-config has trailing bytes");
        }
        return new DecodedPayload(fileName, contents);
    }

    private static String decodeUtf8(byte[] encoded) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException(
                    CATALOG_ID + " encoded SERVER-config filename is not UTF-8", exception);
        }
    }

    private static String requireValue(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest value is missing: " + key);
        }
        return value;
    }

    private static void requireEquals(
            Map<String, String> properties, String key, String expected) {
        String actual = properties.get(key);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest mismatch for " + key
                            + ": " + actual);
        }
    }

    private static int canonicalInt(
            Map<String, String> properties, String key, int minimum, int maximum) {
        String value = properties.get(key);
        if (value == null
                || value.isEmpty()
                || (value.length() > 1 && value.charAt(0) == '0')
                || value.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest integer is not canonical: " + key);
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest integer is outside bounds: " + key,
                    exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest integer is outside bounds: " + key);
        }
        return parsed;
    }

    private static int requirePinnedInt(
            Map<String, String> properties,
            String key,
            int minimum,
            int maximum,
            int expected) {
        int actual = canonicalInt(properties, key, minimum, maximum);
        if (actual != expected) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config manifest differs from pin for " + key);
        }
        return actual;
    }

    private static String requireSha256(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalStateException(
                    CATALOG_ID + " SERVER-config SHA-256 is invalid: " + key);
        }
        return value;
    }

    static String nameSequenceSha256(List<String> fileNames) {
        Objects.requireNonNull(fileNames, "fileNames");
        MessageDigest digest = newSha256();
        for (String fileName : fileNames) {
            byte[] name = Objects.requireNonNull(fileName, "fileName")
                    .getBytes(StandardCharsets.UTF_8);
            updateInt(digest, name.length);
            digest.update(name);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String payloadSequenceSha256(List<Entry> entries) {
        MessageDigest digest = newSha256();
        for (Entry entry : entries) {
            digest.update(entry.encodedPayloadInternal());
        }
        return HexFormat.of().formatHex(digest.digest());
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

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    record Entry(
            String fileName,
            int contentBytes,
            String contentSha256,
            byte[] encodedPayload,
            String encodedSha256) {
        Entry {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(contentSha256, "contentSha256");
            encodedPayload = Objects.requireNonNull(encodedPayload, "encodedPayload").clone();
            Objects.requireNonNull(encodedSha256, "encodedSha256");
            if (!NeoForgeConfigPath.isValid(fileName)
                    || contentBytes < 0
                    || encodedPayload.length < 1
                    || !SHA256.matcher(contentSha256).matches()
                    || !SHA256.matcher(encodedSha256).matches()) {
                throw new IllegalArgumentException("invalid SERVER-config catalog entry");
            }
        }

        @Override
        public byte[] encodedPayload() {
            return encodedPayload.clone();
        }

        private byte[] encodedPayloadInternal() {
            return encodedPayload;
        }

        int encodedBytes() {
            return encodedPayload.length;
        }
    }

    record Catalog(
            String id,
            List<Entry> entries,
            int totalContentBytes,
            int totalEncodedBytes,
            String nameSequenceSha256,
            String payloadSequenceSha256) {
        Catalog {
            id = Objects.requireNonNull(id, "id");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            nameSequenceSha256 = Objects.requireNonNull(
                    nameSequenceSha256, "nameSequenceSha256");
            payloadSequenceSha256 = Objects.requireNonNull(
                    payloadSequenceSha256, "payloadSequenceSha256");
            List<String> names = entries.stream().map(Entry::fileName).toList();
            if (!id.equals(CATALOG_ID)
                    || entries.size() != CONFIG_COUNT
                    || totalContentBytes != TOTAL_CONTENT_BYTES
                    || totalEncodedBytes != TOTAL_ENCODED_BYTES
                    || !nameSequenceSha256.equals(NAME_SEQUENCE_SHA256)
                    || !payloadSequenceSha256.equals(PAYLOAD_SEQUENCE_SHA256)
                    || !Atm10Normal81ServerConfigCatalog.nameSequenceSha256(names)
                            .equals(NAME_SEQUENCE_SHA256)
                    || new HashSet<>(names).size() != names.size()
                    || entries.stream().mapToInt(Entry::contentBytes).sum()
                            != totalContentBytes
                    || entries.stream().mapToInt(Entry::encodedBytes).sum()
                            != totalEncodedBytes) {
                throw new IllegalArgumentException(
                        "SERVER-config catalog differs from exact ATM10 8.1 pins");
            }
        }

        List<String> fileNames() {
            return entries.stream().map(Entry::fileName).toList();
        }
    }

    record RuntimeResolution(
            Optional<Catalog> catalog,
            Optional<Throwable> quarantineFailure) {
        RuntimeResolution {
            catalog = Objects.requireNonNull(catalog, "catalog");
            quarantineFailure = Objects.requireNonNull(
                    quarantineFailure, "quarantineFailure");
            if (catalog.isPresent() && quarantineFailure.isPresent()) {
                throw new IllegalArgumentException(
                        "runtime SERVER-config resolution cannot be present and quarantined");
            }
        }
    }

    private static RuntimeResolution captureRuntimeResolution(ClassLoader loader) {
        try {
            Optional<Catalog> catalog = loadIfPresent(loader);
            return catalog.isPresent()
                    ? new RuntimeResolution(catalog, Optional.empty())
                    : new RuntimeResolution(
                            Optional.empty(),
                            Optional.of(new IllegalStateException(
                                    CATALOG_ID
                                            + " reviewed SERVER-config resources are missing")));
        } catch (RuntimeException | LinkageError failure) {
            return new RuntimeResolution(Optional.empty(), Optional.of(failure));
        }
    }

    private static final class RuntimeHolder {
        private static final RuntimeResolution REVIEWED = captureRuntimeResolution(
                Atm10Normal81ServerConfigCatalog.class.getClassLoader());

        private RuntimeHolder() {
        }
    }

    private record DecodedPayload(String fileName, byte[] contents) {
        private DecodedPayload {
            Objects.requireNonNull(fileName, "fileName");
            contents = Objects.requireNonNull(contents, "contents").clone();
        }

        @Override
        public byte[] contents() {
            return contents.clone();
        }
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int index;

        private Cursor(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
        }

        private int readCanonicalVarInt() {
            int value = 0;
            int start = index;
            for (int part = 0; part < 5; part++) {
                if (index >= bytes.length) {
                    throw new IllegalStateException(
                            CATALOG_ID + " encoded SERVER-config VarInt is truncated");
                }
                int current = bytes[index++] & 0xFF;
                value |= (current & 0x7F) << (part * 7);
                if ((current & 0x80) == 0) {
                    if (value < 0 || index - start != canonicalVarIntBytes(value)) {
                        throw new IllegalStateException(
                                CATALOG_ID + " encoded SERVER-config VarInt is non-canonical");
                    }
                    return value;
                }
            }
            throw new IllegalStateException(
                    CATALOG_ID + " encoded SERVER-config VarInt exceeds five bytes");
        }

        private byte[] readBytes(int length) {
            if (length < 0 || length > remaining()) {
                throw new IllegalStateException(
                        CATALOG_ID + " encoded SERVER-config payload is truncated");
            }
            byte[] result = java.util.Arrays.copyOfRange(bytes, index, index + length);
            index += length;
            return result;
        }

        private int remaining() {
            return bytes.length - index;
        }

        private static int canonicalVarIntBytes(int value) {
            int bytes = 1;
            int remaining = value;
            while ((remaining & ~0x7F) != 0) {
                bytes++;
                remaining >>>= 7;
            }
            return bytes;
        }
    }
}
