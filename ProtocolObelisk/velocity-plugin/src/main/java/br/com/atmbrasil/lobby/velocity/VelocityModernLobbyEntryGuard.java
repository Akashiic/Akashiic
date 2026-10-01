package br.com.atmbrasil.lobby.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Velocity 4 adapter that installs the modern Paper-bound REGISTER fence.
 *
 * <p>The adapter observes the connection already selected by Velocity and mutates only that exact
 * lobby backend's outbound pipeline. It never selects, authorizes, opens, closes or redirects a
 * server connection.</p>
 */
final class VelocityModernLobbyEntryGuard {
    static final String HANDLER_NAME =
            "protocolobelisk-modern-register-lobby-entry-guard";

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

    private final int expectedProtocol;
    private final Class<?> connectedPlayerClass;
    private final Class<?> velocityServerConnectionClass;
    private final Class<?> minecraftConnectionClass;
    private final Class<?> pluginMessagePacketClass;
    private final Class<?> minecraftEncoderClass;
    private final Method getConnectionInFlightMethod;
    private final Method getConnectionMethod;
    private final Method getChannelMethod;
    private final Method pluginMessageGetChannelMethod;
    private final Method pluginMessageContentMethod;
    private final Constructor<?> pluginMessageConstructor;

    private VelocityModernLobbyEntryGuard(
            int expectedProtocol,
            Class<?> connectedPlayerClass,
            Class<?> velocityServerConnectionClass,
            Class<?> minecraftConnectionClass,
            Class<?> pluginMessagePacketClass,
            Class<?> minecraftEncoderClass,
            Method getConnectionInFlightMethod,
            Method getConnectionMethod,
            Method getChannelMethod,
            Method pluginMessageGetChannelMethod,
            Method pluginMessageContentMethod,
            Constructor<?> pluginMessageConstructor) {
        this.expectedProtocol = expectedProtocol;
        this.connectedPlayerClass = connectedPlayerClass;
        this.velocityServerConnectionClass = velocityServerConnectionClass;
        this.minecraftConnectionClass = minecraftConnectionClass;
        this.pluginMessagePacketClass = pluginMessagePacketClass;
        this.minecraftEncoderClass = minecraftEncoderClass;
        this.getConnectionInFlightMethod = getConnectionInFlightMethod;
        this.getConnectionMethod = getConnectionMethod;
        this.getChannelMethod = getChannelMethod;
        this.pluginMessageGetChannelMethod = pluginMessageGetChannelMethod;
        this.pluginMessageContentMethod = pluginMessageContentMethod;
        this.pluginMessageConstructor = pluginMessageConstructor;
    }

    /** Resolves and validates every Velocity/Netty signature before the plugin is enabled. */
    static VelocityModernLobbyEntryGuard resolve(int expectedProtocol) {
        if (expectedProtocol < 393) {
            throw new IllegalArgumentException(
                    "modern lobby REGISTER guard requires a 1.13+ expected protocol");
        }
        ClassLoader loader = VelocityModernLobbyEntryGuard.class.getClassLoader();
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
            Method getPluginMessageChannel = pluginMessagePacket.getMethod("getChannel");
            Method getPluginMessageContent = pluginMessagePacket.getMethod("content");
            Constructor<?> pluginMessagePacketConstructor =
                    pluginMessagePacket.getConstructor(String.class, ByteBuf.class);

            if (!Player.class.isAssignableFrom(connectedPlayer)
                    || !ServerConnection.class.isAssignableFrom(velocityServerConnection)
                    || !velocityServerConnection.isAssignableFrom(
                            getConnectionInFlight.getReturnType())
                    || !minecraftConnection.isAssignableFrom(getConnection.getReturnType())
                    || !Channel.class.isAssignableFrom(getChannel.getReturnType())
                    || getPluginMessageChannel.getReturnType() != String.class
                    || !ByteBuf.class.isAssignableFrom(getPluginMessageContent.getReturnType())
                    || !ChannelOutboundHandler.class.isAssignableFrom(minecraftEncoder)) {
                throw new IllegalStateException(
                        "Velocity modern lobby-entry signatures changed");
            }

            return new VelocityModernLobbyEntryGuard(
                    expectedProtocol,
                    connectedPlayer,
                    velocityServerConnection,
                    minecraftConnection,
                    pluginMessagePacket,
                    minecraftEncoder,
                    getConnectionInFlight,
                    getConnection,
                    getChannel,
                    getPluginMessageChannel,
                    getPluginMessageContent,
                    pluginMessagePacketConstructor);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IllegalStateException(
                    "Velocity modern lobby-entry REGISTER adapter is incompatible",
                    unwrap(failure));
        }
    }

    /**
     * Attaches before Velocity resumes the awaited ServerConnectedEvent and forwards client
     * registrations to Paper.
     */
    CompletableFuture<Attachment> attach(
            Player player,
            String expectedLobby,
            String guardIdentity,
            ModernLobbyRegisterGuardHandler.Policy policy,
            ModernLobbyRegisterGuardHandler.Listener listener,
            long timeoutMillis) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(expectedLobby, "expectedLobby");
        Objects.requireNonNull(guardIdentity, "guardIdentity");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(listener, "listener");

        CompletableFuture<Attachment> result = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        result.whenComplete((attachment, failure) -> claimed.compareAndSet(false, true));

        try {
            if (timeoutMillis <= 0L) {
                throw new IllegalArgumentException(
                        "modern lobby-entry attachment timeout must be positive");
            }
            if (expectedLobby.isBlank() || expectedLobby.length() > 64) {
                throw new IllegalArgumentException(
                        "expected lobby server must contain 1..64 characters");
            }
            if (player.getProtocolVersion().getProtocol() != expectedProtocol) {
                throw new IllegalArgumentException(
                        "modern lobby-entry guard received an unexpected client protocol");
            }
            if (!connectedPlayerClass.isInstance(player)) {
                throw new IllegalStateException(
                        "Velocity Player implementation is not ConnectedPlayer");
            }

            Object inFlight = getConnectionInFlightMethod.invoke(player);
            if (!(inFlight instanceof ServerConnection serverConnection)
                    || !velocityServerConnectionClass.isInstance(inFlight)) {
                throw new IllegalStateException(
                        "Velocity lobby connection in flight is unavailable");
            }
            if (!expectedLobby.equals(serverConnection.getServerInfo().getName())) {
                throw new IllegalStateException(
                        "Velocity connection in flight is not the configured lobby");
            }
            Object minecraftConnection = getConnectionMethod.invoke(inFlight);
            if (!minecraftConnectionClass.isInstance(minecraftConnection)) {
                throw new IllegalStateException(
                        "Velocity lobby MinecraftConnection is unavailable");
            }
            Object rawChannel = getChannelMethod.invoke(minecraftConnection);
            if (!(rawChannel instanceof Channel channel)) {
                throw new IllegalStateException(
                        "Velocity lobby Netty channel is unavailable");
            }

            Runnable install = () -> installOnEventLoop(
                    channel,
                    guardIdentity,
                    policy,
                    listener,
                    claimed,
                    result);
            if (channel.eventLoop().inEventLoop()) {
                install.run();
            } else {
                channel.eventLoop().execute(install);
            }
            scheduleTimeout(timeoutMillis, claimed, result);
        } catch (RejectedExecutionException failure) {
            failBeforeInstallation(
                    claimed,
                    result,
                    new IllegalStateException(
                            "Velocity event loop rejected modern lobby-entry guard", failure));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failBeforeInstallation(claimed, result, unwrap(failure));
        }
        return result;
    }

    private void installOnEventLoop(
            Channel channel,
            String guardIdentity,
            ModernLobbyRegisterGuardHandler.Policy policy,
            ModernLobbyRegisterGuardHandler.Listener listener,
            AtomicBoolean claimed,
            CompletableFuture<Attachment> result) {
        if (!claimed.compareAndSet(false, true)) {
            return;
        }
        ModernLobbyRegisterGuardHandler installedHandler = null;
        boolean installed = false;
        try {
            if (!channel.isActive()) {
                throw new IllegalStateException(
                        "Velocity lobby channel closed before modern entry-guard attachment");
            }
            ChannelPipeline pipeline = channel.pipeline();
            ChannelHandlerContext existing = pipeline.context(HANDLER_NAME);
            if (existing != null) {
                ChannelHandler existingHandler = existing.handler();
                if (existingHandler instanceof ModernLobbyRegisterGuardHandler guard) {
                    if (!guard.active() || !guard.matches(expectedProtocol, guardIdentity)) {
                        throw new IllegalStateException(
                                "Velocity lobby already has an inactive or incompatible "
                                        + "ProtocolObelisk modern entry guard");
                    }
                    result.complete(new Attachment(Outcome.ALREADY_INSTALLED, guard));
                    return;
                }
                throw new IllegalStateException(
                        "Velocity modern lobby entry-guard handler name is owned by "
                                + "an unknown component");
            }

            ChannelHandlerContext encoder = findMinecraftEncoder(pipeline);
            installedHandler = new ModernLobbyRegisterGuardHandler(
                    expectedProtocol,
                    guardIdentity,
                    policy,
                    packetAccess(),
                    listener);
            pipeline.addAfter(encoder.name(), HANDLER_NAME, installedHandler);
            installed = true;
            if (!result.complete(new Attachment(Outcome.INSTALLED, installedHandler))) {
                pipeline.remove(installedHandler);
            }
        } catch (RuntimeException | LinkageError failure) {
            if (installed
                    && installedHandler != null
                    && channel.pipeline().context(installedHandler) != null) {
                channel.pipeline().remove(installedHandler);
            }
            result.completeExceptionally(unwrap(failure));
        }
    }

    private void scheduleTimeout(
            long timeoutMillis,
            AtomicBoolean claimed,
            CompletableFuture<Attachment> result) {
        if (claimed.get()) {
            return;
        }
        try {
            CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (claimed.compareAndSet(false, true)) {
                            result.completeExceptionally(new TimeoutException(
                                    "Velocity modern lobby entry guard was not installed within "
                                            + timeoutMillis + " ms"));
                        }
                    });
        } catch (RejectedExecutionException failure) {
            failBeforeInstallation(
                    claimed,
                    result,
                    new IllegalStateException(
                            "modern lobby-entry timeout scheduling was rejected", failure));
        }
    }

    private ModernLobbyRegisterGuardHandler.PacketAccess packetAccess() {
        return new ModernLobbyRegisterGuardHandler.PacketAccess() {
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

            @Override
            public int readablePayloadBytes(Object message) throws ReflectiveOperationException {
                return content(message).readableBytes();
            }

            @Override
            public byte[] copyPayload(Object message, int expectedBytes)
                    throws ReflectiveOperationException {
                ByteBuf content = content(message);
                if (content.readableBytes() != expectedBytes) {
                    throw new IllegalStateException(
                            "Velocity plugin-message payload size changed during inspection");
                }
                byte[] payload = new byte[expectedBytes];
                content.duplicate().readBytes(payload);
                return payload;
            }

            @Override
            public Object replacement(Object message, String outerChannel, byte[] payload)
                    throws ReflectiveOperationException {
                ByteBuf replacementContent = null;
                try {
                    replacementContent = content(message).alloc().buffer(payload.length);
                    replacementContent.writeBytes(payload);
                    return pluginMessageConstructor.newInstance(
                            outerChannel, replacementContent);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    NettyReferenceOwnership.release(replacementContent);
                    if (failure instanceof ReflectiveOperationException reflective) {
                        throw reflective;
                    }
                    throw failure;
                }
            }

            private ByteBuf content(Object message) throws ReflectiveOperationException {
                Object rawContent = pluginMessageContentMethod.invoke(message);
                if (!(rawContent instanceof ByteBuf content)) {
                    throw new IllegalStateException(
                            "Velocity PluginMessagePacket returned an invalid payload buffer");
                }
                return content;
            }
        };
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

    private static void failBeforeInstallation(
            AtomicBoolean claimed,
            CompletableFuture<Attachment> result,
            Throwable failure) {
        if (claimed.compareAndSet(false, true)) {
            result.completeExceptionally(failure);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof InvocationTargetException
                        || current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    enum Outcome {
        INSTALLED,
        ALREADY_INSTALLED
    }

    record Attachment(
            Outcome outcome,
            ModernLobbyRegisterGuardHandler handler) {
        Attachment {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(handler, "handler");
        }
    }
}
