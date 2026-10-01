package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;

/**
 * One-shot proof that Velocity selected the lobby for a particular reconfiguration generation.
 *
 * <p>The owner accesses this object while holding its bridge-session monitor. Consumption always
 * clears the candidate, including on mismatch, so an unexpected or stale configuration-entry
 * event cannot be reused by a later cycle.</p>
 */
final class LobbyConfigurationEntryFence {
    private String targetServer;
    private long lobbyCycleGeneration;

    void arm(String targetServer, long lobbyCycleGeneration) {
        this.targetServer = Objects.requireNonNull(targetServer, "targetServer");
        if (lobbyCycleGeneration <= 0L) {
            clear();
            throw new IllegalArgumentException("lobbyCycleGeneration must be positive");
        }
        this.lobbyCycleGeneration = lobbyCycleGeneration;
    }

    boolean consume(String expectedLobbyServer, long expectedLobbyCycleGeneration) {
        Objects.requireNonNull(expectedLobbyServer, "expectedLobbyServer");
        boolean accepted = targetServer != null
                && targetServer.equals(expectedLobbyServer)
                && lobbyCycleGeneration == expectedLobbyCycleGeneration;
        clear();
        return accepted;
    }

    void clear() {
        targetServer = null;
        lobbyCycleGeneration = 0L;
    }
}
