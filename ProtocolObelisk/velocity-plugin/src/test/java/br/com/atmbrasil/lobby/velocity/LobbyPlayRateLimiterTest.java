package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LobbyPlayRateLimiterTest {
    private static final int DEFAULT_PACKETS = 512;
    private static final int DEFAULT_BYTES = 8_388_608;
    private static final int DEFAULT_REFILL_MILLIS = 60_000;

    @Test
    void observedLongLivedLobbyTrafficNeverConsumesALifetimeQuota() {
        long now = 1_000_000_000L;
        LobbyPlayRateLimiter limiter = new LobbyPlayRateLimiter(
                DEFAULT_PACKETS, DEFAULT_BYTES, DEFAULT_REFILL_MILLIS, now);

        // 6.29 payloads/s reproduces 4,096 payloads in roughly 10m51s from the field log.
        // Continue for more than four hours and 10 MB to cross both former lifetime ceilings.
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(159L);
        for (int packet = 0; packet < 100_000; packet++) {
            assertEquals(
                    LobbyPlayRateLimiter.Result.ACCEPTED,
                    limiter.tryAcquire(100, now),
                    "packet " + packet);
            now += intervalNanos;
        }
    }

    @Test
    void packetBurstStillFailsClosed() {
        long now = 5_000_000_000L;
        LobbyPlayRateLimiter limiter = new LobbyPlayRateLimiter(3, 1_024, 60_000, now);

        assertEquals(LobbyPlayRateLimiter.Result.ACCEPTED, limiter.tryAcquire(0, now));
        assertEquals(LobbyPlayRateLimiter.Result.ACCEPTED, limiter.tryAcquire(0, now));
        assertEquals(LobbyPlayRateLimiter.Result.ACCEPTED, limiter.tryAcquire(0, now));
        assertEquals(
                LobbyPlayRateLimiter.Result.PACKET_RATE_EXHAUSTED,
                limiter.tryAcquire(0, now));
    }

    @Test
    void byteBurstStillFailsClosedAndRefillsContinuously() {
        long now = 10_000_000_000L;
        LobbyPlayRateLimiter limiter = new LobbyPlayRateLimiter(100, 1_000, 60_000, now);

        assertEquals(LobbyPlayRateLimiter.Result.ACCEPTED, limiter.tryAcquire(600, now));
        assertEquals(
                LobbyPlayRateLimiter.Result.BYTE_RATE_EXHAUSTED,
                limiter.tryAcquire(600, now));

        now += TimeUnit.SECONDS.toNanos(30L);
        assertEquals(LobbyPlayRateLimiter.Result.ACCEPTED, limiter.tryAcquire(600, now));
    }

    @Test
    void aFullIdlePeriodRestoresBothBurstBudgets() {
        long now = 20_000_000_000L;
        LobbyPlayRateLimiter limiter = new LobbyPlayRateLimiter(1, 32, 10_000, now);

        assertEquals(LobbyPlayRateLimiter.Result.ACCEPTED, limiter.tryAcquire(32, now));
        assertEquals(
                LobbyPlayRateLimiter.Result.PACKET_RATE_EXHAUSTED,
                limiter.tryAcquire(1, now));

        now += TimeUnit.SECONDS.toNanos(10L);
        assertEquals(LobbyPlayRateLimiter.Result.ACCEPTED, limiter.tryAcquire(32, now));
    }

    @Test
    void backwardsOrSubMillisecondTimeDoesNotMintCredits() {
        long now = 30_000_000_000L;
        LobbyPlayRateLimiter limiter = new LobbyPlayRateLimiter(1, 32, 1_000, now);

        assertEquals(LobbyPlayRateLimiter.Result.ACCEPTED, limiter.tryAcquire(1, now));
        assertEquals(
                LobbyPlayRateLimiter.Result.PACKET_RATE_EXHAUSTED,
                limiter.tryAcquire(1, now - 1L));
        assertEquals(
                LobbyPlayRateLimiter.Result.PACKET_RATE_EXHAUSTED,
                limiter.tryAcquire(1, now + 999_999L));
    }

    @Test
    void rejectsInvalidConstructionAndPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> new LobbyPlayRateLimiter(0, 1, 1, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new LobbyPlayRateLimiter(1, 0, 1, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new LobbyPlayRateLimiter(1, 1, 0, 0L));

        LobbyPlayRateLimiter limiter = new LobbyPlayRateLimiter(1, 1, 1, 0L);
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(-1, 0L));
    }
}
