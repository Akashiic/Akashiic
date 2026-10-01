package br.com.atmbrasil.lobby.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Strict adapter for Velocity 4's deferred CONFIG registry packet.
 *
 * <p>Velocity intentionally exposes no public API for arbitrary Minecraft configuration packets.
 * This adapter resolves the narrow internal surface once during startup and disables the bridge if
 * that surface is incompatible. No fields are opened and no packet id is hard-coded: Velocity's
 * own CONFIG registry maps {@code RegistrySyncPacket} to the protocol-specific packet id.</p>
 */
final class VelocityRegistryInjector {
    private static final String CONNECTED_PLAYER_CLASS =
            "com.velocitypowered.proxy.connection.client.ConnectedPlayer";
    private static final String MINECRAFT_CONNECTION_CLASS =
            "com.velocitypowered.proxy.connection.MinecraftConnection";
    private static final String STATE_REGISTRY_CLASS =
            "com.velocitypowered.proxy.protocol.StateRegistry";
    private static final String REGISTRY_SYNC_PACKET_CLASS =
            "com.velocitypowered.proxy.protocol.packet.config.RegistrySyncPacket";
    private static final String MINECRAFT_PACKET_CLASS =
            "com.velocitypowered.proxy.protocol.MinecraftPacket";
    private static final String PROTOCOL_DIRECTION_CLASS =
            "com.velocitypowered.proxy.protocol.ProtocolUtils$Direction";
    private static final String DEFERRED_HOLDER_CLASS =
            "com.velocitypowered.proxy.protocol.util.DeferredByteBufHolder";

    private final Class<?> connectedPlayerClass;
    private final Constructor<?> packetConstructor;
    private final Method packetReplaceMethod;
    private final Method getConnectionMethod;
    private final Method getStateMethod;
    private final Method delayedWriteMethod;
    private final Method writeMethod;
    private final Method flushMethod;
    private final Object configurationState;
    private final int registryPacketId;

    private VelocityRegistryInjector(
            Class<?> connectedPlayerClass,
            Constructor<?> packetConstructor,
            Method packetReplaceMethod,
            Method getConnectionMethod,
            Method getStateMethod,
            Method delayedWriteMethod,
            Method writeMethod,
            Method flushMethod,
            Object configurationState,
            int registryPacketId) {
        this.connectedPlayerClass = connectedPlayerClass;
        this.packetConstructor = packetConstructor;
        this.packetReplaceMethod = packetReplaceMethod;
        this.getConnectionMethod = getConnectionMethod;
        this.getStateMethod = getStateMethod;
        this.delayedWriteMethod = delayedWriteMethod;
        this.writeMethod = writeMethod;
        this.flushMethod = flushMethod;
        this.configurationState = configurationState;
        this.registryPacketId = registryPacketId;
    }

    static VelocityRegistryInjector resolve(int expectedProtocol) {
        ClassLoader loader = VelocityRegistryInjector.class.getClassLoader();
        try {
            Class<?> connectedPlayer = Class.forName(CONNECTED_PLAYER_CLASS, false, loader);
            Class<?> minecraftConnection = Class.forName(
                    MINECRAFT_CONNECTION_CLASS, false, loader);
            Class<?> stateRegistry = Class.forName(STATE_REGISTRY_CLASS, false, loader);
            Class<?> registrySyncPacket = Class.forName(
                    REGISTRY_SYNC_PACKET_CLASS, false, loader);
            Class<?> minecraftPacket = Class.forName(MINECRAFT_PACKET_CLASS, false, loader);
            Class<?> protocolDirection = Class.forName(
                    PROTOCOL_DIRECTION_CLASS, false, loader);
            Class<?> deferredHolder = Class.forName(DEFERRED_HOLDER_CLASS, false, loader);

            Object configState = Arrays.stream(stateRegistry.getEnumConstants())
                    .filter(constant -> constant instanceof Enum<?> enumValue
                            && enumValue.name().equals("CONFIG"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Velocity StateRegistry.CONFIG is unavailable"));
            Object clientboundDirection = Arrays.stream(protocolDirection.getEnumConstants())
                    .filter(constant -> constant instanceof Enum<?> enumValue
                            && enumValue.name().equals("CLIENTBOUND"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Velocity ProtocolUtils.Direction.CLIENTBOUND is unavailable"));

            Constructor<?> packetConstructor = registrySyncPacket.getConstructor();
            Method replace = deferredHolder.getMethod("replace", ByteBuf.class);
            Method getConnection = connectedPlayer.getMethod("getConnection");
            Method getState = minecraftConnection.getMethod("getState");
            Method delayedWrite = minecraftConnection.getMethod(
                    "delayedWrite", Object.class);
            Method write = minecraftConnection.getMethod("write", Object.class);
            Method flush = minecraftConnection.getMethod("flush");
            Method getProtocolRegistry = stateRegistry.getMethod(
                    "getProtocolRegistry", protocolDirection, ProtocolVersion.class);

            if (!deferredHolder.isAssignableFrom(registrySyncPacket)
                    || !minecraftConnection.isAssignableFrom(getConnection.getReturnType())
                    || !stateRegistry.isAssignableFrom(getState.getReturnType())
                    || delayedWrite.getReturnType() != Void.TYPE
                    || !ChannelFuture.class.isAssignableFrom(write.getReturnType())
                    || flush.getReturnType() != Void.TYPE) {
                throw new IllegalStateException("Velocity registry injection signatures changed");
            }

            ProtocolVersion protocolVersion = ProtocolVersion.getProtocolVersion(expectedProtocol);
            if (protocolVersion.isUnknown() || !protocolVersion.isSupported()) {
                throw new IllegalStateException(
                        "Velocity does not support configured Minecraft protocol "
                                + expectedProtocol);
            }
            Object protocolRegistry = getProtocolRegistry.invoke(
                    configState, clientboundDirection, protocolVersion);
            Method getPacketId = protocolRegistry.getClass().getMethod(
                    "getPacketId", minecraftPacket);
            Object probePacket = packetConstructor.newInstance();
            Object rawPacketId = getPacketId.invoke(protocolRegistry, probePacket);
            if (!(rawPacketId instanceof Integer packetId) || packetId < 0) {
                throw new IllegalStateException(
                        "Velocity CONFIG registry has no RegistrySyncPacket mapping");
            }

            return new VelocityRegistryInjector(
                    connectedPlayer,
                    packetConstructor,
                    replace,
                    getConnection,
                    getState,
                    delayedWrite,
                    write,
                    flush,
                    configState,
                    packetId);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException(
                    "Velocity 4 registry injection API is incompatible", exception);
        }
    }

    int registryPacketId() {
        return registryPacketId;
    }

    CompletableFuture<Void> inject(Player player, RegistryShimPacket registryPacket) {
        return injectBatch(player, List.of(registryPacket));
    }

    /** Writes an ordered registry tail with one final flush instead of one flush per packet. */
    CompletableFuture<Void> injectBatch(
            Player player, List<RegistryShimPacket> registryPackets) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(registryPackets, "registryPackets");
        if (registryPackets.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("registry packet batch is empty"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        ArrayList<Object> packets = new ArrayList<>(registryPackets.size());
        int handedToConnection = 0;
        Object connection = null;
        try {
            if (!connectedPlayerClass.isInstance(player)) {
                throw new IllegalStateException(
                        "Velocity Player implementation is not ConnectedPlayer");
            }
            connection = getConnectionMethod.invoke(player);
            Object currentState = getStateMethod.invoke(connection);
            if (currentState != configurationState) {
                throw new IllegalStateException(
                        "registry injection attempted outside CONFIG state: " + currentState);
            }

            for (RegistryShimPacket registryPacket : registryPackets) {
                Objects.requireNonNull(registryPacket, "registryPacket");
                Object packet = packetConstructor.newInstance();
                ByteBuf body = registryPacket.newReadOnlyPacketBody();
                try {
                    packetReplaceMethod.invoke(packet, body);
                    packets.add(packet);
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    body.release();
                    throw exception;
                }
            }

            for (int index = 0; index < packets.size() - 1; index++) {
                handedToConnection++;
                delayedWriteMethod.invoke(connection, packets.get(index));
            }
            handedToConnection++;
            Object rawFuture = writeMethod.invoke(connection, packets.get(packets.size() - 1));
            if (!(rawFuture instanceof ChannelFuture channelFuture)) {
                throw new IllegalStateException(
                        "Velocity connection closed before registry batch write");
            }
            channelFuture.addListener(completed -> {
                if (completed.isSuccess()) {
                    result.complete(null);
                } else {
                    Throwable cause = completed.cause();
                    result.completeExceptionally(cause == null
                            ? new IllegalStateException("Velocity registry write failed")
                            : cause);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException exception) {
            for (int index = handedToConnection; index < packets.size(); index++) {
                ReferenceCountUtil.release(packets.get(index));
            }
            flushAfterPartialWrite(connection, handedToConnection);
            result.completeExceptionally(unwrap(exception));
        }
        return result;
    }

    private void flushAfterPartialWrite(Object connection, int handedToConnection) {
        if (connection == null || handedToConnection == 0) {
            return;
        }
        try {
            flushMethod.invoke(connection);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The caller fails closed; the connection owns every handed-off reference.
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }
}
