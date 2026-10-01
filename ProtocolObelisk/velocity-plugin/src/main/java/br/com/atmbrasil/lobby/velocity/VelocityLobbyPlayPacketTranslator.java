package br.com.atmbrasil.lobby.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backend-scoped Velocity 4 adapter for reviewed, raw clientbound PLAY packets.
 *
 * <p>The public Velocity API does not expose raw backend packet interception. This adapter resolves
 * the smallest required internal surface once at boot, verifies that every translated protocol
 * 767 packet remains deliberately unknown to Velocity's own clientbound PLAY decoder, and then
 * installs one inbound handler immediately after that decoder. If Velocity starts decoding any of
 * the reviewed packet ids itself, resolution fails closed instead of risking double decoding or
 * translating a different packet.</p>
 */
final class VelocityLobbyPlayPacketTranslator {
    private static final String VELOCITY_SERVER_CONNECTION_CLASS =
            "com.velocitypowered.proxy.connection.backend.VelocityServerConnection";
    private static final String MINECRAFT_CONNECTION_CLASS =
            "com.velocitypowered.proxy.connection.MinecraftConnection";
    private static final String STATE_REGISTRY_CLASS =
            "com.velocitypowered.proxy.protocol.StateRegistry";
    private static final String MINECRAFT_DECODER_CLASS =
            "com.velocitypowered.proxy.protocol.netty.MinecraftDecoder";
    private static final String PROTOCOL_DIRECTION_CLASS =
            "com.velocitypowered.proxy.protocol.ProtocolUtils$Direction";
    private static final String HANDLER_NAME = "protocolobelisk-block-state-translator";
    private static final long BACKEND_CONNECTION_RETRY_MILLIS = 10L;

    private final Class<?> velocityServerConnectionClass;
    private final Class<?> minecraftConnectionClass;
    private final Class<?> minecraftDecoderClass;
    private final Method getConnectionMethod;
    private final Method getChannelMethod;
    private final Method getStateMethod;
    private final Method decoderGetDirectionMethod;
    private final Object playState;
    private final Object clientboundDirection;
    private final int verifiedProtocol;
    private final Set<Integer> verifiedPacketIds;

    private VelocityLobbyPlayPacketTranslator(
            Class<?> velocityServerConnectionClass,
            Class<?> minecraftConnectionClass,
            Class<?> minecraftDecoderClass,
            Method getConnectionMethod,
            Method getChannelMethod,
            Method getStateMethod,
            Method decoderGetDirectionMethod,
            Object playState,
            Object clientboundDirection,
            BlockStateTranslationProfile profile,
            Set<Integer> verifiedPacketIds) {
        this(
                velocityServerConnectionClass,
                minecraftConnectionClass,
                minecraftDecoderClass,
                getConnectionMethod,
                getChannelMethod,
                getStateMethod,
                decoderGetDirectionMethod,
                playState,
                clientboundDirection,
                Objects.requireNonNull(profile, "profile").minecraftProtocol(),
                verifiedPacketIds);
    }

    private VelocityLobbyPlayPacketTranslator(
            Class<?> velocityServerConnectionClass,
            Class<?> minecraftConnectionClass,
            Class<?> minecraftDecoderClass,
            Method getConnectionMethod,
            Method getChannelMethod,
            Method getStateMethod,
            Method decoderGetDirectionMethod,
            Object playState,
            Object clientboundDirection,
            int verifiedProtocol,
            Set<Integer> verifiedPacketIds) {
        this.velocityServerConnectionClass = velocityServerConnectionClass;
        this.minecraftConnectionClass = minecraftConnectionClass;
        this.minecraftDecoderClass = minecraftDecoderClass;
        this.getConnectionMethod = getConnectionMethod;
        this.getChannelMethod = getChannelMethod;
        this.getStateMethod = getStateMethod;
        this.decoderGetDirectionMethod = decoderGetDirectionMethod;
        this.playState = playState;
        this.clientboundDirection = clientboundDirection;
        this.verifiedProtocol = verifiedProtocol;
        this.verifiedPacketIds = Set.copyOf(verifiedPacketIds);
    }

    /** Resolves and audits the exact Velocity surface used by one reviewed profile. */
    static VelocityLobbyPlayPacketTranslator resolve(BlockStateTranslationProfile profile) {
        return resolve(Set.of(Objects.requireNonNull(profile, "profile")));
    }

    /**
     * Resolves one adapter against the union of every reviewed structural profile surface.
     *
     * <p>Boot verification therefore follows the independent BlockState catalog rather than an
     * unrelated Silent Gear profile which happens to carry one map.</p>
     */
    static VelocityLobbyPlayPacketTranslator resolve(
            Iterable<BlockStateTranslationProfile> reviewedProfiles) {
        ReviewedPacketSurface reviewedSurface = reviewedPacketSurface(reviewedProfiles);
        Set<Integer> packetIds = reviewedSurface.packetIds();
        ClassLoader loader = VelocityLobbyPlayPacketTranslator.class.getClassLoader();
        try {
            Class<?> velocityServerConnection = Class.forName(
                    VELOCITY_SERVER_CONNECTION_CLASS, false, loader);
            Class<?> minecraftConnection = Class.forName(
                    MINECRAFT_CONNECTION_CLASS, false, loader);
            Class<?> stateRegistry = Class.forName(STATE_REGISTRY_CLASS, false, loader);
            Class<?> minecraftDecoder = Class.forName(MINECRAFT_DECODER_CLASS, false, loader);
            Class<?> protocolDirection = Class.forName(
                    PROTOCOL_DIRECTION_CLASS, false, loader);

            Method getConnection = velocityServerConnection.getMethod("getConnection");
            Method getChannel = minecraftConnection.getMethod("getChannel");
            Method getState = minecraftConnection.getMethod("getState");
            Method decoderGetDirection = minecraftDecoder.getMethod("getDirection");
            Method getProtocolRegistry = stateRegistry.getMethod(
                    "getProtocolRegistry", protocolDirection, ProtocolVersion.class);

            if (!ServerConnection.class.isAssignableFrom(velocityServerConnection)
                    || !minecraftConnection.isAssignableFrom(getConnection.getReturnType())
                    || !Channel.class.isAssignableFrom(getChannel.getReturnType())
                    || !stateRegistry.isAssignableFrom(getState.getReturnType())
                    || decoderGetDirection.getReturnType() != protocolDirection
                    || !ChannelHandler.class.isAssignableFrom(minecraftDecoder)) {
                throw new IllegalStateException(
                        "Velocity backend packet translation signatures changed");
            }

            Object play = enumConstant(stateRegistry, "PLAY");
            Object clientbound = enumConstant(protocolDirection, "CLIENTBOUND");
            ProtocolVersion protocolVersion = ProtocolVersion.getProtocolVersion(
                    reviewedSurface.minecraftProtocol());
            if (protocolVersion.isUnknown()
                    || !protocolVersion.isSupported()
                    || protocolVersion.getProtocol() != reviewedSurface.minecraftProtocol()) {
                throw new IllegalStateException(
                        "Velocity does not support reviewed Minecraft protocol "
                                + reviewedSurface.minecraftProtocol());
            }

            Object protocolRegistry = getProtocolRegistry.invoke(
                    play, clientbound, protocolVersion);
            if (protocolRegistry == null) {
                throw new IllegalStateException(
                        "Velocity returned no clientbound PLAY protocol registry");
            }
            Method createPacket = protocolRegistry.getClass().getMethod(
                    "createPacket", int.class);
            for (int packetId : packetIds) {
                Object packet = createPacket.invoke(protocolRegistry, packetId);
                if (packet != null) {
                    ReferenceCountUtil.release(packet);
                    throw new IllegalStateException(
                            "Velocity now decodes reviewed clientbound PLAY packet 0x"
                                    + Integer.toHexString(packetId));
                }
            }

            return new VelocityLobbyPlayPacketTranslator(
                    velocityServerConnection,
                    minecraftConnection,
                    minecraftDecoder,
                    getConnection,
                    getChannel,
                    getState,
                    decoderGetDirection,
                    play,
                    clientbound,
                    reviewedSurface.minecraftProtocol(),
                    packetIds);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException(
                    "Velocity 4 backend packet translation API is incompatible",
                    unwrap(exception));
        }
    }

    /**
     * Attaches one translation lease to the supplied backend on that backend's event loop.
     *
     * <p>The returned lease becomes active only after its handler is present. Translation failures
     * consume the unsafe packet, deactivate the lease, and notify {@code failureListener} exactly
     * once without closing the backend channel; the owner remains responsible for rejecting the
     * affected compatibility session. Velocity can expose the backend-scoped
     * {@link ServerConnection} before its internal Minecraft connection exists during a fresh
     * proxy reconnect. That narrow lifecycle state is retried within {@code readinessTimeoutMillis};
     * every other reflective or channel failure remains immediately fail-closed.</p>
     */
    CompletableFuture<Lease> attach(
            ServerConnection serverConnection,
            BlockStateTranslationProfile profile,
            FailureListener failureListener,
            long readinessTimeoutMillis) {
        return attach(
                serverConnection,
                profile,
                () -> true,
                failureListener,
                readinessTimeoutMillis);
    }

    CompletableFuture<Lease> attach(
            ServerConnection serverConnection,
            BlockStateTranslationProfile profile,
            EvidenceGuard evidenceGuard,
            FailureListener failureListener,
            long readinessTimeoutMillis) {
        Objects.requireNonNull(serverConnection, "serverConnection");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(evidenceGuard, "evidenceGuard");
        Objects.requireNonNull(failureListener, "failureListener");
        CompletableFuture<Lease> result = new CompletableFuture<>();
        try {
            if (readinessTimeoutMillis <= 0L) {
                throw new IllegalArgumentException(
                        "backend connection readiness timeout must be positive");
            }
            validateProfile(profile);
            if (!velocityServerConnectionClass.isInstance(serverConnection)) {
                throw new IllegalStateException(
                        "Velocity ServerConnection implementation is not VelocityServerConnection");
            }
            awaitBackendConnection(
                    serverConnection,
                    profile,
                    evidenceGuard,
                    failureListener,
                    System.nanoTime(),
                    TimeUnit.MILLISECONDS.toNanos(readinessTimeoutMillis),
                    readinessTimeoutMillis,
                    result);
        } catch (RuntimeException | LinkageError exception) {
            result.completeExceptionally(unwrap(exception));
        }
        return result;
    }

    private void awaitBackendConnection(
            ServerConnection serverConnection,
            BlockStateTranslationProfile profile,
            EvidenceGuard evidenceGuard,
            FailureListener failureListener,
            long startedNanos,
            long timeoutNanos,
            long timeoutMillis,
            CompletableFuture<Lease> result) {
        if (result.isDone()) {
            return;
        }
        if (System.nanoTime() - startedNanos >= timeoutNanos) {
            result.completeExceptionally(new IllegalStateException(
                    "Velocity backend connection was not established within "
                            + timeoutMillis + " ms"));
            return;
        }
        try {
            Object minecraftConnection = getConnectionMethod.invoke(serverConnection);
            if (minecraftConnection == null) {
                scheduleBackendConnectionRetry(
                        serverConnection,
                        profile,
                        evidenceGuard,
                        failureListener,
                        startedNanos,
                        timeoutNanos,
                        timeoutMillis,
                        result);
                return;
            }
            if (!minecraftConnectionClass.isInstance(minecraftConnection)) {
                throw new IllegalStateException(
                        "Velocity backend connection has an incompatible implementation");
            }
            Object rawChannel = getChannelMethod.invoke(minecraftConnection);
            if (rawChannel == null) {
                scheduleBackendConnectionRetry(
                        serverConnection,
                        profile,
                        evidenceGuard,
                        failureListener,
                        startedNanos,
                        timeoutNanos,
                        timeoutMillis,
                        result);
                return;
            }
            if (!(rawChannel instanceof Channel channel)) {
                throw new IllegalStateException("Velocity backend has an incompatible Netty channel");
            }
            installOnEventLoop(
                    channel,
                    minecraftConnection,
                    profile,
                    evidenceGuard,
                    failureListener,
                    result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            result.completeExceptionally(unwrap(exception));
        }
    }

    private void scheduleBackendConnectionRetry(
            ServerConnection serverConnection,
            BlockStateTranslationProfile profile,
            EvidenceGuard evidenceGuard,
            FailureListener failureListener,
            long startedNanos,
            long timeoutNanos,
            long timeoutMillis,
            CompletableFuture<Lease> result) {
        try {
            CompletableFuture.delayedExecutor(
                            BACKEND_CONNECTION_RETRY_MILLIS, TimeUnit.MILLISECONDS)
                    .execute(() -> awaitBackendConnection(
                            serverConnection,
                            profile,
                            evidenceGuard,
                            failureListener,
                            startedNanos,
                            timeoutNanos,
                            timeoutMillis,
                            result));
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new IllegalStateException(
                    "Velocity backend readiness retry was rejected", exception));
        }
    }

    private void installOnEventLoop(
            Channel channel,
            Object minecraftConnection,
            BlockStateTranslationProfile profile,
            EvidenceGuard evidenceGuard,
            FailureListener failureListener,
            CompletableFuture<Lease> result) {
        Runnable install = () -> {
            TranslationLease lease = null;
            boolean installed = false;
            try {
                if (result.isDone()) {
                    return;
                }
                if (!channel.isActive()) {
                    throw new IllegalStateException(
                            "Velocity backend channel closed before translator attachment");
                }
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.context(HANDLER_NAME) != null) {
                    throw new IllegalStateException(
                            "Velocity backend already has a ProtocolObelisk translator");
                }
                ChannelHandlerContext decoderContext = findClientboundDecoder(pipeline);
                lease = new TranslationLease(channel);
                TranslationHandler handler = new TranslationHandler(
                        minecraftConnection,
                        getStateMethod,
                        playState,
                        validateProfile(profile),
                        new Minecraft1211BlockStatePacketTranslator(profile),
                        lease,
                        evidenceGuard,
                        failureListener);
                lease.bind(handler);
                pipeline.addAfter(decoderContext.name(), HANDLER_NAME, handler);
                installed = true;
                if (!result.complete(lease)) {
                    lease.close();
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                if (installed && lease != null) {
                    lease.close();
                }
                result.completeExceptionally(unwrap(exception));
            }
        };
        try {
            if (channel.eventLoop().inEventLoop()) {
                install.run();
            } else {
                channel.eventLoop().execute(install);
            }
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new IllegalStateException(
                    "Velocity backend event loop rejected translator attachment", exception));
        }
    }

    private ChannelHandlerContext findClientboundDecoder(ChannelPipeline pipeline)
            throws ReflectiveOperationException {
        ChannelHandlerContext match = null;
        for (String name : pipeline.names()) {
            ChannelHandlerContext context = pipeline.context(name);
            if (context == null || !minecraftDecoderClass.isInstance(context.handler())) {
                continue;
            }
            Object direction = decoderGetDirectionMethod.invoke(context.handler());
            if (direction != clientboundDirection) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException(
                        "Velocity backend has multiple clientbound Minecraft decoders");
            }
            match = context;
        }
        if (match == null) {
            throw new IllegalStateException(
                    "Velocity backend clientbound Minecraft decoder is unavailable");
        }
        return match;
    }

    private Set<Integer> validateProfile(BlockStateTranslationProfile profile) {
        return validatedProfilePacketIds(profile, verifiedProtocol, verifiedPacketIds);
    }

    static Set<Integer> validatedProfilePacketIds(
            BlockStateTranslationProfile profile,
            int verifiedProtocol,
            Set<Integer> verifiedPacketIds) {
        Objects.requireNonNull(verifiedPacketIds, "verifiedPacketIds");
        Set<Integer> profilePacketIds = immutableSortedPacketIds(profile);
        if (profile.minecraftProtocol() != verifiedProtocol
                || profilePacketIds.isEmpty()
                || !verifiedPacketIds.containsAll(profilePacketIds)) {
            throw new IllegalArgumentException(
                    "BlockState profile exceeds the boot-verified packet-id contract");
        }
        return profilePacketIds;
    }

    static ReviewedPacketSurface reviewedPacketSurface(
            Iterable<BlockStateTranslationProfile> reviewedProfiles) {
        Objects.requireNonNull(reviewedProfiles, "reviewedProfiles");
        Integer protocol = null;
        TreeSet<Integer> packetIds = new TreeSet<>();
        int profileCount = 0;
        for (BlockStateTranslationProfile profile : reviewedProfiles) {
            Objects.requireNonNull(profile, "reviewed profile");
            profileCount++;
            if (protocol == null) {
                protocol = profile.minecraftProtocol();
            } else if (protocol != profile.minecraftProtocol()) {
                throw new IllegalArgumentException(
                        "reviewed BlockState profiles span multiple Minecraft protocols");
            }
            packetIds.addAll(immutableSortedPacketIds(profile));
        }
        if (profileCount == 0 || protocol == null || packetIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "reviewed BlockState profile surface is empty");
        }
        return new ReviewedPacketSurface(
                protocol, Collections.unmodifiableSet(packetIds));
    }

    private static Set<Integer> immutableSortedPacketIds(
            BlockStateTranslationProfile profile) {
        Objects.requireNonNull(profile, "profile");
        TreeSet<Integer> sorted = new TreeSet<>(profile.rewrittenPacketIds());
        if (sorted.size() != profile.rewrittenPacketIds().size() || sorted.isEmpty()) {
            throw new IllegalArgumentException(
                    "BlockState translation packet ids are empty or ambiguous");
        }
        for (int packetId : sorted) {
            if (packetId < 0) {
                throw new IllegalArgumentException(
                        "BlockState translation packet id is negative");
            }
        }
        return Collections.unmodifiableSet(sorted);
    }

    record ReviewedPacketSurface(int minecraftProtocol, Set<Integer> packetIds) {
        ReviewedPacketSurface {
            if (minecraftProtocol <= 0) {
                throw new IllegalArgumentException(
                        "reviewed BlockState protocol must be positive");
            }
            packetIds = Set.copyOf(Objects.requireNonNull(packetIds, "packetIds"));
            if (packetIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "reviewed BlockState packet surface must not be empty");
            }
        }
    }

    private static Object enumConstant(Class<?> enumClass, String name) {
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            throw new IllegalStateException(enumClass.getName() + " is not an enum");
        }
        return Arrays.stream(constants)
                .filter(constant -> constant instanceof Enum<?> enumValue
                        && enumValue.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        enumClass.getName() + '.' + name + " is unavailable"));
    }

    private static int peekVarIntValue(ByteBuf input) {
        int readerIndex = input.readerIndex();
        int readable = input.readableBytes();
        int value = 0;
        for (int index = 0; index < 5 && index < readable; index++) {
            int current = input.getUnsignedByte(readerIndex + index);
            value |= (current & 0x7F) << (index * 7);
            if ((current & 0x80) == 0) {
                return value < 0 ? -1 : value;
            }
        }
        return -1;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }

    @FunctionalInterface
    interface FailureListener {
        void failed(Throwable failure);
    }

    /**
     * Checks the monotonic structural-evidence lease around one reviewed packet translation.
     *
     * <p>{@link #commitForward()} is the packet's evidence-linearization point. A reset whose
     * monotonic invalidation is observable before that call returns {@code true} must make it
     * return {@code false}; the packet is then consumed fail-closed. A reset that invalidates the
     * lease after a successful commit is ordered after that packet. Both methods must return before
     * any downstream pipeline callback runs: an implementation may briefly take the manifest
     * registry monitor, but that monitor must never escape through this interface.</p>
     */
    @FunctionalInterface
    interface EvidenceGuard {
        boolean current();

        /**
         * Performs the final evidence observation immediately before downstream ownership.
         * Production callers override this method explicitly; the default keeps lightweight
         * stateless test guards source-compatible and provides no stronger atomicity promise.
         */
        default boolean commitForward() {
            return current();
        }
    }

    interface Lease extends AutoCloseable {
        boolean active();

        void deactivate();

        @Override
        void close();
    }

    private static final class TranslationHandler extends ChannelInboundHandlerAdapter {
        private final Object minecraftConnection;
        private final Method getStateMethod;
        private final Object playState;
        private final Set<Integer> packetIds;
        private final Minecraft1211BlockStatePacketTranslator translator;
        private final TranslationLease lease;
        private final EvidenceGuard evidenceGuard;
        private final FailureListener failureListener;

        private TranslationHandler(
                Object minecraftConnection,
                Method getStateMethod,
                Object playState,
                Set<Integer> packetIds,
                Minecraft1211BlockStatePacketTranslator translator,
                TranslationLease lease,
                EvidenceGuard evidenceGuard,
                FailureListener failureListener) {
            this.minecraftConnection = minecraftConnection;
            this.getStateMethod = getStateMethod;
            this.playState = playState;
            this.packetIds = packetIds;
            this.translator = translator;
            this.lease = lease;
            this.evidenceGuard = evidenceGuard;
            this.failureListener = failureListener;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (!(message instanceof ByteBuf input)) {
                context.fireChannelRead(message);
                return;
            }
            int packetId = peekVarIntValue(input);
            if (!packetIds.contains(packetId)) {
                context.fireChannelRead(message);
                return;
            }
            if (!lease.active()) {
                ReferenceCountUtil.safeRelease(input);
                return;
            }

            boolean ownsInput = true;
            ByteBuf output = null;
            try {
                Object state = getStateMethod.invoke(minecraftConnection);
                if (state != playState) {
                    if (!lease.active()) {
                        ownsInput = false;
                        ReferenceCountUtil.safeRelease(input);
                        return;
                    }
                    ownsInput = false;
                    context.fireChannelRead(input);
                    return;
                }
                if (!evidenceGuard.current()) {
                    throw new IllegalStateException(
                            "reviewed BlockState selection is no longer current");
                }
                output = translator.translate(input, context.alloc());
                if (!evidenceGuard.current()) {
                    throw new IllegalStateException(
                            "reviewed BlockState selection changed during packet translation");
                }
                if (!lease.active()) {
                    ownsInput = false;
                    ByteBuf discardedOutput = output;
                    output = null;
                    ReferenceCountUtil.safeRelease(input);
                    ReferenceCountUtil.safeRelease(discardedOutput);
                    return;
                }
                if (output == null) {
                    if (!commitForward()) {
                        ownsInput = false;
                        ReferenceCountUtil.safeRelease(input);
                        return;
                    }
                    ownsInput = false;
                    context.fireChannelRead(input);
                    return;
                }
                ownsInput = false;
                ReferenceCountUtil.release(input);
                if (!commitForward()) {
                    ReferenceCountUtil.safeRelease(output);
                    output = null;
                    return;
                }
                ByteBuf translated = output;
                output = null;
                context.fireChannelRead(translated);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                if (ownsInput) {
                    ownsInput = false;
                    ReferenceCountUtil.safeRelease(input);
                }
                ReferenceCountUtil.safeRelease(output);
                lease.fail(unwrap(failure), failureListener);
            }
        }

        /**
         * Makes the final fail-closed decision after translation and before calling downstream.
         * Evidence and translation-lease checks finish before {@code fireChannelRead}; consequently
         * neither a registry monitor nor a failure callback can be nested into the Netty pipeline.
         */
        private boolean commitForward() {
            if (!evidenceGuard.commitForward()) {
                throw new IllegalStateException(
                        "reviewed BlockState selection changed before packet forwarding commit");
            }
            return lease.active();
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            lease.deactivate();
            super.channelInactive(context);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext context) throws Exception {
            lease.deactivate();
            super.handlerRemoved(context);
        }
    }

    private static final class TranslationLease implements Lease {
        private final Channel channel;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean failureReported = new AtomicBoolean();
        private volatile TranslationHandler handler;

        private TranslationLease(Channel channel) {
            this.channel = channel;
        }

        private void bind(TranslationHandler handler) {
            if (this.handler != null) {
                throw new IllegalStateException("translation lease is already bound");
            }
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        @Override
        public boolean active() {
            return active.get();
        }

        @Override
        public void deactivate() {
            active.set(false);
        }

        private void fail(Throwable failure, FailureListener listener) {
            Objects.requireNonNull(failure, "failure");
            active.set(false);
            if (!failureReported.compareAndSet(false, true)) {
                return;
            }
            try {
                listener.failed(failure);
            } catch (Throwable ignored) {
                // A listener must not turn a profile failure into a backend pipeline failure.
            }
        }

        @Override
        public void close() {
            active.set(false);
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Runnable remove = this::removeHandlerByIdentity;
            try {
                if (channel.eventLoop().inEventLoop()) {
                    remove.run();
                } else {
                    channel.eventLoop().execute(remove);
                }
            } catch (RejectedExecutionException ignored) {
                // A terminated channel owns no live pipeline traffic; deactivation is immediate.
            }
        }

        private void removeHandlerByIdentity() {
            TranslationHandler expected = handler;
            if (expected == null) {
                return;
            }
            ChannelHandlerContext context = channel.pipeline().context(HANDLER_NAME);
            if (context != null && context.handler() == expected) {
                channel.pipeline().remove(expected);
            }
        }
    }
}
