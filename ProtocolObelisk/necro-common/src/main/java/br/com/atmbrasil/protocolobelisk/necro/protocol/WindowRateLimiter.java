package br.com.atmbrasil.protocolobelisk.necro.protocol;

/** Small synchronized one-second limiter for clientbound NecroTempus payloads. */
public final class WindowRateLimiter {
    private final int maximumPackets;
    private final int maximumBytes;
    private long windowStartNanos;
    private int packets;
    private int bytes;

    public WindowRateLimiter(int maximumPackets, int maximumBytes) {
        if (maximumPackets <= 0 || maximumBytes <= 0) {
            throw new IllegalArgumentException("rate limits must be positive");
        }
        this.maximumPackets = maximumPackets;
        this.maximumBytes = maximumBytes;
        this.windowStartNanos = System.nanoTime();
    }

    public synchronized boolean tryAcquire(int payloadBytes) {
        if (payloadBytes < 0 || payloadBytes > maximumBytes) {
            return false;
        }
        long now = System.nanoTime();
        if (now - windowStartNanos >= 1_000_000_000L || now < windowStartNanos) {
            windowStartNanos = now;
            packets = 0;
            bytes = 0;
        }
        if (packets + 1 > maximumPackets || bytes + payloadBytes > maximumBytes) {
            return false;
        }
        packets++;
        bytes += payloadBytes;
        return true;
    }
}
