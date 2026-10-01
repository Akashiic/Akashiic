package br.com.atmbrasil.lobby.velocity;

import java.util.List;
import java.util.Objects;

/** Strict, one-shot acknowledgement transaction for the three ordered profile maps. */
final class SilentGearAckTransaction {
    private final String profileId;
    private final List<String> sentChannels;
    private int received;

    SilentGearAckTransaction(String profileId) {
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        if (profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        this.sentChannels = SilentGearProtocol.SYNC_CHANNELS;
    }

    Acknowledgement accept(String channelId, byte[] payload) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(payload, "payload");
        if (!channelId.equals(SilentGearProtocol.ACK)) {
            throw new IllegalArgumentException("unexpected Silent Gear ACK channel " + channelId);
        }
        if (payload.length != 0) {
            throw new IllegalArgumentException("Silent Gear ACK payload must be empty");
        }
        if (complete()) {
            throw new IllegalArgumentException("unexpected or duplicate Silent Gear lobby ACK");
        }

        String acknowledgedChannel = sentChannels.get(received);
        received++;
        return new Acknowledgement(received, sentChannels.size(), acknowledgedChannel, complete());
    }

    String profileId() {
        return profileId;
    }

    int expected() {
        return sentChannels.size();
    }

    int received() {
        return received;
    }

    boolean complete() {
        return received == sentChannels.size();
    }

    record Acknowledgement(
            int ordinal,
            int expected,
            String acknowledgedChannel,
            boolean complete) {
        Acknowledgement {
            Objects.requireNonNull(acknowledgedChannel, "acknowledgedChannel");
        }
    }
}
