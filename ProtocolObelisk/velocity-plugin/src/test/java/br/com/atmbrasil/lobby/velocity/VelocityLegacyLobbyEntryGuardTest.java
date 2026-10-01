package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.EventLoop;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class VelocityLegacyLobbyEntryGuardTest {
    private static final LegacyLobbyRegisterSanitizer.Settings SETTINGS =
            new LegacyLobbyRegisterSanitizer.Settings(16, Set.of("necrotempus:main"));
    private static final LegacyLobbyRegisterGuardHandler.Listener NOOP_LISTENER =
            new LegacyLobbyRegisterGuardHandler.Listener() {
                @Override
                public void rewritten(
                        int originalChannelCount,
                        List<String> retainedChannels,
                        long sequence) {
                }

                @Override
                public void dropped(int originalChannelCount, String reason, long sequence) {
                }

                @Override
                public void failed(Throwable failure) {
                }
            };

    @Test
    void timeoutClaimsQueuedAttachmentAndPreventsLatePipelineMutation() throws Exception {
        QueuedChannel queuedChannel = new QueuedChannel();
        Player player = player(5, serverConnection(queuedChannel.channel()));
        VelocityLegacyLobbyEntryGuard guard = adapter();

        CompletableFuture<VelocityLegacyLobbyEntryGuard.Attachment> attachment = guard.attach(
                player, "lobby", SETTINGS, NOOP_LISTENER, 25L);

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> attachment.get(2L, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TimeoutException);
        assertEquals(0, queuedChannel.pipelineAccesses());

        queuedChannel.runQueuedInstallation();

        assertEquals(0, queuedChannel.pipelineAccesses());
        assertTrue(attachment.isCompletedExceptionally());
    }

    @Test
    void cancellationAlsoPreventsLatePipelineMutation() throws Exception {
        QueuedChannel queuedChannel = new QueuedChannel();
        Player player = player(5, serverConnection(queuedChannel.channel()));
        VelocityLegacyLobbyEntryGuard guard = adapter();

        CompletableFuture<VelocityLegacyLobbyEntryGuard.Attachment> attachment = guard.attach(
                player, "lobby", SETTINGS, NOOP_LISTENER, 5_000L);
        assertTrue(attachment.cancel(false));

        queuedChannel.runQueuedInstallation();

        assertTrue(attachment.isCancelled());
        assertEquals(0, queuedChannel.pipelineAccesses());
    }

    @Test
    void externalExceptionalCompletionAlsoPreventsLatePipelineMutation() throws Exception {
        QueuedChannel queuedChannel = new QueuedChannel();
        Player player = player(5, serverConnection(queuedChannel.channel()));
        VelocityLegacyLobbyEntryGuard guard = adapter();

        CompletableFuture<VelocityLegacyLobbyEntryGuard.Attachment> attachment = guard.attach(
                player, "lobby", SETTINGS, NOOP_LISTENER, 5_000L);
        assertTrue(attachment.completeExceptionally(
                new IllegalStateException("simulated owner shutdown")));

        queuedChannel.runQueuedInstallation();

        assertTrue(attachment.isCompletedExceptionally());
        assertEquals(0, queuedChannel.pipelineAccesses());
    }

    @Test
    void adapterRejectsEveryProtocolExceptFiveBeforeEventLoopAccess() throws Exception {
        QueuedChannel queuedChannel = new QueuedChannel();
        Player player = player(767, serverConnection(queuedChannel.channel()));
        VelocityLegacyLobbyEntryGuard guard = adapter();

        CompletableFuture<VelocityLegacyLobbyEntryGuard.Attachment> attachment = guard.attach(
                player, "lobby", SETTINGS, NOOP_LISTENER, 5_000L);

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> attachment.get(2L, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertTrue(failure.getCause().getMessage().contains("protocol 5"));
        assertFalse(queuedChannel.hasQueuedInstallation());
        assertEquals(0, queuedChannel.pipelineAccesses());
    }

    private static VelocityLegacyLobbyEntryGuard adapter() throws Exception {
        Constructor<VelocityLegacyLobbyEntryGuard> constructor =
                VelocityLegacyLobbyEntryGuard.class.getDeclaredConstructor(
                        Class.class,
                        Class.class,
                        Class.class,
                        Class.class,
                        Class.class,
                        Method.class,
                        Method.class,
                        Method.class,
                        Method.class,
                        Method.class,
                        Constructor.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                TestConnectedPlayer.class,
                TestVelocityServerConnection.class,
                TestMinecraftConnection.class,
                TestPluginMessage.class,
                ChannelOutboundHandler.class,
                TestConnectedPlayer.class.getMethod("getConnectionInFlight"),
                TestVelocityServerConnection.class.getMethod("getConnection"),
                TestMinecraftConnection.class.getMethod("getChannel"),
                TestPluginMessage.class.getMethod("getChannel"),
                TestPluginMessage.class.getMethod("content"),
                TestPluginMessage.class.getConstructor(String.class, ByteBuf.class));
    }

    private static Player player(int protocol, ServerConnection serverConnection) {
        return TestConnectedPlayer.class.cast(Proxy.newProxyInstance(
                VelocityLegacyLobbyEntryGuardTest.class.getClassLoader(),
                new Class<?>[] {TestConnectedPlayer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getConnectionInFlight" -> serverConnection;
                    case "getProtocolVersion" -> ProtocolVersion.getProtocolVersion(protocol);
                    default -> objectOrDefault(proxy, method, arguments);
                }));
    }

    private static ServerConnection serverConnection(Channel channel) {
        TestMinecraftConnection minecraftConnection = new TestMinecraftConnection(channel);
        ServerInfo serverInfo = new ServerInfo(
                "lobby",
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 25_565));
        return TestVelocityServerConnection.class.cast(Proxy.newProxyInstance(
                VelocityLegacyLobbyEntryGuardTest.class.getClassLoader(),
                new Class<?>[] {TestVelocityServerConnection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getConnection" -> minecraftConnection;
                    case "getServerInfo" -> serverInfo;
                    default -> objectOrDefault(proxy, method, arguments);
                }));
    }

    private static Object objectOrDefault(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "legacy-entry-test-proxy";
            default -> primitiveDefault(method.getReturnType());
        };
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }

    public interface TestConnectedPlayer extends Player {
        ServerConnection getConnectionInFlight();
    }

    public interface TestVelocityServerConnection extends ServerConnection {
        TestMinecraftConnection getConnection();
    }

    public static final class TestMinecraftConnection {
        private final Channel channel;

        public TestMinecraftConnection(Channel channel) {
            this.channel = channel;
        }

        public Channel getChannel() {
            return channel;
        }
    }

    public static final class TestPluginMessage {
        private final String channel;
        private final ByteBuf content;

        public TestPluginMessage(String channel, ByteBuf content) {
            this.channel = channel;
            this.content = content;
        }

        public String getChannel() {
            return channel;
        }

        public ByteBuf content() {
            return content;
        }
    }

    private static final class QueuedChannel {
        private final AtomicReference<Runnable> queued = new AtomicReference<>();
        private final AtomicInteger pipelineAccesses = new AtomicInteger();
        private final EventLoop eventLoop = EventLoop.class.cast(Proxy.newProxyInstance(
                VelocityLegacyLobbyEntryGuardTest.class.getClassLoader(),
                new Class<?>[] {EventLoop.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "inEventLoop" -> false;
                    case "execute" -> {
                        Runnable task = (Runnable) arguments[0];
                        if (!queued.compareAndSet(null, task)) {
                            throw new IllegalStateException("duplicate event-loop task");
                        }
                        yield null;
                    }
                    default -> objectOrDefault(proxy, method, arguments);
                }));
        private final Channel channel = Channel.class.cast(Proxy.newProxyInstance(
                VelocityLegacyLobbyEntryGuardTest.class.getClassLoader(),
                new Class<?>[] {Channel.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "eventLoop" -> eventLoop;
                    case "pipeline" -> {
                        pipelineAccesses.incrementAndGet();
                        yield null;
                    }
                    case "isActive" -> true;
                    default -> objectOrDefault(proxy, method, arguments);
                }));

        Channel channel() {
            return channel;
        }

        int pipelineAccesses() {
            return pipelineAccesses.get();
        }

        boolean hasQueuedInstallation() {
            return queued.get() != null;
        }

        void runQueuedInstallation() {
            Runnable installation = queued.getAndSet(null);
            if (installation == null) {
                throw new AssertionError("attachment did not reach the event-loop queue");
            }
            installation.run();
        }
    }
}
