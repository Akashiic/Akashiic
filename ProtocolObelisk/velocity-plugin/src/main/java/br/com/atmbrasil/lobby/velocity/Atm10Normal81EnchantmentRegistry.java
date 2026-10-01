package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Inspection;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact, full {@code minecraft:enchantment} transaction exported from ATM10 Normal 8.1. */
final class Atm10Normal81EnchantmentRegistry {
    static final int PROTOCOL_VERSION = 767;
    static final String SHIM_ID = "minecraft-enchantment-atm10-8.1";
    static final String REQUIRED_NAMESPACE = "minecraft";
    static final String REGISTRY_ID = "minecraft:enchantment";
    static final String FULL_CLIENT_CONTRACT_SHA256 =
            Atm10Normal81IronsSpellbooksRegistry.FULL_CLIENT_CONTRACT_SHA256;
    static final List<String> REQUIRED_SENTINEL_ENTRY_IDS = List.of(
            "evilcraft:vengeance",
            "undergarden:ricochet",
            "quarryplus:quarry_pickaxe");
    static final int PACKET_SEQUENCE_INDEX = 9;
    static final int ENTRY_COUNT = 139;
    static final int ENTRIES_WITH_DATA = 100;
    static final int KNOWN_PACK_PLACEHOLDERS = 39;
    static final int PACKET_BYTES = 63_657;
    static final String ENTRY_SEQUENCE_SHA256 =
            "c21bec7f94e27e27b883730bd2496c1e6f4f4f92560c599332246d6a3e4c0454";
    static final String PACKET_SHA256 =
            "e50135f0a0d111014c81d170be0ce7b2590de1415650012540d5dd49e4c30d77";

    static final String RESOURCE_ROOT =
            "configuration-profiles/atm10-normal-8.1-neoforge-21.1.249/"
                    + "dynamic-registries/";
    static final String PACKET_RESOURCE = RESOURCE_ROOT + "minecraft_enchantment.bin";
    static final String PROPERTIES_RESOURCE =
            RESOURCE_ROOT + "minecraft_enchantment.properties";

    private static final int MAXIMUM_PROPERTIES_BYTES = 8_192;
    private static final int MAXIMUM_PACKET_BYTES = 1_048_576;
    private static final Pattern PROPERTY_KEY = Pattern.compile("[a-z0-9.-]+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> EXACT_PROPERTY_KEYS = Set.of(
            "format-version",
            "pack",
            "minecraft",
            "minecraft-protocol",
            "neoforge",
            "curseforge-server-file-id",
            "server-files-sha256",
            "full-client-contract-sha256",
            "source-variant",
            "packet-sequence-index",
            "registry-id",
            "entry-count",
            "entries-with-data",
            "known-pack-placeholders",
            "entry-sequence-sha256",
            "packet-bytes",
            "packet-sha256",
            "sentinel-entries");

    private Atm10Normal81EnchantmentRegistry() {
    }

    /** Loads the reviewed resource, failing closed when it is absent or invalid. */
    static RegistryShimPacket packet(int configuredMaximumBytes) {
        return loadIfPresent(
                        Atm10Normal81EnchantmentRegistry.class.getClassLoader(),
                        configuredMaximumBytes)
                .orElseThrow(() -> new IllegalStateException(
                        SHIM_ID + " reviewed registry resources are missing"));
    }

    /**
     * Returns no packet only when both reviewed resources are absent.
     *
     * <p>A partial, malformed or unverified resource pair is never treated as absence.</p>
     */
    static Optional<RegistryShimPacket> packetIfPresent(int configuredMaximumBytes) {
        return loadIfPresent(
                Atm10Normal81EnchantmentRegistry.class.getClassLoader(),
                configuredMaximumBytes);
    }

    /** Test seam which applies every production validation to an isolated resource loader. */
    static RegistryShimPacket loadForTest(
            ClassLoader loader, int configuredMaximumBytes) {
        return loadIfPresent(loader, configuredMaximumBytes)
                .orElseThrow(() -> new IllegalStateException(
                        SHIM_ID + " reviewed registry resources are missing"));
    }

    /** Test seam for the deliberate both-resources-absent case. */
    static Optional<RegistryShimPacket> loadIfPresentForTest(
            ClassLoader loader, int configuredMaximumBytes) {
        return loadIfPresent(loader, configuredMaximumBytes);
    }

    /** Exact receipt metadata for recipe release, absent until the resource is packaged. */
    static Optional<RegistryShimReceipt> expectedReceiptIfPresent() {
        return runtimeResolution(MAXIMUM_PACKET_BYTES)
                .packet()
                .map(RegistryShimReceipt::from);
    }

    /**
     * Cardinal runtime seam: invalid enrichment is quarantined instead of disabling admission.
     *
     * <p>Release/build audits use {@link #packet(int)} and therefore still fail closed. Runtime
     * catalog callers retain the failure for diagnostics while omitting only this enrichment.</p>
     */
    static RuntimeResolution runtimeResolution(int configuredMaximumBytes) {
        if (configuredMaximumBytes < 1 || configuredMaximumBytes > MAXIMUM_PACKET_BYTES) {
            return new RuntimeResolution(
                    Optional.empty(),
                    Optional.of(new IllegalArgumentException(
                            "maximum-registry-shim-bytes is outside bounds")));
        }
        RuntimeResolution reviewed = RuntimeHolder.REVIEWED;
        if (reviewed.packet().isPresent()
                && reviewed.packet().orElseThrow().packetBytes() > configuredMaximumBytes) {
            RegistryShimPacket packet = reviewed.packet().orElseThrow();
            return new RuntimeResolution(
                    Optional.empty(),
                    Optional.of(new IllegalArgumentException(
                            SHIM_ID + " requires " + packet.packetBytes()
                                    + " bytes but maximum-registry-shim-bytes is "
                                    + configuredMaximumBytes)));
        }
        return reviewed;
    }

    /** Test seam for runtime quarantine semantics with an isolated resource loader. */
    static RuntimeResolution runtimeResolutionForTest(ClassLoader loader) {
        return captureRuntimeResolution(Objects.requireNonNull(loader, "loader"));
    }

    private static Optional<RegistryShimPacket> loadIfPresent(
            ClassLoader loader, int configuredMaximumBytes) {
        Objects.requireNonNull(loader, "loader");
        if (configuredMaximumBytes < 1 || configuredMaximumBytes > MAXIMUM_PACKET_BYTES) {
            throw new IllegalArgumentException(
                    "maximum-registry-shim-bytes is outside bounds");
        }

        try (InputStream propertiesStream = loader.getResourceAsStream(PROPERTIES_RESOURCE);
                InputStream packetStream = loader.getResourceAsStream(PACKET_RESOURCE)) {
            if (propertiesStream == null && packetStream == null) {
                return Optional.empty();
            }
            if (propertiesStream == null || packetStream == null) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry resource pair is incomplete");
            }

            Map<String, String> properties = readCanonicalProperties(propertiesStream);
            validateFixedProperties(properties);
            int entryCount = requirePinnedInt(
                    properties, "entry-count", 1, 256, ENTRY_COUNT);
            int entriesWithData = requirePinnedInt(
                    properties,
                    "entries-with-data",
                    1,
                    entryCount,
                    ENTRIES_WITH_DATA);
            int knownPackPlaceholders = requirePinnedInt(
                    properties,
                    "known-pack-placeholders",
                    1,
                    entryCount - 1,
                    KNOWN_PACK_PLACEHOLDERS);
            if (Math.addExact(entriesWithData, knownPackPlaceholders) != entryCount) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry data-presence counts do not sum");
            }
            int packetBytes = requirePinnedInt(
                    properties,
                    "packet-bytes",
                    1,
                    MAXIMUM_PACKET_BYTES,
                    PACKET_BYTES);
            requirePinnedInt(
                    properties,
                    "packet-sequence-index",
                    0,
                    255,
                    PACKET_SEQUENCE_INDEX);
            if (packetBytes > configuredMaximumBytes) {
                throw new IllegalArgumentException(
                        SHIM_ID + " requires " + packetBytes
                                + " bytes but maximum-registry-shim-bytes is "
                                + configuredMaximumBytes);
            }

            byte[] packetBody = packetStream.readNBytes(
                    Math.addExact(configuredMaximumBytes, 1));
            if (packetBody.length != packetBytes) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry length mismatch: "
                                + packetBody.length);
            }
            String declaredSha256 = requireSha256(properties, "packet-sha256");
            String actualSha256 = sha256(packetBody);
            if (!actualSha256.equals(PACKET_SHA256)
                    || !actualSha256.equals(declaredSha256)) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry SHA-256 mismatch: "
                                + actualSha256);
            }

            Inspection inspection;
            try {
                inspection = MinecraftRegistryPacketCodec.inspect(
                        packetBody, configuredMaximumBytes);
            } catch (ProtocolViolationException exception) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry packet is structurally invalid: "
                                + exception.getMessage(),
                        exception);
            }
            if (!inspection.registryId().equals(REGISTRY_ID)) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry id mismatch: "
                                + inspection.registryId());
            }
            if (inspection.entryIds().size() != entryCount
                    || inspection.entriesWithData() != entriesWithData) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry entry-count/data mismatch");
            }
            if (inspection.packetBytes() != packetBytes) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry inspected byte-count mismatch");
            }
            String actualEntrySequenceSha256 = entrySequenceSha256(inspection);
            if (!actualEntrySequenceSha256.equals(ENTRY_SEQUENCE_SHA256)
                    || !actualEntrySequenceSha256.equals(
                            requireSha256(properties, "entry-sequence-sha256"))) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry entry sequence SHA-256 mismatch");
            }
            if (!inspection.entryIdsWithData().containsAll(REQUIRED_SENTINEL_ENTRY_IDS)) {
                throw new IllegalStateException(
                        SHIM_ID
                                + " reviewed registry is missing data for a required sentinel");
            }

            return Optional.of(new RegistryShimPacket(
                    SHIM_ID,
                    REQUIRED_NAMESPACE,
                    REGISTRY_ID,
                    entryCount,
                    packetBody,
                    actualSha256));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry resources could not be read", exception);
        }
    }

    private static Map<String, String> readCanonicalProperties(InputStream stream)
            throws IOException {
        byte[] encoded = stream.readNBytes(MAXIMUM_PROPERTIES_BYTES + 1);
        if (encoded.length > MAXIMUM_PROPERTIES_BYTES) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry properties exceed byte bound");
        }
        for (byte value : encoded) {
            int unsigned = value & 0xFF;
            if (unsigned == '\r' || unsigned > 0x7F) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry properties are not canonical ASCII");
            }
        }

        String text = new String(encoded, StandardCharsets.US_ASCII);
        if (text.isEmpty() || !text.endsWith("\n")) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry properties lack a final newline");
        }
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry properties contain a non-data line");
            }
            int separator = line.indexOf('=');
            if (separator < 1 || separator == line.length() - 1
                    || line.indexOf('=', separator + 1) >= 0) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry property is malformed");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!PROPERTY_KEY.matcher(key).matches()
                    || value.chars().anyMatch(character -> character <= 0x20)) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry property is not canonical");
            }
            if (properties.putIfAbsent(key, value) != null) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry property is duplicated: " + key);
            }
        }
        if (!properties.keySet().equals(EXACT_PROPERTY_KEYS)) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry property set differs from contract");
        }
        return Map.copyOf(properties);
    }

    private static void validateFixedProperties(Map<String, String> properties) {
        requireEquals(properties, "format-version", "1");
        requireEquals(properties, "pack", "ATM10-8.1");
        requireEquals(properties, "minecraft", "1.21.1");
        requireEquals(properties, "minecraft-protocol", Integer.toString(PROTOCOL_VERSION));
        requireEquals(properties, "neoforge", "21.1.249");
        requireEquals(properties, "curseforge-server-file-id", "8764245");
        requireEquals(
                properties,
                "server-files-sha256",
                "259e4a98888ee6ded0b439113c19ac3c79f6e90eeab1a2c465a1c005d0d5f3c4");
        requireEquals(
                properties, "full-client-contract-sha256", FULL_CLIENT_CONTRACT_SHA256);
        requireEquals(properties, "source-variant", "wire-known-pack");
        requireEquals(properties, "registry-id", REGISTRY_ID);
        requireEquals(
                properties,
                "packet-sequence-index",
                Integer.toString(PACKET_SEQUENCE_INDEX));
        requireEquals(properties, "entry-count", Integer.toString(ENTRY_COUNT));
        requireEquals(
                properties,
                "entries-with-data",
                Integer.toString(ENTRIES_WITH_DATA));
        requireEquals(
                properties,
                "known-pack-placeholders",
                Integer.toString(KNOWN_PACK_PLACEHOLDERS));
        requireEquals(properties, "packet-bytes", Integer.toString(PACKET_BYTES));
        requireEquals(properties, "packet-sha256", PACKET_SHA256);
        requireEquals(
                properties, "entry-sequence-sha256", ENTRY_SEQUENCE_SHA256);
        requireEquals(
                properties,
                "sentinel-entries",
                String.join(",", REQUIRED_SENTINEL_ENTRY_IDS));
        requireSha256(properties, "packet-sha256");
        requireSha256(properties, "entry-sequence-sha256");
    }

    private static void requireEquals(
            Map<String, String> properties, String key, String expected) {
        String actual = properties.get(key);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry property mismatch for " + key
                            + ": " + actual);
        }
    }

    private static int canonicalInt(
            Map<String, String> properties,
            String key,
            int minimum,
            int maximum) {
        String value = properties.get(key);
        if (value == null
                || value.isEmpty()
                || (value.length() > 1 && value.charAt(0) == '0')
                || value.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry integer is not canonical: " + key);
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry integer is outside bounds: " + key,
                    exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry integer is outside bounds: " + key);
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
                    SHIM_ID + " reviewed registry integer differs from independent pin for "
                            + key + ": " + actual);
        }
        return actual;
    }

    private static String requireSha256(Map<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry SHA-256 property is invalid: " + key);
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static String entrySequenceSha256(Inspection inspection) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Set<String> withData = inspection.entryIdsWithData();
            for (String entryId : inspection.entryIds()) {
                digest.update(entryId.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\t');
                digest.update((byte) (withData.contains(entryId) ? '1' : '0'));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    record RuntimeResolution(
            Optional<RegistryShimPacket> packet,
            Optional<Throwable> quarantineFailure) {
        RuntimeResolution {
            packet = Objects.requireNonNull(packet, "packet");
            quarantineFailure = Objects.requireNonNull(
                    quarantineFailure, "quarantineFailure");
            if (packet.isPresent() && quarantineFailure.isPresent()) {
                throw new IllegalArgumentException(
                        "runtime registry resolution cannot be present and quarantined");
            }
        }
    }

    private static final class RuntimeHolder {
        private static final RuntimeResolution REVIEWED = captureRuntimeResolution(
                Atm10Normal81EnchantmentRegistry.class.getClassLoader());

        private RuntimeHolder() {
        }
    }

    private static RuntimeResolution captureRuntimeResolution(ClassLoader loader) {
        try {
            Optional<RegistryShimPacket> packet = loadIfPresent(
                    loader, MAXIMUM_PACKET_BYTES);
            return packet.isPresent()
                    ? new RuntimeResolution(packet, Optional.empty())
                    : new RuntimeResolution(
                            Optional.empty(),
                            Optional.of(new IllegalStateException(
                                    SHIM_ID + " reviewed registry resources are missing")));
        } catch (RuntimeException | LinkageError failure) {
            return new RuntimeResolution(Optional.empty(), Optional.of(failure));
        }
    }
}
