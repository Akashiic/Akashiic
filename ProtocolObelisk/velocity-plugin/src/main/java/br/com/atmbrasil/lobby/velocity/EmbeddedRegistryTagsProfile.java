package br.com.atmbrasil.lobby.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/** Exact standard CONFIG Update Tags packet for reviewed dynamic registries. */
final class EmbeddedRegistryTagsProfile {
    private static final String ATM10_NORMAL_8_0_RESOURCE_ROOT =
            SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT;
    private static final String ATM10_NORMAL_8_1_RESOURCE_ROOT =
            "configuration-profiles/atm10-normal-8.1-neoforge-21.1.249/"
                    + "dynamic-registry-tags/";
    private static final String MANIFEST_FILE = "dynamic-registry-tags.properties";
    private static final String PACKET_FILE = "dynamic-registry-tags.bin";
    private static final String EXPECTED_REGISTRY = "neovitae:sentient_upgrades";
    private static final int EXPECTED_PACKET_BYTES = 267;
    private static final int EXPECTED_TOTAL_MEMBERS = 101;
    private static final String ATM10_NORMAL_8_0_EXPECTED_SHA256 =
            "3d11e2231295e07d3a5bbd1326defa18e1b519cc64f75b6e8f2903e9101c6001";
    private static final String ATM10_NORMAL_8_1_EXPECTED_SHA256 =
            "a9e49f656de774d49518902fda08e846f93eae1abc2cde919b93bfbc778d5195";
    private static final int MAXIMUM_PACKET_BYTES = 4_096;
    private static final int MAXIMUM_TAGS = 64;
    private static final int MAXIMUM_MEMBERS_PER_TAG = 65_535;
    private static final int MAXIMUM_REGISTRY_ENTRY_ID = 43;
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final List<TagEntry> ATM10_NORMAL_8_0_EXPECTED_TAGS = List.of(
            new TagEntry("neovitae:is_downgrade", ids(1, 43, 2, 31, 4, 40, 35, 38, 42)),
            new TagEntry("neovitae:is_scrappable", ids(
                    28, 41, 5, 6, 22, 30, 32, 24, 25, 26, 0, 34, 39, 36, 29, 23,
                    27, 33, 37)),
            new TagEntry("neovitae:sentient_start", ids(
                    7, 16, 10, 8, 9, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21)),
            new TagEntry("neovitae:tooltip_hide", ids(
                    7, 16, 10, 8, 9, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21)),
            new TagEntry("neovitae:tooltip_order", ids(
                    28, 41, 5, 6, 22, 30, 32, 24, 25, 26, 0, 34, 39, 36, 29, 23,
                    27, 33, 37, 1, 43, 2, 31, 4, 40, 35, 38, 42)),
            new TagEntry("neovitae:trainer", ids(
                    7, 16, 10, 8, 9, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21)));
    /**
     * Exact iteration order emitted by two independent ATM10 8.1 cold boots.
     *
     * <p>Membership and numeric IDs are unchanged from 8.0, but packet order is part of the
     * reviewed byte identity and is therefore pinned independently.</p>
     */
    private static final List<TagEntry> ATM10_NORMAL_8_1_EXPECTED_TAGS = List.of(
            new TagEntry("neovitae:is_scrappable", ids(
                    28, 41, 5, 6, 22, 30, 32, 24, 25, 26, 0, 34, 39, 36, 29, 23,
                    27, 33, 37)),
            new TagEntry("neovitae:trainer", ids(
                    7, 16, 10, 8, 9, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21)),
            new TagEntry("neovitae:tooltip_order", ids(
                    28, 41, 5, 6, 22, 30, 32, 24, 25, 26, 0, 34, 39, 36, 29, 23,
                    27, 33, 37, 1, 43, 2, 31, 4, 40, 35, 38, 42)),
            new TagEntry("neovitae:is_downgrade", ids(1, 43, 2, 31, 4, 40, 35, 38, 42)),
            new TagEntry("neovitae:tooltip_hide", ids(
                    7, 16, 10, 8, 9, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21)),
            new TagEntry("neovitae:sentient_start", ids(
                    7, 16, 10, 8, 9, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21)));
    private static final EmbeddedRegistryTagsProfile EMPTY =
            new EmbeddedRegistryTagsProfile("", List.of(), new byte[0], "");

    private final String registryId;
    private final List<TagEntry> tags;
    private final byte[] packetBody;
    private final String sha256;

    private EmbeddedRegistryTagsProfile(
            String registryId, List<TagEntry> tags, byte[] packetBody, String sha256) {
        this.registryId = Objects.requireNonNull(registryId, "registryId");
        this.tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        this.packetBody = Objects.requireNonNull(packetBody, "packetBody").clone();
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
    }

    static EmbeddedRegistryTagsProfile empty() {
        return EMPTY;
    }

    static EmbeddedRegistryTagsProfile loadAtm10Normal80(ClassLoader loader) throws IOException {
        return loadReviewed(
                loader,
                new Review(
                        ATM10_NORMAL_8_0_RESOURCE_ROOT,
                        "ATM10-8.0",
                        "21.1.247",
                        "",
                        "",
                        ATM10_NORMAL_8_0_EXPECTED_SHA256,
                        ATM10_NORMAL_8_0_EXPECTED_TAGS));
    }

    static EmbeddedRegistryTagsProfile loadAtm10Normal81NeoVitae(ClassLoader loader)
            throws IOException {
        return loadReviewed(
                loader,
                new Review(
                        ATM10_NORMAL_8_1_RESOURCE_ROOT,
                        "ATM10-8.1",
                        "21.1.249",
                        "767",
                        Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256,
                        ATM10_NORMAL_8_1_EXPECTED_SHA256,
                        ATM10_NORMAL_8_1_EXPECTED_TAGS));
    }

    private static EmbeddedRegistryTagsProfile loadReviewed(
            ClassLoader loader, Review review) throws IOException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(review, "review");
        Properties manifest = loadManifest(loader, review.resourceRoot());
        requireEquals(manifest, "format-version", "1");
        requireEquals(manifest, "pack", review.pack());
        requireEquals(manifest, "minecraft", "1.21.1");
        requireEquals(manifest, "neoforge", review.neoForge());
        if (!review.minecraftProtocol().isEmpty()) {
            requireEquals(manifest, "minecraft-protocol", review.minecraftProtocol());
        }
        if (!review.fullClientContractSha256().isEmpty()) {
            requireEquals(
                    manifest,
                    "full-client-contract-sha256",
                    review.fullClientContractSha256());
        }
        requireEquals(manifest, "packet", "clientbound-update-tags");
        requireEquals(
                manifest,
                "lifecycle",
                "configuration-after-dynamic-registry-tail");
        requireEquals(manifest, "registry.count", "1");
        requireEquals(manifest, "registry.name", EXPECTED_REGISTRY);
        requireEquals(manifest, "tag.count", Integer.toString(review.expectedTags().size()));
        requireEquals(manifest, "tag.total-members", Integer.toString(EXPECTED_TOTAL_MEMBERS));
        requireEquals(manifest, "file", PACKET_FILE);
        requireEquals(manifest, "bytes", Integer.toString(EXPECTED_PACKET_BYTES));
        requireEquals(manifest, "sha256", review.expectedSha256());
        for (int index = 0; index < review.expectedTags().size(); index++) {
            TagEntry expected = review.expectedTags().get(index);
            String key = "tag." + index + '.';
            requireEquals(manifest, key + "name", expected.tagId());
            requireEquals(
                    manifest,
                    key + "members",
                    Integer.toString(expected.memberIds().length));
            requireEquals(manifest, key + "member-ids", join(expected.memberIds()));
        }

        byte[] bytes = readBounded(
                loader, review.resourceRoot() + PACKET_FILE, MAXIMUM_PACKET_BYTES);
        if (bytes.length != EXPECTED_PACKET_BYTES) {
            throw new IllegalArgumentException("embedded registry tags packet length mismatch");
        }
        String actualSha256 = SilentGearProtocol.sha256(bytes);
        if (!actualSha256.equals(review.expectedSha256())) {
            throw new IllegalArgumentException("embedded registry tags packet hash mismatch");
        }
        ParsedPacket parsed = decode(bytes);
        if (!parsed.registryId().equals(EXPECTED_REGISTRY)
                || parsed.tags().size() != review.expectedTags().size()) {
            throw new IllegalArgumentException("embedded registry tags packet identity mismatch");
        }
        for (TagEntry expected : review.expectedTags()) {
            TagEntry actual = parsed.tags().stream()
                    .filter(candidate -> candidate.tagId().equals(expected.tagId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "embedded registry tag is absent: " + expected.tagId()));
            if (!Arrays.equals(actual.memberIds(), expected.memberIds())) {
                throw new IllegalArgumentException(
                        "embedded registry tag differs from reviewed export: " + expected.tagId());
            }
        }
        int totalMembers = parsed.tags().stream()
                .mapToInt(tag -> tag.memberIds().length)
                .sum();
        if (totalMembers != EXPECTED_TOTAL_MEMBERS) {
            throw new IllegalArgumentException("embedded registry tag member total mismatch");
        }
        return new EmbeddedRegistryTagsProfile(
                parsed.registryId(), parsed.tags(), bytes, actualSha256);
    }

    String registryId() {
        return registryId;
    }

    List<TagEntry> tags() {
        return tags;
    }

    int totalMembers() {
        return tags.stream().mapToInt(tag -> tag.memberIds().length).sum();
    }

    int packetBytes() {
        return packetBody.length;
    }

    byte[] packetBody() {
        return packetBody.clone();
    }

    String sha256() {
        return sha256;
    }

    boolean isEmpty() {
        return tags.isEmpty();
    }

    Map<String, Map<String, int[]>> velocityTagMap() {
        if (isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, int[]> inner = new LinkedHashMap<>();
        for (TagEntry tag : tags) {
            inner.put(tag.tagId(), tag.memberIds());
        }
        LinkedHashMap<String, Map<String, int[]>> outer = new LinkedHashMap<>();
        outer.put(registryId, Collections.unmodifiableMap(inner));
        return Collections.unmodifiableMap(outer);
    }

    static void validatePacketStructure(byte[] bytes) {
        decode(Objects.requireNonNull(bytes, "bytes"));
    }

    private static ParsedPacket decode(byte[] bytes) {
        Cursor cursor = new Cursor(bytes);
        int registryCount = cursor.readBoundedVarInt(1, 1, "registry count");
        if (registryCount != 1) {
            throw new IllegalArgumentException("embedded registry tags packet is not a singleton");
        }
        String registryId = cursor.readResourceLocation();
        int tagCount = cursor.readBoundedVarInt(1, MAXIMUM_TAGS, "tag count");
        ArrayList<TagEntry> tags = new ArrayList<>(tagCount);
        for (int tagIndex = 0; tagIndex < tagCount; tagIndex++) {
            String tagId = cursor.readResourceLocation();
            int memberCount = cursor.readBoundedVarInt(
                    1, MAXIMUM_MEMBERS_PER_TAG, "tag member count");
            int[] memberIds = new int[memberCount];
            boolean[] encountered = new boolean[MAXIMUM_REGISTRY_ENTRY_ID + 1];
            for (int memberIndex = 0; memberIndex < memberCount; memberIndex++) {
                int memberId = cursor.readBoundedVarInt(
                        0, MAXIMUM_REGISTRY_ENTRY_ID, "tag member id");
                if (encountered[memberId]) {
                    throw new IllegalArgumentException(
                            "embedded registry tag has duplicate member id " + tagId);
                }
                encountered[memberId] = true;
                memberIds[memberIndex] = memberId;
            }
            if (tags.stream().anyMatch(existing -> existing.tagId().equals(tagId))) {
                throw new IllegalArgumentException("embedded registry tags packet has duplicate tag");
            }
            tags.add(new TagEntry(tagId, memberIds));
        }
        if (cursor.remaining() != 0) {
            throw new IllegalArgumentException("embedded registry tags packet has trailing bytes");
        }
        return new ParsedPacket(registryId, tags);
    }

    private static Properties loadManifest(ClassLoader loader, String resourceRoot)
            throws IOException {
        try (InputStream stream = loader.getResourceAsStream(resourceRoot + MANIFEST_FILE)) {
            if (stream == null) {
                throw new IOException("missing embedded registry tags manifest");
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
                throw new IOException("missing embedded registry tags resource " + resource);
            }
            byte[] bytes = stream.readNBytes(Math.addExact(maximumBytes, 1));
            if (bytes.length > maximumBytes) {
                throw new IllegalArgumentException("embedded registry tags resource exceeds bound");
            }
            return bytes;
        }
    }

    private static void requireEquals(Properties manifest, String key, String expected) {
        String actual = manifest.getProperty(key);
        if (actual == null || !actual.strip().equals(expected)) {
            throw new IllegalArgumentException(
                    "embedded registry tags manifest mismatch: " + key);
        }
    }

    private static int[] ids(int... values) {
        return values;
    }

    private static String join(int[] values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(values[index]);
        }
        return result.toString();
    }

    record TagEntry(String tagId, int[] memberIds) {
        TagEntry {
            if (!RESOURCE_LOCATION.matcher(Objects.requireNonNull(tagId, "tagId")).matches()) {
                throw new IllegalArgumentException("embedded registry tag id is invalid");
            }
            memberIds = Objects.requireNonNull(memberIds, "memberIds").clone();
        }

        @Override
        public int[] memberIds() {
            return memberIds.clone();
        }
    }

    private record ParsedPacket(String registryId, List<TagEntry> tags) {
        private ParsedPacket {
            Objects.requireNonNull(registryId, "registryId");
            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        }
    }

    private record Review(
            String resourceRoot,
            String pack,
            String neoForge,
            String minecraftProtocol,
            String fullClientContractSha256,
            String expectedSha256,
            List<TagEntry> expectedTags) {
        private Review {
            Objects.requireNonNull(resourceRoot, "resourceRoot");
            Objects.requireNonNull(pack, "pack");
            Objects.requireNonNull(neoForge, "neoForge");
            Objects.requireNonNull(minecraftProtocol, "minecraftProtocol");
            Objects.requireNonNull(
                    fullClientContractSha256, "fullClientContractSha256");
            Objects.requireNonNull(expectedSha256, "expectedSha256");
            expectedTags = List.copyOf(Objects.requireNonNull(expectedTags, "expectedTags"));
        }
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int index;

        private Cursor(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        private String readResourceLocation() {
            int byteCount = readBoundedVarInt(1, 256, "resource-location bytes");
            byte[] encoded = readBytes(byteCount);
            final String value;
            try {
                value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(encoded))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException(
                        "embedded registry tags resource location is not UTF-8", exception);
            }
            if (!RESOURCE_LOCATION.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "embedded registry tags resource location is invalid");
            }
            return value;
        }

        private int readBoundedVarInt(int minimum, int maximum, String label) {
            int start = index;
            int value = readVarInt();
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(label + " is outside bounds");
            }
            if (index - start != varIntBytes(value)) {
                throw new IllegalArgumentException(label + " uses a non-canonical VarInt");
            }
            return value;
        }

        private int readVarInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int current = readUnsignedByte();
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("embedded registry tags VarInt is oversized");
        }

        private static int varIntBytes(int value) {
            int bytes = 1;
            while ((value & ~0x7F) != 0) {
                value >>>= 7;
                bytes++;
            }
            return bytes;
        }

        private int readUnsignedByte() {
            if (index >= bytes.length) {
                throw new IllegalArgumentException("embedded registry tags packet is truncated");
            }
            return bytes[index++] & 0xFF;
        }

        private byte[] readBytes(int count) {
            if (count < 0 || count > remaining()) {
                throw new IllegalArgumentException("embedded registry tags packet is truncated");
            }
            byte[] result = Arrays.copyOfRange(bytes, index, index + count);
            index += count;
            return result;
        }

        private int remaining() {
            return bytes.length - index;
        }
    }
}
