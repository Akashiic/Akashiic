package br.com.atmbrasil.lobby.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.ChannelFuture;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Strict adapter for Velocity 4's standard CONFIG Update Tags packet. */
final class VelocityTagsInjector {
    private static final String CONNECTED_PLAYER_CLASS =
            "com.velocitypowered.proxy.connection.client.ConnectedPlayer";
    private static final String MINECRAFT_CONNECTION_CLASS =
            "com.velocitypowered.proxy.connection.MinecraftConnection";
    private static final String STATE_REGISTRY_CLASS =
            "com.velocitypowered.proxy.protocol.StateRegistry";
    private static final String TAGS_UPDATE_PACKET_CLASS =
            "com.velocitypowered.proxy.protocol.packet.config.TagsUpdatePacket";
    private static final String MINECRAFT_PACKET_CLASS =
            "com.velocitypowered.proxy.protocol.MinecraftPacket";
    private static final String PROTOCOL_DIRECTION_CLASS =
            "com.velocitypowered.proxy.protocol.ProtocolUtils$Direction";

    private final Class<?> connectedPlayerClass;
    private final Constructor<?> packetConstructor;
    private final Method getConnectionMethod;
    private final Method getStateMethod;
    private final Method writeMethod;
    private final Object configurationState;
    private final int tagsPacketId;

    private VelocityTagsInjector(
            Class<?> connectedPlayerClass,
            Constructor<?> packetConstructor,
            Method getConnectionMethod,
            Method getStateMethod,
            Method writeMethod,
            Object configurationState,
            int tagsPacketId) {
        this.connectedPlayerClass = connectedPlayerClass;
        this.packetConstructor = packetConstructor;
        this.getConnectionMethod = getConnectionMethod;
        this.getStateMethod = getStateMethod;
        this.writeMethod = writeMethod;
        this.configurationState = configurationState;
        this.tagsPacketId = tagsPacketId;
    }

    static VelocityTagsInjector resolve(int expectedProtocol) {
        ClassLoader loader = VelocityTagsInjector.class.getClassLoader();
        try {
            Class<?> connectedPlayer = Class.forName(CONNECTED_PLAYER_CLASS, false, loader);
            Class<?> minecraftConnection = Class.forName(
                    MINECRAFT_CONNECTION_CLASS, false, loader);
            Class<?> stateRegistry = Class.forName(STATE_REGISTRY_CLASS, false, loader);
            Class<?> tagsUpdatePacket = Class.forName(
                    TAGS_UPDATE_PACKET_CLASS, false, loader);
            Class<?> minecraftPacket = Class.forName(MINECRAFT_PACKET_CLASS, false, loader);
            Class<?> protocolDirection = Class.forName(
                    PROTOCOL_DIRECTION_CLASS, false, loader);

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

            Constructor<?> packetConstructor = tagsUpdatePacket.getConstructor(Map.class);
            Method getConnection = connectedPlayer.getMethod("getConnection");
            Method getState = minecraftConnection.getMethod("getState");
            Method write = minecraftConnection.getMethod("write", Object.class);
            Method getProtocolRegistry = stateRegistry.getMethod(
                    "getProtocolRegistry", protocolDirection, ProtocolVersion.class);
            if (!minecraftPacket.isAssignableFrom(tagsUpdatePacket)
                    || !minecraftConnection.isAssignableFrom(getConnection.getReturnType())
                    || !stateRegistry.isAssignableFrom(getState.getReturnType())
                    || !ChannelFuture.class.isAssignableFrom(write.getReturnType())) {
                throw new IllegalStateException("Velocity tag injection signatures changed");
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
            Object probePacket = packetConstructor.newInstance(Map.of());
            Object rawPacketId = getPacketId.invoke(protocolRegistry, probePacket);
            if (!(rawPacketId instanceof Integer packetId) || packetId < 0) {
                throw new IllegalStateException(
                        "Velocity CONFIG registry has no TagsUpdatePacket mapping");
            }
            return new VelocityTagsInjector(
                    connectedPlayer,
                    packetConstructor,
                    getConnection,
                    getState,
                    write,
                    configState,
                    packetId);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException(
                    "Velocity 4 tag injection API is incompatible", exception);
        }
    }

    int tagsPacketId() {
        return tagsPacketId;
    }

    /**
     * Writes one CONFIG Update Tags packet for registries a compatibility pack delivered. The
     * client keeps the last tag payload per registry, so these entries supersede Paper's only for
     * the registries whose numeric ids this pack itself defined.
     */
    CompletableFuture<Void> injectTagMap(Player player, Map<String, Map<String, int[]>> tags) {
        Objects.requireNonNull(tags, "tags");
        if (tags.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return injectVelocityTagMap(player, tags);
    }

    CompletableFuture<Void> inject(Player player, EmbeddedRegistryTagsProfile profile) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(profile, "profile");
        if (profile.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("registry tags profile is empty"));
        }
        return injectVelocityTagMap(player, profile.velocityTagMap());
    }

    private CompletableFuture<Void> injectVelocityTagMap(
            Player player, Map<String, Map<String, int[]>> tagMap) {
        Objects.requireNonNull(player, "player");
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            if (!connectedPlayerClass.isInstance(player)) {
                throw new IllegalStateException(
                        "Velocity Player implementation is not ConnectedPlayer");
            }
            Object connection = getConnectionMethod.invoke(player);
            Object currentState = getStateMethod.invoke(connection);
            if (currentState != configurationState) {
                throw new IllegalStateException(
                        "tag injection attempted outside CONFIG state: " + currentState);
            }
            Object packet = packetConstructor.newInstance(tagMap);
            Object rawFuture = writeMethod.invoke(connection, packet);
            if (!(rawFuture instanceof ChannelFuture channelFuture)) {
                throw new IllegalStateException("Velocity connection closed before tag write");
            }
            channelFuture.addListener(completed -> {
                if (completed.isSuccess()) {
                    result.complete(null);
                } else {
                    Throwable cause = completed.cause();
                    result.completeExceptionally(cause == null
                            ? new IllegalStateException("Velocity tag write failed")
                            : cause);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException exception) {
            result.completeExceptionally(unwrap(exception));
        }
        return result;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }
}
