package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact compatibility facts reviewed against the affected ATM10 releases. */
final class ReviewedAtmCompatibility {
    static final String PLACEBO_MOD_VERSION = "9.9.1";
    static final PinnedPlayChannel PLACEBO_PATREON_PLAY_SINK =
            new PinnedPlayChannel("placebo:patreon_disable", "1");

    static final String XYCRAFT_CORE_MOD_VERSION = "0.7.53";
    static final String XYCRAFT_CORE_NETWORK_VERSION = "1.0.0";
    static final PinnedPlayChannel XYCRAFT_MODIFIER_KEY_PLAY_SINK =
            new PinnedPlayChannel(
                    "xycraft_core:modifier_key", XYCRAFT_CORE_NETWORK_VERSION);

    static final String SOPHISTICATED_BACKPACKS_ARTIFACT_VERSION =
            "1.21.1-3.25.34.1604";
    static final String SOPHISTICATED_BACKPACKS_NETWORK_VERSION = "1.0";
    static final PinnedPlayChannel SOPHISTICATED_BACKPACKS_OPEN_PLAY_SINK =
            new PinnedPlayChannel(
                    "sophisticatedbackpacks:backpack_open",
                    SOPHISTICATED_BACKPACKS_NETWORK_VERSION);
    static final PinnedPlayChannel
            SOPHISTICATED_BACKPACKS_ANOTHER_PLAYER_BACKPACK_OPEN_PLAY_SINK =
                    new PinnedPlayChannel(
                            "sophisticatedbackpacks:another_player_backpack_open",
                            SOPHISTICATED_BACKPACKS_NETWORK_VERSION);
    static final int SOPHISTICATED_BACKPACKS_ANOTHER_PLAYER_OPEN_PAYLOAD_BYTES =
            Integer.BYTES;
    static final PinnedPlayChannel SOPHISTICATED_BACKPACKS_BLOCK_PICK_PLAY_SINK =
            new PinnedPlayChannel(
                    "sophisticatedbackpacks:block_pick",
                    SOPHISTICATED_BACKPACKS_NETWORK_VERSION);

    static final String LOGISTICS_NETWORKS_ARTIFACT_VERSION = "1.21.1-1.13.0";
    static final String LOGISTICS_NETWORKS_NETWORK_VERSION = "1";
    static final String LOGISTICS_NETWORKS_SOURCE_COMMIT =
            "9ab7ef827103f6e43012c5d3a54c58cd85414616";
    static final String LOGISTICS_NETWORKS_REGISTRATION_SOURCE_SHA =
            "dbe5e66b64290046da47a0f718d024d99d22b023";
    static final String LOGISTICS_NETWORKS_DEFAULT_VISIBILITY_SOURCE_SHA =
            "c802dbe91a8aeb0e9eed5dd9593a1e27e9e3209e";
    static final String LOGISTICS_NETWORKS_LOGIN_HANDLER_SOURCE_SHA =
            "fe74a1c60d203e7ce7fe572a604248fd0992c119";
    static final PinnedPlayChannel LOGISTICS_NETWORKS_DEFAULT_VISIBILITY_PLAY_SINK =
            new PinnedPlayChannel(
                    "logisticsnetworks:set_default_node_visibility",
                    LOGISTICS_NETWORKS_NETWORK_VERSION);
    static final PinnedPlayChannel LOGISTICS_NETWORKS_MODIFIER_KEYS_ATM10_82_PLAY_SINK =
            new PinnedPlayChannel(
                    Atm10Normal82Contract.LOGISTICS_CHANNEL_ID,
                    Atm10Normal82Contract.LOGISTICS_NETWORK_VERSION);


    static final String KUBEJS_ARTIFACT_VERSION = "2101.7.2-build.348";
    static final String KUBEJS_NETWORK_VERSION = "1";
    static final PinnedPlayChannel KUBEJS_KUBEDEX_REQUEST_BLOCK_PLAY_SINK =
            new PinnedPlayChannel(
                    "kubejs:kubedex/request_block", KUBEJS_NETWORK_VERSION);

    static final String AE2_WIRELESS_TERMINAL_LIBRARY_ARTIFACT_VERSION = "19.4.1";
    static final String AE2_WIRELESS_TERMINAL_LIBRARY_NETWORK_VERSION = "ae2wtlib";
    static final PinnedPlayChannel AE2_WIRELESS_TERMINAL_LIBRARY_PICK_BLOCK_PLAY_SINK =
            new PinnedPlayChannel(
                    "ae2wtlib:pick_block",
                    AE2_WIRELESS_TERMINAL_LIBRARY_NETWORK_VERSION);

    static final String DRACONIC_EVOLUTION_ARTIFACT_VERSION = "1.21.1-3.1.4.632";
    static final String BRANDONS_CORE_ARTIFACT_VERSION = "1.21.1-3.2.1.309";
    static final String DRACONIC_EVOLUTION_NETWORK_VERSION = "3.2.1.309";
    static final PinnedPlayChannel DRACONIC_EVOLUTION_NETWORK_PLAY_SINK =
            new PinnedPlayChannel(
                    "draconicevolution:network",
                    DRACONIC_EVOLUTION_NETWORK_VERSION);

    static final String CURIOS_ARTIFACT_VERSION = "9.5.1+1.21.1";
    static final String CURIOS_NETWORK_VERSION = "1.0";
    static final PinnedPlayChannel CURIOS_OPEN_PLAY_SINK =
            new PinnedPlayChannel("curios:open_curios", CURIOS_NETWORK_VERSION);

    static final String COSMETIC_ARMOR_REWORKED_ARTIFACT_VERSION =
            "1.21.1-v1-neoforge";
    static final String COSMETIC_ARMOR_REWORKED_NETWORK_VERSION = "5";
    static final PinnedPlayChannel COSMETIC_ARMOR_REWORKED_OPEN_PLAY_SINK =
            new PinnedPlayChannel(
                    "cosmeticarmorreworked:open_cosarmor_inv",
                    COSMETIC_ARMOR_REWORKED_NETWORK_VERSION);

    static final String FTB_TEAMS_ARTIFACT_VERSION = "2101.1.9";
    static final String FTB_TEAMS_NETWORK_VERSION = "ftbteams";
    static final PinnedPlayChannel FTB_TEAMS_OPEN_GUI_PLAY_SINK =
            new PinnedPlayChannel("ftbteams:open_gui", FTB_TEAMS_NETWORK_VERSION);

    static final String NOT_ENOUGH_WANDS_ARTIFACT_VERSION = "1.21-7.0.3";
    static final String NOT_ENOUGH_WANDS_NETWORK_VERSION = "1.0";
    static final PinnedPlayChannel NOT_ENOUGH_WANDS_PROTECTED_BLOCK_COUNT_PLAY_SINK =
            new PinnedPlayChannel(
                    "notenoughwands:getprotectedblockcount",
                    NOT_ENOUGH_WANDS_NETWORK_VERSION);

    static final String FTB_ULTIMINE_ARTIFACT_VERSION = "2101.1.13";
    static final String FTB_ULTIMINE_NETWORK_VERSION = "ftbultimine";
    static final PinnedPlayChannel FTB_ULTIMINE_KEY_PRESSED_PLAY_SINK =
            new PinnedPlayChannel(
                    "ftbultimine:key_pressed_packet", FTB_ULTIMINE_NETWORK_VERSION);
    static final PinnedPlayChannel FTB_ULTIMINE_MODE_CHANGED_PLAY_SINK =
            new PinnedPlayChannel(
                    "ftbultimine:mode_changed_packet", FTB_ULTIMINE_NETWORK_VERSION);

    static final String CB_MULTIPART_ARTIFACT_VERSION = "1.21.1-3.5.0.155";
    static final String CODE_CHICKEN_LIB_ARTIFACT_VERSION = "1.21.1-4.6.1.526";
    static final String CB_MULTIPART_NETWORK_VERSION = "3.5.0.155";
    static final PinnedPlayChannel CB_MULTIPART_NETWORK_PLAY_SINK =
            new PinnedPlayChannel("cb_multipart:network", CB_MULTIPART_NETWORK_VERSION);

    static final String MCJTYLIB_ARTIFACT_VERSION = "1.21-9.0.20";
    static final String RFTOOLS_BUILDER_ARTIFACT_VERSION = "1.21-7.0.4";
    static final String MCJTYLIB_NETWORK_VERSION = "1.0";
    static final PinnedPlayChannel MCJTYLIB_SEND_SERVER_COMMAND_PLAY_SINK =
            new PinnedPlayChannel(
                    "mcjtylib:sendservercommand", MCJTYLIB_NETWORK_VERSION);

    static final String TRANSLOCATORS_ARTIFACT_VERSION = "1.21.1-2.8.0.89";
    static final String TRANSLOCATORS_NETWORK_VERSION = "2.8.0.89";
    static final PinnedPlayChannel TRANSLOCATORS_NETWORK_PLAY_SINK =
            new PinnedPlayChannel(
                    "translocators:network", TRANSLOCATORS_NETWORK_VERSION);

    static final String SIMPLE_MAGNETS_ARTIFACT_VERSION = "1.1.12c-neoforge-mc1.21";
    static final String SIMPLE_MAGNETS_MOD_VERSION = "1.1.12+c";
    static final String SUPERMARTIJN642_CORE_LIB_ARTIFACT_VERSION =
            "1.1.21-neoforge-mc1.21";
    static final String SIMPLE_MAGNETS_NETWORK_VERSION = "1";
    static final PinnedPlayChannel SIMPLE_MAGNETS_MAIN_PLAY_SINK =
            new PinnedPlayChannel("simplemagnets:main", SIMPLE_MAGNETS_NETWORK_VERSION);

    static final String STRUCTURIZE_ARTIFACT_VERSION = "1.0.832-1.21.1";
    static final String STRUCTURIZE_NETWORK_VERSION = STRUCTURIZE_ARTIFACT_VERSION;
    static final PinnedPlayChannel STRUCTURIZE_STRUCTURE_PACKS_PLAY_SINK =
            new PinnedPlayChannel(
                    "structurize:notify_server_about_structure_packs",
                    STRUCTURIZE_NETWORK_VERSION);

    static final String REFINED_STORAGE_ARTIFACT_VERSION = "2.0.9";
    static final String REFINED_STORAGE_NETWORK_VERSION = REFINED_STORAGE_ARTIFACT_VERSION;
    static final PinnedPlayChannel REFINED_STORAGE_TENTH_ANNIVERSARY_CAPE_PLAY_SINK =
            new PinnedPlayChannel(
                    "refinedstorage:set_tenth_anniversary_cape",
                    REFINED_STORAGE_NETWORK_VERSION);

    static final String ACCESSORIES_ARTIFACT_VERSION = "1.1.0-beta.48+1.21.1";
    static final String ACCESSORIES_NETWORK_VERSION = "1.0.0";
    static final PinnedPlayChannel ACCESSORIES_MAIN_PLAY_SINK =
            new PinnedPlayChannel("accessories:main", ACCESSORIES_NETWORK_VERSION);

    static final String AETHER_ARTIFACT_VERSION = "1.21.1-1.5.10-neoforge";
    static final String NITROGEN_ARTIFACT_VERSION = "1.21.1-1.1.25-neoforge";
    static final String AETHER_PLAYER_ATTACHMENT_NETWORK_VERSION = "1.0.0";
    static final PinnedPlayChannel AETHER_PLAYER_ATTACHMENT_PLAY_SINK =
            new PinnedPlayChannel(
                    "aether:sync_aether_player_attachment",
                    AETHER_PLAYER_ATTACHMENT_NETWORK_VERSION);

    static final String MAHOU_TSUKAI_ARTIFACT_VERSION = "1.21.1-v1.36.27";
    static final String MAHOU_TSUKAI_NETWORK_VERSION = "1";
    static final int MAHOU_TSUKAI_CHUNK_REQUEST_BYTES = 8;
    static final PinnedPlayChannel MAHOU_TSUKAI_CHUNK_REQUEST_PLAY_SINK =
            new PinnedPlayChannel(
                    "mahoutsukai:chunk_mahou_request_packet",
                    MAHOU_TSUKAI_NETWORK_VERSION);

    static final String CREEPER_OVERHAUL_ARTIFACT_VERSION = "4.0.6";
    static final String RESOURCEFUL_LIB_ARTIFACT_VERSION = "1.21-3.0.12";
    static final String CREEPER_OVERHAUL_NETWORK_VERSION = "v1";
    static final int CREEPER_OVERHAUL_COSMETIC_BYTES = 1;
    static final PinnedPlayChannel CREEPER_OVERHAUL_COSMETIC_PLAY_SINK =
            new PinnedPlayChannel(
                    "creeperoverhaul:main/v1/creeperoverhaul/set_cosmetic",
                    CREEPER_OVERHAUL_NETWORK_VERSION);

    static final String ETERNAL_STARLIGHT_ARTIFACT_VERSION = "0.8.1+1.21.1+neoforge";
    static final String ETERNAL_STARLIGHT_NETWORK_VERSION = "eternal_starlight";
    static final String ETERNAL_STARLIGHT_JAR_SHA256 =
            "acb8fa7a69c4f7d4f1dfa3fba1dbdc96976478ea80b2f9025e43f0c4ee1ceb19";
    static final PinnedPlayChannel ETERNAL_STARLIGHT_BOOK_PROGRESSION_PLAY_SINK =
            new PinnedPlayChannel(
                    "eternal_starlight:update_book_progression",
                    ETERNAL_STARLIGHT_NETWORK_VERSION);
    static final PinnedPlayChannel ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK =
            new PinnedPlayChannel(
                    "eternal_starlight:simple_action",
                    ETERNAL_STARLIGHT_NETWORK_VERSION);

    static final String RELICS_ARTIFACT_VERSION = "1.21.1-0.12.8";
    static final String RELICS_NETWORK_VERSION = "1.0";
    static final String RELICS_JAR_SHA256 =
            "db6f436053fe717413389e55390a5f98103ccd91cca5fe469e090bdfaef70717";
    static final PinnedPlayChannel RELICS_SHIELD_RELEASE_PLAY_SINK =
            new PinnedPlayChannel(
                    "relics:shield_of_retaliation/release",
                    RELICS_NETWORK_VERSION);

    static final String ENDER_DRIVES_ARTIFACT_VERSION = "1.21.1-1.5.23";
    static final String ENDER_DRIVES_MOD_VERSION = "1.5.23";
    static final String ENDER_DRIVES_NETWORK_VERSION = "1.0";
    static final String ENDER_DRIVES_JAR_SHA256 =
            "5266b6c871c5e0d0cd355ab09205d415727de9563ff51204b93633bfefc14ae6";
    static final PinnedPlayChannel ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK =
            new PinnedPlayChannel(
                    "enderdrives:request_disk_type_count", ENDER_DRIVES_NETWORK_VERSION);
    static final PinnedPlayChannel ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK =
            new PinnedPlayChannel(
                    "enderdrives:request_fluid_disk_type_count",
                    ENDER_DRIVES_NETWORK_VERSION);

    static final String TWILIGHT_FOREST_ARTIFACT_VERSION = "1.21.1-4.8.3345";
    static final String TWILIGHT_FOREST_MOD_VERSION = "4.8.3345";
    static final String TWILIGHT_FOREST_NETWORK_VERSION = "1.0.0";
    static final String TWILIGHT_FOREST_JAR_SHA256 =
            "493da16d10210f9f53a3c33b3d8fcb4ae9b14016ee420d753c894562bd9aba35";
    static final PinnedPlayChannel TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK =
            new PinnedPlayChannel(
                    "twilightforest:gradual_glide_packet",
                    TWILIGHT_FOREST_NETWORK_VERSION);

    static final String DEEPER_DARKER_ATM10_NORMAL_7_3_ARTIFACT_VERSION =
            "1.21-1.3.4";
    static final String DEEPER_DARKER_ATM10_NORMAL_8_0_ARTIFACT_VERSION =
            "1.21.1-1.4.1";
    static final String DEEPER_DARKER_NETWORK_VERSION = "1.0";
    static final String DEEPER_DARKER_1_4_1_JAR_SHA256 =
            "eee3f51222b0bcc714def002ff089ac9e131d3cae4575b542fd0a7dd101fe0af";
    static final PinnedPlayChannel DEEPER_DARKER_USE_TRANSMITTER_PLAY_SINK =
            new PinnedPlayChannel(
                    "deeperdarker:use_transmitter", DEEPER_DARKER_NETWORK_VERSION);

    static final String TOOL_BELT_ARTIFACT_VERSION = "1.21.1-2.2.10";
    static final String TOOL_BELT_MOD_VERSION = "2.2.10";
    static final String TOOL_BELT_NETWORK_VERSION = "1.0";
    static final String TOOL_BELT_2_2_10_JAR_SHA256 =
            "918b35093dfaf8033115acd8dacd8978e325dcba36355f8d1a938ee92d5ab4ff";
    static final String TOOL_BELT_2_2_10_SOURCE_SHA =
            "1f59485e890959e18fde12db5a62a36a997d76d5";
    static final PinnedPlayChannel TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK =
            new PinnedPlayChannel(
                    "toolbelt:open_belt_slot_inventory", TOOL_BELT_NETWORK_VERSION);

    static final String IRONS_SPELLBOOKS_ARTIFACT_VERSION = "1.21.1-3.16.2";
    static final String IRONS_SPELLBOOKS_NETWORK_VERSION = "1.0.0";
    static final String IRONS_SPELLBOOKS_3_16_2_JAR_SHA256 =
            "82b650aff7636c8fa88da0e4cfea008c229bb62843274678ee932f6b4ec74430";
    static final String IRONS_SPELLBOOKS_3_16_2_SOURCE_COMMIT =
            "8e233f5d34d57d1f7237ef2ff5d76017bf08c616";
    static final String IRONS_SPELLBOOKS_CAST_SOURCE_SHA =
            "1e06f129cd96d5de3dae1244c13a7f0f1d58efe5";
    static final String IRONS_SPELLBOOKS_PAYLOAD_HANDLER_SOURCE_SHA =
            "41ca54310a02cc772072b952b467e783b017412a";
    static final PinnedPlayChannel IRONS_SPELLBOOKS_CAST_PLAY_SINK =
            new PinnedPlayChannel(
                    "irons_spellbooks:cast", IRONS_SPELLBOOKS_NETWORK_VERSION);

    /**
     * Client-to-server interactions proven by attached crash reports or latest.log and reviewed
     * against the owning mods' registrations. Some owning channels are bidirectional, but the
     * lobby consumes only client-originated packets as bounded no-ops. Paper never sees them and
     * no server-side mod behavior is emulated.
     */
    static final List<PinnedPlayChannel> REVIEWED_LOBBY_INTERACTION_PLAY_SINKS = List.of(
            AE2_WIRELESS_TERMINAL_LIBRARY_PICK_BLOCK_PLAY_SINK,
            DRACONIC_EVOLUTION_NETWORK_PLAY_SINK,
            CURIOS_OPEN_PLAY_SINK,
            COSMETIC_ARMOR_REWORKED_OPEN_PLAY_SINK,
            FTB_TEAMS_OPEN_GUI_PLAY_SINK,
            NOT_ENOUGH_WANDS_PROTECTED_BLOCK_COUNT_PLAY_SINK,
            FTB_ULTIMINE_KEY_PRESSED_PLAY_SINK,
            FTB_ULTIMINE_MODE_CHANGED_PLAY_SINK,
            CB_MULTIPART_NETWORK_PLAY_SINK,
            MCJTYLIB_SEND_SERVER_COMMAND_PLAY_SINK,
            TRANSLOCATORS_NETWORK_PLAY_SINK,
            SIMPLE_MAGNETS_MAIN_PLAY_SINK,
            LOGISTICS_NETWORKS_DEFAULT_VISIBILITY_PLAY_SINK,
            LOGISTICS_NETWORKS_MODIFIER_KEYS_ATM10_82_PLAY_SINK,
            STRUCTURIZE_STRUCTURE_PACKS_PLAY_SINK,
            REFINED_STORAGE_TENTH_ANNIVERSARY_CAPE_PLAY_SINK,
            ACCESSORIES_MAIN_PLAY_SINK,
            AETHER_PLAYER_ATTACHMENT_PLAY_SINK,
            MAHOU_TSUKAI_CHUNK_REQUEST_PLAY_SINK,
            CREEPER_OVERHAUL_COSMETIC_PLAY_SINK,
            ETERNAL_STARLIGHT_BOOK_PROGRESSION_PLAY_SINK,
            ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK,
            RELICS_SHIELD_RELEASE_PLAY_SINK,
            ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK,
            ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK,
            TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK,
            DEEPER_DARKER_USE_TRANSMITTER_PLAY_SINK,
            TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK,
            IRONS_SPELLBOOKS_CAST_PLAY_SINK);

    /**
     * Reviewed lifecycle and chunk-interaction packets are accepted only with the exact contract
     * proven by the owning artifact and query fixtures. Older interaction pins intentionally
     * retain the client's announced contract.
     */
    static boolean matchesReviewedPinnedContract(
            int minecraftProtocol,
            PinnedPlayChannel pinned,
            Channel advertised,
            Registry clientRegistry,
            ChannelContractSignature.Signatures clientSignatures) {
        Objects.requireNonNull(clientRegistry, "clientRegistry");
        Objects.requireNonNull(clientSignatures, "clientSignatures");
        if (pinned.equals(
                SOPHISTICATED_BACKPACKS_ANOTHER_PLAYER_BACKPACK_OPEN_PLAY_SINK)) {
            return advertised.flow() == Flow.SERVERBOUND && !advertised.optional();
        }
        if (pinned.equals(LOGISTICS_NETWORKS_DEFAULT_VISIBILITY_PLAY_SINK)
                || pinned.equals(LOGISTICS_NETWORKS_MODIFIER_KEYS_ATM10_82_PLAY_SINK)) {
            return matchesExactContract(pinned, advertised, Flow.SERVERBOUND, false);
        }
        if (pinned.equals(STRUCTURIZE_STRUCTURE_PACKS_PLAY_SINK)
                || pinned.equals(REFINED_STORAGE_TENTH_ANNIVERSARY_CAPE_PLAY_SINK)
                || pinned.equals(MAHOU_TSUKAI_CHUNK_REQUEST_PLAY_SINK)
                || pinned.equals(CREEPER_OVERHAUL_COSMETIC_PLAY_SINK)
                || pinned.equals(DEEPER_DARKER_USE_TRANSMITTER_PLAY_SINK)
                || pinned.equals(TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK)) {
            return matchesExactContract(pinned, advertised, Flow.SERVERBOUND, false);
        }
        if (pinned.equals(IRONS_SPELLBOOKS_CAST_PLAY_SINK)) {
            return matchesExactContract(pinned, advertised, Flow.SERVERBOUND, true);
        }
        if (pinned.equals(ACCESSORIES_MAIN_PLAY_SINK)
                || pinned.equals(AETHER_PLAYER_ATTACHMENT_PLAY_SINK)
                || pinned.equals(ETERNAL_STARLIGHT_BOOK_PROGRESSION_PLAY_SINK)) {
            return matchesExactContract(pinned, advertised, Flow.BIDIRECTIONAL, true);
        }
        if (pinned.equals(ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK)) {
            return matchesAtm10Normal80Contract(
                            minecraftProtocol, clientRegistry, clientSignatures)
                    && matchesExactContract(pinned, advertised, Flow.BIDIRECTIONAL, true);
        }
        if (pinned.equals(RELICS_SHIELD_RELEASE_PLAY_SINK)) {
            return matchesAtm10Normal80Contract(
                            minecraftProtocol, clientRegistry, clientSignatures)
                    && matchesExactContract(pinned, advertised, Flow.SERVERBOUND, true);
        }
        if (pinned.equals(ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK)
                || pinned.equals(ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK)) {
            return matchesExactContract(pinned, advertised, Flow.SERVERBOUND, true);
        }
        if (pinned.equals(TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK)) {
            return matchesAtm10Normal80Contract(
                            minecraftProtocol, clientRegistry, clientSignatures)
                    && matchesExactContract(pinned, advertised, Flow.BIDIRECTIONAL, true);
        }
        return true;
    }

    /** Applies exact size or structural byte bounds to bytecode-audited payload codecs. */
    static boolean matchesReviewedPayloadBytes(String channelId, int payloadBytes) {
        if (channelId.equals(
                SOPHISTICATED_BACKPACKS_ANOTHER_PLAYER_BACKPACK_OPEN_PLAY_SINK.id())) {
            return payloadBytes == SOPHISTICATED_BACKPACKS_ANOTHER_PLAYER_OPEN_PAYLOAD_BYTES;
        }
        if (channelId.equals(LOGISTICS_NETWORKS_DEFAULT_VISIBILITY_PLAY_SINK.id())) {
            return payloadBytes
                    == LogisticsNetworksDefaultNodeVisibilityPayload.PAYLOAD_BYTES;
        }
        if (channelId.equals(LOGISTICS_NETWORKS_MODIFIER_KEYS_ATM10_82_PLAY_SINK.id())) {
            return payloadBytes == Atm10Normal82Contract.LOGISTICS_PAYLOAD_BYTES;
        }
        if (channelId.equals(MAHOU_TSUKAI_CHUNK_REQUEST_PLAY_SINK.id())) {
            return payloadBytes == MAHOU_TSUKAI_CHUNK_REQUEST_BYTES;
        }
        if (channelId.equals(CREEPER_OVERHAUL_COSMETIC_PLAY_SINK.id())) {
            return payloadBytes == CREEPER_OVERHAUL_COSMETIC_BYTES;
        }
        if (channelId.equals(ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK.id())) {
            return payloadBytes == EternalStarlightSimpleActionPayload.PAYLOAD_BYTES;
        }
        if (channelId.equals(RELICS_SHIELD_RELEASE_PLAY_SINK.id())) {
            return payloadBytes == RelicsShieldReleasePayload.PAYLOAD_BYTES;
        }
        if (isEnderDrivesTypeCountRequest(channelId)) {
            return EnderDrivesTypeCountRequestPayload.hasValidSize(payloadBytes);
        }
        if (channelId.equals(TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK.id())) {
            return payloadBytes == TwilightForestGradualGlidePayload.PAYLOAD_BYTES;
        }
        if (channelId.equals(DEEPER_DARKER_USE_TRANSMITTER_PLAY_SINK.id())) {
            return payloadBytes == DeeperDarkerUseTransmitterPayload.PAYLOAD_BYTES;
        }
        if (channelId.equals(TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK.id())) {
            return payloadBytes == ToolBeltOpenBeltSlotInventoryPayload.PAYLOAD_BYTES;
        }
        if (channelId.equals(IRONS_SPELLBOOKS_CAST_PLAY_SINK.id())) {
            return payloadBytes == IronsSpellbooksCastPayload.PAYLOAD_BYTES;
        }
        return true;
    }

    /** Applies both exact-size and variable-codec validation to reviewed client payloads. */
    static boolean matchesReviewedPayload(String channelId, byte[] payload) {
        if (!matchesReviewedPayloadBytes(channelId, payload.length)) {
            return false;
        }
        if (channelId.equals(LOGISTICS_NETWORKS_DEFAULT_VISIBILITY_PLAY_SINK.id())) {
            return LogisticsNetworksDefaultNodeVisibilityPayload.isValid(payload);
        }
        if (channelId.equals(ETERNAL_STARLIGHT_BOOK_PROGRESSION_PLAY_SINK.id())) {
            return EternalStarlightProgressionPayload.isValid(payload);
        }
        if (channelId.equals(ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK.id())) {
            return EternalStarlightSimpleActionPayload.isValid(payload);
        }
        if (channelId.equals(RELICS_SHIELD_RELEASE_PLAY_SINK.id())) {
            return RelicsShieldReleasePayload.isValid(payload);
        }
        if (isEnderDrivesTypeCountRequest(channelId)) {
            return EnderDrivesTypeCountRequestPayload.isValid(payload);
        }
        if (channelId.equals(TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK.id())) {
            return TwilightForestGradualGlidePayload.isStructurallyValid(payload);
        }
        if (channelId.equals(DEEPER_DARKER_USE_TRANSMITTER_PLAY_SINK.id())) {
            return DeeperDarkerUseTransmitterPayload.isValid(payload);
        }
        if (channelId.equals(TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK.id())) {
            return ToolBeltOpenBeltSlotInventoryPayload.isValid(payload);
        }
        if (channelId.equals(IRONS_SPELLBOOKS_CAST_PLAY_SINK.id())) {
            return IronsSpellbooksCastPayload.isValid(payload);
        }
        return true;
    }

    /** Applies the owning codec plus same-player binding for session-bearing payloads. */
    static boolean matchesReviewedSessionPayload(
            String channelId, byte[] payload, UUID expectedPlayer) {
        Objects.requireNonNull(expectedPlayer, "expectedPlayer");
        if (!matchesReviewedPayload(channelId, payload)) {
            return false;
        }
        if (channelId.equals(TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK.id())) {
            return TwilightForestGradualGlidePayload.belongsTo(payload, expectedPlayer);
        }
        return true;
    }

    private static boolean isEnderDrivesTypeCountRequest(String channelId) {
        return channelId.equals(ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK.id())
                || channelId.equals(
                        ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK.id());
    }

    private static boolean matchesAtm10Normal80Contract(
            int minecraftProtocol,
            Registry clientRegistry,
            ChannelContractSignature.Signatures clientSignatures) {
        return ReviewedClientContractEvidence.matchAtm10Normal80(
                        minecraftProtocol, clientRegistry, clientSignatures)
                .isPresent();
    }

    private static boolean matchesExactContract(
            PinnedPlayChannel pinned,
            Channel advertised,
            Flow flow,
            boolean optional) {
        return advertised.version().equals(pinned.version())
                && advertised.flow() == flow
                && advertised.optional() == optional;
    }

    static final String MATC_MOD_VERSION = "1.7.1";
    static final String MATC_SERVER_CONFIG = "matc-server.toml";

    static final String MEKANISM_ARTIFACT_VERSION = "1.21.1-10.7.18.84";
    static final String MEKANISM_NETWORK_VERSION = "10.7.18";
    static final PinnedPlayChannel MEKANISM_KEY_PLAY_SINK =
            new PinnedPlayChannel("mekanism:key", MEKANISM_NETWORK_VERSION);
    static final List<String> MEKANISM_SERVER_CONFIGS = List.of(
            "Mekanism/general.toml",
            "Mekanism/gear.toml",
            "Mekanism/machine-storage.toml",
            "Mekanism/tiers.toml",
            "Mekanism/machine-usage.toml",
            "Mekanism/world.toml",
            "Mekanism/generators.toml",
            "Mekanism/generators-gear.toml",
            "Mekanism/generator-storage.toml",
            "Mekanism/tools.toml");

    private ReviewedAtmCompatibility() {
    }
}
