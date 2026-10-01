package br.com.atmbrasil.lobby.velocity;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/** Strict validator for Twilight Forest's gradual-glide state transition payload. */
final class TwilightForestGradualGlidePayload {
    static final int PAYLOAD_BYTES = 1 + Long.BYTES + Long.BYTES;

    private TwilightForestGradualGlidePayload() {}

    static boolean isStructurallyValid(byte[] payload) {
        return payload != null
                && payload.length == PAYLOAD_BYTES
                && (payload[0] == 0 || payload[0] == 1);
    }

    static boolean belongsTo(byte[] payload, UUID expectedPlayer) {
        Objects.requireNonNull(expectedPlayer, "expectedPlayer");
        if (!isStructurallyValid(payload)) {
            return false;
        }
        ByteBuffer input = ByteBuffer.wrap(payload, 1, PAYLOAD_BYTES - 1).slice();
        UUID encodedPlayer = new UUID(input.getLong(), input.getLong());
        return expectedPlayer.equals(encodedPlayer) && !input.hasRemaining();
    }
}
