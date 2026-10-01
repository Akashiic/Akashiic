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
 * Rewrites only an unsafe protocol-5 Forge channel replay before it reaches the Paper lobby.
 *
 * <p>The handler owns every packet it consumes. A successfully rewritten packet transfers
 * ownership to a newly constructed Velocity packet; a dropped or failed packet completes its
 * promise successfully so the unsafe payload cannot continue toward Paper.</p>
 */
final class LegacyLobbyRegisterGuardHandler extends ChannelDuplexHandler {
    private static final long MAXIMUM_NOTIFICATIONS_PER_ACTION = 8L;

    private final int clientProtocol;
    private final LegacyLobbyRegisterSanitizer.Settings settings;
    private final PacketAccess packetAccess;
    private final Listener listener;
    private final LegacyLobbyRegisterSanitizer sanitizer =
            new LegacyLobbyRegisterSanitizer();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.ACTIVE);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicLong rewrittenPackets = new AtomicLong();
    private final AtomicLong droppedPackets = new AtomicLong();

    LegacyLobbyRegisterGuardHandler(
            int clientProtocol,
            LegacyLobbyRegisterSanitizer.Settings settings,
            PacketAccess packetAccess,
            Listener listener) {
        if (clientProtocol != LegacyForgeHandoffPolicy.MINECRAFT_1_7_10_PROTOCOL) {
            throw new IllegalArgumentException(
                    "legacy lobby REGISTER guard requires Minecraft protocol 5");
        }
        this.clientProtocol = clientProtocol;
        this.settings = Objects.requireNonNull(settings, "settings");
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
        if (!LegacyLobbyRegisterSanitizer.isControlChannel(outerChannel)) {
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
        if (payloadBytes > LegacyLobbyRegisterSanitizer.MAXIMUM_INSPECTION_BYTES) {
            dropByPolicy(
                    message,
                    promise,
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

        LegacyLobbyRegisterSanitizer.Decision decision;
        try {
            decision = sanitizer.inspect(outerChannel, payload, settings);
        } catch (RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }
        if (decision.action() == LegacyLobbyRegisterSanitizer.Action.PASS) {
            super.write(context, message, promise);
            return;
        }
        if (decision.action() == LegacyLobbyRegisterSanitizer.Action.DROP) {
            dropByPolicy(
                    message,
                    promise,
                    decision.originalChannelCount(),
                    decision.reason());
            return;
        }

        Object replacement;
        try {
            replacement = packetAccess.replacement(
                    message, outerChannel, decision.rewrittenPayload());
            if (replacement == null || replacement == message
                    || !packetAccess.isPluginMessage(replacement)) {
                if (replacement != message) {
                    NettyReferenceOwnership.release(replacement);
                }
                throw new IllegalStateException(
                        "Velocity returned an invalid REGISTER replacement packet");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failClosed(message, promise, failure);
            return;
        }

        NettyReferenceOwnership.release(message);
        long sequence = rewrittenPackets.incrementAndGet();
        if (sequence <= MAXIMUM_NOTIFICATIONS_PER_ACTION) {
            notifyRewritten(
                    decision.originalChannelCount(), decision.retainedChannels(), sequence);
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

    boolean matches(int expectedProtocol, LegacyLobbyRegisterSanitizer.Settings expectedSettings) {
        return clientProtocol == expectedProtocol && settings.equals(expectedSettings);
    }

    void deactivate() {
        status.set(Status.CLOSED);
    }

    private void dropByPolicy(
            Object message,
            ChannelPromise promise,
            int originalChannelCount,
            String reason) {
        consume(message, promise);
        long sequence = droppedPackets.incrementAndGet();
        if (sequence > MAXIMUM_NOTIFICATIONS_PER_ACTION) {
            return;
        }
        try {
            listener.dropped(originalChannelCount, reason, sequence);
        } catch (Throwable ignored) {
            // Diagnostics cannot turn a successfully fenced payload into pipeline failure.
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
            // FAILED quarantines plugin messages until the owner disconnects or the channel closes.
        }
    }

    private void notifyRewritten(
            int originalChannelCount, List<String> retainedChannels, long sequence) {
        try {
            listener.rewritten(originalChannelCount, retainedChannels, sequence);
        } catch (Throwable ignored) {
            // Diagnostics cannot turn a successfully rewritten payload into pipeline failure.
        }
    }

    private static void consume(Object message, ChannelPromise promise) {
        NettyReferenceOwnership.release(message);
        promise.trySuccess();
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
        void rewritten(int originalChannelCount, List<String> retainedChannels, long sequence);

        void dropped(int originalChannelCount, String reason, long sequence);

        void failed(Throwable failure);
    }

    private enum Status {
        ACTIVE,
        FAILED,
        CLOSED
    }
}
