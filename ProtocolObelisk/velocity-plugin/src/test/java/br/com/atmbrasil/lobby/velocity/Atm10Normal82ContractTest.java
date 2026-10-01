package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class Atm10Normal82ContractTest {
    @Test
    void exactLogisticsV9AndAdAstraEvidenceAuthorizesOnlyTheNarrowMerge() {
        Registry exact = registry(
                new Channel("ad_astra:sync", "1", Flow.SERVERBOUND, false),
                logistics("9", Flow.SERVERBOUND, false));

        assertTrue(Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(767, exact));
    }

    @Test
    void atm10Normal81StyleLogisticsV1CannotEnterThe82MergePath() {
        Registry oldLineage = registry(
                new Channel("ad_astra:sync", "1", Flow.SERVERBOUND, false),
                logistics("1", Flow.SERVERBOUND, false));

        assertFalse(Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(
                767, oldLineage));
    }

    @Test
    void protocolFlowOptionalityAndIndependentAdAstraEvidenceRemainExact() {
        Registry exact = registry(
                new Channel("ad_astra:sync", "1", Flow.SERVERBOUND, false),
                logistics("9", Flow.SERVERBOUND, false));
        assertFalse(Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(766, exact));
        assertFalse(Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(
                767,
                registry(
                        new Channel("ad_astra:sync", "1", Flow.SERVERBOUND, false),
                        logistics("9", Flow.CLIENTBOUND, false))));
        assertFalse(Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(
                767,
                registry(
                        new Channel("ad_astra:sync", "1", Flow.SERVERBOUND, false),
                        logistics("9", Flow.SERVERBOUND, true))));
        assertFalse(Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(
                767, registry(logistics("9", Flow.SERVERBOUND, false))));
    }

    private static Channel logistics(String version, Flow flow, boolean optional) {
        return new Channel(
                Atm10Normal82Contract.LOGISTICS_CHANNEL_ID, version, flow, optional);
    }

    private static Registry registry(Channel... channels) {
        return new Registry(Map.of(1, List.of(channels)));
    }
}
