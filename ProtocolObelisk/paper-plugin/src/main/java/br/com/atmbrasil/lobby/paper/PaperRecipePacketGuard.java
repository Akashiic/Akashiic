package br.com.atmbrasil.lobby.paper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Installs a Paper 1.21.1 channel initializer that applies the lobby recipe policy.
 *
 * <p>NeoForge makes {@code RecipeBookType} extensible. A client whose connection was negotiated
 * as NeoForge expects settings for those extra enum values, while a Paper backend serializes only
 * the vanilla values. Modded clients may also react to vanilla recipe definitions by reading
 * server-side mod data that a Paper lobby cannot provide. The recipe-book stream is dropped. The
 * recipe-definition stream is withheld until Velocity confirms all negotiated PLAY bootstraps,
 * then exactly one NMS packet containing an empty collection fires the client's recipe-update
 * lifecycle without exposing Paper recipe data.</p>
 */
final class PaperRecipePacketGuard implements AutoCloseable {
    private static final String INITIALIZER_HOLDER_CLASS =
            "io.papermc.paper.network.ChannelInitializeListenerHolder";
    private static final String INITIALIZER_LISTENER_CLASS =
            "io.papermc.paper.network.ChannelInitializeListener";
    private static final String MINECRAFT_PACKET_HANDLER = "packet_handler";
    static final String GUARD_HANDLER = "atm10_lobby_recipe_guard";
    private static final Key LISTENER_KEY =
            Key.key("atm10-lobby-bridge", "recipe-packet-guard");

    private final JavaPlugin plugin;
    private final DiagnosticLog diagnosticLog;
    private final Class<?> listenerClass;
    private final Method addListenerMethod;
    private final Method removeListenerMethod;
    private final Object listenerProxy;
    private final boolean suppressRecipeBookPackets;
    private final boolean rewriteRecipeDefinitionPackets;
    private final EmptyRecipeDefinitionsPacketFactory emptyRecipeDefinitionsPacketFactory;
    private final PaperRecipeLifecycleReleaser recipeLifecycleReleaser;
    private final LongAdder guardedChannels = new LongAdder();
    private final LongAdder droppedRecipeBookPackets = new LongAdder();
    private final LongAdder suppressedRecipeDefinitionPackets = new LongAdder();
    private final LongAdder rewrittenRecipeDefinitionPackets = new LongAdder();
    private volatile boolean closed;

    private PaperRecipePacketGuard(
            JavaPlugin plugin,
            boolean suppressRecipeBookPackets,
            boolean rewriteRecipeDefinitionPackets,
            DiagnosticLog diagnosticLog) throws ReflectiveOperationException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.diagnosticLog = Objects.requireNonNull(diagnosticLog, "diagnosticLog");
        if (!suppressRecipeBookPackets && !rewriteRecipeDefinitionPackets) {
            throw new IllegalArgumentException("at least one recipe packet policy must be active");
        }
        this.suppressRecipeBookPackets = suppressRecipeBookPackets;
        this.rewriteRecipeDefinitionPackets = rewriteRecipeDefinitionPackets;
        Class<?> holderClass = Class.forName(INITIALIZER_HOLDER_CLASS, false,
                plugin.getClass().getClassLoader());
        emptyRecipeDefinitionsPacketFactory = rewriteRecipeDefinitionPackets
                ? EmptyRecipeDefinitionsPacketFactory.resolve(holderClass.getClassLoader())
                : null;
        recipeLifecycleReleaser = rewriteRecipeDefinitionPackets
                ? PaperRecipeLifecycleReleaser.resolve(
                        holderClass.getClassLoader(), emptyRecipeDefinitionsPacketFactory)
                : null;
        listenerClass = Class.forName(INITIALIZER_LISTENER_CLASS, false,
                plugin.getClass().getClassLoader());
        Method hasListenerMethod = holderClass.getMethod("hasListener", Key.class);
        addListenerMethod = holderClass.getMethod("addListener", Key.class, listenerClass);
        removeListenerMethod = holderClass.getMethod("removeListener", Key.class);

        if (Boolean.TRUE.equals(hasListenerMethod.invoke(null, LISTENER_KEY))) {
            throw new IllegalStateException(
                    "Paper channel initializer key is already registered: " + LISTENER_KEY);
        }

        InvocationHandler invocationHandler = this::invokeInitializer;
        listenerProxy = Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[] {listenerClass},
                invocationHandler);
        addListenerMethod.invoke(null, LISTENER_KEY, listenerProxy);
    }

    static PaperRecipePacketGuard install(
            JavaPlugin plugin,
            boolean suppressRecipeBookPackets,
            boolean rewriteRecipeDefinitionPackets,
            DiagnosticLog diagnosticLog) throws ReflectiveOperationException {
        return new PaperRecipePacketGuard(
                plugin, suppressRecipeBookPackets, rewriteRecipeDefinitionPackets, diagnosticLog);
    }

    long guardedChannelCount() {
        return guardedChannels.sum();
    }

    long droppedRecipeBookPacketCount() {
        return droppedRecipeBookPackets.sum();
    }

    long rewrittenRecipeDefinitionPacketCount() {
        return rewrittenRecipeDefinitionPackets.sum();
    }

    long suppressedRecipeDefinitionPacketCount() {
        return suppressedRecipeDefinitionPackets.sum();
    }

    boolean releaseRecipeLifecycle(Player player) {
        Objects.requireNonNull(player, "player");
        if (recipeLifecycleReleaser == null) {
            return false;
        }
        boolean scheduled = recipeLifecycleReleaser.release(player);
        if (scheduled) {
            diagnosticLog.info(() ->
                    "Released one empty recipe lifecycle packet after ordered PLAY bootstrap "
                            + "completion for " + player.getName());
        }
        return scheduled;
    }

    private Object invokeInitializer(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> arguments != null
                        && arguments.length == 1
                        && proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "ATM10LobbyRecipePacketInitializer";
                default -> throw new IllegalStateException(
                        "Unexpected Object method on Paper initializer: " + method);
            };
        }
        if (!method.getName().equals("afterInitChannel")
                || arguments == null
                || arguments.length != 1
                || !(arguments[0] instanceof Channel channel)) {
            throw new IllegalStateException("Unexpected Paper channel initializer method: " + method);
        }
        guardChannel(channel);
        return null;
    }

    private void guardChannel(Channel channel) {
        if (closed) {
            return;
        }
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(GUARD_HANDLER) != null) {
                return;
            }
            if (pipeline.context(MINECRAFT_PACKET_HANDLER) == null) {
                throw new IllegalStateException(
                        "Paper pipeline does not expose the expected packet_handler anchor");
            }
            pipeline.addBefore(
                    MINECRAFT_PACKET_HANDLER,
                    GUARD_HANDLER,
                    new RecipePacketSuppressor(
                            suppressRecipeBookPackets,
                            rewriteRecipeDefinitionPackets,
                            droppedRecipeBookPackets,
                            suppressedRecipeDefinitionPackets,
                            rewrittenRecipeDefinitionPackets,
                            emptyRecipeDefinitionsPacketFactory,
                            (channelWithAction, packetType) -> diagnosticLog.info(() ->
                                    actionMessage(packetType)
                                            + channelWithAction.remoteAddress())));
            guardedChannels.increment();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not install the recipe guard on a new Paper connection; closing it",
                    exception);
            channel.close();
        }
    }

    private static String actionMessage(RecipePacketSuppressor.RecipePacketType packetType) {
        return switch (packetType) {
            case RECIPE_BOOK ->
                    "Suppressed incompatible clientbound recipe-book packet for lobby connection ";
            case RECIPE_DEFINITIONS ->
                    "Deferred clientbound recipe definitions until ordered PLAY bootstraps "
                            + "complete for ";
            case NONE -> throw new IllegalArgumentException("cannot report a passthrough packet");
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            Object removed = removeListenerMethod.invoke(null, LISTENER_KEY);
            if (removed != null && removed != listenerProxy) {
                addListenerMethod.invoke(null, LISTENER_KEY, removed);
                plugin.getLogger().severe(
                        "Paper recipe initializer ownership changed; restored the foreign listener");
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not remove the Paper recipe packet initializer cleanly", exception);
        }
    }
}
