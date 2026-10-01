package br.com.atmbrasil.lobby.velocity;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, pack-fingerprinted copy of the three server-to-client data maps. */
final class SilentGearSnapshot {
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final String clientRegistryFingerprint;
    private final String sourceServer;
    private final long capturedAtEpochMillis;
    private final Map<String, byte[]> payloads;

    SilentGearSnapshot(
            String clientRegistryFingerprint,
            String sourceServer,
            long capturedAtEpochMillis,
            Map<String, byte[]> payloads) {
        this.clientRegistryFingerprint = requireFingerprint(clientRegistryFingerprint);
        if (sourceServer == null || !SERVER_NAME.matcher(sourceServer).matches()) {
            throw new IllegalArgumentException("invalid Silent Gear snapshot source server");
        }
        if (capturedAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid Silent Gear snapshot timestamp");
        }
        Objects.requireNonNull(payloads, "payloads");
        if (!payloads.keySet().equals(new java.util.LinkedHashSet<>(
                SilentGearProtocol.SYNC_CHANNELS))) {
            throw new IllegalArgumentException(
                    "Silent Gear snapshot must contain the exact sync channel set");
        }

        LinkedHashMap<String, byte[]> defensive = new LinkedHashMap<>();
        for (String channelId : SilentGearProtocol.SYNC_CHANNELS) {
            byte[] payload = Objects.requireNonNull(payloads.get(channelId), channelId);
            if (payload.length == 0) {
                throw new IllegalArgumentException("empty Silent Gear snapshot payload");
            }
            defensive.put(channelId, payload.clone());
        }
        this.sourceServer = sourceServer;
        this.capturedAtEpochMillis = capturedAtEpochMillis;
        this.payloads = Collections.unmodifiableMap(defensive);
    }

    String clientRegistryFingerprint() {
        return clientRegistryFingerprint;
    }

    String sourceServer() {
        return sourceServer;
    }

    long capturedAtEpochMillis() {
        return capturedAtEpochMillis;
    }

    byte[] payload(String channelId) {
        byte[] payload = payloads.get(channelId);
        if (payload == null) {
            throw new IllegalArgumentException("snapshot has no payload for " + channelId);
        }
        return payload.clone();
    }

    List<String> channels() {
        return SilentGearProtocol.SYNC_CHANNELS;
    }

    int payloadBytes() {
        return payloads.values().stream().mapToInt(value -> value.length).sum();
    }

    int entryCount(String channelId, int maximumPayloadBytes) {
        return SilentGearProtocol.validateNonEmptyMapPayload(
                payload(channelId), maximumPayloadBytes);
    }

    String payloadSha256(String channelId) {
        return SilentGearProtocol.sha256(payload(channelId));
    }

    boolean hasSamePayloads(SilentGearSnapshot other) {
        if (other == null || !clientRegistryFingerprint.equals(
                other.clientRegistryFingerprint)) {
            return false;
        }
        return SilentGearProtocol.SYNC_CHANNELS.stream().allMatch(channel ->
                Arrays.equals(payloads.get(channel), other.payloads.get(channel)));
    }

    static String requireFingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid client registry fingerprint");
        }
        return value;
    }
}
