package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.PlaySinkPlanner.Mode;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class PlaySinkPlannerTest {
    private static final ProtocolLimits LIMITS = ProtocolLimits.productionDefaults();
    private static final PinnedPlayChannel SOPHISTICATED = new PinnedPlayChannel(
            "sophisticatedbackpacks:request_player_settings", "1.0");
    private static final PinnedPlayChannel SOPHISTICATED_STORAGE = new PinnedPlayChannel(
            "sophisticatedstorage:request_player_settings", "1.0");
    private static final PinnedPlayChannel PNEUMATICCRAFT = new PinnedPlayChannel(
            "pneumaticcraft:sync_amadron_offers", "1");
    private static final PinnedPlayChannel XYCRAFT_MODIFIER_KEY =
            ReviewedAtmCompatibility.XYCRAFT_MODIFIER_KEY_PLAY_SINK;
    private static final PinnedPlayChannel SOPHISTICATED_BLOCK_PICK =
            ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_BLOCK_PICK_PLAY_SINK;
    private static final PinnedPlayChannel SOPHISTICATED_BACKPACK_OPEN =
            ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_OPEN_PLAY_SINK;
    private static final PinnedPlayChannel SOPHISTICATED_ANOTHER_PLAYER_BACKPACK_OPEN =
            ReviewedAtmCompatibility
                    .SOPHISTICATED_BACKPACKS_ANOTHER_PLAYER_BACKPACK_OPEN_PLAY_SINK;
    private static final PinnedPlayChannel KUBEJS_KUBEDEX_REQUEST_BLOCK =
            ReviewedAtmCompatibility.KUBEJS_KUBEDEX_REQUEST_BLOCK_PLAY_SINK;
    private static final PinnedPlayChannel LOGISTICS_DEFAULT_VISIBILITY =
            ReviewedAtmCompatibility.LOGISTICS_NETWORKS_DEFAULT_VISIBILITY_PLAY_SINK;
    private static final List<PinnedPlayChannel> INCIDENT_INTERACTIONS =
            ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS;

    @Test
    void logisticsDefaultVisibilityIsPinnedOutsideAutomaticBudget() {
        byte[] query = query(protocol(1,
                channel(
                        LOGISTICS_DEFAULT_VISIBILITY.id(),
                        LOGISTICS_DEFAULT_VISIBILITY.version(),
                        Flow.SERVERBOUND,
                        false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(LOGISTICS_DEFAULT_VISIBILITY), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(List.of(LOGISTICS_DEFAULT_VISIBILITY.id()),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                LOGISTICS_DEFAULT_VISIBILITY.id(), 1));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                LOGISTICS_DEFAULT_VISIBILITY.id(), 2));
    }

    @Test
    void mekanismReviewedFallbackRetainsNewerClientAdvertisedNetworkVersion() {
        byte[] query = query(protocol(1,
                channel(
                        ReviewedAtmCompatibility.MEKANISM_KEY_PLAY_SINK.id(),
                        "10.7.19",
                        Flow.SERVERBOUND,
                        false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query,
                LIMITS,
                List.of(ReviewedAtmCompatibility.MEKANISM_KEY_PLAY_SINK),
                0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.channels().size());
        assertEquals("mekanism:key", plan.channels().getFirst().id());
        assertEquals("10.7.19", plan.channels().getFirst().version());
    }

    @Test
    void configuredLogisticsModifierKeysV9PinIsSelectedOutsideAutomaticBudget() {
        PinnedPlayChannel modifierKeys = new PinnedPlayChannel(
                Atm10Normal82Contract.LOGISTICS_CHANNEL_ID,
                Atm10Normal82Contract.LOGISTICS_NETWORK_VERSION);
        byte[] query = query(protocol(1,
                channel(
                        modifierKeys.id(),
                        modifierKeys.version(),
                        Flow.SERVERBOUND,
                        false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(modifierKeys), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(List.of(modifierKeys.id()),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertEquals("9", plan.channels().getFirst().version());
        assertEquals(Flow.SERVERBOUND, plan.channels().getFirst().flow());
    }

    @Test
    void logisticsModifierKeysV9PinRejectsSameIdWithOlderNetworkVersion() {
        PinnedPlayChannel modifierKeys =
                ReviewedAtmCompatibility.LOGISTICS_NETWORKS_MODIFIER_KEYS_ATM10_82_PLAY_SINK;
        byte[] query = query(protocol(1,
                channel(modifierKeys.id(), "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(modifierKeys), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertTrue(plan.channels().isEmpty());
        assertEquals(1, plan.ignoredRegistryChannelCount());
    }

    @Test
    void logisticsModifierKeysPayloadSizeIsExactlyOneByte() {
        String id = Atm10Normal82Contract.LOGISTICS_CHANNEL_ID;
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(id, 1));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(id, 0));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(id, 2));
    }

    @Test
    void adaptivePriorityKeepsSetDefaultLifecycleTrafficAheadOfOrdinaryActions() {
        byte[] query = query(protocol(1,
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false),
                channel(
                        "logisticsnetworks:set_default_node_visibility",
                        "1",
                        Flow.SERVERBOUND,
                        false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(query, LIMITS, List.of(), 1);

        assertEquals(List.of("logisticsnetworks:set_default_node_visibility"),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
    }

    @Test
    void parsedPlanPinsKnownChannelAndPrioritizesJoinTraffic() {
        byte[] query = query(protocol(1,
                channel(SOPHISTICATED.id(), SOPHISTICATED.version(), Flow.SERVERBOUND, false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false),
                channel("example:client_ready", "1", Flow.BIDIRECTIONAL, true),
                channel("example:login_notice", "1", Flow.CLIENTBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(SOPHISTICATED), 1);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(3, plan.eligibleChannelCount());
        assertEquals(1, plan.omittedAutomaticChannelCount());
        assertEquals(0, plan.ignoredRegistryChannelCount());
        assertEquals(
                List.of("example", "sophisticatedbackpacks"),
                plan.advertisedNamespaces());
        assertEquals(
                List.of(
                        "sophisticatedbackpacks:request_player_settings",
                        "example:client_ready"),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
    }

    @Test
    void pneumaticCraftAmadronPinRetainsTheClientsBidirectionalRegistration() {
        byte[] query = query(protocol(1,
                channel(PNEUMATICCRAFT.id(), "1", Flow.BIDIRECTIONAL, false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(PNEUMATICCRAFT), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(2, plan.eligibleChannelCount());
        assertEquals(1, plan.omittedAutomaticChannelCount());
        assertEquals(List.of(PNEUMATICCRAFT.id()),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertEquals(Flow.BIDIRECTIONAL, plan.channels().getFirst().flow());
        assertEquals("1", plan.channels().getFirst().version());
    }

    @Test
    void xyCraftModifierKeyIsNegotiatedOutsideTheAutomaticBudget() {
        byte[] query = query(protocol(1,
                channel(
                        XYCRAFT_MODIFIER_KEY.id(),
                        XYCRAFT_MODIFIER_KEY.version(),
                        Flow.BIDIRECTIONAL,
                        false),
                channel("example:client_ready", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(XYCRAFT_MODIFIER_KEY), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.omittedAutomaticChannelCount());
        assertEquals(List.of(XYCRAFT_MODIFIER_KEY.id()),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertEquals(Flow.BIDIRECTIONAL, plan.channels().getFirst().flow());
        assertEquals("1.0.0", plan.channels().getFirst().version());
    }

    @Test
    void blockPickIsNegotiatedOutsideBudgetAndRetainsTheAdvertisedContract() {
        byte[] query = query(protocol(1,
                channel(
                        SOPHISTICATED_BLOCK_PICK.id(),
                        "3.25.34",
                        Flow.SERVERBOUND,
                        false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(SOPHISTICATED_BLOCK_PICK), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.omittedAutomaticChannelCount());
        assertEquals(List.of(SOPHISTICATED_BLOCK_PICK.id()),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertEquals("3.25.34", plan.channels().getFirst().version());
    }

    @Test
    void malformedQueryNeverInventsReviewedBlockPickVersion() {
        byte[] query = query(protocol(1,
                channel(SOPHISTICATED_BLOCK_PICK.id(), "3.25.34", Flow.SERVERBOUND, false)));
        query[1] = 3;

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(SOPHISTICATED_BLOCK_PICK), 0);

        assertEquals(Mode.PINNED_FALLBACK, plan.mode());
        assertTrue(plan.channels().isEmpty());
    }

    @Test
    void newCrashChannelsArePinnedAndRetainTheClientAdvertisedContracts() {
        byte[] query = query(protocol(1,
                channel(
                        SOPHISTICATED_BACKPACK_OPEN.id(),
                        "3.25.34",
                        Flow.SERVERBOUND,
                        false),
                channel(
                        SOPHISTICATED_ANOTHER_PLAYER_BACKPACK_OPEN.id(),
                        "3.25.77",
                        Flow.SERVERBOUND,
                        false),
                channel(
                        KUBEJS_KUBEDEX_REQUEST_BLOCK.id(),
                        "1",
                        Flow.SERVERBOUND,
                        true),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query,
                LIMITS,
                List.of(
                        SOPHISTICATED_BACKPACK_OPEN,
                        SOPHISTICATED_ANOTHER_PLAYER_BACKPACK_OPEN,
                        KUBEJS_KUBEDEX_REQUEST_BLOCK),
                0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.omittedAutomaticChannelCount());
        assertEquals(
                List.of(
                        "sophisticatedbackpacks:backpack_open",
                        "sophisticatedbackpacks:another_player_backpack_open",
                        "kubejs:kubedex/request_block"),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertEquals(
                List.of("3.25.34", "3.25.77", "1"),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::version).toList());
        assertEquals(
                List.of(false, false, true),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::optional).toList());
    }

    @Test
    void anotherPlayerBackpackOpenRetainsClientVersionAndRequiresFourByteEntityId() {
        byte[] query = query(protocol(1,
                channel(
                        SOPHISTICATED_ANOTHER_PLAYER_BACKPACK_OPEN.id(),
                        "3.25.77",
                        Flow.SERVERBOUND,
                        false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(SOPHISTICATED_ANOTHER_PLAYER_BACKPACK_OPEN), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(
                List.of("sophisticatedbackpacks:another_player_backpack_open"),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertEquals("3.25.77", plan.channels().getFirst().version());
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                SOPHISTICATED_ANOTHER_PLAYER_BACKPACK_OPEN.id(), new byte[Integer.BYTES]));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                SOPHISTICATED_ANOTHER_PLAYER_BACKPACK_OPEN.id(), new byte[Integer.BYTES - 1]));
    }

    @Test
    void attachedCrashInteractionsArePinnedOutsideTheAutomaticBudget() {
        byte[] query = query(protocol(1,
                channel("ae2wtlib:pick_block", "ae2wtlib", Flow.BIDIRECTIONAL, false),
                channel(
                        "draconicevolution:network",
                        "3.2.1.309",
                        Flow.BIDIRECTIONAL,
                        true),
                channel("curios:open_curios", "1.0", Flow.SERVERBOUND, false),
                channel(
                        "cosmeticarmorreworked:open_cosarmor_inv",
                        "5",
                        Flow.SERVERBOUND,
                        false),
                channel("ftbteams:open_gui", "ftbteams", Flow.SERVERBOUND, true),
                channel(
                        "notenoughwands:getprotectedblockcount",
                        "1.0",
                        Flow.SERVERBOUND,
                        true),
                channel(
                        "ftbultimine:key_pressed_packet",
                        "ftbultimine",
                        Flow.SERVERBOUND,
                        true),
                channel(
                        "ftbultimine:mode_changed_packet",
                        "ftbultimine",
                        Flow.SERVERBOUND,
                        true),
                channel(
                        "cb_multipart:network",
                        "3.5.0.155",
                        Flow.BIDIRECTIONAL,
                        false),
                channel(
                        "mcjtylib:sendservercommand",
                        "1.0",
                        Flow.SERVERBOUND,
                        true),
                channel(
                        "translocators:network",
                        "2.8.0.89",
                        Flow.BIDIRECTIONAL,
                        false),
                channel(
                        "simplemagnets:main",
                        "1",
                        Flow.BIDIRECTIONAL,
                        false),
                channel(
                        "logisticsnetworks:set_default_node_visibility",
                        "1",
                        Flow.SERVERBOUND,
                        false),
                channel(
                        "structurize:notify_server_about_structure_packs",
                        "1.0.832-1.21.1",
                        Flow.SERVERBOUND,
                        false),
                channel(
                        "refinedstorage:set_tenth_anniversary_cape",
                        "2.0.9",
                        Flow.SERVERBOUND,
                        false),
                channel(
                        "accessories:main",
                        "1.0.0",
                        Flow.BIDIRECTIONAL,
                        true),
                channel(
                        "aether:sync_aether_player_attachment",
                        "1.0.0",
                        Flow.BIDIRECTIONAL,
                        true),
                channel(
                        "mahoutsukai:chunk_mahou_request_packet",
                        "1",
                        Flow.SERVERBOUND,
                        false),
                channel(
                        "creeperoverhaul:main/v1/creeperoverhaul/set_cosmetic",
                        "v1",
                        Flow.SERVERBOUND,
                        false),
                channel(
                        "eternal_starlight:update_book_progression",
                        "eternal_starlight",
                        Flow.BIDIRECTIONAL,
                        true),
                channel(
                        "enderdrives:request_disk_type_count",
                        "1.0",
                        Flow.SERVERBOUND,
                        true),
                channel(
                        "enderdrives:request_fluid_disk_type_count",
                        "1.0",
                        Flow.SERVERBOUND,
                        true),
                channel(
                        "deeperdarker:use_transmitter",
                        "1.0",
                        Flow.SERVERBOUND,
                        false),
                channel(
                        "toolbelt:open_belt_slot_inventory",
                        "1.0",
                        Flow.SERVERBOUND,
                        false),
                channel(
                        "irons_spellbooks:cast",
                        "1.0.0",
                        Flow.SERVERBOUND,
                        true),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, INCIDENT_INTERACTIONS, 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.omittedAutomaticChannelCount());
        assertEquals(
                INCIDENT_INTERACTIONS.stream()
                        .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                                .TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK))
                        .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                                .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK))
                        .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                                .RELICS_SHIELD_RELEASE_PLAY_SINK))
                        // The fixture is an 8.1-era client without LogisticsNetworks v9.
                        .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                                .LOGISTICS_NETWORKS_MODIFIER_KEYS_ATM10_82_PLAY_SINK))
                        .toList(),
                plan.channels().stream()
                        .map(channel -> new PinnedPlayChannel(channel.id(), channel.version()))
                        .toList());
        assertTrue(plan.channels().stream().noneMatch(channel -> channel.id().equals(
                ReviewedAtmCompatibility.TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK.id())));
        assertTrue(plan.channels().stream().noneMatch(channel ->
                channel.id().equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK.id())
                        || channel.id().equals(
                                ReviewedAtmCompatibility.RELICS_SHIELD_RELEASE_PLAY_SINK.id())));
        assertEquals(
                List.of(
                        Flow.BIDIRECTIONAL,
                        Flow.BIDIRECTIONAL,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.BIDIRECTIONAL,
                        Flow.SERVERBOUND,
                        Flow.BIDIRECTIONAL,
                        Flow.BIDIRECTIONAL,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.BIDIRECTIONAL,
                        Flow.BIDIRECTIONAL,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.BIDIRECTIONAL,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND,
                        Flow.SERVERBOUND),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::flow).toList());
        assertEquals(
                List.of(
                        false,
                        true,
                        false,
                        false,
                        true,
                        true,
                        true,
                        true,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        true,
                        false,
                        false,
                        true,
                        true,
                        true,
                        false,
                        false,
                        true),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::optional).toList());
    }

    @Test
    void deeperDarkerTransmitterRequiresExactMandatoryServerboundContract() {
        PinnedPlayChannel transmitter =
                ReviewedAtmCompatibility.DEEPER_DARKER_USE_TRANSMITTER_PLAY_SINK;
        byte[] exact = query(protocol(1, channel(
                transmitter.id(), transmitter.version(), Flow.SERVERBOUND, false)));
        PlaySinkPlanner.Plan accepted = PlaySinkPlanner.plan(
                exact, LIMITS, List.of(transmitter), 0);

        assertEquals(List.of(transmitter.id()), accepted.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .toList());
        assertEquals(Flow.SERVERBOUND, accepted.channels().getFirst().flow());
        assertFalse(accepted.channels().getFirst().optional());

        List<byte[]> divergentContracts = List.of(
                query(protocol(1, channel(
                        transmitter.id(), "1.1", Flow.SERVERBOUND, false))),
                query(protocol(1, channel(
                        transmitter.id(), transmitter.version(), Flow.BIDIRECTIONAL, false))),
                query(protocol(1, channel(
                        transmitter.id(), transmitter.version(), Flow.SERVERBOUND, true))),
                query(protocol(1, channel(
                        transmitter.id(), transmitter.version(), Flow.CLIENTBOUND, false))));
        for (byte[] divergent : divergentContracts) {
            PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                    divergent, LIMITS, List.of(transmitter), 48);
            assertTrue(rejected.channels().isEmpty());
        }
    }

    @Test
    void toolBeltOpenSlotRequiresExactMandatoryServerboundContract() {
        PinnedPlayChannel openSlot =
                ReviewedAtmCompatibility.TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK;
        byte[] exact = query(protocol(1, channel(
                openSlot.id(), openSlot.version(), Flow.SERVERBOUND, false)));
        PlaySinkPlanner.Plan accepted = PlaySinkPlanner.plan(
                exact, LIMITS, List.of(openSlot), 0);

        assertEquals(List.of(openSlot.id()), accepted.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .toList());
        assertEquals(Flow.SERVERBOUND, accepted.channels().getFirst().flow());
        assertFalse(accepted.channels().getFirst().optional());
        assertTrue(accepted.clientboundBootstrapChannels().isEmpty());

        List<byte[]> divergentContracts = List.of(
                query(protocol(1, channel(
                        openSlot.id(), "1.1", Flow.SERVERBOUND, false))),
                query(protocol(1, channel(
                        openSlot.id(), openSlot.version(), Flow.BIDIRECTIONAL, false))),
                query(protocol(1, channel(
                        openSlot.id(), openSlot.version(), Flow.SERVERBOUND, true))),
                query(protocol(1, channel(
                        openSlot.id(), openSlot.version(), Flow.CLIENTBOUND, false))));
        for (byte[] divergent : divergentContracts) {
            PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                    divergent, LIMITS, List.of(openSlot), 48);
            assertTrue(rejected.channels().isEmpty());
        }
    }

    @Test
    void ironsSpellbooksCastRequiresExactOptionalServerboundContract() {
        PinnedPlayChannel cast = ReviewedAtmCompatibility.IRONS_SPELLBOOKS_CAST_PLAY_SINK;
        byte[] exact = query(protocol(1, channel(
                cast.id(), cast.version(), Flow.SERVERBOUND, true)));
        PlaySinkPlanner.Plan accepted = PlaySinkPlanner.plan(
                exact, LIMITS, List.of(cast), 0);

        assertEquals(List.of(cast.id()), accepted.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .toList());
        assertEquals(Flow.SERVERBOUND, accepted.channels().getFirst().flow());
        assertTrue(accepted.channels().getFirst().optional());
        assertTrue(accepted.clientboundBootstrapChannels().isEmpty());

        List<byte[]> divergentContracts = List.of(
                query(protocol(1, channel(
                        cast.id(), "1.0.1", Flow.SERVERBOUND, true))),
                query(protocol(1, channel(
                        cast.id(), cast.version(), Flow.BIDIRECTIONAL, true))),
                query(protocol(1, channel(
                        cast.id(), cast.version(), Flow.SERVERBOUND, false))),
                query(protocol(1, channel(
                        cast.id(), cast.version(), Flow.CLIENTBOUND, true))));
        for (byte[] divergent : divergentContracts) {
            PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                    divergent, LIMITS, List.of(cast), 48);
            assertTrue(rejected.channels().isEmpty());
        }
    }

    @Test
    void enderDrivesRequestsRequireExactOptionalServerboundContracts() {
        List<PinnedPlayChannel> requests = List.of(
                ReviewedAtmCompatibility.ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK,
                ReviewedAtmCompatibility.ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK);
        for (PinnedPlayChannel request : requests) {
            byte[] exact = query(protocol(1, channel(
                    request.id(), request.version(), Flow.SERVERBOUND, true)));
            PlaySinkPlanner.Plan accepted = PlaySinkPlanner.plan(
                    exact, LIMITS, List.of(request), 0);

            assertEquals(List.of(request.id()), accepted.channels().stream()
                    .map(NeoForgeHandshakeCodec.Channel::id)
                    .toList());
            assertEquals(Flow.SERVERBOUND, accepted.channels().getFirst().flow());
            assertTrue(accepted.channels().getFirst().optional());
            assertTrue(accepted.clientboundBootstrapChannels().isEmpty());

            List<byte[]> divergentContracts = List.of(
                    query(protocol(1, channel(
                            request.id(), "1.1", Flow.SERVERBOUND, true))),
                    query(protocol(1, channel(
                            request.id(), request.version(), Flow.BIDIRECTIONAL, true))),
                    query(protocol(1, channel(
                            request.id(), request.version(), Flow.SERVERBOUND, false))),
                    query(protocol(1, channel(
                            request.id(), request.version(), Flow.CLIENTBOUND, true))));
            for (byte[] divergent : divergentContracts) {
                PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                        divergent, LIMITS, List.of(request), 48);
                assertTrue(rejected.channels().isEmpty());
            }

            byte[] absent = query(protocol(1,
                    channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));
            assertTrue(PlaySinkPlanner.plan(absent, LIMITS, List.of(request), 0)
                    .channels()
                    .isEmpty());
        }
    }

    @Test
    void eternalStarlightProgressionUsesTheExactOptionalBidirectionalContract() {
        PinnedPlayChannel eternalStarlight =
                ReviewedAtmCompatibility.ETERNAL_STARLIGHT_BOOK_PROGRESSION_PLAY_SINK;
        byte[] exact = query(protocol(1, channel(
                eternalStarlight.id(),
                eternalStarlight.version(),
                Flow.BIDIRECTIONAL,
                true)));
        PlaySinkPlanner.Plan accepted = PlaySinkPlanner.plan(
                exact, LIMITS, List.of(eternalStarlight), 0);

        assertEquals(List.of(eternalStarlight.id()), accepted.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .toList());
        assertTrue(accepted.clientboundBootstrapChannels().isEmpty());

        List<byte[]> divergentContracts = List.of(
                query(protocol(1, channel(
                        eternalStarlight.id(),
                        eternalStarlight.version() + ".mutated",
                        Flow.BIDIRECTIONAL,
                        true))),
                query(protocol(1, channel(
                        eternalStarlight.id(),
                        eternalStarlight.version(),
                        Flow.SERVERBOUND,
                        true))),
                query(protocol(1, channel(
                        eternalStarlight.id(),
                        eternalStarlight.version(),
                        Flow.BIDIRECTIONAL,
                        false))));
        for (byte[] divergent : divergentContracts) {
            PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                    divergent, LIMITS, List.of(eternalStarlight), 48);
            assertTrue(rejected.channels().isEmpty());
            assertEquals(1, rejected.ignoredRegistryChannelCount());
        }
    }

    @Test
    void mahouAndCreeperSinksRequireExactRequiredServerboundContracts() {
        List<PinnedPlayChannel> reviewed = List.of(
                ReviewedAtmCompatibility.MAHOU_TSUKAI_CHUNK_REQUEST_PLAY_SINK,
                ReviewedAtmCompatibility.CREEPER_OVERHAUL_COSMETIC_PLAY_SINK);
        for (PinnedPlayChannel pinned : reviewed) {
            byte[] exact = query(protocol(1,
                    channel(pinned.id(), pinned.version(), Flow.SERVERBOUND, false)));
            PlaySinkPlanner.Plan accepted = PlaySinkPlanner.plan(
                    exact, LIMITS, List.of(pinned), 0);

            assertEquals(List.of(pinned.id()), accepted.channels().stream()
                    .map(NeoForgeHandshakeCodec.Channel::id)
                    .toList());
            assertEquals(Flow.SERVERBOUND, accepted.channels().getFirst().flow());
            assertFalse(accepted.channels().getFirst().optional());

            List<byte[]> divergentContracts = List.of(
                    query(protocol(1,
                            channel(pinned.id(), pinned.version() + ".mutated",
                                    Flow.SERVERBOUND, false))),
                    query(protocol(1,
                            channel(pinned.id(), pinned.version(), Flow.BIDIRECTIONAL, false))),
                    query(protocol(1,
                            channel(pinned.id(), pinned.version(), Flow.SERVERBOUND, true))));
            for (byte[] divergent : divergentContracts) {
                PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                        divergent, LIMITS, List.of(pinned), 48);
                assertTrue(rejected.channels().isEmpty());
                assertEquals(1, rejected.ignoredRegistryChannelCount());
            }

            byte[] absent = query(protocol(1,
                    channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));
            assertTrue(PlaySinkPlanner.plan(absent, LIMITS, List.of(pinned), 0)
                    .channels()
                    .isEmpty());
        }
    }

    @Test
    void structurizeWorldJoinSinkUsesTheExactRequiredServerboundContract() {
        PinnedPlayChannel structurize =
                ReviewedAtmCompatibility.STRUCTURIZE_STRUCTURE_PACKS_PLAY_SINK;
        byte[] query = query(protocol(1,
                channel(
                        structurize.id(),
                        structurize.version(),
                        Flow.SERVERBOUND,
                        false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(structurize), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.channels().size());
        assertEquals(structurize.id(), plan.channels().getFirst().id());
        assertEquals(structurize.version(), plan.channels().getFirst().version());
        assertEquals(Flow.SERVERBOUND, plan.channels().getFirst().flow());
        assertFalse(plan.channels().getFirst().optional());
        assertEquals(1, plan.omittedAutomaticChannelCount());

        List<byte[]> divergentContracts = List.of(
                query(protocol(1, channel(
                        structurize.id(), "1.0.831-1.21.1", Flow.SERVERBOUND, false))),
                query(protocol(1, channel(
                        structurize.id(), structurize.version(),
                        Flow.BIDIRECTIONAL, false))),
                query(protocol(1, channel(
                        structurize.id(), structurize.version(),
                        Flow.SERVERBOUND, true))));
        for (byte[] divergent : divergentContracts) {
            PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                    divergent, LIMITS, List.of(structurize), 48);
            assertTrue(rejected.channels().isEmpty());
            assertEquals(1, rejected.ignoredRegistryChannelCount());
        }
    }

    @Test
    void refinedStorageLoginCapeSinkUsesTheExactRequiredServerboundContract() {
        PinnedPlayChannel refinedStorage = ReviewedAtmCompatibility
                .REFINED_STORAGE_TENTH_ANNIVERSARY_CAPE_PLAY_SINK;
        byte[] query = query(protocol(1,
                channel(
                        refinedStorage.id(),
                        refinedStorage.version(),
                        Flow.SERVERBOUND,
                        false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(refinedStorage), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.channels().size());
        assertEquals(refinedStorage.id(), plan.channels().getFirst().id());
        assertEquals(refinedStorage.version(), plan.channels().getFirst().version());
        assertEquals(Flow.SERVERBOUND, plan.channels().getFirst().flow());
        assertFalse(plan.channels().getFirst().optional());
        assertEquals(1, plan.omittedAutomaticChannelCount());

        List<byte[]> divergentContracts = List.of(
                query(protocol(1, channel(
                        refinedStorage.id(), "2.0.8", Flow.SERVERBOUND, false))),
                query(protocol(1, channel(
                        refinedStorage.id(), refinedStorage.version(),
                        Flow.BIDIRECTIONAL, false))),
                query(protocol(1, channel(
                        refinedStorage.id(), refinedStorage.version(),
                        Flow.SERVERBOUND, true))));
        for (byte[] divergent : divergentContracts) {
            PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                    divergent, LIMITS, List.of(refinedStorage), 48);
            assertTrue(rejected.channels().isEmpty());
            assertEquals(1, rejected.ignoredRegistryChannelCount());
        }
    }

    @Test
    void accessoriesLoginSyncUsesTheExactOptionalBidirectionalContract() {
        PinnedPlayChannel accessories =
                ReviewedAtmCompatibility.ACCESSORIES_MAIN_PLAY_SINK;
        byte[] query = query(protocol(1,
                channel(
                        accessories.id(),
                        accessories.version(),
                        Flow.BIDIRECTIONAL,
                        true),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(accessories), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.channels().size());
        assertEquals(accessories.id(), plan.channels().getFirst().id());
        assertEquals(accessories.version(), plan.channels().getFirst().version());
        assertEquals(Flow.BIDIRECTIONAL, plan.channels().getFirst().flow());
        assertTrue(plan.channels().getFirst().optional());
        assertEquals(1, plan.omittedAutomaticChannelCount());

        List<byte[]> divergentContracts = List.of(
                query(protocol(1, channel(
                        accessories.id(), "1.0.1", Flow.BIDIRECTIONAL, true))),
                query(protocol(1, channel(
                        accessories.id(), accessories.version(),
                        Flow.SERVERBOUND, true))),
                query(protocol(1, channel(
                        accessories.id(), accessories.version(),
                        Flow.BIDIRECTIONAL, false))));
        for (byte[] divergent : divergentContracts) {
            PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                    divergent, LIMITS, List.of(accessories), 48);
            assertTrue(rejected.channels().isEmpty());
            assertEquals(1, rejected.ignoredRegistryChannelCount());
        }

        byte[] absent = query(protocol(1,
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));
        assertTrue(PlaySinkPlanner.plan(
                        absent, LIMITS, List.of(accessories), 0)
                .channels()
                .isEmpty());
    }

    @Test
    void aetherPlayerJoinSyncUsesTheExactOptionalBidirectionalContract() {
        PinnedPlayChannel aether =
                ReviewedAtmCompatibility.AETHER_PLAYER_ATTACHMENT_PLAY_SINK;
        byte[] query = query(protocol(1,
                channel(
                        aether.id(),
                        aether.version(),
                        Flow.BIDIRECTIONAL,
                        true),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(aether), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.channels().size());
        assertEquals(aether.id(), plan.channels().getFirst().id());
        assertEquals(aether.version(), plan.channels().getFirst().version());
        assertEquals(Flow.BIDIRECTIONAL, plan.channels().getFirst().flow());
        assertTrue(plan.channels().getFirst().optional());
        assertTrue(plan.clientboundBootstrapChannels().isEmpty());
        assertEquals(1, plan.omittedAutomaticChannelCount());

        List<byte[]> divergentContracts = List.of(
                query(protocol(1, channel(
                        aether.id(), "1.0.1", Flow.BIDIRECTIONAL, true))),
                query(protocol(1, channel(
                        aether.id(), aether.version(), Flow.SERVERBOUND, true))),
                query(protocol(1, channel(
                        aether.id(), aether.version(), Flow.BIDIRECTIONAL, false))));
        for (byte[] divergent : divergentContracts) {
            PlaySinkPlanner.Plan rejected = PlaySinkPlanner.plan(
                    divergent, LIMITS, List.of(aether), 48);
            assertTrue(rejected.channels().isEmpty());
            assertEquals(1, rejected.ignoredRegistryChannelCount());
        }

        byte[] absent = query(protocol(1,
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));
        assertTrue(PlaySinkPlanner.plan(
                        absent, LIMITS, List.of(aether), 0)
                .channels()
                .isEmpty());
    }

    @Test
    void simpleMagnetsPinPreservesTheClientsRequiredBidirectionalContract() {
        PinnedPlayChannel simpleMagnets =
                ReviewedAtmCompatibility.SIMPLE_MAGNETS_MAIN_PLAY_SINK;
        byte[] query = query(protocol(1,
                channel(
                        simpleMagnets.id(),
                        simpleMagnets.version(),
                        Flow.BIDIRECTIONAL,
                        false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(simpleMagnets), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.channels().size());
        assertEquals("simplemagnets:main", plan.channels().getFirst().id());
        assertEquals("1", plan.channels().getFirst().version());
        assertEquals(Flow.BIDIRECTIONAL, plan.channels().getFirst().flow());
        assertFalse(plan.channels().getFirst().optional());
        assertEquals(1, plan.omittedAutomaticChannelCount());
    }

    @Test
    void translocatorsPinPreservesTheClientsRequiredBidirectionalContract() {
        PinnedPlayChannel translocators =
                ReviewedAtmCompatibility.TRANSLOCATORS_NETWORK_PLAY_SINK;
        byte[] query = query(protocol(1,
                channel(
                        translocators.id(),
                        translocators.version(),
                        Flow.BIDIRECTIONAL,
                        false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(translocators), 0);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(1, plan.channels().size());
        assertEquals("translocators:network", plan.channels().getFirst().id());
        assertEquals("2.8.0.89", plan.channels().getFirst().version());
        assertEquals(Flow.BIDIRECTIONAL, plan.channels().getFirst().flow());
        assertFalse(plan.channels().getFirst().optional());
        assertEquals(1, plan.omittedAutomaticChannelCount());
    }

    @Test
    void malformedQueryDoesNotArmIncidentContracts() {
        byte[] query = query(protocol(1,
                channel("curios:open_curios", "future-contract", Flow.SERVERBOUND, false)));
        query[1] = 3;

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, INCIDENT_INTERACTIONS, 48);

        assertEquals(Mode.PINNED_FALLBACK, plan.mode());
        assertTrue(plan.channels().isEmpty());
    }

    @Test
    void automaticBudgetPrioritizesHotkeyTrafficAfterRequiredJoinTraffic() {
        byte[] query = query(protocol(1,
                channel("example:zeta_status", "1", Flow.SERVERBOUND, false),
                channel("placebo:patreon_disable", "1", Flow.BIDIRECTIONAL, false),
                channel("example:optional_input", "1", Flow.SERVERBOUND, true)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(), 1);

        assertEquals(
                List.of("placebo:patreon_disable"),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertEquals(2, plan.omittedAutomaticChannelCount());
    }

    @Test
    void malformedRegistryDoesNotInventSophisticatedComponents() {
        byte[] query = query(protocol(1,
                channel(SOPHISTICATED.id(), SOPHISTICATED.version(), Flow.SERVERBOUND, false)));
        query[1] = 3; // Unsupported ConnectionProtocol, while the component bytes remain intact.

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(SOPHISTICATED, SOPHISTICATED_STORAGE), 48);

        assertEquals(Mode.PINNED_FALLBACK, plan.mode());
        assertTrue(plan.advertisedNamespaces().isEmpty());
        assertTrue(plan.channels().isEmpty());
        assertTrue(plan.fallbackReason().contains("unsupported connection protocol"));
    }

    @Test
    void malformedRegistryDoesNotReflectPinnedConfiguration() {
        byte[] query = query(protocol(1,
                channel("attacker:request_player_settings", "1.0", Flow.SERVERBOUND, false)));
        query[1] = 3;

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(SOPHISTICATED, SOPHISTICATED_STORAGE), 48);

        assertEquals(Mode.PINNED_FALLBACK, plan.mode());
        assertTrue(plan.channels().isEmpty());
    }

    @Test
    void parsedRegistryNeverInventsAReviewedChannelTheClientDidNotAdvertise() {
        byte[] query = query(protocol(1,
                channel("example:client_ready", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, INCIDENT_INTERACTIONS, 1);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(
                List.of("example:client_ready"),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertTrue(plan.channels().stream().noneMatch(channel ->
                INCIDENT_INTERACTIONS.stream().anyMatch(
                        pinned -> pinned.id().equals(channel.id()))));
    }

    @Test
    void incidentSizedMalformedPayloadDoesNotArmLoginSinks() {
        byte[] payload = new byte[66_700];
        payload[0] = 1;
        payload[1] = 3; // Structurally unsupported protocol forces the bounded fallback.

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                payload,
                LIMITS,
                List.of(SOPHISTICATED, SOPHISTICATED_STORAGE),
                48);

        assertEquals(Mode.PINNED_FALLBACK, plan.mode());
        assertTrue(plan.channels().isEmpty());
    }

    @Test
    void lobbyDecoderAndSetupAcceptMinecraftWireStringBeyondForensicPolicy() throws Exception {
        String longId = "longpack:" + "a".repeat(300);
        byte[] query = query(protocol(1,
                channel(longId, "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(query, LIMITS, List.of(), 1);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(List.of("longpack"), plan.advertisedNamespaces());
        assertEquals(List.of(longId),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        byte[] setup = NeoForgeHandshakeCodec.encodeLobbySetup(
                new NeoForgeHandshakeCodec.Registry(Map.of(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL, plan.channels())),
                LIMITS);
        assertTrue(setup.length > longId.length());
    }

    @Test
    void unusualComponentDoesNotEraseLaterStorageLoginChannel() {
        byte[] query = query(protocol(1,
                channel("INVALID:component", "1", Flow.SERVERBOUND, false),
                channel("edgecase:", "1", Flow.SERVERBOUND, false),
                channel(
                        SOPHISTICATED_STORAGE.id(),
                        SOPHISTICATED_STORAGE.version(),
                        Flow.SERVERBOUND,
                        false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(SOPHISTICATED), 1);

        assertEquals(Mode.PARSED, plan.mode());
        assertEquals(2, plan.ignoredRegistryChannelCount());
        assertEquals(
                List.of(SOPHISTICATED_STORAGE.id()),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
    }

    @Test
    void clientboundUtilitarianNamespaceStillProducesServerConfigCandidate() {
        byte[] query = query(protocol(1,
                channel("utilitarian:sync_muffler_data", "1", Flow.CLIENTBOUND, false)));

        PlaySinkPlanner.Plan networkPlan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(), 48);
        TransientServerConfigPlanner.Plan configPlan = TransientServerConfigPlanner.plan(
                List.of(), networkPlan.advertisedNamespaces(), true, 512);

        assertTrue(networkPlan.channels().isEmpty());
        assertEquals(List.of("utilitarian"), networkPlan.advertisedNamespaces());
        assertEquals(List.of("utilitarian-server.toml"), configPlan.configs());
    }

    @Test
    void exactAtm10Normal81ContractNeverSelectsPartialArsEnchantmentTail() {
        byte[] query = query(protocol(1,
                channel("ars_nouveau:reactive_spell", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan networkPlan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(), 0);
        List<RegistryShimPacket> configured = RegistryShimCatalog.resolve(
                RegistryShimCatalog.builtInAtmShimIds().stream()
                        .filter(id -> !id.equals(
                                RegistryShimCatalog.FULL_ENCHANTMENT_ATM10_8_1))
                        .toList(),
                65_536);

        assertTrue(networkPlan.channels().isEmpty());
        assertEquals(List.of("ars_nouveau"), networkPlan.advertisedNamespaces());
        assertEquals(
                List.of(RegistryShimCatalog.NEOVITAE_SENTIENT_ATM10_8_1),
                RegistryShimCatalog.selectForProfile(
                                null,
                                configured,
                                networkPlan.advertisedNamespaces(),
                                Atm10Normal81IronsSpellbooksRegistry
                                        .FULL_CLIENT_CONTRACT_SHA256)
                        .stream().map(RegistryShimPacket::shimId).toList());
        assertTrue(RegistryShimCatalog.selectForProfile(
                null,
                configured,
                networkPlan.advertisedNamespaces(),
                "0".repeat(64)).isEmpty());
    }

    @Test
    void exactApothicClientboundChannelIsNegotiatedOutsideTheSinkBudget() {
        byte[] query = query(protocol(1,
                channel(
                        ApothicEnchantingBootstrapPayload.CHANNEL_ID,
                        ApothicEnchantingBootstrapPayload.CHANNEL_VERSION,
                        Flow.CLIENTBOUND,
                        false),
                channel("example:ordinary_action", "1", Flow.SERVERBOUND, false)));

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(), 0, true);

        assertTrue(plan.channels().isEmpty());
        assertEquals(
                List.of(ApothicEnchantingBootstrapPayload.CHANNEL_ID),
                plan.clientboundBootstrapChannels().stream()
                        .map(NeoForgeHandshakeCodec.Channel::id)
                        .toList());
        assertEquals(
                List.of(ApothicEnchantingBootstrapPayload.CHANNEL_VERSION),
                plan.clientboundBootstrapChannels().stream()
                        .map(NeoForgeHandshakeCodec.Channel::version)
                        .toList());
        assertEquals(
                List.of(Flow.CLIENTBOUND),
                plan.clientboundBootstrapChannels().stream()
                        .map(NeoForgeHandshakeCodec.Channel::flow)
                        .toList());
        assertEquals(1, plan.omittedAutomaticChannelCount());
    }

    @Test
    void apothicBootstrapRequiresTheReviewedVersionAndMayBeDisabled() {
        byte[] wrongVersion = query(protocol(1,
                channel(
                        ApothicEnchantingBootstrapPayload.CHANNEL_ID,
                        "2",
                        Flow.CLIENTBOUND,
                        false)));
        byte[] exactVersion = query(protocol(1,
                channel(
                        ApothicEnchantingBootstrapPayload.CHANNEL_ID,
                        ApothicEnchantingBootstrapPayload.CHANNEL_VERSION,
                        Flow.CLIENTBOUND,
                        false)));

        assertTrue(PlaySinkPlanner.plan(wrongVersion, LIMITS, List.of(), 0, true)
                .clientboundBootstrapChannels().isEmpty());
        assertTrue(PlaySinkPlanner.plan(exactVersion, LIMITS, List.of(), 0, false)
                .clientboundBootstrapChannels().isEmpty());
    }

    @Test
    void apothicBootstrapRequiresTheNonOptionalReviewedContract() {
        byte[] optional = query(protocol(1,
                channel(
                        ApothicEnchantingBootstrapPayload.CHANNEL_ID,
                        ApothicEnchantingBootstrapPayload.CHANNEL_VERSION,
                        Flow.CLIENTBOUND,
                        true)));

        assertTrue(PlaySinkPlanner.plan(optional, LIMITS, List.of(), 0, true)
                .clientboundBootstrapChannels().isEmpty());
    }

    @Test
    void silentGearProfileNegotiatesExactMapsAndAckOutsideAutomaticBudget() {
        byte[] query = query(
                reviewedSilentGearProtocol(), reviewedFrozenRegistryProtocol());

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(), 0, false, true, true);

        assertTrue(plan.silentGearCompatibleChannelsAdvertised());
        assertTrue(plan.frozenRegistryCompatibleChannelsAdvertised());
        assertTrue(plan.silentGearProfileSelected());
        assertEquals(SilentGearProtocol.ATM10_TTS_4_1_3.canonicalContractSha256(),
                plan.silentGearContractSha256());
        assertEquals(List.of(SilentGearProtocol.ACK),
                plan.channels().stream().map(NeoForgeHandshakeCodec.Channel::id).toList());
        assertEquals(SilentGearProtocol.SYNC_CHANNELS,
                plan.clientboundBootstrapChannels().stream()
                        .map(NeoForgeHandshakeCodec.Channel::id)
                        .toList());
    }

    @Test
    void silentGearProfileRequiresTheExactFrozenRegistryConfigurationContract() {
        byte[] query = query(reviewedSilentGearProtocol());

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(), 0, false, true, true);

        assertTrue(plan.silentGearCompatibleChannelsAdvertised());
        assertFalse(plan.frozenRegistryCompatibleChannelsAdvertised());
        assertFalse(plan.silentGearProfileSelected());
        assertTrue(plan.silentGearRejectionReason().contains("frozen-registry"));
        assertTrue(plan.clientboundBootstrapChannels().isEmpty());
    }

    @Test
    void silentGearClientboundMapsFailClosedWithoutReviewedEmbeddedProfile() {
        byte[] query = query(reviewedSilentGearProtocol());

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query, LIMITS, List.of(), 0, false, true, false);

        assertTrue(plan.silentGearCompatibleChannelsAdvertised());
        assertTrue(plan.channels().isEmpty());
        assertTrue(plan.clientboundBootstrapChannels().isEmpty());
    }

    @Test
    void silentGearProfileFailsClosedWhenAnyReviewedChannelIsMissing() {
        List<NeoForgeHandshakeCodec.Channel> reviewed = new java.util.ArrayList<>(
                SilentGearProtocol.reviewedPlayContract());
        reviewed.removeLast();
        TestChannel[] incomplete = reviewed.stream()
                .map(channel -> channel(
                        channel.id(), channel.version(), channel.flow(), channel.optional()))
                .toArray(TestChannel[]::new);

        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                query(protocol(1, incomplete)),
                LIMITS,
                List.of(),
                0,
                false,
                true,
                true);

        assertFalse(plan.silentGearCompatibleChannelsAdvertised());
        assertFalse(plan.silentGearProfileSelected());
        assertTrue(plan.clientboundBootstrapChannels().isEmpty());
    }

    @Test
    void randomUntrustedRegistriesRemainBoundedAndKeepOnlyTrustedFallback() {
        Random random = new Random(0x70_07_10L);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            byte[] payload = new byte[random.nextInt(513)];
            random.nextBytes(payload);

            PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                    payload,
                    LIMITS,
                    List.of(SOPHISTICATED, SOPHISTICATED_STORAGE),
                    48);

            assertTrue(plan.channels().size() <= 50);
            if (plan.mode() == Mode.PINNED_FALLBACK) {
                assertTrue(plan.channels().isEmpty());
            } else {
                assertTrue(plan.channels().stream().noneMatch(channel ->
                        channel.id().equals(SOPHISTICATED.id())
                                || channel.id().equals(SOPHISTICATED_STORAGE.id())));
            }
        }
    }

    private static byte[] query(Protocol... protocols) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarInt(output, protocols.length);
        for (Protocol protocol : protocols) {
            writeVarInt(output, protocol.ordinal());
            writeVarInt(output, protocol.channels().length);
            for (TestChannel channel : protocol.channels()) {
                writeString(output, channel.id());
                writeString(output, channel.version());
                if (channel.flow() == Flow.BIDIRECTIONAL) {
                    output.write(0);
                } else {
                    output.write(1);
                    writeVarInt(output, channel.flow().wireOrdinal());
                }
                output.write(channel.optional() ? 1 : 0);
            }
        }
        return output.toByteArray();
    }

    private static Protocol protocol(int ordinal, TestChannel... channels) {
        return new Protocol(ordinal, channels);
    }

    private static Protocol reviewedSilentGearProtocol() {
        TestChannel[] channels = SilentGearProtocol.reviewedPlayContract().stream()
                .map(channel -> channel(
                        channel.id(), channel.version(), channel.flow(), channel.optional()))
                .toArray(TestChannel[]::new);
        return protocol(1, channels);
    }

    private static Protocol reviewedFrozenRegistryProtocol() {
        TestChannel[] channels = NeoForgeFrozenRegistryProfile
                .reviewedConfigurationChannels().stream()
                .map(channel -> channel(
                        channel.id(), channel.version(), channel.flow(), channel.optional()))
                .toArray(TestChannel[]::new);
        return protocol(4, channels);
    }

    private static TestChannel channel(
            String id, String version, Flow flow, boolean optional) {
        return new TestChannel(id, version, flow, optional);
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, encoded.length);
        output.writeBytes(encoded);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        do {
            int next = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                next |= 0x80;
            }
            output.write(next);
        } while (remaining != 0);
    }

    private record Protocol(int ordinal, TestChannel[] channels) {
    }

    private record TestChannel(String id, String version, Flow flow, boolean optional) {
    }
}
