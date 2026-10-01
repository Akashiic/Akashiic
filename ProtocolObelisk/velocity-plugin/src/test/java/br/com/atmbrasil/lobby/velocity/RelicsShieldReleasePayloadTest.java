package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RelicsShieldReleasePayloadTest {
    @Test
    void acceptsOnlyTheAuditedTrueReleaseBody() {
        assertTrue(RelicsShieldReleasePayload.isValid(new byte[] {1}));
        assertFalse(RelicsShieldReleasePayload.isValid(null));
        assertFalse(RelicsShieldReleasePayload.isValid(new byte[0]));
        assertFalse(RelicsShieldReleasePayload.isValid(new byte[] {0}));
        assertFalse(RelicsShieldReleasePayload.isValid(new byte[] {2}));
        assertFalse(RelicsShieldReleasePayload.isValid(new byte[] {1, 0}));
    }
}
