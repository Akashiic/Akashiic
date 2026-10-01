package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class IronsSpellbooksCastPayloadTest {
    @Test
    void acceptsOnlyTheAuditedEmptyCodecBody() {
        assertTrue(IronsSpellbooksCastPayload.isValid(new byte[0]));
        assertFalse(IronsSpellbooksCastPayload.isValid(null));
        assertFalse(IronsSpellbooksCastPayload.isValid(new byte[] {0}));
        assertFalse(IronsSpellbooksCastPayload.isValid(new byte[] {1}));
        assertFalse(IronsSpellbooksCastPayload.isValid(new byte[] {0, 0}));
    }
}
