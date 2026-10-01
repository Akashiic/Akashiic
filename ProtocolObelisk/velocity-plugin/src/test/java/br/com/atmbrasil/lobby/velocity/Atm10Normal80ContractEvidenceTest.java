package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannelReason;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Exact, bounded regression fixture from the passive ATM10 Normal 8.0 capture. */
final class Atm10Normal80ContractEvidenceTest {
    private static final String QUERY_RESOURCE =
            "atm10-normal/8.0/client-neoforge-response.bin";
    private static final int MINECRAFT_PROTOCOL = 767;

    @Test
    void capturedQueryIsCompleteDespitePartialBundleStatus() throws Exception {
        byte[] query = readQuery();
        Registry registry = NeoForgeHandshakeCodec.decodeLobbyQuery(
                query, ProtocolLimits.productionDefaults());
        ChannelContractSignature.Signatures signatures = ChannelContractSignature.from(registry);

        assertEquals(103_657, query.length);
        assertEquals(
                "defcad0781fa208aa5ecb9358831d140eb530a499066e8694833989fb6dbdd4c",
                SilentGearProtocol.sha256(query));
        assertEquals(2_404, registry.declaredChannelCount());
        assertEquals(1, registry.ignoredChannelCount());
        assertEquals(List.of(new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "ae2:",
                        "ae2",
                        Flow.CLIENTBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)),
                registry.ignoredChannels());
        assertEquals(2_403, registry.channelCount());
        assertEquals(2_375,
                registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL).size());
        assertEquals(28,
                registry.channelsFor(NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL).size());
        assertTrue(registry.channelsFor(NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL).stream()
                .noneMatch(channel -> channel.id().equals("ae2:")));
        assertEquals(
                "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f",
                signatures.fullContractSha256());
        assertEquals(
                "003a1f69d13a92e3a9d6c70288dc38e6fc1e29c0c8df4513cbb06653736e0d44",
                signatures.silentGearContractSha256());
        assertTrue(ReviewedClientContractEvidence.ATM10_NORMAL_8_0.matches(
                MINECRAFT_PROTOCOL, signatures));
        assertFalse(ReviewedClientContractEvidence.ATM10_NORMAL_7_3.matches(
                MINECRAFT_PROTOCOL, signatures));

        Channel structurize = registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(
                        ReviewedAtmCompatibility.STRUCTURIZE_STRUCTURE_PACKS_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.STRUCTURIZE_NETWORK_VERSION,
                structurize.version());
        assertEquals(Flow.SERVERBOUND, structurize.flow());
        assertFalse(structurize.optional());

        Channel refinedStorage = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .REFINED_STORAGE_TENTH_ANNIVERSARY_CAPE_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.REFINED_STORAGE_NETWORK_VERSION,
                refinedStorage.version());
        assertEquals(Flow.SERVERBOUND, refinedStorage.flow());
        assertFalse(refinedStorage.optional());

        Channel accessories = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(
                        ReviewedAtmCompatibility.ACCESSORIES_MAIN_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.ACCESSORIES_NETWORK_VERSION,
                accessories.version());
        assertEquals(Flow.BIDIRECTIONAL, accessories.flow());
        assertTrue(accessories.optional());

        Channel aether = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .AETHER_PLAYER_ATTACHMENT_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.AETHER_PLAYER_ATTACHMENT_NETWORK_VERSION,
                aether.version());
        assertEquals(Flow.BIDIRECTIONAL, aether.flow());
        assertTrue(aether.optional());

        Channel mahou = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .MAHOU_TSUKAI_CHUNK_REQUEST_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.MAHOU_TSUKAI_NETWORK_VERSION,
                mahou.version());
        assertEquals(Flow.SERVERBOUND, mahou.flow());
        assertFalse(mahou.optional());

        Channel creeper = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .CREEPER_OVERHAUL_COSMETIC_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.CREEPER_OVERHAUL_NETWORK_VERSION,
                creeper.version());
        assertEquals(Flow.SERVERBOUND, creeper.flow());
        assertFalse(creeper.optional());

        Channel eternalStarlight = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_BOOK_PROGRESSION_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.ETERNAL_STARLIGHT_NETWORK_VERSION,
                eternalStarlight.version());
        assertEquals(Flow.BIDIRECTIONAL, eternalStarlight.flow());
        assertTrue(eternalStarlight.optional());

        Channel eternalStarlightSimpleAction = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.ETERNAL_STARLIGHT_NETWORK_VERSION,
                eternalStarlightSimpleAction.version());
        assertEquals(Flow.BIDIRECTIONAL, eternalStarlightSimpleAction.flow());
        assertTrue(eternalStarlightSimpleAction.optional());

        Channel relicsShieldRelease = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(
                        ReviewedAtmCompatibility.RELICS_SHIELD_RELEASE_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.RELICS_NETWORK_VERSION,
                relicsShieldRelease.version());
        assertEquals(Flow.SERVERBOUND, relicsShieldRelease.flow());
        assertTrue(relicsShieldRelease.optional());

        List<Channel> enderDrivesRequests = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                                .ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK.id())
                        || channel.id().equals(ReviewedAtmCompatibility
                                .ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK.id()))
                .toList();
        assertEquals(
                List.of(
                        ReviewedAtmCompatibility
                                .ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK.id(),
                        ReviewedAtmCompatibility
                                .ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK.id()),
                enderDrivesRequests.stream().map(Channel::id).toList());
        assertTrue(enderDrivesRequests.stream()
                .allMatch(channel -> channel.version().equals("1.0")
                        && channel.flow() == Flow.SERVERBOUND
                        && channel.optional()));

        Channel gradualGlide = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.TWILIGHT_FOREST_NETWORK_VERSION,
                gradualGlide.version());
        assertEquals(Flow.BIDIRECTIONAL, gradualGlide.flow());
        assertTrue(gradualGlide.optional());
        Channel deeperDarkerTransmitter = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .DEEPER_DARKER_USE_TRANSMITTER_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.DEEPER_DARKER_NETWORK_VERSION,
                deeperDarkerTransmitter.version());
        assertEquals(Flow.SERVERBOUND, deeperDarkerTransmitter.flow());
        assertFalse(deeperDarkerTransmitter.optional());
        Channel toolBeltOpenSlot = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.TOOL_BELT_NETWORK_VERSION,
                toolBeltOpenSlot.version());
        assertEquals(Flow.SERVERBOUND, toolBeltOpenSlot.flow());
        assertFalse(toolBeltOpenSlot.optional());
        Channel ironsSpellbooksCast = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .IRONS_SPELLBOOKS_CAST_PLAY_SINK.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(ReviewedAtmCompatibility.IRONS_SPELLBOOKS_NETWORK_VERSION,
                ironsSpellbooksCast.version());
        assertEquals(Flow.SERVERBOUND, ironsSpellbooksCast.flow());
        assertTrue(ironsSpellbooksCast.optional());

        PlaySinkPlanner.Plan playPlan = PlaySinkPlanner.plan(
                MINECRAFT_PROTOCOL,
                registry,
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS,
                0,
                true,
                false,
                false,
                Set.of());
        assertTrue(playPlan.channels().contains(structurize));
        assertTrue(playPlan.channels().contains(refinedStorage));
        assertTrue(playPlan.channels().contains(accessories));
        assertTrue(playPlan.channels().contains(aether));
        assertTrue(playPlan.channels().contains(mahou));
        assertTrue(playPlan.channels().contains(creeper));
        assertTrue(playPlan.channels().contains(eternalStarlight));
        assertTrue(playPlan.channels().contains(eternalStarlightSimpleAction));
        assertTrue(playPlan.channels().contains(relicsShieldRelease));
        assertTrue(playPlan.channels().containsAll(enderDrivesRequests));
        assertTrue(playPlan.channels().contains(gradualGlide));
        assertTrue(playPlan.channels().contains(deeperDarkerTransmitter));
        assertTrue(playPlan.channels().contains(toolBeltOpenSlot));
        assertTrue(playPlan.channels().contains(ironsSpellbooksCast));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(accessories));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(aether));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(mahou));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(creeper));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(eternalStarlight));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(
                eternalStarlightSimpleAction));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(relicsShieldRelease));
        assertTrue(playPlan.clientboundBootstrapChannels().stream()
                .noneMatch(channel -> channel.id().startsWith("enderdrives:")));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(gradualGlide));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(toolBeltOpenSlot));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(ironsSpellbooksCast));

        SilentGearProfileCatalog catalog = SilentGearProfileCatalog.loadReviewed(
                getClass().getClassLoader(), MINECRAFT_PROTOCOL, 1_048_576, 3_145_728);
        assertEquals(
                "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                catalog.select(MINECRAFT_PROTOCOL, signatures).orElseThrow().profileId());
        SilentGearEmbeddedProfile profile = catalog.select(
                MINECRAFT_PROTOCOL, signatures).orElseThrow();
        assertTrue(registry.channelsFor(NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL)
                .containsAll(ReviewedConfigurationProfile.channels(profile)));
        ChannelContractSignature.Signatures mutated = new ChannelContractSignature.Signatures(
                "0" + signatures.fullContractSha256().substring(1),
                signatures.silentGearContractSha256());
        assertTrue(catalog.select(MINECRAFT_PROTOCOL, mutated).isEmpty());
    }

    private static byte[] readQuery() throws Exception {
        try (InputStream stream = Atm10Normal80ContractEvidenceTest.class
                .getClassLoader()
                .getResourceAsStream(QUERY_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing ATM10 Normal 8.0 contract fixture");
            }
            return stream.readAllBytes();
        }
    }
}
