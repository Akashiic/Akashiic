package br.com.atmbrasil.lobby.paper;

import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import org.bukkit.entity.Player;

/** Resolves the narrow Paper 1.21.1 path used to send one post-bootstrap recipe update. */
final class PaperRecipeLifecycleReleaser {
    private static final String CRAFT_PLAYER_CLASS =
            "org.bukkit.craftbukkit.entity.CraftPlayer";
    private static final String SERVER_PLAYER_CLASS =
            "net.minecraft.server.level.ServerPlayer";
    private static final String GAME_LISTENER_CLASS =
            "net.minecraft.server.network.ServerGamePacketListenerImpl";
    private static final String COMMON_LISTENER_CLASS =
            "net.minecraft.server.network.ServerCommonPacketListenerImpl";
    private static final String CONNECTION_CLASS =
            "net.minecraft.network.Connection";
    private static final String PACKET_CLASS =
            "net.minecraft.network.protocol.Packet";

    private final Class<?> craftPlayerClass;
    private final EmptyRecipeDefinitionsPacketFactory packetFactory;
    private final Method getHandleMethod;
    private final Field gameListenerField;
    private final Field networkConnectionField;
    private final Field channelField;
    private final Method sendMethod;

    private PaperRecipeLifecycleReleaser(
            Class<?> craftPlayerClass,
            EmptyRecipeDefinitionsPacketFactory packetFactory,
            Method getHandleMethod,
            Field gameListenerField,
            Field networkConnectionField,
            Field channelField,
            Method sendMethod) {
        this.craftPlayerClass = Objects.requireNonNull(craftPlayerClass, "craftPlayerClass");
        this.packetFactory = Objects.requireNonNull(packetFactory, "packetFactory");
        this.getHandleMethod = Objects.requireNonNull(getHandleMethod, "getHandleMethod");
        this.gameListenerField = Objects.requireNonNull(gameListenerField, "gameListenerField");
        this.networkConnectionField = Objects.requireNonNull(
                networkConnectionField, "networkConnectionField");
        this.channelField = Objects.requireNonNull(channelField, "channelField");
        this.sendMethod = Objects.requireNonNull(sendMethod, "sendMethod");
    }

    static PaperRecipeLifecycleReleaser resolve(
            ClassLoader classLoader,
            EmptyRecipeDefinitionsPacketFactory packetFactory)
            throws ReflectiveOperationException {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(packetFactory, "packetFactory");
        Class<?> craftPlayer = Class.forName(CRAFT_PLAYER_CLASS, false, classLoader);
        Class<?> serverPlayer = Class.forName(SERVER_PLAYER_CLASS, false, classLoader);
        Class<?> gameListener = Class.forName(GAME_LISTENER_CLASS, false, classLoader);
        Class<?> commonListener = Class.forName(COMMON_LISTENER_CLASS, false, classLoader);
        Class<?> connection = Class.forName(CONNECTION_CLASS, false, classLoader);
        Class<?> packet = Class.forName(PACKET_CLASS, false, classLoader);

        Method getHandle = craftPlayer.getMethod("getHandle");
        Field gameListenerField = serverPlayer.getField("connection");
        Field networkConnectionField = commonListener.getField("connection");
        Field channelField = connection.getField("channel");
        Method send = gameListener.getMethod("send", packet);

        if (!serverPlayer.isAssignableFrom(getHandle.getReturnType())
                || !gameListener.isAssignableFrom(gameListenerField.getType())
                || !connection.isAssignableFrom(networkConnectionField.getType())
                || !Channel.class.isAssignableFrom(channelField.getType())
                || !packetFactory.producesPacketType(packet)
                || send.getReturnType() != Void.TYPE) {
            throw new IllegalStateException(
                    "Paper 1.21.1 recipe lifecycle signatures changed");
        }
        return new PaperRecipeLifecycleReleaser(
                craftPlayer,
                packetFactory,
                getHandle,
                gameListenerField,
                networkConnectionField,
                channelField,
                send);
    }

    boolean release(Player player) {
        Objects.requireNonNull(player, "player");
        try {
            if (!craftPlayerClass.isInstance(player)) {
                throw new IllegalStateException("Bukkit Player is not CraftPlayer");
            }
            Object serverPlayer = getHandleMethod.invoke(player);
            Object gameListener = gameListenerField.get(serverPlayer);
            Object networkConnection = networkConnectionField.get(gameListener);
            Object rawChannel = channelField.get(networkConnection);
            if (!(rawChannel instanceof Channel channel)
                    || !channel.isActive()
                    || channel.pipeline().get(PaperRecipePacketGuard.GUARD_HANDLER) == null) {
                throw new IllegalStateException(
                        "Paper recipe guard is not active on the player channel");
            }
            if (RecipePacketSuppressor.recipeDefinitionsReleased(channel)) {
                return false;
            }
            RecipePacketSuppressor.armRecipeDefinitionsRelease(channel);
            sendMethod.invoke(gameListener, packetFactory.create());
            return true;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Paper denied recipe lifecycle reflection", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException(
                    "Paper rejected the empty recipe lifecycle packet", cause);
        }
    }
}
