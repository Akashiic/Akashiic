package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EternalStarlightSimpleActionPayloadTest {
    @Test
    void acceptsOnlyTheTwoAuditedClientToServerActions() {
        assertTrue(EternalStarlightSimpleActionPayload.isValid(new byte[] {
            12, 's', 'w', 'i', 'n', 'g', '_', 'a', 't', 't', 'a', 'c', 'k'
        }));
        assertTrue(EternalStarlightSimpleActionPayload.isValid(new byte[] {
            12, 's', 'w', 'i', 't', 'c', 'h', '_', 'c', 'r', 'e', 's', 't'
        }));
    }

    @Test
    void rejectsClientboundUnknownNonCanonicalAndResidualBodies() {
        assertFalse(EternalStarlightSimpleActionPayload.isValid(null));
        assertFalse(EternalStarlightSimpleActionPayload.isValid(new byte[0]));
        assertFalse(EternalStarlightSimpleActionPayload.isValid(new byte[] {
            13, 'c', 'l', 'e', 'a', 'r', '_', 'w', 'e', 'a', 't', 'h', 'e', 'r'
        }));
        assertFalse(EternalStarlightSimpleActionPayload.isValid(new byte[] {
            (byte) 0x8c, 0, 's', 'w', 'i', 'n', 'g', '_', 'a', 't', 't', 'a', 'c', 'k'
        }));
        assertFalse(EternalStarlightSimpleActionPayload.isValid(new byte[] {
            12, 's', 'w', 'i', 'n', 'g', '_', 'a', 't', 't', 'a', 'c', 'k', 0
        }));
    }
}
