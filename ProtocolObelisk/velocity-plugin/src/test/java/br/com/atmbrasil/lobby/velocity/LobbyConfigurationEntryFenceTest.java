package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.configuration.PlayerEnteredConfigurationEvent;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class LobbyConfigurationEntryFenceTest {
    @Test
    void exactLobbyGenerationCanBeConsumedOnlyOnce() {
        var fence = new LobbyConfigurationEntryFence();

        fence.arm("lobby", 2L);

        assertTrue(fence.consume("lobby", 2L));
        assertFalse(fence.consume("lobby", 2L));
    }

    @Test
    void staleGenerationFailsClosedAndCannotBeReused() {
        var fence = new LobbyConfigurationEntryFence();

        fence.arm("lobby", 4L);

        assertFalse(fence.consume("lobby", 5L));
        assertFalse(fence.consume("lobby", 4L));
    }

    @Test
    void foreignTargetFailsClosedAndCannotReachTheNextCycle() {
        var fence = new LobbyConfigurationEntryFence();

        fence.arm("atm10-normal-1", 7L);

        assertFalse(fence.consume("lobby", 7L));
        assertFalse(fence.consume("atm10-normal-1", 7L));
    }

    @Test
    void newestArmReplacesThePreviousGeneration() {
        var fence = new LobbyConfigurationEntryFence();

        fence.arm("lobby", 8L);
        fence.arm("lobby", 9L);

        assertFalse(fence.consume("lobby", 8L));
        fence.arm("lobby", 9L);
        assertTrue(fence.consume("lobby", 9L));
    }

    @Test
    void invalidGenerationIsRejectedAndClearsAnyPriorCandidate() {
        var fence = new LobbyConfigurationEntryFence();
        fence.arm("lobby", 10L);

        assertThrows(IllegalArgumentException.class, () -> fence.arm("lobby", 0L));
        assertFalse(fence.consume("lobby", 10L));
    }

    @Test
    void configurationEntryHandlerIsSynchronousAndRunsAtMaximumPriority()
            throws Exception {
        Method method = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "onEnteredConfiguration", PlayerEnteredConfigurationEvent.class);
        Subscribe subscription = method.getAnnotation(Subscribe.class);

        assertNotNull(subscription);
        assertEquals(Short.MAX_VALUE, subscription.priority());
        assertFalse(subscription.async());
    }

    @Test
    void returnLifecycleArmsBeforeEntryAndQueriesBeforeLateConfigurationEvent()
            throws Exception {
        Path sourcePath = Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java");
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        int enterStart = source.indexOf("public void onEnterConfiguration(");
        int enteredStart = source.indexOf("public void onEnteredConfiguration(", enterStart);
        int lateConfigurationStart = source.indexOf(
                "public EventTask onPlayerConfiguration(", enteredStart);

        assertTrue(enterStart >= 0 && enteredStart > enterStart
                        && lateConfigurationStart > enteredStart,
                "the three configuration lifecycle handlers must remain ordered in source");
        String targetSelection = source.substring(enterStart, enteredStart);
        String safeEntry = source.substring(enteredStart, lateConfigurationStart);

        assertTrue(targetSelection.contains("lobbyConfigurationEntryFence.arm"));
        assertTrue(targetSelection.contains("lobbyConfigurationEntryFence.clear"));
        assertTrue(safeEntry.contains("lobbyConfigurationEntryFence.consume"));
        assertTrue(safeEntry.contains("sendNeoForgeQuery"));
        assertFalse(safeEntry.contains("serverName(event.server())"));
        assertFalse(safeEntry.contains("getCurrentServer"));
        assertFalse(safeEntry.contains("createConnectionRequest"));
    }
}
