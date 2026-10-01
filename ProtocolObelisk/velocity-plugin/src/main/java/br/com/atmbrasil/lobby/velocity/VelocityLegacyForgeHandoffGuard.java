package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.LegacyForgeHandoffPolicy.ControlOperation;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal Velocity 4 internal adapter for the legacy Forge old-backend handoff race.
 *
 * <p>The public API exposes neither the connection currently in flight nor an outbound packet
 * interception point on one backend. This adapter resolves only those read-only/internal surfaces
 * at boot, then attaches a backend-scoped handler after Velocity's Minecraft encoder so it sees
 * decoded packet objects before encoding. It never creates, selects, replaces or cancels a route.</p>
 */
final class VelocityLegacyForgeHandoffGuard {
    private static final String CONNECTED_PLAYER_CLASS =
            "com.velocitypowered.proxy.connection.client.ConnectedPlayer";
    private static final String VELOCITY_SERVER_CONNECTION_CLASS =
            "com.velocitypowered.proxy.connection.backend.VelocityServerConnection";
    private static final String MINECRAFT_CONNECTION_CLASS =
            "com.velocitypowered.proxy.connection.MinecraftConnection";
    private static final String PLUGIN_MESSAGE_PACKET_CLASS =
            "com.velocitypowered.proxy.protocol.packet.PluginMessagePacket";
    private static final String MINECRAFT_ENCODER_CLASS =
            "com.velocitypowered.proxy.protocol.netty.MinecraftEncoder";
    private static final String HANDLER_NAME = "protocolobelisk-legacy-forge-handoff-guard";
    private static final long BACKEND_CONNECTION_RETRY_MILLIS = 10L;

    private final Class<?> connectedPlayerClass;
    private final Class<?> velocityServerConnectionClass;
    private final Class<?> minecraftConnectionClass;
    private final Class<?> pluginMessagePacketClass;
    private final Class<?> minecraftEncoderClass;
    private final Method getConnectionInFlightMethod;
    private final Method getConnectionMethod;
    private final Method getChannelMethod;
    private final Method pluginMessageGetChannelMethod;

    private VelocityLegacyForgeHandoffGuard(
            Class<?> connectedPlayerClass,
            Class<?> velocityServerConnectionClass,
            Class<?> minecraftConnectionClass,
            Class<?> pluginMessagePacketClass,
            Class<?> minecraftEncoderClass,
            Method getConnectionInFlightMethod,
            Method getConnectionMethod,
            Method getChannelMethod,
            Method pluginMessageGetChannelMethod) {
        this.connectedPlayerClass = connectedPlayerClass;
        this.velocityServerConnectionClass = velocityServerConnectionClass;
        this.minecraftConnectionClass = minecraftConnectionClass;
        this.pluginMessagePacketClass = pluginMessagePacketClass;
        this.minecraftEncoderClass = minecraftEncoderClass;
        this.getConnectionInFlightMethod = getConnectionInFlightMethod;
        this.getConnectionMethod = getConnectionMethod;
        this.getChannelMethod = getChannelMethod;
        this.pluginMessageGetChannelMethod = pluginMessageGetChannelMethod;
    }

    /** Resolves and validates every internal signature before any legacy session is armed. */
    static VelocityLegacyForgeHandoffGuard resolve() {
        ClassLoader loader = VelocityLegacyForgeHandoffGuard.class.getClassLoader();
        try {
            Class<?> connectedPlayer = Class.forName(CONNECTED_PLAYER_CLASS, false, loader);
            Class<?> velocityServerConnection = Class.forName(
                    VELOCITY_SERVER_CONNECTION_CLASS, false, loader);
            Class<?> minecraftConnection = Class.forName(
                    MINECRAFT_CONNECTION_CLASS, false, loader);
            Class<?> pluginMessagePacket = Class.forName(
                    PLUGIN_MESSAGE_PACKET_CLASS, false, loader);
            Class<?> minecraftEncoder = Class.forName(
                    MINECRAFT_ENCODER_CLASS, false, loader);

            Method getConnectionInFlight = connectedPlayer.getMethod("getConnectionInFlight");
            Method getConnection = velocityServerConnection.getMethod("getConnection");
            Method getChannel = minecraftConnection.getMethod("getChannel");
            Method pluginMessageGetChannel = pluginMessagePacket.getMethod("getChannel");

            if (!Player.class.isAssignableFrom(connectedPlayer)
                    || !ServerConnection.class.isAssignableFrom(velocityServerConnection)
                    || !velocityServerConnection.isAssignableFrom(
                            getConnectionInFlight.getReturnType())
                    || !minecraftConnection.isAssignableFrom(getConnection.getReturnType())
                    || !Channel.class.isAssignableFrom(getChannel.getReturnType())
                    || pluginMessageGetChannel.getReturnType() != String.class
                    || !ChannelOutboundHandler.class.isAssignableFrom(minecraftEncoder)) {
                throw new IllegalStateException(
                        "Velocity legacy Forge handoff signatures changed");
            }

            ProtocolVersion protocol = ProtocolVersion.getProtocolVersion(
                    LegacyForgeHandoffPolicy.MINECRAFT_1_7_10_PROTOCOL);
            if (protocol.isUnknown()
                    || !protocol.isSupported()
                    || protocol.getProtocol()
                            != LegacyForgeHandoffPolicy.MINECRAFT_1_7_10_PROTOCOL) {
                throw new IllegalStateException(
                        "Velocity does not support Minecraft 1.7.10 protocol 5");
            }

            return new VelocityLegacyForgeHandoffGuard(
                    connectedPlayer,
                    velocityServerConnection,
                    minecraftConnection,
                    pluginMessagePacket,
                    minecraftEncoder,
                    getConnectionInFlight,
                    getConnection,
                    getChannel,
                    pluginMessageGetChannel);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            throw new IllegalStateException(
                    "Velocity 4 legacy Forge handoff API is incompatible", unwrap(exception));
        }
    }

    CompletableFuture<Lease> attach(
            Player player,
            ServerConnection lobbyConnection,
            String lobbyServer,
            Set<String> allowedTargets,
            Listener listener,
            long readinessTimeoutMillis) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(lobbyConnection, "lobbyConnection");
        Objects.requireNonNull(lobbyServer, "lobbyServer");
        Set<String> immutableTargets = Set.copyOf(
                Objects.requireNonNull(allowedTargets, "allowedTargets"));
        Objects.requireNonNull(listener, "listener");
        CompletableFuture<Lease> result = new CompletableFuture<>();
        try {
            if (readinessTimeoutMillis <= 0L) {
                throw new IllegalArgumentException(
                        "backend connection readiness timeout must be positive");
            }
            if (!connectedPlayerClass.isInstance(player)) {
                throw new IllegalStateException(
                        "Velocity Player implementation is not ConnectedPlayer");
            }
            if (!velocityServerConnectionClass.isInstance(lobbyConnection)) {
                throw new IllegalStateException(
                        "Velocity ServerConnection implementation is not VelocityServerConnection");
            }
            if (player.getProtocolVersion().getProtocol()
                    != LegacyForgeHandoffPolicy.MINECRAFT_1_7_10_PROTOCOL) {
                throw new IllegalArgumentException(
                        "legacy Forge handoff guard requires Minecraft protocol 5");
            }
            String guardedBackend = lobbyConnection.getServerInfo().getName();
            if (!guardedBackend.equals(lobbyServer)) {
                throw new IllegalArgumentException(
                        "legacy Forge handoff guard can attach only to lobby-server");
            }
            if (immutableTargets.isEmpty() || immutableTargets.contains(lobbyServer)) {
                throw new IllegalArgumentException(
                        "legacy Forge handoff target allowlist is empty or contains the lobby");
            }

            awaitBackendConnection(
                    player,
                    lobbyConnection,
                    lobbyServer,
                    immutableTargets,
                    listener,
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
            Player player,
            ServerConnection lobbyConnection,
            String lobbyServer,
            Set<String> allowedTargets,
            Listener listener,
            long startedNanos,
            long timeoutNanos,
            long timeoutMillis,
            CompletableFuture<Lease> result) {
        if (result.isDone()) {
            return;
        }
        if (System.nanoTime() - startedNanos >= timeoutNanos) {
            result.completeExceptionally(new IllegalStateException(
                    "Velocity lobby connection was not established within "
                            + timeoutMillis + " ms"));
            return;
        }
        try {
            Object minecraftConnection = getConnectionMethod.invoke(lobbyConnection);
            if (minecraftConnection == null) {
                scheduleRetry(
                        player,
                        lobbyConnection,
                        lobbyServer,
                        allowedTargets,
                        listener,
                        startedNanos,
                        timeoutNanos,
                        timeoutMillis,
                        result);
                return;
            }
            if (!minecraftConnectionClass.isInstance(minecraftConnection)) {
                throw new IllegalStateException(
                        "Velocity lobby connection has an incompatible implementation");
            }
            Object rawChannel = getChannelMethod.invoke(minecraftConnection);
            if (rawChannel == null) {
                scheduleRetry(
                        player,
                        lobbyConnection,
                        lobbyServer,
                        allowedTargets,
                        listener,
                        startedNanos,
                        timeoutNanos,
                        timeoutMillis,
                        result);
                return;
            }
            if (!(rawChannel instanceof Channel channel)) {
                throw new IllegalStateException(
                        "Velocity lobby has an incompatible Netty channel");
            }
            installOnEventLoop(
                    channel,
                    player,
                    lobbyConnection,
                    lobbyServer,
                    allowedTargets,
                    listener,
                    result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            result.completeExceptionally(unwrap(exception));
        }
    }

    private void scheduleRetry(
            Player player,
            ServerConnection lobbyConnection,
            String lobbyServer,
            Set<String> allowedTargets,
            Listener listener,
            long startedNanos,
            long timeoutNanos,
            long timeoutMillis,
            CompletableFuture<Lease> result) {
        try {
            CompletableFuture.delayedExecutor(
                            BACKEND_CONNECTION_RETRY_MILLIS, TimeUnit.MILLISECONDS)
                    .execute(() -> awaitBackendConnection(
                            player,
                            lobbyConnection,
                            lobbyServer,
                            allowedTargets,
                            listener,
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
            Player player,
            ServerConnection lobbyConnection,
            String lobbyServer,
            Set<String> allowedTargets,
            Listener listener,
            CompletableFuture<Lease> result) {
        Runnable install = () -> {
            GuardLease lease = null;
            boolean installed = false;
            try {
                if (result.isDone()) {
                    return;
                }
                if (!channel.isActive()) {
                    throw new IllegalStateException(
                            "Velocity lobby channel closed before guard attachment");
                }
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.context(HANDLER_NAME) != null) {
                    throw new IllegalStateException(
                            "Velocity lobby already has a ProtocolObelisk legacy handoff guard");
                }
                ChannelHandlerContext encoderContext = findMinecraftEncoder(pipeline);
                LegacyForgeHandoffGuardHandler handler = new LegacyForgeHandoffGuardHandler(
                        player.getProtocolVersion().getProtocol(),
                        lobbyConnection.getServerInfo().getName(),
                        lobbyServer,
                        allowedTargets,
                        packetAccess(),
                        () -> inFlightTarget(player),
                        new LegacyForgeHandoffGuardHandler.Listener() {
                            @Override
                            public void suppressed(
                                    ControlOperation operation,
                                    String inFlightTarget,
                                    long sequence) {
                                listener.suppressed(operation, inFlightTarget, sequence);
                            }

                            @Override
                            public void failed(Throwable failure) {
                                listener.failed(failure);
                            }
                        });
                lease = new GuardLease(channel, handler);
                pipeline.addAfter(encoderContext.name(), HANDLER_NAME, handler);
                installed = true;
                if (!result.complete(lease)) {
                    lease.close();
                }
            } catch (RuntimeException | LinkageError exception) {
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
                    "Velocity lobby event loop rejected guard attachment", exception));
        }
    }

    private LegacyForgeHandoffGuardHandler.PacketAccess packetAccess() {
        return new LegacyForgeHandoffGuardHandler.PacketAccess() {
            @Override
            public boolean isPluginMessage(Object message) {
                return pluginMessagePacketClass.isInstance(message);
            }

            @Override
            public String outerChannel(Object message) throws ReflectiveOperationException {
                Object rawChannel = pluginMessageGetChannelMethod.invoke(message);
                if (!(rawChannel instanceof String channel) || channel.isBlank()) {
                    throw new IllegalStateException(
                            "Velocity PluginMessagePacket returned an invalid channel");
                }
                return channel;
            }
        };
    }

    private String inFlightTarget(Player player) throws ReflectiveOperationException {
        Object rawTarget = getConnectionInFlightMethod.invoke(player);
        if (rawTarget == null) {
            return null;
        }
        if (!(rawTarget instanceof ServerConnection target)
                || !velocityServerConnectionClass.isInstance(rawTarget)) {
            throw new IllegalStateException(
                    "Velocity returned an incompatible connection-in-flight object");
        }
        return target.getServerInfo().getName();
    }

    private ChannelHandlerContext findMinecraftEncoder(ChannelPipeline pipeline) {
        ChannelHandlerContext match = null;
        for (String name : pipeline.names()) {
            ChannelHandlerContext context = pipeline.context(name);
            if (context == null || !minecraftEncoderClass.isInstance(context.handler())) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException(
                        "Velocity lobby has multiple Minecraft encoders");
            }
            match = context;
        }
        if (match == null) {
            throw new IllegalStateException(
                    "Velocity lobby Minecraft encoder is unavailable");
        }
        return match;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }

    interface Listener {
        void suppressed(ControlOperation operation, String inFlightTarget, long sequence);

        void failed(Throwable failure);
    }

    interface Lease extends AutoCloseable {
        boolean active();

        long suppressedPackets();

        @Override
        void close();
    }

    private static final class GuardLease implements Lease {
        private final Channel channel;
        private final LegacyForgeHandoffGuardHandler handler;
        private final AtomicBoolean closed = new AtomicBoolean();

        private GuardLease(Channel channel, LegacyForgeHandoffGuardHandler handler) {
            this.channel = channel;
            this.handler = handler;
        }

        @Override
        public boolean active() {
            return handler.active();
        }

        @Override
        public long suppressedPackets() {
            return handler.suppressedPackets();
        }

        @Override
        public void close() {
            handler.deactivate();
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
                // A terminated channel owns no live backend traffic.
            }
        }

        private void removeHandlerByIdentity() {
            ChannelHandlerContext context = channel.pipeline().context(HANDLER_NAME);
            if (context != null && context.handler() == handler) {
                channel.pipeline().remove(handler);
            }
        }
    }
}
