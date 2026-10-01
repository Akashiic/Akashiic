package br.com.atmbrasil.lobby.velocity;

/** Exact body emitted by the Deeper and Darker Sculk Transmitter hotkey. */
final class DeeperDarkerUseTransmitterPayload {
    static final int PAYLOAD_BYTES = 1;

    private DeeperDarkerUseTransmitterPayload() {}

    static boolean isValid(byte[] payload) {
        // UseTransmitterPacket is a one-field ByteBufCodecs.BOOL record. The audited client
        // keybind constructs new UseTransmitterPacket(true), whose canonical wire byte is one.
        return payload != null && payload.length == PAYLOAD_BYTES && payload[0] == 1;
    }
}
