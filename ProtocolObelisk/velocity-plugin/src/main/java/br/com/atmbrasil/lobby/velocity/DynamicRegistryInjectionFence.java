package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity fence for one dynamic-registry injection attempt.
 *
 * <p>The initial lobby is still Velocity's in-flight connection while the finish-configuration
 * event is held for the reviewed registry tail. Consequently this fence deliberately uses only
 * identities owned by the armed lobby cycle; {@code Player#getCurrentServer()} is not a valid
 * liveness signal at this stage.</p>
 */
record DynamicRegistryInjectionFence<S, P>(
        UUID playerId,
        S sessionIdentity,
        long lobbyCycleGeneration,
        P profileIdentity) {
    DynamicRegistryInjectionFence {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sessionIdentity, "sessionIdentity");
    }

    boolean permits(
            UUID currentPlayerId,
            S currentSession,
            long currentLobbyCycleGeneration,
            P currentProfile,
            boolean injectingState) {
        return injectingState
                && playerId.equals(currentPlayerId)
                && sessionIdentity == currentSession
                && lobbyCycleGeneration == currentLobbyCycleGeneration
                && profileIdentity == currentProfile;
    }
}
