package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;

/** Exact one-byte boolean codec used by Logistics Networks during client PLAY login. */
final class LogisticsNetworksDefaultNodeVisibilityPayload {
    static final int PAYLOAD_BYTES = 1;

    private LogisticsNetworksDefaultNodeVisibilityPayload() {
    }

    static boolean isValid(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        return payload.length == PAYLOAD_BYTES
                && (payload[0] == 0 || payload[0] == 1);
    }
}
