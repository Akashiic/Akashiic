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

/**
 * Exact Iron's Spellbooks registry closure reviewed for ATM10 Normal 8.1.
 *
 * <p>The retained 8.0 wire capture was revalidated byte-for-byte against the 14 generated
 * {@code upgrade_orb_type} definitions in upstream Iron's Spells 'n Spellbooks 1.21.1-3.16.3,
 * commit {@code 532c98dfa5b219f3927aca9b23d9042c00e767bb}. The version/commit pins make this
 * deliberate reuse auditable instead of treating an older profile directory as evidence.</p>
 */
final class Atm10Normal81IronsSpellbooksRegistry {
    static final String REVIEWED_UPSTREAM_VERSION = "1.21.1-3.16.3";
    static final String REVIEWED_UPSTREAM_COMMIT =
            "532c98dfa5b219f3927aca9b23d9042c00e767bb";
    static final String SHIM_ID = "irons-spellbooks-atm10-8.1";
    static final String FULL_CLIENT_CONTRACT_SHA256 =
            "9d06683c97b68f2bf5093c15d35a95ab14e8dbde67c6c9e65509cdaebed607a3";
    static final String REQUIRED_NAMESPACE = "irons_spellbooks";
    static final String REGISTRY_ID = "irons_spellbooks:upgrade_orb_type";
    static final int ENTRY_COUNT = 14;
    static final int PACKET_BYTES = 2_592;
    static final String PACKET_SHA256 =
            "7b49f4b642d457e983a08ef16e2ac09b4c418a28cdb3723dbd2baa3ec331e0c1";

    private static final String RESOURCE =
            "silentgear-profiles/atm10-normal-8.0/dynamic-registries/"
                    + "irons_spellbooks_upgrade_orb_type.bin";
    private static final RegistryShimPacket REVIEWED_PACKET = loadReviewed(
            Atm10Normal81IronsSpellbooksRegistry.class.getClassLoader(), PACKET_BYTES);

    private Atm10Normal81IronsSpellbooksRegistry() {
    }

    static RegistryShimPacket packet(int configuredMaximumBytes) {
        requirePacketBudget(configuredMaximumBytes);
        return REVIEWED_PACKET;
    }

    /** Test seam which applies every production validation to an isolated resource loader. */
    static RegistryShimPacket loadForTest(
            ClassLoader loader, int configuredMaximumBytes) {
        return loadReviewed(loader, configuredMaximumBytes);
    }

    private static RegistryShimPacket loadReviewed(
            ClassLoader loader, int configuredMaximumBytes) {
        Objects.requireNonNull(loader, "loader");
        requirePacketBudget(configuredMaximumBytes);

        final byte[] packetBody;
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry resource is missing");
            }
            packetBody = stream.readNBytes(Math.addExact(PACKET_BYTES, 1));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry resource could not be read", exception);
        }
        if (packetBody.length != PACKET_BYTES) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry length mismatch: " + packetBody.length);
        }

        String actualSha256 = sha256(packetBody);
        if (!actualSha256.equals(PACKET_SHA256)) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry SHA-256 mismatch: " + actualSha256);
        }

        PacketHeader header = decodeHeader(packetBody);
        if (!header.registryId().equals(REGISTRY_ID)) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry id mismatch: " + header.registryId());
        }
        if (header.entryCount() != ENTRY_COUNT) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry entry-count mismatch: "
                            + header.entryCount());
        }

        return new RegistryShimPacket(
                SHIM_ID,
                REQUIRED_NAMESPACE,
                REGISTRY_ID,
                ENTRY_COUNT,
                packetBody,
                actualSha256);
    }

    private static void requirePacketBudget(int configuredMaximumBytes) {
        if (configuredMaximumBytes < PACKET_BYTES) {
            throw new IllegalArgumentException(
                    SHIM_ID + " requires " + PACKET_BYTES
                            + " bytes but maximum-registry-shim-bytes is "
                            + configuredMaximumBytes);
        }
    }

    private static PacketHeader decodeHeader(byte[] packetBody) {
        ByteCursor cursor = new ByteCursor(packetBody);
        int nameBytes = cursor.readVarInt();
        if (nameBytes < 1 || nameBytes > 256) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry id length is outside bounds");
        }

        final String registryId;
        try {
            registryId = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(cursor.readBytes(nameBytes)))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry id is not UTF-8", exception);
        }
        return new PacketHeader(registryId, cursor.readVarInt());
    }

    private static String sha256(byte[] packetBody) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(packetBody));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private record PacketHeader(String registryId, int entryCount) {
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
                    throw new IllegalStateException(
                            SHIM_ID + " reviewed registry header is truncated");
                }
                int current = bytes[index++] & 0xFF;
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    if (shift > 0 && (current & 0x7F) == 0) {
                        throw new IllegalStateException(
                                SHIM_ID + " reviewed registry header has non-canonical VarInt");
                    }
                    return value;
                }
            }
            throw new IllegalStateException(
                    SHIM_ID + " reviewed registry header has oversized VarInt");
        }

        private byte[] readBytes(int length) {
            if (length < 0 || index > bytes.length - length) {
                throw new IllegalStateException(
                        SHIM_ID + " reviewed registry header is truncated");
            }
            byte[] copy = java.util.Arrays.copyOfRange(bytes, index, index + length);
            index += length;
            return copy;
        }
    }
}
