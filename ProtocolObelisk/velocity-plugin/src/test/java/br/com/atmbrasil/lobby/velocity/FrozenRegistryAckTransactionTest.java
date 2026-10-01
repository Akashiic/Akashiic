package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FrozenRegistryAckTransactionTest {
    @Test
    void acceptsExactlyOneEmptyCompletedPayload() {
        FrozenRegistryAckTransaction transaction = new FrozenRegistryAckTransaction();

        assertFalse(transaction.complete());
        transaction.accept(NeoForgeFrozenRegistryProfile.COMPLETED_CHANNEL, new byte[0]);
        assertTrue(transaction.complete());
        assertThrows(IllegalArgumentException.class, () -> transaction.accept(
                NeoForgeFrozenRegistryProfile.COMPLETED_CHANNEL, new byte[0]));
    }

    @Test
    void rejectsWrongChannelAndNonEmptyPayload() {
        FrozenRegistryAckTransaction transaction = new FrozenRegistryAckTransaction();

        assertThrows(IllegalArgumentException.class, () -> transaction.accept(
                NeoForgeFrozenRegistryProfile.REGISTRY_CHANNEL, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> transaction.accept(
                NeoForgeFrozenRegistryProfile.COMPLETED_CHANNEL, new byte[] {1}));
        assertFalse(transaction.complete());
    }
}
