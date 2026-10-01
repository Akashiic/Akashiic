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
import org.junit.jupiter.api.Test;

/** Regression tests joining the passive client contract to its reviewed executable profile. */
final class Atm10Normal73ContractEvidenceTest {
    private static final String QUERY_RESOURCE =
            "atm10-normal/7.3/client-neoforge-response.bin";
    private static final int MINECRAFT_PROTOCOL = 767;

    @Test
    void capturedContractHasStableCompleteAndSilentGearSignatures() throws Exception {
        byte[] query = readQuery();
        Registry registry = NeoForgeHandshakeCodec.decodeLobbyQuery(
                query, ProtocolLimits.productionDefaults());
        ChannelContractSignature.Signatures signatures = ChannelContractSignature.from(registry);
        SilentGearProtocol.Compatibility silentGear = SilentGearProtocol.inspect(
                registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL));

        assertEquals(103_124, query.length);
        assertEquals(
                "056a39b8d3832525ef4bfc67f4570aeae6557566ac6ba7a57be98d3f9ee453ae",
                SilentGearProtocol.sha256(query));
        assertEquals(2_381, registry.declaredChannelCount());
        assertEquals(1, registry.ignoredChannelCount());
        assertEquals(List.of(new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "ae2:",
                        "ae2",
                        Flow.CLIENTBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)),
                registry.ignoredChannels());
        assertEquals(2_380, registry.channelCount());
        assertEquals(2_352,
                registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL).size());
        assertEquals(28,
                registry.channelsFor(NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL).size());
        assertEquals(
                "cfce57a5a93240f97d570e952c3b4d71e5fac1f11504289f5ea4265371da559a",
                signatures.fullContractSha256());
        assertEquals(
                "003a1f69d13a92e3a9d6c70288dc38e6fc1e29c0c8df4513cbb06653736e0d44",
                signatures.silentGearContractSha256());
        assertTrue(silentGear.exact());
        assertEquals(SilentGearProtocol.ATM10_NORMAL_4_2, silentGear.contract().orElseThrow());
        assertTrue(ReviewedClientContractEvidence.ATM10_NORMAL_7_3.matches(
                MINECRAFT_PROTOCOL, signatures));
        assertTrue(ReviewedClientContractEvidence.matchesAtm10Normal73(
                MINECRAFT_PROTOCOL, registry, signatures));
        assertTrue(ReviewedClientContractEvidence.allowsAutomaticPlaySinks(
                MINECRAFT_PROTOCOL, registry, signatures));

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
        List<Channel> enderDrivesRequests = registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> channel.id().equals(ReviewedAtmCompatibility
                                .ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK.id())
                        || channel.id().equals(ReviewedAtmCompatibility
                                .ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK.id()))
                .toList();
        assertEquals(
                List.of(ReviewedAtmCompatibility
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
        assertEquals("1.0.0", gradualGlide.version());
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
                query,
                ProtocolLimits.productionDefaults(),
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS,
                0);
        assertTrue(playPlan.channels().contains(mahou));
        assertTrue(playPlan.channels().contains(creeper));
        assertTrue(playPlan.channels().contains(eternalStarlight));
        assertTrue(playPlan.channels().contains(deeperDarkerTransmitter));
        assertTrue(playPlan.channels().contains(toolBeltOpenSlot));
        assertTrue(playPlan.channels().contains(ironsSpellbooksCast));
        assertTrue(registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .anyMatch(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK.id())));
        assertTrue(registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .anyMatch(channel -> channel.id().equals(
                        ReviewedAtmCompatibility.RELICS_SHIELD_RELEASE_PLAY_SINK.id())));
        assertTrue(playPlan.channels().stream().noneMatch(channel ->
                channel.id().equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK.id())
                        || channel.id().equals(
                                ReviewedAtmCompatibility.RELICS_SHIELD_RELEASE_PLAY_SINK.id())));
        assertTrue(playPlan.channels().containsAll(enderDrivesRequests));
        assertFalse(playPlan.channels().contains(gradualGlide));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(mahou));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(creeper));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(eternalStarlight));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(toolBeltOpenSlot));
        assertFalse(playPlan.clientboundBootstrapChannels().contains(ironsSpellbooksCast));
        assertTrue(playPlan.channels().stream()
                .noneMatch(channel -> channel.id().equals(ReviewedAtmCompatibility
                        .ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK.id())));
    }

    @Test
    void exactEvidenceSelectsOnlyTheReviewedNormal73ExecutableProfile() throws Exception {
        byte[] query = readQuery();
        Registry registry = NeoForgeHandshakeCodec.decodeLobbyQuery(
                query, ProtocolLimits.productionDefaults());
        ChannelContractSignature.Signatures signatures = ChannelContractSignature.from(registry);
        SilentGearProfileCatalog catalog = SilentGearProfileCatalog.loadReviewed(
                getClass().getClassLoader(), MINECRAFT_PROTOCOL, 1_048_576, 3_145_728);

        SilentGearEmbeddedProfile profile = catalog.select(
                MINECRAFT_PROTOCOL, registry, signatures).orElseThrow();
        assertEquals(
                "atm10-normal-7.3_silentgear-4.2.1.1_neoforge-21.1.247",
                profile.profileId());
        assertEquals(116, profile.frozenRegistries().registryCount());

        Registry alteredIgnoredComponent = new Registry(
                registry.protocols(),
                registry.declaredChannelCount(),
                registry.ignoredChannelCount(),
                List.of(new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "ae2:",
                        "altered",
                        Flow.CLIENTBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)));
        ChannelContractSignature.Signatures alteredSignatures =
                ChannelContractSignature.from(alteredIgnoredComponent);
        assertEquals(signatures, alteredSignatures);
        assertFalse(ReviewedClientContractEvidence.matchesAtm10Normal73(
                MINECRAFT_PROTOCOL, alteredIgnoredComponent, alteredSignatures));
        assertFalse(ReviewedClientContractEvidence.allowsAutomaticPlaySinks(
                MINECRAFT_PROTOCOL, alteredIgnoredComponent, alteredSignatures));
        assertTrue(catalog.select(
                        MINECRAFT_PROTOCOL, alteredIgnoredComponent, alteredSignatures)
                .isEmpty());

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query,
                ProtocolLimits.productionDefaults(),
                List.of(),
                64,
                true,
                true,
                true);
        assertTrue(plan.silentGearCompatibleChannelsAdvertised());
        assertTrue(plan.silentGearProfileSelected());
        assertFalse(plan.clientboundBootstrapChannels().stream()
                .noneMatch(channel -> SilentGearProtocol.isSyncChannel(channel.id())));
    }

    private static byte[] readQuery() throws Exception {
        try (InputStream stream = Atm10Normal73ContractEvidenceTest.class
                .getClassLoader()
                .getResourceAsStream(QUERY_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing ATM10 Normal 7.3 contract fixture");
            }
            return stream.readAllBytes();
        }
    }
}
