package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ToolBeltOpenBeltSlotInventoryPayloadTest {
    @Test
    void acceptsOnlyTheAuditedUnitCodecBody() {
        assertTrue(ToolBeltOpenBeltSlotInventoryPayload.isValid(new byte[0]));
        assertFalse(ToolBeltOpenBeltSlotInventoryPayload.isValid(null));
        assertFalse(ToolBeltOpenBeltSlotInventoryPayload.isValid(new byte[] {0}));
        assertFalse(ToolBeltOpenBeltSlotInventoryPayload.isValid(new byte[] {1}));
        assertFalse(ToolBeltOpenBeltSlotInventoryPayload.isValid(new byte[] {0, 0}));
    }
}
