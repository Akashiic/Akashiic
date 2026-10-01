package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigurationPersistenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedConfigurationIsDisabledBoundedAndValidForLobbyOnly() throws Exception {
        BridgeConfig config = BridgeConfig.load(temporaryDirectory);

        assertFalse(config.debug());
        assertFalse(config.enabled());
        assertEquals(4, BridgeConfig.CONFIG_VERSION);
        assertEquals("lobby", config.lobbyServer());
        assertEquals(767, config.expectedMinecraftProtocol());
        assertEquals(5_000, config.handshakeProbeTimeoutMillis());
        assertEquals(60_000, config.frozenRegistryAckTimeoutMillis());
        assertTrue(config.allowVanillaLobby());
        assertTrue(config.enableLegacyForgeHandoffGuard());
        assertEquals(List.of("forbidden-1"), config.legacyForgeHandoffTargets());
        assertTrue(config.enableLegacyForgeLobbyEntryGuard());
        assertEquals(16, config.maximumLegacyLobbyClientChannels());
        assertEquals(Set.of("necrotempus:main"), config.allowedLegacyLobbyClientChannels());
        assertTrue(config.enableNecroTempusTransport());
        assertEquals(30_000, config.necroTempusMaximumCompressedNbtBytes());
        assertEquals(262_144, config.necroTempusMaximumDecompressedNbtBytes());
        assertEquals(30_000, config.necroTempusMaximumTabBridgeMessageBytes());
        assertEquals(16_384, config.necroTempusMaximumTabTextBytes());
        assertEquals(64, config.necroTempusMaximumPacketsPerSecond());
        assertEquals(524_288, config.necroTempusMaximumBytesPerSecond());
        assertTrue(config.enableBackendNeoForgeCapabilityRelay());
        assertTrue(config.enableBuiltInAe2JeiSessionOptimization());
        assertTrue(config.enableBuiltInApothicEnchantingBootstrap());
        assertTrue(config.enableBuiltInMekanismCompatibility());
        assertTrue(config.enableBuiltInReviewedLobbyInteractionPlaySinks());
        assertTrue(config.enableBuiltInSilentGearSnapshotBridge());
        assertEquals(1_048_576, config.maximumSilentGearPayloadBytes());
        assertEquals(3_145_728, config.maximumSilentGearTotalBytes());
        assertEquals(16, config.maximumSilentGearSnapshots());
        assertEquals(RegistryShimCatalog.builtInAtmShimIds(), config.registryShims());
        assertEquals(65_536, config.maximumRegistryShimBytes());
        assertEquals(
                List.of(
                        "neoforge-server.toml",
                        "create-server.toml",
                        "create_hypertube-server.toml",
                        "securitycraft-server.toml",
                        "productivefarming-server.toml",
                        "bhc-server.toml",
                        "utilitarian-server.toml",
                        "ars_nouveau/rewind.toml",
                        "matc-server.toml",
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
                config.transientServerConfigs());
        assertTrue(config.deriveServerConfigsFromClientChannels());
        assertEquals(512, config.maximumDerivedServerConfigs());
        assertEquals(
                List.of(
                        new PinnedPlayChannel(
                                "sophisticatedbackpacks:request_player_settings", "1.0"),
                        new PinnedPlayChannel(
                                "sophisticatedbackpacks:backpack_open", "1.0"),
                        new PinnedPlayChannel(
                                "sophisticatedbackpacks:another_player_backpack_open", "1.0"),
                        new PinnedPlayChannel(
                                "sophisticatedbackpacks:block_pick", "1.0"),
                        new PinnedPlayChannel(
                                "sophisticatedstorage:request_player_settings", "1.0"),
                        new PinnedPlayChannel(
                                "pneumaticcraft:sync_amadron_offers", "1"),
                        new PinnedPlayChannel("kubejs:first_click", "1"),
                        new PinnedPlayChannel("kubejs:kubedex/request_block", "1"),
                        new PinnedPlayChannel("placebo:patreon_disable", "1"),
                        new PinnedPlayChannel("xycraft_core:modifier_key", "1.0.0"),
                        new PinnedPlayChannel("mekanism:key", "10.7.18"),
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
                config.pinnedPlaySinkChannels());
        assertEquals(48, config.maximumAutomaticPlaySinkChannels());
        assertEquals(48, config.configuredMaximumAutomaticPlaySinkChannels());
        assertFalse(config.automaticPlaySinkBudgetClamped());
        assertEquals(96, config.maximumGlobalPlaySinkChannels());
        assertEquals(16_384, config.maximumGlobalPlaySinkChannelBytes());
        assertEquals(1_048_576, config.maximumLobbyPlayPayloadBytes());
        assertEquals(8_388_608, config.maximumLobbyPlayTotalBytes());
        assertEquals(512, config.maximumLobbyPlayPackets());
        assertEquals(60_000, config.lobbyPlayBudgetRefillMillis());
        assertFalse(config.legacyRoutingConfigurationIgnored());
        assertEquals(1_048_576, config.limits().maximumQueryBytes());
        assertEquals(16_384, config.limits().maximumChannels());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("bridge.properties")));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("debug=false"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("frozen-registry-ack-timeout-millis=60000"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-backend-neoforge-capability-relay=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-legacy-forge-handoff-guard=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("legacy-forge-handoff-targets=forbidden-1"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-legacy-forge-lobby-entry-guard=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("maximum-legacy-lobby-client-channels=16"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("allowed-legacy-lobby-client-channels=necrotempus:main"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-necrotempus-transport=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-built-in-ae2-jei-session-optimization=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("transient-server-configs=neoforge-server.toml,create-server.toml,"
                        + "create_hypertube-server.toml,securitycraft-server.toml,"
                        + "productivefarming-server.toml,bhc-server.toml,"
                        + "utilitarian-server.toml,ars_nouveau/rewind.toml,"
                        + "matc-server.toml"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-built-in-atm-registry-shims=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-built-in-apothic-enchanting-bootstrap=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-built-in-placebo-play-sink=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-built-in-xycraft-play-sink=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("lobby-play-budget-refill-millis=60000"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-built-in-mekanism-compatibility=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-built-in-reviewed-lobby-interaction-play-sinks=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("enable-built-in-silent-gear-snapshot-bridge=true"));
        assertTrue(Files.readString(
                temporaryDirectory.resolve("bridge.properties"), StandardCharsets.UTF_8)
                .contains("Mekanism/general.toml"));
    }

    @Test
    void legacyConfigurationProducesV4ExampleAndFailsClosed() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("bridge.properties"),
                "config-version=3\nenabled=true\nlobby-server=lobby\n",
                StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));
        assertTrue(Files.isRegularFile(
                temporaryDirectory.resolve("bridge-v4.example.properties")));
    }

    @Test
    void legacyDestinationConfigurationIsAcceptedButHasNoRoutingEffect() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        configureTwoDestinations();

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);
        assertTrue(loaded.legacyRoutingConfigurationIgnored());
    }

    @Test
    void arbitraryLegacyDestinationValuesCannotReintroduceRouting() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        configureTwoDestinations();
        updateProperties(properties -> properties.setProperty(
                "destination.normal.display-name", "ATM10 São Paulo #1"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);
        assertTrue(loaded.legacyRoutingConfigurationIgnored());
    }

    @Test
    void removedRoutingKeysAreNeverInterpretedAsServerAuthority() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        configureTwoDestinations();
        updateProperties(properties -> properties.setProperty(
                "destination.normal.velocity-server", "lobby"));
        assertTrue(BridgeConfig.load(temporaryDirectory).legacyRoutingConfigurationIgnored());
    }

    @Test
    void malformedLegacyDestinationKeyStillFailsClosed() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "destination.INVALID.velocity-server", "server"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));
    }

    @Test
    void obsoleteProfileAndUnknownPropertiesAreRejected() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty("profiles", "atm10"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty("unexpected", "value"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));
    }

    @Test
    void existingV4ConfigurationWithoutNewKeysUsesSafeCompatibilityDefaults() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> {
            properties.remove("debug");
            properties.remove("enable-legacy-forge-handoff-guard");
            properties.remove("legacy-forge-handoff-targets");
            properties.remove("enable-legacy-forge-lobby-entry-guard");
            properties.remove("maximum-legacy-lobby-client-channels");
            properties.remove("allowed-legacy-lobby-client-channels");
            properties.remove("enable-necrotempus-transport");
            properties.remove("necrotempus-maximum-compressed-nbt-bytes");
            properties.remove("necrotempus-maximum-decompressed-nbt-bytes");
            properties.remove("necrotempus-maximum-tab-bridge-message-bytes");
            properties.remove("necrotempus-maximum-tab-text-bytes");
            properties.remove("necrotempus-maximum-packets-per-second");
            properties.remove("necrotempus-maximum-bytes-per-second");
            properties.remove("enable-backend-neoforge-capability-relay");
            properties.remove("enable-built-in-ae2-jei-session-optimization");
            properties.remove("transient-server-configs");
            properties.remove("registry-shims");
            properties.remove("enable-built-in-atm-registry-shims");
            properties.remove("maximum-registry-shim-bytes");
            properties.remove("enable-built-in-atm-server-configs");
            properties.remove("derive-server-configs-from-client-channels");
            properties.remove("maximum-derived-server-configs");
            properties.remove("enable-built-in-apothic-enchanting-bootstrap");
            properties.remove("enable-built-in-mekanism-compatibility");
            properties.remove("enable-built-in-reviewed-lobby-interaction-play-sinks");
            properties.remove("enable-built-in-silent-gear-snapshot-bridge");
            properties.remove("maximum-silent-gear-payload-bytes");
            properties.remove("maximum-silent-gear-total-bytes");
            properties.remove("maximum-silent-gear-snapshots");
            properties.remove("frozen-registry-ack-timeout-millis");
            properties.remove("enable-built-in-sophisticated-play-sinks");
            properties.remove("enable-built-in-pneumaticcraft-play-sink");
            properties.remove("enable-built-in-kubejs-play-sinks");
            properties.remove("enable-built-in-placebo-play-sink");
            properties.remove("enable-built-in-xycraft-play-sink");
            properties.remove("pinned-play-sink-channels");
            properties.remove("maximum-automatic-play-sink-channels");
            properties.remove("maximum-global-play-sink-channels");
            properties.remove("maximum-global-play-sink-channel-bytes");
            properties.remove("maximum-lobby-play-payload-bytes");
            properties.remove("maximum-lobby-play-total-bytes");
            properties.remove("maximum-lobby-play-packets");
            properties.remove("lobby-play-budget-refill-millis");
        });
        byte[] beforeLoad = Files.readAllBytes(
                temporaryDirectory.resolve("bridge.properties"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(
                List.of(
                        "neoforge-server.toml",
                        "create-server.toml",
                        "create_hypertube-server.toml",
                        "securitycraft-server.toml",
                        "productivefarming-server.toml",
                        "bhc-server.toml",
                        "utilitarian-server.toml",
                        "ars_nouveau/rewind.toml",
                        "matc-server.toml",
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
                loaded.transientServerConfigs());
        assertEquals(RegistryShimCatalog.builtInAtmShimIds(), loaded.registryShims());
        assertEquals(65_536, loaded.maximumRegistryShimBytes());
        assertTrue(loaded.deriveServerConfigsFromClientChannels());
        assertFalse(loaded.debug());
        assertEquals(60_000, loaded.frozenRegistryAckTimeoutMillis());
        assertTrue(loaded.enableLegacyForgeHandoffGuard());
        assertEquals(List.of("forbidden-1"), loaded.legacyForgeHandoffTargets());
        assertTrue(loaded.enableLegacyForgeLobbyEntryGuard());
        assertEquals(16, loaded.maximumLegacyLobbyClientChannels());
        assertEquals(
                Set.of("necrotempus:main"),
                loaded.allowedLegacyLobbyClientChannels());
        assertTrue(loaded.enableNecroTempusTransport());
        assertEquals(30_000, loaded.necroTempusMaximumCompressedNbtBytes());
        assertEquals(262_144, loaded.necroTempusMaximumDecompressedNbtBytes());
        assertEquals(30_000, loaded.necroTempusMaximumTabBridgeMessageBytes());
        assertEquals(16_384, loaded.necroTempusMaximumTabTextBytes());
        assertEquals(64, loaded.necroTempusMaximumPacketsPerSecond());
        assertEquals(524_288, loaded.necroTempusMaximumBytesPerSecond());
        assertTrue(loaded.enableBackendNeoForgeCapabilityRelay());
        assertTrue(loaded.enableBuiltInAe2JeiSessionOptimization());
        assertTrue(loaded.enableBuiltInApothicEnchantingBootstrap());
        assertTrue(loaded.enableBuiltInReviewedLobbyInteractionPlaySinks());
        assertTrue(loaded.enableBuiltInSilentGearSnapshotBridge());
        assertEquals(1_048_576, loaded.maximumSilentGearPayloadBytes());
        assertEquals(3_145_728, loaded.maximumSilentGearTotalBytes());
        assertEquals(16, loaded.maximumSilentGearSnapshots());
        assertEquals(512, loaded.maximumDerivedServerConfigs());
        assertEquals(39, loaded.pinnedPlaySinkChannels().size());
        assertEquals(48, loaded.maximumAutomaticPlaySinkChannels());
        assertEquals(48, loaded.configuredMaximumAutomaticPlaySinkChannels());
        assertFalse(loaded.automaticPlaySinkBudgetClamped());
        assertEquals(96, loaded.maximumGlobalPlaySinkChannels());
        assertArrayEquals(
                beforeLoad,
                Files.readAllBytes(temporaryDirectory.resolve("bridge.properties")));
    }

    @Test
    void legacyForgeHandoffAllowlistIsStrictBoundedAndMayBeDisabled() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "legacy-forge-handoff-targets", "forbidden-1,forbidden-2"));
        BridgeConfig twoTargets = BridgeConfig.load(temporaryDirectory);
        assertEquals(
                List.of("forbidden-1", "forbidden-2"),
                twoTargets.legacyForgeHandoffTargets());

        updateProperties(properties -> properties.setProperty(
                "legacy-forge-handoff-targets", "forbidden-1,forbidden-1"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "legacy-forge-handoff-targets", "lobby"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "legacy-forge-handoff-targets", "../forbidden"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "legacy-forge-handoff-targets", ""));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> {
            properties.setProperty("enable-legacy-forge-handoff-guard", "false");
            properties.setProperty("legacy-forge-handoff-targets", "");
        });
        BridgeConfig disabled = BridgeConfig.load(temporaryDirectory);
        assertFalse(disabled.enableLegacyForgeHandoffGuard());
        assertTrue(disabled.legacyForgeHandoffTargets().isEmpty());
    }

    @Test
    void legacyLobbyEntryGuardConfigurationIsCanonicalBoundedAndMayDropAll()
            throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> {
            properties.setProperty("maximum-legacy-lobby-client-channels", "3");
            properties.setProperty(
                    "allowed-legacy-lobby-client-channels",
                    "necrotempus:main,example:secondary");
        });
        BridgeConfig configured = BridgeConfig.load(temporaryDirectory);
        assertEquals(3, configured.maximumLegacyLobbyClientChannels());
        assertEquals(
                List.of("necrotempus:main", "example:secondary"),
                List.copyOf(configured.allowedLegacyLobbyClientChannels()));

        updateProperties(properties -> properties.setProperty(
                "allowed-legacy-lobby-client-channels",
                "necrotempus:main,necrotempus:main"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "allowed-legacy-lobby-client-channels", "NecroTempus:main"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> {
            properties.setProperty("maximum-legacy-lobby-client-channels", "1");
            properties.setProperty(
                    "allowed-legacy-lobby-client-channels",
                    "necrotempus:main,example:secondary");
        });
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> {
            properties.setProperty("enable-legacy-forge-lobby-entry-guard", "false");
            properties.setProperty("allowed-legacy-lobby-client-channels", "");
        });
        BridgeConfig disabled = BridgeConfig.load(temporaryDirectory);
        assertFalse(disabled.enableLegacyForgeLobbyEntryGuard());
        assertTrue(disabled.allowedLegacyLobbyClientChannels().isEmpty());
    }

    @Test
    void necroTempusTransportConfigurationIsStrictAndBounded() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties ->
                properties.setProperty("enable-necrotempus-transport", "false"));
        assertFalse(BridgeConfig.load(temporaryDirectory).enableNecroTempusTransport());

        for (String key : List.of(
                "necrotempus-maximum-compressed-nbt-bytes",
                "necrotempus-maximum-decompressed-nbt-bytes",
                "necrotempus-maximum-tab-text-bytes",
                "necrotempus-maximum-packets-per-second",
                "necrotempus-maximum-bytes-per-second")) {
            resetDefaults();
            updateProperties(properties -> properties.setProperty(key, "0"));
            assertThrows(IllegalArgumentException.class, () ->
                    BridgeConfig.load(temporaryDirectory), key);
        }

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "necrotempus-maximum-tab-bridge-message-bytes", "41"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "necrotempus-maximum-compressed-nbt-bytes", "32768"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-necrotempus-transport", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));
    }

    @Test
    void realOperatorV4ConfigurationExpandsLegacyCapacityWithoutRewrite() throws Exception {
        var fixture = ConfigurationPersistenceTest.class.getResourceAsStream(
                "/bridge-v4-operator-025-regression.properties");
        assertNotNull(fixture);
        try (fixture) {
            Files.copy(fixture, temporaryDirectory.resolve("bridge.properties"));
        }
        byte[] beforeLoad = Files.readAllBytes(
                temporaryDirectory.resolve("bridge.properties"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(43, loaded.pinnedPlaySinkChannels().size());
        assertEquals(48, loaded.configuredMaximumAutomaticPlaySinkChannels());
        assertEquals(48, loaded.maximumAutomaticPlaySinkChannels());
        assertEquals(91, loaded.maximumGlobalPlaySinkChannels());
        assertEquals(4_096, loaded.maximumLobbyPlayPackets());
        assertEquals(8_388_608, loaded.maximumLobbyPlayTotalBytes());
        assertEquals(60_000, loaded.lobbyPlayBudgetRefillMillis());
        assertFalse(loaded.automaticPlaySinkBudgetClamped());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.id().equals("kubejs:first_click"))
                .count());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.id().equals("mekanism:key"))
                .count());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.id().equals(
                        "logisticsnetworks:set_default_node_visibility"))
                .count());
        assertTrue(loaded.pinnedPlaySinkChannels().stream()
                .map(PinnedPlayChannel::id)
                .toList()
                .containsAll(List.of(
                        "watut:nbt_server",
                        "fancymenu:fancymenu_bridge_packet",
                        "ironjetpacks:update_inputs",
                        "modern_industrialization:update_keys",
                        "deeperdarker:use_transmitter",
                        "toolbelt:open_belt_slot_inventory",
                        "irons_spellbooks:cast")));

        byte[] query;
        try (InputStream stream = ConfigurationPersistenceTest.class.getClassLoader()
                .getResourceAsStream("atm10-normal/8.0/client-neoforge-response.bin")) {
            assertNotNull(stream);
            query = stream.readAllBytes();
        }
        PlaySinkPlanner.Plan boundedPlan = normal80Plan(
                query,
                loaded,
                loaded.pinnedPlaySinkChannels(),
                loaded.maximumAutomaticPlaySinkChannels());
        assertEquals(87, boundedPlan.channels().size());
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "refinedstorage:set_tenth_anniversary_cape")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "structurize:notify_server_about_structure_packs")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "accessories:main")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "aether:sync_aether_player_attachment")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "mahoutsukai:chunk_mahou_request_packet")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "creeperoverhaul:main/v1/creeperoverhaul/set_cosmetic")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "eternal_starlight:update_book_progression")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "eternal_starlight:simple_action")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "relics:shield_of_retaliation/release")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "enderdrives:request_disk_type_count")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "enderdrives:request_fluid_disk_type_count")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "twilightforest:gradual_glide_packet")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "deeperdarker:use_transmitter")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "toolbelt:open_belt_slot_inventory")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "irons_spellbooks:cast")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "create:left_click")));
        assertTrue(boundedPlan.channels().stream().anyMatch(channel -> channel.id().equals(
                "create:linked_controller_input")));

        // Preserve the historical priority/rebalance regression under the former effective
        // automatic budget. The operator's live v4 configuration is deliberately expanded to
        // 48 in memory above; mixing that wider service-first capacity into these pairwise
        // priority assertions would test two independent migrations at once.
        PlaySinkPlanner.Plan legacyCapacityPlan = normal80Plan(
                query,
                loaded,
                loaded.pinnedPlaySinkChannels(),
                21);
        assertEquals(60, legacyCapacityPlan.channels().size());

        List<PinnedPlayChannel> beta9Pins = loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_BOOK_PROGRESSION_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .RELICS_SHIELD_RELEASE_PLAY_SINK))
                .toList();
        PlaySinkPlanner.Plan beta9Plan = normal80Plan(
                query,
                loaded,
                beta9Pins,
                32);
        Set<String> beta9Channels = beta9Plan.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<PinnedPlayChannel> beta10Pins = loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .RELICS_SHIELD_RELEASE_PLAY_SINK))
                .toList();
        PlaySinkPlanner.Plan beta10Plan = normal80Plan(
                query,
                loaded,
                beta10Pins,
                31);
        Set<String> beta10Channels = beta10Plan.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> currentChannels = legacyCapacityPlan.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertEquals(38L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> currentChannels.contains(channel.id()))
                .count());
        Set<String> removedByRebalance = new LinkedHashSet<>(beta9Channels);
        removedByRebalance.removeAll(beta10Channels);
        Set<String> addedByRebalance = new LinkedHashSet<>(beta10Channels);
        addedByRebalance.removeAll(beta9Channels);
        assertEquals(
                Set.of("computercraft:key_event"),
                removedByRebalance);
        assertEquals(
                Set.of("eternal_starlight:update_book_progression"),
                addedByRebalance);

        List<PinnedPlayChannel> beta11Pins = loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .RELICS_SHIELD_RELEASE_PLAY_SINK))
                .toList();
        PlaySinkPlanner.Plan beta11Plan = normal80Plan(
                query,
                loaded,
                beta11Pins,
                29);
        Set<String> beta11Channels = beta11Plan.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> displacedByEnderDrives = new LinkedHashSet<>(beta10Channels);
        displacedByEnderDrives.removeAll(beta11Channels);
        Set<String> addedByEnderDrives = new LinkedHashSet<>(beta11Channels);
        addedByEnderDrives.removeAll(beta10Channels);
        assertEquals(2, displacedByEnderDrives.size());
        assertTrue(displacedByEnderDrives.stream().noneMatch(id ->
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.stream()
                        .anyMatch(pin -> pin.id().equals(id))));
        assertEquals(
                Set.of(
                        "enderdrives:request_disk_type_count",
                        "enderdrives:request_fluid_disk_type_count"),
                addedByEnderDrives);

        List<PinnedPlayChannel> beta14Pins = loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK))
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .RELICS_SHIELD_RELEASE_PLAY_SINK))
                .toList();
        PlaySinkPlanner.Plan beta14Plan = normal80Plan(
                query,
                loaded,
                beta14Pins,
                28);
        Set<String> beta14Channels = beta14Plan.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> displacedByTwilightForest = new LinkedHashSet<>(beta11Channels);
        displacedByTwilightForest.removeAll(beta14Channels);
        Set<String> addedByTwilightForest = new LinkedHashSet<>(beta14Channels);
        addedByTwilightForest.removeAll(beta11Channels);
        assertEquals(1, displacedByTwilightForest.size());
        assertTrue(displacedByTwilightForest.stream().noneMatch(id ->
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.stream()
                        .anyMatch(pin -> pin.id().equals(id))));
        assertEquals(Set.of("twilightforest:gradual_glide_packet"), addedByTwilightForest);

        Set<String> displacedByCurrentInteractions = new LinkedHashSet<>(beta14Channels);
        displacedByCurrentInteractions.removeAll(currentChannels);
        Set<String> addedByCurrentInteractions = new LinkedHashSet<>(currentChannels);
        addedByCurrentInteractions.removeAll(beta14Channels);
        assertEquals(7, displacedByCurrentInteractions.size());
        assertTrue(displacedByCurrentInteractions.stream().noneMatch(id ->
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.stream()
                        .anyMatch(pin -> pin.id().equals(id))));
        assertEquals(
                Set.of(
                        "eternal_starlight:simple_action",
                        "relics:shield_of_retaliation/release"),
                addedByCurrentInteractions);

        List<PinnedPlayChannel> preToolBeltPins = loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK))
                .toList();
        PlaySinkPlanner.Plan preToolBeltPlan = normal80Plan(
                query,
                loaded,
                preToolBeltPins,
                22);
        Set<String> preToolBeltChannels = preToolBeltPlan.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> displacedByToolBelt = new LinkedHashSet<>(preToolBeltChannels);
        displacedByToolBelt.removeAll(currentChannels);
        Set<String> addedByToolBelt = new LinkedHashSet<>(currentChannels);
        addedByToolBelt.removeAll(preToolBeltChannels);
        assertEquals(1, displacedByToolBelt.size());
        assertTrue(displacedByToolBelt.stream().noneMatch(id ->
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.stream()
                        .anyMatch(pin -> pin.id().equals(id))));
        assertEquals(Set.of("toolbelt:open_belt_slot_inventory"), addedByToolBelt);

        List<PinnedPlayChannel> preIronsSpellbooksPins = loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> !channel.equals(ReviewedAtmCompatibility
                        .IRONS_SPELLBOOKS_CAST_PLAY_SINK))
                .toList();
        PlaySinkPlanner.Plan preIronsSpellbooksPlan = normal80Plan(
                query,
                loaded,
                preIronsSpellbooksPins,
                22);
        Set<String> preIronsSpellbooksChannels = preIronsSpellbooksPlan.channels().stream()
                .map(NeoForgeHandshakeCodec.Channel::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> displacedByIronsSpellbooks =
                new LinkedHashSet<>(preIronsSpellbooksChannels);
        displacedByIronsSpellbooks.removeAll(currentChannels);
        Set<String> addedByIronsSpellbooks = new LinkedHashSet<>(currentChannels);
        addedByIronsSpellbooks.removeAll(preIronsSpellbooksChannels);
        assertEquals(1, displacedByIronsSpellbooks.size());
        assertTrue(displacedByIronsSpellbooks.stream().noneMatch(id ->
                ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.stream()
                        .anyMatch(pin -> pin.id().equals(id))));
        assertEquals(Set.of("irons_spellbooks:cast"), addedByIronsSpellbooks);

        PlaySinkPlanner.Plan controlsInputThreshold = normal80Plan(
                query,
                loaded,
                beta10Pins,
                36);
        assertTrue(controlsInputThreshold.channels().stream().anyMatch(
                channel -> channel.id().equals(
                "create:controls_input")));
        assertFalse(controlsInputThreshold.channels().stream().anyMatch(
                channel -> channel.id().equals(
                "create:left_click")));
        assertFalse(controlsInputThreshold.channels().stream().anyMatch(
                channel -> channel.id().equals(
                "create:linked_controller_input")));

        PlaySinkPlanner.Plan leftClickThreshold = normal80Plan(
                query,
                loaded,
                beta10Pins,
                37);
        assertTrue(leftClickThreshold.channels().stream().anyMatch(
                channel -> channel.id().equals(
                "create:left_click")));
        assertFalse(leftClickThreshold.channels().stream().anyMatch(
                channel -> channel.id().equals(
                "create:linked_controller_input")));

        PlaySinkPlanner.Plan linkedControllerThreshold = normal80Plan(
                query,
                loaded,
                beta10Pins,
                38);
        assertTrue(linkedControllerThreshold.channels().stream().anyMatch(
                channel -> channel.id().equals(
                "create:linked_controller_input")));
        assertArrayEquals(
                beforeLoad,
                Files.readAllBytes(temporaryDirectory.resolve("bridge.properties")));
    }

    @Test
    void transientServerConfigNamesAreOrderedBoundedAndPathSafe() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "transient-server-configs",
                "securitycraft-server.toml,example-server.toml,example/nested/config.toml"));
        assertEquals(
                List.of(
                        "neoforge-server.toml",
                        "create-server.toml",
                        "create_hypertube-server.toml",
                        "securitycraft-server.toml",
                        "productivefarming-server.toml",
                        "bhc-server.toml",
                        "utilitarian-server.toml",
                        "ars_nouveau/rewind.toml",
                        "matc-server.toml",
                        "Mekanism/general.toml",
                        "Mekanism/gear.toml",
                        "Mekanism/machine-storage.toml",
                        "Mekanism/tiers.toml",
                        "Mekanism/machine-usage.toml",
                        "Mekanism/world.toml",
                        "Mekanism/generators.toml",
                        "Mekanism/generators-gear.toml",
                        "Mekanism/generator-storage.toml",
                        "Mekanism/tools.toml",
                        "example-server.toml",
                        "example/nested/config.toml"),
                BridgeConfig.load(temporaryDirectory).transientServerConfigs());

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "transient-server-configs",
                "securitycraft-server.toml,securitycraft-server.toml"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "transient-server-configs", "../securitycraft-server.toml"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "transient-server-configs", "/ars_nouveau/rewind.toml"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "transient-server-configs", "ars_nouveau\\rewind.toml"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "transient-server-configs", "securitycraft-server.toml,"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> {
            properties.setProperty("enable-built-in-atm-server-configs", "false");
            properties.setProperty("enable-built-in-mekanism-compatibility", "false");
            properties.setProperty("transient-server-configs", "");
        });
        assertTrue(BridgeConfig.load(temporaryDirectory).transientServerConfigs().isEmpty());
    }

    @Test
    void booleansAndProtocolAllocationLimitsAreStrict() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty("debug", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-ae2-jei-session-optimization", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties ->
                properties.setProperty("allow-vanilla-lobby", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-backend-neoforge-capability-relay", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-legacy-forge-lobby-entry-guard", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties ->
                properties.setProperty("enable-built-in-atm-server-configs", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "derive-server-configs-from-client-channels", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties ->
                properties.setProperty("maximum-derived-server-configs", "1025"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties ->
                properties.setProperty("maximum-query-bytes", "32767"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> {
            properties.setProperty("maximum-channels", "100");
            properties.setProperty("maximum-channels-per-protocol", "101");
        });
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));
    }

    @Test
    void debugPreferenceDefaultsFalseAndHonorsExplicitTrueBeforeFullValidation()
            throws Exception {
        assertFalse(BridgeConfig.preferredDebug(temporaryDirectory));
        BridgeConfig.load(temporaryDirectory);
        assertFalse(BridgeConfig.preferredDebug(temporaryDirectory));

        updateProperties(properties -> {
            properties.setProperty("debug", "true");
            properties.setProperty("unexpected", "invalid");
        });
        assertTrue(BridgeConfig.preferredDebug(temporaryDirectory));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));
    }

    @Test
    void frozenRegistryAckTimeoutIsBoundedAndDefaultsWithoutRewritingOldV4()
            throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties ->
                properties.remove("frozen-registry-ack-timeout-millis"));
        byte[] before = Files.readAllBytes(temporaryDirectory.resolve("bridge.properties"));
        assertEquals(60_000, BridgeConfig.load(temporaryDirectory)
                .frozenRegistryAckTimeoutMillis());
        assertArrayEquals(
                before,
                Files.readAllBytes(temporaryDirectory.resolve("bridge.properties")));

        updateProperties(properties ->
                properties.setProperty("frozen-registry-ack-timeout-millis", "30000"));
        assertEquals(30_000, BridgeConfig.load(temporaryDirectory)
                .frozenRegistryAckTimeoutMillis());

        updateProperties(properties ->
                properties.setProperty("frozen-registry-ack-timeout-millis", "180000"));
        assertEquals(180_000, BridgeConfig.load(temporaryDirectory)
                .frozenRegistryAckTimeoutMillis());

        updateProperties(properties ->
                properties.setProperty("frozen-registry-ack-timeout-millis", "29999"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        updateProperties(properties ->
                properties.setProperty("frozen-registry-ack-timeout-millis", "180001"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));
    }

    @Test
    void playSinkConfigurationIsBoundedCanonicalAndMayBeDisabled() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> {
            properties.setProperty("enable-built-in-sophisticated-play-sinks", "false");
            properties.setProperty("enable-built-in-pneumaticcraft-play-sink", "false");
            properties.setProperty("enable-built-in-kubejs-play-sinks", "false");
            properties.setProperty("enable-built-in-placebo-play-sink", "false");
            properties.setProperty("enable-built-in-xycraft-play-sink", "false");
            properties.setProperty("enable-built-in-mekanism-compatibility", "false");
            properties.setProperty(
                    "enable-built-in-reviewed-lobby-interaction-play-sinks", "false");
            properties.setProperty("pinned-play-sink-channels", "");
            properties.setProperty("maximum-automatic-play-sink-channels", "0");
        });
        BridgeConfig disabled = BridgeConfig.load(temporaryDirectory);
        assertTrue(disabled.pinnedPlaySinkChannels().isEmpty());
        assertEquals(0, disabled.maximumAutomaticPlaySinkChannels());

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-reviewed-lobby-interaction-play-sinks", "false"));
        BridgeConfig withoutIncidentBundle = BridgeConfig.load(temporaryDirectory);
        assertEquals(11, withoutIncidentBundle.pinnedPlaySinkChannels().size());
        assertTrue(withoutIncidentBundle.pinnedPlaySinkChannels().stream()
                .noneMatch(channel -> ReviewedAtmCompatibility
                        .REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.contains(channel)));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "maximum-automatic-play-sink-channels", "60"));
        BridgeConfig clamped = BridgeConfig.load(temporaryDirectory);
        assertEquals(60, clamped.configuredMaximumAutomaticPlaySinkChannels());
        assertEquals(57, clamped.maximumAutomaticPlaySinkChannels());
        assertTrue(clamped.automaticPlaySinkBudgetClamped());

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "maximum-global-play-sink-channels", "13"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels", "INVALID:channel@1"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels", "voicechat:request_secret@voicechat"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels", "vc:request_secret@voicechat"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels",
                "sophisticatedbackpacks:request_player_settings"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels",
                "sophisticatedbackpacks:request_player_settings@2.0"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-sophisticated-play-sinks", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-pneumaticcraft-play-sink", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-kubejs-play-sinks", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-placebo-play-sink", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-xycraft-play-sink", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-apothic-enchanting-bootstrap", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-mekanism-compatibility", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-reviewed-lobby-interaction-play-sinks", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-silent-gear-snapshot-bridge", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> {
            properties.setProperty("maximum-silent-gear-payload-bytes", "1048576");
            properties.setProperty("maximum-silent-gear-total-bytes", "1048575");
        });
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> {
            properties.setProperty("maximum-lobby-play-payload-bytes", "1048576");
            properties.setProperty("maximum-lobby-play-total-bytes", "1048575");
        });
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "lobby-play-budget-refill-millis", "999"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "lobby-play-budget-refill-millis", "3600001"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));
    }

    @Test
    void registryShimConfigurationIsVersionedBoundedAndMayBeDisabled() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> {
            properties.setProperty("enable-built-in-atm-registry-shims", "false");
            properties.setProperty("registry-shims", "");
        });
        assertTrue(BridgeConfig.load(temporaryDirectory).registryShims().isEmpty());

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "registry-shims", "forbidden-arcanus-2.6.1,forbidden-arcanus-2.6.1"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "registry-shims", "unreviewed-registry-payload"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "maximum-registry-shim-bytes", "4095"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));

        resetDefaults();
        updateProperties(properties -> {
            properties.setProperty("enable-built-in-atm-registry-shims", "false");
            properties.setProperty("registry-shims", "ars-nouveau-5.11.3");
        });
        assertEquals(
                List.of("ars-nouveau-5.11.3"),
                BridgeConfig.load(temporaryDirectory).registryShims());

        resetDefaults();
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-atm-registry-shims", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(temporaryDirectory));
    }

    @Test
    void existingV013RegistryConfigurationEnablesReviewedBuiltIns() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> {
            properties.remove("enable-built-in-atm-registry-shims");
            properties.setProperty("registry-shims", "forbidden-arcanus-2.6.1");
        });

        assertEquals(
                RegistryShimCatalog.builtInAtmShimIds(),
                BridgeConfig.load(temporaryDirectory).registryShims());

        updateProperties(properties -> properties.setProperty("registry-shims", ""));
        assertTrue(BridgeConfig.load(temporaryDirectory).registryShims().isEmpty());
    }

    @Test
    void explicitV07BackpacksPinStillReceivesStorageCompatibilitySink() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> {
            properties.remove("enable-built-in-sophisticated-play-sinks");
            properties.setProperty(
                    "pinned-play-sink-channels",
                    "sophisticatedbackpacks:request_player_settings@1.0");
        });

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(
                List.of(
                        "sophisticatedbackpacks:request_player_settings",
                        "sophisticatedbackpacks:backpack_open",
                        "sophisticatedbackpacks:another_player_backpack_open",
                        "sophisticatedbackpacks:block_pick",
                        "sophisticatedstorage:request_player_settings",
                        "pneumaticcraft:sync_amadron_offers",
                        "kubejs:first_click",
                        "kubejs:kubedex/request_block",
                        "placebo:patreon_disable",
                        "xycraft_core:modifier_key",
                        "mekanism:key",
                        "ae2wtlib:pick_block",
                        "draconicevolution:network",
                        "curios:open_curios",
                        "cosmeticarmorreworked:open_cosarmor_inv",
                        "ftbteams:open_gui",
                        "notenoughwands:getprotectedblockcount",
                        "ftbultimine:key_pressed_packet",
                        "ftbultimine:mode_changed_packet",
                        "cb_multipart:network",
                        "mcjtylib:sendservercommand",
                        "translocators:network",
                        "simplemagnets:main",
                        "logisticsnetworks:set_default_node_visibility",
                        "structurize:notify_server_about_structure_packs",
                        "refinedstorage:set_tenth_anniversary_cape",
                        "accessories:main",
                        "aether:sync_aether_player_attachment",
                        "mahoutsukai:chunk_mahou_request_packet",
                        "creeperoverhaul:main/v1/creeperoverhaul/set_cosmetic",
                        "eternal_starlight:update_book_progression",
                        "eternal_starlight:simple_action",
                        "relics:shield_of_retaliation/release",
                        "enderdrives:request_disk_type_count",
                        "enderdrives:request_fluid_disk_type_count",
                        "twilightforest:gradual_glide_packet",
                        "deeperdarker:use_transmitter",
                        "toolbelt:open_belt_slot_inventory",
                        "irons_spellbooks:cast"),
                loaded.pinnedPlaySinkChannels().stream()
                        .map(PinnedPlayChannel::id)
                        .toList());
    }

    @Test
    void explicitPneumaticCraftPinIsDeduplicatedAgainstTheBuiltIn() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels",
                "pneumaticcraft:sync_amadron_offers@1"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(39, loaded.pinnedPlaySinkChannels().size());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.id().equals("pneumaticcraft:sync_amadron_offers"))
                .count());
    }

    @Test
    void reviewedCodecPinsAreDeduplicatedAgainstOperatorEntries()
            throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels",
                "mahoutsukai:chunk_mahou_request_packet@1,"
                        + "creeperoverhaul:main/v1/creeperoverhaul/set_cosmetic@v1,"
                        + "eternal_starlight:update_book_progression@eternal_starlight,"
                        + "eternal_starlight:simple_action@eternal_starlight,"
                        + "relics:shield_of_retaliation/release@1.0,"
                        + "enderdrives:request_disk_type_count@1.0,"
                        + "enderdrives:request_fluid_disk_type_count@1.0,"
                        + "twilightforest:gradual_glide_packet@1.0.0,"
                        + "toolbelt:open_belt_slot_inventory@1.0,"
                        + "irons_spellbooks:cast@1.0.0"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(39, loaded.pinnedPlaySinkChannels().size());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.equals(
                        ReviewedAtmCompatibility.MAHOU_TSUKAI_CHUNK_REQUEST_PLAY_SINK))
                .count());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.equals(
                        ReviewedAtmCompatibility.CREEPER_OVERHAUL_COSMETIC_PLAY_SINK))
                .count());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_BOOK_PROGRESSION_PLAY_SINK))
                .count());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.equals(ReviewedAtmCompatibility
                        .ETERNAL_STARLIGHT_SIMPLE_ACTION_PLAY_SINK))
                .count());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.equals(
                        ReviewedAtmCompatibility.RELICS_SHIELD_RELEASE_PLAY_SINK))
                .count());
        assertEquals(2L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.equals(ReviewedAtmCompatibility
                                .ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK)
                        || channel.equals(ReviewedAtmCompatibility
                                .ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK))
                .count());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.equals(ReviewedAtmCompatibility
                        .TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK))
                .count());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.equals(ReviewedAtmCompatibility
                        .TOOL_BELT_OPEN_BELT_SLOT_INVENTORY_PLAY_SINK))
                .count());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.equals(ReviewedAtmCompatibility
                        .IRONS_SPELLBOOKS_CAST_PLAY_SINK))
                .count());
    }

    @Test
    void operatorKubeJsFirstClickPinMigratesIntoTheBuiltInWithoutDuplication() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels",
                "kubejs:first_click@1"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(39, loaded.pinnedPlaySinkChannels().size());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.id().equals("kubejs:first_click"))
                .count());
    }

    @Test
    void operatorPlaceboPinMigratesIntoTheBuiltInWithoutDuplication() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels",
                "placebo:patreon_disable@1"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(39, loaded.pinnedPlaySinkChannels().size());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.id().equals("placebo:patreon_disable"))
                .count());
    }

    @Test
    void operatorMekanismPinMigratesIntoTheBuiltInWithoutDuplication() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels",
                "mekanism:key@10.7.18"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(39, loaded.pinnedPlaySinkChannels().size());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.id().equals("mekanism:key"))
                .count());
    }

    @Test
    void operatorXyCraftPinMigratesIntoTheBuiltInWithoutDuplication() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels",
                "xycraft_core:modifier_key@1.0.0"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(39, loaded.pinnedPlaySinkChannels().size());
        assertEquals(1L, loaded.pinnedPlaySinkChannels().stream()
                .filter(channel -> channel.id().equals("xycraft_core:modifier_key"))
                .count());
    }

    @Test
    void operatorUltimineAndMultipartPinsMigrateWithoutDuplication() throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "pinned-play-sink-channels",
                "ftbultimine:key_pressed_packet@ftbultimine,"
                        + "ftbultimine:mode_changed_packet@ftbultimine,"
                        + "cb_multipart:network@3.5.0.155"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(39, loaded.pinnedPlaySinkChannels().size());
        assertEquals(
                3L,
                loaded.pinnedPlaySinkChannels().stream()
                        .filter(channel -> channel.id().startsWith("ftbultimine:")
                                || channel.id().equals("cb_multipart:network"))
                        .count());
    }

    @Test
    void operatorMekanismConfigIsDeduplicatedAgainstTheExactCaseSensitiveBuiltIn()
            throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "transient-server-configs",
                "Mekanism/general.toml"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertEquals(19, loaded.transientServerConfigs().size());
        assertEquals(1L, loaded.transientServerConfigs().stream()
                .filter(config -> config.equals("Mekanism/general.toml"))
                .count());
    }

    @Test
    void mekanismCompatibilityBundleMayBeDisabledWithoutAffectingPriorBuiltIns()
            throws Exception {
        BridgeConfig.load(temporaryDirectory);
        updateProperties(properties -> properties.setProperty(
                "enable-built-in-mekanism-compatibility", "false"));

        BridgeConfig loaded = BridgeConfig.load(temporaryDirectory);

        assertFalse(loaded.enableBuiltInMekanismCompatibility());
        assertEquals(9, loaded.transientServerConfigs().size());
        assertTrue(loaded.transientServerConfigs().contains("matc-server.toml"));
        assertTrue(loaded.transientServerConfigs().stream()
                .noneMatch(config -> config.startsWith("Mekanism/")));
        assertEquals(38, loaded.pinnedPlaySinkChannels().size());
        assertTrue(loaded.pinnedPlaySinkChannels().stream()
                .noneMatch(channel -> channel.id().equals("mekanism:key")));
    }

    private static PlaySinkPlanner.Plan normal80Plan(
            byte[] query,
            BridgeConfig config,
            List<PinnedPlayChannel> pinned,
            int maximumAutomaticChannels) throws Exception {
        NeoForgeHandshakeCodec.Registry registry = NeoForgeHandshakeCodec.decodeLobbyQuery(
                query, config.limits());
        return PlaySinkPlanner.plan(
                config.expectedMinecraftProtocol(),
                registry,
                pinned,
                maximumAutomaticChannels,
                true,
                true,
                true,
                Set.of());
    }

    private void configureTwoDestinations() throws Exception {
        updateProperties(properties -> {
            properties.setProperty("destinations", "normal,sky");
            properties.setProperty("destination.normal.display-name", "ATM10 Normal #1");
            properties.setProperty("destination.normal.velocity-server", "atm10-normal-1");
            properties.setProperty("destination.sky.display-name", "ATM10 To The Sky #1");
            properties.setProperty("destination.sky.velocity-server", "atm10-sky-1");
        });
    }

    private void resetDefaults() throws Exception {
        Files.delete(temporaryDirectory.resolve("bridge.properties"));
        BridgeConfig.load(temporaryDirectory);
    }

    private void updateProperties(PropertyMutation mutation) throws Exception {
        Path path = temporaryDirectory.resolve("bridge.properties");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        mutation.apply(properties);
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, "test mutation");
        }
    }

    @FunctionalInterface
    private interface PropertyMutation {
        void apply(Properties properties);
    }

}
