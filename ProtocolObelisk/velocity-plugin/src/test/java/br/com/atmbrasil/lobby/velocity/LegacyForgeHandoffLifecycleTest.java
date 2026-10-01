package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class LegacyForgeHandoffLifecycleTest {
    @Test
    void clearingFenceCancelsAttachmentClosesLeaseAndInvalidatesGeneration()
            throws ReflectiveOperationException {
        Class<?> sessionType = Class.forName(
                Atm10LobbyVelocityPlugin.class.getName() + "$BridgeSession");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(UUID.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(UUID.randomUUID());

        CompletableFuture<VelocityLegacyForgeHandoffGuard.Lease> pending =
                new CompletableFuture<>();
        Field gate = field(sessionType, "legacyForgeGuardGate");
        Field leaseField = field(sessionType, "legacyForgeGuardLease");
        Field endpoint = field(sessionType, "legacyForgeGuardEndpoint");
        Field generation = field(sessionType, "legacyForgeGuardGeneration");
        gate.set(session, pending);
        generation.setLong(session, 41L);
        AtomicBoolean closed = new AtomicBoolean();
        leaseField.set(session, new VelocityLegacyForgeHandoffGuard.Lease() {
            @Override
            public boolean active() {
                return !closed.get();
            }

            @Override
            public long suppressedPackets() {
                return 0L;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        });

        Method clear = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "clearLegacyForgeHandoffGuard", sessionType);
        clear.setAccessible(true);
        clear.invoke(null, session);

        assertTrue(pending.isCancelled());
        assertTrue(closed.get());
        assertNull(gate.get(session));
        assertNull(leaseField.get(session));
        assertNull(endpoint.get(session));
        assertEquals(42L, generation.getLong(session));
    }

    @Test
    void fenceSurvivesTransitionStartAndClosesOnlyAfterPostConnect() throws Exception {
        Path sourcePath = Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java");
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        int enterStart = source.indexOf("public void onEnterConfiguration(");
        int enteredStart = source.indexOf("public void onEnteredConfiguration(", enterStart);
        int postConnectStart = source.indexOf("public void onServerPostConnect(", enteredStart);
        int disconnectStart = source.indexOf("public void onDisconnect(", postConnectStart);

        assertTrue(enterStart >= 0 && enteredStart > enterStart);
        assertTrue(postConnectStart > enteredStart && disconnectStart > postConnectStart);
        String transitionStart = source.substring(enterStart, enteredStart);
        String postConnect = source.substring(postConnectStart, disconnectStart);
        assertFalse(transitionStart.contains("clearLegacyForgeHandoffGuard(session)"));
        assertTrue(postConnect.contains("clearLegacyForgeHandoffGuard(session)"));
        assertTrue(postConnect.contains("attachLegacyForgeHandoffGuard("));
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
