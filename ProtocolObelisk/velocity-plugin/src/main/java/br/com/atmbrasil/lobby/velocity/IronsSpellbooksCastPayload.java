package br.com.atmbrasil.lobby.velocity;

/** Exact empty body emitted when Iron's Spells asks the server to start the selected cast. */
final class IronsSpellbooksCastPayload {
    static final int PAYLOAD_BYTES = 0;

    private IronsSpellbooksCastPayload() {}

    static boolean isValid(byte[] payload) {
        // Iron's Spells 3.16.2 has empty write/read methods for CastPacket.
        return payload != null && payload.length == PAYLOAD_BYTES;
    }
}
