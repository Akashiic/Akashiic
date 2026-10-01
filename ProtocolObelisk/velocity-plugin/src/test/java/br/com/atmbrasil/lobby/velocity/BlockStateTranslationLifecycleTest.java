package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BlockStateTranslationLifecycleTest {
    @Test
    void lobbyFirstPluginHasNoRuntimeControlStartupOrGraceWindow() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/br/com/atmbrasil/lobby/velocity/"
                        + "Atm10LobbyVelocityPlugin.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("initializeYouerAgentControl"));
        assertFalse(source.contains("YouerAgentControlServer"));
        assertFalse(source.contains("YouerAgentManifestRegistry"));
        assertFalse(source.contains("BLOCK_STATE_RUNTIME_GRACE"));
        assertFalse(source.contains("beginRuntimeBlockStateEvidenceGrace"));
        assertFalse(source.contains("pollRuntimeBlockStateEvidence"));
        assertFalse(source.contains("blockStateRuntimeGrace"));
        assertTrue(source.contains("fallback=IMMEDIATE_NO_CONTROL_WAIT"));
        assertTrue(source.contains("pluginInitiatedDisconnect=false"));
    }

    @Test
    void missingEmbeddedMapImmediatelyReturnsPassthroughDecision() throws Exception {
        Atm10LobbyVelocityPlugin plugin = new Atm10LobbyVelocityPlugin(
                null, null, Path.of("."));
        Object session = newSession();

        Object decision = select(plugin, session);
        assertEquals("NO_EXACT_EMBEDDED_EVIDENCE", invoke(decision, "status"));
        assertTrue(((Optional<?>) invoke(decision, "selection")).isEmpty());

        Class<?> sessionType = session.getClass();
        assertThrows(
                NoSuchFieldException.class,
                () -> sessionType.getDeclaredField("blockStateRuntimeGraceActive"));
    }

    @Test
    void exactEmbeddedMapRemainsEligibleWithoutRuntimeLease() throws Exception {
        Atm10LobbyVelocityPlugin plugin = new Atm10LobbyVelocityPlugin(
                null, null, Path.of("."));
        setField(
                plugin,
                "reviewedBlockStateProfiles",
                invoke(plugin, "loadBundledReviewedBlockStateProfiles"));
        setField(plugin, "lobbyPlayPacketTranslator", translatorSentinel());
        Object session = newSession();
        setField(
                session,
                "fullClientContractSha256",
                ReviewedClientContractEvidence.ATM10_NORMAL_8_0
                        .fullClientContractSha256());

        Object decision = select(plugin, session);
        assertEquals("EMBEDDED_EXACT_OFFLINE", invoke(decision, "status"));
        Object selection = ((Optional<?>) invoke(decision, "selection")).orElseThrow();
        assertEquals(
                "embedded-exact-atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                invoke(selection, "source"));
        assertThrows(
                NoSuchMethodException.class,
                () -> selection.getClass().getDeclaredMethod("evidenceLease"));
    }

    @Test
    void exactMapWithoutPacketAdapterImmediatelyReturnsPassthroughDecision()
            throws Exception {
        Atm10LobbyVelocityPlugin plugin = new Atm10LobbyVelocityPlugin(
                null, null, Path.of("."));
        setField(
                plugin,
                "reviewedBlockStateProfiles",
                invoke(plugin, "loadBundledReviewedBlockStateProfiles"));
        Object session = newSession();
        setField(
                session,
                "fullClientContractSha256",
                ReviewedClientContractEvidence.ATM10_NORMAL_8_0
                        .fullClientContractSha256());

        Object decision = select(plugin, session);

        assertEquals(
                "EXACT_EMBEDDED_TRANSLATOR_UNAVAILABLE",
                invoke(decision, "status"));
        assertTrue(((Optional<?>) invoke(decision, "selection")).isEmpty());
    }

    @Test
    void adapterExceptionAndNullAreQuarantinedWithoutEscaping() {
        AtomicInteger diagnostics = new AtomicInteger();
        AtomicReference<Throwable> lastFailure = new AtomicReference<>();

        assertNull(Atm10LobbyVelocityPlugin.resolveBlockStateTranslatorOrPassthrough(
                () -> {
                    throw new IllegalStateException("synthetic resolver incompatibility");
                },
                failure -> {
                    diagnostics.incrementAndGet();
                    lastFailure.set(failure);
                }));
        assertEquals("synthetic resolver incompatibility", lastFailure.get().getMessage());

        assertNull(Atm10LobbyVelocityPlugin.resolveBlockStateTranslatorOrPassthrough(
                () -> null,
                failure -> {
                    diagnostics.incrementAndGet();
                    lastFailure.set(failure);
                }));
        assertTrue(lastFailure.get().getMessage().contains("returned null"));
        assertEquals(2, diagnostics.get());

        assertNull(Atm10LobbyVelocityPlugin.resolveBlockStateTranslatorOrPassthrough(
                () -> {
                    throw new IllegalStateException("resolver failure");
                },
                failure -> {
                    throw new IllegalStateException("diagnostic failure");
                }));
    }

    @Test
    void clearingLobbyGenerationCancelsPendingAttachmentBeforeDroppingItsIdentity()
            throws ReflectiveOperationException {
        Object session = newSession();
        Class<?> sessionType = session.getClass();
        CompletableFuture<VelocityLobbyPlayPacketTranslator.Lease> pending =
                new CompletableFuture<>();
        Field gate = sessionType.getDeclaredField("blockStateTranslationGate");
        gate.setAccessible(true);
        gate.set(session, pending);

        Method clear = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "clearBlockStateTranslation", sessionType);
        clear.setAccessible(true);
        clear.invoke(null, session);

        assertTrue(pending.isCancelled());
        assertNull(gate.get(session));
    }

    private static Object newSession() throws ReflectiveOperationException {
        Class<?> sessionType = Class.forName(
                Atm10LobbyVelocityPlugin.class.getName() + "$BridgeSession");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(UUID.class);
        constructor.setAccessible(true);
        return constructor.newInstance(UUID.randomUUID());
    }

    private static VelocityLobbyPlayPacketTranslator translatorSentinel()
            throws ReflectiveOperationException {
        Constructor<VelocityLobbyPlayPacketTranslator> constructor =
                VelocityLobbyPlayPacketTranslator.class.getDeclaredConstructor(
                        Class.class,
                        Class.class,
                        Class.class,
                        Method.class,
                        Method.class,
                        Method.class,
                        Method.class,
                        Object.class,
                        Object.class,
                        int.class,
                        Set.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                Object.class,
                Object.class,
                Object.class,
                null,
                null,
                null,
                null,
                new Object(),
                new Object(),
                767,
                Set.of(9));
    }

    private static Object select(Atm10LobbyVelocityPlugin plugin, Object session)
            throws ReflectiveOperationException {
        Method select = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "selectBlockStateTranslation", session.getClass(), int.class);
        select.setAccessible(true);
        return select.invoke(plugin, session, 767);
    }

    private static Object invoke(Object target, String method)
            throws ReflectiveOperationException {
        Method declared = target.getClass().getDeclaredMethod(method);
        declared.setAccessible(true);
        return declared.invoke(target);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
