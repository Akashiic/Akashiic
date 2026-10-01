package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Bounds both phases of a frozen-registry transaction: write completion, then client ACK. */
final class FrozenRegistryAckWatchdog {
    private FrozenRegistryAckWatchdog() {
    }

    static void watchWriteThenAck(
            CompletableFuture<Void> write,
            int writeTimeoutMillis,
            int acknowledgementTimeoutMillis,
            Scheduler scheduler,
            Runnable writeSucceeded,
            Consumer<Throwable> failureCallback,
            Runnable acknowledgementDeadline) {
        Objects.requireNonNull(write, "write");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(writeSucceeded, "writeSucceeded");
        Objects.requireNonNull(failureCallback, "failureCallback");
        Objects.requireNonNull(acknowledgementDeadline, "acknowledgementDeadline");
        if (writeTimeoutMillis < 1) {
            throw new IllegalArgumentException("writeTimeoutMillis must be positive");
        }
        if (acknowledgementTimeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "acknowledgementTimeoutMillis must be positive");
        }

        AtomicReference<Phase> phase = new AtomicReference<>(Phase.WRITING);
        try {
            scheduler.schedule(writeTimeoutMillis, () -> {
                if (phase.compareAndSet(Phase.WRITING, Phase.TERMINAL)) {
                    failureCallback.accept(new TimeoutException(
                            "frozen-registry batch write did not complete within "
                                    + writeTimeoutMillis + " ms"));
                }
            });
        } catch (RuntimeException schedulingFailure) {
            if (phase.compareAndSet(Phase.WRITING, Phase.TERMINAL)) {
                failureCallback.accept(new IllegalStateException(
                        "could not schedule the frozen-registry write deadline",
                        schedulingFailure));
            }
            return;
        }

        write.whenComplete((ignored, writeFailure) -> {
            if (writeFailure != null) {
                if (phase.compareAndSet(Phase.WRITING, Phase.TERMINAL)) {
                    failureCallback.accept(writeFailure);
                }
                return;
            }
            if (!phase.compareAndSet(Phase.WRITING, Phase.WAITING_FOR_ACK)) {
                return;
            }
            try {
                scheduler.schedule(acknowledgementTimeoutMillis, () -> {
                    if (phase.compareAndSet(Phase.WAITING_FOR_ACK, Phase.TERMINAL)) {
                        acknowledgementDeadline.run();
                    }
                });
            } catch (RuntimeException schedulingFailure) {
                if (phase.compareAndSet(Phase.WAITING_FOR_ACK, Phase.TERMINAL)) {
                    failureCallback.accept(new IllegalStateException(
                            "could not schedule the frozen-registry ACK deadline",
                            schedulingFailure));
                }
                return;
            }
            if (phase.get() != Phase.WAITING_FOR_ACK) {
                return;
            }
            try {
                writeSucceeded.run();
            } catch (RuntimeException callbackFailure) {
                if (phase.compareAndSet(Phase.WAITING_FOR_ACK, Phase.TERMINAL)) {
                    failureCallback.accept(callbackFailure);
                }
            }
        });
    }

    @FunctionalInterface
    interface Scheduler {
        void schedule(int delayMillis, Runnable task);
    }

    private enum Phase {
        WRITING,
        WAITING_FOR_ACK,
        TERMINAL
    }
}
