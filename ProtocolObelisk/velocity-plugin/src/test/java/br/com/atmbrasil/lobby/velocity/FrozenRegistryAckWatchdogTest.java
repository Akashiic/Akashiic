package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class FrozenRegistryAckWatchdogTest {
    @Test
    void successfulWriteReplacesWriteDeadlineWithAckDeadline() {
        CompletableFuture<Void> write = new CompletableFuture<>();
        List<Integer> scheduledDelays = new ArrayList<>();
        List<Runnable> scheduledTasks = new ArrayList<>();
        AtomicInteger writeSucceeded = new AtomicInteger();
        AtomicInteger acknowledgementDeadlines = new AtomicInteger();

        FrozenRegistryAckWatchdog.watchWriteThenAck(
                write,
                20_000,
                60_000,
                (delay, task) -> {
                    scheduledDelays.add(delay);
                    scheduledTasks.add(task);
                },
                writeSucceeded::incrementAndGet,
                failure -> {
                    throw new AssertionError(failure);
                },
                acknowledgementDeadlines::incrementAndGet);

        assertEquals(List.of(20_000), scheduledDelays);
        assertFalse(write.isDone());
        write.complete(null);
        assertEquals(List.of(20_000, 60_000), scheduledDelays);
        assertEquals(1, writeSucceeded.get());

        scheduledTasks.get(0).run();
        assertEquals(0, acknowledgementDeadlines.get(),
                "the stale write deadline must not become an ACK deadline");
        scheduledTasks.get(1).run();
        scheduledTasks.get(1).run();
        assertEquals(1, acknowledgementDeadlines.get());
    }

    @Test
    void pendingWriteTimesOutAndLateCompletionCannotArmAckDeadline() {
        CompletableFuture<Void> write = new CompletableFuture<>();
        AtomicReference<Runnable> writeDeadline = new AtomicReference<>();
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger writeSucceeded = new AtomicInteger();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        AtomicInteger acknowledgementDeadlines = new AtomicInteger();

        FrozenRegistryAckWatchdog.watchWriteThenAck(
                write,
                20_000,
                60_000,
                (delay, task) -> {
                    scheduled.incrementAndGet();
                    writeDeadline.set(task);
                },
                writeSucceeded::incrementAndGet,
                observedFailure::set,
                acknowledgementDeadlines::incrementAndGet);
        writeDeadline.get().run();

        assertTrue(observedFailure.get() instanceof TimeoutException);
        assertTrue(observedFailure.get().getMessage().contains("batch write"));
        write.complete(null);

        assertEquals(1, scheduled.get());
        assertEquals(0, writeSucceeded.get());
        assertEquals(0, acknowledgementDeadlines.get());
    }

    @Test
    void writeFailureRejectsAndLeavesTheWriteDeadlineStale() {
        CompletableFuture<Void> write = new CompletableFuture<>();
        AtomicReference<Runnable> writeDeadline = new AtomicReference<>();
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger failureCalls = new AtomicInteger();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        IllegalStateException expected = new IllegalStateException("write failed");

        FrozenRegistryAckWatchdog.watchWriteThenAck(
                write,
                20_000,
                60_000,
                (delay, task) -> {
                    scheduled.incrementAndGet();
                    writeDeadline.set(task);
                },
                () -> { },
                failure -> {
                    failureCalls.incrementAndGet();
                    observedFailure.set(failure);
                },
                () -> { });
        write.completeExceptionally(expected);
        writeDeadline.get().run();

        assertEquals(1, scheduled.get());
        assertEquals(1, failureCalls.get());
        assertSame(expected, observedFailure.get());
    }

    @Test
    void alreadyFailedWriteReportsFailureSynchronouslyBeforeWatchReturns() {
        CompletableFuture<Void> write = new CompletableFuture<>();
        IllegalStateException expected = new IllegalStateException("immediate write failure");
        write.completeExceptionally(expected);
        AtomicBoolean returned = new AtomicBoolean();
        AtomicInteger scheduled = new AtomicInteger();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();

        FrozenRegistryAckWatchdog.watchWriteThenAck(
                write,
                20_000,
                60_000,
                (delay, task) -> scheduled.incrementAndGet(),
                () -> { },
                failure -> {
                    assertFalse(returned.get());
                    observedFailure.set(failure);
                },
                () -> { });
        returned.set(true);

        assertEquals(1, scheduled.get());
        assertSame(expected, observedFailure.get());
    }

    @Test
    void rejectedWriteDeadlineSchedulingFailsSynchronouslyAndExactlyOnce() {
        CompletableFuture<Void> write = new CompletableFuture<>();
        RejectedExecutionException expected = new RejectedExecutionException("scheduler stopped");
        AtomicInteger failureCalls = new AtomicInteger();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();

        FrozenRegistryAckWatchdog.watchWriteThenAck(
                write,
                20_000,
                30_000,
                (delay, task) -> {
                    throw expected;
                },
                () -> {
                    throw new AssertionError("write success must remain fenced");
                },
                failure -> {
                    failureCalls.incrementAndGet();
                    observedFailure.set(failure);
                },
                () -> {
                    throw new AssertionError("ACK deadline must remain fenced");
                });
        write.complete(null);

        assertEquals(1, failureCalls.get());
        assertTrue(observedFailure.get() instanceof IllegalStateException);
        assertSame(expected, observedFailure.get().getCause());
    }

    @Test
    void rejectedAckDeadlineSchedulingFailsWithoutPublishingWriteSuccess() {
        CompletableFuture<Void> write = new CompletableFuture<>();
        AtomicReference<Runnable> writeDeadline = new AtomicReference<>();
        AtomicInteger schedules = new AtomicInteger();
        AtomicInteger writeSucceeded = new AtomicInteger();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        RejectedExecutionException expected = new RejectedExecutionException("scheduler stopped");

        FrozenRegistryAckWatchdog.watchWriteThenAck(
                write,
                20_000,
                30_000,
                (delay, task) -> {
                    if (schedules.incrementAndGet() == 1) {
                        writeDeadline.set(task);
                    } else {
                        throw expected;
                    }
                },
                writeSucceeded::incrementAndGet,
                observedFailure::set,
                () -> { });
        write.complete(null);
        writeDeadline.get().run();

        assertEquals(2, schedules.get());
        assertEquals(0, writeSucceeded.get());
        assertTrue(observedFailure.get() instanceof IllegalStateException);
        assertSame(expected, observedFailure.get().getCause());
    }

    @Test
    void rejectsNonPositiveDeadlines() {
        assertThrows(IllegalArgumentException.class, () ->
                FrozenRegistryAckWatchdog.watchWriteThenAck(
                        CompletableFuture.completedFuture(null),
                        0,
                        60_000,
                        (delay, task) -> { },
                        () -> { },
                        failure -> { },
                        () -> { }));
        assertThrows(IllegalArgumentException.class, () ->
                FrozenRegistryAckWatchdog.watchWriteThenAck(
                        CompletableFuture.completedFuture(null),
                        20_000,
                        0,
                        (delay, task) -> { },
                        () -> { },
                        failure -> { },
                        () -> { }));
    }

    @Test
    void frozenTransactionFencesEveryCallbackAndLogsSentOnlyAfterWriteSuccess()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java"),
                StandardCharsets.UTF_8);
        int transactionStart = source.indexOf("private void sendFrozenRegistryTransaction(");
        int acknowledgementStart = source.indexOf(
                "private void receiveFrozenRegistryAcknowledgement(", transactionStart);
        assertTrue(transactionStart >= 0 && acknowledgementStart > transactionStart);
        String transaction = source.substring(transactionStart, acknowledgementStart);

        int resetTimestamp = transaction.indexOf("session.frozenRegistrySentNanos = 0L;");
        int send = transaction.indexOf("CompletableFuture<Void> write = sender.send(");
        int arm = transaction.indexOf("FrozenRegistryAckWatchdog.watchWriteThenAck(");
        int sentTimestamp = transaction.indexOf(
                "session.frozenRegistrySentNanos = System.nanoTime();", arm);
        int sentLog = transaction.indexOf("\"Sent exact NeoForge frozen-registry transaction");
        assertTrue(resetTimestamp >= 0 && resetTimestamp < send);
        assertTrue(send < arm && arm < sentTimestamp && sentTimestamp < sentLog);
        assertTrue(transaction.substring(arm, sentTimestamp).contains(
                "FROZEN_REGISTRY_WRITE_TIMEOUT_MILLIS"));
        assertTrue(transaction.substring(arm, sentTimestamp).contains(
                "currentConfig.frozenRegistryAckTimeoutMillis()"));

        String lobbyGenerationFence =
                "session.lobbyCycleGeneration != lobbyGeneration";
        assertEquals(3, occurrences(transaction, lobbyGenerationFence),
                "write success, failure and ACK deadline callbacks must share the lobby fence");
        String postArm = transaction.substring(arm);
        assertTrue(postArm.contains(
                "session.state != State.LOBBY_SYNCING_FROZEN_REGISTRIES"));
        assertEquals(3, occurrences(postArm,
                "session.frozenRegistryGeneration != generation"));
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        for (int offset = 0; (offset = source.indexOf(token, offset)) >= 0;
                offset += token.length()) {
            count++;
        }
        return count;
    }
}
