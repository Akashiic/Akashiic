package br.com.atmbrasil.lobby.velocity;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Strict in-memory transaction for one real NeoForge backend sync sequence. */
final class SilentGearSnapshotCapture {
    private final String clientRegistryFingerprint;
    private final String sourceServer;
    private final int maximumPayloadBytes;
    private final int maximumTotalBytes;
    private final LinkedHashMap<String, byte[]> payloads = new LinkedHashMap<>();

    private int expectedChannelIndex;
    private int totalBytes;

    SilentGearSnapshotCapture(
            String clientRegistryFingerprint,
            String sourceServer,
            int maximumPayloadBytes,
            int maximumTotalBytes) {
        this.clientRegistryFingerprint = SilentGearSnapshot.requireFingerprint(
                clientRegistryFingerprint);
        this.sourceServer = Objects.requireNonNull(sourceServer, "sourceServer");
        if (maximumPayloadBytes < 1 || maximumTotalBytes < maximumPayloadBytes) {
            throw new IllegalArgumentException("invalid Silent Gear capture limits");
        }
        this.maximumPayloadBytes = maximumPayloadBytes;
        this.maximumTotalBytes = maximumTotalBytes;
    }

    Observation observe(String channelId, byte[] payload, long capturedAtEpochMillis) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(payload, "payload");
        if (!SilentGearProtocol.isSyncChannel(channelId)) {
            return new Observation(Status.IGNORED, null, 0, channelId);
        }

        String expected = SilentGearProtocol.SYNC_CHANNELS.get(expectedChannelIndex);
        if (!channelId.equals(expected)) {
            reset();
            if (!channelId.equals(SilentGearProtocol.SYNC_TRAITS)) {
                return new Observation(Status.RESET, null, 0, channelId);
            }
        }

        int entries = SilentGearProtocol.validateNonEmptyMapPayload(
                payload, maximumPayloadBytes);
        long nextTotal = (long) totalBytes + payload.length;
        if (nextTotal > maximumTotalBytes) {
            reset();
            throw new IllegalArgumentException("Silent Gear snapshot exceeds total byte budget");
        }

        payloads.put(channelId, payload.clone());
        totalBytes = (int) nextTotal;
        expectedChannelIndex++;
        if (expectedChannelIndex < SilentGearProtocol.SYNC_CHANNELS.size()) {
            return new Observation(Status.PROGRESS, null, entries, channelId);
        }

        SilentGearSnapshot snapshot = new SilentGearSnapshot(
                clientRegistryFingerprint,
                sourceServer,
                capturedAtEpochMillis,
                new LinkedHashMap<>(payloads));
        return new Observation(Status.COMPLETE, snapshot, entries, channelId);
    }

    private void reset() {
        payloads.clear();
        expectedChannelIndex = 0;
        totalBytes = 0;
    }

    enum Status {
        IGNORED,
        PROGRESS,
        COMPLETE,
        RESET
    }

    record Observation(Status status, SilentGearSnapshot snapshot, int entryCount, String channel) {
        Observation {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(channel, "channel");
        }
    }
}
