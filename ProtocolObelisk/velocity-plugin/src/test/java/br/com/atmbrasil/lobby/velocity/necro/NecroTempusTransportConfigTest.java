package br.com.atmbrasil.lobby.velocity.necro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NecroTempusTransportConfigTest {
    @Test
    void defaultsMatchTheProvenStandaloneTransportLimits() {
        NecroTempusTransportConfig config = NecroTempusTransportConfig.defaults();
        assertTrue(config.enabled());
        assertEquals(30_000, config.maximumCompressedNbtBytes());
        assertEquals(262_144, config.maximumDecompressedNbtBytes());
        assertEquals(30_000, config.maximumTabBridgeMessageBytes());
        assertEquals(16_384, config.maximumTabTextBytes());
        assertEquals(64, config.maximumPacketsPerSecond());
        assertEquals(524_288, config.maximumBytesPerSecond());
    }

    @Test
    void constructorRejectsEveryUnsafeBoundary() {
        assertInvalid(0, 262_144, 30_000, 16_384, 64, 524_288);
        assertInvalid(32_768, 262_144, 30_000, 16_384, 64, 524_288);
        assertInvalid(30_000, 0, 30_000, 16_384, 64, 524_288);
        assertInvalid(30_000, 4 * 1024 * 1024 + 1, 30_000, 16_384, 64, 524_288);
        assertInvalid(30_000, 262_144, 41, 16_384, 64, 524_288);
        assertInvalid(30_000, 262_144, 32_768, 16_384, 64, 524_288);
        assertInvalid(30_000, 262_144, 30_000, 0, 64, 524_288);
        assertInvalid(30_000, 262_144, 30_000, 65_536, 64, 524_288);
        assertInvalid(30_000, 262_144, 30_000, 16_384, 0, 524_288);
        assertInvalid(30_000, 262_144, 30_000, 16_384, 1_001, 524_288);
        assertInvalid(30_000, 262_144, 30_000, 16_384, 64, 0);
        assertInvalid(30_000, 262_144, 30_000, 16_384, 64, 16 * 1024 * 1024 + 1);
    }

    private static void assertInvalid(
            int maximumCompressed,
            int maximumDecompressed,
            int maximumTabBridge,
            int maximumTabText,
            int maximumPackets,
            int maximumBytes) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NecroTempusTransportConfig(
                        true,
                        maximumCompressed,
                        maximumDecompressed,
                        maximumTabBridge,
                        maximumTabText,
                        maximumPackets,
                        maximumBytes));
    }
}
