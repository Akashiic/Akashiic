package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannelReason;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeConfigPath;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class ReviewedAtmCompatibilityTest {
    @Test
    void beta15InteractionsRequireEveryAtm10Normal80IdentityDimension() throws Exception {
        Registry exactRegistry = baseRegistry();
        ChannelContractSignature.Signatures exact = ChannelContractSignature.from(exactRegistry);
        ChannelContractSignature.Signatures normal73 = new ChannelContractSignature.Signatures(
                ReviewedClientContractEvidence.ATM10_NORMAL_7_3.fullClientContractSha256(),
                exact.silentGearContractSha256());
        ChannelContractSignature.Signatures wrongSilentGear =
                new ChannelContractSignature.Signatures(
                        exact.fullContractSha256(),
                        "1" + exact.silentGearContractSha256().substring(1));

        PinnedPlayChannel eternal =
                ReviewedAtmCompatibility.ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK;
        Channel exactEternal = new Channel(
                eternal.id(), eternal.version(), Flow.BIDIRECTIONAL, true);
        assertTrue(matchesReviewedPinnedContract(
                eternal, exactEternal, exactRegistry, exact));
        assertFalse(matchesReviewedPinnedContract(
                eternal, exactEternal, exactRegistry, normal73));
        assertFalse(matchesReviewedPinnedContract(
                eternal, exactEternal, exactRegistry, wrongSilentGear));
        assertFalse(matchesReviewedPinnedContract(
                eternal,
                new Channel(eternal.id(), "changed", Flow.BIDIRECTIONAL, true),
                exactRegistry,
                exact));
        assertFalse(matchesReviewedPinnedContract(
                eternal,
                new Channel(eternal.id(), eternal.version(), Flow.SERVERBOUND, true),
                exactRegistry,
                exact));
        assertFalse(matchesReviewedPinnedContract(
                eternal,
                new Channel(eternal.id(), eternal.version(), Flow.BIDIRECTIONAL, false),
                exactRegistry,
                exact));

        PinnedPlayChannel relics = ReviewedAtmCompatibility.RELICS_SHIELD_RELEASE_PLAY_SINK;
        Channel exactRelics = new Channel(
                relics.id(), relics.version(), Flow.SERVERBOUND, true);
        assertTrue(matchesReviewedPinnedContract(
                relics, exactRelics, exactRegistry, exact));
        assertFalse(matchesReviewedPinnedContract(
                relics, exactRelics, exactRegistry, normal73));
        assertFalse(matchesReviewedPinnedContract(
                relics,
                new Channel(relics.id(), relics.version(), Flow.BIDIRECTIONAL, true),
                exactRegistry,
                exact));
        assertFalse(matchesReviewedPinnedContract(
                relics,
                new Channel(relics.id(), relics.version(), Flow.SERVERBOUND, false),
                exactRegistry,
                exact));
    }

    @Test
    void profileSpecificPinsAcceptEveryStructurallyReviewedNormal80Identity() throws Exception {
        List<ReviewedPin> reviewedPins = List.of(
                new ReviewedPin(
                        ReviewedAtmCompatibility.ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK,
                        Flow.BIDIRECTIONAL),
                new ReviewedPin(
                        ReviewedAtmCompatibility.RELICS_SHIELD_RELEASE_PLAY_SINK,
                        Flow.SERVERBOUND),
                new ReviewedPin(
                        ReviewedAtmCompatibility.TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK,
                        Flow.BIDIRECTIONAL));

        Registry base = baseRegistry();
        List<Registry> registries = List.of(
                base,
                withoutWorldEditCui(base),
                withSimpleVoiceChat(base),
                withSimpleVoiceChat(withoutWorldEditCui(base)));
        for (Registry registry : registries) {
            ChannelContractSignature.Signatures signatures =
                    ChannelContractSignature.from(registry);
            assertTrue(ReviewedClientContractEvidence.matchAtm10Normal80(
                            767, registry, signatures)
                    .isPresent());
            for (ReviewedPin reviewed : reviewedPins) {
                Channel advertised = new Channel(
                        reviewed.pinned().id(),
                        reviewed.pinned().version(),
                        reviewed.flow(),
                        true);
                assertTrue(matchesReviewedPinnedContract(
                        reviewed.pinned(), advertised, registry, signatures));
                assertFalse(ReviewedAtmCompatibility.matchesReviewedPinnedContract(
                        766, reviewed.pinned(), advertised, registry, signatures));
            }
        }

        ChannelContractSignature.Signatures unknown = new ChannelContractSignature.Signatures(
                "f".repeat(64),
                ChannelContractSignature.from(base).silentGearContractSha256());
        for (ReviewedPin reviewed : reviewedPins) {
            assertFalse(matchesReviewedPinnedContract(
                    reviewed.pinned(),
                    new Channel(
                            reviewed.pinned().id(),
                            reviewed.pinned().version(),
                            reviewed.flow(),
                            true),
                    base,
                    unknown));
        }

        Registry alteredIgnoredComponent = new Registry(
                base.protocols(),
                base.declaredChannelCount(),
                1,
                List.of(new IgnoredChannel(
                        br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec
                                .PLAY_PROTOCOL,
                        "ae2:",
                        "altered",
                        Flow.CLIENTBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)));
        ChannelContractSignature.Signatures alteredSignatures =
                ChannelContractSignature.from(alteredIgnoredComponent);
        assertEquals(ChannelContractSignature.from(base), alteredSignatures);
        for (ReviewedPin reviewed : reviewedPins) {
            assertFalse(matchesReviewedPinnedContract(
                    reviewed.pinned(),
                    new Channel(
                            reviewed.pinned().id(),
                            reviewed.pinned().version(),
                            reviewed.flow(),
                            true),
                    alteredIgnoredComponent,
                    alteredSignatures));
        }
    }

    @Test
    void mekanismBundleMatchesTheReviewedBaseGeneratorsAndToolsServerConfigs() {
        assertEquals(
                List.of(
                        "Mekanism/general.toml",
                        "Mekanism/gear.toml",
                        "Mekanism/machine-storage.toml",
                        "Mekanism/tiers.toml",
                        "Mekanism/machine-usage.toml",
                        "Mekanism/world.toml",
                        "Mekanism/generators.toml",
                        "Mekanism/generators-gear.toml",
                        "Mekanism/generator-storage.toml",
                        "Mekanism/tools.toml"),
                ReviewedAtmCompatibility.MEKANISM_SERVER_CONFIGS);
        assertEquals(
                ReviewedAtmCompatibility.MEKANISM_SERVER_CONFIGS.size(),
                ReviewedAtmCompatibility.MEKANISM_SERVER_CONFIGS.stream().distinct().count());
        assertTrue(ReviewedAtmCompatibility.MEKANISM_SERVER_CONFIGS.stream()
                .allMatch(NeoForgeConfigPath::isValid));
    }

    @Test
    void mekanismKeySinkUsesTheExactReviewedNetworkVersion() {
        assertEquals("1.21.1-10.7.18.84",
                ReviewedAtmCompatibility.MEKANISM_ARTIFACT_VERSION);
        assertEquals("10.7.18", ReviewedAtmCompatibility.MEKANISM_NETWORK_VERSION);
        assertEquals(
                new PinnedPlayChannel("mekanism:key", "10.7.18"),
                ReviewedAtmCompatibility.MEKANISM_KEY_PLAY_SINK);
    }

    @Test
    void xyCraftModifierKeySinkUsesTheExactReviewedNetworkVersion() {
        assertEquals("0.7.53", ReviewedAtmCompatibility.XYCRAFT_CORE_MOD_VERSION);
        assertEquals("1.0.0", ReviewedAtmCompatibility.XYCRAFT_CORE_NETWORK_VERSION);
        assertEquals(
                new PinnedPlayChannel("xycraft_core:modifier_key", "1.0.0"),
                ReviewedAtmCompatibility.XYCRAFT_MODIFIER_KEY_PLAY_SINK);
    }

    @Test
    void sophisticatedBackpacksInteractionsUseTheReviewedFallbackContract() {
        assertEquals(
                "1.21.1-3.25.34.1604",
                ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_ARTIFACT_VERSION);
        assertEquals(
                "1.0",
                ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_NETWORK_VERSION);
        assertEquals(
                new PinnedPlayChannel("sophisticatedbackpacks:backpack_open", "1.0"),
                ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_OPEN_PLAY_SINK);
        assertEquals(
                new PinnedPlayChannel(
                        "sophisticatedbackpacks:another_player_backpack_open", "1.0"),
                ReviewedAtmCompatibility
                        .SOPHISTICATED_BACKPACKS_ANOTHER_PLAYER_BACKPACK_OPEN_PLAY_SINK);
        assertEquals(
                Integer.BYTES,
                ReviewedAtmCompatibility
                        .SOPHISTICATED_BACKPACKS_ANOTHER_PLAYER_OPEN_PAYLOAD_BYTES);
        assertEquals(
                new PinnedPlayChannel("sophisticatedbackpacks:block_pick", "1.0"),
                ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_BLOCK_PICK_PLAY_SINK);
    }

    @Test
    void kubeJsKubedexRequestUsesItsOptionalVersionOneContract() {
        assertEquals("2101.7.2-build.348",
                ReviewedAtmCompatibility.KUBEJS_ARTIFACT_VERSION);
        assertEquals("1", ReviewedAtmCompatibility.KUBEJS_NETWORK_VERSION);
        assertEquals(
                new PinnedPlayChannel("kubejs:kubedex/request_block", "1"),
                ReviewedAtmCompatibility.KUBEJS_KUBEDEX_REQUEST_BLOCK_PLAY_SINK);
    }

    @Test
    void attachedCrashInteractionsUseTheirOwningModsExactReviewedContracts() {
        assertEquals("19.4.1",
                ReviewedAtmCompatibility.AE2_WIRELESS_TERMINAL_LIBRARY_ARTIFACT_VERSION);
        assertEquals("1.21.1-3.1.4.632",
                ReviewedAtmCompatibility.DRACONIC_EVOLUTION_ARTIFACT_VERSION);
        assertEquals("1.21.1-3.2.1.309",
                ReviewedAtmCompatibility.BRANDONS_CORE_ARTIFACT_VERSION);
        assertEquals("9.5.1+1.21.1", ReviewedAtmCompatibility.CURIOS_ARTIFACT_VERSION);
        assertEquals("1.21.1-v1-neoforge",
                ReviewedAtmCompatibility.COSMETIC_ARMOR_REWORKED_ARTIFACT_VERSION);
        assertEquals("2101.1.9", ReviewedAtmCompatibility.FTB_TEAMS_ARTIFACT_VERSION);
        assertEquals("1.21-7.0.3",
                ReviewedAtmCompatibility.NOT_ENOUGH_WANDS_ARTIFACT_VERSION);
        assertEquals("2101.1.13",
                ReviewedAtmCompatibility.FTB_ULTIMINE_ARTIFACT_VERSION);
        assertEquals("ftbultimine",
                ReviewedAtmCompatibility.FTB_ULTIMINE_NETWORK_VERSION);
        assertEquals("1.21.1-3.5.0.155",
                ReviewedAtmCompatibility.CB_MULTIPART_ARTIFACT_VERSION);
        assertEquals("1.21.1-4.6.1.526",
                ReviewedAtmCompatibility.CODE_CHICKEN_LIB_ARTIFACT_VERSION);
        assertEquals("3.5.0.155",
                ReviewedAtmCompatibility.CB_MULTIPART_NETWORK_VERSION);
        assertEquals("1.21-9.0.20",
                ReviewedAtmCompatibility.MCJTYLIB_ARTIFACT_VERSION);
        assertEquals("1.21-7.0.4",
                ReviewedAtmCompatibility.RFTOOLS_BUILDER_ARTIFACT_VERSION);
        assertEquals("1.0",
                ReviewedAtmCompatibility.MCJTYLIB_NETWORK_VERSION);
        assertEquals("1.21.1-2.8.0.89",
                ReviewedAtmCompatibility.TRANSLOCATORS_ARTIFACT_VERSION);
        assertEquals("2.8.0.89",
                ReviewedAtmCompatibility.TRANSLOCATORS_NETWORK_VERSION);
        assertEquals("1.1.12c-neoforge-mc1.21",
                ReviewedAtmCompatibility.SIMPLE_MAGNETS_ARTIFACT_VERSION);
        assertEquals("1.1.12+c",
                ReviewedAtmCompatibility.SIMPLE_MAGNETS_MOD_VERSION);
        assertEquals("1.1.21-neoforge-mc1.21",
                ReviewedAtmCompatibility.SUPERMARTIJN642_CORE_LIB_ARTIFACT_VERSION);
        assertEquals("1",
                ReviewedAtmCompatibility.SIMPLE_MAGNETS_NETWORK_VERSION);
        assertEquals("1.1.0-beta.48+1.21.1",
                ReviewedAtmCompatibility.ACCESSORIES_ARTIFACT_VERSION);
        assertEquals("1.0.0",
                ReviewedAtmCompatibility.ACCESSORIES_NETWORK_VERSION);
        assertEquals("1.21.1-1.5.10-neoforge",
                ReviewedAtmCompatibility.AETHER_ARTIFACT_VERSION);
        assertEquals("1.21.1-1.1.25-neoforge",
                ReviewedAtmCompatibility.NITROGEN_ARTIFACT_VERSION);
        assertEquals("1.0.0",
                ReviewedAtmCompatibility.AETHER_PLAYER_ATTACHMENT_NETWORK_VERSION);
        assertEquals("1.21.1-v1.36.27",
                ReviewedAtmCompatibility.MAHOU_TSUKAI_ARTIFACT_VERSION);
        assertEquals("1", ReviewedAtmCompatibility.MAHOU_TSUKAI_NETWORK_VERSION);
        assertEquals(8, ReviewedAtmCompatibility.MAHOU_TSUKAI_CHUNK_REQUEST_BYTES);
        assertEquals("4.0.6",
                ReviewedAtmCompatibility.CREEPER_OVERHAUL_ARTIFACT_VERSION);
        assertEquals("1.21-3.0.12",
                ReviewedAtmCompatibility.RESOURCEFUL_LIB_ARTIFACT_VERSION);
        assertEquals("v1",
                ReviewedAtmCompatibility.CREEPER_OVERHAUL_NETWORK_VERSION);
        assertEquals(1, ReviewedAtmCompatibility.CREEPER_OVERHAUL_COSMETIC_BYTES);
        assertEquals("0.8.1+1.21.1+neoforge",
                ReviewedAtmCompatibility.ETERNAL_STARLIGHT_ARTIFACT_VERSION);
        assertEquals("eternal_starlight",
                ReviewedAtmCompatibility.ETERNAL_STARLIGHT_NETWORK_VERSION);
        assertEquals(
                "acb8fa7a69c4f7d4f1dfa3fba1dbdc96976478ea80b2f9025e43f0c4ee1ceb19",
                ReviewedAtmCompatibility.ETERNAL_STARLIGHT_JAR_SHA256);
        assertEquals("1.21.1-0.12.8", ReviewedAtmCompatibility.RELICS_ARTIFACT_VERSION);
        assertEquals("1.0", ReviewedAtmCompatibility.RELICS_NETWORK_VERSION);
        assertEquals(
                "db6f436053fe717413389e55390a5f98103ccd91cca5fe469e090bdfaef70717",
                ReviewedAtmCompatibility.RELICS_JAR_SHA256);
        assertEquals("1.21.1-1.5.23",
                ReviewedAtmCompatibility.ENDER_DRIVES_ARTIFACT_VERSION);
        assertEquals("1.5.23", ReviewedAtmCompatibility.ENDER_DRIVES_MOD_VERSION);
        assertEquals("1.0", ReviewedAtmCompatibility.ENDER_DRIVES_NETWORK_VERSION);
        assertEquals(
                "5266b6c871c5e0d0cd355ab09205d415727de9563ff51204b93633bfefc14ae6",
                ReviewedAtmCompatibility.ENDER_DRIVES_JAR_SHA256);
        assertEquals("1.21.1-4.8.3345",
                ReviewedAtmCompatibility.TWILIGHT_FOREST_ARTIFACT_VERSION);
        assertEquals("4.8.3345", ReviewedAtmCompatibility.TWILIGHT_FOREST_MOD_VERSION);
        assertEquals("1.0.0",
                ReviewedAtmCompatibility.TWILIGHT_FOREST_NETWORK_VERSION);
        assertEquals(
                "493da16d10210f9f53a3c33b3d8fcb4ae9b14016ee420d753c894562bd9aba35",
                ReviewedAtmCompatibility.TWILIGHT_FOREST_JAR_SHA256);
        assertEquals("1.21-1.3.4",
                ReviewedAtmCompatibility.DEEPER_DARKER_ATM10_NORMAL_7_3_ARTIFACT_VERSION);
        assertEquals("1.21.1-1.4.1",
                ReviewedAtmCompatibility.DEEPER_DARKER_ATM10_NORMAL_8_0_ARTIFACT_VERSION);
        assertEquals("1.0", ReviewedAtmCompatibility.DEEPER_DARKER_NETWORK_VERSION);
        assertEquals(
                "eee3f51222b0bcc714def002ff089ac9e131d3cae4575b542fd0a7dd101fe0af",
                ReviewedAtmCompatibility.DEEPER_DARKER_1_4_1_JAR_SHA256);
        assertEquals("1.21.1-2.2.10",
                ReviewedAtmCompatibility.TOOL_BELT_ARTIFACT_VERSION);
        assertEquals("2.2.10", ReviewedAtmCompatibility.TOOL_BELT_MOD_VERSION);
        assertEquals("1.0", ReviewedAtmCompatibility.TOOL_BELT_NETWORK_VERSION);
        assertEquals(
                "918b35093dfaf8033115acd8dacd8978e325dcba36355f8d1a938ee92d5ab4ff",
                ReviewedAtmCompatibility.TOOL_BELT_2_2_10_JAR_SHA256);
        assertEquals(
                "1f59485e890959e18fde12db5a62a36a997d76d5",
                ReviewedAtmCompatibility.TOOL_BELT_2_2_10_SOURCE_SHA);
        assertEquals("1.21.1-1.13.0",
                ReviewedAtmCompatibility.LOGISTICS_NETWORKS_ARTIFACT_VERSION);
        assertEquals("1",
                ReviewedAtmCompatibility.LOGISTICS_NETWORKS_NETWORK_VERSION);
        assertEquals(
                "9ab7ef827103f6e43012c5d3a54c58cd85414616",
                ReviewedAtmCompatibility.LOGISTICS_NETWORKS_SOURCE_COMMIT);
        assertEquals(
                "dbe5e66b64290046da47a0f718d024d99d22b023",
                ReviewedAtmCompatibility.LOGISTICS_NETWORKS_REGISTRATION_SOURCE_SHA);
        assertEquals(
                "c802dbe91a8aeb0e9eed5dd9593a1e27e9e3209e",
                ReviewedAtmCompatibility.LOGISTICS_NETWORKS_DEFAULT_VISIBILITY_SOURCE_SHA);
        assertEquals(
                "fe74a1c60d203e7ce7fe572a604248fd0992c119",
                ReviewedAtmCompatibility.LOGISTICS_NETWORKS_LOGIN_HANDLER_SOURCE_SHA);
        assertEquals("1.21.1-3.16.2",
                ReviewedAtmCompatibility.IRONS_SPELLBOOKS_ARTIFACT_VERSION);
        assertEquals("1.0.0",
                ReviewedAtmCompatibility.IRONS_SPELLBOOKS_NETWORK_VERSION);
        assertEquals(
                "82b650aff7636c8fa88da0e4cfea008c229bb62843274678ee932f6b4ec74430",
                ReviewedAtmCompatibility.IRONS_SPELLBOOKS_3_16_2_JAR_SHA256);
        assertEquals(
                "8e233f5d34d57d1f7237ef2ff5d76017bf08c616",
                ReviewedAtmCompatibility.IRONS_SPELLBOOKS_3_16_2_SOURCE_COMMIT);
        assertEquals(
                "1e06f129cd96d5de3dae1244c13a7f0f1d58efe5",
                ReviewedAtmCompatibility.IRONS_SPELLBOOKS_CAST_SOURCE_SHA);
        assertEquals(
                "41ca54310a02cc772072b952b467e783b017412a",
                ReviewedAtmCompatibility.IRONS_SPELLBOOKS_PAYLOAD_HANDLER_SOURCE_SHA);
        assertEquals(
                List.of(
                        new PinnedPlayChannel("ae2wtlib:pick_block", "ae2wtlib"),
                        new PinnedPlayChannel("draconicevolution:network", "3.2.1.309"),
                        new PinnedPlayChannel("curios:open_curios", "1.0"),
                        new PinnedPlayChannel(
                                "cosmeticarmorreworked:open_cosarmor_inv", "5"),
                        new PinnedPlayChannel("ftbteams:open_gui", "ftbteams"),
                        new PinnedPlayChannel(
                                "notenoughwands:getprotectedblockcount", "1.0"),
                        new PinnedPlayChannel(
                                "ftbultimine:key_pressed_packet", "ftbultimine"),
                        new PinnedPlayChannel(
                                "ftbultimine:mode_changed_packet", "ftbultimine"),
                        new PinnedPlayChannel("cb_multipart:network", "3.5.0.155"),
                        new PinnedPlayChannel("mcjtylib:sendservercommand", "1.0"),
                        new PinnedPlayChannel("translocators:network", "2.8.0.89"),
                        new PinnedPlayChannel("simplemagnets:main", "1"),
                        new PinnedPlayChannel(
                                "logisticsnetworks:set_default_node_visibility", "1"),
                        // HF3: ATM10 8.2 lineage (LogisticsNetworks 1.16.3, network v9).
                        new PinnedPlayChannel(
                                "logisticsnetworks:sync_modifier_keys", "9"),
                        new PinnedPlayChannel(
                                "structurize:notify_server_about_structure_packs",
                                "1.0.832-1.21.1"),
                        new PinnedPlayChannel(
                                "refinedstorage:set_tenth_anniversary_cape", "2.0.9"),
                        new PinnedPlayChannel("accessories:main", "1.0.0"),
                        new PinnedPlayChannel(
                                "aether:sync_aether_player_attachment", "1.0.0"),
                        new PinnedPlayChannel(
                                "mahoutsukai:chunk_mahou_request_packet", "1"),
                        new PinnedPlayChannel(
                                "creeperoverhaul:main/v1/creeperoverhaul/set_cosmetic", "v1"),
                        new PinnedPlayChannel(
                                "eternal_starlight:update_book_progression",
                                "eternal_starlight"),
                        new PinnedPlayChannel(
                                "eternal_starlight:simple_action", "eternal_starlight"),
                        new PinnedPlayChannel(
                                "relics:shield_of_retaliation/release", "1.0"),
                        new PinnedPlayChannel(
                                "enderdrives:request_disk_type_count", "1.0"),
                        new PinnedPlayChannel(
                                "enderdrives:request_fluid_disk_type_count", "1.0"),
                        new PinnedPlayChannel(
                                "twilightforest:gradual_glide_packet", "1.0.0"),
                        new PinnedPlayChannel(
                                "deeperdarker:use_transmitter", "1.0"),
                        new PinnedPlayChannel(
                                "toolbelt:open_belt_slot_inventory", "1.0"),
                        new PinnedPlayChannel(
                                "irons_spellbooks:cast", "1.0.0")),
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS);
        assertEquals(
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.size(),
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.stream()
                        .map(PinnedPlayChannel::id)
                        .distinct()
                        .count());
        assertTrue(ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.stream()
                .map(PinnedPlayChannel::id)
                .noneMatch(id -> id.equals("enderdrives:update_disk_type_count")
                        || id.equals("enderdrives:update_fluid_disk_type_count")));
    }

    @Test
    void bytecodeAuditedPayloadsRequireTheirExactBodySizes() {
        String logisticsDefaultVisibility = ReviewedAtmCompatibility
                .LOGISTICS_NETWORKS_DEFAULT_VISIBILITY_PLAY_SINK.id();
        String mahou = ReviewedAtmCompatibility.MAHOU_TSUKAI_CHUNK_REQUEST_PLAY_SINK.id();
        String creeper = ReviewedAtmCompatibility.CREEPER_OVERHAUL_COSMETIC_PLAY_SINK.id();
        String gradualGlide = ReviewedAtmCompatibility
                .TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK.id();
        String eternalSimpleAction = ReviewedAtmCompatibility
                .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK.id();
        String relicsShieldRelease = ReviewedAtmCompatibility
                .RELICS_SHIELD_RELEASE_PLAY_SINK.id();
        String deeperDarkerTransmitter = ReviewedAtmCompatibility
                .DEEPER_DARKER_USE_TRANSMITTER_PLAY_SINK.id();
        String toolBeltOpenSlot = ReviewedAtmCompatibility
                .TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK.id();
        String ironsSpellbooksCast = ReviewedAtmCompatibility
                .IRONS_SPELLBOOKS_CAST_PLAY_SINK.id();

        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                logisticsDefaultVisibility, 1));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                logisticsDefaultVisibility, 0));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                logisticsDefaultVisibility, 2));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                logisticsDefaultVisibility, new byte[] {0}));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                logisticsDefaultVisibility, new byte[] {1}));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                logisticsDefaultVisibility, new byte[] {2}));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(mahou, 8));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(mahou, 7));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(mahou, 9));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(creeper, 1));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(creeper, 0));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(creeper, 2));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(gradualGlide, 17));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(gradualGlide, 16));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(gradualGlide, 18));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                eternalSimpleAction, 13));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                eternalSimpleAction, 12));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                eternalSimpleAction, 14));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                relicsShieldRelease, 1));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                relicsShieldRelease, 0));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                relicsShieldRelease, 2));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                deeperDarkerTransmitter, 1));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                deeperDarkerTransmitter, 0));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                deeperDarkerTransmitter, 2));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                toolBeltOpenSlot, 0));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                toolBeltOpenSlot, 1));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                ironsSpellbooksCast, 0));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayloadBytes(
                ironsSpellbooksCast, 1));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayloadBytes("example:other", 0));

        String eternal =
                ReviewedAtmCompatibility.ETERNAL_STARLIGHT_BOOK_PROGRESSION_PLAY_SINK.id();
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                eternal, new byte[] {0, 0, 0, 0}));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                eternal, new byte[] {0, 0, 0, 0, 1}));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                eternalSimpleAction,
                new byte[] {
                    12, 's', 'w', 'i', 'n', 'g', '_', 'a', 't', 't', 'a', 'c', 'k'
                }));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                eternalSimpleAction,
                new byte[] {
                    12, 'c', 'l', 'e', 'a', 'r', '_', 'w', 'e', 'a', 't', 'h', 'e'
                }));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                relicsShieldRelease, new byte[] {1}));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                relicsShieldRelease, new byte[] {0}));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                deeperDarkerTransmitter, new byte[] {1}));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                deeperDarkerTransmitter, new byte[] {0}));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                deeperDarkerTransmitter, new byte[] {2}));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                toolBeltOpenSlot, new byte[0]));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                toolBeltOpenSlot, new byte[] {0}));
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                ironsSpellbooksCast, new byte[0]));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                ironsSpellbooksCast, new byte[] {0}));

        byte[] enderDrivesRequest = new byte[] {
            6, 'g', 'l', 'o', 'b', 'a', 'l', 0, 63
        };
        for (PinnedPlayChannel request : List.of(
                ReviewedAtmCompatibility.ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK,
                ReviewedAtmCompatibility.ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK)) {
            assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                    request.id(), enderDrivesRequest));
            assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                    request.id(), new byte[] {0, 0}));
        }
    }

    private record ReviewedPin(PinnedPlayChannel pinned, Flow flow) {
    }

    private static boolean matchesReviewedPinnedContract(
            PinnedPlayChannel pinned,
            Channel advertised,
            Registry registry,
            ChannelContractSignature.Signatures signatures) {
        return ReviewedAtmCompatibility.matchesReviewedPinnedContract(
                767, pinned, advertised, registry, signatures);
    }

    private static Registry baseRegistry() throws Exception {
        try (InputStream stream = ReviewedAtmCompatibilityTest.class
                .getClassLoader()
                .getResourceAsStream("atm10-normal/8.0/client-neoforge-response.bin")) {
            if (stream == null) {
                throw new IllegalStateException("missing Normal 8.0 query fixture");
            }
            return br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec
                    .decodeLobbyQuery(stream.readAllBytes(), ProtocolLimits.productionDefaults());
        }
    }

    private static Registry withSimpleVoiceChat(Registry registry) {
        return mutatePlay(registry, channels -> {
            ArrayList<Channel> result = new ArrayList<>(channels);
            result.addAll(ReviewedSimpleVoiceChatExtension.channels());
            return List.copyOf(result);
        });
    }

    private static Registry withoutWorldEditCui(Registry registry) {
        return mutatePlay(registry, channels -> channels.stream()
                .filter(channel -> !channel.id().equals("worldedit:cui"))
                .toList());
    }

    private static Registry mutatePlay(
            Registry registry, UnaryOperator<List<Channel>> mutation) {
        Map<Integer, List<Channel>> protocols = new LinkedHashMap<>(registry.protocols());
        protocols.put(
                br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                List.copyOf(mutation.apply(new ArrayList<>(registry.channelsFor(
                        br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec
                                .PLAY_PROTOCOL)))));
        int usable = protocols.values().stream().mapToInt(List::size).sum();
        return new Registry(
                protocols,
                usable + registry.ignoredChannelCount(),
                registry.ignoredChannelCount(),
                registry.ignoredChannels());
    }
}
