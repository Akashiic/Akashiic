package br.com.atmbrasil.lobby.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Strict Velocity 4 adapter for one ordered, single-flush plugin-message transaction.
 *
 * <p>{@code Player.sendPluginMessage} creates the correct packet but immediately flushes every
 * message. Frozen registry synchronization contains more than one hundred already-reviewed
 * payloads, so the public loop needlessly creates the same number of flush boundaries. Velocity's
 * own connection API exposes {@code delayedWrite} followed by {@code write} (write-and-flush); this
 * adapter resolves that ABI once at startup and preserves every packet boundary and byte. Both
 * CONFIGURATION and PLAY mappings are verified during startup so a caller can use the returned
 * future as a real ordering barrier before releasing dependent backend traffic.</p>
 */
final class VelocityPluginMessageBatchSender {
    private static final String CONNECTED_PLAYER_CLASS =
            "com.velocitypowered.proxy.connection.client.ConnectedPlayer";
    private static final String MINECRAFT_CONNECTION_CLASS =
            "com.velocitypowered.proxy.connection.MinecraftConnection";
    private static final String STATE_REGISTRY_CLASS =
            "com.velocitypowered.proxy.protocol.StateRegistry";
    private static final String PLUGIN_MESSAGE_PACKET_CLASS =
            "com.velocitypowered.proxy.protocol.packet.PluginMessagePacket";
    private static final String MINECRAFT_PACKET_CLASS =
            "com.velocitypowered.proxy.protocol.MinecraftPacket";
    private static final String PROTOCOL_DIRECTION_CLASS =
            "com.velocitypowered.proxy.protocol.ProtocolUtils$Direction";

    private final Class<?> connectedPlayerClass;
    private final Constructor<?> packetConstructor;
    private final Method getConnectionMethod;
    private final Method getStateMethod;
    private final Method delayedWriteMethod;
    private final Method writeMethod;
    private final Method flushMethod;
    private final Object configurationState;
    private final Object playState;
    private final int configurationPluginMessagePacketId;
    private final int playPluginMessagePacketId;

    private VelocityPluginMessageBatchSender(
            Class<?> connectedPlayerClass,
            Constructor<?> packetConstructor,
            Method getConnectionMethod,
            Method getStateMethod,
            Method delayedWriteMethod,
            Method writeMethod,
            Method flushMethod,
            Object configurationState,
            Object playState,
            int configurationPluginMessagePacketId,
            int playPluginMessagePacketId) {
        this.connectedPlayerClass = connectedPlayerClass;
        this.packetConstructor = packetConstructor;
        this.getConnectionMethod = getConnectionMethod;
        this.getStateMethod = getStateMethod;
        this.delayedWriteMethod = delayedWriteMethod;
        this.writeMethod = writeMethod;
        this.flushMethod = flushMethod;
        this.configurationState = configurationState;
        this.playState = playState;
        this.configurationPluginMessagePacketId = configurationPluginMessagePacketId;
        this.playPluginMessagePacketId = playPluginMessagePacketId;
    }

    static VelocityPluginMessageBatchSender resolve(int expectedProtocol) {
        ClassLoader loader = VelocityPluginMessageBatchSender.class.getClassLoader();
        Object probePacket = null;
        try {
            Class<?> connectedPlayer = Class.forName(CONNECTED_PLAYER_CLASS, false, loader);
            Class<?> minecraftConnection = Class.forName(
                    MINECRAFT_CONNECTION_CLASS, false, loader);
            Class<?> stateRegistry = Class.forName(STATE_REGISTRY_CLASS, false, loader);
            Class<?> pluginMessagePacket = Class.forName(
                    PLUGIN_MESSAGE_PACKET_CLASS, false, loader);
            Class<?> minecraftPacket = Class.forName(MINECRAFT_PACKET_CLASS, false, loader);
            Class<?> protocolDirection = Class.forName(
                    PROTOCOL_DIRECTION_CLASS, false, loader);

            Object configState = Arrays.stream(stateRegistry.getEnumConstants())
                    .filter(constant -> constant instanceof Enum<?> enumValue
                            && enumValue.name().equals("CONFIG"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Velocity StateRegistry.CONFIG is unavailable"));
            Object playState = Arrays.stream(stateRegistry.getEnumConstants())
                    .filter(constant -> constant instanceof Enum<?> enumValue
                            && enumValue.name().equals("PLAY"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Velocity StateRegistry.PLAY is unavailable"));
            Object clientboundDirection = Arrays.stream(protocolDirection.getEnumConstants())
                    .filter(constant -> constant instanceof Enum<?> enumValue
                            && enumValue.name().equals("CLIENTBOUND"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Velocity ProtocolUtils.Direction.CLIENTBOUND is unavailable"));

            Constructor<?> constructor = pluginMessagePacket.getConstructor(
                    String.class, ByteBuf.class);
            Method getConnection = connectedPlayer.getMethod("getConnection");
            Method getState = minecraftConnection.getMethod("getState");
            Method delayedWrite = minecraftConnection.getMethod("delayedWrite", Object.class);
            Method write = minecraftConnection.getMethod("write", Object.class);
            Method flush = minecraftConnection.getMethod("flush");
            Method getProtocolRegistry = stateRegistry.getMethod(
                    "getProtocolRegistry", protocolDirection, ProtocolVersion.class);
            if (!minecraftPacket.isAssignableFrom(pluginMessagePacket)
                    || !minecraftConnection.isAssignableFrom(getConnection.getReturnType())
                    || !stateRegistry.isAssignableFrom(getState.getReturnType())
                    || delayedWrite.getReturnType() != Void.TYPE
                    || !ChannelFuture.class.isAssignableFrom(write.getReturnType())
                    || flush.getReturnType() != Void.TYPE) {
                throw new IllegalStateException(
                        "Velocity plugin-message batch signatures changed");
            }

            ProtocolVersion protocolVersion = ProtocolVersion.getProtocolVersion(expectedProtocol);
            if (protocolVersion.isUnknown() || !protocolVersion.isSupported()) {
                throw new IllegalStateException(
                        "Velocity does not support configured Minecraft protocol "
                                + expectedProtocol);
            }
            Object configurationProtocolRegistry = getProtocolRegistry.invoke(
                    configState, clientboundDirection, protocolVersion);
            Object playProtocolRegistry = getProtocolRegistry.invoke(
                    playState, clientboundDirection, protocolVersion);
            Method getConfigurationPacketId = configurationProtocolRegistry.getClass().getMethod(
                    "getPacketId", minecraftPacket);
            Method getPlayPacketId = playProtocolRegistry.getClass().getMethod(
                    "getPacketId", minecraftPacket);
            probePacket = constructor.newInstance("minecraft:brand", Unpooled.EMPTY_BUFFER);
            Object rawConfigurationPacketId = getConfigurationPacketId.invoke(
                    configurationProtocolRegistry, probePacket);
            if (!(rawConfigurationPacketId instanceof Integer configurationPacketId)
                    || configurationPacketId < 0) {
                throw new IllegalStateException(
                        "Velocity CONFIG registry has no PluginMessagePacket mapping");
            }
            Object rawPlayPacketId = getPlayPacketId.invoke(playProtocolRegistry, probePacket);
            if (!(rawPlayPacketId instanceof Integer playPacketId) || playPacketId < 0) {
                throw new IllegalStateException(
                        "Velocity PLAY registry has no PluginMessagePacket mapping");
            }
            return new VelocityPluginMessageBatchSender(
                    connectedPlayer,
                    constructor,
                    getConnection,
                    getState,
                    delayedWrite,
                    write,
                    flush,
                    configState,
                    playState,
                    configurationPacketId,
                    playPacketId);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException(
                    "Velocity 4 plugin-message batch API is incompatible", exception);
        } finally {
            ReferenceCountUtil.release(probePacket);
        }
    }

    int pluginMessagePacketId() {
        return configurationPluginMessagePacketId;
    }

    int playPluginMessagePacketId() {
        return playPluginMessagePacketId;
    }

    CompletableFuture<Void> send(Player player, Batch batch) {
        return sendInState(player, batch, configurationState, "CONFIG");
    }

    CompletableFuture<Void> sendPlay(Player player, Batch batch) {
        return sendInState(player, batch, playState, "PLAY");
    }

    private CompletableFuture<Void> sendInState(
            Player player,
            Batch batch,
            Object expectedState,
            String expectedStateName) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(batch, "batch");
        CompletableFuture<Void> result = new CompletableFuture<>();
        ArrayList<Object> preparedPackets = new ArrayList<>(batch.packetCount());
        int handedToConnection = 0;
        Object connection = null;
        try {
            if (!connectedPlayerClass.isInstance(player)) {
                throw new IllegalStateException(
                        "Velocity Player implementation is not ConnectedPlayer");
            }
            connection = getConnectionMethod.invoke(player);
            Object currentState = getStateMethod.invoke(connection);
            if (currentState != expectedState) {
                throw new ProtocolStateMismatchException(
                        "plugin-message batch attempted outside "
                                + expectedStateName + " state: " + currentState);
            }

            for (Payload payload : batch.payloads()) {
                ByteBuf body = payload.newReadOnlyBuffer();
                try {
                    preparedPackets.add(packetConstructor.newInstance(payload.channelId(), body));
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    body.release();
                    throw exception;
                }
            }

            for (int index = 0; index < preparedPackets.size() - 1; index++) {
                // Ownership passes to the channel even if its implementation releases a closed
                // write immediately. Mark it first to prevent a double release on reflection
                // failure.
                handedToConnection++;
                delayedWriteMethod.invoke(connection, preparedPackets.get(index));
            }
            handedToConnection++;
            Object rawFuture = writeMethod.invoke(
                    connection, preparedPackets.get(preparedPackets.size() - 1));
            if (!(rawFuture instanceof ChannelFuture channelFuture)) {
                throw new IllegalStateException(
                        "Velocity connection closed before plugin-message batch write");
            }
            channelFuture.addListener(completed -> {
                if (completed.isSuccess()) {
                    result.complete(null);
                } else {
                    Throwable cause = completed.cause();
                    result.completeExceptionally(cause == null
                            ? new IllegalStateException(
                                    "Velocity plugin-message batch write failed")
                            : cause);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException exception) {
            releaseUnhanded(preparedPackets, handedToConnection);
            flushAfterPartialWrite(connection, handedToConnection);
            result.completeExceptionally(unwrap(exception));
        }
        return result;
    }

    static boolean isProtocolStateMismatch(Throwable throwable) {
        return throwable instanceof ProtocolStateMismatchException;
    }

    private void flushAfterPartialWrite(Object connection, int handedToConnection) {
        if (connection == null || handedToConnection == 0) {
            return;
        }
        try {
            flushMethod.invoke(connection);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The caller will fail closed and disconnect. Netty owns every handed-off packet.
        }
    }

    private static void releaseUnhanded(List<Object> packets, int handedToConnection) {
        for (int index = handedToConnection; index < packets.size(); index++) {
            ReferenceCountUtil.release(packets.get(index));
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }

    private static final class ProtocolStateMismatchException
            extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ProtocolStateMismatchException(String message) {
            super(message);
        }
    }

    /** Immutable, reusable wire plan. A fresh read-only ByteBuf view is created for every send. */
    static final class Batch {
        private final String profileId;
        private final List<Payload> payloads;
        private final int totalBytes;
        private final String sequenceSha256;

        private Batch(
                String profileId,
                List<Payload> payloads,
                int totalBytes,
                String sequenceSha256) {
            if (Objects.requireNonNull(profileId, "profileId").isBlank()) {
                throw new IllegalArgumentException("batch profile id must not be blank");
            }
            this.profileId = profileId;
            this.payloads = List.copyOf(Objects.requireNonNull(payloads, "payloads"));
            if (this.payloads.isEmpty()) {
                throw new IllegalArgumentException("plugin-message batch must not be empty");
            }
            int actualBytes = this.payloads.stream().mapToInt(Payload::byteLength).sum();
            if (totalBytes < 0 || actualBytes != totalBytes) {
                throw new IllegalArgumentException("plugin-message batch byte total mismatch");
            }
            this.totalBytes = totalBytes;
            this.sequenceSha256 = Objects.requireNonNull(sequenceSha256, "sequenceSha256");
        }

        static Batch frozenRegistryTransaction(SilentGearEmbeddedProfile embeddedProfile) {
            Objects.requireNonNull(embeddedProfile, "embeddedProfile");
            NeoForgeFrozenRegistryProfile profile = embeddedProfile.frozenRegistries();
            ArrayList<Payload> payloads = new ArrayList<>(profile.registryCount() + 2);
            // These accessors return defensive copies. Payload takes exclusive ownership of each
            // copy and keeps it for the lifetime of this bounded, generation-specific cache.
            payloads.add(Payload.takeOwnership(
                    NeoForgeFrozenRegistryProfile.START_CHANNEL,
                    profile.startPayload()));
            for (NeoForgeFrozenRegistryProfile.RegistryPayload registry
                    : profile.registryPayloads()) {
                payloads.add(Payload.takeOwnership(
                        NeoForgeFrozenRegistryProfile.REGISTRY_CHANNEL,
                        registry.bytes()));
            }
            payloads.add(Payload.takeOwnership(
                    NeoForgeFrozenRegistryProfile.COMPLETED_CHANNEL,
                    profile.completedPayload()));
            return new Batch(
                    embeddedProfile.profileId(),
                    payloads,
                    profile.totalTransactionBytes(),
                    profile.sequenceSha256());
        }

        static Builder builder(String planId) {
            return new Builder(planId);
        }

        String profileId() {
            return profileId;
        }

        int packetCount() {
            return payloads.size();
        }

        int totalBytes() {
            return totalBytes;
        }

        String sequenceSha256() {
            return sequenceSha256;
        }

        private List<Payload> payloads() {
            return payloads;
        }

        static final class Builder {
            private final String planId;
            private final ArrayList<Payload> payloads = new ArrayList<>();
            private int totalBytes;
            private boolean built;

            private Builder(String planId) {
                if (Objects.requireNonNull(planId, "planId").isBlank()) {
                    throw new IllegalArgumentException("batch plan id must not be blank");
                }
                this.planId = planId;
            }

            Builder addOwned(String channelId, byte[] ownedBytes) {
                if (built) {
                    throw new IllegalStateException("plugin-message batch was already built");
                }
                Payload payload = Payload.takeOwnership(channelId, ownedBytes);
                totalBytes = Math.addExact(totalBytes, payload.byteLength());
                payloads.add(payload);
                return this;
            }

            int packetCount() {
                return payloads.size();
            }

            int totalBytes() {
                return totalBytes;
            }

            Batch build() {
                if (built) {
                    throw new IllegalStateException("plugin-message batch was already built");
                }
                built = true;
                if (payloads.isEmpty()) {
                    throw new IllegalStateException("plugin-message batch is empty");
                }
                return new Batch(
                        planId,
                        payloads,
                        totalBytes,
                        wireSequenceSha256(payloads));
            }

            private static String wireSequenceSha256(List<Payload> payloads) {
                MessageDigest digest = newSha256();
                updateInt(digest, payloads.size());
                for (Payload payload : payloads) {
                    byte[] channelBytes = payload.channelId()
                            .getBytes(StandardCharsets.UTF_8);
                    updateInt(digest, channelBytes.length);
                    digest.update(channelBytes);
                    updateInt(digest, payload.byteLength());
                    digest.update(payload.bytes);
                }
                return HexFormat.of().formatHex(digest.digest());
            }

            private static MessageDigest newSha256() {
                try {
                    return MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException impossible) {
                    throw new ExceptionInInitializerError(impossible);
                }
            }

            private static void updateInt(MessageDigest digest, int value) {
                digest.update((byte) (value >>> 24));
                digest.update((byte) (value >>> 16));
                digest.update((byte) (value >>> 8));
                digest.update((byte) value);
            }
        }
    }

    private static final class Payload {
        private final String channelId;
        private final byte[] bytes;

        private Payload(String channelId, byte[] ownedBytes) {
            if (Objects.requireNonNull(channelId, "channelId").isBlank()) {
                throw new IllegalArgumentException("batch channel id must not be blank");
            }
            this.channelId = channelId;
            this.bytes = Objects.requireNonNull(ownedBytes, "ownedBytes");
        }

        private static Payload takeOwnership(String channelId, byte[] ownedBytes) {
            return new Payload(channelId, ownedBytes);
        }

        private String channelId() {
            return channelId;
        }

        private int byteLength() {
            return bytes.length;
        }

        private ByteBuf newReadOnlyBuffer() {
            return Unpooled.wrappedBuffer(bytes).asReadOnly();
        }
    }
}
