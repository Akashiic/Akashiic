package br.com.atmbrasil.lobby.velocity;

/** Exact unit body emitted when Tool Belt asks the server to open its belt-slot inventory. */
final class ToolBeltOpenBeltSlotInventoryPayload {
    static final int PAYLOAD_BYTES = 0;

    private ToolBeltOpenBeltSlotInventoryPayload() {}

    static boolean isValid(byte[] payload) {
        // Tool Belt 2.2.10 registers StreamCodec.unit(INSTANCE): the canonical body is empty.
        return payload != null && payload.length == PAYLOAD_BYTES;
    }
}
