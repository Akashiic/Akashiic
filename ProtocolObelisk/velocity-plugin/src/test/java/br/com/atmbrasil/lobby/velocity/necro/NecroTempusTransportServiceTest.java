package br.com.atmbrasil.lobby.velocity.necro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent.LoginStatus;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerModInfoEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.ModInfo;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

final class NecroTempusTransportServiceTest {
    @Test
    void lifecycleRegistersExactlyThreeOwnedChannelsAndIsIdempotent() {
        RegistrarProbe registrarProbe = new RegistrarProbe(false);
        LoggerProbe loggerProbe = new LoggerProbe();
        NecroTempusTransportService service = service(registrarProbe, loggerProbe);

        service.initialize();
        service.initialize();

        assertTrue(service.initialized());
        assertEquals(1, registrarProbe.registerCalls.get());
        assertEquals(0, registrarProbe.unregisterCalls.get());
        assertEquals(
                List.of("necrotempus:main", "pobelisk:ntcap", "pobelisk:nttab"),
                registrarProbe.registeredIds);
        assertEquals(1, loggerProbe.infoCalls.get());
        NecroTempusTransportService.Metrics initialized = service.metrics();
        assertTrue(initialized.initialized());
        assertEquals(0, initialized.activeSessions());

        service.shutdown();
        service.shutdown();

        assertFalse(service.initialized());
        assertEquals(1, registrarProbe.unregisterCalls.get());
        assertEquals(registrarProbe.registeredIds, registrarProbe.unregisteredIds);
        assertFalse(service.metrics().initialized());
    }

    @Test
    void failedRegistrationRollsBackAndDoesNotActivateService() {
        RegistrarProbe registrarProbe = new RegistrarProbe(true);
        NecroTempusTransportService service = service(registrarProbe, new LoggerProbe());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::initialize);

        assertEquals(
                "Could not initialize the embedded NecroTempus transport",
                failure.getMessage());
        assertEquals("registration failed", failure.getCause().getMessage());
        assertFalse(service.initialized());
        assertEquals(1, registrarProbe.registerCalls.get());
        assertEquals(1, registrarProbe.unregisterCalls.get());
    }

    @Test
    void operationalLoggerFailureAfterRegistrationRollsBackBeforePublication() {
        RegistrarProbe registrarProbe = new RegistrarProbe(false);
        LoggerProbe loggerProbe = new LoggerProbe(true);
        NecroTempusTransportService service = service(registrarProbe, loggerProbe);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                service::initialize);

        assertEquals("logger failed", failure.getCause().getMessage());
        assertFalse(service.initialized());
        assertEquals(1, registrarProbe.registerCalls.get());
        assertEquals(1, registrarProbe.unregisterCalls.get());
        assertEquals(registrarProbe.registeredIds, registrarProbe.unregisteredIds);
    }

    @Test
    void serviceIsAnUnannotatedDelegateWithExplicitCompleteEventApi() throws Exception {
        assertTrue(Arrays.stream(NecroTempusTransportService.class.getAnnotations())
                .noneMatch(annotation -> annotation.annotationType().getName().equals(
                        "com.velocitypowered.api.plugin.Plugin")));
        for (Method method : NecroTempusTransportService.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Subscribe.class), method.getName());
        }

        assertEquals(
                void.class,
                method("initialize").getReturnType());
        assertEquals(
                void.class,
                method("shutdown").getReturnType());
        assertEquals(
                void.class,
                method("handlePlayerModInfo", PlayerModInfoEvent.class).getReturnType());
        assertEquals(
                void.class,
                method("handlePlayerChannelRegister", PlayerChannelRegisterEvent.class)
                        .getReturnType());
        assertEquals(
                boolean.class,
                method("handlePluginMessage", PluginMessageEvent.class).getReturnType());
        assertEquals(
                void.class,
                method("handleServerPreConnect", ServerPreConnectEvent.class).getReturnType());
        assertEquals(
                void.class,
                method("handleServerPostConnect", ServerPostConnectEvent.class).getReturnType());
        assertEquals(
                void.class,
                method("handleDisconnect", DisconnectEvent.class).getReturnType());
        assertThrows(
                NoSuchFieldException.class,
                () -> NecroTempusTransportService.class.getDeclaredField("dataDirectory"));
    }

    @Test
    void disabledDiagnosticsIsStableAndIndependentFromOperationalLogger() {
        NecroTempusDiagnostics first = NecroTempusDiagnostics.disabled();
        NecroTempusDiagnostics second = NecroTempusDiagnostics.disabled();
        assertSame(first, second);
        first.log("not emitted {}", 1);

        RegistrarProbe registrarProbe = new RegistrarProbe(false);
        LoggerProbe loggerProbe = new LoggerProbe();
        NecroTempusTransportService service = service(registrarProbe, loggerProbe);
        service.initialize();
        assertEquals(1, loggerProbe.infoCalls.get());
        assertEquals(0, loggerProbe.warnCalls.get());
    }

    @Test
    void disabledTransportDoesNotRegisterClaimOrMutateAnyChannel() {
        RegistrarProbe registrarProbe = new RegistrarProbe(false);
        LoggerProbe loggerProbe = new LoggerProbe();
        NecroTempusTransportConfig disabled = new NecroTempusTransportConfig(
                false, 30_000, 262_144, 30_000, 16_384, 64, 524_288);
        NecroTempusTransportService service = service(
                registrarProbe, loggerProbe, disabled);
        service.initialize();
        assertFalse(service.initialized());
        assertEquals(0, registrarProbe.registerCalls.get());
        assertEquals(0, registrarProbe.unregisterCalls.get());
        assertEquals(0, loggerProbe.infoCalls.get());
        ChannelMessageSource source = proxy(
                ChannelMessageSource.class, NecroTempusTransportServiceTest::defaultValue);
        ChannelMessageSink target = proxy(
                ChannelMessageSink.class, NecroTempusTransportServiceTest::defaultValue);

        List<ChannelIdentifier> owned = List.of(
                new LegacyChannelIdentifier("necrotempus:main"),
                MinecraftChannelIdentifier.from("pobelisk:ntcap"),
                new LegacyChannelIdentifier("pobelisk:nttab"));
        for (ChannelIdentifier identifier : owned) {
            PluginMessageEvent event = new PluginMessageEvent(
                    source, target, identifier, new byte[0]);
            assertFalse(service.handlePluginMessage(event), identifier.getId());
            assertTrue(event.getResult().isAllowed(), identifier.getId());
        }

        PluginMessageEvent unrelated = new PluginMessageEvent(
                source,
                target,
                new LegacyChannelIdentifier("unrelated"),
                new byte[0]);
        assertFalse(service.handlePluginMessage(unrelated));
        assertTrue(unrelated.getResult().isAllowed());
    }

    @Test
    void enabledTransportClaimsOnlyItsThreeOwnedChannels() {
        NecroTempusTransportService service = service(
                new RegistrarProbe(false), new LoggerProbe());
        service.initialize();
        ChannelMessageSource source = proxy(
                ChannelMessageSource.class, NecroTempusTransportServiceTest::defaultValue);
        ChannelMessageSink target = proxy(
                ChannelMessageSink.class, NecroTempusTransportServiceTest::defaultValue);

        for (ChannelIdentifier identifier : List.of(
                new LegacyChannelIdentifier("necrotempus:main"),
                MinecraftChannelIdentifier.from("pobelisk:ntcap"),
                new LegacyChannelIdentifier("pobelisk:nttab"))) {
            PluginMessageEvent event = new PluginMessageEvent(
                    source, target, identifier, new byte[0]);
            assertTrue(service.handlePluginMessage(event), identifier.getId());
            assertFalse(event.getResult().isAllowed(), identifier.getId());
        }

        PluginMessageEvent unrelated = new PluginMessageEvent(
                source,
                target,
                new LegacyChannelIdentifier("unrelated"),
                new byte[0]);
        assertFalse(service.handlePluginMessage(unrelated));
        assertTrue(unrelated.getResult().isAllowed());
    }

    @Test
    void disconnectSerializesWithConfirmationEffectsAndRejectsStaleConfirmation()
            throws Exception {
        RegistrarProbe registrarProbe = new RegistrarProbe(false);
        LoggerProbe loggerProbe = new LoggerProbe();
        CountDownLatch refinementEntered = new CountDownLatch(1);
        CountDownLatch releaseRefinement = new CountDownLatch(1);
        CountDownLatch disconnectStarted = new CountDownLatch(1);
        CountDownLatch disconnectFinished = new CountDownLatch(1);
        AtomicBoolean playerActive = new AtomicBoolean(true);
        AtomicInteger outboundEffects = new AtomicInteger();
        AtomicInteger staleEffects = new AtomicInteger();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<NecroTempusTransportService> serviceReference =
                new AtomicReference<>();

        NecroTempusDiagnostics diagnostics = (message, arguments) -> {
            if (!message.startsWith("Refined NecroTempus capability")) {
                return;
            }
            NecroTempusTransportService.Metrics metrics = serviceReference.get().metrics();
            if (metrics.activeSessions() != 1 || metrics.confirmedClients() != 1L) {
                staleEffects.incrementAndGet();
            }
            refinementEntered.countDown();
            await(releaseRefinement);
        };
        Player player = proxy(Player.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> UUID.fromString("a2dbeba5-97a3-4812-8bea-c31ab136db08");
            case "getUsername" -> "RaceProbe";
            case "getProtocolVersion" -> ProtocolVersion.MINECRAFT_1_7_6;
            case "isActive" -> playerActive.get();
            case "getModInfo", "getCurrentServer" -> Optional.empty();
            case "sendPluginMessage" -> {
                outboundEffects.incrementAndGet();
                NecroTempusTransportService.Metrics metrics = serviceReference.get().metrics();
                if (metrics.activeSessions() != 1 || metrics.confirmedClients() != 1L) {
                    staleEffects.incrementAndGet();
                }
                yield true;
            }
            default -> defaultValue(proxy, method, arguments);
        });
        NecroTempusTransportService service = service(
                registrarProbe,
                loggerProbe,
                NecroTempusTransportConfig.defaults(),
                diagnostics);
        serviceReference.set(service);
        service.initialize();

        service.handlePlayerChannelRegister(new PlayerChannelRegisterEvent(
                player,
                List.of(new LegacyChannelIdentifier("necrotempus:main"))));
        assertEquals(1, service.metrics().activeSessions());
        assertEquals(1L, service.metrics().confirmedClients());
        assertEquals(1, outboundEffects.get());
        assertEquals(0, staleEffects.get());

        Thread refinement = new Thread(() -> {
            try {
                service.handlePlayerModInfo(new PlayerModInfoEvent(
                        player,
                        new ModInfo("FML", List.of(new ModInfo.Mod("necrotempus", "1.0.4")))));
            } catch (Throwable failure) {
                asyncFailure.compareAndSet(null, failure);
            }
        }, "necro-confirm-race-probe");
        refinement.start();
        assertTrue(refinementEntered.await(5, TimeUnit.SECONDS));

        playerActive.set(false);
        Thread disconnect = new Thread(() -> {
            disconnectStarted.countDown();
            try {
                service.handleDisconnect(new DisconnectEvent(player, LoginStatus.SUCCESSFUL_LOGIN));
            } catch (Throwable failure) {
                asyncFailure.compareAndSet(null, failure);
            } finally {
                disconnectFinished.countDown();
            }
        }, "necro-disconnect-race-probe");
        disconnect.start();
        assertTrue(disconnectStarted.await(5, TimeUnit.SECONDS));
        assertFalse(disconnectFinished.await(100, TimeUnit.MILLISECONDS),
                "disconnect must wait for the active confirmation effect to linearize");

        releaseRefinement.countDown();
        assertTrue(disconnectFinished.await(5, TimeUnit.SECONDS));
        refinement.join(5_000L);
        disconnect.join(5_000L);
        assertFalse(refinement.isAlive());
        assertFalse(disconnect.isAlive());
        assertTrue(asyncFailure.get() == null, () -> "async failure: " + asyncFailure.get());
        assertEquals(0, staleEffects.get());
        assertEquals(0, service.metrics().activeSessions());
        assertEquals(0L, service.metrics().confirmedClients());

        int effectsAfterDisconnect = outboundEffects.get();
        service.handlePlayerModInfo(new PlayerModInfoEvent(
                player,
                new ModInfo("FML", List.of(new ModInfo.Mod("necrotempus", "1.0.4")))));
        assertEquals(effectsAfterDisconnect, outboundEffects.get());
        assertEquals(0, service.metrics().activeSessions());
        assertEquals(0L, service.metrics().confirmedClients());
    }

    private static Method method(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return NecroTempusTransportService.class.getDeclaredMethod(name, parameterTypes);
    }

    private static NecroTempusTransportService service(
            RegistrarProbe registrarProbe,
            LoggerProbe loggerProbe) {
        return service(
                registrarProbe, loggerProbe, NecroTempusTransportConfig.defaults());
    }

    private static NecroTempusTransportService service(
            RegistrarProbe registrarProbe,
            LoggerProbe loggerProbe,
            NecroTempusTransportConfig config) {
        return service(
                registrarProbe,
                loggerProbe,
                config,
                NecroTempusDiagnostics.disabled());
    }

    private static NecroTempusTransportService service(
            RegistrarProbe registrarProbe,
            LoggerProbe loggerProbe,
            NecroTempusTransportConfig config,
            NecroTempusDiagnostics diagnostics) {
        ChannelRegistrar registrar = proxy(ChannelRegistrar.class, registrarProbe);
        ProxyServer server = proxy(ProxyServer.class, (proxy, method, arguments) -> {
            if (method.getName().equals("getChannelRegistrar")) {
                return registrar;
            }
            return defaultValue(proxy, method, arguments);
        });
        Logger logger = proxy(Logger.class, loggerProbe);
        return new NecroTempusTransportService(
                server,
                logger,
                diagnostics,
                config);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrency probe");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrency probe interrupted", exception);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Object proxy, Method method, Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> arguments != null && arguments.length == 1
                        && proxy == arguments[0];
                default -> throw new AssertionError("unexpected Object method " + method);
            };
        }
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class RegistrarProbe implements InvocationHandler {
        private final boolean failRegistration;
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger unregisterCalls = new AtomicInteger();
        private List<String> registeredIds = List.of();
        private List<String> unregisteredIds = List.of();

        private RegistrarProbe(boolean failRegistration) {
            this.failRegistration = failRegistration;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("register")) {
                registerCalls.incrementAndGet();
                registeredIds = identifiers(arguments);
                if (failRegistration) {
                    throw new IllegalStateException("registration failed");
                }
                return null;
            }
            if (method.getName().equals("unregister")) {
                unregisterCalls.incrementAndGet();
                unregisteredIds = identifiers(arguments);
                return null;
            }
            return defaultValue(proxy, method, arguments);
        }

        private static List<String> identifiers(Object[] arguments) {
            ChannelIdentifier[] identifiers = (ChannelIdentifier[]) arguments[0];
            List<String> result = new ArrayList<>(identifiers.length);
            for (ChannelIdentifier identifier : identifiers) {
                result.add(identifier.getId());
            }
            return List.copyOf(result);
        }
    }

    private static final class LoggerProbe implements InvocationHandler {
        private final boolean failInfo;
        private final AtomicInteger infoCalls = new AtomicInteger();
        private final AtomicInteger warnCalls = new AtomicInteger();

        private LoggerProbe() {
            this(false);
        }

        private LoggerProbe(boolean failInfo) {
            this.failInfo = failInfo;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("info")) {
                infoCalls.incrementAndGet();
                if (failInfo) {
                    throw new IllegalStateException("logger failed");
                }
            } else if (method.getName().equals("warn")) {
                warnCalls.incrementAndGet();
            }
            return defaultValue(proxy, method, arguments);
        }
    }
}
