package br.com.atmbrasil.lobby.velocity.necro;

import br.com.atmbrasil.protocolobelisk.necro.protocol.NecroTempusPacketCodec;

/** Immutable, caller-owned limits for the embedded NecroTempus Velocity transport. */
public record NecroTempusTransportConfig(
        boolean enabled,
        int maximumCompressedNbtBytes,
        int maximumDecompressedNbtBytes,
        int maximumTabBridgeMessageBytes,
        int maximumTabTextBytes,
        int maximumPacketsPerSecond,
        int maximumBytesPerSecond) {
    private static final int TAB_BRIDGE_FIXED_BYTES = 42;

    public NecroTempusTransportConfig {
        requireRange(
                "maximumCompressedNbtBytes",
                maximumCompressedNbtBytes,
                1,
                NecroTempusPacketCodec.MINECRAFT_1_7_SIGNED_SHORT_MAXIMUM);
        requireRange(
                "maximumDecompressedNbtBytes",
                maximumDecompressedNbtBytes,
                1,
                4 * 1024 * 1024);
        requireRange(
                "maximumTabBridgeMessageBytes",
                maximumTabBridgeMessageBytes,
                TAB_BRIDGE_FIXED_BYTES,
                NecroTempusPacketCodec.MINECRAFT_1_7_SIGNED_SHORT_MAXIMUM);
        requireRange("maximumTabTextBytes", maximumTabTextBytes, 1, 65_535);
        requireRange("maximumPacketsPerSecond", maximumPacketsPerSecond, 1, 1_000);
        requireRange("maximumBytesPerSecond", maximumBytesPerSecond, 1, 16 * 1024 * 1024);
    }

    public static NecroTempusTransportConfig defaults() {
        return new NecroTempusTransportConfig(
                true,
                30_000,
                262_144,
                30_000,
                16_384,
                64,
                524_288);
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }
}
