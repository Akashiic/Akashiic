package br.com.atmbrasil.lobby.velocity.protocol;

/** Hard limits applied before allocating collections or strings from an untrusted client. */
public record ProtocolLimits(
        int maximumQueryBytes,
        int maximumSetupBytes,
        int maximumProtocols,
        int maximumChannels,
        int maximumChannelsPerProtocol,
        int maximumResourceLocationBytes,
        int maximumVersionBytes) {

    public ProtocolLimits {
        requirePositive(maximumQueryBytes, "maximumQueryBytes");
        requirePositive(maximumSetupBytes, "maximumSetupBytes");
        requirePositive(maximumProtocols, "maximumProtocols");
        requirePositive(maximumChannels, "maximumChannels");
        requirePositive(maximumChannelsPerProtocol, "maximumChannelsPerProtocol");
        requirePositive(maximumResourceLocationBytes, "maximumResourceLocationBytes");
        requirePositive(maximumVersionBytes, "maximumVersionBytes");
    }

    public static ProtocolLimits productionDefaults() {
        return new ProtocolLimits(
                1_048_576, 1_048_576, 2, 16_384, 16_384, 256, 256);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
