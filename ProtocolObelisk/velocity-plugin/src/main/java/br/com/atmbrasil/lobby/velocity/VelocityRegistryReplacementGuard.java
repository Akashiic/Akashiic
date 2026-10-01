package br.com.atmbrasil.lobby.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs the exact ATM10 8.1 CONFIG registry replacement on the client connection. */
final class VelocityRegistryReplacementGuard {
    static final String HANDLER_NAME =
            "protocolobelisk-atm10-8.1-registry-replacement";

    private static final String CONNECTED_PLAYER_CLASS =
            "com.velocitypowered.proxy.connection.client.ConnectedPlayer";
    private static final String MINECRAFT_CONNECTION_CLASS =
            "com.velocitypowered.proxy.connection.MinecraftConnection";
    private static final String REGISTRY_SYNC_PACKET_CLASS =
            "com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket";
    private static final String DEFERRED_HOLDER_CLASS =
            "com.velocitypowered.proxy.protocol.util.DeferredByteBufHolder";
    private static final String MINECRAFT_ENCODER_CLASS =
            "com.velocitypowered.proxy.protocol.netty.MinecraftEncoder";
    private static final String PROTOCOL_DIRECTION_CLASS =
            "com.velocitypowered.proxy.protocol.ProtocolUtils$Direction";

    private final int expectedProtocol;
    private final Class<?> connectedPlayerClass;
    private final Class<?> minecraftConnectionClass;
    private final Class<?> registrySyncPacketClass;
    private final Class<?> minecraftEncoderClass;
    private final Constructor<?> registryPacketConstructor;
    private final Method getConnectionMethod;
    private final Method getChannelMethod;
    private final Method holderContentMethod;
    private final Method holderReplaceMethod;
    private final Method encoderDirectionMethod;
    private final Object clientboundDirection;

    private VelocityRegistryReplacementGuard(
            int expectedProtocol,
            Class<?> connectedPlayerClass,
            Class<?> minecraftConnectionClass,
            Class<?> registrySyncPacketClass,
            Class<?> minecraftEncoderClass,
            Constructor<?> registryPacketConstructor,
            Method getConnectionMethod,
            Method getChannelMethod,
            Method holderContentMethod,
            Method holderReplaceMethod,
            Method encoderDirectionMethod,
            Object clientboundDirection) {
        this.expectedProtocol = expectedProtocol;
        this.connectedPlayerClass = connectedPlayerClass;
        this.minecraftConnectionClass = minecraftConnectionClass;
        this.registrySyncPacketClass = registrySyncPacketClass;
        this.minecraftEncoderClass = minecraftEncoderClass;
        this.registryPacketConstructor = registryPacketConstructor;
        this.getConnectionMethod = getConnectionMethod;
        this.getChannelMethod = getChannelMethod;
        this.holderContentMethod = holderContentMethod;
        this.holderReplaceMethod = holderReplaceMethod;
        this.encoderDirectionMethod = encoderDirectionMethod;
        this.clientboundDirection = clientboundDirection;
    }

    /** Resolves every Velocity/Netty signature and protocol invariant before plugin enablement. */
    static VelocityRegistryReplacementGuard resolve(int expectedProtocol) {
        if (expectedProtocol != Atm10Normal81EnchantmentRegistry.PROTOCOL_VERSION) {
            throw new IllegalArgumentException(
                    "reviewed registry transform requires protocol 767");
        }
        ClassLoader loader = VelocityRegistryReplacementGuard.class.getClassLoader();
        try {
            Class<?> connectedPlayer = Class.forName(
                    CONNECTED_PLAYER_CLASS, false, loader);
            Class<?> minecraftConnection = Class.forName(
                    MINECRAFT_CONNECTION_CLASS, false, loader);
            Class<?> registrySyncPacket = Class.forName(
                    REGISTRY_SYNC_PACKET_CLASS, false, loader);
            Class<?> deferredHolder = Class.forName(
                    DEFERRED_HOLDER_CLASS, false, loader);
            Class<?> minecraftEncoder = Class.forName(
                    MINECRAFT_ENCODER_CLASS, false, loader);
            Class<?> protocolDirection = Class.forName(
                    PROTOCOL_DIRECTION_CLASS, false, loader);

            Constructor<?> packetConstructor = registrySyncPacket.getConstructor();
            Method getConnection = connectedPlayer.getMethod("getConnection");
            Method getChannel = minecraftConnection.getMethod("getChannel");
            Method content = deferredHolder.getMethod("content");
            Method replace = deferredHolder.getMethod("replace", ByteBuf.class);
            Method getDirection = minecraftEncoder.getMethod("getDirection");
            Object clientbound = enumConstant(protocolDirection, "CLIENTBOUND");

            if (!Player.class.isAssignableFrom(connectedPlayer)
                    || !minecraftConnection.isAssignableFrom(getConnection.getReturnType())
                    || !Channel.class.isAssignableFrom(getChannel.getReturnType())
                    || !deferredHolder.isAssignableFrom(registrySyncPacket)
                    || !ByteBuf.class.isAssignableFrom(content.getReturnType())
                    || !deferredHolder.isAssignableFrom(replace.getDeclaringClass())
                    || !ChannelOutboundHandler.class.isAssignableFrom(minecraftEncoder)
                    || getDirection.getReturnType() != protocolDirection) {
                throw new IllegalStateException(
                        "Velocity registry replacement signatures changed");
            }

            ProtocolVersion protocolVersion = ProtocolVersion.getProtocolVersion(
                    expectedProtocol);
            if (protocolVersion.isUnknown()
                    || !protocolVersion.isSupported()
                    || protocolVersion.getProtocol() != expectedProtocol) {
                throw new IllegalStateException(
                        "Velocity does not support reviewed Minecraft protocol "
                                + expectedProtocol);
            }

            return new VelocityRegistryReplacementGuard(
                    expectedProtocol,
                    connectedPlayer,
                    minecraftConnection,
                    registrySyncPacket,
                    minecraftEncoder,
                    packetConstructor,
                    getConnection,
                    getChannel,
                    content,
                    replace,
                    getDirection,
                    clientbound);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IllegalStateException(
                    "Velocity 4 registry replacement API is incompatible", unwrap(failure));
        }
    }

    /**
     * Attaches to the exact ConnectedPlayer outbound pipeline.
     *
     * <p>The returned lease's completion resolves only after the replacement object's downstream
     * {@code ChannelPromise} succeeds. Closing before that point reports absence/failure but never
     * disconnects or denies the player directly.</p>
     */
    CompletableFuture<Lease> attach(
            Player player,
            RegistryShimPacket replacementPacket,
            RegistryReplacementGuardHandler.Listener listener) {
        return attach(
                player,
                replacementPacket,
                RegistryReplacementGuardHandler.TransformMode.EXACT_REPLACEMENT,
                listener);
    }

    CompletableFuture<Lease> attach(
            Player player,
            RegistryShimPacket replacementPacket,
            RegistryReplacementGuardHandler.TransformMode transformMode,
            RegistryReplacementGuardHandler.Listener listener) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(replacementPacket, "replacementPacket");
        Objects.requireNonNull(transformMode, "transformMode");
        Objects.requireNonNull(listener, "listener");
        CompletableFuture<Lease> result = new CompletableFuture<>();
        try {
            if (player.getProtocolVersion().getProtocol() != expectedProtocol) {
                throw new IllegalArgumentException(
                        "registry replacement guard received an unexpected client protocol");
            }
            if (!connectedPlayerClass.isInstance(player)) {
                throw new IllegalStateException(
                        "Velocity Player implementation is not ConnectedPlayer");
            }
            Object connection = getConnectionMethod.invoke(player);
            if (!minecraftConnectionClass.isInstance(connection)) {
                throw new IllegalStateException(
                        "Velocity client MinecraftConnection is unavailable");
            }
            Object rawChannel = getChannelMethod.invoke(connection);
            if (!(rawChannel instanceof Channel channel)) {
                throw new IllegalStateException(
                        "Velocity client Netty channel is unavailable");
            }

            Runnable install = () -> installOnEventLoop(
                    channel, replacementPacket, transformMode, listener, result);
            if (channel.eventLoop().inEventLoop()) {
                install.run();
            } else {
                channel.eventLoop().execute(install);
            }
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(new IllegalStateException(
                    "Velocity event loop rejected registry replacement guard", failure));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            result.completeExceptionally(unwrap(failure));
        }
        return result;
    }

    private void installOnEventLoop(
            Channel channel,
            RegistryShimPacket replacementPacket,
            RegistryReplacementGuardHandler.TransformMode transformMode,
            RegistryReplacementGuardHandler.Listener listener,
            CompletableFuture<Lease> result) {
        RegistryReplacementLease lease = null;
        boolean installed = false;
        try {
            if (result.isDone()) {
                return;
            }
            if (!channel.isActive()) {
                throw new IllegalStateException(
                        "Velocity client channel closed before registry guard attachment");
            }
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.context(HANDLER_NAME) != null) {
                throw new IllegalStateException(
                        "Velocity client already has a registry replacement guard");
            }
            ChannelHandlerContext encoder = findClientboundMinecraftEncoder(pipeline);
            RegistryReplacementGuardHandler handler =
                    new RegistryReplacementGuardHandler(
                            replacementPacket, transformMode, packetAccess(), listener);
            lease = new RegistryReplacementLease(channel, handler);
            pipeline.addAfter(encoder.name(), HANDLER_NAME, handler);
            installed = true;
            if (!result.complete(lease)) {
                lease.detach();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (installed && lease != null) {
                lease.detach();
            }
            result.completeExceptionally(unwrap(failure));
        }
    }

    private RegistryReplacementGuardHandler.PacketAccess packetAccess() {
        return new RegistryReplacementGuardHandler.PacketAccess() {
            @Override
            public boolean isRegistrySyncPacket(Object message) {
                return registrySyncPacketClass.isInstance(message);
            }

            @Override
            public int readableBodyBytes(Object message) throws ReflectiveOperationException {
                return content(message).readableBytes();
            }

            @Override
            public byte[] copyBody(Object message, int expectedBytes)
                    throws ReflectiveOperationException {
                ByteBuf content = content(message);
                if (content.readableBytes() != expectedBytes) {
                    throw new IllegalStateException(
                            "Velocity registry packet body size changed during inspection");
                }
                byte[] body = new byte[expectedBytes];
                content.duplicate().readBytes(body);
                return body;
            }

            @Override
            public Object replacement(Object original, byte[] replacementBody)
                    throws ReflectiveOperationException {
                Objects.requireNonNull(replacementBody, "replacementBody");
                ByteBuf body = null;
                Object packet = null;
                try {
                    body = content(original).alloc().buffer(replacementBody.length);
                    body.writeBytes(replacementBody);
                    packet = registryPacketConstructor.newInstance();
                    Object replaced = holderReplaceMethod.invoke(packet, body);
                    if (replaced != packet) {
                        throw new IllegalStateException(
                                "Velocity deferred holder replace did not return its packet");
                    }
                    body = null;
                    return packet;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    if (packet != null && body == null) {
                        NettyReferenceOwnership.release(packet);
                    } else {
                        NettyReferenceOwnership.release(body);
                    }
                    if (failure instanceof ReflectiveOperationException reflective) {
                        throw reflective;
                    }
                    throw failure;
                }
            }

            private ByteBuf content(Object message) throws ReflectiveOperationException {
                Object rawContent = holderContentMethod.invoke(message);
                if (!(rawContent instanceof ByteBuf content)) {
                    throw new IllegalStateException(
                            "Velocity RegistrySyncPacket returned an invalid body buffer");
                }
                return content;
            }
        };
    }

    private ChannelHandlerContext findClientboundMinecraftEncoder(ChannelPipeline pipeline)
            throws ReflectiveOperationException {
        ChannelHandlerContext match = null;
        for (String name : pipeline.names()) {
            ChannelHandlerContext context = pipeline.context(name);
            if (context == null || !minecraftEncoderClass.isInstance(context.handler())) {
                continue;
            }
            Object direction = encoderDirectionMethod.invoke(context.handler());
            if (direction != clientboundDirection) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException(
                        "Velocity client has multiple clientbound Minecraft encoders");
            }
            match = context;
        }
        if (match == null) {
            throw new IllegalStateException(
                    "Velocity clientbound Minecraft encoder is unavailable");
        }
        return match;
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

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    interface Lease extends AutoCloseable {
        boolean active();

        CompletableFuture<RegistryShimReceipt> completion();

        void detach();

        @Override
        default void close() {
            detach();
        }
    }

    private static final class RegistryReplacementLease implements Lease {
        private final Channel channel;
        private final RegistryReplacementGuardHandler handler;
        private final AtomicBoolean detached = new AtomicBoolean();

        private RegistryReplacementLease(
                Channel channel, RegistryReplacementGuardHandler handler) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        @Override
        public boolean active() {
            return handler.active() && !detached.get();
        }

        @Override
        public CompletableFuture<RegistryShimReceipt> completion() {
            return handler.completion();
        }

        @Override
        public void detach() {
            if (!detached.compareAndSet(false, true)) {
                return;
            }
            handler.deactivate();
            Runnable remove = () -> {
                ChannelHandlerContext context = channel.pipeline().context(HANDLER_NAME);
                if (context != null && context.handler() == handler) {
                    channel.pipeline().remove(handler);
                }
            };
            try {
                if (channel.eventLoop().inEventLoop()) {
                    remove.run();
                } else {
                    channel.eventLoop().execute(remove);
                }
            } catch (RejectedExecutionException ignored) {
                // A terminated client channel has no remaining outbound packet ownership.
            }
        }
    }

}
