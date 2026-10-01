package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

final class BackendNeoForgeCapabilityRelayTest {
    @Test
    void relayRequiresAValidatedFullyInitializedLobbySession() {
        BackendNeoForgeCapabilityRelay relay = new BackendNeoForgeCapabilityRelay();
        relay.beginLobbyCycle();
        relay.beginBackendTransition("atm10-sky-1");

        assertTrue(relay.plan(
                "atm10-sky-1", NeoForgeHandshakeCodec.queryRequest()).isEmpty());

        relay.captureValidatedAdvertisement(new byte[] {1, 2, 3}, "fingerprint");
        relay.beginBackendTransition("atm10-sky-1");
        assertTrue(relay.plan(
                "atm10-sky-1", NeoForgeHandshakeCodec.queryRequest()).isEmpty());

        relay.markLobbyInitializationComplete();
        assertTrue(relay.plan(
                "atm10-sky-1", NeoForgeHandshakeCodec.queryRequest()).isPresent());
    }

    @Test
    void exactQueryGetsOneDefensiveOneShotRelayPerBackendGeneration() {
        BackendNeoForgeCapabilityRelay relay = readyRelay();
        byte[] original = {10, 20, 30};
        relay.beginLobbyCycle();
        relay.captureValidatedAdvertisement(original, "abc123");
        relay.markLobbyInitializationComplete();
        relay.beginBackendTransition("atm10-sky-1");
        original[0] = 99;

        BackendNeoForgeCapabilityRelay.RelayPlan plan = relay.plan(
                        "atm10-sky-1", NeoForgeHandshakeCodec.queryRequest())
                .orElseThrow();
        byte[] exposed = plan.clientAdvertisement();
        exposed[1] = 88;

        assertArrayEquals(new byte[] {10, 20, 30}, plan.clientAdvertisement());
        assertTrue(relay.commit(plan));
        assertTrue(relay.shouldConsumeNativeResponse("atm10-sky-1"));
        assertFalse(relay.shouldConsumeNativeResponse("another-server"));
        assertTrue(relay.plan(
                "atm10-sky-1", NeoForgeHandshakeCodec.queryRequest()).isEmpty());
    }

    @Test
    void nonEmptyOrWrongTargetQueryIsLeftToVelocityAndTheClient() {
        BackendNeoForgeCapabilityRelay relay = readyRelay();
        relay.beginBackendTransition("atm10-sky-1");

        assertTrue(relay.plan("atm10-sky-1", new byte[] {1}).isEmpty());
        assertTrue(relay.plan(
                "atm10-normal-1", NeoForgeHandshakeCodec.queryRequest()).isEmpty());
        assertFalse(relay.shouldConsumeNativeResponse("atm10-sky-1"));
    }

    @Test
    void nextTransitionInvalidatesStalePlanAndLobbyReturnPurgesCapabilities() {
        BackendNeoForgeCapabilityRelay relay = readyRelay();
        relay.beginBackendTransition("atm10-sky-1");
        BackendNeoForgeCapabilityRelay.RelayPlan stale = relay.plan(
                        "atm10-sky-1", NeoForgeHandshakeCodec.queryRequest())
                .orElseThrow();

        relay.beginBackendTransition("atm10-normal-1");
        assertFalse(relay.commit(stale));
        assertTrue(relay.plan(
                "atm10-normal-1", NeoForgeHandshakeCodec.queryRequest()).isPresent());

        relay.beginLobbyCycle();
        relay.beginBackendTransition("atm10-normal-1");
        assertTrue(relay.plan(
                "atm10-normal-1", NeoForgeHandshakeCodec.queryRequest()).isEmpty());
    }

    @Test
    void exactAtm10Normal80AdvertisementIsRelayedByteForByte() throws Exception {
        byte[] advertisement;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "atm10-normal/8.0/client-neoforge-response.bin")) {
            if (stream == null) {
                throw new IllegalStateException("missing ATM10 Normal 8.0 advertisement fixture");
            }
            advertisement = stream.readAllBytes();
        }
        BackendNeoForgeCapabilityRelay relay = new BackendNeoForgeCapabilityRelay();
        relay.beginLobbyCycle();
        relay.captureValidatedAdvertisement(
                advertisement, SilentGearProtocol.sha256(advertisement));
        assertTrue(relay.markLobbyInitializationComplete());
        relay.beginBackendTransition("atm10-normal-1");

        BackendNeoForgeCapabilityRelay.RelayPlan plan = relay.plan(
                        "atm10-normal-1", NeoForgeHandshakeCodec.queryRequest())
                .orElseThrow();

        assertArrayEquals(advertisement, plan.clientAdvertisement());
        assertTrue(relay.commit(plan));
        assertTrue(relay.shouldConsumeNativeResponse("atm10-normal-1"));
    }

    private static BackendNeoForgeCapabilityRelay readyRelay() {
        BackendNeoForgeCapabilityRelay relay = new BackendNeoForgeCapabilityRelay();
        relay.beginLobbyCycle();
        relay.captureValidatedAdvertisement(new byte[] {4, 5, 6}, "fingerprint");
        relay.markLobbyInitializationComplete();
        return relay;
    }
}
