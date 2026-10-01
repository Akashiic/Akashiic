package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SilentGearSnapshotStoreTest {
    private static final String FINGERPRINT = "cd".repeat(32);

    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicRoundTripIsFingerprintKeyedAndDeduplicated() throws Exception {
        SilentGearSnapshotStore store = new SilentGearSnapshotStore(
                temporaryDirectory, 1024, 3072, 4);
        SilentGearSnapshot snapshot = snapshot();

        assertTrue(store.store(snapshot).changed());
        assertFalse(store.store(snapshot).changed());

        SilentGearSnapshotStore reloaded = new SilentGearSnapshotStore(
                temporaryDirectory, 1024, 3072, 4);
        assertEquals(1, reloaded.load().loadedSnapshots());
        SilentGearSnapshot actual = reloaded.find(FINGERPRINT).orElseThrow();
        assertArrayEquals(snapshot.payload(SilentGearProtocol.SYNC_MATERIALS),
                actual.payload(SilentGearProtocol.SYNC_MATERIALS));
    }

    @Test
    void tamperedEnvelopeIsIgnoredWithoutDeletingOperatorEvidence() throws Exception {
        SilentGearSnapshotStore store = new SilentGearSnapshotStore(
                temporaryDirectory, 1024, 3072, 4);
        store.store(snapshot());
        Path file = temporaryDirectory.resolve(FINGERPRINT + ".sgsnapshot");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);

        SilentGearSnapshotStore reloaded = new SilentGearSnapshotStore(
                temporaryDirectory, 1024, 3072, 4);
        SilentGearSnapshotStore.LoadResult result = reloaded.load();

        assertEquals(0, result.loadedSnapshots());
        assertEquals(1, result.warnings().size());
        assertTrue(Files.exists(file));
    }

    private static SilentGearSnapshot snapshot() {
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        payloads.put(SilentGearProtocol.SYNC_TRAITS, new byte[] {2, 10});
        payloads.put(SilentGearProtocol.SYNC_MATERIALS, new byte[] {3, 11});
        payloads.put(SilentGearProtocol.SYNC_PARTS, new byte[] {4, 12});
        return new SilentGearSnapshot(
                FINGERPRINT, "atm10-sky-1", 1_723_300_000_000L, payloads);
    }
}
