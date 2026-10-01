package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LogisticsNetworksDefaultNodeVisibilityPayloadTest {
    @Test
    void acceptsOnlyCanonicalOneByteBooleans() {
        assertTrue(LogisticsNetworksDefaultNodeVisibilityPayload.isValid(new byte[] {0}));
        assertTrue(LogisticsNetworksDefaultNodeVisibilityPayload.isValid(new byte[] {1}));
        assertFalse(LogisticsNetworksDefaultNodeVisibilityPayload.isValid(new byte[0]));
        assertFalse(LogisticsNetworksDefaultNodeVisibilityPayload.isValid(new byte[] {2}));
        assertFalse(LogisticsNetworksDefaultNodeVisibilityPayload.isValid(new byte[] {1, 0}));
        assertThrows(NullPointerException.class,
                () -> LogisticsNetworksDefaultNodeVisibilityPayload.isValid(null));
    }
}
