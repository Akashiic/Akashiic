package br.com.atmbrasil.lobby.velocity;

/**
 * Dual token bucket for client-to-server lobby PLAY payloads.
 *
 * <p>The configured packet and byte maxima are burst capacities, not lifetime quotas. Both
 * budgets refill continuously and are completely restored after one idle refill period. This
 * keeps a bounded fail-closed flood boundary without placing a time limit on a normal lobby
 * session.
 */
final class LobbyPlayRateLimiter {
    enum Result {
        ACCEPTED,
        PACKET_RATE_EXHAUSTED,
        BYTE_RATE_EXHAUSTED
    }

    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final int packetCapacity;
    private final int byteCapacity;
    private final int refillPeriodMillis;
    private final long packetCreditCapacity;
    private final long byteCreditCapacity;

    private long availablePacketCredits;
    private long availableByteCredits;
    private long lastRefillNanos;

    LobbyPlayRateLimiter(
            int packetCapacity,
            int byteCapacity,
            int refillPeriodMillis,
            long startedNanos) {
        if (packetCapacity <= 0) {
            throw new IllegalArgumentException("packetCapacity must be positive");
        }
        if (byteCapacity <= 0) {
            throw new IllegalArgumentException("byteCapacity must be positive");
        }
        if (refillPeriodMillis <= 0) {
            throw new IllegalArgumentException("refillPeriodMillis must be positive");
        }
        this.packetCapacity = packetCapacity;
        this.byteCapacity = byteCapacity;
        this.refillPeriodMillis = refillPeriodMillis;
        try {
            packetCreditCapacity = Math.multiplyExact(
                    (long) packetCapacity, refillPeriodMillis);
            byteCreditCapacity = Math.multiplyExact((long) byteCapacity, refillPeriodMillis);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("rate-limiter capacity is too large", exception);
        }
        availablePacketCredits = packetCreditCapacity;
        availableByteCredits = byteCreditCapacity;
        lastRefillNanos = startedNanos;
    }

    Result tryAcquire(int payloadBytes, long nowNanos) {
        if (payloadBytes < 0) {
            throw new IllegalArgumentException("payloadBytes cannot be negative");
        }
        refill(nowNanos);

        long packetCost = refillPeriodMillis;
        final long byteCost;
        try {
            byteCost = Math.multiplyExact((long) payloadBytes, refillPeriodMillis);
        } catch (ArithmeticException exception) {
            return Result.BYTE_RATE_EXHAUSTED;
        }
        if (availablePacketCredits < packetCost) {
            return Result.PACKET_RATE_EXHAUSTED;
        }
        if (availableByteCredits < byteCost) {
            return Result.BYTE_RATE_EXHAUSTED;
        }

        availablePacketCredits -= packetCost;
        availableByteCredits -= byteCost;
        return Result.ACCEPTED;
    }

    int packetCapacity() {
        return packetCapacity;
    }

    int byteCapacity() {
        return byteCapacity;
    }

    int refillPeriodMillis() {
        return refillPeriodMillis;
    }

    private void refill(long nowNanos) {
        long elapsedNanos = nowNanos - lastRefillNanos;
        if (elapsedNanos <= 0L) {
            return;
        }
        long elapsedMillis = elapsedNanos / NANOS_PER_MILLISECOND;
        if (elapsedMillis <= 0L) {
            return;
        }
        if (elapsedMillis >= refillPeriodMillis) {
            availablePacketCredits = packetCreditCapacity;
            availableByteCredits = byteCreditCapacity;
            lastRefillNanos = nowNanos;
            return;
        }

        availablePacketCredits = Math.min(
                packetCreditCapacity,
                availablePacketCredits + elapsedMillis * packetCapacity);
        availableByteCredits = Math.min(
                byteCreditCapacity,
                availableByteCredits + elapsedMillis * byteCapacity);
        lastRefillNanos += elapsedMillis * NANOS_PER_MILLISECOND;
    }
}
