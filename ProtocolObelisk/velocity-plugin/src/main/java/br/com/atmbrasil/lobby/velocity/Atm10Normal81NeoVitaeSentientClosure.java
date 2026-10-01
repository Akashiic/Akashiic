package br.com.atmbrasil.lobby.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact NeoVitae sentient registry-and-tags closure for ATM10 Normal 8.1.
 *
 * <p>NeoVitae 1.1.15 builds its creative tab by looking up
 * {@code neovitae:sentient_upgrades#neovitae:sentient_start} and immediately calling
 * {@code Optional.orElseThrow()}. The registry packet and its six-tag packet are therefore one
 * atomic structural enrichment: publishing either one without the other is forbidden.</p>
 */
final class Atm10Normal81NeoVitaeSentientClosure {
    static final String SHIM_ID = "neovitae-sentient-atm10-8.1";
    static final String REQUIRED_NAMESPACE = "neovitae";
    static final String REGISTRY_ID = "neovitae:sentient_upgrades";
    static final int ENTRY_COUNT = 44;
    static final int PACKET_BYTES = 7_229;
    static final String ENTRY_SEQUENCE_SHA256 =
            "59e693d55b4fc103a0fef6c1c69a60f55331f832fa7b2ca7ae14e454f4ccc437";
    static final String PACKET_SHA256 =
            "da417ff57a7a822ac29f843fbd9c41d15cdffbea150f560a2772e54a683b8d76";
    static final String TAG_PACKET_SHA256 =
            "a9e49f656de774d49518902fda08e846f93eae1abc2cde919b93bfbc778d5195";
    static final int TAG_COUNT = 6;
    static final int TAG_MEMBER_COUNT = 101;

    private static final int MAXIMUM_PACKET_BYTES = 1_048_576;
    private static final String PACKET_RESOURCE =
            "configuration-profiles/atm10-normal-8.1-neoforge-21.1.249/"
                    + "dynamic-registries/neovitae_sentient_upgrades.bin";

    private Atm10Normal81NeoVitaeSentientClosure() {
    }

    static RuntimeResolution runtimeResolution(int configuredMaximumBytes) {
        if (configuredMaximumBytes < 1 || configuredMaximumBytes > MAXIMUM_PACKET_BYTES) {
            return new RuntimeResolution(
                    Optional.empty(),
                    Optional.of(new IllegalArgumentException(
                            "maximum-registry-shim-bytes is outside bounds")));
        }
        RuntimeResolution reviewed = RuntimeHolder.REVIEWED;
        if (reviewed.closure().isPresent()
                && reviewed.closure().orElseThrow().packet().packetBytes()
                        > configuredMaximumBytes) {
            return new RuntimeResolution(
                    Optional.empty(),
                    Optional.of(new IllegalArgumentException(
                            SHIM_ID + " requires " + PACKET_BYTES
                                    + " bytes but maximum-registry-shim-bytes is "
                                    + configuredMaximumBytes)));
        }
        return reviewed;
    }

    static RuntimeResolution runtimeResolutionForTest(
            ClassLoader loader, int configuredMaximumBytes) {
        Objects.requireNonNull(loader, "loader");
        if (configuredMaximumBytes < 1 || configuredMaximumBytes > MAXIMUM_PACKET_BYTES) {
            return new RuntimeResolution(
                    Optional.empty(),
                    Optional.of(new IllegalArgumentException(
                            "maximum-registry-shim-bytes is outside bounds")));
        }
        RuntimeResolution reviewed = captureRuntimeResolution(loader);
        if (reviewed.closure().isPresent()
                && reviewed.closure().orElseThrow().packet().packetBytes()
                        > configuredMaximumBytes) {
            return new RuntimeResolution(
                    Optional.empty(),
                    Optional.of(new IllegalArgumentException(
                            SHIM_ID + " exceeds the configured packet budget")));
        }
        return reviewed;
    }

    static Closure loadForTest(ClassLoader loader, int configuredMaximumBytes)
            throws IOException {
        if (configuredMaximumBytes < PACKET_BYTES) {
            throw new IllegalArgumentException(SHIM_ID + " exceeds the configured packet budget");
        }
        return loadReviewed(Objects.requireNonNull(loader, "loader"));
    }

    static Optional<RegistryShimReceipt> expectedReceiptIfPresent() {
        return runtimeResolution(MAXIMUM_PACKET_BYTES)
                .closure()
                .map(Closure::packet)
                .map(RegistryShimReceipt::from);
    }

    private static RuntimeResolution captureRuntimeResolution(ClassLoader loader) {
        try {
            return new RuntimeResolution(
                    Optional.of(loadReviewed(loader)), Optional.empty());
        } catch (RuntimeException | IOException | LinkageError failure) {
            return new RuntimeResolution(Optional.empty(), Optional.of(failure));
        }
    }

    private static Closure loadReviewed(ClassLoader loader) throws IOException {
        byte[] packetBody;
        try (InputStream stream = loader.getResourceAsStream(PACKET_RESOURCE)) {
            if (stream == null) {
                throw new IOException(SHIM_ID + " reviewed registry resource is missing");
            }
            packetBody = stream.readNBytes(PACKET_BYTES + 1);
        }
        if (packetBody.length != PACKET_BYTES) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry length mismatch: " + packetBody.length);
        }
        String actualSha256 = sha256(packetBody);
        if (!PACKET_SHA256.equals(actualSha256)) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry SHA-256 mismatch: " + actualSha256);
        }
        PacketHeader header = decodeHeader(packetBody);
        if (!REGISTRY_ID.equals(header.registryId()) || header.entryCount() != ENTRY_COUNT) {
            throw new IllegalStateException(SHIM_ID + " reviewed registry header mismatch");
        }

        EmbeddedRegistryTagsProfile tags =
                EmbeddedRegistryTagsProfile.loadAtm10Normal81NeoVitae(loader);
        if (!REGISTRY_ID.equals(tags.registryId())
                || tags.tags().size() != TAG_COUNT
                || tags.totalMembers() != TAG_MEMBER_COUNT
                || !TAG_PACKET_SHA256.equals(tags.sha256())) {
            throw new IllegalStateException(SHIM_ID + " reviewed tag closure mismatch");
        }
        EmbeddedRegistryTagsProfile.TagEntry sentientStart = tags.tags().stream()
                .filter(tag -> tag.tagId().equals("neovitae:sentient_start"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        SHIM_ID + " lacks the crash-sentinel sentient_start tag"));
        if (sentientStart.memberIds().length != 15) {
            throw new IllegalStateException(
                    SHIM_ID + " crash-sentinel tag has unexpected membership");
        }
        for (EmbeddedRegistryTagsProfile.TagEntry tag : tags.tags()) {
            for (int memberId : tag.memberIds()) {
                if (memberId < 0 || memberId >= ENTRY_COUNT) {
                    throw new IllegalStateException(
                            SHIM_ID + " tag member is outside the exact registry: " + memberId);
                }
            }
        }

        RegistryShimPacket packet = new RegistryShimPacket(
                SHIM_ID,
                REQUIRED_NAMESPACE,
                REGISTRY_ID,
                ENTRY_COUNT,
                packetBody,
                actualSha256);
        return new Closure(packet, tags);
    }

    private static PacketHeader decodeHeader(byte[] packetBody) {
        ByteCursor cursor = new ByteCursor(packetBody);
        int nameBytes = cursor.readVarInt();
        if (nameBytes < 1 || nameBytes > 256) {
            throw new IllegalStateException(SHIM_ID + " registry id length is outside bounds");
        }
        final String registryId;
        try {
            registryId = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(cursor.readBytes(nameBytes)))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException(SHIM_ID + " registry id is not UTF-8", exception);
        }
        return new PacketHeader(registryId, cursor.readVarInt());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    record Closure(RegistryShimPacket packet, EmbeddedRegistryTagsProfile tags) {
        Closure {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(tags, "tags");
            if (!packet.registryId().equals(tags.registryId())) {
                throw new IllegalArgumentException(
                        "NeoVitae registry and tag identities differ");
            }
        }
    }

    record RuntimeResolution(
            Optional<Closure> closure,
            Optional<Throwable> quarantineFailure) {
        RuntimeResolution {
            closure = Objects.requireNonNull(closure, "closure");
            quarantineFailure = Objects.requireNonNull(
                    quarantineFailure, "quarantineFailure");
            if (closure.isPresent() && quarantineFailure.isPresent()) {
                throw new IllegalArgumentException(
                        "runtime NeoVitae resolution cannot be present and quarantined");
            }
        }
    }

    private record PacketHeader(String registryId, int entryCount) {
    }

    private static final class RuntimeHolder {
        private static final RuntimeResolution REVIEWED = captureRuntimeResolution(
                Atm10Normal81NeoVitaeSentientClosure.class.getClassLoader());

        private RuntimeHolder() {
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
            int start = index;
            for (int shift = 0; shift < 35; shift += 7) {
                if (index >= bytes.length) {
                    throw new IllegalStateException(SHIM_ID + " registry header is truncated");
                }
                int current = bytes[index++] & 0xFF;
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    if (index - start != varIntBytes(value)) {
                        throw new IllegalStateException(
                                SHIM_ID + " registry header has a non-canonical VarInt");
                    }
                    return value;
                }
            }
            throw new IllegalStateException(SHIM_ID + " registry header has an oversized VarInt");
        }

        private byte[] readBytes(int length) {
            if (length < 0 || index > bytes.length - length) {
                throw new IllegalStateException(SHIM_ID + " registry header is truncated");
            }
            byte[] copy = java.util.Arrays.copyOfRange(bytes, index, index + length);
            index += length;
            return copy;
        }

        private static int varIntBytes(int value) {
            int count = 1;
            while ((value & ~0x7F) != 0) {
                value >>>= 7;
                count++;
            }
            return count;
        }
    }
}
