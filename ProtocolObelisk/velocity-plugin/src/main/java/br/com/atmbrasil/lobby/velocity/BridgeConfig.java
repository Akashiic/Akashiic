package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeConfigPath;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict configuration for a universal lobby. Client modpack identity is deliberately absent. */
record BridgeConfig(
        boolean debug,
        boolean enabled,
        String lobbyServer,
        int expectedMinecraftProtocol,
        int handshakeProbeTimeoutMillis,
        int frozenRegistryAckTimeoutMillis,
        boolean allowVanillaLobby,
        boolean enableLegacyForgeHandoffGuard,
        List<String> legacyForgeHandoffTargets,
        boolean enableLegacyForgeLobbyEntryGuard,
        int maximumLegacyLobbyClientChannels,
        Set<String> allowedLegacyLobbyClientChannels,
        boolean enableNecroTempusTransport,
        int necroTempusMaximumCompressedNbtBytes,
        int necroTempusMaximumDecompressedNbtBytes,
        int necroTempusMaximumTabBridgeMessageBytes,
        int necroTempusMaximumTabTextBytes,
        int necroTempusMaximumPacketsPerSecond,
        int necroTempusMaximumBytesPerSecond,
        boolean enableBackendNeoForgeCapabilityRelay,
        boolean enableBuiltInAe2JeiSessionOptimization,
        boolean enableBuiltInApothicEnchantingBootstrap,
        boolean enableBuiltInMekanismCompatibility,
        boolean enableBuiltInReviewedLobbyInteractionPlaySinks,
        boolean enableBuiltInSilentGearSnapshotBridge,
        int maximumSilentGearPayloadBytes,
        int maximumSilentGearTotalBytes,
        int maximumSilentGearSnapshots,
        List<String> registryShims,
        int maximumRegistryShimBytes,
        List<String> transientServerConfigs,
        boolean deriveServerConfigsFromClientChannels,
        int maximumDerivedServerConfigs,
        List<PinnedPlayChannel> pinnedPlaySinkChannels,
        int configuredMaximumAutomaticPlaySinkChannels,
        int maximumAutomaticPlaySinkChannels,
        int maximumGlobalPlaySinkChannels,
        int maximumGlobalPlaySinkChannelBytes,
        int maximumLobbyPlayPayloadBytes,
        int maximumLobbyPlayTotalBytes,
        int maximumLobbyPlayPackets,
        int lobbyPlayBudgetRefillMillis,
        boolean legacyRoutingConfigurationIgnored,
        ProtocolLimits limits) {

    static final int CONFIG_VERSION = 4;

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern LEGACY_DESTINATION_KEY = Pattern.compile(
            "destination\\.[a-z0-9][a-z0-9_-]{0,47}\\.(display-name|velocity-server)");
    private static final Pattern REGISTRY_SHIM_ID = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final List<String> BUILT_IN_ATM_SERVER_CONFIGS = List.of(
            // NeoForge itself registers a SERVER spec. Capability-only clients do not
            // select a Silent Gear profile, so namespace derivation is intentionally off.
            // Keep this reviewed loader filename independent of any modpack fingerprint.
            // The existing ordered CONFIGURATION prefix sends an empty in-memory TOML;
            // the client's own spec supplies defaults. Never suppress entity exceptions.
            "neoforge-server.toml",
            // Create 6.0.10 PlayerMixin.pretendNotPassenger reads its SERVER spec
            // on every player aiStep, even when the player is not a passenger.
            // Prepare the reviewed base-mod config before the Hypertube addon.
            // Empty TOML uses the client's own defaults, not a borrowed pack config.
            "create-server.toml",
            // Hypertube 0.6.0 reads ENTITY_LIST_MODE during client entity ticks.
            // Its SERVER registration is create_hypertube-server.toml, independently
            // of a Silent Gear/profile match. Do not inject values or guess more names.
            "create_hypertube-server.toml",
            // Sophisticated Backpacks 3.26.x initializes ENTITY backpack weights from
            // Config.SERVER during the first client entity tick. Prepare the exact
            // NeoForge SERVER filename so the client spec can load its own defaults.
            "sophisticatedbackpacks-server.toml",
            // ElevatorMod 1.21.1-1.11.4 reads activationRange on jump/sneak input,
            // before checking whether the current block is an elevator. Its registered
            // SERVER spec needs preparation during CONFIGURATION, not after PLAY starts.
            // Empty TOML preserves client-defined defaults; never alter movement rules.
            "elevatorid-server.toml",
            "securitycraft-server.toml",
            "productivefarming-server.toml",
            "bhc-server.toml",
            "utilitarian-server.toml",
            "ars_nouveau/rewind.toml",
            ReviewedAtmCompatibility.MATC_SERVER_CONFIG);
    private static final List<PinnedPlayChannel> BUILT_IN_SOPHISTICATED_PLAY_SINK_CHANNELS = List.of(
            new PinnedPlayChannel(
                    "sophisticatedbackpacks:request_player_settings", "1.0"),
            ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_OPEN_PLAY_SINK,
            ReviewedAtmCompatibility
                    .SOPHISTICATED_BACKPACKS_ANOTHER_PLAYER_BACKPACK_OPEN_PLAY_SINK,
            ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_BLOCK_PICK_PLAY_SINK,
            new PinnedPlayChannel(
                    "sophisticatedstorage:request_player_settings", "1.0"));
    private static final PinnedPlayChannel BUILT_IN_PNEUMATICCRAFT_PLAY_SINK =
            new PinnedPlayChannel("pneumaticcraft:sync_amadron_offers", "1");
    private static final List<PinnedPlayChannel> BUILT_IN_KUBEJS_PLAY_SINK_CHANNELS = List.of(
            new PinnedPlayChannel("kubejs:first_click", "1"),
            ReviewedAtmCompatibility.KUBEJS_KUBEDEX_REQUEST_BLOCK_PLAY_SINK);
    private static final List<String> DEFAULT_LEGACY_FORGE_HANDOFF_TARGETS =
            List.of("forbidden-1");
    private static final Set<String> DEFAULT_ALLOWED_LEGACY_LOBBY_CLIENT_CHANNELS =
            Set.of("necrotempus:main");
    private static final int DEFAULT_MAXIMUM_LEGACY_LOBBY_CLIENT_CHANNELS = 16;
    private static final int DEFAULT_MAXIMUM_DERIVED_SERVER_CONFIGS = 512;
    private static final int DEFAULT_MAXIMUM_REGISTRY_SHIM_BYTES = 65_536;
    private static final int DEFAULT_MAXIMUM_AUTOMATIC_PLAY_SINK_CHANNELS = 48;
    private static final int LEGACY_DEFAULT_MAXIMUM_GLOBAL_PLAY_SINK_CHANNELS = 64;
    private static final int DEFAULT_MAXIMUM_GLOBAL_PLAY_SINK_CHANNELS = 96;
    private static final int DEFAULT_MAXIMUM_GLOBAL_PLAY_SINK_CHANNEL_BYTES = 16_384;
    private static final int DEFAULT_MAXIMUM_LOBBY_PLAY_PAYLOAD_BYTES = 1_048_576;
    private static final int DEFAULT_MAXIMUM_LOBBY_PLAY_TOTAL_BYTES = 8_388_608;
    private static final int DEFAULT_MAXIMUM_LOBBY_PLAY_PACKETS = 512;
    private static final int DEFAULT_LOBBY_PLAY_BUDGET_REFILL_MILLIS = 60_000;
    private static final int DEFAULT_MAXIMUM_SILENT_GEAR_PAYLOAD_BYTES = 1_048_576;
    private static final int DEFAULT_MAXIMUM_SILENT_GEAR_TOTAL_BYTES = 3_145_728;
    private static final int DEFAULT_MAXIMUM_SILENT_GEAR_SNAPSHOTS = 16;
    private static final int DEFAULT_FROZEN_REGISTRY_ACK_TIMEOUT_MILLIS = 60_000;
    private static final Set<String> FIXED_KEYS = Set.of(
            "config-version",
            "debug",
            "enabled",
            "lobby-server",
            "expected-minecraft-protocol",
            "handshake-probe-timeout-millis",
            "frozen-registry-ack-timeout-millis",
            "allow-vanilla-lobby",
            "enable-legacy-forge-handoff-guard",
            "legacy-forge-handoff-targets",
            "enable-legacy-forge-lobby-entry-guard",
            "maximum-legacy-lobby-client-channels",
            "allowed-legacy-lobby-client-channels",
            "enable-necrotempus-transport",
            "necrotempus-maximum-compressed-nbt-bytes",
            "necrotempus-maximum-decompressed-nbt-bytes",
            "necrotempus-maximum-tab-bridge-message-bytes",
            "necrotempus-maximum-tab-text-bytes",
            "necrotempus-maximum-packets-per-second",
            "necrotempus-maximum-bytes-per-second",
            "enable-backend-neoforge-capability-relay",
            "enable-built-in-ae2-jei-session-optimization",
            "enable-built-in-apothic-enchanting-bootstrap",
            "enable-built-in-mekanism-compatibility",
            "enable-built-in-reviewed-lobby-interaction-play-sinks",
            "enable-built-in-silent-gear-snapshot-bridge",
            "maximum-silent-gear-payload-bytes",
            "maximum-silent-gear-total-bytes",
            "maximum-silent-gear-snapshots",
            "enable-built-in-atm-registry-shims",
            "registry-shims",
            "maximum-registry-shim-bytes",
            "enable-built-in-atm-server-configs",
            "transient-server-configs",
            "derive-server-configs-from-client-channels",
            "maximum-derived-server-configs",
            "enable-built-in-sophisticated-play-sinks",
            "enable-built-in-pneumaticcraft-play-sink",
            "enable-built-in-kubejs-play-sinks",
            "enable-built-in-placebo-play-sink",
            "enable-built-in-xycraft-play-sink",
            "pinned-play-sink-channels",
            "maximum-automatic-play-sink-channels",
            "maximum-global-play-sink-channels",
            "maximum-global-play-sink-channel-bytes",
            "maximum-lobby-play-payload-bytes",
            "maximum-lobby-play-total-bytes",
            "maximum-lobby-play-packets",
            "lobby-play-budget-refill-millis",
            "destinations",
            "maximum-query-bytes",
            "maximum-setup-bytes",
            "maximum-channels",
            "maximum-channels-per-protocol",
            "maximum-resource-location-bytes",
            "maximum-version-bytes");
    private static final String DEFAULT_CONFIGURATION = """
            # ProtocolObelisk configuration v4. Compatibility remains permissive;
            # reviewed profiles only gate rigorously versioned bootstrap payloads.
            # Velocity alone owns initial-server choice, /server, fallback and backend routing.
            config-version=4
            debug=false
            enabled=false
            lobby-server=lobby
            expected-minecraft-protocol=767
            handshake-probe-timeout-millis=5000
            # Client-ACK phase deadline. The preceding Netty single-flush write has its own
            # fail-closed 20-second deadline. Production evidence reached 21 seconds in client
            # processing alone, so a full 60 seconds starts only after that write succeeds.
            frozen-registry-ack-timeout-millis=60000
            allow-vanilla-lobby=true

            # Minecraft 1.7.10 Forge (protocol 5) emits REGISTER/UNREGISTER while Velocity
            # already has a target connection in flight but still exposes the old lobby as the
            # connected backend. Suppress only that old-lobby copy for explicitly reviewed
            # targets; Velocity still tracks the channels and replays them to the Forge backend.
            # These names observe an existing Velocity transition and never select or start one.
            enable-legacy-forge-handoff-guard=true
            legacy-forge-handoff-targets=forbidden-1

            # Complementary protocol-5 fence for initial/fallback/forbidden -> lobby joins.
            # Velocity's large Forge channel replay is reduced before Paper sees it; global
            # channel tracking remains untouched, so the full set can still reach Crucible.
            enable-legacy-forge-lobby-entry-guard=true
            maximum-legacy-lobby-client-channels=16
            allowed-legacy-lobby-client-channels=necrotempus:main

            # Embedded Velocity transport for the Forbidden/Crucible NecroTempus UI protocol.
            # All diagnostic traces use the root debug switch; protocol failures remain visible.
            enable-necrotempus-transport=true
            necrotempus-maximum-compressed-nbt-bytes=30000
            necrotempus-maximum-decompressed-nbt-bytes=262144
            necrotempus-maximum-tab-bridge-message-bytes=30000
            necrotempus-maximum-tab-text-bytes=16384
            necrotempus-maximum-packets-per-second=64
            necrotempus-maximum-bytes-per-second=524288

            # During a Velocity /server transition, a NeoForge backend sends its empty
            # capability query immediately before the vanilla-detection ping. Relay the exact,
            # validated advertisement captured from this same client in the lobby while still
            # forwarding the backend query to the client. The backend remains authoritative for
            # every version/channel compatibility decision; Velocity remains routing authority.
            enable-backend-neoforge-capability-relay=true

            # Exact ATM10 TTS 2.0.2 session-only AE2 CLIENT config. It keeps facades
            # visible in JEI so AE2-JEI does not remove ~12k-16k entries one-by-one.
            # The client file is never written; the in-memory value lasts until game exit.
            enable-built-in-ae2-jei-session-optimization=true

            # The bridge negotiates this exact clientbound PLAY channel only when the
            # client advertises Apothic Enchanting. A bounded fallback-state payload is
            # sent after lobby connection so JEI/creative inventory can initialize.
            enable-built-in-apothic-enchanting-bootstrap=true

            # Exact Mekanism 1.21.1-10.7.18.84 compatibility. This enables its
            # serverbound hotkey sink and all ten SERVER config identifiers registered by
            # the installed base, Generators and Tools modules. Payloads/configs stay in memory.
            enable-built-in-mekanism-compatibility=true

            # Exact serverbound interaction channels proven by ATM10 TTS 2.0.2 client
            # crash reports/latest.log and reviewed against their owning mods: AE2WTLib
            # pick-block, Draconic Evolution input, Curios inventory, Cosmetic Armor
            # inventory, FTB Teams GUI, Not Enough Wands, FTB Ultimine, CBMultipart,
            # McJtyLib/RFTools Builder and Translocators hotkeys. Velocity
            # negotiates and consumes them as bounded no-ops; nothing is forwarded to Paper
            # and no modded server behavior is emulated.
            enable-built-in-reviewed-lobby-interaction-play-sinks=true

            # Silent Gear 1.21.1-4.1.3.1 requires three real datapack maps before
            # opening the creative inventory. The reviewed ATM10 TTS 2.0.2 profile is
            # bundled in the Velocity JAR and selected only for its exact PLAY contract.
            # No backend seed, runtime snapshot file or client modification is required.
            enable-built-in-silent-gear-snapshot-bridge=true
            maximum-silent-gear-payload-bytes=1048576
            maximum-silent-gear-total-bytes=3145728
            # Legacy v4 key retained and bounded for migration; no runtime store is allocated.
            maximum-silent-gear-snapshots=16

            # Reviewed dynamic registries sent during CONFIGURATION only when the client
            # advertises the owning namespace. Version-specific payloads may additionally
            # require the exact full client contract. The built-ins cover Forbidden Arcanus,
            # Ars Nouveau, Ad Astra Giselle, the contract-gated ATM10 8.1 Iron's Spellbooks
            # registry, the full 8.1 enchantment replacement and the paired NeoVitae sentient
            # registry/tags closure. Exact 8.1 resources require protocol 767 plus the complete
            # contract; neither namespace nor fingerprint is an admission or routing predicate.
            # Set the switch false to use only optional reviewed shim ids.
            enable-built-in-atm-registry-shims=true
            registry-shims=
            maximum-registry-shim-bytes=65536

            # Empty SERVER configs sent only in memory during NeoForge CONFIGURATION.
            # Flat and reviewed nested NeoForge names are supported; no local path is touched.
            # The effective proven baseline is written explicitly for operator visibility.
            # MATC and Mekanism's custom case-sensitive directory cannot be derived correctly
            # from the client channel registry and are included in this reviewed baseline.
            # The built-in switches also protect older v4 files whose list was blank.
            # Mekanism bundle: Mekanism/general.toml, Mekanism/gear.toml,
            # Mekanism/machine-storage.toml, Mekanism/tiers.toml,
            # Mekanism/machine-usage.toml, Mekanism/world.toml,
            # Mekanism/generators.toml, Mekanism/generators-gear.toml,
            # Mekanism/generator-storage.toml and Mekanism/tools.toml.
            enable-built-in-atm-server-configs=true
            transient-server-configs=neoforge-server.toml,create-server.toml,create_hypertube-server.toml,sophisticatedbackpacks-server.toml,elevatorid-server.toml,securitycraft-server.toml,productivefarming-server.toml,bhc-server.toml,utilitarian-server.toml,ars_nouveau/rewind.toml,matc-server.toml

            # A real NeoForge server syncs every registered SERVER config. Paper cannot list
            # them, so derive the conventional <network-namespace>-server.toml candidates from
            # the bounded client registry. Unknown filenames are ignored by NeoForge.
            derive-server-configs-from-client-channels=true
            maximum-derived-server-configs=512

            # Client-to-server PLAY payloads are consumed by Velocity and never reach Paper.
            # The built-ins cover the exact Backpacks (including middle-click block_pick),
            # Storage, PneumaticCraft, KubeJS, Placebo, Mekanism, XyCraft and the
            # reviewed interaction bundle (including Simple Magnets, Accessories and
            # Eternal Starlight's bounded progression observation plus the two exact,
            # optional EnderDrives type-count requests)
            # observed during real ATM lobby joins.
            enable-built-in-sophisticated-play-sinks=true
            enable-built-in-pneumaticcraft-play-sink=true
            enable-built-in-kubejs-play-sinks=true
            enable-built-in-placebo-play-sink=true
            # XyCraft Core 0.7.53 binds modifier_key to left Ctrl by default. Without
            # its exact 1.0.0 PLAY contract, NeoForge crashes before sending the key state.
            enable-built-in-xycraft-play-sink=true
            # Optional additional id@version fallbacks for future, verified login payloads.
            pinned-play-sink-channels=
            # This is a requested maximum, not a reservation. If pins leave fewer slots
            # below the global limit, the bridge clamps the effective automatic budget
            # in memory and reports both values without rewriting this file.
            maximum-automatic-play-sink-channels=48
            # 96 preserves the complete adaptive sink budget while remaining below Paper's
            # default 128-channel registration ceiling. Existing v4 files at the old 64/48
            # defaults are expanded only in memory; operator files are never rewritten.
            maximum-global-play-sink-channels=96
            maximum-global-play-sink-channel-bytes=16384
            maximum-lobby-play-payload-bytes=1048576
            # The next two values are burst capacities, not lifetime quotas. They refill
            # continuously and recover completely over the configured idle period.
            maximum-lobby-play-total-bytes=8388608
            maximum-lobby-play-packets=512
            lobby-play-budget-refill-millis=60000

            maximum-query-bytes=1048576
            maximum-setup-bytes=1048576
            maximum-channels=16384
            maximum-channels-per-protocol=16384
            maximum-resource-location-bytes=256
            maximum-version-bytes=256
            """;

    BridgeConfig {
        Objects.requireNonNull(lobbyServer, "lobbyServer");
        legacyForgeHandoffTargets = List.copyOf(
                Objects.requireNonNull(
                        legacyForgeHandoffTargets, "legacyForgeHandoffTargets"));
        allowedLegacyLobbyClientChannels = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(
                        allowedLegacyLobbyClientChannels,
                        "allowedLegacyLobbyClientChannels")));
        registryShims = List.copyOf(Objects.requireNonNull(registryShims, "registryShims"));
        transientServerConfigs = List.copyOf(
                Objects.requireNonNull(transientServerConfigs, "transientServerConfigs"));
        pinnedPlaySinkChannels = List.copyOf(
                Objects.requireNonNull(pinnedPlaySinkChannels, "pinnedPlaySinkChannels"));
        Objects.requireNonNull(limits, "limits");
    }

    boolean automaticPlaySinkBudgetClamped() {
        return maximumAutomaticPlaySinkChannels
                < configuredMaximumAutomaticPlaySinkChannels;
    }

    static BridgeConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("bridge.properties");
        if (Files.notExists(configPath)) {
            writeDefaults(configPath);
        }

        Properties properties = loadProperties(configPath);
        String rawVersion = properties.getProperty("config-version", "").strip();
        if (!rawVersion.equals(Integer.toString(CONFIG_VERSION))) {
            Path example = dataDirectory.resolve("bridge-v4.example.properties");
            if (Files.notExists(example)) {
                writeDefaults(example);
            }
            throw new IllegalArgumentException(
                    "bridge.properties is not config-version=4; migrate using bridge-v4.example.properties");
        }

        boolean debug = strictBooleanOrDefault(properties, "debug", false);
        boolean enabled = strictBoolean(properties, "enabled");
        String lobbyServer = required(properties, "lobby-server");
        requireServerName(lobbyServer, "lobby-server");
        int expectedProtocol = boundedInt(
                properties, "expected-minecraft-protocol", 1, 10_000);
        int timeoutMillis = boundedInt(
                properties, "handshake-probe-timeout-millis", 250, 15_000);
        int frozenRegistryAckTimeoutMillis = boundedIntOrDefault(
                properties,
                "frozen-registry-ack-timeout-millis",
                30_000,
                180_000,
                DEFAULT_FROZEN_REGISTRY_ACK_TIMEOUT_MILLIS);
        boolean allowVanilla = strictBoolean(properties, "allow-vanilla-lobby");
        boolean enableLegacyForgeHandoffGuard = strictBooleanOrDefault(
                properties, "enable-legacy-forge-handoff-guard", true);
        List<String> legacyForgeHandoffTargets = parseLegacyForgeHandoffTargets(
                properties, enableLegacyForgeHandoffGuard, lobbyServer);
        boolean enableLegacyForgeLobbyEntryGuard = strictBooleanOrDefault(
                properties, "enable-legacy-forge-lobby-entry-guard", true);
        int maximumLegacyLobbyClientChannels = boundedIntOrDefault(
                properties,
                "maximum-legacy-lobby-client-channels",
                1,
                64,
                DEFAULT_MAXIMUM_LEGACY_LOBBY_CLIENT_CHANNELS);
        Set<String> allowedLegacyLobbyClientChannels =
                parseAllowedLegacyLobbyClientChannels(
                        properties, maximumLegacyLobbyClientChannels);
        boolean enableNecroTempusTransport = strictBooleanOrDefault(
                properties, "enable-necrotempus-transport", true);
        int necroTempusMaximumCompressedNbtBytes = boundedIntOrDefault(
                properties,
                "necrotempus-maximum-compressed-nbt-bytes",
                1,
                32_767,
                30_000);
        int necroTempusMaximumDecompressedNbtBytes = boundedIntOrDefault(
                properties,
                "necrotempus-maximum-decompressed-nbt-bytes",
                1,
                4_194_304,
                262_144);
        int necroTempusMaximumTabBridgeMessageBytes = boundedIntOrDefault(
                properties,
                "necrotempus-maximum-tab-bridge-message-bytes",
                42,
                32_767,
                30_000);
        int necroTempusMaximumTabTextBytes = boundedIntOrDefault(
                properties,
                "necrotempus-maximum-tab-text-bytes",
                1,
                65_535,
                16_384);
        int necroTempusMaximumPacketsPerSecond = boundedIntOrDefault(
                properties,
                "necrotempus-maximum-packets-per-second",
                1,
                1_000,
                64);
        int necroTempusMaximumBytesPerSecond = boundedIntOrDefault(
                properties,
                "necrotempus-maximum-bytes-per-second",
                1,
                16_777_216,
                524_288);
        boolean enableBackendNeoForgeCapabilityRelay = strictBooleanOrDefault(
                properties, "enable-backend-neoforge-capability-relay", true);
        boolean enableBuiltInAe2JeiSessionOptimization = strictBooleanOrDefault(
                properties, "enable-built-in-ae2-jei-session-optimization", true);
        boolean enableBuiltInApothicEnchantingBootstrap = strictBooleanOrDefault(
                properties, "enable-built-in-apothic-enchanting-bootstrap", true);
        boolean enableBuiltInMekanismCompatibility = strictBooleanOrDefault(
                properties, "enable-built-in-mekanism-compatibility", true);
        boolean enableBuiltInReviewedLobbyInteractionPlaySinks = strictBooleanOrDefault(
                properties,
                "enable-built-in-reviewed-lobby-interaction-play-sinks",
                true);
        boolean enableBuiltInSilentGearSnapshotBridge = strictBooleanOrDefault(
                properties, "enable-built-in-silent-gear-snapshot-bridge", true);
        int maximumSilentGearPayloadBytes = boundedIntOrDefault(
                properties,
                "maximum-silent-gear-payload-bytes",
                1,
                1_048_576,
                DEFAULT_MAXIMUM_SILENT_GEAR_PAYLOAD_BYTES);
        int maximumSilentGearTotalBytes = boundedIntOrDefault(
                properties,
                "maximum-silent-gear-total-bytes",
                1,
                3_145_728,
                DEFAULT_MAXIMUM_SILENT_GEAR_TOTAL_BYTES);
        int maximumSilentGearSnapshots = boundedIntOrDefault(
                properties,
                "maximum-silent-gear-snapshots",
                1,
                64,
                DEFAULT_MAXIMUM_SILENT_GEAR_SNAPSHOTS);
        if (maximumSilentGearTotalBytes < maximumSilentGearPayloadBytes) {
            throw new IllegalArgumentException(
                    "maximum-silent-gear-total-bytes cannot be below the per-payload limit");
        }
        boolean enableBuiltInAtmRegistryShims = strictBooleanOrDefault(
                properties,
                "enable-built-in-atm-registry-shims",
                legacyRegistryShimsDefaultEnabled(properties));
        List<String> registryShims = parseRegistryShims(
                properties, enableBuiltInAtmRegistryShims);
        int maximumRegistryShimBytes = boundedIntOrDefault(
                properties,
                "maximum-registry-shim-bytes",
                4_096,
                1_048_576,
                DEFAULT_MAXIMUM_REGISTRY_SHIM_BYTES);
        boolean enableBuiltInAtmServerConfigs = strictBooleanOrDefault(
                properties, "enable-built-in-atm-server-configs", true);
        List<String> transientServerConfigs = parseTransientServerConfigs(
                properties,
                enableBuiltInAtmServerConfigs,
                enableBuiltInMekanismCompatibility);
        boolean deriveServerConfigsFromClientChannels = strictBooleanOrDefault(
                properties, "derive-server-configs-from-client-channels", true);
        int maximumDerivedServerConfigs = boundedIntOrDefault(
                properties,
                "maximum-derived-server-configs",
                0,
                1_024,
                DEFAULT_MAXIMUM_DERIVED_SERVER_CONFIGS);
        boolean enableBuiltInSophisticatedPlaySinks = strictBooleanOrDefault(
                properties, "enable-built-in-sophisticated-play-sinks", true);
        boolean enableBuiltInPneumaticCraftPlaySink = strictBooleanOrDefault(
                properties, "enable-built-in-pneumaticcraft-play-sink", true);
        boolean enableBuiltInKubeJsPlaySinks = strictBooleanOrDefault(
                properties, "enable-built-in-kubejs-play-sinks", true);
        boolean enableBuiltInPlaceboPlaySink = strictBooleanOrDefault(
                properties, "enable-built-in-placebo-play-sink", true);
        boolean enableBuiltInXyCraftPlaySink = strictBooleanOrDefault(
                properties, "enable-built-in-xycraft-play-sink", true);
        List<PinnedPlayChannel> pinnedPlaySinkChannels = parsePinnedPlaySinkChannels(
                properties,
                enableBuiltInSophisticatedPlaySinks,
                enableBuiltInPneumaticCraftPlaySink,
                enableBuiltInKubeJsPlaySinks,
                enableBuiltInPlaceboPlaySink,
                enableBuiltInXyCraftPlaySink,
                enableBuiltInMekanismCompatibility,
                enableBuiltInReviewedLobbyInteractionPlaySinks);
        int configuredMaximumAutomaticPlaySinkChannels = boundedIntOrDefault(
                properties,
                "maximum-automatic-play-sink-channels",
                0,
                64,
                DEFAULT_MAXIMUM_AUTOMATIC_PLAY_SINK_CHANNELS);
        int configuredMaximumGlobalPlaySinkChannels = boundedIntOrDefault(
                properties,
                "maximum-global-play-sink-channels",
                1,
                96,
                DEFAULT_MAXIMUM_GLOBAL_PLAY_SINK_CHANNELS);
        int requestedServiceFirstCapacity = Math.min(
                DEFAULT_MAXIMUM_GLOBAL_PLAY_SINK_CHANNELS,
                pinnedPlaySinkChannels.size() + configuredMaximumAutomaticPlaySinkChannels);
        int maximumGlobalPlaySinkChannels =
                configuredMaximumGlobalPlaySinkChannels
                                        == LEGACY_DEFAULT_MAXIMUM_GLOBAL_PLAY_SINK_CHANNELS
                                && configuredMaximumAutomaticPlaySinkChannels
                                        == DEFAULT_MAXIMUM_AUTOMATIC_PLAY_SINK_CHANNELS
                        ? Math.max(
                                configuredMaximumGlobalPlaySinkChannels,
                                requestedServiceFirstCapacity)
                        : configuredMaximumGlobalPlaySinkChannels;
        int maximumGlobalPlaySinkChannelBytes = boundedIntOrDefault(
                properties,
                "maximum-global-play-sink-channel-bytes",
                1_024,
                24_576,
                DEFAULT_MAXIMUM_GLOBAL_PLAY_SINK_CHANNEL_BYTES);
        int maximumLobbyPlayPayloadBytes = boundedIntOrDefault(
                properties,
                "maximum-lobby-play-payload-bytes",
                1,
                1_048_576,
                DEFAULT_MAXIMUM_LOBBY_PLAY_PAYLOAD_BYTES);
        int maximumLobbyPlayTotalBytes = boundedIntOrDefault(
                properties,
                "maximum-lobby-play-total-bytes",
                1,
                67_108_864,
                DEFAULT_MAXIMUM_LOBBY_PLAY_TOTAL_BYTES);
        int maximumLobbyPlayPackets = boundedIntOrDefault(
                properties,
                "maximum-lobby-play-packets",
                1,
                4_096,
                DEFAULT_MAXIMUM_LOBBY_PLAY_PACKETS);
        int lobbyPlayBudgetRefillMillis = boundedIntOrDefault(
                properties,
                "lobby-play-budget-refill-millis",
                1_000,
                3_600_000,
                DEFAULT_LOBBY_PLAY_BUDGET_REFILL_MILLIS);
        if (pinnedPlaySinkChannels.size() > maximumGlobalPlaySinkChannels) {
            throw new IllegalArgumentException(
                    "pinned-play-sink-channels exceeds maximum-global-play-sink-channels");
        }
        int maximumAutomaticPlaySinkChannels = Math.min(
                configuredMaximumAutomaticPlaySinkChannels,
                maximumGlobalPlaySinkChannels - pinnedPlaySinkChannels.size());
        if (maximumLobbyPlayTotalBytes < maximumLobbyPlayPayloadBytes) {
            throw new IllegalArgumentException(
                    "maximum-lobby-play-total-bytes cannot be below the per-payload limit");
        }

        int maxQuery = boundedInt(
                properties, "maximum-query-bytes", 32_768, 1_048_576);
        int maxSetup = boundedInt(
                properties, "maximum-setup-bytes", 1_024, 1_048_576);
        int maxChannels = boundedInt(properties, "maximum-channels", 1, 16_384);
        int maxPerProtocol = boundedInt(
                properties, "maximum-channels-per-protocol", 1, 16_384);
        if (maxPerProtocol > maxChannels) {
            throw new IllegalArgumentException(
                    "maximum-channels-per-protocol cannot exceed maximum-channels");
        }
        int maxResource = boundedInt(
                properties, "maximum-resource-location-bytes", 16, 1_024);
        int maxVersion = boundedInt(properties, "maximum-version-bytes", 1, 1_024);

        boolean legacyRoutingConfigurationIgnored = hasLegacyRoutingConfiguration(properties);
        rejectUnknownKeys(properties);
        return new BridgeConfig(
                debug,
                enabled,
                lobbyServer,
                expectedProtocol,
                timeoutMillis,
                frozenRegistryAckTimeoutMillis,
                allowVanilla,
                enableLegacyForgeHandoffGuard,
                legacyForgeHandoffTargets,
                enableLegacyForgeLobbyEntryGuard,
                maximumLegacyLobbyClientChannels,
                allowedLegacyLobbyClientChannels,
                enableNecroTempusTransport,
                necroTempusMaximumCompressedNbtBytes,
                necroTempusMaximumDecompressedNbtBytes,
                necroTempusMaximumTabBridgeMessageBytes,
                necroTempusMaximumTabTextBytes,
                necroTempusMaximumPacketsPerSecond,
                necroTempusMaximumBytesPerSecond,
                enableBackendNeoForgeCapabilityRelay,
                enableBuiltInAe2JeiSessionOptimization,
                enableBuiltInApothicEnchantingBootstrap,
                enableBuiltInMekanismCompatibility,
                enableBuiltInReviewedLobbyInteractionPlaySinks,
                enableBuiltInSilentGearSnapshotBridge,
                maximumSilentGearPayloadBytes,
                maximumSilentGearTotalBytes,
                maximumSilentGearSnapshots,
                registryShims,
                maximumRegistryShimBytes,
                transientServerConfigs,
                deriveServerConfigsFromClientChannels,
                maximumDerivedServerConfigs,
                pinnedPlaySinkChannels,
                configuredMaximumAutomaticPlaySinkChannels,
                maximumAutomaticPlaySinkChannels,
                maximumGlobalPlaySinkChannels,
                maximumGlobalPlaySinkChannelBytes,
                maximumLobbyPlayPayloadBytes,
                maximumLobbyPlayTotalBytes,
                maximumLobbyPlayPackets,
                lobbyPlayBudgetRefillMillis,
                legacyRoutingConfigurationIgnored,
                new ProtocolLimits(maxQuery, maxSetup, 2, maxChannels, maxPerProtocol,
                        maxResource, maxVersion));
    }

    /** Reads only the diagnostic switch; operational failures never depend on this preference. */
    static boolean preferredDebug(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Path configPath = dataDirectory.resolve("bridge.properties");
        if (Files.notExists(configPath)) {
            return false;
        }
        try {
            String raw = loadProperties(configPath).getProperty("debug");
            return raw != null && raw.strip().equalsIgnoreCase("true");
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean hasLegacyRoutingConfiguration(Properties properties) {
        String destinations = properties.getProperty("destinations", "");
        return !destinations.isBlank()
                || properties.stringPropertyNames().stream()
                        .anyMatch(key -> LEGACY_DESTINATION_KEY.matcher(key).matches());
    }

    private static List<String> parseLegacyForgeHandoffTargets(
            Properties properties, boolean enabled, String lobbyServer) {
        String raw = properties.getProperty("legacy-forge-handoff-targets");
        List<String> declared = raw == null
                ? DEFAULT_LEGACY_FORGE_HANDOFF_TARGETS
                : raw.isBlank()
                        ? List.of()
                        : Arrays.stream(raw.split(",", -1))
                                .map(String::strip)
                                .toList();
        if (declared.size() > 16 || declared.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException(
                    "legacy-forge-handoff-targets must contain 0..16 Velocity server names");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(declared);
        if (unique.size() != declared.size()) {
            throw new IllegalArgumentException(
                    "legacy-forge-handoff-targets contains a duplicate server name");
        }
        for (String serverName : unique) {
            requireServerName(serverName, "legacy-forge-handoff-targets");
            if (serverName.equals(lobbyServer)) {
                throw new IllegalArgumentException(
                        "legacy-forge-handoff-targets cannot contain lobby-server");
            }
        }
        if (enabled && unique.isEmpty()) {
            throw new IllegalArgumentException(
                    "legacy-forge-handoff-targets cannot be empty while its guard is enabled");
        }
        return List.copyOf(unique);
    }

    private static Set<String> parseAllowedLegacyLobbyClientChannels(
            Properties properties, int maximumChannels) {
        String raw = properties.getProperty("allowed-legacy-lobby-client-channels");
        List<String> declared = raw == null
                ? List.copyOf(DEFAULT_ALLOWED_LEGACY_LOBBY_CLIENT_CHANNELS)
                : raw.isBlank()
                        ? List.of()
                        : Arrays.stream(raw.split(",", -1))
                                .map(String::strip)
                                .toList();
        if (declared.size() > maximumChannels
                || declared.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException(
                    "allowed-legacy-lobby-client-channels must contain 0.."
                            + maximumChannels + " channels");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(declared);
        if (unique.size() != declared.size()) {
            throw new IllegalArgumentException(
                    "allowed-legacy-lobby-client-channels contains a duplicate channel");
        }
        for (String channel : unique) {
            if (!RESOURCE_LOCATION.matcher(channel).matches()
                    || channel.getBytes(StandardCharsets.UTF_8).length > 64) {
                throw new IllegalArgumentException(
                        "allowed-legacy-lobby-client-channels must contain lowercase resource "
                                + "locations of at most 64 UTF-8 bytes");
            }
        }
        return Collections.unmodifiableSet(unique);
    }

    private static List<String> parseTransientServerConfigs(
            Properties properties,
            boolean enableBuiltInAtmServerConfigs,
            boolean enableBuiltInMekanismCompatibility) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (enableBuiltInAtmServerConfigs) {
            unique.addAll(BUILT_IN_ATM_SERVER_CONFIGS);
        }
        if (enableBuiltInMekanismCompatibility) {
            unique.addAll(ReviewedAtmCompatibility.MEKANISM_SERVER_CONFIGS);
        }
        String raw = properties.getProperty("transient-server-configs");
        if (raw == null) {
            return List.copyOf(unique);
        }
        List<String> declared = raw.isBlank()
                ? List.of()
                : Arrays.stream(raw.split(",", -1))
                        .map(String::strip)
                        .toList();
        if (!raw.isBlank() && declared.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException(
                    "transient-server-configs contains an empty filename");
        }
        LinkedHashSet<String> operatorUnique = new LinkedHashSet<>(declared);
        if (declared.size() != operatorUnique.size()) {
            throw new IllegalArgumentException(
                    "transient-server-configs contains a duplicate filename");
        }
        unique.addAll(operatorUnique);
        if (unique.size() > 128) {
            throw new IllegalArgumentException(
                    "transient-server-configs must contain at most 128 merged filenames");
        }
        for (String fileName : unique) {
            if (!NeoForgeConfigPath.isValid(fileName)) {
                throw new IllegalArgumentException(
                        "transient-server-configs contains invalid filename " + fileName);
            }
        }
        return List.copyOf(unique);
    }

    private static boolean legacyRegistryShimsDefaultEnabled(Properties properties) {
        String raw = properties.getProperty("registry-shims");
        return raw == null || !raw.isBlank();
    }

    private static List<String> parseRegistryShims(
            Properties properties,
            boolean enableBuiltInAtmRegistryShims) {
        LinkedHashSet<String> effective = new LinkedHashSet<>();
        if (enableBuiltInAtmRegistryShims) {
            effective.addAll(RegistryShimCatalog.builtInAtmShimIds());
        }
        String raw = properties.getProperty("registry-shims");
        if (raw == null || raw.isBlank()) {
            return List.copyOf(effective);
        }
        List<String> declared = Arrays.stream(raw.split(",", -1))
                .map(String::strip)
                .toList();
        if (declared.stream().anyMatch(String::isEmpty) || declared.size() > 16) {
            throw new IllegalArgumentException(
                    "registry-shims must contain 0..16 reviewed shim ids");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(declared);
        if (unique.size() != declared.size()) {
            throw new IllegalArgumentException("registry-shims contains a duplicate id");
        }
        for (String shimId : unique) {
            if (!REGISTRY_SHIM_ID.matcher(shimId).matches()) {
                throw new IllegalArgumentException(
                        "registry-shims contains invalid id " + shimId);
            }
            if (!RegistryShimCatalog.isSupported(shimId)) {
                throw new IllegalArgumentException("unsupported registry shim: " + shimId);
            }
        }
        effective.addAll(unique);
        return List.copyOf(effective);
    }

    private static List<PinnedPlayChannel> parsePinnedPlaySinkChannels(
            Properties properties,
            boolean enableBuiltInSophisticatedPlaySinks,
            boolean enableBuiltInPneumaticCraftPlaySink,
            boolean enableBuiltInKubeJsPlaySinks,
            boolean enableBuiltInPlaceboPlaySink,
            boolean enableBuiltInXyCraftPlaySink,
            boolean enableBuiltInMekanismCompatibility,
            boolean enableBuiltInReviewedLobbyInteractionPlaySinks) {
        LinkedHashMap<String, PinnedPlayChannel> channels = new LinkedHashMap<>();
        if (enableBuiltInSophisticatedPlaySinks) {
            BUILT_IN_SOPHISTICATED_PLAY_SINK_CHANNELS.forEach(
                    channel -> channels.put(channel.id(), channel));
        }
        if (enableBuiltInPneumaticCraftPlaySink) {
            channels.put(
                    BUILT_IN_PNEUMATICCRAFT_PLAY_SINK.id(),
                    BUILT_IN_PNEUMATICCRAFT_PLAY_SINK);
        }
        if (enableBuiltInKubeJsPlaySinks) {
            BUILT_IN_KUBEJS_PLAY_SINK_CHANNELS.forEach(
                    channel -> channels.put(channel.id(), channel));
        }
        if (enableBuiltInPlaceboPlaySink) {
            channels.put(
                    ReviewedAtmCompatibility.PLACEBO_PATREON_PLAY_SINK.id(),
                    ReviewedAtmCompatibility.PLACEBO_PATREON_PLAY_SINK);
        }
        if (enableBuiltInXyCraftPlaySink) {
            channels.put(
                    ReviewedAtmCompatibility.XYCRAFT_MODIFIER_KEY_PLAY_SINK.id(),
                    ReviewedAtmCompatibility.XYCRAFT_MODIFIER_KEY_PLAY_SINK);
        }
        if (enableBuiltInMekanismCompatibility) {
            channels.put(
                    ReviewedAtmCompatibility.MEKANISM_KEY_PLAY_SINK.id(),
                    ReviewedAtmCompatibility.MEKANISM_KEY_PLAY_SINK);
        }
        if (enableBuiltInReviewedLobbyInteractionPlaySinks) {
            ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.forEach(
                    channel -> channels.put(channel.id(), channel));
        }

        String raw = properties.getProperty("pinned-play-sink-channels");
        if (raw == null || raw.isBlank()) {
            return List.copyOf(channels.values());
        }

        List<String> entries = Arrays.stream(raw.split(",", -1))
                .map(String::strip)
                .toList();
        if (entries.stream().anyMatch(String::isEmpty) || entries.size() > 16) {
            throw new IllegalArgumentException(
                    "pinned-play-sink-channels must contain 0..16 additional id@version entries");
        }

        LinkedHashSet<String> declaredIds = new LinkedHashSet<>();
        for (String entry : entries) {
            int separator = entry.lastIndexOf('@');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "invalid pinned PLAY channel; expected id@version: " + entry);
            }
            String id = entry.substring(0, separator);
            String version = entry.substring(separator + 1);
            if (id.getBytes(StandardCharsets.UTF_8).length > 256
                    || !RESOURCE_LOCATION.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid pinned PLAY channel id: " + id);
            }
            if (version.getBytes(StandardCharsets.UTF_8).length > 256
                    || version.isBlank()
                    || !version.equals(version.strip())) {
                throw new IllegalArgumentException(
                        "invalid pinned PLAY channel version for " + id);
            }
            if (ProxyPluginMessageOwnership.externallyOwns(id)) {
                // Old/manual configuration cannot claim Velocity's transport or disable an
                // otherwise valid bridge. Omit this sink pin; leave the on-disk file untouched.
                continue;
            }
            if (ReviewedSimpleVoiceChatExtension.externallyOwns(id)) {
                throw new IllegalArgumentException(
                        "pinned PLAY channel is externally owned by Simple Voice Chat: " + id);
            }
            if (!declaredIds.add(id)) {
                throw new IllegalArgumentException("duplicate pinned PLAY channel id: " + id);
            }
            PinnedPlayChannel previous = channels.putIfAbsent(
                    id, new PinnedPlayChannel(id, version));
            if (previous != null && !previous.version().equals(version)) {
                throw new IllegalArgumentException(
                        "pinned PLAY channel conflicts with built-in version for " + id);
            }
        }
        return List.copyOf(channels.values());
    }

    private static void rejectUnknownKeys(Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            if (FIXED_KEYS.contains(key) || LEGACY_DESTINATION_KEY.matcher(key).matches()) {
                continue;
            }
            throw new IllegalArgumentException("unknown configuration key: " + key);
        }
    }

    private static Properties loadProperties(Path configPath) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void writeDefaults(Path configPath) throws IOException {
        Files.writeString(configPath, DEFAULT_CONFIGURATION, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing configuration key: " + key);
        }
        return value.strip();
    }

    private static boolean strictBoolean(Properties properties, String key) {
        String value = required(properties, key);
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }

    private static boolean strictBooleanOrDefault(
            Properties properties, String key, boolean fallback) {
        if (properties.getProperty(key) == null) {
            return fallback;
        }
        return strictBoolean(properties, key);
    }

    private static int boundedInt(Properties properties, String key, int minimum, int maximum) {
        String value = required(properties, key);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(
                        key + " must be from " + minimum + " to " + maximum);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " is not an integer", exception);
        }
    }

    private static int boundedIntOrDefault(
            Properties properties,
            String key,
            int minimum,
            int maximum,
            int fallback) {
        if (properties.getProperty(key) == null) {
            return fallback;
        }
        return boundedInt(properties, key, minimum, maximum);
    }

    private static void requireServerName(String value, String key) {
        if (!SERVER_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(key + " has an invalid Velocity server name");
        }
    }
}
