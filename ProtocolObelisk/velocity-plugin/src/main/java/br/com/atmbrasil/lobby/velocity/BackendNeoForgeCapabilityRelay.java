package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds one player's validated NeoForge capability advertisement across Velocity backend swaps.
 *
 * <p>NeoForge servers send their empty capability query immediately before a ping that classifies
 * a connection as vanilla when no query response has arrived. Velocity pauses backend reads while
 * firing the plugin-message event, so the proxy can forward the original query to the client and
 * relay the same client's already validated advertisement before allowing that ping to continue.
 * No server is selected here and no compatibility result is fabricated: the target server still
 * compares its own registrations against the client's exact advertisement.</p>
 *
 * <p>All methods are intentionally unsynchronized. The owning {@code BridgeSession} monitor is
 * the single lock used by the Velocity plugin.</p>
 */
final class BackendNeoForgeCapabilityRelay {
    private byte[] validatedClientAdvertisement;
    private String advertisementFingerprint;
    private boolean lobbyInitializationComplete;
    private String backendTarget;
    private long backendGeneration;
    private boolean responseRelayed;

    void beginLobbyCycle() {
        validatedClientAdvertisement = null;
        advertisementFingerprint = null;
        lobbyInitializationComplete = false;
        backendTarget = null;
        backendGeneration++;
        responseRelayed = false;
    }

    void captureValidatedAdvertisement(byte[] advertisement, String fingerprint) {
        Objects.requireNonNull(advertisement, "advertisement");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (advertisement.length == 0) {
            throw new IllegalArgumentException("advertisement must not be empty");
        }
        validatedClientAdvertisement = advertisement.clone();
        advertisementFingerprint = fingerprint;
        lobbyInitializationComplete = false;
    }

    boolean markLobbyInitializationComplete() {
        if (validatedClientAdvertisement == null || advertisementFingerprint == null) {
            return false;
        }
        lobbyInitializationComplete = true;
        return true;
    }

    void beginBackendTransition(String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        backendTarget = target;
        backendGeneration++;
        responseRelayed = false;
    }

    Optional<RelayPlan> plan(String target, byte[] serverQuery) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(serverQuery, "serverQuery");
        if (!lobbyInitializationComplete
                || validatedClientAdvertisement == null
                || responseRelayed
                || !target.equals(backendTarget)
                || !Arrays.equals(serverQuery, NeoForgeHandshakeCodec.queryRequest())) {
            return Optional.empty();
        }
        return Optional.of(new RelayPlan(
                backendGeneration,
                target,
                validatedClientAdvertisement,
                advertisementFingerprint));
    }

    boolean commit(RelayPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (responseRelayed
                || !lobbyInitializationComplete
                || plan.generation() != backendGeneration
                || !plan.target().equals(backendTarget)
                || !plan.fingerprint().equals(advertisementFingerprint)
                || !Arrays.equals(plan.clientAdvertisement(), validatedClientAdvertisement)) {
            return false;
        }
        responseRelayed = true;
        return true;
    }

    boolean shouldConsumeNativeResponse(String target) {
        return responseRelayed && target != null && target.equals(backendTarget);
    }

    record RelayPlan(
            long generation,
            String target,
            byte[] clientAdvertisement,
            String fingerprint) {
        RelayPlan {
            Objects.requireNonNull(target, "target");
            clientAdvertisement = Objects.requireNonNull(
                    clientAdvertisement, "clientAdvertisement").clone();
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        @Override
        public byte[] clientAdvertisement() {
            return clientAdvertisement.clone();
        }
    }
}
