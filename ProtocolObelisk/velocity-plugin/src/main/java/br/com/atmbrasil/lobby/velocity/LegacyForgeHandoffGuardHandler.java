package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.LegacyForgeHandoffPolicy.ControlOperation;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drops only legacy REGISTER/UNREGISTER writes that would otherwise leak to the old lobby.
 *
 * <p>Velocity has already decoded the client packet and updated its client-channel set before this
 * outbound backend boundary. Consuming the old-lobby write therefore preserves Velocity's normal
 * replay to the new Forge backend while protecting Paper's per-player channel registry.</p>
 */
final class LegacyForgeHandoffGuardHandler extends ChannelDuplexHandler {
    private static final long MAXIMUM_SUPPRESSION_NOTIFICATIONS = 8L;
    private final int clientProtocol;
    private final String guardedBackend;
    private final String lobbyServer;
    private final Set<String> allowedTargets;
    private final PacketAccess packetAccess;
    private final TargetProbe targetProbe;
    private final Listener listener;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.ACTIVE);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicLong suppressedPackets = new AtomicLong();

    LegacyForgeHandoffGuardHandler(
            int clientProtocol,
            String guardedBackend,
            String lobbyServer,
            Set<String> allowedTargets,
            PacketAccess packetAccess,
            TargetProbe targetProbe,
            Listener listener) {
        this.clientProtocol = clientProtocol;
        this.guardedBackend = Objects.requireNonNull(guardedBackend, "guardedBackend");
        this.lobbyServer = Objects.requireNonNull(lobbyServer, "lobbyServer");
        this.allowedTargets = Set.copyOf(Objects.requireNonNull(allowedTargets, "allowedTargets"));
        this.packetAccess = Objects.requireNonNull(packetAccess, "packetAccess");
        this.targetProbe = Objects.requireNonNull(targetProbe, "targetProbe");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
            throws Exception {
        Status current = status.get();
        if (current == Status.CLOSED || !packetAccess.isPluginMessage(message)) {
            super.write(context, message, promise);
            return;
        }
        if (current == Status.FAILED) {
            consume(message, promise);
            return;
        }

        ControlOperation operation;
        try {
            operation = LegacyForgeHandoffPolicy.classify(packetAccess.outerChannel(message));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }
        if (operation == ControlOperation.OTHER) {
            super.write(context, message, promise);
            return;
        }

        String inFlightTarget;
        try {
            inFlightTarget = targetProbe.inFlightTarget();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }
        if (!LegacyForgeHandoffPolicy.shouldSuppress(
                clientProtocol,
                guardedBackend,
                lobbyServer,
                inFlightTarget,
                allowedTargets,
                operation)) {
            super.write(context, message, promise);
            return;
        }

        consume(message, promise);
        long sequence = suppressedPackets.incrementAndGet();
        if (sequence > MAXIMUM_SUPPRESSION_NOTIFICATIONS) {
            return;
        }
        try {
            listener.suppressed(operation, inFlightTarget, sequence);
        } catch (Throwable ignored) {
            // Observability must never turn a successfully fenced packet into pipeline failure.
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        deactivate();
        super.channelInactive(context);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) throws Exception {
        deactivate();
        super.handlerRemoved(context);
    }

    boolean active() {
        return status.get() == Status.ACTIVE;
    }

    long suppressedPackets() {
        return suppressedPackets.get();
    }

    void deactivate() {
        status.compareAndSet(Status.ACTIVE, Status.CLOSED);
    }

    private void failClosed(Object message, ChannelPromise promise, Throwable failure) {
        status.set(Status.FAILED);
        consume(message, promise);
        if (!failureReported.compareAndSet(false, true)) {
            return;
        }
        try {
            listener.failed(failure);
        } catch (Throwable ignored) {
            // The FAILED state keeps plugin messages quarantined until the owner disconnects.
        }
    }

    private static void consume(Object message, ChannelPromise promise) {
        NettyReferenceOwnership.release(message);
        promise.trySuccess();
    }

    interface PacketAccess {
        boolean isPluginMessage(Object message);

        String outerChannel(Object message) throws ReflectiveOperationException;
    }

    @FunctionalInterface
    interface TargetProbe {
        String inFlightTarget() throws ReflectiveOperationException;
    }

    interface Listener {
        void suppressed(ControlOperation operation, String inFlightTarget, long sequence);

        void failed(Throwable failure);
    }

    private enum Status {
        ACTIVE,
        FAILED,
        CLOSED
    }
}
