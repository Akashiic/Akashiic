package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class SilentGearSnapshotCaptureTest {
    private static final String FINGERPRINT = "ab".repeat(32);

    @Test
    void exactOrderedTransactionProducesOneDefensiveSnapshot() {
        SilentGearSnapshotCapture capture = new SilentGearSnapshotCapture(
                FINGERPRINT, "atm10-sky-1", 1024, 3072);

        assertEquals(SilentGearSnapshotCapture.Status.PROGRESS,
                capture.observe(SilentGearProtocol.SYNC_TRAITS, payload(2, 11), 1L).status());
        assertEquals(SilentGearSnapshotCapture.Status.PROGRESS,
                capture.observe(SilentGearProtocol.SYNC_MATERIALS, payload(3, 12), 2L).status());
        SilentGearSnapshotCapture.Observation completed = capture.observe(
                SilentGearProtocol.SYNC_PARTS, payload(4, 13), 3L);

        assertEquals(SilentGearSnapshotCapture.Status.COMPLETE, completed.status());
        assertEquals(FINGERPRINT, completed.snapshot().clientRegistryFingerprint());
        assertEquals("atm10-sky-1", completed.snapshot().sourceServer());
        assertArrayEquals(payload(4, 13),
                completed.snapshot().payload(SilentGearProtocol.SYNC_PARTS));
    }

    @Test
    void outOfOrderAndOversizeInputCannotPublishPartialState() {
        SilentGearSnapshotCapture capture = new SilentGearSnapshotCapture(
                FINGERPRINT, "atm10-sky-1", 8, 16);
        SilentGearSnapshotCapture.Observation reset = capture.observe(
                SilentGearProtocol.SYNC_PARTS, payload(1, 1), 1L);

        assertEquals(SilentGearSnapshotCapture.Status.RESET, reset.status());
        assertNull(reset.snapshot());
        assertThrows(IllegalArgumentException.class, () -> capture.observe(
                SilentGearProtocol.SYNC_TRAITS,
                new byte[] {1, 1, 1, 1, 1, 1, 1, 1, 1},
                2L));
    }

    private static byte[] payload(int entries, int marker) {
        return new byte[] {(byte) entries, (byte) marker};
    }
}
