package br.com.atmbrasil.protocolobelisk.necro.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned backend-only transport for TAB header/footer state.
 *
 * <p>The Crucible companion sends this envelope to Velocity on a private channel. Velocity validates
 * the backend/session identity and creates the final NecroTempus SimpleImpl packet itself. This keeps
 * Forge/NBT framing out of Bukkit's legacy plugin-message boundary.</p>
 */
public final class TabBridgeProtocol {
    public static final String CHANNEL = "pobelisk:nttab";
    public static final int MAGIC = 0x504F5442; // POTB
    public static final int VERSION = 1;
    public static final int DEFAULT_MAXIMUM_MESSAGE_BYTES = 30_000;
    public static final int DEFAULT_MAXIMUM_TEXT_BYTES = 16_384;
    private static final int FIXED_BYTES = 42;

    private TabBridgeProtocol() {
    }

    public static byte[] encodeSet(
            long connectionEpoch,
            UUID playerId,
            long sequence,
            String header,
            String footer,
            int maximumTextBytes,
            int maximumMessageBytes) throws ProtocolException {
        return encode(
                Operation.SET,
                connectionEpoch,
                playerId,
                sequence,
                Objects.requireNonNull(header, "header"),
                Objects.requireNonNull(footer, "footer"),
                maximumTextBytes,
                maximumMessageBytes);
    }

    public static byte[] encodeRemove(
            long connectionEpoch,
            UUID playerId,
            long sequence,
            int maximumMessageBytes) throws ProtocolException {
        return encode(
                Operation.REMOVE,
                connectionEpoch,
                playerId,
                sequence,
                "",
                "",
                DEFAULT_MAXIMUM_TEXT_BYTES,
                maximumMessageBytes);
    }

    private static byte[] encode(
            Operation operation,
            long connectionEpoch,
            UUID playerId,
            long sequence,
            String header,
            String footer,
            int maximumTextBytes,
            int maximumMessageBytes) throws ProtocolException {
        validateLimits(maximumTextBytes, maximumMessageBytes);
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(playerId, "playerId");
        if (connectionEpoch == 0L) {
            throw new ProtocolException("connection epoch must be non-zero");
        }
        if (sequence <= 0L) {
            throw new ProtocolException("TAB sequence must be positive");
        }
        byte[] headerBytes = encodeText(header, maximumTextBytes, "header");
        byte[] footerBytes = encodeText(footer, maximumTextBytes, "footer");
        if (operation == Operation.REMOVE && (headerBytes.length != 0 || footerBytes.length != 0)) {
            throw new ProtocolException("remove operation cannot contain text");
        }
        int total;
        try {
            total = Math.addExact(FIXED_BYTES, Math.addExact(headerBytes.length, footerBytes.length));
        } catch (ArithmeticException exception) {
            throw new ProtocolException("TAB bridge message size overflow", exception);
        }
        if (total > maximumMessageBytes) {
            throw new ProtocolException(
                    "TAB bridge message size " + total + " exceeds bound " + maximumMessageBytes);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(total);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                output.writeByte(operation.wireId);
                output.writeLong(connectionEpoch);
                output.writeLong(playerId.getMostSignificantBits());
                output.writeLong(playerId.getLeastSignificantBits());
                output.writeLong(sequence);
                output.writeShort(headerBytes.length);
                output.writeShort(footerBytes.length);
                output.write(headerBytes);
                output.write(footerBytes);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new ProtocolException("in-memory TAB bridge encoding failed", impossible);
        }
    }

    public static Snapshot decode(
            byte[] message,
            int maximumTextBytes,
            int maximumMessageBytes) throws ProtocolException {
        Objects.requireNonNull(message, "message");
        validateLimits(maximumTextBytes, maximumMessageBytes);
        if (message.length < FIXED_BYTES || message.length > maximumMessageBytes) {
            throw new ProtocolException("invalid TAB bridge message size " + message.length);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            if (input.readInt() != MAGIC) {
                throw new ProtocolException("invalid TAB bridge magic");
            }
            int protocolVersion = input.readUnsignedByte();
            if (protocolVersion != VERSION) {
                throw new ProtocolException("unsupported TAB bridge version " + protocolVersion);
            }
            Operation operation = Operation.fromWireId(input.readUnsignedByte());
            long epoch = input.readLong();
            if (epoch == 0L) {
                throw new ProtocolException("zero TAB bridge connection epoch");
            }
            UUID playerId = new UUID(input.readLong(), input.readLong());
            long sequence = input.readLong();
            if (sequence <= 0L) {
                throw new ProtocolException("non-positive TAB bridge sequence");
            }
            int headerLength = input.readUnsignedShort();
            int footerLength = input.readUnsignedShort();
            if (headerLength > maximumTextBytes || footerLength > maximumTextBytes) {
                throw new ProtocolException("TAB bridge text length exceeds its bound");
            }
            if (headerLength + footerLength != input.available()) {
                throw new ProtocolException("truncated or trailing TAB bridge text");
            }
            byte[] headerBytes = input.readNBytes(headerLength);
            byte[] footerBytes = input.readNBytes(footerLength);
            if (input.available() != 0) {
                throw new ProtocolException("trailing TAB bridge data");
            }
            String header = decodeText(headerBytes, "header");
            String footer = decodeText(footerBytes, "footer");
            if (operation == Operation.REMOVE && (!header.isEmpty() || !footer.isEmpty())) {
                throw new ProtocolException("remove TAB bridge message contains text");
            }
            return new Snapshot(operation, epoch, playerId, sequence, header, footer);
        } catch (EOFException exception) {
            throw new ProtocolException("truncated TAB bridge message", exception);
        } catch (IOException exception) {
            throw new ProtocolException("could not decode TAB bridge message", exception);
        }
    }

    private static byte[] encodeText(String text, int maximumTextBytes, String field)
            throws ProtocolException {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maximumTextBytes || encoded.length > 65_535) {
            throw new ProtocolException(field + " exceeds the TAB bridge text bound");
        }
        return encoded;
    }

    private static String decodeText(byte[] encoded, String field) throws ProtocolException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException(field + " is not valid UTF-8", exception);
        }
    }

    private static void validateLimits(int maximumTextBytes, int maximumMessageBytes) {
        if (maximumTextBytes <= 0 || maximumTextBytes > 65_535) {
            throw new IllegalArgumentException("maximumTextBytes must be between 1 and 65535");
        }
        if (maximumMessageBytes < FIXED_BYTES || maximumMessageBytes > 32_767) {
            throw new IllegalArgumentException("maximumMessageBytes must be between 42 and 32767");
        }
    }

    public enum Operation {
        SET(1),
        REMOVE(2);

        private final int wireId;

        Operation(int wireId) {
            this.wireId = wireId;
        }

        static Operation fromWireId(int wireId) throws ProtocolException {
            for (Operation operation : values()) {
                if (operation.wireId == wireId) {
                    return operation;
                }
            }
            throw new ProtocolException("unknown TAB bridge operation " + wireId);
        }
    }

    public static final class Snapshot {
        private final Operation operation;
        private final long connectionEpoch;
        private final UUID playerId;
        private final long sequence;
        private final String header;
        private final String footer;

        Snapshot(
                Operation operation,
                long connectionEpoch,
                UUID playerId,
                long sequence,
                String header,
                String footer) {
            this.operation = operation;
            this.connectionEpoch = connectionEpoch;
            this.playerId = playerId;
            this.sequence = sequence;
            this.header = header;
            this.footer = footer;
        }

        public Operation operation() {
            return operation;
        }

        public long connectionEpoch() {
            return connectionEpoch;
        }

        public UUID playerId() {
            return playerId;
        }

        public long sequence() {
            return sequence;
        }

        public String header() {
            return header;
        }

        public String footer() {
            return footer;
        }
    }

    public static final class ProtocolException extends Exception {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
