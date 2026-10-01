package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;

/** Strict one-shot acknowledgement for NeoForge's configuration registry-sync task. */
final class FrozenRegistryAckTransaction {
    private boolean complete;

    void accept(String channelId, byte[] payload) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(payload, "payload");
        if (!channelId.equals(NeoForgeFrozenRegistryProfile.COMPLETED_CHANNEL)) {
            throw new IllegalArgumentException(
                    "unexpected frozen registry ACK channel " + channelId);
        }
        if (payload.length != 0) {
            throw new IllegalArgumentException("frozen registry ACK payload must be empty");
        }
        if (complete) {
            throw new IllegalArgumentException("unexpected duplicate frozen registry ACK");
        }
        complete = true;
    }

    boolean complete() {
        return complete;
    }
}
