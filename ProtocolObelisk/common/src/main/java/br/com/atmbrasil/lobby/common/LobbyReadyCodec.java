package br.com.atmbrasil.lobby.common;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Minimal, bounded signal from the trusted Velocity plugin to the Paper lobby.
 *
 * <p>This protocol deliberately contains no destination, address, command, permission or
 * client-provided value. Its only purpose is to tell Paper that Velocity completed the ordered
 * NeoForge PLAY bootstraps and that Paper may release the empty recipe lifecycle.</p>
 */
public final class LobbyReadyCodec {
    public static final String CHANNEL_ID = "atm10bridge:control";
    public static final int PAYLOAD_BYTES = 14;

    private static final byte[] MAGIC = {'P', 'O', 'B', 'R'};
    private static final int VERSION = 1;
    private static final int OP_LOBBY_READY = 1;

    private LobbyReadyCodec() {
    }

    public record LobbyReady(long sessionId) {
        public LobbyReady {
            if (sessionId == 0L) {
                throw new IllegalArgumentException("session id cannot be zero");
            }
        }
    }

    public static byte[] encode(LobbyReady message) {
        Objects.requireNonNull(message, "message");
        return ByteBuffer.allocate(PAYLOAD_BYTES)
                .put(MAGIC)
                .put((byte) VERSION)
                .put((byte) OP_LOBBY_READY)
                .putLong(message.sessionId())
                .array();
    }

    public static LobbyReady decode(byte[] payload) throws ProtocolException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length != PAYLOAD_BYTES) {
            throw new ProtocolException("payload size must be exactly " + PAYLOAD_BYTES + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        for (byte expected : MAGIC) {
            if (buffer.get() != expected) {
                throw new ProtocolException("invalid magic");
            }
        }
        if (Byte.toUnsignedInt(buffer.get()) != VERSION) {
            throw new ProtocolException("unsupported protocol version");
        }
        if (Byte.toUnsignedInt(buffer.get()) != OP_LOBBY_READY) {
            throw new ProtocolException("unknown opcode");
        }
        try {
            return new LobbyReady(buffer.getLong());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("invalid readiness signal", exception);
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
