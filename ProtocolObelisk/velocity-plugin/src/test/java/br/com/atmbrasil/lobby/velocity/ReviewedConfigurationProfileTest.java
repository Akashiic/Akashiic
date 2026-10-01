package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ReviewedConfigurationProfileTest {
    private static final int PROTOCOL = 767;
    private static final int PAYLOAD_LIMIT = 1_048_576;
    private static final int TOTAL_LIMIT = 3_145_728;

    @Test
    void normal73AdvertisesExactReviewedConfigurationContract() throws Exception {
        SilentGearEmbeddedProfile profile = normal73();
        List<Channel> channels = ReviewedConfigurationProfile.channels(profile);

        assertTrue(ReviewedConfigurationProfile.isAtm10Normal73(profile));
        assertEquals(13, channels.size());
        assertEquals(NeoForgeFrozenRegistryProfile.reviewedConfigurationChannels(),
                channels.subList(0, 4));
        assertEquals(List.of(
                required("trashcans:main", "1", Flow.BIDIRECTIONAL),
                required("owo:handshake_off", "1.0.0", Flow.CLIENTBOUND),
                required("simplemagnets:main", "1", Flow.BIDIRECTIONAL),
                required("owo:handshake", "1.0.0", Flow.BIDIRECTIONAL),
                required("mekanism:batch_security", "10.7.19", Flow.CLIENTBOUND),
                required("wirelesschargers:main", "1", Flow.BIDIRECTIONAL),
                required("itemcollectors:main", "1", Flow.BIDIRECTIONAL),
                required("rechiseled:main", "1", Flow.BIDIRECTIONAL),
                required("enderstorage:network", "2.13.0.191", Flow.CLIENTBOUND)),
                channels.subList(4, channels.size()));
        assertEquals(Set.copyOf(channels.subList(4, channels.size()).stream()
                .map(Channel::id)
                .toList()), ReviewedConfigurationProfile.advertisedChannelIds(profile));
    }

    @Test
    void normal73SendsOnlyNeutralMekanismSnapshotWithoutWaitingForAck() throws Exception {
        List<ReviewedConfigurationProfile.Bootstrap> bootstraps =
                ReviewedConfigurationProfile.bootstraps(normal73());

        assertEquals(1, bootstraps.size());
        ReviewedConfigurationProfile.Bootstrap bootstrap = bootstraps.getFirst();
        assertEquals(ReviewedConfigurationProfile.MEKANISM_BATCH_SECURITY,
                bootstrap.channelId());
        assertArrayEquals(new byte[] {0, 0}, bootstrap.bytes());
        assertFalse(bootstrap.acknowledgementRequired());

        byte[] mutation = bootstrap.bytes();
        mutation[0] = 1;
        assertArrayEquals(new byte[] {0, 0}, bootstrap.bytes());
    }

    @Test
    void normal80UsesTheSameExactConfigurationSubsetButItsOwnCanonicalIdentity()
            throws Exception {
        SilentGearEmbeddedProfile profile = normal80();

        assertTrue(ReviewedConfigurationProfile.isAtm10Normal80(profile));
        assertTrue(ReviewedConfigurationProfile.isAtm10Normal(profile));
        assertFalse(ReviewedConfigurationProfile.isAtm10Normal73(profile));
        assertEquals(ReviewedConfigurationProfile.channels(normal73()),
                ReviewedConfigurationProfile.channels(profile));
        assertArrayEquals(new byte[] {0, 0},
                ReviewedConfigurationProfile.bootstraps(profile).getFirst().bytes());
    }

    @Test
    void ttsProfileRemainsOnItsOriginalFourChannelContract() throws Exception {
        SilentGearEmbeddedProfile tts = SilentGearEmbeddedProfile.loadReviewed(
                getClass().getClassLoader(), PROTOCOL, PAYLOAD_LIMIT, TOTAL_LIMIT);

        assertFalse(ReviewedConfigurationProfile.isAtm10Normal73(tts));
        assertEquals(NeoForgeFrozenRegistryProfile.reviewedConfigurationChannels(),
                ReviewedConfigurationProfile.channels(tts));
        assertTrue(ReviewedConfigurationProfile.bootstraps(tts).isEmpty());
        assertTrue(ReviewedConfigurationProfile.advertisedChannelIds(tts).isEmpty());
    }

    @Test
    void fixedRegistrarSetContainsOnlyTheNinePackSpecificChannels() {
        assertEquals(Set.of(
                "trashcans:main",
                "owo:handshake_off",
                "simplemagnets:main",
                "owo:handshake",
                "mekanism:batch_security",
                "wirelesschargers:main",
                "itemcollectors:main",
                "rechiseled:main",
                "enderstorage:network"), ReviewedConfigurationProfile.ownedChannelIds());
    }

    private SilentGearEmbeddedProfile normal73() throws Exception {
        return SilentGearEmbeddedProfile.loadAtm10Normal73(
                getClass().getClassLoader(), PROTOCOL, PAYLOAD_LIMIT, TOTAL_LIMIT);
    }

    private SilentGearEmbeddedProfile normal80() throws Exception {
        return SilentGearEmbeddedProfile.loadAtm10Normal80(
                getClass().getClassLoader(), PROTOCOL, PAYLOAD_LIMIT, TOTAL_LIMIT);
    }

    private static Channel required(String id, String version, Flow flow) {
        return new Channel(id, version, flow, false);
    }
}
