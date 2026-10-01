package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SilentGearAckTransactionTest {
    private static final String PROFILE = "atm10-tts-2.0.2_silentgear-4.1.3.1";

    @Test
    void consumesExactlyThreeEmptyAcksInSentMapOrder() {
        SilentGearAckTransaction transaction = new SilentGearAckTransaction(PROFILE);
        List<String> acknowledged = new ArrayList<>();

        for (int ordinal = 1; ordinal <= 3; ordinal++) {
            SilentGearAckTransaction.Acknowledgement ack = transaction.accept(
                    SilentGearProtocol.ACK, new byte[0]);
            assertEquals(ordinal, ack.ordinal());
            assertEquals(3, ack.expected());
            acknowledged.add(ack.acknowledgedChannel());
        }

        assertEquals(SilentGearProtocol.SYNC_CHANNELS, acknowledged);
        assertEquals(3, transaction.received());
        assertTrue(transaction.complete());
    }

    @Test
    void nonEmptyWrongChannelAndDuplicateAcksFailClosed() {
        SilentGearAckTransaction transaction = new SilentGearAckTransaction(PROFILE);

        assertThrows(IllegalArgumentException.class,
                () -> transaction.accept(SilentGearProtocol.ACK, new byte[] {0}));
        assertThrows(IllegalArgumentException.class,
                () -> transaction.accept("silentgear:not_ack", new byte[0]));
        assertEquals(0, transaction.received());
        assertFalse(transaction.complete());

        for (int index = 0; index < 3; index++) {
            transaction.accept(SilentGearProtocol.ACK, new byte[0]);
        }
        assertThrows(IllegalArgumentException.class,
                () -> transaction.accept(SilentGearProtocol.ACK, new byte[0]));
    }
}
