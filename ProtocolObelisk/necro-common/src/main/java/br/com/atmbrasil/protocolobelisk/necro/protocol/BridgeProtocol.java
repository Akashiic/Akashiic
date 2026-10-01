package br.com.atmbrasil.protocolobelisk.necro.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Fixed-size, bounded control protocol used only between the Velocity transport and a backend
 * companion. It never contains TAB layouts, commands, routes, permissions or arbitrary payloads.
 */
public final class BridgeProtocol {
    // Keep the control identifier below the legacy 1.7 custom-payload channel limit. Velocity
    // advertises every registered identifier to the client; a long modern-only identifier would
    // therefore leak an unusable channel into the Forge 1.7 registration set.
    public static final String CAPABILITY_CHANNEL = "pobelisk:ntcap";
    public static final int MAGIC = 0x504F4E54; // "PONT" - ProtocolObelisk NecroTempus
    public static final int VERSION = 1;
    public static final int MAXIMUM_MOD_VERSION_BYTES = 96;
    public static final int MAXIMUM_MESSAGE_BYTES = 256;

    private BridgeProtocol() {
    }

    public static byte[] encode(Capability capability) {
        Objects.requireNonNull(capability, "capability");
        byte[] versionBytes;
        if (capability.operation() != Operation.CONFIRM) {
            if (!capability.necroTempusVersion().isEmpty()) {
                throw new IllegalArgumentException(
                        capability.operation().name().toLowerCase()
                                + " capability must not contain a version");
            }
            versionBytes = new byte[0];
        } else {
            versionBytes = Utf8Token.encode(
                    capability.necroTempusVersion(),
                    MAXIMUM_MOD_VERSION_BYTES,
                    "NecroTempus version");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(64 + versionBytes.length);
            try (DataOutputStream data = new DataOutputStream(output)) {
                data.writeInt(MAGIC);
                data.writeByte(VERSION);
                data.writeByte(capability.operation().wireId());
                data.writeLong(capability.connectionEpoch());
                data.writeLong(capability.playerId().getMostSignificantBits());
                data.writeLong(capability.playerId().getLeastSignificantBits());
                data.writeShort(versionBytes.length);
                data.write(versionBytes);
            }
            byte[] encoded = output.toByteArray();
            if (encoded.length > MAXIMUM_MESSAGE_BYTES) {
                throw new IllegalStateException("capability message exceeded its fixed bound");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory capability encoding failed", impossible);
        }
    }

    public static Capability decode(byte[] message) throws ProtocolException {
        Objects.requireNonNull(message, "message");
        if (message.length == 0 || message.length > MAXIMUM_MESSAGE_BYTES) {
            throw new ProtocolException("invalid capability message size");
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(message))) {
            if (data.readInt() != MAGIC) {
                throw new ProtocolException("invalid capability magic");
            }
            int version = data.readUnsignedByte();
            if (version != VERSION) {
                throw new ProtocolException("unsupported capability protocol version " + version);
            }
            Operation operation = Operation.fromWireId(data.readUnsignedByte());
            long epoch = data.readLong();
            if (operation == Operation.REQUEST) {
                if (epoch != 0L) {
                    throw new ProtocolException("request capability must use epoch zero");
                }
            } else if (epoch == 0L) {
                throw new ProtocolException("zero connection epoch");
            }
            UUID playerId = new UUID(data.readLong(), data.readLong());
            int versionLength = data.readUnsignedShort();
            if (versionLength > MAXIMUM_MOD_VERSION_BYTES || versionLength > data.available()) {
                throw new ProtocolException("invalid NecroTempus version length");
            }
            byte[] versionBytes = data.readNBytes(versionLength);
            if (versionBytes.length != versionLength || data.available() != 0) {
                throw new ProtocolException("truncated or trailing capability data");
            }
            String modVersion;
            try {
                if (operation != Operation.CONFIRM) {
                    if (versionBytes.length != 0) {
                        throw new IllegalArgumentException(
                                operation.name().toLowerCase()
                                        + " capability must not contain a version");
                    }
                    modVersion = "";
                } else {
                    modVersion = Utf8Token.decode(
                            versionBytes,
                            MAXIMUM_MOD_VERSION_BYTES,
                            "NecroTempus version");
                }
            } catch (IllegalArgumentException exception) {
                throw new ProtocolException(exception.getMessage(), exception);
            }
            return new Capability(operation, epoch, playerId, modVersion);
        } catch (EOFException exception) {
            throw new ProtocolException("truncated capability message", exception);
        } catch (IOException exception) {
            throw new ProtocolException("could not decode capability message", exception);
        }
    }

    public enum Operation {
        CONFIRM(1),
        CLEAR(2),
        REQUEST(3);

        private final int wireId;

        Operation(int wireId) {
            this.wireId = wireId;
        }

        int wireId() {
            return wireId;
        }

        static Operation fromWireId(int wireId) throws ProtocolException {
            return switch (wireId) {
                case 1 -> CONFIRM;
                case 2 -> CLEAR;
                case 3 -> REQUEST;
                default -> throw new ProtocolException("unknown capability operation " + wireId);
            };
        }
    }

    public record Capability(
            Operation operation,
            long connectionEpoch,
            UUID playerId,
            String necroTempusVersion) {
        public Capability {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(necroTempusVersion, "necroTempusVersion");
            if (operation == Operation.REQUEST) {
                if (connectionEpoch != 0L) {
                    throw new IllegalArgumentException(
                            "request capability must use connectionEpoch zero");
                }
                if (!necroTempusVersion.isEmpty()) {
                    throw new IllegalArgumentException(
                            "request capability must not contain a version");
                }
            } else if (connectionEpoch == 0L) {
                throw new IllegalArgumentException("connectionEpoch must not be zero");
            }
        }

        public static Capability confirmed(long epoch, UUID playerId, String modVersion) {
            return new Capability(Operation.CONFIRM, epoch, playerId, modVersion);
        }

        public static Capability cleared(long epoch, UUID playerId) {
            return new Capability(Operation.CLEAR, epoch, playerId, "");
        }

        public static Capability requested(UUID playerId) {
            return new Capability(Operation.REQUEST, 0L, playerId, "");
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
