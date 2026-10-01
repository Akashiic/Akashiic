package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class ConfigurationGateLifecycleTest {
    @Test
    void terminalSessionAbandonmentDoesNotResumeVelocityContinuations() throws Exception {
        Class<?> sessionType = Class.forName(
                "br.com.atmbrasil.lobby.velocity.Atm10LobbyVelocityPlugin$BridgeSession");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(UUID.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(UUID.randomUUID());

        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> registryTail = new CompletableFuture<>();
        set(sessionType, session, "gate", gate);
        set(sessionType, session, "registryTailGate", registryTail);
        setLong(sessionType, session, "lobbyCycleGeneration", 10L);
        setLong(sessionType, session, "frozenRegistryGeneration", 20L);
        setLong(sessionType, session, "playBootstrapGeneration", 30L);
        setLong(sessionType, session, "legacyForgeGuardGeneration", 40L);

        Method abandon = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "abandonConfigurationGates", sessionType);
        abandon.setAccessible(true);
        abandon.invoke(null, session);

        assertFalse(gate.isDone(), "retired gate must not resume Velocity");
        assertFalse(registryTail.isDone(), "retired registry tail must not resume Velocity");
        assertNull(get(sessionType, session, "gate"));
        assertNull(get(sessionType, session, "registryTailGate"));
        assertEquals(11L, getLong(sessionType, session, "lobbyCycleGeneration"));
        assertEquals(21L, getLong(sessionType, session, "frozenRegistryGeneration"));
        assertEquals(31L, getLong(sessionType, session, "playBootstrapGeneration"));
        assertEquals(41L, getLong(sessionType, session, "legacyForgeGuardGeneration"));
    }

    private static Object get(Class<?> type, Object instance, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static long getLong(Class<?> type, Object instance, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(instance);
    }

    private static void set(Class<?> type, Object instance, String name, Object value)
            throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static void setLong(Class<?> type, Object instance, String name, long value)
            throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(instance, value);
    }
}
