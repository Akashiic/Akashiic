package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DynamicRegistryInjectionFenceTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("e1f69e79-0432-33bb-ad07-2077994de007");

    @Test
    void configurationTailInjectionDoesNotRequirePlayServerVisibility() {
        Object session = new Object();
        Object profile = new Object();
        var fence = new DynamicRegistryInjectionFence<>(PLAYER_ID, session, 7L, profile);

        assertTrue(fence.permits(PLAYER_ID, session, 7L, profile, true));
    }

    @Test
    void staleSessionStateGenerationOrProfileFailsClosed() {
        Object session = new Object();
        Object profile = new Object();
        var fence = new DynamicRegistryInjectionFence<>(PLAYER_ID, session, 7L, profile);

        assertFalse(fence.permits(UUID.randomUUID(), session, 7L, profile, true));
        assertFalse(fence.permits(PLAYER_ID, new Object(), 7L, profile, true));
        assertFalse(fence.permits(PLAYER_ID, session, 8L, profile, true));
        assertFalse(fence.permits(PLAYER_ID, session, 7L, new Object(), true));
        assertFalse(fence.permits(PLAYER_ID, session, 7L, profile, false));
        assertFalse(fence.permits(PLAYER_ID, null, 7L, profile, true));
    }

    @Test
    void absentLegacyProfileIdentityRemainsAValidExactFence() {
        Object session = new Object();
        var fence = new DynamicRegistryInjectionFence<Object, Object>(
                PLAYER_ID, session, 7L, null);

        assertTrue(fence.permits(PLAYER_ID, session, 7L, null, true));
        assertFalse(fence.permits(PLAYER_ID, session, 7L, new Object(), true));
    }

    @Test
    void compiledFenceHasNoVelocityServerLookupDependency() throws Exception {
        String resourceName = "/" + DynamicRegistryInjectionFence.class.getName()
                .replace('.', '/') + ".class";
        try (var stream = DynamicRegistryInjectionFenceTest.class
                .getResourceAsStream(resourceName)) {
            assertTrue(stream != null, "compiled injection fence must be available");
            String symbols = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(symbols.contains("getCurrentServer"));
            assertFalse(symbols.contains("ServerConnection"));
            assertFalse(symbols.contains("RegisteredServer"));
        }
    }

    @Test
    void injectionLifecycleSourceNeverConsultsPlayOnlyServerVisibility() throws Exception {
        Path sourcePath = Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java");
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        int start = source.indexOf("private void injectDynamicRegistryShims(");
        int end = source.indexOf("private static List<Channel> configurationChannels", start);

        assertTrue(start >= 0 && end > start, "dynamic injection source slice must be found");
        String injectionLifecycle = source.substring(start, end);
        assertTrue(injectionLifecycle.contains("dynamicRegistryInjectionIsCurrent"));
        assertTrue(injectionLifecycle.contains("tagsInjector.inject(player, tagProfile)"));
        assertFalse(injectionLifecycle.contains("getCurrentServer"));
        assertFalse(injectionLifecycle.contains("ServerConnection"));
        assertFalse(injectionLifecycle.contains("RegisteredServer"));
    }

    @Test
    void dynamicExtensionsAreAppendedOnlyAfterPaperRegistryPackets() throws Exception {
        Path sourcePath = Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java");
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

        int acknowledgementStart = source.indexOf(
                "private void receiveFrozenRegistryAcknowledgement(");
        int acknowledgementEnd = source.indexOf(
                "private void awaitPaperRegistryPrefix(", acknowledgementStart);
        assertTrue(acknowledgementStart >= 0 && acknowledgementEnd > acknowledgementStart);
        String acknowledgement = source.substring(acknowledgementStart, acknowledgementEnd);
        assertTrue(acknowledgement.contains("awaitPaperRegistryPrefix"));
        assertFalse(acknowledgement.contains("injectDynamicRegistryShims"));

        int tailEventStart = source.indexOf(
                "public EventTask onPlayerFinishConfiguration(");
        int tailEventEnd = source.indexOf(
                "public void onPluginMessage(", tailEventStart);
        assertTrue(tailEventStart >= 0 && tailEventEnd > tailEventStart);
        String tailEvent = source.substring(tailEventStart, tailEventEnd);
        assertTrue(tailEvent.contains("PlayerFinishConfigurationEvent"));
        assertTrue(tailEvent.contains("LOBBY_WAITING_FOR_REGISTRY_TAIL"));
        assertTrue(tailEvent.contains("injectDynamicRegistryShims"));
        assertTrue(source.contains(
                "DYNAMIC_REGISTRY_INJECTION_TIMEOUT_MILLIS = 4_000L"));

        int injectionStart = source.indexOf("private void injectDynamicRegistryShims(");
        int registryBatch = source.indexOf(
                "injector.injectBatch(player, packets)",
                injectionStart);
        int tagWrite = source.indexOf("tagsInjector.inject(player, tagProfile)", registryBatch);
        int finish = source.indexOf(
                "finishNeoForgeLobbyHandshake(player, session, currentConfig, packets)",
                tagWrite);
        assertTrue(registryBatch >= 0 && tagWrite > registryBatch && finish > tagWrite,
                "registry entries, registry tags, and handshake completion must remain ordered");
        assertTrue(source.contains("registryFlushes={}"));
    }
}
