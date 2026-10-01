package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DeeperDarkerUseTransmitterPayloadTest {
    @Test
    void acceptsOnlyTheAuditedTrueHotkeyBody() {
        assertTrue(DeeperDarkerUseTransmitterPayload.isValid(new byte[] {1}));
        assertFalse(DeeperDarkerUseTransmitterPayload.isValid(null));
        assertFalse(DeeperDarkerUseTransmitterPayload.isValid(new byte[0]));
        assertFalse(DeeperDarkerUseTransmitterPayload.isValid(new byte[] {0}));
        assertFalse(DeeperDarkerUseTransmitterPayload.isValid(new byte[] {2}));
        assertFalse(DeeperDarkerUseTransmitterPayload.isValid(new byte[] {1, 0}));
    }
}
