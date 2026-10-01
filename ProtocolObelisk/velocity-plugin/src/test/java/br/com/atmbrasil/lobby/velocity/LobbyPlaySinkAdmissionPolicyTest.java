package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class LobbyPlaySinkAdmissionPolicyTest {
    @Test
    void unknownContractRetainsTheSameBoundedAutomaticBudget()
            throws Exception {
        List<Channel> channels = new ArrayList<>(
                SilentGearProtocol.ATM10_TTS_4_1_3.channels());
        for (int index = 0; index < 31; index++) {
            channels.add(new Channel(
                    "unreviewed:channel_" + index, "1", Flow.SERVERBOUND, true));
        }
        Registry unreviewed = new Registry(Map.of(
                NeoForgeHandshakeCodec.PLAY_PROTOCOL, List.copyOf(channels)));
        ChannelContractSignature.Signatures unreviewedSignatures =
                ChannelContractSignature.from(unreviewed);
        SilentGearProfileCatalog profiles = SilentGearProfileCatalog.loadReviewed(
                getClass().getClassLoader(), 767, 1_048_576, 3_145_728);

        assertTrue(profiles.select(767, unreviewed, unreviewedSignatures).isPresent());
        assertFalse(ReviewedClientContractEvidence.allowsAutomaticPlaySinks(
                767, unreviewed, unreviewedSignatures));

        int adaptiveBudget = LobbyPlaySinkAdmissionPolicy.automaticBudget(
                ReviewedClientContractEvidence.allowsAutomaticPlaySinks(
                        767, unreviewed, unreviewedSignatures),
                30);
        PlaySinkPlanner.Plan adaptive = PlaySinkPlanner.plan(
                767,
                unreviewed,
                List.of(),
                adaptiveBudget,
                false,
                false,
                false,
                Set.of());
        assertEquals(30, adaptiveBudget);
        assertEquals(30, adaptive.channels().size());
        long eligibleChannels = channels.stream()
                .filter(channel -> channel.flow() != Flow.CLIENTBOUND)
                .count();
        assertEquals(eligibleChannels - 30, adaptive.omittedAutomaticChannelCount());

        Registry reviewedNormal80 = normal80Registry();
        ChannelContractSignature.Signatures reviewedSignatures =
                ChannelContractSignature.from(reviewedNormal80);
        assertTrue(ReviewedClientContractEvidence.allowsAutomaticPlaySinks(
                767, reviewedNormal80, reviewedSignatures));
        int reviewedBudget = LobbyPlaySinkAdmissionPolicy.automaticBudget(
                ReviewedClientContractEvidence.allowsAutomaticPlaySinks(
                        767, reviewedNormal80, reviewedSignatures),
                30);
        PlaySinkPlanner.Plan reviewed = PlaySinkPlanner.plan(
                767,
                reviewedNormal80,
                List.of(),
                reviewedBudget,
                false,
                false,
                false,
                Set.of());
        assertEquals(30, reviewed.channels().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> LobbyPlaySinkAdmissionPolicy.automaticBudget(true, -1));
    }

    @Test
    void globalRegistrationNeverTransfersConsumptionAcrossSessionsOrOrigins() {
        Set<String> firstSession = Set.of("reviewed:first");
        Set<String> secondSession = Set.of("reviewed:second");

        assertTrue(LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                true, firstSession, "reviewed:first"));
        assertFalse(LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                true, secondSession, "reviewed:first"));
        assertFalse(LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                false, firstSession, "reviewed:first"));
        assertFalse(LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                true, Set.of("vc:secret"), "vc:secret"));
        assertFalse(LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                true, Set.of("voicechat:request_secret"), "voicechat:request_secret"));

        assertTrue(LobbyPlaySinkAdmissionPolicy.interceptsProtocolChannels(true, false));
        assertFalse(LobbyPlaySinkAdmissionPolicy.interceptsProtocolChannels(true, true));
        assertFalse(LobbyPlaySinkAdmissionPolicy.interceptsProtocolChannels(false, false));
    }

    private static Registry normal80Registry() throws Exception {
        try (InputStream stream = LobbyPlaySinkAdmissionPolicyTest.class
                .getClassLoader()
                .getResourceAsStream("atm10-normal/8.0/client-neoforge-response.bin")) {
            if (stream == null) {
                throw new IllegalStateException("missing Normal 8.0 query fixture");
            }
            return NeoForgeHandshakeCodec.decodeLobbyQuery(
                    stream.readAllBytes(), ProtocolLimits.productionDefaults());
        }
    }
}
