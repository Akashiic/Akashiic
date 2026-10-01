package br.com.atmbrasil.lobby.velocity;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Filters only the Paper-bound copy of modern REGISTER/UNREGISTER packets.
 *
 * <p>Velocity has already decoded the original packet and updated its process-wide client channel
 * state before this outbound handler runs. Consuming or replacing the backend write therefore does
 * not erase the client's full capability set from Velocity, Voice Chat or future backend switches.</p>
 */
final class ModernLobbyRegisterGuardHandler extends ChannelDuplexHandler {
    private static final long MAXIMUM_NOTIFICATIONS_PER_ACTION = 8L;

    private final int clientProtocol;
    private final String guardIdentity;
    private final Policy policy;
    private final PacketAccess packetAccess;
    private final Listener listener;
    private final ModernLobbyRegisterSanitizer sanitizer =
            new ModernLobbyRegisterSanitizer();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.ACTIVE);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicLong rewrittenPackets = new AtomicLong();
    private final AtomicLong droppedPackets = new AtomicLong();

    ModernLobbyRegisterGuardHandler(
            int clientProtocol,
            String guardIdentity,
            Policy policy,
            PacketAccess packetAccess,
            Listener listener) {
        if (clientProtocol < 393) {
            throw new IllegalArgumentException(
                    "modern lobby REGISTER guard requires a 1.13+ protocol");
        }
        if (guardIdentity == null || guardIdentity.isBlank() || guardIdentity.length() > 128) {
            throw new IllegalArgumentException(
                    "guardIdentity must contain 1..128 characters");
        }
        this.clientProtocol = clientProtocol;
        this.guardIdentity = guardIdentity;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.packetAccess = Objects.requireNonNull(packetAccess, "packetAccess");
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

        String outerChannel;
        try {
            outerChannel = packetAccess.outerChannel(message);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }
        if (!ModernLobbyRegisterSanitizer.isControlChannel(outerChannel)) {
            super.write(context, message, promise);
            return;
        }

        final ModernLobbyRegisterSanitizer.Settings settings;
        try {
            settings = policy.currentSettings();
        } catch (RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }
        if (settings == null) {
            // Native vanilla/protocol-bypass sessions retain Velocity's normal behavior.
            super.write(context, message, promise);
            return;
        }

        int payloadBytes;
        try {
            payloadBytes = packetAccess.readablePayloadBytes(message);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }
        if (payloadBytes < 0) {
            failClosed(
                    message,
                    promise,
                    new IllegalStateException("Velocity returned a negative payload size"));
            return;
        }
        if (payloadBytes > ModernLobbyRegisterSanitizer.MAXIMUM_INSPECTION_BYTES) {
            dropByPolicy(
                    message,
                    promise,
                    outerChannel,
                    -1,
                    "control payload exceeds the bounded inspection limit");
            return;
        }

        byte[] payload;
        try {
            payload = packetAccess.copyPayload(message, payloadBytes);
            if (payload.length != payloadBytes) {
                throw new IllegalStateException(
                        "Velocity plugin-message payload changed during bounded inspection");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }

        final ModernLobbyRegisterSanitizer.Decision decision;
        try {
            decision = sanitizer.inspect(outerChannel, payload, settings);
        } catch (RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }
        if (decision.action() == ModernLobbyRegisterSanitizer.Action.PASS) {
            super.write(context, message, promise);
            return;
        }
        if (decision.action() == ModernLobbyRegisterSanitizer.Action.DROP) {
            dropByPolicy(
                    message,
                    promise,
                    outerChannel,
                    decision.originalChannelCount(),
                    decision.reason());
            return;
        }

        Object replacement;
        try {
            replacement = packetAccess.replacement(
                    message, outerChannel, decision.rewrittenPayload());
            if (replacement == null
                    || replacement == message
                    || !packetAccess.isPluginMessage(replacement)) {
                if (replacement != message) {
                    NettyReferenceOwnership.release(replacement);
                }
                throw new IllegalStateException(
                        "Velocity returned an invalid modern REGISTER replacement packet");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }

        NettyReferenceOwnership.release(message);
        long sequence = rewrittenPackets.incrementAndGet();
        if (sequence <= MAXIMUM_NOTIFICATIONS_PER_ACTION) {
            notifyRewritten(
                    outerChannel,
                    decision.originalChannelCount(),
                    decision.retainedChannels(),
                    sequence);
        }
        super.write(context, replacement, promise);
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

    long rewrittenPackets() {
        return rewrittenPackets.get();
    }

    long droppedPackets() {
        return droppedPackets.get();
    }

    boolean matches(int expectedProtocol, String expectedIdentity) {
        return clientProtocol == expectedProtocol && guardIdentity.equals(expectedIdentity);
    }

    void deactivate() {
        status.set(Status.CLOSED);
    }

    private void dropByPolicy(
            Object message,
            ChannelPromise promise,
            String outerChannel,
            int originalChannelCount,
            String reason) {
        consume(message, promise);
        long sequence = droppedPackets.incrementAndGet();
        if (sequence > MAXIMUM_NOTIFICATIONS_PER_ACTION) {
            return;
        }
        try {
            listener.dropped(outerChannel, originalChannelCount, reason, sequence);
        } catch (Throwable ignored) {
            // Diagnostics cannot reopen a packet which was already fenced.
        }
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
            // FAILED quarantines plugin messages until the owner disconnects or channel closes.
        }
    }

    private void notifyRewritten(
            String outerChannel,
            int originalChannelCount,
            List<String> retainedChannels,
            long sequence) {
        try {
            listener.rewritten(
                    outerChannel, originalChannelCount, retainedChannels, sequence);
        } catch (Throwable ignored) {
            // Diagnostics cannot turn a successful replacement into a pipeline failure.
        }
    }

    private static void consume(Object message, ChannelPromise promise) {
        NettyReferenceOwnership.release(message);
        promise.trySuccess();
    }

    @FunctionalInterface
    interface Policy {
        /** Returns null while the exact connection must retain native Velocity forwarding. */
        ModernLobbyRegisterSanitizer.Settings currentSettings();
    }

    interface PacketAccess {
        boolean isPluginMessage(Object message);

        String outerChannel(Object message) throws ReflectiveOperationException;

        int readablePayloadBytes(Object message) throws ReflectiveOperationException;

        byte[] copyPayload(Object message, int expectedBytes) throws ReflectiveOperationException;

        Object replacement(Object message, String outerChannel, byte[] payload)
                throws ReflectiveOperationException;
    }

    interface Listener {
        void rewritten(
                String outerChannel,
                int originalChannelCount,
                List<String> retainedChannels,
                long sequence);

        void dropped(
                String outerChannel,
                int originalChannelCount,
                String reason,
                long sequence);

        void failed(Throwable failure);
    }

    private enum Status {
        ACTIVE,
        FAILED,
        CLOSED
    }
}
