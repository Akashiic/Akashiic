package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProxyPluginMessageOwnershipTest {
    private static final String TRANSPORT = ProxyPluginMessageOwnership.BUNGEE_CHANNEL_ID;
    private static final Channel ORDINARY =
            new Channel("example:ordinary_action", "1", Flow.SERVERBOUND, false);

    @TempDir
    Path temporaryDirectory;

    @Test
    void forgedTransportAdvertisementCannotTakeAnAutomaticOrPinnedSinkSlot() {
        for (Flow flow : Flow.values()) {
            for (boolean optional : List.of(false, true)) {
                Registry registry = new Registry(Map.of(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        List.of(new Channel(TRANSPORT, "1", flow, optional), ORDINARY)));
                ChannelContractSignature.Signatures raw = ChannelContractSignature.from(registry);
                PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                        767, registry, List.of(new PinnedPlayChannel(TRANSPORT, "1")),
                        1, false, false, false, Set.of());

                assertEquals(PlaySinkPlanner.Mode.PARSED, plan.mode());
                assertEquals(List.of(ORDINARY), plan.channels());
                assertEquals(1, plan.eligibleChannelCount());
                assertEquals(0, plan.omittedAutomaticChannelCount());
                assertTrue(plan.clientboundBootstrapChannels().isEmpty());
                assertEquals(raw, ChannelContractSignature.from(registry));
            }
        }
    }

    @Test
    void unknownClientWithOnlyTransportStillHasAValidEmptySinkPlan() {
        Registry registry = new Registry(Map.of(
                NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                List.of(new Channel(TRANSPORT, "unexpected-version", Flow.BIDIRECTIONAL, false))));
        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                767, registry, List.of(), 30, false, false, false, Set.of());

        assertEquals(PlaySinkPlanner.Mode.PARSED, plan.mode());
        assertTrue(plan.channels().isEmpty());
        assertTrue(plan.clientboundBootstrapChannels().isEmpty());
        assertEquals(0, plan.eligibleChannelCount());
        assertEquals(0, plan.omittedAutomaticChannelCount());
    }

    @Test
    void staleSessionCannotAuthorizeTransportConsumptionForEitherOrigin() {
        Set<String> staleSession = Set.of(TRANSPORT, ORDINARY.id());

        assertFalse(LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                true, staleSession, TRANSPORT));
        assertFalse(LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                false, staleSession, TRANSPORT));
        assertTrue(LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                true, staleSession, ORDINARY.id()));
        assertFalse(LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                false, staleSession, ORDINARY.id()));
    }

    @Test
    void staleOperatorPinIsOmittedWithoutDisablingBridgeOrOverwritingConfiguration()
            throws Exception {
        BridgeConfig baseline = BridgeConfig.load(temporaryDirectory);
        Path configPath = temporaryDirectory.resolve("bridge.properties");
        Files.writeString(configPath,
                "\nenabled=true\npinned-play-sink-channels=bungeecord:main@1,example:ordinary_action@1\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(configPath);

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertTrue(loaded.enabled());
        assertFalse(loaded.pinnedPlaySinkChannels().stream()
                .anyMatch(channel -> channel.id().equals(TRANSPORT)));
        assertTrue(loaded.pinnedPlaySinkChannels().contains(
                new PinnedPlayChannel(ORDINARY.id(), ORDINARY.version())));
        assertEquals(baseline.pinnedPlaySinkChannels().size() + 1,
                loaded.pinnedPlaySinkChannels().size());
        assertArrayEquals(before, Files.readAllBytes(configPath));
    }

    @Test
    void reservationFenceProtectsModernTransportAndLegacyAlias() throws Exception {
        assertThrows(ProtocolViolationException.class,
                () -> ProxyPluginMessageOwnership.requireProtocolObeliskSinkOwnership(TRANSPORT));
        ProxyPluginMessageOwnership.requireProtocolObeliskSinkOwnership(ORDINARY.id());
        assertFalse(ProxyPluginMessageOwnership.externallyOwns("bungeecord:other"));
        assertTrue(ProxyPluginMessageOwnership.externallyOwns("BungeeCord"));
        assertTrue(ProxyPluginMessageOwnership.externallyOwns("bungeecord"));
        assertEquals(Set.of(TRANSPORT), ProxyPluginMessageOwnership.channelIds());
    }
}
