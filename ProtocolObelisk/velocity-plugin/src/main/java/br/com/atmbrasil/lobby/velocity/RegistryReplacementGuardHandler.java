package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Inspection;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transforms exactly one outbound Paper CONFIG registry packet before Velocity encodes it.
 *
 * <p>The Minecraft 1.21.1 client appends repeated packets for one registry key, so reviewed
 * enchantment compatibility must operate on Paper's original {@code minecraft:enchantment}
 * object. ATM10 8.1 uses an exact full-body replacement; the ATM10 8.2 lineage uses a narrow
 * duplicate-rejecting merge. Both paths allocate a new packet and never mutate the original
 * deferred holder.</p>
 */
final class RegistryReplacementGuardHandler extends ChannelDuplexHandler {
    private static final int MAXIMUM_INSPECTION_BYTES = 1_048_576;

    private final RegistryShimPacket replacementPacket;
    private final RegistryShimReceipt replacementReceipt;
    private final TransformMode transformMode;
    private final PacketAccess packetAccess;
    private final Listener listener;
    private final CompletableFuture<RegistryShimReceipt> completion =
            new CompletableFuture<>();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.ACTIVE);
    private final AtomicBoolean replacementClaimed = new AtomicBoolean();
    private final AtomicBoolean duplicateReported = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicLong replacedPackets = new AtomicLong();
    private final AtomicLong duplicatePackets = new AtomicLong();

    RegistryReplacementGuardHandler(
            RegistryShimPacket replacementPacket,
            PacketAccess packetAccess,
            Listener listener) {
        this(replacementPacket, TransformMode.EXACT_REPLACEMENT, packetAccess, listener);
    }

    RegistryReplacementGuardHandler(
            RegistryShimPacket replacementPacket,
            TransformMode transformMode,
            PacketAccess packetAccess,
            Listener listener) {
        this.replacementPacket = Objects.requireNonNull(
                replacementPacket, "replacementPacket");
        this.transformMode = Objects.requireNonNull(transformMode, "transformMode");
        validateTransformIdentity(replacementPacket, transformMode);
        this.replacementReceipt = RegistryShimReceipt.from(replacementPacket);
        this.packetAccess = Objects.requireNonNull(packetAccess, "packetAccess");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
            throws Exception {
        if (!packetAccess.isRegistrySyncPacket(message)) {
            super.write(context, message, promise);
            return;
        }
        Status initialStatus = status.get();
        if (initialStatus == Status.FAILED || initialStatus == Status.CLOSED) {
            super.write(context, message, promise);
            return;
        }
        final byte[] originalBody;
        final String registryId;
        try {
            int bodyBytes = packetAccess.readableBodyBytes(message);
            if (bodyBytes < 1 || bodyBytes > MAXIMUM_INSPECTION_BYTES) {
                throw new IllegalStateException(
                        "Velocity registry packet body is outside inspection bounds");
            }
            originalBody = packetAccess.copyBody(message, bodyBytes);
            if (originalBody.length != bodyBytes) {
                throw new IllegalStateException(
                        "Velocity registry packet body changed during inspection");
            }
            registryId = MinecraftRegistryPacketCodec.inspectRegistryId(
                    originalBody, MAXIMUM_INSPECTION_BYTES);
        } catch (ReflectiveOperationException
                | ProtocolViolationException
                | RuntimeException
                | LinkageError failure) {
            failOpen(context, message, promise, failure);
            return;
        }

        if (!registryId.equals(Atm10Normal81EnchantmentRegistry.REGISTRY_ID)) {
            super.write(context, message, promise);
            return;
        }

        // Once the target registry has been claimed, every later target packet remains behind
        // the fence regardless of its trailing contents. The key is decoded separately from the
        // complete structural inspection so a duplicate with malformed NBT or a truncated entry
        // table cannot fail open and append a second minecraft:enchantment transaction.
        if (replacementClaimed.get()) {
            duplicatePackets.incrementAndGet();
            consumeDuplicate(
                    message,
                    promise,
                    new IllegalStateException(
                            "Paper emitted duplicate minecraft:enchantment registry packets"));
            return;
        }

        final Inspection inspection;
        try {
            inspection = MinecraftRegistryPacketCodec.inspect(
                    originalBody, MAXIMUM_INSPECTION_BYTES);
        } catch (ProtocolViolationException | RuntimeException | LinkageError failure) {
            failOpen(context, message, promise, failure);
            return;
        }

        final byte[] transformedBody;
        try {
            transformedBody = transformMode == TransformMode.EXACT_REPLACEMENT
                    ? replacementPacket.packetBody()
                    : MinecraftRegistryPacketCodec.mergeDistinctEntries(
                            originalBody,
                            replacementPacket.packetBody(),
                            MAXIMUM_INSPECTION_BYTES);
        } catch (ProtocolViolationException | RuntimeException | LinkageError failure) {
            failOpen(context, message, promise, failure);
            return;
        }
        if (promise.isVoid()) {
            failOpen(
                    context,
                    message,
                    promise,
                    new IllegalStateException(
                            "Velocity registry replacement received a void write promise"));
            return;
        }
        if (!replacementClaimed.compareAndSet(false, true)) {
            duplicatePackets.incrementAndGet();
            consumeDuplicate(
                    message,
                    promise,
                    new IllegalStateException(
                            "Paper emitted duplicate minecraft:enchantment registry packets"));
            return;
        }
        if (!status.compareAndSet(Status.ACTIVE, Status.WRITE_PENDING)) {
            failOpen(
                    context,
                    message,
                    promise,
                    new IllegalStateException(
                            "registry replacement guard is not active"));
            return;
        }

        final Object replacement;
        try {
            replacement = packetAccess.replacement(
                    message, transformedBody);
            if (replacement == null
                    || replacement == message
                    || !packetAccess.isRegistrySyncPacket(replacement)) {
                if (replacement != message) {
                    NettyReferenceOwnership.release(replacement);
                }
                throw new IllegalStateException(
                        "Velocity returned an invalid registry replacement object");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failOpen(context, message, promise, failure);
            return;
        }

        NettyReferenceOwnership.release(message);
        promise.addListener(completed -> {
            if (completed.isSuccess()) {
                writeSucceeded();
            } else {
                Throwable cause = completed.cause();
                failTerminal(cause == null
                        ? new IllegalStateException(
                                "Velocity registry replacement write failed")
                        : cause);
            }
        });
        super.write(context, replacement, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        deactivate(new IllegalStateException(
                "client channel closed before registry replacement guard detached"));
        super.channelInactive(context);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) throws Exception {
        deactivate(new IllegalStateException(
                "registry replacement guard was removed before completion"));
        super.handlerRemoved(context);
    }

    CompletableFuture<RegistryShimReceipt> completion() {
        return completion;
    }

    boolean active() {
        Status current = status.get();
        return current != Status.FAILED && current != Status.CLOSED;
    }

    long replacedPackets() {
        return replacedPackets.get();
    }

    long duplicatePackets() {
        return duplicatePackets.get();
    }

    void deactivate() {
        deactivate(new IllegalStateException(
                "registry replacement guard detached before a successful replacement"));
    }

    TransformMode transformMode() {
        return transformMode;
    }

    private static void validateTransformIdentity(
            RegistryShimPacket packet, TransformMode transformMode) {
        boolean valid = switch (transformMode) {
            case EXACT_REPLACEMENT -> packet.shimId().equals(
                            Atm10Normal81EnchantmentRegistry.SHIM_ID)
                    && packet.registryId().equals(
                            Atm10Normal81EnchantmentRegistry.REGISTRY_ID);
            case MERGE_DISTINCT_EXTENSION -> packet.shimId().equals(
                            Atm10Normal82GiselleEnchantmentExtension.SHIM_ID)
                    && packet.registryId().equals(
                            Atm10Normal82GiselleEnchantmentExtension.REGISTRY_ID);
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "registry transform packet identity is not reviewed for " + transformMode);
        }
    }

    private void writeSucceeded() {
        if (!status.compareAndSet(Status.WRITE_PENDING, Status.REPLACED)) {
            return;
        }
        replacedPackets.incrementAndGet();
        if (!completion.complete(replacementReceipt)) {
            return;
        }
        try {
            listener.replaced(replacementReceipt);
        } catch (Throwable ignored) {
            // Diagnostics cannot invalidate a completed exact write receipt.
        }
    }

    private void failOpen(
            ChannelHandlerContext context,
            Object message,
            ChannelPromise promise,
            Throwable failure) throws Exception {
        failTerminal(failure);
        super.write(context, message, promise);
    }

    private void consumeDuplicate(
            Object message, ChannelPromise promise, Throwable failure) {
        NettyReferenceOwnership.release(message);
        promise.trySuccess();
        reportDuplicate(failure);
    }

    private void reportDuplicate(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!duplicateReported.compareAndSet(false, true)) {
            return;
        }
        try {
            listener.duplicate(failure);
        } catch (Throwable ignored) {
            // Duplicate diagnostics cannot release the fence or invalidate an exact receipt.
        }
    }

    private void failTerminal(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Status previous = status.getAndSet(Status.FAILED);
        if (previous == Status.FAILED || previous == Status.CLOSED) {
            return;
        }
        completion.completeExceptionally(failure);
        if (!failureReported.compareAndSet(false, true)) {
            return;
        }
        try {
            listener.failed(failure);
        } catch (Throwable ignored) {
            // The owner controls admission; listener failures cannot reopen this packet path.
        }
    }

    private void deactivate(Throwable incompleteFailure) {
        while (true) {
            Status current = status.get();
            if (current == Status.CLOSED) {
                return;
            }
            if (!status.compareAndSet(current, Status.CLOSED)) {
                continue;
            }
            if (current != Status.REPLACED && current != Status.FAILED) {
                completion.completeExceptionally(incompleteFailure);
                if (failureReported.compareAndSet(false, true)) {
                    try {
                        listener.failed(incompleteFailure);
                    } catch (Throwable ignored) {
                        // Detachment remains final even if its observer fails.
                    }
                }
            }
            return;
        }
    }

    interface PacketAccess {
        boolean isRegistrySyncPacket(Object message);

        int readableBodyBytes(Object message) throws ReflectiveOperationException;

        byte[] copyBody(Object message, int expectedBytes) throws ReflectiveOperationException;

        Object replacement(Object original, byte[] replacementBody)
                throws ReflectiveOperationException;
    }

    interface Listener {
        void replaced(RegistryShimReceipt receipt);

        void duplicate(Throwable failure);

        void failed(Throwable failure);
    }

    enum TransformMode {
        EXACT_REPLACEMENT,
        MERGE_DISTINCT_EXTENSION
    }

    private enum Status {
        ACTIVE,
        WRITE_PENDING,
        REPLACED,
        FAILED,
        CLOSED
    }
}
