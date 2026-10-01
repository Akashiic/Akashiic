package br.com.atmbrasil.protocolobelisk.necro.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Wire-compatible codec for NecroTempus' Forge 1.7.10 SimpleImpl channel. */
public final class NecroTempusPacketCodec {
    public static final String CHANNEL = "necrotempus:main";
    public static final int CLIENT_HELLO_DISCRIMINATOR = 0;
    public static final int BOSS_BAR_DISCRIMINATOR = 1;
    public static final int PLAYER_TAB_DISCRIMINATOR = 2;
    public static final int TITLE_DISCRIMINATOR = 3;
    public static final int ACTION_BAR_DISCRIMINATOR = 4;
    public static final int MINECRAFT_1_7_10_PROTOCOL = 5;
    public static final int MINECRAFT_1_7_SIGNED_SHORT_MAXIMUM = 32_767;
    public static final int MAXIMUM_HELLO_VERSION_BYTES = 96;
    public static final int DEFAULT_MAXIMUM_COMPRESSED_NBT_BYTES = 30_000;
    public static final int DEFAULT_MAXIMUM_DECOMPRESSED_NBT_BYTES = 262_144;

    private NecroTempusPacketCodec() {
    }

    public static byte[] encodePlayerTabSet(
            String header,
            String footer,
            int maximumCompressedNbtBytes) throws CodecException {
        return encodePlayerTab("set", true, header, footer, maximumCompressedNbtBytes);
    }

    public static byte[] encodePlayerTabRemove(int maximumCompressedNbtBytes)
            throws CodecException {
        return encodePlayerTab("remove", false, null, null, maximumCompressedNbtBytes);
    }

    public static Hello decodeClientHello(
            byte[] payload,
            int maximumCompressedNbtBytes,
            int maximumDecompressedNbtBytes) throws CodecException {
        Objects.requireNonNull(payload, "payload");
        FramedNbt framed = decodeFramedNbt(
                payload,
                CLIENT_HELLO_DISCRIMINATOR,
                maximumCompressedNbtBytes,
                maximumDecompressedNbtBytes);
        String version;
        try {
            byte[] encodedVersion = Utf8Token.encode(
                    framed.stringFields().getOrDefault("version", ""),
                    MAXIMUM_HELLO_VERSION_BYTES,
                    "NecroTempus HELLO version");
            version = Utf8Token.decode(
                    encodedVersion,
                    MAXIMUM_HELLO_VERSION_BYTES,
                    "NecroTempus HELLO version");
        } catch (IllegalArgumentException exception) {
            throw new CodecException(exception.getMessage(), exception);
        }
        return new Hello(version, framed.compressedBytes(), framed.stringFields());
    }

    public static ClientboundPacket validateClientbound(
            byte[] payload,
            int maximumCompressedNbtBytes,
            int maximumDecompressedNbtBytes) throws CodecException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < 3) {
            throw new CodecException("clientbound payload is too short");
        }
        int discriminator = Byte.toUnsignedInt(payload[0]);
        if (discriminator < BOSS_BAR_DISCRIMINATOR
                || discriminator > ACTION_BAR_DISCRIMINATOR) {
            throw new CodecException("unsupported clientbound discriminator " + discriminator);
        }
        FramedNbt framed = decodeFramedNbt(
                payload,
                discriminator,
                maximumCompressedNbtBytes,
                maximumDecompressedNbtBytes);
        validateClientboundOperation(discriminator, framed.stringFields());
        return new ClientboundPacket(
                discriminator,
                framed.compressedBytes(),
                framed.stringFields());
    }

    /** Test fixture helper; never used by either runtime plugin. */
    public static byte[] encodeHelloFixture(String version) throws CodecException {
        try {
            String validated = Utf8Token.decode(
                    Utf8Token.encode(version, MAXIMUM_HELLO_VERSION_BYTES,
                            "NecroTempus HELLO version"),
                    MAXIMUM_HELLO_VERSION_BYTES,
                    "NecroTempus HELLO version");
            byte[] compressed = LegacyNbt.writeStringCompound(Map.of("version", validated));
            return frame(CLIENT_HELLO_DISCRIMINATOR, compressed, MINECRAFT_1_7_SIGNED_SHORT_MAXIMUM);
        } catch (IOException exception) {
            throw new CodecException("could not encode HELLO fixture", exception);
        }
    }

    private static byte[] encodePlayerTab(
            String packetType,
            boolean drawPlayerHeads,
            String header,
            String footer,
            int maximumCompressedNbtBytes) throws CodecException {
        try {
            byte[] compressed = LegacyNbt.writePlayerTab(
                    packetType,
                    drawPlayerHeads,
                    header,
                    footer);
            return frame(PLAYER_TAB_DISCRIMINATOR, compressed, maximumCompressedNbtBytes);
        } catch (IOException exception) {
            throw new CodecException("could not encode NecroTempus player tab", exception);
        }
    }

    private static byte[] frame(int discriminator, byte[] compressed, int maximumCompressedNbtBytes)
            throws CodecException {
        validateMaximum(maximumCompressedNbtBytes);
        if (compressed.length == 0
                || compressed.length > maximumCompressedNbtBytes
                || compressed.length > MINECRAFT_1_7_SIGNED_SHORT_MAXIMUM) {
            throw new CodecException(
                    "compressed NBT size " + compressed.length + " exceeds the configured bound");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(compressed.length + 3);
            try (DataOutputStream data = new DataOutputStream(output)) {
                data.writeByte(discriminator);
                data.writeShort(compressed.length);
                data.write(compressed);
            }
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new CodecException("in-memory packet framing failed", impossible);
        }
    }

    private static FramedNbt decodeFramedNbt(
            byte[] payload,
            int expectedDiscriminator,
            int maximumCompressedNbtBytes,
            int maximumDecompressedNbtBytes) throws CodecException {
        validateMaximum(maximumCompressedNbtBytes);
        if (maximumDecompressedNbtBytes <= 0) {
            throw new IllegalArgumentException("maximumDecompressedNbtBytes must be positive");
        }
        if (payload.length < 3 || payload.length > maximumCompressedNbtBytes + 3) {
            throw new CodecException("invalid framed payload size " + payload.length);
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
            int discriminator = data.readUnsignedByte();
            if (discriminator != expectedDiscriminator) {
                throw new CodecException(
                        "expected discriminator " + expectedDiscriminator + " but received " + discriminator);
            }
            int compressedLength = data.readShort();
            if (compressedLength <= 0
                    || compressedLength > maximumCompressedNbtBytes
                    || compressedLength != data.available()) {
                throw new CodecException("invalid compressed NBT length " + compressedLength);
            }
            byte[] compressed = data.readNBytes(compressedLength);
            if (compressed.length != compressedLength || data.available() != 0) {
                throw new CodecException("truncated or trailing framed NBT");
            }
            Map<String, String> strings = LegacyNbt.readRootStringFields(
                    compressed,
                    maximumDecompressedNbtBytes);
            return new FramedNbt(compressedLength, strings);
        } catch (EOFException exception) {
            throw new CodecException("truncated framed NBT", exception);
        } catch (IOException exception) {
            throw new CodecException("could not decode framed NBT", exception);
        }
    }

    private static void validateMaximum(int maximumCompressedNbtBytes) {
        if (maximumCompressedNbtBytes <= 0
                || maximumCompressedNbtBytes > MINECRAFT_1_7_SIGNED_SHORT_MAXIMUM) {
            throw new IllegalArgumentException(
                    "maximumCompressedNbtBytes must be between 1 and 32767");
        }
    }

    private static void validateClientboundOperation(
            int discriminator,
            Map<String, String> fields) throws CodecException {
        String operation = fields.getOrDefault("packetType", "").toLowerCase(java.util.Locale.ROOT);
        boolean valid = switch (discriminator) {
            case BOSS_BAR_DISCRIMINATOR -> operation.equals("add")
                    || operation.equals("remove")
                    || operation.equals("update");
            case PLAYER_TAB_DISCRIMINATOR, TITLE_DISCRIMINATOR,
                    ACTION_BAR_DISCRIMINATOR -> operation.equals("set")
                            || operation.equals("remove");
            default -> false;
        };
        if (!valid) {
            throw new CodecException(
                    "invalid packetType '" + operation + "' for discriminator " + discriminator);
        }
    }

    public record Hello(String version, int compressedBytes, Map<String, String> stringFields) {
        public Hello {
            Objects.requireNonNull(version, "version");
            stringFields = Map.copyOf(Objects.requireNonNull(stringFields, "stringFields"));
        }
    }

    public record ClientboundPacket(
            int discriminator,
            int compressedBytes,
            Map<String, String> stringFields) {
        public ClientboundPacket {
            stringFields = Map.copyOf(Objects.requireNonNull(stringFields, "stringFields"));
        }
    }

    private record FramedNbt(int compressedBytes, Map<String, String> stringFields) {
    }

    public static final class CodecException extends Exception {
        private static final long serialVersionUID = 1L;

        public CodecException(String message) {
            super(message);
        }

        public CodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
