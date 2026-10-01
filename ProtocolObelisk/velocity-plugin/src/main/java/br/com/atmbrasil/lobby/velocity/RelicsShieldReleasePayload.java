package br.com.atmbrasil.lobby.velocity;

/** Exact body emitted when Relics' Shield of Retaliation client releases use. */
final class RelicsShieldReleasePayload {
    static final int PAYLOAD_BYTES = 1;

    private RelicsShieldReleasePayload() {}

    static boolean isValid(byte[] payload) {
        // ByteBufCodecs.BOOL writes true as one byte with value one. The audited sender always
        // constructs C2SShieldOfRetaliationRelease(true); false is ignored by its server handler.
        return payload != null && payload.length == PAYLOAD_BYTES && payload[0] == 1;
    }
}
