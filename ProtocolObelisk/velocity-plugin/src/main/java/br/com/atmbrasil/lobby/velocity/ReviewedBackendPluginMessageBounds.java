package br.com.atmbrasil.lobby.velocity;

/**
 * Immutable bounds derived from reviewed backend-to-client plugin-message failures.
 *
 * <p>The observed PLAY frame was rejected by Velocity before the channel and body were decoded.
 * Consequently this class deliberately records a frame bound, not a payload identity or hash.
 */
final class ReviewedBackendPluginMessageBounds {
    static final String ATM10_NORMAL_8_0_PROFILE_ID =
            "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247";
    static final String OBSERVED_VELOCITY_LOG_SHA256 =
            "cb2ac2d901f09e6ed8abc6348f0431609de85aa23ab9dda1c6a88fa116ffb4be";
    static final String OBSERVED_PACKET_CLASS =
            "com.velocitypowered.proxy.protocol.packet.PluginMessagePacket";
    static final int VELOCITY_DEFAULT_MAX_STRING_BYTES = 196_611;
    static final int PREVIOUS_CLIENTBOUND_PROPERTY_BYTES = 5_347_218;
    static final int OBSERVED_ATM10_NORMAL_8_0_PLAY_FRAME_BYTES = 8_388_602;
    static final int MINIMUM_PROPERTY_FOR_OBSERVED_FRAME_BYTES =
            OBSERVED_ATM10_NORMAL_8_0_PLAY_FRAME_BYTES
                    - VELOCITY_DEFAULT_MAX_STRING_BYTES;

    /**
     * Uses the complete observed frame as the property bound because the pre-decode failure did
     * not expose the channel/body split. This remains bounded and is only for clientbound traffic.
     */
    static final int REVIEWED_CLIENTBOUND_PROPERTY_BYTES =
            OBSERVED_ATM10_NORMAL_8_0_PLAY_FRAME_BYTES;

    private ReviewedBackendPluginMessageBounds() {
    }

    static int requiredClientboundPropertyBytes(int maximumFrozenRegistryPayloadBytes) {
        if (maximumFrozenRegistryPayloadBytes < 0) {
            throw new IllegalArgumentException(
                    "maximum frozen-registry payload bytes must not be negative");
        }
        return Math.max(
                maximumFrozenRegistryPayloadBytes, REVIEWED_CLIENTBOUND_PROPERTY_BYTES);
    }

    static long decoderFrameCapacityBytes(int clientboundPropertyBytes) {
        if (clientboundPropertyBytes < 1) {
            throw new IllegalArgumentException(
                    "Velocity clientbound plugin-message property must be positive");
        }
        return (long) VELOCITY_DEFAULT_MAX_STRING_BYTES + clientboundPropertyBytes;
    }

    static boolean decoderCanAcceptFrame(
            int clientboundPropertyBytes, int observedFrameBytes) {
        if (observedFrameBytes < 0) {
            throw new IllegalArgumentException("observed frame bytes must not be negative");
        }
        return observedFrameBytes <= decoderFrameCapacityBytes(clientboundPropertyBytes);
    }
}
