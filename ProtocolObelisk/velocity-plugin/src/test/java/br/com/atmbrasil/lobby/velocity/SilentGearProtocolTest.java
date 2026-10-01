package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SilentGearProtocolTest {
    @Test
    void exactReviewedContractRequiresAllElevenSourceRegisteredChannels() {
        SilentGearProtocol.Compatibility compatibility = SilentGearProtocol.inspect(
                SilentGearProtocol.reviewedPlayContract());

        assertTrue(compatibility.exact());
        assertEquals(11, SilentGearProtocol.reviewedPlayContract().size());
        assertEquals(SilentGearProtocol.SYNC_CHANNELS,
                compatibility.clientboundChannels().stream().map(Channel::id).toList());
        assertEquals(SilentGearProtocol.ACK, compatibility.ackChannel().id());
        assertEquals(
                "994cb14de20346ab71e51ffdf378333eaa0068cbc8fad1faeda5ef0b5e2aa72f",
                compatibility.contractSha256());
        assertEquals("atm10-tts-silentgear-4.1.3", compatibility.contractId());
        assertEquals("4.1.3", compatibility.networkVersion());
        assertEquals(
                "4e602bac9ef9fb6104a9b437aaa1c25cc3e2f47df5e4ed5c4c82129915e83b73",
                SilentGearProtocol.reviewedContractSha256());
    }

    @Test
    void atm10Normal42IsASeparateExactChannelContractOnly() {
        SilentGearProtocol.Compatibility compatibility = SilentGearProtocol.inspect(
                SilentGearProtocol.ATM10_NORMAL_4_2.channels());

        assertTrue(compatibility.exact());
        assertEquals("atm10-normal-silentgear-4.2", compatibility.contractId());
        assertEquals("4.2", compatibility.networkVersion());
        assertEquals(
                "003a1f69d13a92e3a9d6c70288dc38e6fc1e29c0c8df4513cbb06653736e0d44",
                compatibility.contractSha256());
        assertTrue(SilentGearProtocol.ATM10_NORMAL_4_2.channels().stream()
                .anyMatch(channel -> channel.id().equals(SilentGearProtocol.TOGGLE_WORK_MODE)));
        assertFalse(SilentGearProtocol.ATM10_NORMAL_4_2.channels().stream()
                .anyMatch(channel -> channel.id().equals(SilentGearProtocol.ALLOY_MAKER_UPDATE)));
    }

    @Test
    void wrongVersionOrDirectionFailsClosed() {
        List<Channel> channels = new ArrayList<>(SilentGearProtocol.reviewedPlayContract());
        int parts = channels.indexOf(channels.stream()
                .filter(channel -> channel.id().equals(SilentGearProtocol.SYNC_PARTS))
                .findFirst()
                .orElseThrow());
        channels.set(parts, new Channel(
                SilentGearProtocol.SYNC_PARTS, "4.1.4", Flow.CLIENTBOUND, false));

        assertFalse(SilentGearProtocol.inspect(channels).exact());
    }

    @Test
    void missingExtraOptionalAndDuplicateChannelsFailClosed() {
        List<Channel> missing = new ArrayList<>(SilentGearProtocol.reviewedPlayContract());
        missing.removeLast();
        assertFalse(SilentGearProtocol.inspect(missing).exact());

        List<Channel> extra = new ArrayList<>(SilentGearProtocol.reviewedPlayContract());
        extra.add(new Channel("silentgear:unknown", "4.1.3", Flow.SERVERBOUND, false));
        assertFalse(SilentGearProtocol.inspect(extra).exact());

        List<Channel> optional = new ArrayList<>(SilentGearProtocol.reviewedPlayContract());
        optional.set(0, new Channel(
                SilentGearProtocol.SYNC_TRAITS, "4.1.3", Flow.CLIENTBOUND, true));
        assertFalse(SilentGearProtocol.inspect(optional).exact());

        List<Channel> duplicate = new ArrayList<>(SilentGearProtocol.reviewedPlayContract());
        duplicate.add(duplicate.getFirst());
        assertFalse(SilentGearProtocol.inspect(duplicate).exact());
    }

    @Test
    void fingerprintsAndOpaqueMapBoundsAreDeterministic() {
        byte[] query = "reviewed-registry".getBytes(StandardCharsets.UTF_8);
        assertEquals(64, SilentGearProtocol.fingerprint(query).length());
        assertEquals(SilentGearProtocol.fingerprint(query), SilentGearProtocol.fingerprint(query));
        assertEquals(130, SilentGearProtocol.validateNonEmptyMapPayload(
                new byte[] {(byte) 0x82, 0x01, 0x00}, 16));
        assertThrows(IllegalArgumentException.class,
                () -> SilentGearProtocol.validateNonEmptyMapPayload(new byte[] {0}, 16));
    }
}
