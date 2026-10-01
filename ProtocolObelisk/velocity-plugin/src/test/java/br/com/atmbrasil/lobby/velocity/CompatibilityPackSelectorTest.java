package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CompatibilityPackSelectorTest {
    private static final int PROTOCOL = 767;

    @Test
    void clientWithTheServersChannelsNegotiatesThePack() throws Exception {
        CompatibilityPack pack = pack("atm-a");
        CompatibilityPackSelector.Selection selection = CompatibilityPackSelector.select(
                PROTOCOL, client(serverConfiguration(), serverPlay()), List.of(pack), "");

        assertEquals(CompatibilityPackSelector.Status.NEGOTIATED, selection.status());
        assertEquals("atm-a", selection.pack().orElseThrow().packId());
    }

    @Test
    void optionalChannelsOnEitherSideNeverBlockNegotiation() throws Exception {
        List<Channel> play = new ArrayList<>(serverPlay());
        play.removeIf(channel -> channel.id().equals("testmod:optional_hud"));
        play.add(new Channel("voicechat:secret", "18", Flow.BIDIRECTIONAL, true));
        List<Channel> configuration = new ArrayList<>(serverConfiguration());
        configuration.removeIf(channel -> channel.optional());

        CompatibilityPackSelector.Selection selection = CompatibilityPackSelector.select(
                PROTOCOL, client(configuration, play), List.of(pack("atm-a")), "");
        assertEquals(CompatibilityPackSelector.Status.NEGOTIATED, selection.status());
    }

    @Test
    void aChangedChannelVersionMeansADifferentModpackRelease() throws Exception {
        List<Channel> play = replace(serverPlay(), new Channel("testmod:keys", "4", Flow.SERVERBOUND, false));

        CompatibilityPackSelector.Selection selection = CompatibilityPackSelector.select(
                PROTOCOL, client(serverConfiguration(), play), List.of(pack("atm-a")), "");
        assertEquals(CompatibilityPackSelector.Status.NO_COMPATIBLE_PACK, selection.status());
        CompatibilityPackSelector.Evaluation closest = selection.closestMismatch().orElseThrow();
        assertEquals(1, closest.mismatchCount());
        assertTrue(closest.mismatches().getFirst().contains("testmod:keys"));
        assertTrue(closest.mismatches().getFirst().contains("'4'"));
    }

    @Test
    void missingRequiredChannelsAndFlowMismatchesFailLikeNeoForge() throws Exception {
        List<Channel> play = new ArrayList<>(serverPlay());
        play.removeIf(channel -> channel.id().equals("testmod:sync"));
        play = replace(play, new Channel("testmod:keys", "3", Flow.CLIENTBOUND, false));
        play.add(new Channel("clientonly:required", "1", Flow.SERVERBOUND, false));

        CompatibilityPackSelector.Evaluation evaluation = CompatibilityPackSelector.evaluate(
                PROTOCOL, client(serverConfiguration(), play), pack("atm-a"));
        assertFalse(evaluation.compatible());
        assertEquals(3, evaluation.mismatchCount());
    }

    @Test
    void aDifferentMinecraftProtocolNeverSelectsAPack() throws Exception {
        CompatibilityPackSelector.Selection selection = CompatibilityPackSelector.select(
                766, client(serverConfiguration(), serverPlay()), List.of(pack("atm-a")), "atm-a");
        assertEquals(CompatibilityPackSelector.Status.NO_COMPATIBLE_PACK, selection.status());
        assertTrue(selection.pack().isEmpty());
    }

    @Test
    void identicalCandidatesAreAmbiguousAndSelectNothing() throws Exception {
        CompatibilityPackSelector.Selection selection = CompatibilityPackSelector.select(
                PROTOCOL, client(serverConfiguration(), serverPlay()),
                List.of(pack("atm-a"), pack("atm-b")), "");
        assertEquals(CompatibilityPackSelector.Status.AMBIGUOUS, selection.status());
        assertTrue(selection.pack().isEmpty());
    }

    @Test
    void operatorMayForceAPackOnlyWhenNothingNegotiates() throws Exception {
        List<Channel> play = replace(serverPlay(), new Channel("testmod:keys", "9", Flow.SERVERBOUND, false));
        CompatibilityPackSelector.Selection forced = CompatibilityPackSelector.select(
                PROTOCOL, client(serverConfiguration(), play), List.of(pack("atm-a")), "atm-a");
        assertEquals(CompatibilityPackSelector.Status.FORCED_BY_OPERATOR, forced.status());
        assertEquals("atm-a", forced.pack().orElseThrow().packId());

        CompatibilityPackSelector.Selection unknown = CompatibilityPackSelector.select(
                PROTOCOL, client(serverConfiguration(), play), List.of(pack("atm-a")), "missing");
        assertEquals(CompatibilityPackSelector.Status.NO_COMPATIBLE_PACK, unknown.status());
    }

    @Test
    void noInstalledPacksIsReportedDistinctly() {
        CompatibilityPackSelector.Selection selection = CompatibilityPackSelector.select(
                PROTOCOL, client(serverConfiguration(), serverPlay()), List.of(), "");
        assertEquals(CompatibilityPackSelector.Status.NO_PACKS_INSTALLED, selection.status());
    }

    @Test
    void realAtm10ClientQueriesDoNotMatchAnUnrelatedPack() throws Exception {
        for (String fixture : List.of(
                "atm10-normal/7.3/client-neoforge-response.bin",
                "atm10-normal/8.0/client-neoforge-response.bin")) {
            Registry realClient = NeoForgeHandshakeCodec.decodeLobbyQuery(
                    CompatibilityPackFixtures.resource(fixture),
                    new br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits(
                            1_048_576, 1_048_576, 2, 16_384, 16_384, 1_024, 1_024));
            CompatibilityPackSelector.Selection selection = CompatibilityPackSelector.select(
                    PROTOCOL, realClient, List.of(pack("atm-a")), "");
            assertEquals(CompatibilityPackSelector.Status.NO_COMPATIBLE_PACK, selection.status(), fixture);
            assertTrue(selection.closestMismatch().orElseThrow().mismatchCount() > 0);
        }
    }

    private static CompatibilityPack pack(String packId) throws Exception {
        return CompatibilityPack.load(packId + ".obpack", CompatibilityPackFixtures.pack(packId));
    }

    private static List<Channel> serverConfiguration() {
        return CompatibilityPackFixtures.SERVER_CONFIGURATION;
    }

    private static List<Channel> serverPlay() {
        return CompatibilityPackFixtures.SERVER_PLAY;
    }

    private static List<Channel> replace(List<Channel> channels, Channel replacement) {
        List<Channel> result = new ArrayList<>(channels);
        result.replaceAll(channel -> channel.id().equals(replacement.id()) ? replacement : channel);
        return result;
    }

    private static Registry client(List<Channel> configuration, List<Channel> play) {
        return new Registry(Map.of(
                NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL, configuration,
                NeoForgeHandshakeCodec.PLAY_PROTOCOL, play));
    }
}
