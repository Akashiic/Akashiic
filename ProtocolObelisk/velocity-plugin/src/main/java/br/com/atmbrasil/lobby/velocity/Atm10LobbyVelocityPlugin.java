package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.common.LegacyConfigurationMigration;
import br.com.atmbrasil.lobby.common.LobbyReadyCodec;
import br.com.atmbrasil.lobby.common.LobbyReadyCodec.LobbyReady;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import br.com.atmbrasil.lobby.velocity.necro.NecroTempusTransportConfig;
import br.com.atmbrasil.lobby.velocity.necro.NecroTempusTransportService;
import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerModInfoEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.configuration.PlayerConfigurationEvent;
import com.velocitypowered.api.event.player.configuration.PlayerEnterConfigurationEvent;
import com.velocitypowered.api.event.player.configuration.PlayerEnteredConfigurationEvent;
import com.velocitypowered.api.event.player.configuration.PlayerFinishConfigurationEvent;
import com.velocitypowered.api.event.player.configuration.PlayerFinishedConfigurationEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

@Plugin(
        id = "protocolobelisk",
        name = "ProtocolObelisk",
        version = "1.9.16-EVOLUTION",
        description = "NeoForge 1.21.1 lobby bridge with reviewed offline enrichment",
        authors = {"ATM Brasil"})
public final class Atm10LobbyVelocityPlugin {
    private static final String LEGACY_PLUGIN_ID = "atm10-lobby-bridge";
    private static final String LEGACY_REGISTER_PLUGIN_ID =
            "akashic-legacy-register-transition-fix";
    private static final String LEGACY_NECROTEMPUS_PLUGIN_ID =
            "protocolobelisk-necrotempus";
    private static final String LEGACY_CONFIG_FILE = "bridge.properties";
    private static final MinecraftChannelIdentifier NEOFORGE_REGISTER = channel("neoforge:register");
    private static final MinecraftChannelIdentifier NEOFORGE_NETWORK = channel("neoforge:network");
    private static final MinecraftChannelIdentifier NEOFORGE_SETUP_FAILED =
            channel("neoforge:modded_network_setup_failed");
    private static final MinecraftChannelIdentifier NEOFORGE_CONFIG_FILE =
            channel("neoforge:config_file");
    private static final MinecraftChannelIdentifier NEOFORGE_FROZEN_REGISTRY_START =
            channel(NeoForgeFrozenRegistryProfile.START_CHANNEL);
    private static final MinecraftChannelIdentifier NEOFORGE_FROZEN_REGISTRY =
            channel(NeoForgeFrozenRegistryProfile.REGISTRY_CHANNEL);
    private static final MinecraftChannelIdentifier NEOFORGE_FROZEN_REGISTRY_COMPLETED =
            channel(NeoForgeFrozenRegistryProfile.COMPLETED_CHANNEL);
    private static final MinecraftChannelIdentifier MINECRAFT_REGISTER = channel("minecraft:register");
    private static final MinecraftChannelIdentifier MINECRAFT_UNREGISTER =
            channel("minecraft:unregister");
    private static final MinecraftChannelIdentifier COMMON_VERSION = channel("c:version");
    private static final MinecraftChannelIdentifier COMMON_REGISTER = channel("c:register");
    private static final MinecraftChannelIdentifier BRIDGE_CONTROL =
            channel(LobbyReadyCodec.CHANNEL_ID);
    private static final String WORLD_EDIT_CUI_CHANNEL_ID = "worldedit:cui";
    /** Proxy-owned transport: preserve its registration without registering a PLAY sink. */
    private static final String BUNGEE_PLUGIN_MESSAGE_CHANNEL_ID =
            ProxyPluginMessageOwnership.BUNGEE_CHANNEL_ID;
    private static final MinecraftChannelIdentifier APOTHIC_ENCHANTMENT_INFO =
            channel(ApothicEnchantingBootstrapPayload.CHANNEL_ID);
    private static final MinecraftChannelIdentifier SILENT_GEAR_SYNC_TRAITS =
            channel(SilentGearProtocol.SYNC_TRAITS);
    private static final MinecraftChannelIdentifier SILENT_GEAR_SYNC_MATERIALS =
            channel(SilentGearProtocol.SYNC_MATERIALS);
    private static final MinecraftChannelIdentifier SILENT_GEAR_SYNC_PARTS =
            channel(SilentGearProtocol.SYNC_PARTS);
    private static final MinecraftChannelIdentifier SILENT_GEAR_ACK =
            channel(SilentGearProtocol.ACK);
    private static final Map<String, MinecraftChannelIdentifier> SILENT_GEAR_CHANNELS = Map.of(
            SilentGearProtocol.SYNC_TRAITS, SILENT_GEAR_SYNC_TRAITS,
            SilentGearProtocol.SYNC_MATERIALS, SILENT_GEAR_SYNC_MATERIALS,
            SilentGearProtocol.SYNC_PARTS, SILENT_GEAR_SYNC_PARTS,
            SilentGearProtocol.ACK, SILENT_GEAR_ACK);
    private static final int MAXIMUM_PLAY_BOOTSTRAP_SEND_ATTEMPTS = 40;
    private static final long PLAY_BOOTSTRAP_RETRY_MILLIS = 250L;
    /**
     * Bounds an unhealthy Netty single-flush future independently from the client ACK window.
     * With the default 60-second ACK deadline, both phases finish within the reviewed
     * 90-second Velocity read timeout.
     */
    static final int FROZEN_REGISTRY_WRITE_TIMEOUT_MILLIS = 20_000;
    /** Bounds the actual client-channel flush before Paper may release recipe lifecycle packets. */
    static final int PLAY_BOOTSTRAP_WRITE_TIMEOUT_MILLIS = 20_000;
    /**
     * Fail-closed deadline for the three Silent Gear PLAY acknowledgements.
     *
     * <p>The ATM10 Normal 8.0 incident captured on 2026-08-21 spent approximately 7.6 seconds
     * on the client render thread before processing these maps. A ten-second end-to-end deadline
     * left too little event-dispatch margin on a large first join. Thirty seconds remains bounded
     * while allowing the already-validated client work and Velocity event bus to complete.</p>
     */
    static final long SILENT_GEAR_ACK_TIMEOUT_MILLIS = 30_000L;
    /** Must finish before Velocity's five-second PlayerFinishConfigurationEvent deadline. */
    private static final long DYNAMIC_REGISTRY_INJECTION_TIMEOUT_MILLIS = 4_000L;
    /** Pipeline mutation must finish before the CONFIGURATION gate is released. */
    private static final long BLOCK_STATE_TRANSLATOR_ATTACH_TIMEOUT_MILLIS = 2_000L;
    /** Bounds an unhealthy optional adapter; timeout resumes Paper passthrough, never admission. */
    private static final long REGISTRY_REPLACEMENT_ATTACH_TIMEOUT_MILLIS = 1_000L;
    /** The post-connect lobby backend is normally ready; this only bounds narrow reconnect races. */
    private static final long LEGACY_FORGE_GUARD_ATTACH_TIMEOUT_MILLIS = 2_000L;
    /** Must complete while Velocity is awaiting ServerConnectedEvent. */
    private static final long MODERN_REGISTER_GUARD_ATTACH_TIMEOUT_MILLIS = 2_000L;
    /** Leaves 32 channels of headroom below Paper's 128-channel player ceiling. */
    static final int MODERN_LOBBY_REGISTER_FORWARD_LIMIT = 96;

    private static final Set<String> FROZEN_REGISTRY_CHANNEL_IDS = Set.of(
            NEOFORGE_FROZEN_REGISTRY_START.getId(),
            NEOFORGE_FROZEN_REGISTRY.getId(),
            NEOFORGE_FROZEN_REGISTRY_COMPLETED.getId());

    private static final Set<String> NEOFORGE_BUILTIN_CHANNEL_IDS = Set.of(
            NEOFORGE_REGISTER.getId(),
            NEOFORGE_NETWORK.getId(),
            NEOFORGE_SETUP_FAILED.getId(),
            NEOFORGE_CONFIG_FILE.getId(),
            NEOFORGE_FROZEN_REGISTRY_START.getId(),
            NEOFORGE_FROZEN_REGISTRY.getId(),
            NEOFORGE_FROZEN_REGISTRY_COMPLETED.getId(),
            MINECRAFT_REGISTER.getId(),
            MINECRAFT_UNREGISTER.getId(),
            COMMON_VERSION.getId(),
            COMMON_REGISTER.getId());
    private static final List<Channel> BASE_LOBBY_CONFIGURATION_CHANNELS = List.of(new Channel(
            NEOFORGE_CONFIG_FILE.getId(), "1", Flow.CLIENTBOUND, true));
    private static final Component INCOMPATIBLE = Component.text(
            "A negociação modded do lobby falhou. Reconecte ou informe o log ao administrador.",
            NamedTextColor.RED);
    private static final Component LEGACY_FORGE_INCOMPATIBLE = Component.text(
            "A proteção da transição Forge 1.7.10 falhou. Reconecte e informe o log ao administrador.",
            NamedTextColor.RED);

    private final ProxyServer proxy;
    private final Logger consoleLogger;
    private final Path dataDirectory;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<UUID, BridgeSession> sessions = new ConcurrentHashMap<>();
    private final Object playSinkRegistryLock = new Object();
    private final Map<String, MinecraftChannelIdentifier> globalPlaySinkChannels =
            new HashMap<>();

    private int globalPlaySinkChannelBytes;
    private long negotiationPlanGeneration;

    private volatile Logger logger;
    private volatile BridgeConfig config;
    private volatile List<RegistryShimPacket> registryShimPackets = List.of();
    private volatile Optional<RegistryShimPacket> atm10Normal82GiselleExtensionPacket =
            Optional.empty();
    private volatile Optional<Atm10Normal81ServerConfigCatalog.Catalog>
            exactServerConfigCatalog = Optional.empty();
    private volatile VelocityRegistryInjector registryInjector;
    private volatile VelocityTagsInjector registryTagsInjector;
    private volatile VelocityRegistryReplacementGuard registryReplacementGuard;
    private volatile VelocityLobbyPlayPacketTranslator lobbyPlayPacketTranslator;
    private volatile ReviewedBlockStateProfileCatalog reviewedBlockStateProfiles =
            ReviewedBlockStateProfileCatalog.empty();
    private volatile VelocityLegacyForgeHandoffGuard legacyForgeHandoffGuard;
    private volatile VelocityLegacyLobbyEntryGuard legacyLobbyEntryGuard;
    private volatile VelocityModernLobbyEntryGuard modernLobbyEntryGuard;
    private volatile NecroTempusTransportService necroTempusTransport;
    private volatile SilentGearProfileCatalog silentGearProfiles =
            SilentGearProfileCatalog.empty();
    private volatile Ae2JeiSessionOptimization ae2JeiSessionOptimization;
    private volatile LobbyNegotiationPlanCache negotiationPlanCache;
    private volatile LobbyConfigurationPrefixCache configurationPrefixCache;
    private volatile VelocityPluginMessageBatchSender pluginMessageBatchSender;
    private volatile Map<String, VelocityPluginMessageBatchSender.Batch>
            frozenRegistryWirePlans = Map.of();

    @Inject
    public Atm10LobbyVelocityPlugin(
            ProxyServer proxy,
            Logger logger,
            @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.consoleLogger = logger;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent ignored) {
        if (proxy.getPluginManager().getPlugin(LEGACY_PLUGIN_ID).isPresent()) {
            consoleLogger.error(
                    "ProtocolObelisk 1.9.16-EVOLUTION was not enabled because the legacy "
                            + "atm10-lobby-bridge JAR is also installed; remove the old JAR "
                            + "and restart Velocity");
            return;
        }
        if (proxy.getPluginManager().getPlugin(LEGACY_NECROTEMPUS_PLUGIN_ID).isPresent()) {
            consoleLogger.error(
                    "ProtocolObelisk was not enabled because the standalone "
                            + "ProtocolObelisk-NecroTempus Velocity JAR is also installed. "
                            + "Remove the old JAR and perform a full Velocity restart");
            return;
        }
        if (proxy.getPluginManager().getPlugin(LEGACY_REGISTER_PLUGIN_ID).isPresent()) {
            consoleLogger.error(
                    "ProtocolObelisk was not enabled because the standalone Akashic Legacy "
                            + "REGISTER Transition Fix is still installed. ProtocolObelisk now "
                            + "owns that lobby-entry fence; remove the old JAR and perform a full "
                            + "Velocity restart");
            return;
        }
        registerFixedChannels();
        try {
            var migration = LegacyConfigurationMigration.copyIfTargetMissing(
                    dataDirectory.resolveSibling(LEGACY_PLUGIN_ID).resolve(LEGACY_CONFIG_FILE),
                    dataDirectory.resolve(LEGACY_CONFIG_FILE));
            migration.ifPresent(result -> consoleLogger.info(
                    "Migrated legacy Velocity configuration for ProtocolObelisk 1.9.16-EVOLUTION: "
                            + "source={}, target={}, bytes={}, legacySourcePreserved=true",
                    result.legacyConfig(), result.targetConfig(), result.bytes()));
            BridgeConfig loaded = BridgeConfig.load(dataDirectory);
            logger = loaded.debug() ? consoleLogger : NOPLogger.NOP_LOGGER;
            List<RegistryShimPacket> loadedRegistryShimPackets = List.of();
            Optional<RegistryShimPacket> loadedAtm10Normal82GiselleExtensionPacket =
                    Optional.empty();
            Optional<Atm10Normal81ServerConfigCatalog.Catalog>
                    loadedExactServerConfigCatalog = Optional.empty();
            VelocityRegistryInjector loadedRegistryInjector = null;
            VelocityTagsInjector loadedRegistryTagsInjector = null;
            VelocityRegistryReplacementGuard loadedRegistryReplacementGuard = null;
            VelocityLobbyPlayPacketTranslator loadedLobbyPlayPacketTranslator = null;
            ReviewedBlockStateProfileCatalog loadedReviewedBlockStateProfiles =
                    ReviewedBlockStateProfileCatalog.empty();
            VelocityLegacyForgeHandoffGuard loadedLegacyForgeHandoffGuard = null;
            VelocityLegacyLobbyEntryGuard loadedLegacyLobbyEntryGuard = null;
            VelocityModernLobbyEntryGuard loadedModernLobbyEntryGuard = null;
            NecroTempusTransportService loadedNecroTempusTransport = null;
            SilentGearProfileCatalog loadedSilentGearProfiles =
                    SilentGearProfileCatalog.empty();
            Ae2JeiSessionOptimization loadedAe2JeiSessionOptimization = null;
            VelocityPluginMessageBatchSender loadedPluginMessageBatchSender = null;
            Map<String, VelocityPluginMessageBatchSender.Batch>
                    loadedFrozenRegistryWirePlans = Map.of();
            if (loaded.enabled()) {
                validateRegisteredServers(loaded);
                registerPinnedPlaySinkChannels(loaded);
                if (loaded.enableLegacyForgeHandoffGuard()) {
                    loadedLegacyForgeHandoffGuard =
                            VelocityLegacyForgeHandoffGuard.resolve();
                }
                if (loaded.enableLegacyForgeLobbyEntryGuard()) {
                    loadedLegacyLobbyEntryGuard = VelocityLegacyLobbyEntryGuard.resolve();
                }
                // Velocity handles minecraft:register in a dedicated branch which bypasses
                // PluginMessageEvent and forwards the original packet to Paper. This raw outbound
                // adapter is therefore mandatory for the configured modern protocol.
                loadedModernLobbyEntryGuard = VelocityModernLobbyEntryGuard.resolve(
                        loaded.expectedMinecraftProtocol());
                loadedPluginMessageBatchSender =
                        VelocityPluginMessageBatchSender.resolve(
                                loaded.expectedMinecraftProtocol());
                loadedRegistryShimPackets = RegistryShimCatalog.resolve(
                        loaded.registryShims(), loaded.maximumRegistryShimBytes());
                Atm10Normal82GiselleEnchantmentExtension.RuntimeResolution
                        giselle82Resolution =
                                Atm10Normal82GiselleEnchantmentExtension.runtimeResolution(
                                        loaded.maximumRegistryShimBytes());
                loadedAtm10Normal82GiselleExtensionPacket = giselle82Resolution.packet();
                giselle82Resolution.quarantineFailure().ifPresent(failure ->
                        consoleLogger.warn(
                                "Quarantined ATM10 8.2-lineage Giselle enchantment merge "
                                        + "extension; Paper registry passthrough remains active "
                                        + "for that lineage and admission/routing are unchanged",
                                failure));
                Atm10Normal81ServerConfigCatalog.RuntimeResolution configCatalogResolution =
                        Atm10Normal81ServerConfigCatalog.runtimeResolution();
                loadedExactServerConfigCatalog = configCatalogResolution.catalog();
                configCatalogResolution.quarantineFailure().ifPresent(failure ->
                        consoleLogger.warn(
                                "Quarantined exact ATM10 8.1 SERVER-config catalog; "
                                        + "cardinal admission and routing remain active, "
                                        + "recipe lifecycle will be WITHHELD",
                                failure));
                RegistryShimCatalog.fullEnchantmentQuarantineFailure(
                                loaded.maximumRegistryShimBytes())
                        .ifPresent(failure -> consoleLogger.warn(
                                "Quarantined ATM10 8.1 full-enchantment replacement resource; "
                                        + "Paper registry passthrough remains active, recipe "
                                        + "lifecycle will be WITHHELD and admission/routing are "
                                        + "unchanged",
                                failure));
                RegistryShimCatalog.neoVitaeSentientQuarantineFailure(
                                loaded.maximumRegistryShimBytes())
                        .ifPresent(failure -> consoleLogger.warn(
                                "Quarantined exact ATM10 8.1 NeoVitae sentient registry-and-tags "
                                        + "closure; creative-inventory enrichment is withheld, "
                                        + "admission/routing remain unchanged",
                                failure));
                loadedRegistryReplacementGuard =
                        resolveRegistryReplacementGuardOrPassthrough(
                                () -> VelocityRegistryReplacementGuard.resolve(
                                        Atm10Normal81EnchantmentRegistry.PROTOCOL_VERSION),
                                failure -> consoleLogger.warn(
                                        "Quarantined unavailable ATM10 8.1 registry replacement "
                                                + "adapter; Paper registry passthrough remains "
                                                + "active, recipe lifecycle will be WITHHELD and "
                                                + "admission/routing are unchanged",
                                        failure));
                if (!loadedRegistryShimPackets.isEmpty()) {
                    loadedRegistryInjector = VelocityRegistryInjector.resolve(
                            loaded.expectedMinecraftProtocol());
                }
                boolean neoVitaeSentientSelected = loadedRegistryShimPackets.stream()
                        .anyMatch(packet -> packet.shimId().equals(
                                RegistryShimCatalog.NEOVITAE_SENTIENT_ATM10_8_1));
                if (neoVitaeSentientSelected) {
                    loadedRegistryTagsInjector = resolveRegistryTagsInjectorOrPassthrough(
                            () -> VelocityTagsInjector.resolve(
                                    loaded.expectedMinecraftProtocol()),
                            failure -> consoleLogger.warn(
                                    "Quarantined unavailable ATM10 8.1 NeoVitae tags adapter; "
                                            + "the paired sentient registry is also withheld and "
                                            + "admission/routing remain unchanged",
                                    failure));
                    if (loadedRegistryTagsInjector == null) {
                        loadedRegistryShimPackets = loadedRegistryShimPackets.stream()
                                .filter(packet -> !packet.shimId().equals(
                                        RegistryShimCatalog.NEOVITAE_SENTIENT_ATM10_8_1))
                                .toList();
                    }
                }
                if (loaded.enableBuiltInSilentGearSnapshotBridge()) {
                    loadedSilentGearProfiles = loadReviewedSilentGearProfiles(loaded);
                    if (loadedRegistryInjector == null
                            && loadedSilentGearProfiles.profiles().stream()
                                    .anyMatch(profile -> !profile.dynamicRegistries().isEmpty())) {
                        loadedRegistryInjector = VelocityRegistryInjector.resolve(
                                loaded.expectedMinecraftProtocol());
                    }
                    if (loadedSilentGearProfiles.profiles().stream()
                            .anyMatch(profile -> !profile.dynamicRegistryTags().isEmpty())) {
                        loadedRegistryTagsInjector = VelocityTagsInjector.resolve(
                                loaded.expectedMinecraftProtocol());
                    }
                    if (!loadedSilentGearProfiles.profiles().isEmpty()) {
                        LinkedHashMap<String, VelocityPluginMessageBatchSender.Batch>
                                wirePlans = new LinkedHashMap<>();
                        for (SilentGearEmbeddedProfile profile
                                : loadedSilentGearProfiles.profiles()) {
                            VelocityPluginMessageBatchSender.Batch batch =
                                    VelocityPluginMessageBatchSender.Batch
                                            .frozenRegistryTransaction(profile);
                            if (wirePlans.putIfAbsent(profile.profileId(), batch) != null) {
                                throw new IllegalArgumentException(
                                        "duplicate frozen-registry wire-plan profile id");
                            }
                        }
                        loadedFrozenRegistryWirePlans = Map.copyOf(wirePlans);
                    }
                }
                loadedReviewedBlockStateProfiles = loadBundledReviewedBlockStateProfiles();
                if (!loadedReviewedBlockStateProfiles.reviewedProfiles().isEmpty()) {
                    List<BlockStateTranslationProfile> reviewedProfiles =
                            loadedReviewedBlockStateProfiles.reviewedProfiles();
                    loadedLobbyPlayPacketTranslator = resolveBlockStateTranslatorOrPassthrough(
                            () -> VelocityLobbyPlayPacketTranslator.resolve(reviewedProfiles),
                            failure -> consoleLogger.warn(
                                    "Quarantined unavailable lobby BlockState packet adapter; "
                                            + "reviewed profiles remain optional, admission and "
                                            + "routing continue with UNVERIFIED_PASSTHROUGH",
                                    failure));
                }
                if (loaded.enableBuiltInAe2JeiSessionOptimization()) {
                    loadedAe2JeiSessionOptimization = Ae2JeiSessionOptimization.loadReviewed(
                            Atm10LobbyVelocityPlugin.class.getClassLoader());
                }
                if (loaded.enableNecroTempusTransport()) {
                    NecroTempusTransportConfig necroConfig = new NecroTempusTransportConfig(
                            true,
                            loaded.necroTempusMaximumCompressedNbtBytes(),
                            loaded.necroTempusMaximumDecompressedNbtBytes(),
                            loaded.necroTempusMaximumTabBridgeMessageBytes(),
                            loaded.necroTempusMaximumTabTextBytes(),
                            loaded.necroTempusMaximumPacketsPerSecond(),
                            loaded.necroTempusMaximumBytesPerSecond());
                    loadedNecroTempusTransport = new NecroTempusTransportService(
                            proxy,
                            consoleLogger,
                            (message, arguments) -> logger.info(message, arguments),
                            necroConfig);
                    loadedNecroTempusTransport.initialize();
                }
            }
            registryShimPackets = loadedRegistryShimPackets;
            atm10Normal82GiselleExtensionPacket =
                    loadedAtm10Normal82GiselleExtensionPacket;
            exactServerConfigCatalog = loadedExactServerConfigCatalog;
            registryInjector = loadedRegistryInjector;
            registryTagsInjector = loadedRegistryTagsInjector;
            registryReplacementGuard = loadedRegistryReplacementGuard;
            lobbyPlayPacketTranslator = loadedLobbyPlayPacketTranslator;
            reviewedBlockStateProfiles = loadedReviewedBlockStateProfiles;
            legacyForgeHandoffGuard = loadedLegacyForgeHandoffGuard;
            legacyLobbyEntryGuard = loadedLegacyLobbyEntryGuard;
            modernLobbyEntryGuard = loadedModernLobbyEntryGuard;
            necroTempusTransport = loadedNecroTempusTransport;
            silentGearProfiles = loadedSilentGearProfiles;
            ae2JeiSessionOptimization = loadedAe2JeiSessionOptimization;
            pluginMessageBatchSender = loadedPluginMessageBatchSender;
            frozenRegistryWirePlans = loadedFrozenRegistryWirePlans;
            long cacheGeneration = ++negotiationPlanGeneration;
            negotiationPlanCache = new LobbyNegotiationPlanCache(cacheGeneration);
            configurationPrefixCache = new LobbyConfigurationPrefixCache(cacheGeneration);
            config = loaded;
            if (!loaded.enabled()) {
                consoleLogger.warn(
                        "ProtocolObelisk 1.9.16-EVOLUTION is disabled in bridge.properties");
                return;
            }

            consoleLogger.info(
                    "ProtocolObelisk 1.9.16-EVOLUTION enabled: lobby='{}', debug={}, "
                            + "frozenRegistryAckTimeout={} ms, admission=SERVICE_FIRST, "
                                    + "unknownProfiles=CAPABILITY_ADAPTIVE, "
                                    + "structuralWrites=EXACT_ONLY",
                    loaded.lobbyServer(),
                    loaded.debug(),
                    loaded.frozenRegistryAckTimeoutMillis());

            logger.info(
                    "ProtocolObelisk 1.9.16-EVOLUTION enabled: configVersion={}, debug={}, "
                            + "lobby='{}', routing=Velocity-native, "
                            + "legacyForgeHandoffGuard={}, legacyForgeTargets={}, "
                            + "legacyLobbyEntryGuard={}, legacyLobbyChannelLimit={}, "
                            + "legacyLobbySafeChannels={}, modernLobbyRegisterGuard={}, "
                            + "modernLobbyRegisterLimit={}, "
                            + "necroTempusTransport={}, necroTempusRate={}/{}, "
                            + "backendNeoForgeCapabilityRelay={}, "
                            + "registryShims={}, registryShimPackets={}, "
                            + "registryShimBytes={}, baselineTransientConfigs={}, "
                            + "deriveTransientConfigs={}, derivedConfigLimit={}, "
                            + "apothicEnchantingBootstrap={}, silentGearProfileBridge={}, "
                            + "ae2JeiSessionOptimization={}, "
                            + "silentGearProfiles={}, pinnedPlaySinks={}, "
                            + "automaticPlaySinksEffective={}, automaticPlaySinksConfigured={}, "
                            + "globalPlaySinkLimit={}, playPacketBurst={}, playByteBurst={}, "
                            + "playBudgetRefillMillis={}, queryLimit={} bytes",
                    BridgeConfig.CONFIG_VERSION,
                    loaded.debug(),
                    loaded.lobbyServer(),
                    loaded.enableLegacyForgeHandoffGuard(),
                    loaded.legacyForgeHandoffTargets(),
                    loaded.enableLegacyForgeLobbyEntryGuard(),
                    loaded.maximumLegacyLobbyClientChannels(),
                    loaded.allowedLegacyLobbyClientChannels(),
                    loadedModernLobbyEntryGuard != null,
                    MODERN_LOBBY_REGISTER_FORWARD_LIMIT,
                    loaded.enableNecroTempusTransport(),
                    loaded.necroTempusMaximumPacketsPerSecond(),
                    loaded.necroTempusMaximumBytesPerSecond(),
                    loaded.enableBackendNeoForgeCapabilityRelay(),
                    loaded.registryShims(),
                    loadedRegistryShimPackets.size(),
                    loadedRegistryShimPackets.stream()
                            .mapToInt(RegistryShimPacket::packetBytes)
                            .sum(),
                    loaded.transientServerConfigs().size(),
                    loaded.deriveServerConfigsFromClientChannels(),
                    loaded.maximumDerivedServerConfigs(),
                    loaded.enableBuiltInApothicEnchantingBootstrap(),
                    loaded.enableBuiltInSilentGearSnapshotBridge(),
                    loadedAe2JeiSessionOptimization != null,
                    loadedSilentGearProfiles.size(),
                    loaded.pinnedPlaySinkChannels().size(),
                    loaded.maximumAutomaticPlaySinkChannels(),
                    loaded.configuredMaximumAutomaticPlaySinkChannels(),
                    loaded.maximumGlobalPlaySinkChannels(),
                    loaded.maximumLobbyPlayPackets(),
                    loaded.maximumLobbyPlayTotalBytes(),
                    loaded.lobbyPlayBudgetRefillMillis(),
                    loaded.limits().maximumQueryBytes());
            if (loaded.automaticPlaySinkBudgetClamped()) {
                consoleLogger.warn(
                        "Clamped automatic PLAY sink budget from {} to {}: pinnedPlaySinks={} "
                                + "and globalPlaySinkLimit={}; bridge.properties was not rewritten",
                        loaded.configuredMaximumAutomaticPlaySinkChannels(),
                        loaded.maximumAutomaticPlaySinkChannels(),
                        loaded.pinnedPlaySinkChannels().size(),
                        loaded.maximumGlobalPlaySinkChannels());
            }
            if (loadedAe2JeiSessionOptimization != null) {
                logger.info(
                        "Reviewed transient AE2-JEI session optimization armed: ae2={}, "
                                + "ae2Jei={}, config={}, bytes={}, sha256={}, persistence=memory-only",
                        Ae2JeiSessionOptimization.REVIEWED_AE2_VERSION,
                        Ae2JeiSessionOptimization.REVIEWED_AE2_JEI_VERSION,
                        Ae2JeiSessionOptimization.CONFIG_FILE_NAME,
                        loadedAe2JeiSessionOptimization.contentBytes(),
                        loadedAe2JeiSessionOptimization.contentSha256());
            }
            loadedExactServerConfigCatalog.ifPresent(catalog -> logger.info(
                    "Exact ATM10 8.1 SERVER-config catalog armed: id={}, configs={}, "
                            + "contentBytes={}, encodedBytes={}, nameSequenceSha256={}, "
                            + "payloadSequenceSha256={}, sourceServerFilesSha256={}, "
                            + "coldBootAgreement=2/2, admissionDecision=UNCHANGED_CARDINAL",
                    catalog.id(),
                    catalog.entries().size(),
                    catalog.totalContentBytes(),
                    catalog.totalEncodedBytes(),
                    catalog.nameSequenceSha256(),
                    catalog.payloadSequenceSha256(),
                    Atm10Normal81ServerConfigCatalog.SERVER_FILES_SHA256));
            logger.info(
                    "Configured baseline transient SERVER configs: {}",
                    loaded.transientServerConfigs());
            if (loaded.transientServerConfigs().contains(
                    ReviewedAtmCompatibility.MATC_SERVER_CONFIG)) {
                logger.info(
                        "Reviewed MATC SERVER config compatibility armed: mod={}, config={}",
                        ReviewedAtmCompatibility.MATC_MOD_VERSION,
                        ReviewedAtmCompatibility.MATC_SERVER_CONFIG);
            }
            if (loaded.pinnedPlaySinkChannels().contains(
                    ReviewedAtmCompatibility.PLACEBO_PATREON_PLAY_SINK)) {
                logger.info(
                        "Reviewed Placebo PLAY sink armed: mod={}, channel={}@{}",
                        ReviewedAtmCompatibility.PLACEBO_MOD_VERSION,
                        ReviewedAtmCompatibility.PLACEBO_PATREON_PLAY_SINK.id(),
                        ReviewedAtmCompatibility.PLACEBO_PATREON_PLAY_SINK.version());
            }
            if (loaded.pinnedPlaySinkChannels().contains(
                            ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_OPEN_PLAY_SINK)
                    && loaded.pinnedPlaySinkChannels().contains(
                            ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_BLOCK_PICK_PLAY_SINK)) {
                logger.info(
                        "Reviewed Sophisticated Backpacks interaction PLAY sinks armed: "
                                + "artifact={}, channels=[{}@{}, {}@{}]",
                        ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_OPEN_PLAY_SINK.id(),
                        ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_OPEN_PLAY_SINK.version(),
                        ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_BLOCK_PICK_PLAY_SINK.id(),
                        ReviewedAtmCompatibility.SOPHISTICATED_BACKPACKS_BLOCK_PICK_PLAY_SINK
                                .version());
            }
            if (loaded.pinnedPlaySinkChannels().contains(
                    ReviewedAtmCompatibility.KUBEJS_KUBEDEX_REQUEST_BLOCK_PLAY_SINK)) {
                logger.info(
                        "Reviewed KubeJS Kubedex PLAY sink armed: artifact={}, "
                                + "channel={}@{}, registration=optional",
                        ReviewedAtmCompatibility.KUBEJS_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.KUBEJS_KUBEDEX_REQUEST_BLOCK_PLAY_SINK.id(),
                        ReviewedAtmCompatibility.KUBEJS_KUBEDEX_REQUEST_BLOCK_PLAY_SINK.version());
            }
            if (loaded.enableBackendNeoForgeCapabilityRelay()) {
                logger.info(
                        "Backend NeoForge capability relay armed: "
                                + "source=same-player-validated-lobby-advertisement, "
                                + "routing=Velocity-native, compatibilityAuthority=target-backend");
            }
            if (loadedLegacyForgeHandoffGuard != null) {
                logger.info(
                        "Velocity legacy Forge handoff adapter verified: protocol=5, "
                                + "oldBackend='{}', allowedInFlightTargets={}, "
                                + "channels=[REGISTER,UNREGISTER], "
                                + "channelTracking=Velocity-native, routingMutation=false",
                        loaded.lobbyServer(),
                        loaded.legacyForgeHandoffTargets());
            }
            if (loaded.pinnedPlaySinkChannels().contains(
                    ReviewedAtmCompatibility.XYCRAFT_MODIFIER_KEY_PLAY_SINK)) {
                logger.info(
                        "Reviewed XyCraft modifier-key PLAY sink armed: mod={}, "
                                + "channel={}@{}",
                        ReviewedAtmCompatibility.XYCRAFT_CORE_MOD_VERSION,
                        ReviewedAtmCompatibility.XYCRAFT_MODIFIER_KEY_PLAY_SINK.id(),
                        ReviewedAtmCompatibility.XYCRAFT_MODIFIER_KEY_PLAY_SINK.version());
            }
            if (loaded.enableBuiltInMekanismCompatibility()) {
                logger.info(
                        "Reviewed Mekanism compatibility armed: artifact={}, network={}, "
                                + "channel={}@{}, serverConfigs={}",
                        ReviewedAtmCompatibility.MEKANISM_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.MEKANISM_NETWORK_VERSION,
                        ReviewedAtmCompatibility.MEKANISM_KEY_PLAY_SINK.id(),
                        ReviewedAtmCompatibility.MEKANISM_KEY_PLAY_SINK.version(),
                        ReviewedAtmCompatibility.MEKANISM_SERVER_CONFIGS.size());
            }
            if (loaded.enableBuiltInReviewedLobbyInteractionPlaySinks()) {
                logger.info(
                        "Reviewed lobby interaction PLAY sinks armed: count={}, channels={}, "
                                + "artifacts=[AE2WTLib {}, Draconic Evolution {}, Brandon's Core {}, "
                                + "Curios {}, Cosmetic Armor Reworked {}, FTB Teams {}, "
                                + "Not Enough Wands {}, FTB Ultimine {}, CBMultipart {}, "
                                + "CodeChickenLib {}, McJtyLib {}, RFTools Builder {}, "
                                + "Translocators {}, Simple Magnets {}, "
                                + "SuperMartijn642 Core Lib {}, Structurize {}, "
                                + "Refined Storage {}, Accessories {}, Aether {}, Nitrogen {}, "
                                + "Twilight Forest {}, Deeper and Darker {} / {}], "
                                + "handling=Velocity-bounded-no-op, "
                                + "profileSpecific=[twilightforest:gradual_glide_packet -> "
                                + "ATM10 Normal 8.0 canonical contract]",
                        ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.size(),
                        ReviewedAtmCompatibility.REVIEWED_LOBBY_INTERACTION_PLAY_SINKS.stream()
                                .map(channel -> channel.id() + '@' + channel.version())
                                .toList(),
                        ReviewedAtmCompatibility.AE2_WIRELESS_TERMINAL_LIBRARY_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.DRACONIC_EVOLUTION_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.BRANDONS_CORE_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.CURIOS_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.COSMETIC_ARMOR_REWORKED_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.FTB_TEAMS_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.NOT_ENOUGH_WANDS_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.FTB_ULTIMINE_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.CB_MULTIPART_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.CODE_CHICKEN_LIB_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.MCJTYLIB_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.RFTOOLS_BUILDER_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.TRANSLOCATORS_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.SIMPLE_MAGNETS_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.SUPERMARTIJN642_CORE_LIB_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.STRUCTURIZE_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.REFINED_STORAGE_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.ACCESSORIES_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.AETHER_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.NITROGEN_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility.TWILIGHT_FOREST_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility
                                .DEEPER_DARKER_ATM10_NORMAL_7_3_ARTIFACT_VERSION,
                        ReviewedAtmCompatibility
                                .DEEPER_DARKER_ATM10_NORMAL_8_0_ARTIFACT_VERSION);
            }
            loadedRegistryShimPackets.forEach(packet -> logger.info(
                    "Reviewed registry shim '{}' armed: requiredNamespace={}, registry={}, "
                            + "entries={}, bytes={}, sha256={}",
                    packet.shimId(),
                    packet.requiredNamespace(),
                    packet.registryId(),
                    packet.entryCount(),
                    packet.packetBytes(),
                    packet.sha256()));
            if (loaded.enableBuiltInApothicEnchantingBootstrap()) {
                logger.info(
                        "Reviewed Apothic Enchanting PLAY bootstrap armed: codecs=[{},{},{}], "
                                + "handling=registry-independent-sentinel, channel={}@{}, "
                                + "bytes={}, sha256={}",
                        ApothicEnchantingBootstrapPayload.REVIEWED_MOD_VERSION,
                        ApothicEnchantingBootstrapPayload.ATM10_NORMAL_REVIEWED_MOD_VERSION,
                        ApothicEnchantingBootstrapPayload.ATM10_8_1_REVIEWED_MOD_VERSION,
                        ApothicEnchantingBootstrapPayload.CHANNEL_ID,
                        ApothicEnchantingBootstrapPayload.CHANNEL_VERSION,
                        ApothicEnchantingBootstrapPayload.payloadBytes(),
                        ApothicEnchantingBootstrapPayload.payloadSha256());
            }
            if (loaded.enableBuiltInSilentGearSnapshotBridge()) {
                logger.info(
                        "Reviewed executable Silent Gear profile bridge armed: profiles={}, "
                                + "payloadLimit={} bytes, totalLimit={} bytes",
                        loadedSilentGearProfiles.size(),
                        loaded.maximumSilentGearPayloadBytes(),
                        loaded.maximumSilentGearTotalBytes());
                loadedSilentGearProfiles.profiles().forEach(profile -> logger.info(
                        "Validated immutable Silent Gear profile: profile={}, packRelease={} "
                                + "(internal={}), NeoForge={}, channelContractSha256={}, "
                                + "channels={}, entries=[{},{},{}], "
                                + "bytes={}, sequenceSha256={}, frozenRegistries={}, "
                                + "frozenEntries={}, frozenBytes={}, frozenSequenceSha256={}, "
                                + "dynamicRegistries={}, dynamicEntries={}, dynamicBytes={}, "
                                + "dynamicSequenceSha256={}",
                        profile.profileId(),
                        profile.packRelease(),
                        profile.packInternalVersion(),
                        profile.neoForgeVersion(),
                        profile.silentGearChannelContractSha256(),
                        profile.channels(),
                        profile.entryCount(SilentGearProtocol.SYNC_TRAITS),
                        profile.entryCount(SilentGearProtocol.SYNC_MATERIALS),
                        profile.entryCount(SilentGearProtocol.SYNC_PARTS),
                        profile.payloadBytes(),
                        profile.payloadSequenceSha256(),
                        profile.frozenRegistries().registryCount(),
                        profile.frozenRegistries().totalEntries(),
                        profile.frozenRegistries().totalTransactionBytes(),
                        profile.frozenRegistries().sequenceSha256(),
                        profile.dynamicRegistries().registryCount(),
                        profile.dynamicRegistries().totalEntries(),
                        profile.dynamicRegistries().totalBytes(),
                        profile.dynamicRegistries().sequenceSha256()));
                loadedSilentGearProfiles.profiles().stream()
                        .filter(ReviewedConfigurationProfile::isAtm10Normal)
                        .forEach(profile -> logger.info(
                                "Reviewed executable ATM10 Normal client contract armed: "
                                        + "profile={}, protocol={}, silentGearMod={}, "
                                        + "silentGearNetwork={}, channelContractSha256={}, "
                                        + "fullClientContractSha256={}, "
                                        + "configurationChannels={}, "
                                + "configurationBootstraps={}, "
                                + "dynamicRegistries={}, dynamicEntries={}, "
                                + "dynamicBytes={}, dynamicSequenceSha256={}, "
                                + "dynamicTagRegistry={}, dynamicTags={}, dynamicTagMembers={}, "
                                + "dynamicTagBytes={}, dynamicTagSha256={}",
                                profile.profileId(),
                                profile.minecraftProtocol(),
                                SilentGearProtocol.ATM10_NORMAL_4_2.modVersion(),
                                SilentGearProtocol.ATM10_NORMAL_4_2.networkVersion(),
                                profile.silentGearChannelContractSha256(),
                                profile.fullClientContractSha256().orElseThrow(),
                                ReviewedConfigurationProfile.channels(profile).size(),
                                ReviewedConfigurationProfile.bootstraps(profile).stream()
                                        .map(ReviewedConfigurationProfile.Bootstrap::channelId)
                                        .toList(),
                                profile.dynamicRegistries().registryCount(),
                                profile.dynamicRegistries().totalEntries(),
                                profile.dynamicRegistries().totalBytes(),
                                profile.dynamicRegistries().sequenceSha256(),
                                profile.dynamicRegistryTags().isEmpty()
                                        ? "none"
                                        : profile.dynamicRegistryTags().registryId(),
                                profile.dynamicRegistryTags().tags().size(),
                                profile.dynamicRegistryTags().totalMembers(),
                                profile.dynamicRegistryTags().packetBytes(),
                                profile.dynamicRegistryTags().isEmpty()
                                        ? "none"
                                        : profile.dynamicRegistryTags().sha256()));
                logger.info(
                        "Reviewed Simple Voice Chat client-contract extension armed: "
                                + "artifact={}, artifactSha256={}, compatibility={}, "
                                + "channels={}, wireBytes={}, ownership=external-pass-through, "
                                + "processOwnershipIds={}, normal80Variants={}",
                        ReviewedSimpleVoiceChatExtension.ARTIFACT_VERSION,
                        ReviewedSimpleVoiceChatExtension.ARTIFACT_SHA256,
                        ReviewedSimpleVoiceChatExtension.COMPATIBILITY_VERSION,
                        ReviewedSimpleVoiceChatExtension.channels().size(),
                        ReviewedSimpleVoiceChatExtension.CANONICAL_WIRE_BYTES,
                        ReviewedSimpleVoiceChatExtension.externallyOwnedChannelIds().size(),
                        ReviewedClientContractEvidence.atm10Normal80Variants().stream()
                                .map(variant -> variant.id() + '@'
                                        + shortFingerprint(variant.observedContract()
                                                .fullClientContractSha256()))
                                .toList());
            }
            if (loadedRegistryReplacementGuard != null) {
                logger.info(
                        "Velocity full-enchantment replacement guard verified: protocol={}, "
                                + "delivery=PAPER_REGISTRY_REPLACEMENT, "
                                + "receipt=DOWNSTREAM_CHANNEL_PROMISE_SUCCESS, "
                                + "fallback=PAPER_REGISTRY_PASSTHROUGH",
                        loaded.expectedMinecraftProtocol());
            }
            if (loadedRegistryInjector != null) {
                logger.info(
                        "Velocity CONFIG registry adapter verified: protocol={}, packetId={}",
                        loaded.expectedMinecraftProtocol(),
                        loadedRegistryInjector.registryPacketId());
            }
            if (loadedRegistryTagsInjector != null) {
                logger.info(
                        "Velocity CONFIG tags adapter verified: protocol={}, packetId={}",
                        loaded.expectedMinecraftProtocol(),
                        loadedRegistryTagsInjector.tagsPacketId());
            }
            if (loadedPluginMessageBatchSender != null) {
                logger.info(
                        "Velocity CONFIG plugin-message batch adapter verified: protocol={}, "
                                + "packetId={}, frozenWirePlans={}, flushesPerTransaction=1, "
                                + "cacheGeneration={}, planCacheLimit={}, prefixCacheLimit={}",
                        loaded.expectedMinecraftProtocol(),
                        loadedPluginMessageBatchSender.pluginMessagePacketId(),
                        loadedFrozenRegistryWirePlans.size(),
                        negotiationPlanCache.snapshot().generation(),
                        negotiationPlanCache.snapshot().maximumEntries(),
                        configurationPrefixCache.snapshot().maximumEntries());
                loadedFrozenRegistryWirePlans.values().forEach(batch -> logger.info(
                        "Validated immutable frozen-registry wire plan: profile={}, packets={}, "
                                + "bytes={}, sequenceSha256={}, storage=bounded-memory-only",
                        batch.profileId(),
                        batch.packetCount(),
                        batch.totalBytes(),
                        batch.sequenceSha256()));
            }
            if (loadedLobbyPlayPacketTranslator != null) {
                loadedReviewedBlockStateProfiles.reviewedProfiles().stream()
                        .forEach(profile -> logger.info(
                                "Velocity lobby BlockState translator verified: profile={}, "
                                        + "protocol={}, sourceStates={}, clientGlobalStates={}, "
                                        + "paletteBits={}->{}, mapSha256={}, "
                                        + "vanillaStateTableSha256={}, clientStateTableSha256={}, "
                                        + "scope=selected-lobby-backend-only",
                                profile.profileId(),
                                profile.minecraftProtocol(),
                                profile.sourceStateCount(),
                                profile.clientGlobalStateCount(),
                                profile.sourceGlobalPaletteBits(),
                                profile.targetGlobalPaletteBits(),
                                profile.mapSha256(),
                                profile.vanillaStateTableSha256(),
                                profile.clientStateTableSha256()));
            }
            if (loaded.legacyRoutingConfigurationIgnored()) {
                consoleLogger.warn(
                        "Ignored legacy destinations/destination.* keys: ProtocolObelisk no longer "
                                + "selects, authorizes or initiates server transitions; configure "
                                + "servers, try order, fallback and /server only in velocity.toml");
            }
            auditVelocityPayloadLimits(loaded, loadedSilentGearProfiles);
        } catch (IOException | IllegalArgumentException exception) {
            config = null;
            registryShimPackets = List.of();
            atm10Normal82GiselleExtensionPacket = Optional.empty();
            exactServerConfigCatalog = Optional.empty();
            registryInjector = null;
            registryReplacementGuard = null;
            lobbyPlayPacketTranslator = null;
            reviewedBlockStateProfiles = ReviewedBlockStateProfileCatalog.empty();
            legacyForgeHandoffGuard = null;
            legacyLobbyEntryGuard = null;
            modernLobbyEntryGuard = null;
            shutdownNecroTempusTransport();
            silentGearProfiles = SilentGearProfileCatalog.empty();
            ae2JeiSessionOptimization = null;
            negotiationPlanCache = null;
            configurationPrefixCache = null;
            pluginMessageBatchSender = null;
            frozenRegistryWirePlans = Map.of();
            consoleLogger.error(
                    "ProtocolObelisk disabled because configuration validation failed",
                    exception);
        } catch (IllegalStateException exception) {
            config = null;
            registryShimPackets = List.of();
            atm10Normal82GiselleExtensionPacket = Optional.empty();
            exactServerConfigCatalog = Optional.empty();
            registryInjector = null;
            registryReplacementGuard = null;
            lobbyPlayPacketTranslator = null;
            reviewedBlockStateProfiles = ReviewedBlockStateProfileCatalog.empty();
            legacyForgeHandoffGuard = null;
            legacyLobbyEntryGuard = null;
            modernLobbyEntryGuard = null;
            shutdownNecroTempusTransport();
            silentGearProfiles = SilentGearProfileCatalog.empty();
            ae2JeiSessionOptimization = null;
            negotiationPlanCache = null;
            configurationPrefixCache = null;
            pluginMessageBatchSender = null;
            frozenRegistryWirePlans = Map.of();
            consoleLogger.error(
                    "ProtocolObelisk disabled because Velocity internals are incompatible",
                    exception);
        }
    }

    /**
     * Arms the initial lobby handshake after Velocity has chosen its target.
     *
     * <p>{@link PlayerEnterConfigurationEvent} is a server-switch event and is not fired for the
     * initial connection. NeoForge therefore needs its query to be emitted from this earlier
     * lifecycle point. This observer deliberately never changes, clears, authorizes or connects
     * the selected server: Velocity's own try order and other routing plugins remain authoritative.
     */
    @Subscribe(priority = Short.MIN_VALUE, async = false)
    public void onInitialServerSelected(PlayerChooseInitialServerEvent event) {
        BridgeConfig currentConfig = activeConfig();
        if (currentConfig == null
                || event.getInitialServer().stream()
                        .noneMatch(server -> server.getServerInfo().getName()
                                .equals(currentConfig.lobbyServer()))) {
            return;
        }

        Player player = event.getPlayer();
        BridgeSession session = sessions.computeIfAbsent(
                player.getUniqueId(), BridgeSession::new);
        synchronized (session) {
            if (session.state == State.FAILED) {
                return;
            }
            armLobbyLocked(player, session, currentConfig);
            session.previousBackendServerId = null;
        }
        if (player.getProtocolVersion().getProtocol()
                == currentConfig.expectedMinecraftProtocol()) {
            sendNeoForgeQuery(player, session);
        }
    }

    /** Observes Velocity's chosen target and arms compatibility only when that target is the lobby. */
    @Subscribe(async = false)
    public void onEnterConfiguration(PlayerEnterConfigurationEvent event) {
        BridgeConfig currentConfig = activeConfig();
        if (currentConfig == null || event.server() == null) {
            return;
        }

        BridgeSession session = sessions.computeIfAbsent(
                event.player().getUniqueId(), BridgeSession::new);
        String target = serverName(event.server());
        String observedCurrentServer = event.player().getCurrentServer()
                .map(Atm10LobbyVelocityPlugin::serverName)
                .orElse("");
        synchronized (session) {
            if (session.state == State.FAILED) {
                return;
            }
            if (target.equals(currentConfig.lobbyServer())) {
                String previousBackend = !observedCurrentServer.isEmpty()
                                && !observedCurrentServer.equals(currentConfig.lobbyServer())
                                && !observedCurrentServer.equals(target)
                        ? observedCurrentServer
                        : session.lastConfirmedBackendServerId;
                armLobbyLocked(event.player(), session, currentConfig);
                session.previousBackendServerId = previousBackend;
                if (event.player().getProtocolVersion().getProtocol()
                        == currentConfig.expectedMinecraftProtocol()) {
                    session.lobbyConfigurationEntryFence.arm(
                            target, session.lobbyCycleGeneration);
                }
            } else {
                session.previousBackendServerId = null;
                session.lobbyConfigurationEntryFence.clear();
                clearRegistryReplacementAttachment(session);
                clearBlockStateTranslation(session);
                session.state = State.OUTSIDE;
                completeConfigurationGates(session);
                session.gate = null;
                session.registryTailGate = null;
                clearReadinessToken(session);
                if (currentConfig.enableBackendNeoForgeCapabilityRelay()
                        && event.player().getProtocolVersion().getProtocol()
                                == currentConfig.expectedMinecraftProtocol()) {
                    session.backendNeoForgeCapabilityRelay.beginBackendTransition(target);
                }
            }
        }
    }

    /**
     * Starts the new lobby's NeoForge classification before Paper can emit vanilla
     * configuration packets.
     *
     * <p>{@link PlayerEnterConfigurationEvent} runs while the client still has its old PLAY
     * listener, so sending the query there could leak the new lobby cycle into the backend being
     * left. {@link PlayerConfigurationEvent}, however, is reached only after early backend
     * configuration packets have already been processed. Velocity emits this intermediate event
     * after installing the client's CONFIGURATION listener and before it resumes the target
     * backend, which is the safe re-entry boundary.</p>
     *
     * <p>The event's server reference can still describe the backend being left. Target identity
     * therefore comes exclusively from the one-shot, generation-bound fence armed by the awaited
     * {@code PlayerEnterConfigurationEvent}; no routing decision is made here.</p>
     */
    @Subscribe(priority = Short.MAX_VALUE, async = false)
    public void onEnteredConfiguration(PlayerEnteredConfigurationEvent event) {
        BridgeConfig currentConfig = activeConfig();
        if (currentConfig == null
                || event.player().getProtocolVersion().getProtocol()
                        != currentConfig.expectedMinecraftProtocol()) {
            return;
        }

        BridgeSession session = sessions.get(event.player().getUniqueId());
        if (session == null) {
            return;
        }

        long generation;
        boolean querySent;
        synchronized (session) {
            generation = session.lobbyCycleGeneration;
            boolean fencedLobbyEntry = session.lobbyConfigurationEntryFence.consume(
                    currentConfig.lobbyServer(), generation);
            if (!fencedLobbyEntry
                    || (session.state != State.LOBBY_ARMED
                            && session.state != State.LOBBY_WAITING_FOR_QUERY)) {
                return;
            }
            querySent = sendNeoForgeQuery(event.player(), session);
        }

        if (!querySent) {
            reject(event.player(), session,
                    "could not send NeoForge query at lobby CONFIGURATION entry");
            return;
        }
        logger.info(
                "Sent generation-bound NeoForge lobby query for {} [{}]: "
                        + "generation={}, phase=PlayerEnteredConfigurationEvent",
                event.player().getUsername(), event.player().getUniqueId(), generation);
    }

    @Subscribe(async = false)
    public EventTask onPlayerConfiguration(PlayerConfigurationEvent event) {
        BridgeConfig currentConfig = activeConfig();
        if (currentConfig == null
                || event.server() == null
                || !serverName(event.server()).equals(currentConfig.lobbyServer())) {
            return completedTask();
        }

        Player player = event.player();
        BridgeSession session = sessions.computeIfAbsent(player.getUniqueId(), BridgeSession::new);
        CompletableFuture<Void> gate;
        synchronized (session) {
            if (session.state == State.FAILED) {
                return completedTask();
            }
            if (session.state == State.OUTSIDE) {
                armLobbyLocked(player, session, currentConfig);
            }
            if (player.getProtocolVersion().getProtocol()
                    != currentConfig.expectedMinecraftProtocol()) {
                session.negotiation = ClientNegotiation.PROTOCOL_BYPASS;
                session.state = State.LOBBY_READY;
                return completedTask();
            }
            if (session.state == State.LOBBY_READY || session.state == State.LOBBY_PLAY) {
                return completedTask();
            }
            if ((session.state == State.LOBBY_SYNCING_FROZEN_REGISTRIES
                            || session.state == State.LOBBY_WAITING_FOR_REGISTRY_TAIL
                            || session.state == State.LOBBY_INJECTING_REGISTRIES)
                    && session.gate != null) {
                return EventTask.resumeWhenComplete(session.gate);
            }
            if (session.negotiation == ClientNegotiation.VANILLA) {
                session.state = State.LOBBY_READY;
                return completedTask();
            }

            if (session.gate == null || session.gate.isDone()) {
                session.gate = new CompletableFuture<>();
            }
            gate = session.gate;
            session.state = State.LOBBY_WAITING_FOR_QUERY;
            if (session.neoForgeQueryReceived) {
                completeNeoForgeLobbyHandshake(player, session, currentConfig);
            } else {
                sendNeoForgeQuery(player, session);
            }
        }

        CompletableFuture.delayedExecutor(
                currentConfig.handshakeProbeTimeoutMillis(), TimeUnit.MILLISECONDS).execute(() -> {
                    synchronized (session) {
                        if (gate.isDone() || session.state == State.FAILED) {
                            return;
                        }
                        if (session.neoForgeQueryReceived) {
                            return;
                        }
                        if (currentConfig.allowVanillaLobby()) {
                            session.negotiation = ClientNegotiation.VANILLA;
                            session.state = State.LOBBY_READY;
                            logger.info(
                                    "Lobby fallback accepted {} [{}] as vanilla-compatible",
                                    player.getUsername(), player.getUniqueId());
                            gate.complete(null);
                        } else {
                            reject(player, session, "NeoForge query timed out");
                        }
                    }
                });
        return EventTask.resumeWhenComplete(gate);
    }

    /**
     * Appends reviewed modded dynamic registries and their tags after Paper's vanilla prefix.
     *
     * <p>Injecting these packets while {@link PlayerConfigurationEvent} is held gives the first
     * modded entry numerical id zero. Paper subsequently sends the vanilla entries, so the login
     * packet's vanilla dimension-type id resolves to a modded dimension (observed as
     * {@code compactmachines:compact_world}, height 48). Velocity fires this event only after the
     * backend registry packets have been forwarded and before it sends the client's final
     * configuration acknowledgement. Holding this event therefore preserves Paper's vanilla ids
     * and installs the reviewed entries strictly as a tail.</p>
     */
    @Subscribe(async = false)
    public EventTask onPlayerFinishConfiguration(PlayerFinishConfigurationEvent event) {
        BridgeConfig currentConfig = activeConfig();
        if (currentConfig == null
                || event.server() == null
                || !serverName(event.server()).equals(currentConfig.lobbyServer())) {
            return completedTask();
        }

        Player player = event.player();
        BridgeSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.disconnect(INCOMPATIBLE);
            return completedTask();
        }

        CompletableFuture<Void> tailGate;
        synchronized (session) {
            if (session.state == State.FAILED) {
                return completedTask();
            }
            if (session.negotiation != ClientNegotiation.NEOFORGE) {
                if (session.state != State.LOBBY_READY || session.negotiation == null) {
                    reject(player, session,
                            "lobby registry tail reached before negotiation readiness");
                }
                return completedTask();
            }
            if (session.state == State.LOBBY_INJECTING_REGISTRIES
                    && session.registryTailGate != null) {
                return EventTask.resumeWhenComplete(session.registryTailGate);
            }
            if (session.state != State.LOBBY_WAITING_FOR_REGISTRY_TAIL) {
                reject(player, session,
                        "Paper registry prefix finished outside the reviewed tail state");
                return completedTask();
            }
            session.registryTailGate = new CompletableFuture<>();
            tailGate = session.registryTailGate;
            try {
                injectDynamicRegistryShims(player, session, currentConfig);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                reject(player, session,
                        "could not append reviewed dynamic registry tail: "
                                + exception.getMessage());
            }
        }
        return EventTask.resumeWhenComplete(tailGate);
    }

    // PluginMessageEvent ordering is security- and protocol-critical here. Velocity invokes
    // higher priorities first; maximum priority keeps this synchronous consumer ahead of the
    // default asynchronous listener chain instead of letting ACKs wait behind unrelated plugins.
    @Subscribe(priority = Short.MAX_VALUE, async = false)
    public void onPluginMessage(PluginMessageEvent event) {
        NecroTempusTransportService necroTransport = necroTempusTransport;
        if (necroTransport != null && necroTransport.handlePluginMessage(event)) {
            return;
        }
        String channelId = event.getIdentifier().getId();
        Player player = playerFrom(event);

        if (channelId.equals(BRIDGE_CONTROL.getId())) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            if (player != null) {
                consoleLogger.warn(
                        "Blocked inbound readiness-channel payload from {}: source={}, bytes={}",
                        player.getUsername(), event.getSource().getClass().getSimpleName(),
                        event.getData().length);
            }
            return;
        }

        // These identifiers belong process-wide to the official Simple Voice Chat plugins.
        // Returning without marking the event handled preserves their listener/forwarding path,
        // even if a rejected client or a stale configuration tried to reserve the same id.
        if (ReviewedSimpleVoiceChatExtension.externallyOwns(channelId)) {
            return;
        }
        if (ProxyPluginMessageOwnership.externallyOwns(channelId)) {
            traceNativeBungeePluginMessage(event, channelId, player);
            return;
        }

        BridgeConfig currentConfig = activeConfig();
        if (player == null || currentConfig == null) {
            return;
        }
        BridgeSession session = sessions.get(player.getUniqueId());
        ServerConnection endpoint = serverEndpoint(event);
        if (session != null
                && endpoint != null
                && channelId.equals(NEOFORGE_REGISTER.getId())
                && !serverName(endpoint).equals(currentConfig.lobbyServer())) {
            handleBackendNeoForgeCapabilityRelay(
                    event, player, endpoint, session, currentConfig);
            return;
        }
        if (session == null
                || endpoint == null
                || !serverName(endpoint).equals(currentConfig.lobbyServer())
                || !interceptsLobbyChannels(session)) {
            return;
        }

        boolean playSinkChannel = LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(
                event.getSource() instanceof Player,
                session.playSinkChannelIds,
                channelId);
        boolean reviewedConfigurationChannel =
                ReviewedConfigurationProfile.ownedChannelIds().contains(channelId);
        if (!playSinkChannel
                && !reviewedConfigurationChannel
                && !NEOFORGE_BUILTIN_CHANNEL_IDS.contains(channelId)) {
            return;
        }

        // Every channel owned by this exact session is consumed at the lobby boundary. Dynamic
        // PLAY ids are registered globally because Velocity has no per-player registrar, but a
        // global registration alone never grants consumption authority to another session or to
        // backend-originated traffic.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (playSinkChannel) {
            if (event.getSource() instanceof Player) {
                consumeLobbyPlayPayload(player, session, channelId, event.getData(), currentConfig);
            }
            return;
        }
        if (reviewedConfigurationChannel) {
            if (event.getSource() instanceof Player) {
                reject(player, session,
                        "unexpected client payload on reviewed CONFIGURATION channel "
                                + channelId);
            }
            return;
        }
        if (!(event.getSource() instanceof Player)) {
            return;
        }

        try {
            if (channelId.equals(NEOFORGE_REGISTER.getId())) {
                receiveNeoForgeQuery(
                        player, endpoint, session, event.getData(), currentConfig);
            } else if (channelId.equals(NEOFORGE_FROZEN_REGISTRY_COMPLETED.getId())) {
                receiveFrozenRegistryAcknowledgement(player, session, event.getData());
            } else {
                // These lobby-side advertisements are consumed, never reflected or authorized.
                // Apply a byte bound without imposing semantics stricter than NeoForge itself.
                NeoForgeHandshakeCodec.validateOpaqueControlPayload(
                        event.getData(), currentConfig.limits().maximumSetupBytes());
            }
        } catch (ProtocolViolationException exception) {
            reject(player, session, "malformed " + channelId + ": " + exception.getMessage());
        }
    }

    /**
     * Preserves the client's NeoForge identity across a Velocity-native backend transition.
     *
     * <p>The backend query is written to the client first. The exact capability advertisement
     * captured and structurally decoded during this same player's completed lobby session is then
     * written to the querying backend while Velocity still has backend reads paused for this
     * event. A later native client response is consumed as a duplicate. No target is chosen or
     * authorized here, and the backend remains responsible for all channel/version checks.</p>
     */
    private void handleBackendNeoForgeCapabilityRelay(
            PluginMessageEvent event,
            Player player,
            ServerConnection endpoint,
            BridgeSession session,
            BridgeConfig currentConfig) {
        if (!event.getResult().isAllowed()
                || !currentConfig.enableBackendNeoForgeCapabilityRelay()
                || player.getProtocolVersion().getProtocol()
                        != currentConfig.expectedMinecraftProtocol()) {
            return;
        }

        String target = serverName(endpoint);
        if (event.getSource() instanceof ServerConnection) {
            byte[] query = event.getData();
            synchronized (session) {
                var relayPlan = session.backendNeoForgeCapabilityRelay
                        .plan(target, query)
                        .orElse(null);
                if (relayPlan == null) {
                    return;
                }

                // Manual forwarding makes the order explicit: the client sees the target's real
                // query before any setup produced after the relayed advertisement.
                if (!player.sendPluginMessage(NEOFORGE_REGISTER, query)) {
                    consoleLogger.warn(
                            "Could not forward backend NeoForge query to {} for {}; "
                                    + "leaving the event to Velocity's native forwarding path",
                            player.getUsername(), target);
                    return;
                }
                event.setResult(PluginMessageEvent.ForwardResult.handled());

                byte[] advertisement = relayPlan.clientAdvertisement();
                if (!endpoint.sendPluginMessage(NEOFORGE_REGISTER, advertisement)) {
                    consoleLogger.warn(
                            "Could not relay cached NeoForge capability response for {} to {}; "
                                    + "the client's native response remains eligible for forwarding",
                            player.getUsername(), target);
                    return;
                }
                if (!session.backendNeoForgeCapabilityRelay.commit(relayPlan)) {
                    consoleLogger.warn(
                            "Discarded stale backend NeoForge relay state for {} -> {}; "
                                    + "the target still received the same-player advertisement",
                            player.getUsername(), target);
                    return;
                }
                logger.info(
                        "Relayed same-player NeoForge capability response for {} to {}: "
                                + "bytes={}, queryBytes={}, queryFingerprint={}, "
                                + "routing=Velocity-native, compatibilityAuthority=target-backend",
                        player.getUsername(),
                        target,
                        advertisement.length,
                        query.length,
                        shortFingerprint(relayPlan.fingerprint()));
            }
            return;
        }

        if (event.getSource() instanceof Player) {
            synchronized (session) {
                if (!session.backendNeoForgeCapabilityRelay
                        .shouldConsumeNativeResponse(target)) {
                    return;
                }
                event.setResult(PluginMessageEvent.ForwardResult.handled());
                logger.info(
                        "Consumed duplicate native NeoForge capability response from {} for {}: "
                                + "bytes={} (same-player response already relayed before backend ping)",
                        player.getUsername(), target, event.getData().length);
            }
        }
    }

    @Subscribe(async = false)
    public void onPlayerFinishedConfiguration(PlayerFinishedConfigurationEvent event) {
        BridgeConfig currentConfig = activeConfig();
        if (currentConfig == null
                || !serverName(event.server()).equals(currentConfig.lobbyServer())) {
            return;
        }

        BridgeSession session = sessions.get(event.player().getUniqueId());
        if (session == null) {
            event.player().disconnect(INCOMPATIBLE);
            return;
        }

        synchronized (session) {
            if (session.state != State.LOBBY_READY || session.negotiation == null) {
                reject(event.player(), session, "lobby configuration finished before readiness");
                return;
            }
            if (session.negotiation == ClientNegotiation.NEOFORGE) {
                try {
                    boolean unregisterSent = event.player().sendPluginMessage(
                            MINECRAFT_UNREGISTER,
                            NeoForgeHandshakeCodec.encodeDinnerboneChannels(
                                    advertisedLobbyBuiltins(session),
                                    currentConfig.limits().maximumSetupBytes()));
                    boolean registerSent = event.player().sendPluginMessage(
                            MINECRAFT_REGISTER,
                            NeoForgeHandshakeCodec.encodeDinnerboneChannels(
                                    List.of(
                                            MINECRAFT_REGISTER.getId(),
                                            MINECRAFT_UNREGISTER.getId(),
                                            NEOFORGE_REGISTER.getId()),
                                    currentConfig.limits().maximumSetupBytes()));
                    if (!unregisterSent || !registerSent) {
                        reject(event.player(), session, "could not finalize NeoForge lobby channels");
                        return;
                    }
                } catch (ProtocolViolationException exception) {
                    reject(event.player(), session,
                            "could not finalize NeoForge lobby channels: " + exception.getMessage());
                    return;
                }
            }
            clearRegistryReplacementAttachment(session);
            enterLobbyPlay(session);
        }
        scheduleLobbyPlayBootstraps(event.player(), session);
    }

    private void enterLobbyPlay(BridgeSession session) {
        rotateReadinessToken(session);
        session.state = State.LOBBY_PLAY;
        session.playEnteredNanos = System.nanoTime();
    }

    /**
     * Installs the raw lobby-entry REGISTER fence before Velocity resumes backend forwarding.
     *
     * <p>{@link ServerConnectedEvent} is awaited by Velocity; the later post-connect event is not.
     * Returning this task is therefore a protocol invariant, not an observability convenience.</p>
     */
    @Subscribe(priority = Short.MAX_VALUE, async = false)
    public EventTask onServerConnected(ServerConnectedEvent event) {
        BridgeConfig currentConfig = activeConfig();
        Player player = event.getPlayer();
        if (currentConfig == null
                || !event.getServer().getServerInfo().getName()
                        .equals(currentConfig.lobbyServer())) {
            return completedTask();
        }

        int clientProtocol = player.getProtocolVersion().getProtocol();
        if (clientProtocol == currentConfig.expectedMinecraftProtocol()) {
            return attachModernLobbyRegisterGuard(player, currentConfig);
        }
        if (clientProtocol == LegacyForgeHandoffPolicy.MINECRAFT_1_7_10_PROTOCOL
                && currentConfig.enableLegacyForgeLobbyEntryGuard()) {
            return attachLegacyLobbyRegisterGuard(player, currentConfig);
        }
        return completedTask();
    }

    private EventTask attachLegacyLobbyRegisterGuard(
            Player player, BridgeConfig currentConfig) {
        VelocityLegacyLobbyEntryGuard adapter = legacyLobbyEntryGuard;
        if (adapter == null) {
            consoleLogger.warn(
                    "Rejected protocol-5 lobby entry for {} [{}]: the legacy REGISTER "
                            + "adapter is unavailable",
                    player.getUsername(),
                    player.getUniqueId());
            player.disconnect(LEGACY_FORGE_INCOMPATIBLE);
            return completedTask();
        }

        LegacyLobbyRegisterSanitizer.Settings settings =
                new LegacyLobbyRegisterSanitizer.Settings(
                        currentConfig.maximumLegacyLobbyClientChannels(),
                        currentConfig.allowedLegacyLobbyClientChannels());
        LegacyLobbyRegisterGuardHandler.Listener listener =
                legacyLobbyEntryListener(player, currentConfig.lobbyServer());
        CompletableFuture<Void> installation = adapter.attach(
                        player,
                        currentConfig.lobbyServer(),
                        settings,
                        listener,
                        LEGACY_FORGE_GUARD_ATTACH_TIMEOUT_MILLIS)
                .handle((attachment, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrapCompletionFailure(failure);
                        consoleLogger.warn(
                                "Rejected protocol-5 lobby entry for {} [{}]: the awaited "
                                        + "REGISTER replay fence could not be installed",
                                player.getUsername(),
                                player.getUniqueId(),
                                cause);
                        player.disconnect(LEGACY_FORGE_INCOMPATIBLE);
                    } else {
                        logger.info(
                                "Armed protocol-5 lobby-entry REGISTER fence for {} [{}]: "
                                        + "outcome={}, maximumChannels={}, safeChannels={}",
                                player.getUsername(),
                                player.getUniqueId(),
                                attachment.outcome(),
                                currentConfig.maximumLegacyLobbyClientChannels(),
                                currentConfig.allowedLegacyLobbyClientChannels());
                    }
                    return null;
                });
        return EventTask.resumeWhenComplete(installation);
    }

    private EventTask attachModernLobbyRegisterGuard(
            Player player, BridgeConfig currentConfig) {
        VelocityModernLobbyEntryGuard adapter = modernLobbyEntryGuard;
        if (adapter == null) {
            consoleLogger.warn(
                    "Rejected modern lobby entry for {} [{}]: the Paper-bound REGISTER "
                            + "adapter is unavailable",
                    player.getUsername(),
                    player.getUniqueId());
            player.disconnect(INCOMPATIBLE);
            return completedTask();
        }

        String guardIdentity = player.getUniqueId().toString();
        ModernLobbyRegisterGuardHandler.Policy policy =
                () -> modernLobbyRegisterSettings(player.getUniqueId());
        ModernLobbyRegisterGuardHandler.Listener listener =
                modernLobbyEntryListener(player, currentConfig.lobbyServer());
        CompletableFuture<Void> installation = adapter.attach(
                        player,
                        currentConfig.lobbyServer(),
                        guardIdentity,
                        policy,
                        listener,
                        MODERN_REGISTER_GUARD_ATTACH_TIMEOUT_MILLIS)
                .handle((attachment, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrapCompletionFailure(failure);
                        consoleLogger.warn(
                                "Rejected modern lobby entry for {} [{}]: the awaited "
                                        + "Paper-bound REGISTER fence could not be installed",
                                player.getUsername(),
                                player.getUniqueId(),
                                cause);
                        player.disconnect(INCOMPATIBLE);
                    } else {
                        logger.info(
                                "Armed modern Paper-bound REGISTER fence for {} [{}]: "
                                        + "outcome={}, maximumForwardedChannels={}, "
                                        + "reservedProxyChannel=bungeecord:main, "
                                        + "proxyChannelOwner=Velocity, "
                                        + "velocityClientChannelState=untouched",
                                player.getUsername(),
                                player.getUniqueId(),
                                attachment.outcome(),
                                MODERN_LOBBY_REGISTER_FORWARD_LIMIT);
                    }
                    return null;
                });
        return EventTask.resumeWhenComplete(installation);
    }

    @Subscribe(priority = Short.MAX_VALUE, async = false)
    public void onPlayerModInfo(PlayerModInfoEvent event) {
        NecroTempusTransportService transport = necroTempusTransport;
        if (transport != null) {
            transport.handlePlayerModInfo(event);
        }
    }

    @Subscribe(priority = Short.MAX_VALUE, async = false)
    public void onPlayerChannelRegister(PlayerChannelRegisterEvent event) {
        NecroTempusTransportService transport = necroTempusTransport;
        if (transport != null) {
            transport.handlePlayerChannelRegister(event);
        }
    }

    @Subscribe(priority = Short.MIN_VALUE, async = false)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        String previous = Optional.ofNullable(event.getPreviousServer())
                .map(server -> server.getServerInfo().getName())
                .orElseGet(() -> event.getPlayer().getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse("none"));
        String originalTarget = event.getOriginalServer().getServerInfo().getName();
        String effectiveTarget = event.getResult().getServer()
                .map(server -> server.getServerInfo().getName())
                .orElse("DENIED");
        consoleLogger.info(
                "[BUNGEE-TRACE] preConnect player={} protocol={} previous={} originalTarget={} "
                        + "effectiveTarget={} observerPriority={} routingMutation=false",
                event.getPlayer().getUsername(),
                event.getPlayer().getProtocolVersion().getProtocol(),
                previous,
                originalTarget,
                effectiveTarget,
                Short.MIN_VALUE);
        NecroTempusTransportService transport = necroTempusTransport;
        if (transport != null) {
            transport.handleServerPreConnect(event);
        }
    }

    @Subscribe(async = false)
    public void onServerPostConnect(ServerPostConnectEvent event) {
        NecroTempusTransportService transport = necroTempusTransport;
        if (transport != null) {
            transport.handleServerPostConnect(event);
        }
        BridgeConfig currentConfig = activeConfig();
        if (currentConfig == null) {
            return;
        }
        ServerConnection connection = event.getPlayer().getCurrentServer().orElse(null);
        if (connection == null) {
            return;
        }
        String target = serverName(connection);
        BridgeSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null && target.equals(currentConfig.lobbyServer())) {
            session = sessions.computeIfAbsent(
                    event.getPlayer().getUniqueId(), BridgeSession::new);
        }
        if (session == null) {
            return;
        }

        synchronized (session) {
            if (target.equals(currentConfig.lobbyServer())) {
                // ViaVersion-compatible older protocols may not emit configuration events.
                if (session.negotiation == null
                        && event.getPlayer().getProtocolVersion().getProtocol()
                                != currentConfig.expectedMinecraftProtocol()) {
                    armLobbyLocked(event.getPlayer(), session, currentConfig);
                    session.negotiation = ClientNegotiation.PROTOCOL_BYPASS;
                    rotateReadinessToken(session);
                    session.state = State.LOBBY_PLAY;
                    session.playEnteredNanos = System.nanoTime();
                }
            } else {
                session.lastConfirmedBackendServerId = target;
                session.lobbyConfigurationEntryFence.clear();
                clearRegistryReplacementAttachment(session);
                clearBlockStateTranslation(session);
                clearLegacyForgeHandoffGuard(session);
                session.state = State.OUTSIDE;
                completeConfigurationGates(session);
                session.gate = null;
                session.registryTailGate = null;
                clearReadinessToken(session);
            }
        }
        if (target.equals(currentConfig.lobbyServer())) {
            attachLegacyForgeHandoffGuard(
                    event.getPlayer(), connection, session, currentConfig);
            scheduleLobbyPlayBootstraps(event.getPlayer(), session);
        }
    }

    private LegacyLobbyRegisterGuardHandler.Listener legacyLobbyEntryListener(
            Player player, String lobbyServer) {
        return new LegacyLobbyRegisterGuardHandler.Listener() {
            @Override
            public void rewritten(
                    int originalChannelCount, List<String> retainedChannels, long sequence) {
                logger.info(
                        "Rewrote bounded protocol-5 REGISTER replay for {} -> {}: "
                                + "originalChannels={}, retainedChannels={}, sequence={}",
                        player.getUsername(),
                        lobbyServer,
                        originalChannelCount,
                        retainedChannels,
                        sequence);
            }

            @Override
            public void dropped(int originalChannelCount, String reason, long sequence) {
                logger.info(
                        "Dropped unsafe protocol-5 REGISTER replay for {} -> {}: "
                                + "originalChannels={}, reason={}, sequence={}",
                        player.getUsername(),
                        lobbyServer,
                        originalChannelCount,
                        reason,
                        sequence);
            }

            @Override
            public void failed(Throwable failure) {
                consoleLogger.warn(
                        "Protocol-5 lobby-entry REGISTER fence failed closed for {} [{}] -> {}; "
                                + "disconnecting before more plugin messages reach Paper",
                        player.getUsername(),
                        player.getUniqueId(),
                        lobbyServer,
                        failure);
                player.disconnect(LEGACY_FORGE_INCOMPATIBLE);
            }
        };
    }

    private ModernLobbyRegisterGuardHandler.Listener modernLobbyEntryListener(
            Player player, String lobbyServer) {
        return new ModernLobbyRegisterGuardHandler.Listener() {
            @Override
            public void rewritten(
                    String outerChannel,
                    int originalChannelCount,
                    List<String> retainedChannels,
                    long sequence) {
                logger.info(
                        "Rewrote Paper-bound {} for {} -> {}: originalChannels={}, "
                                + "retainedCount={}, omittedCount={}, retainedChannels={}, "
                                + "sequence={}, velocityClientChannelState=untouched",
                        outerChannel,
                        player.getUsername(),
                        lobbyServer,
                        originalChannelCount,
                        retainedChannels.size(),
                        Math.max(0, originalChannelCount - retainedChannels.size()),
                        retainedChannels,
                        sequence);
            }

            @Override
            public void dropped(
                    String outerChannel,
                    int originalChannelCount,
                    String reason,
                    long sequence) {
                logger.info(
                        "Dropped Paper-bound {} for {} -> {}: originalChannels={}, "
                                + "reason={}, sequence={}, velocityClientChannelState=untouched",
                        outerChannel,
                        player.getUsername(),
                        lobbyServer,
                        originalChannelCount,
                        reason,
                        sequence);
            }

            @Override
            public void failed(Throwable failure) {
                consoleLogger.warn(
                        "Modern lobby-entry REGISTER fence failed closed for {} [{}] -> {}; "
                                + "disconnecting before an unbounded registration reaches Paper",
                        player.getUsername(),
                        player.getUniqueId(),
                        lobbyServer,
                        failure);
                player.disconnect(INCOMPATIBLE);
            }
        };
    }

    /**
     * Returns the current bounded Paper-facing channel contract, or null for a native pass-through
     * session. The full client registration remains stored by Velocity before this policy runs.
     */
    private ModernLobbyRegisterSanitizer.Settings modernLobbyRegisterSettings(UUID playerUuid) {
        BridgeSession session = sessions.get(playerUuid);
        if (session == null) {
            return null;
        }
        synchronized (session) {
            boolean lifecycleStateEligible = switch (session.state) {
                case LOBBY_ARMED, LOBBY_WAITING_FOR_QUERY,
                        LOBBY_SYNCING_FROZEN_REGISTRIES,
                        LOBBY_WAITING_FOR_REGISTRY_TAIL, LOBBY_INJECTING_REGISTRIES,
                        LOBBY_READY, LOBBY_PLAY -> true;
                default -> false;
            };
            if (!lifecycleStateEligible || session.negotiation != ClientNegotiation.NEOFORGE) {
                return null;
            }

            LinkedHashSet<String> allowed = new LinkedHashSet<>();
            // Paper plugins use this proxy-owned transport for server-selection menus. Reserve
            // its slot before any capped optional channels; never claim it as an Obelisk sink.
            addModernLobbyRegisterChannels(
                    allowed, List.of(BUNGEE_PLUGIN_MESSAGE_CHANNEL_ID));
            addModernLobbyRegisterChannels(
                    allowed, NEOFORGE_BUILTIN_CHANNEL_IDS.stream().sorted().toList());
            addModernLobbyRegisterChannels(allowed, List.of(WORLD_EDIT_CUI_CHANNEL_ID));
            addModernLobbyRegisterChannels(
                    allowed,
                    ReviewedSimpleVoiceChatExtension.externallyOwnedChannelIds().stream()
                            .sorted()
                            .toList());
            addModernLobbyRegisterChannels(
                    allowed,
                    session.externallyOwnedPlayChannels.stream()
                            .map(Channel::id)
                            .sorted()
                            .toList());
            addModernLobbyRegisterChannels(
                    allowed,
                    session.clientboundPlayBootstrapChannels.stream()
                            .map(Channel::id)
                            .sorted()
                            .toList());
            addModernLobbyRegisterChannels(
                    allowed, session.playSinkChannelIds.stream().sorted().toList());
            return new ModernLobbyRegisterSanitizer.Settings(
                    MODERN_LOBBY_REGISTER_FORWARD_LIMIT, allowed);
        }
    }

    private static void addModernLobbyRegisterChannels(
            LinkedHashSet<String> allowed, List<String> candidates) {
        for (String channel : candidates) {
            if (allowed.size() >= MODERN_LOBBY_REGISTER_FORWARD_LIMIT) {
                return;
            }
            if (ModernLobbyRegisterSanitizer.isForwardableChannel(channel)) {
                allowed.add(channel);
            }
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        NecroTempusTransportService transport = necroTempusTransport;
        if (transport != null) {
            transport.handleDisconnect(event);
        }
        BridgeSession session = sessions.remove(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        synchronized (session) {
            session.state = State.FAILED;
            session.lobbyConfigurationEntryFence.clear();
            clearRegistryReplacementAttachment(session);
            clearBlockStateTranslation(session);
            clearLegacyForgeHandoffGuard(session);
            abandonConfigurationGates(session);
            clearReadinessToken(session);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent ignored) {
        shutdownNecroTempusTransport();
        exactServerConfigCatalog = Optional.empty();
        sessions.values().forEach(session -> {
            synchronized (session) {
                clearRegistryReplacementAttachment(session);
                clearBlockStateTranslation(session);
                clearLegacyForgeHandoffGuard(session);
            }
        });
        reviewedBlockStateProfiles = ReviewedBlockStateProfileCatalog.empty();
    }

    private void scheduleLobbyPlayBootstraps(Player player, BridgeSession session) {
        synchronized (session) {
            if (session.playBootstrapSent
                    || session.playBootstrapScheduled
                    || session.playBootstrapWriteInFlight
                    || session.state != State.LOBBY_PLAY) {
                return;
            }
            session.playBootstrapScheduled = true;
        }
        CompletableFuture.delayedExecutor(
                        PLAY_BOOTSTRAP_RETRY_MILLIS, TimeUnit.MILLISECONDS)
                .execute(() -> sendLobbyPlayBootstraps(player, session));
    }

    private void sendLobbyPlayBootstraps(Player player, BridgeSession session) {
        BridgeConfig currentConfig = activeConfig();
        String currentServer = player.getCurrentServer()
                .map(Atm10LobbyVelocityPlugin::serverName)
                .orElse("");
        boolean retry = false;
        String failure = null;
        VelocityPluginMessageBatchSender sender = null;
        VelocityPluginMessageBatchSender.Batch wirePlan = null;
        List<String> sentChannels = List.of();
        int sentBytes = 0;
        SilentGearEmbeddedProfile sentProfile = null;
        long sentGeneration = 0L;
        boolean bootstrapCompleted = false;

        synchronized (session) {
            session.playBootstrapScheduled = false;
            if (currentConfig == null
                    || sessions.get(player.getUniqueId()) != session
                    || session.state == State.FAILED
                    || session.playBootstrapSent
                    || session.playBootstrapWriteInFlight
                    || !currentServer.equals(currentConfig.lobbyServer())) {
                return;
            }

            session.playBootstrapSendAttempts++;
            if (session.state != State.LOBBY_PLAY) {
                if (session.playBootstrapSendAttempts
                        < MAXIMUM_PLAY_BOOTSTRAP_SEND_ATTEMPTS) {
                    retry = true;
                } else {
                    failure = "clientbound PLAY bootstrap never reached lobby PLAY state";
                }
            } else {
                if (session.silentGearProfile != null
                        && session.silentGearAcks == null) {
                    session.silentGearAcks = new SilentGearAckTransaction(
                            session.silentGearProfile.profileId());
                }
                if (session.clientboundPlayBootstrapChannels.isEmpty()) {
                    session.playBootstrapSent = true;
                    session.playBootstrapSentNanos = System.nanoTime();
                    bootstrapCompleted = true;
                    sentGeneration = session.playBootstrapGeneration;
                } else {
                    sender = pluginMessageBatchSender;
                    if (sender == null) {
                        failure = "Velocity PLAY plugin-message sender is unavailable";
                    } else {
                        VelocityPluginMessageBatchSender.Batch.Builder builder =
                                VelocityPluginMessageBatchSender.Batch.builder(
                                        "lobby-play-" + session.playerUuid + "-"
                                                + session.playBootstrapGeneration);
                        for (Channel channel : session.clientboundPlayBootstrapChannels) {
                            final byte[] payload;
                            if (channel.id().equals(
                                    ApothicEnchantingBootstrapPayload.CHANNEL_ID)) {
                                payload = ApothicEnchantingBootstrapPayload.payload();
                            } else if (SilentGearProtocol.isSyncChannel(channel.id())
                                    && session.silentGearProfile != null) {
                                payload = session.silentGearProfile.payload(channel.id());
                            } else {
                                failure = "unsupported negotiated PLAY bootstrap " + channel.id();
                                break;
                            }
                            builder.addOwned(channel.id(), payload);
                        }
                        if (failure == null) {
                            wirePlan = builder.build();
                            sentChannels = session.clientboundPlayBootstrapChannels.stream()
                                    .map(Channel::id)
                                    .toList();
                            sentBytes = wirePlan.totalBytes();
                            sentProfile = session.silentGearProfile;
                            sentGeneration = session.playBootstrapGeneration;
                            session.playBootstrapWriteInFlight = true;
                        }
                    }
                }
            }
        }

        if (failure != null) {
            reject(player, session, failure);
        } else if (retry) {
            scheduleLobbyPlayBootstraps(player, session);
        } else if (bootstrapCompleted) {
            continueAfterLobbyPlayBootstrap(
                    player, session, sentChannels, sentBytes, sentProfile, sentGeneration);
        } else if (wirePlan != null) {
            VelocityPluginMessageBatchSender finalSender = sender;
            List<String> finalSentChannels = sentChannels;
            int finalSentBytes = sentBytes;
            SilentGearEmbeddedProfile finalSentProfile = sentProfile;
            long finalSentGeneration = sentGeneration;
            finalSender.sendPlay(player, wirePlan)
                    .orTimeout(PLAY_BOOTSTRAP_WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    .whenComplete((ignored, writeFailure) -> finishLobbyPlayBootstrapWrite(
                            player,
                            session,
                            finalSentGeneration,
                            finalSentChannels,
                            finalSentBytes,
                            finalSentProfile,
                            writeFailure));
        }
    }

    private void finishLobbyPlayBootstrapWrite(
            Player player,
            BridgeSession session,
            long generation,
            List<String> sentChannels,
            int sentBytes,
            SilentGearEmbeddedProfile sentProfile,
            Throwable writeFailure) {
        boolean completed = false;
        boolean retry = false;
        String failure = null;
        synchronized (session) {
            if (sessions.get(player.getUniqueId()) != session
                    || session.playBootstrapGeneration != generation) {
                return;
            }
            session.playBootstrapWriteInFlight = false;
            boolean stillInLobby = player.getCurrentServer()
                    .map(Atm10LobbyVelocityPlugin::serverName)
                    .map(name -> name.equals(activeLobbyName()))
                    .orElse(false);
            if (session.state == State.FAILED || !stillInLobby) {
                return;
            }
            if (writeFailure != null) {
                Throwable root = unwrapCompletionFailure(writeFailure);
                if (VelocityPluginMessageBatchSender.isProtocolStateMismatch(root)
                        && session.playBootstrapSendAttempts
                                < MAXIMUM_PLAY_BOOTSTRAP_SEND_ATTEMPTS) {
                    retry = true;
                } else {
                    failure = "clientbound PLAY bootstrap write failed: "
                            + stableFailureMessage(root);
                }
            } else if (session.state != State.LOBBY_PLAY) {
                if (session.playBootstrapSendAttempts
                        < MAXIMUM_PLAY_BOOTSTRAP_SEND_ATTEMPTS) {
                    retry = true;
                } else {
                    failure = "clientbound PLAY bootstrap completed outside lobby PLAY state";
                }
            } else {
                session.playBootstrapSent = true;
                session.playBootstrapSentNanos = System.nanoTime();
                session.playBootstrapBytesSent = sentBytes;
                completed = true;
            }
        }

        if (failure != null) {
            reject(player, session, failure);
        } else if (retry) {
            scheduleLobbyPlayBootstraps(player, session);
        } else if (completed) {
            continueAfterLobbyPlayBootstrap(
                    player, session, sentChannels, sentBytes, sentProfile, generation);
        }
    }

    private void continueAfterLobbyPlayBootstrap(
            Player player,
            BridgeSession session,
            List<String> sentChannels,
            int sentBytes,
            SilentGearEmbeddedProfile sentProfile,
            long sentGeneration) {
        if (!sentChannels.isEmpty()) {
            logger.info(
                    "Flushed bounded NeoForge lobby PLAY bootstrap to {}: channels={}, bytes={} "
                            + "(Velocity-only; Paper readiness still withheld)",
                    player.getUsername(), sentChannels, sentBytes);
        }
        if (sentProfile != null) {
            logger.info(
                    "Sent self-contained Silent Gear profile to {}: profile={}, "
                            + "orderedPayloads={}, sequenceSha256={}",
                    player.getUsername(),
                    sentProfile.profileId(),
                    silentGearPayloadSummary(sentProfile),
                    sentProfile.payloadSequenceSha256());
            scheduleSilentGearAckTimeout(
                    player, session, sentGeneration, sentProfile.profileId());
        } else {
            completeLobbyClientInitialization(player, session);
        }
    }

    /**
     * Completes the lobby PLAY bootstrap transaction. The fixed-size Velocity -> Paper readiness
     * signal is emitted only when every advertised recipe-time dependency was satisfied; otherwise
     * the client remains admitted and only Paper's synthetic empty recipe update is withheld.
     */
    private void completeLobbyClientInitialization(Player player, BridgeSession session) {
        BridgeConfig currentConfig = activeConfig();
        ServerConnection lobby = player.getCurrentServer().orElse(null);
        boolean ready = false;
        long totalNegotiationMillis = -1L;
        long configurationToPlayMillis = -1L;
        long playBootstrapAndAckMillis = -1L;
        PaperRecipeLifecyclePolicy.Evaluation recipeLifecycleEvaluation = null;
        synchronized (session) {
            if (currentConfig == null
                    || lobby == null
                    || sessions.get(player.getUniqueId()) != session
                    || session.state != State.LOBBY_PLAY
                    || !serverName(lobby).equals(currentConfig.lobbyServer())
                    || session.lobbyClientInitializationComplete) {
                return;
            }
            if (!session.playBootstrapSent) {
                reject(player, session,
                        "client initialization completed before PLAY bootstraps were sent");
                return;
            }
            if (session.silentGearProfile != null
                    && (session.silentGearAcks == null
                            || !session.silentGearAcks.complete())) {
                reject(player, session,
                        "client initialization completed before Silent Gear ACKs");
                return;
            }
            recipeLifecycleEvaluation = PaperRecipeLifecyclePolicy.decide(
                    new PaperRecipeLifecyclePolicy.Context(
                            session.playBootstrapSent,
                            session.negotiation != ClientNegotiation.NEOFORGE
                                    || session.silentGearProfile != null,
                            session.configurationPrefixWriteComplete,
                            java.util.Objects.requireNonNullElse(
                                    structuralClientContract(session), ""),
                            session.advertisedNamespaces,
                            session.clientboundPlayBootstrapChannelIds,
                            session.transientServerConfigs,
                            session.reviewedTransientConfigCatalogId,
                            session.reviewedTransientConfigNameSequenceSha256,
                            session.reviewedTransientConfigPayloadSequenceSha256,
                            session.reviewedTransientConfigCatalogEncodedBytes,
                            session.registryShimReceipts));
            session.lobbyClientInitializationComplete = true;
            if (session.negotiation == ClientNegotiation.NEOFORGE) {
                session.backendNeoForgeCapabilityRelay.markLobbyInitializationComplete();
            }
            session.lobbyInitializationCompleteNanos = System.nanoTime();
            totalNegotiationMillis = elapsedMillis(
                    session.querySentNanos, session.lobbyInitializationCompleteNanos);
            configurationToPlayMillis = elapsedMillis(
                    session.configurationReadyNanos, session.playEnteredNanos);
            playBootstrapAndAckMillis = elapsedMillis(
                    session.playEnteredNanos, session.lobbyInitializationCompleteNanos);
            ready = true;
        }
        if (!ready) {
            return;
        }
        PaperRecipeLifecyclePolicy.Evaluation finalRecipeLifecycleEvaluation =
                java.util.Objects.requireNonNull(
                        recipeLifecycleEvaluation, "recipeLifecycleEvaluation");
        if (finalRecipeLifecycleEvaluation.release()) {
            logger.info(
                    "Completed ordered lobby PLAY initialization for {}: playBootstraps={}, "
                            + "silentGearAcks={}, totalNegotiationMs={}, "
                            + "configurationToPlayMs={}, playBootstrapAndAckMs={}; "
                            + "admission=COMPLETE, recipeLifecycle=RELEASE, "
                            + "recipeLifecycleBasis={}, observedContract={}, structuralContract={}",
                    player.getUsername(),
                    session.clientboundPlayBootstrapChannelIds,
                    session.silentGearAcks == null ? 0 : session.silentGearAcks.received(),
                    totalNegotiationMillis,
                    configurationToPlayMillis,
                    playBootstrapAndAckMillis,
                    finalRecipeLifecycleEvaluation.basis(),
                    shortFingerprint(session.fullClientContractSha256),
                    shortFingerprint(structuralClientContract(session)));
            sendLobbyReady(player, lobby, session);
        } else {
            logger.warn(
                    "Completed adaptive lobby PLAY initialization for {}: playBootstraps={}, "
                            + "silentGearAcks={}, totalNegotiationMs={}, "
                            + "configurationToPlayMs={}, playBootstrapAndAckMs={}; "
                            + "admission=COMPLETE, recipeLifecycle=WITHHELD, basis={}, reason={}, "
                            + "unsatisfiedRequirements={}, observedContract={}, structuralContract={}, "
                            + "pluginInitiatedDisconnect=false",
                    player.getUsername(),
                    session.clientboundPlayBootstrapChannelIds,
                    session.silentGearAcks == null ? 0 : session.silentGearAcks.received(),
                    totalNegotiationMillis,
                    configurationToPlayMillis,
                    playBootstrapAndAckMillis,
                    finalRecipeLifecycleEvaluation.basis(),
                    finalRecipeLifecycleEvaluation.reason(),
                    finalRecipeLifecycleEvaluation.unsatisfiedRequirements(),
                    shortFingerprint(session.fullClientContractSha256),
                    shortFingerprint(structuralClientContract(session)));
        }
    }

    private void scheduleSilentGearAckTimeout(
            Player player,
            BridgeSession session,
            long generation,
            String profileId) {
        CompletableFuture.delayedExecutor(
                        SILENT_GEAR_ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    String failure = null;
                    synchronized (session) {
                        boolean sameSession = sessions.get(player.getUniqueId()) == session;
                        boolean sameGeneration = session.playBootstrapGeneration == generation;
                        boolean sameProfile = session.silentGearProfile != null
                                && session.silentGearProfile.profileId().equals(profileId);
                        boolean stillInLobby = player.getCurrentServer()
                                .map(Atm10LobbyVelocityPlugin::serverName)
                                .map(name -> name.equals(activeLobbyName()))
                                .orElse(false);
                        if (sameSession
                                && sameGeneration
                                && sameProfile
                                && stillInLobby
                                && session.state != State.FAILED
                                && (session.silentGearAcks == null
                                        || !session.silentGearAcks.complete())) {
                            int received = session.silentGearAcks == null
                                    ? 0
                                    : session.silentGearAcks.received();
                            long elapsed = elapsedMillis(
                                    session.playBootstrapSentNanos, System.nanoTime());
                            failure = "Silent Gear profile ACK timeout for " + profileId
                                    + " after " + elapsed + " ms: received " + received + "/3";
                        }
                    }
                    if (failure != null) {
                        reject(player, session, failure);
                    }
                });
    }

    private static List<String> silentGearPayloadSummary(
            SilentGearEmbeddedProfile profile) {
        return profile.channels().stream()
                .map(channel -> channel
                        + "(entries=" + profile.entryCount(channel)
                        + ",bytes=" + profile.payloadBytes(channel)
                        + ",sha256=" + profile.payloadSha256(channel) + ')')
                .toList();
    }

    private static void armLobbyLocked(
            Player player, BridgeSession session, BridgeConfig currentConfig) {
        session.lobbyCycleGeneration++;
        session.lobbyConfigurationEntryFence.clear();
        clearRegistryReplacementAttachment(session);
        clearBlockStateTranslation(session);
        completeConfigurationGates(session);
        session.gate = null;
        session.registryTailGate = null;
        session.querySentThisLobbyCycle = false;
        session.neoForgeQueryReceived = false;
        session.lobbyHandshakeStarted = false;
        session.querySentNanos = 0L;
        session.queryReceivedNanos = 0L;
        session.frozenRegistrySentNanos = 0L;
        session.configurationReadyNanos = 0L;
        session.playEnteredNanos = 0L;
        session.lobbyInitializationCompleteNanos = 0L;
        session.registryShimPacketsInjected = 0;
        session.registryShimBytesInjected = 0;
        session.registryShimReceipts = Set.of();
        session.registryReplacementStatus = "NOT_SELECTED";
        session.registryReplacementMode = null;
        session.registryReplacementEvidenceId = "none";
        session.advertisedNamespaces = Set.of();
        session.queryBytes = 0;
        session.playSinkChannels = List.of();
        session.playSinkChannelIds = Set.of();
        session.externallyOwnedPlayChannels = List.of();
        session.clientboundPlayBootstrapChannels = List.of();
        session.clientboundPlayBootstrapChannelIds = Set.of();
        session.playBootstrapScheduled = false;
        session.playBootstrapWriteInFlight = false;
        session.playBootstrapSent = false;
        session.playBootstrapSentNanos = 0L;
        session.playBootstrapSendAttempts = 0;
        session.playBootstrapBytesSent = 0;
        session.playBootstrapGeneration++;
        session.lobbyClientInitializationComplete = false;
        session.backendNeoForgeCapabilityRelay.beginLobbyCycle();
        session.clientRegistryFingerprint = null;
        session.fullClientContractSha256 = null;
        session.normalizedClientContractSha256 = null;
        session.reviewedClientContractVariant = "none";
        session.atm10Normal82GiselleMergeEvidence = false;
        session.negotiationPlanCacheHit = false;
        session.negotiationPlanningNanos = 0L;
        session.silentGearProfile = null;
        session.silentGearAcks = null;
        session.frozenRegistryAcks = null;
        session.frozenRegistryCountSent = 0;
        session.frozenRegistryEntriesSent = 0;
        session.frozenRegistryBytesSent = 0;
        session.configurationPrefixPacketsSent = 0;
        session.configurationPrefixBytesSent = 0;
        session.configurationPrefixCacheHit = false;
        session.configurationPrefixWriteComplete = false;
        session.frozenRegistryGeneration++;
        session.ae2JeiSessionOptimizationSelected = false;
        session.ae2JeiSessionOptimizationBytesSent = 0;
        session.playSinkPlanMode = null;
        session.omittedPlaySinkChannels = 0;
        session.previousBackendServerId = null;
        session.transientServerConfigs = currentConfig.transientServerConfigs();
        session.transientServerConfigSource = "baseline";
        session.derivedTransientServerConfigs = 0;
        session.omittedDerivedTransientServerConfigs = 0;
        session.ignoredTransientConfigNamespaces = 0;
        session.reviewedTransientConfigCatalog = null;
        session.reviewedTransientConfigCatalogId =
                TransientServerConfigPlanner.NO_REVIEWED_CATALOG;
        session.reviewedTransientConfigNameSequenceSha256 = "";
        session.reviewedTransientConfigPayloadSequenceSha256 = "";
        session.reviewedTransientConfigCatalogCandidates = 0;
        session.reviewedTransientConfigCatalogEncodedBytes = 0;
        session.addedReviewedTransientConfigs = 0;
        session.retainedReviewedTransientConfigs = 0;
        session.discardedBaseTransientConfigs = 0;
        session.playRateLimiter = new LobbyPlayRateLimiter(
                currentConfig.maximumLobbyPlayPackets(),
                currentConfig.maximumLobbyPlayTotalBytes(),
                currentConfig.lobbyPlayBudgetRefillMillis(),
                System.nanoTime());
        session.observedPlaySinkChannels = new LinkedHashSet<>();
        session.negotiation = null;
        clearReadinessToken(session);
        if (player.getProtocolVersion().getProtocol()
                == currentConfig.expectedMinecraftProtocol()) {
            session.state = State.LOBBY_ARMED;
        } else {
            session.negotiation = ClientNegotiation.PROTOCOL_BYPASS;
            session.state = State.LOBBY_READY;
        }
    }

    private void receiveNeoForgeQuery(
            Player player,
            ServerConnection lobbyEndpoint,
            BridgeSession session,
            byte[] payload,
            BridgeConfig currentConfig) throws ProtocolViolationException {
        synchronized (session) {
            if (session.state != State.LOBBY_ARMED
                    && session.state != State.LOBBY_WAITING_FOR_QUERY
                    && session.state != State.LOBBY_SYNCING_FROZEN_REGISTRIES
                    && session.state != State.LOBBY_WAITING_FOR_REGISTRY_TAIL
                    && session.state != State.LOBBY_INJECTING_REGISTRIES
                    && session.state != State.LOBBY_READY
                    && session.state != State.LOBBY_PLAY) {
                reject(player, session, "late or out-of-state NeoForge query response");
                return;
            }
            if (!session.querySentThisLobbyCycle) {
                reject(player, session, "unsolicited NeoForge query response");
                return;
            }
            NeoForgeHandshakeCodec.validateOpaqueQueryResponse(payload, currentConfig.limits());
            if (!session.neoForgeQueryReceived) {
                long queryReceivedNanos = System.nanoTime();
                long planningStartedNanos = System.nanoTime();
                String registryFingerprint = SilentGearProtocol.fingerprint(payload);
                int minecraftProtocol = player.getProtocolVersion().getProtocol();
                SilentGearProfileCatalog profiles = silentGearProfiles;
                Registry decodedClientRegistry = NeoForgeHandshakeCodec.decodeLobbyQuery(
                        payload, currentConfig.limits());
                ChannelContractSignature.Signatures contractSignatures =
                        ChannelContractSignature.from(decodedClientRegistry);
                boolean atm10Normal82GiselleMergeEvidence =
                        Atm10Normal82Contract.matchesGiselleRegistryMergeEvidence(
                                minecraftProtocol, decodedClientRegistry);
                java.util.Optional<ReviewedClientContractEvidence.Atm10Normal80Variant>
                        reviewedNormal80Variant =
                                ReviewedClientContractEvidence.matchAtm10Normal80(
                                        minecraftProtocol,
                                        decodedClientRegistry,
                                        contractSignatures);
                Optional<Atm10Normal81ClientContractEvidence.Match> reviewedNormal81Variant =
                        Atm10Normal81ClientContractEvidence.match(
                                minecraftProtocol, decodedClientRegistry, contractSignatures);
                List<Channel> externallyOwnedPlayChannels = reviewedNormal80Variant
                        .map(ReviewedClientContractEvidence.Atm10Normal80Variant
                                ::externallyOwnedPlayChannels)
                        .orElseGet(() -> reviewedNormal81Variant
                                .map(Atm10Normal81ClientContractEvidence.Match
                                        ::externallyOwnedPlayChannels)
                                .orElse(List.of()));
                Set<String> externallyOwnedPlayChannelIds = externallyOwnedPlayChannels.stream()
                        .map(Channel::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                LobbyNegotiationPlanCache cache = negotiationPlanCache;
                if (cache == null) {
                    throw new IllegalStateException(
                            "lobby negotiation plan cache is unavailable");
                }
                LobbyNegotiationPlanCache.Key cacheKey =
                        new LobbyNegotiationPlanCache.Key(
                                minecraftProtocol,
                                registryFingerprint,
                                contractSignatures.fullContractSha256(),
                                decodedClientRegistry.channelCount(),
                                decodedClientRegistry.ignoredChannelCount());
                Ae2JeiSessionOptimization optimization = ae2JeiSessionOptimization;
                LobbyNegotiationPlanCache.Lookup lookup = cache.resolve(cacheKey, () -> {
                    SilentGearProtocol.Compatibility inspectedSilentGear =
                            SilentGearProtocol.inspect(decodedClientRegistry.channelsFor(
                                    NeoForgeHandshakeCodec.PLAY_PROTOCOL));
                    SilentGearEmbeddedProfile reviewedContractProfile = profiles.select(
                                    minecraftProtocol,
                                    decodedClientRegistry,
                                    contractSignatures)
                            .orElse(null);
                    boolean reviewedAutomaticSinkContract =
                            ReviewedClientContractEvidence.allowsAutomaticPlaySinks(
                                    minecraftProtocol,
                                    decodedClientRegistry,
                                    contractSignatures);
                    SilentGearEmbeddedProfile contractSelectedProfile =
                            currentConfig.enableBuiltInSilentGearSnapshotBridge()
                                    ? reviewedContractProfile
                                    : null;
                    boolean executableProfileAvailable = contractSelectedProfile != null;
                    int automaticPlaySinkBudget =
                            LobbyPlaySinkAdmissionPolicy.automaticBudget(
                                    reviewedAutomaticSinkContract,
                                    currentConfig.maximumAutomaticPlaySinkChannels());
                    PlaySinkPlanner.Plan computedPlayPlan = PlaySinkPlanner.plan(
                            minecraftProtocol,
                            decodedClientRegistry,
                            currentConfig.pinnedPlaySinkChannels(),
                            automaticPlaySinkBudget,
                            currentConfig.enableBuiltInApothicEnchantingBootstrap(),
                            currentConfig.enableBuiltInSilentGearSnapshotBridge(),
                            executableProfileAvailable,
                            externallyOwnedPlayChannelIds);
                    SilentGearEmbeddedProfile selectedProfile =
                            computedPlayPlan.silentGearProfileSelected()
                                    ? java.util.Objects.requireNonNull(
                                            contractSelectedProfile,
                                            "selected Silent Gear profile disappeared")
                                    : null;
                    List<String> configDerivationNamespaces = configDerivationNamespaces(
                            computedPlayPlan.advertisedNamespaces(),
                            externallyOwnedPlayChannelIds);
                    TransientServerConfigPlanner.Plan computedConfigPlan =
                            TransientServerConfigPlanner.plan(
                                    currentConfig.transientServerConfigs(),
                                    configDerivationNamespaces,
                                    currentConfig.deriveServerConfigsFromClientChannels()
                                            && selectedProfile != null,
                                    currentConfig.maximumDerivedServerConfigs());
                    java.util.Optional<ReviewedBackendNeoForgeAdvertisement.Evidence>
                            reviewedBackendAdvertisement =
                                    computedPlayPlan.mode() == PlaySinkPlanner.Mode.PARSED
                                            ? ReviewedBackendNeoForgeAdvertisement.match(
                                                    minecraftProtocol,
                                                    payload.length,
                                                    decodedClientRegistry,
                                                    contractSignatures,
                                                    selectedProfile)
                                            : java.util.Optional.empty();
                    boolean strictBackendAdvertisement =
                            computedPlayPlan.mode() == PlaySinkPlanner.Mode.PARSED
                                    && computedPlayPlan.ignoredRegistryChannelCount() == 0;
                    boolean ae2Selected =
                            currentConfig.enableBuiltInAe2JeiSessionOptimization()
                                    && optimization != null
                                    && optimization.matches(
                                            selectedProfile,
                                            Set.copyOf(computedPlayPlan.advertisedNamespaces()));
                    return new LobbyNegotiationPlanCache.Plan(
                            contractSignatures,
                            inspectedSilentGear,
                            java.util.Optional.ofNullable(selectedProfile),
                            computedPlayPlan,
                            computedConfigPlan,
                            reviewedBackendAdvertisement,
                            strictBackendAdvertisement,
                            ae2Selected);
                });
                LobbyNegotiationPlanCache.Plan cachedPlan = lookup.plan();
                if (!cachedPlan.contractSignatures().equals(contractSignatures)) {
                    throw new IllegalStateException(
                            "cached negotiation contract differs from validated query");
                }
                SilentGearProtocol.Compatibility silentGearCompatibility =
                        cachedPlan.silentGearCompatibility();
                PlaySinkPlanner.Plan plan = cachedPlan.playSinkPlan();
                SilentGearEmbeddedProfile matchingSilentGearProfile =
                        cachedPlan.selectedSilentGearProfile().orElse(null);
                String profileNormalizedClientContract = matchingSilentGearProfile == null
                        ? contractSignatures.fullContractSha256()
                        : matchingSilentGearProfile.fullClientContractSha256()
                                .orElse(contractSignatures.fullContractSha256());
                String normalizedClientContractSha256 = reviewedNormal81Variant
                        .map(Atm10Normal81ClientContractEvidence.Match
                                ::normalizedFullClientContractSha256)
                        .orElse(profileNormalizedClientContract);
                String reviewedClientContractVariant = reviewedNormal80Variant
                        .map(ReviewedClientContractEvidence.Atm10Normal80Variant::id)
                        .orElseGet(() -> reviewedNormal81Variant
                                .map(Atm10Normal81ClientContractEvidence.Match::id)
                                .orElseGet(() -> ReviewedClientContractEvidence.ATM10_NORMAL_7_3.matches(
                                        minecraftProtocol, contractSignatures)
                                ? ReviewedClientContractEvidence.ATM10_NORMAL_7_3.id()
                                : "none"));
                TransientServerConfigPlanner.Plan configPlan =
                        cachedPlan.transientConfigPlan();
                // The exact 8.1 catalog comes from two matching cold boots of the official pack.
                // It is keyed to protocol plus the strictly normalized complete contract and is
                // negotiation enrichment: missing/corrupt evidence never affects admission.
                Optional<Atm10Normal81ServerConfigCatalog.Catalog> reviewedConfigCatalog =
                        exactServerConfigCatalog;
                configPlan = TransientServerConfigPlanner.withReviewedContractCatalog(
                        configPlan,
                        minecraftProtocol,
                        normalizedClientContractSha256,
                        reviewedConfigCatalog);
                PlaySinkReservation reservation = reservePlaySinkChannels(
                        plan.channels(), currentConfig);

                session.neoForgeQueryReceived = true;
                session.queryReceivedNanos = queryReceivedNanos;
                session.queryBytes = payload.length;
                session.clientRegistryFingerprint = registryFingerprint;
                session.fullClientContractSha256 = contractSignatures.fullContractSha256();
                session.normalizedClientContractSha256 = normalizedClientContractSha256;
                session.reviewedClientContractVariant = reviewedClientContractVariant;
                session.atm10Normal82GiselleMergeEvidence =
                        atm10Normal82GiselleMergeEvidence;
                if (atm10Normal82GiselleMergeEvidence) {
                    consoleLogger.info(
                            "compatibilityEvidence=ATM10_8_2_LINEAGE_GISELLE_MERGE for {}: "
                                    + "protocol={}, markerChannel={}@{}, adAstraNamespace=true, "
                                    + "scope=FOUR_ENTRY_ENCHANTMENT_MERGE_ONLY, "
                                    + "fullPackIdentity=UNCLAIMED, "
                                    + "admissionDecision=UNCHANGED_CARDINAL, aclMutation=false",
                            player.getUsername(),
                            minecraftProtocol,
                            Atm10Normal82Contract.LOGISTICS_CHANNEL_ID,
                            Atm10Normal82Contract.LOGISTICS_NETWORK_VERSION);
                }
                if (reviewedNormal81Variant.isPresent()) {
                    consoleLogger.info(
                            "compatibilityEvidence=ATM10_8_1_EXACT_OPTIONAL_NORMALIZATION for {}: "
                                    + "observedContract={}, structuralContract={}, variant={}; "
                                    + "scope=STRUCTURAL_ENRICHMENT_ONLY, "
                                    + "admissionDecision=UNCHANGED_CARDINAL, aclMutation=false",
                            player.getUsername(), session.fullClientContractSha256,
                            normalizedClientContractSha256, reviewedClientContractVariant);
                } else if (reviewedNormal80Variant.isEmpty()) {
                    consoleLogger.info(
                            "compatibilityEvidence=OPTIONAL_NORMALIZATION_NOT_ESTABLISHED for {}: "
                                    + "observedContract={}, usableChannels={}, ignoredChannels={}, "
                                    + "ignoredDuplicates={}, worldEditCuiPlayChannels={}, "
                                    + "voiceChatPlayChannels={}; "
                                    + "admissionDecision=UNCHANGED_CARDINAL, aclMutation=false",
                            player.getUsername(), session.fullClientContractSha256,
                            decodedClientRegistry.channelCount(),
                            decodedClientRegistry.ignoredChannelCount(),
                            decodedClientRegistry.ignoredChannels().stream()
                                    .filter(channel -> channel.reason()
                                            == NeoForgeHandshakeCodec.IgnoredChannelReason
                                                    .DUPLICATE_CHANNEL_ID)
                                    .count(),
                            decodedClientRegistry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL)
                                    .stream().filter(channel -> channel.id().equals("worldedit:cui"))
                                    .count(),
                            decodedClientRegistry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL)
                                    .stream().filter(channel -> channel.id().startsWith("voicechat:"))
                                    .count());
                }
                session.silentGearProfile = matchingSilentGearProfile;
                session.ae2JeiSessionOptimizationSelected =
                        cachedPlan.ae2JeiSessionOptimizationSelected();
                session.negotiationPlanCacheHit = lookup.hit();
                session.negotiationPlanningNanos = System.nanoTime() - planningStartedNanos;
                session.negotiation = ClientNegotiation.NEOFORGE;
                session.playSinkChannels = reservation.channels();
                session.playSinkChannelIds = reservation.channelIds();
                session.clientboundPlayBootstrapChannels =
                        plan.clientboundBootstrapChannels();
                session.clientboundPlayBootstrapChannelIds =
                        plan.clientboundBootstrapChannels().stream()
                                .map(Channel::id)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                session.externallyOwnedPlayChannels = externallyOwnedPlayChannels;
                session.advertisedNamespaces = Set.copyOf(plan.advertisedNamespaces());
                session.playSinkPlanMode = plan.mode();
                java.util.Optional<ReviewedBackendNeoForgeAdvertisement.Evidence>
                        reviewedBackendAdvertisement =
                                cachedPlan.backendAdvertisementEvidence();
                boolean strictBackendAdvertisement =
                        cachedPlan.strictBackendAdvertisement();
                session.backendNeoForgeCapabilityRelay.captureValidatedAdvertisement(
                        payload, registryFingerprint);
                session.omittedPlaySinkChannels = Math.addExact(
                        plan.omittedAutomaticChannelCount(), reservation.omittedByCapacity());
                session.transientServerConfigs = configPlan.configs();
                session.transientServerConfigSource = configPlan.reviewedCatalogApplied()
                                ? "reviewed-exact-runtime-catalog"
                        : configPlan.derivedConfigCount() > 0
                                ? "derived-fallback"
                                : matchingSilentGearProfile == null
                                        ? "reviewed-baseline-only"
                                        : "reviewed-profile-baseline";
                session.derivedTransientServerConfigs = configPlan.derivedConfigCount();
                session.omittedDerivedTransientServerConfigs =
                        configPlan.omittedDerivedConfigCount();
                session.ignoredTransientConfigNamespaces = configPlan.ignoredNamespaceCount();
                session.reviewedTransientConfigCatalog = configPlan.reviewedCatalogApplied()
                        ? reviewedConfigCatalog.orElseThrow()
                        : null;
                session.reviewedTransientConfigCatalogId = configPlan.reviewedCatalogId();
                session.reviewedTransientConfigNameSequenceSha256 =
                        configPlan.reviewedCatalogNameSequenceSha256();
                session.reviewedTransientConfigPayloadSequenceSha256 =
                        configPlan.reviewedCatalogPayloadSequenceSha256();
                session.reviewedTransientConfigCatalogCandidates =
                        configPlan.reviewedCatalogCandidateCount();
                session.reviewedTransientConfigCatalogEncodedBytes =
                        configPlan.reviewedCatalogEncodedBytes();
                session.addedReviewedTransientConfigs =
                        configPlan.addedReviewedCatalogConfigCount();
                session.retainedReviewedTransientConfigs =
                        configPlan.retainedReviewedCatalogConfigCount();
                session.discardedBaseTransientConfigs = configPlan.discardedBaseConfigCount();
                int registrarBoundedPlaySinks =
                        registrarBoundedPlaySinkChannelCount(session);
                logger.info(
                        "Received bounded NeoForge lobby query response from {} [{}]: bytes={}, "
                                + "registryMode={}, eligiblePlaySinks={}, ignoredRegistryChannels={}, "
                                + "selectedPlaySinks={}, registrarBoundedPlaySinks={}, "
                                + "sessionOnlyPlaySinks={}, selectedPlayBootstraps={}, "
                                + "queryFingerprint={}, canonicalChannelContract={}, "
                                + "normalizedClientContract={}, reviewedClientVariant={}, "
                                + "externallyOwnedPlayChannels={}, "
                                + "backendAdvertisementRelay={}, "
                                + "immutablePlanCache={}, cacheGeneration={}, cacheSize={}, "
                                + "cacheHits={}, cacheMisses={}, cacheEvictions={}, "
                                + "silentGearContract={}, silentGearContractId={}, "
                                + "silentGearNetwork={}, frozenRegistryAdvertisement={}, "
                                + "silentGearProfile={}, "
                                + "ae2JeiSessionOptimization={}, "
                                + "omittedPlaySinks={}, "
                                + "advertisedNamespaces={}, transientConfigSource={}, "
                                + "previousBackend={}, transientConfigs={}, "
                                + "reviewedConfigCatalog={} (candidates={}, added={}, "
                                + "retained={}, discardedBase={}, encodedBytes={}, "
                                + "nameSequenceSha256={}, payloadSequenceSha256={}), "
                                + "derivedTransientConfigs={}, "
                                + "omittedDerivedConfigs={}, ignoredConfigNamespaces={}, "
                                + "queryRoundTripMs={}, validatedPlanningMicros={}",
                        player.getUsername(),
                        player.getUniqueId(),
                        payload.length,
                        plan.mode(),
                        plan.eligibleChannelCount(),
                        plan.ignoredRegistryChannelCount(),
                        session.playSinkChannels.size(),
                        registrarBoundedPlaySinks,
                        session.playSinkChannels.size() - registrarBoundedPlaySinks,
                        session.clientboundPlayBootstrapChannels.size(),
                        shortFingerprint(registryFingerprint),
                        shortFingerprint(contractSignatures.fullContractSha256()),
                        shortFingerprint(session.normalizedClientContractSha256),
                        session.reviewedClientContractVariant,
                        session.externallyOwnedPlayChannels.stream()
                                .map(Channel::id)
                                .sorted()
                                .toList(),
                        strictBackendAdvertisement
                                ? "validated-strict-zero-ignored"
                                : reviewedBackendAdvertisement
                                        .map(evidence -> "validated-reviewed-" + evidence.id())
                                        .orElse("validated-client-observed"),
                        lookup.hit() ? "hit" : "miss",
                        lookup.snapshot().generation(),
                        lookup.snapshot().size(),
                        lookup.snapshot().hits(),
                        lookup.snapshot().misses(),
                        lookup.snapshot().evictions(),
                        plan.silentGearContractSha256().isEmpty()
                                ? "rejected"
                                : shortFingerprint(plan.silentGearContractSha256()),
                        silentGearCompatibility.contractId(),
                        silentGearCompatibility.networkVersion(),
                        plan.frozenRegistryCompatibleChannelsAdvertised()
                                ? "exact"
                                : "rejected",
                        session.silentGearProfile == null
                                ? "none"
                                : session.silentGearProfile.profileId(),
                        session.ae2JeiSessionOptimizationSelected,
                        session.omittedPlaySinkChannels,
                        plan.advertisedNamespaces().size(),
                        session.transientServerConfigSource,
                        session.previousBackendServerId == null
                                ? "none"
                                : session.previousBackendServerId,
                        session.transientServerConfigs.size(),
                        session.reviewedTransientConfigCatalogId,
                        session.reviewedTransientConfigCatalogCandidates,
                        session.addedReviewedTransientConfigs,
                        session.retainedReviewedTransientConfigs,
                        session.discardedBaseTransientConfigs,
                        session.reviewedTransientConfigCatalogEncodedBytes,
                        shortFingerprint(session.reviewedTransientConfigNameSequenceSha256),
                        shortFingerprint(session.reviewedTransientConfigPayloadSequenceSha256),
                        session.derivedTransientServerConfigs,
                        session.omittedDerivedTransientServerConfigs,
                        session.ignoredTransientConfigNamespaces,
                        elapsedMillis(session.querySentNanos, session.queryReceivedNanos),
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMicros(
                                session.negotiationPlanningNanos));
                if (plan.mode() == PlaySinkPlanner.Mode.PINNED_FALLBACK) {
                    consoleLogger.warn(
                            "Compatibility decision=CAPABILITY_ONLY; NeoForge registry decoder degraded to pinned PLAY sinks for {}: {} "
                                    + "(pinned={})",
                            player.getUsername(),
                            plan.fallbackReason(),
                            session.playSinkChannelIds);
                }
                if (session.omittedPlaySinkChannels > 0) {
                    logger.info(
                            "PLAY sink budget omitted {} channels for {}; pinned and join-priority "
                                    + "channels were considered first",
                            session.omittedPlaySinkChannels,
                            player.getUsername());
                }
                if (session.omittedDerivedTransientServerConfigs > 0) {
                    consoleLogger.warn(
                            "Transient SERVER config derivation omitted {} namespaces for {}; "
                                    + "raise maximum-derived-server-configs only after reviewing "
                                    + "the client registry size",
                            session.omittedDerivedTransientServerConfigs,
                            player.getUsername());
                }
                if (plan.advertisedNamespaces().contains("silentgear")
                        && session.silentGearProfile == null
                        && currentConfig.enableBuiltInSilentGearSnapshotBridge()) {
                    boolean knownContractOnly =
                            ReviewedClientContractEvidence.ATM10_NORMAL_7_3.matches(
                                    minecraftProtocol, contractSignatures)
                            || ReviewedClientContractEvidence.matchesAtm10Normal80(
                                    minecraftProtocol, contractSignatures);
                    String rejectionReason = knownContractOnly
                            ? "known ATM10 Normal contract did not resolve to its reviewed "
                                    + "executable embedded profile"
                            : plan.silentGearRejectionReason().isEmpty()
                                    ? "no exact embedded profile matched; continuing with the client-observed bounded capability plan"
                                    : plan.silentGearRejectionReason();
                    consoleLogger.warn(
                            "Compatibility decision=ADMITTED_CAPABILITY_ADAPTIVE for {}: contractId={}, "
                                    + "network={}, queryFingerprint={}, "
                                    + "canonicalChannelContract={}, reason={}. "
                                    + "No unverified structural payload was sent; adaptive channel negotiation continues; "
                                    + "pluginInitiatedDisconnect=false",
                            player.getUsername(),
                            silentGearCompatibility.contractId(),
                            silentGearCompatibility.networkVersion(),
                            registryFingerprint,
                            contractSignatures.fullContractSha256(),
                            rejectionReason);
                }
                BlockStateTranslationDecision translationDecision =
                        selectBlockStateTranslation(session, minecraftProtocol);
                boolean translationOwnershipTransferred = false;
                try {
                    if (translationDecision.selection().isPresent()) {
                        BlockStateTranslationSelection selectedTranslation =
                                translationDecision.selection().orElseThrow();
                        translationOwnershipTransferred = true;
                        beginBlockStateTranslationAttachment(
                                player,
                                lobbyEndpoint,
                                session,
                                selectedTranslation);
                    } else {
                        warnUnverifiedBlockStatePassthrough(
                                player,
                                session,
                                translationDecision.status());
                    }
                } finally {
                    if (!translationOwnershipTransferred) {
                        translationDecision.close();
                    }
                }
            }
            if (session.state == State.LOBBY_WAITING_FOR_QUERY) {
                completeNeoForgeLobbyHandshake(player, session, currentConfig);
            }
        }
    }

    private void warnUnverifiedBlockStatePassthrough(
            Player player,
            BridgeSession session,
            String evidenceStatus,
            Throwable failure) {
        consoleLogger.warn(
                "compatibilityCapability=BLOCKSTATE_MAP_UNAVAILABLE, "
                        + "blockStateRuntimeReadiness=NOT_REQUIRED, "
                        + "visualBlockStateCompatibility=UNVERIFIED_PASSTHROUGH for {}: "
                        + "no reviewed lobby BlockState translation is active; "
                        + "evidenceStatus={}, clientContract={}, previousBackend={}, failure={}; "
                        + "fallback=IMMEDIATE_NO_CONTROL_WAIT; admission continues "
                        + "by cardinal policy, lobby visuals are not certified, "
                        + "admissionDecision=UNCHANGED_CARDINAL, aclMutation=false, "
                        + "pluginInitiatedDisconnect=false, routeMutation=false",
                player.getUsername(),
                evidenceStatus,
                shortFingerprint(session.fullClientContractSha256),
                session.previousBackendServerId == null
                        ? "none"
                        : session.previousBackendServerId,
                failure == null ? "none" : stableFailureMessage(failure));
    }

    private void warnUnverifiedBlockStatePassthrough(
            Player player,
            BridgeSession session,
            String evidenceStatus) {
        warnUnverifiedBlockStatePassthrough(player, session, evidenceStatus, null);
    }

    /**
     * Selects only an exact, reviewed map embedded in the selected client contract. Missing or
     * unknown maps immediately fall through to passthrough; the first lobby hop never waits for
     * or consumes backend runtime control-plane state.
     */
    private BlockStateTranslationDecision selectBlockStateTranslation(
            BridgeSession session, int minecraftProtocol) {
        Optional<BlockStateTranslationProfile> exact = reviewedBlockStateProfiles
                .findStructuralEnrichment(
                        minecraftProtocol, structuralClientContract(session));
        if (exact.isPresent()) {
            if (lobbyPlayPacketTranslator == null) {
                return BlockStateTranslationDecision.unavailable(
                        "EXACT_EMBEDDED_TRANSLATOR_UNAVAILABLE");
            }
            BlockStateTranslationProfile profile = exact.orElseThrow();
            return BlockStateTranslationDecision.selected(
                    new BlockStateTranslationSelection(
                            profile, "embedded-exact-" + profile.profileId()),
                    "EMBEDDED_EXACT_OFFLINE");
        }
        return BlockStateTranslationDecision.unavailable("NO_EXACT_EMBEDDED_EVIDENCE");
    }

    /** Observed identity is retained for telemetry; only reviewed normalization selects data. */
    private static String structuralClientContract(BridgeSession session) {
        return session.normalizedClientContractSha256 == null
                ? session.fullClientContractSha256
                : session.normalizedClientContractSha256;
    }

    private record BlockStateTranslationSelection(
            BlockStateTranslationProfile profile,
            String source) {
        private BlockStateTranslationSelection {
            java.util.Objects.requireNonNull(profile, "profile");
            java.util.Objects.requireNonNull(source, "source");
        }
    }

    private record BlockStateTranslationDecision(
            Optional<BlockStateTranslationSelection> selection, String status)
            implements AutoCloseable {
        private BlockStateTranslationDecision {
            selection = java.util.Objects.requireNonNull(selection, "selection");
            java.util.Objects.requireNonNull(status, "status");
            if (status.isBlank()) {
                throw new IllegalArgumentException("BlockState decision status is blank");
            }
        }

        private static BlockStateTranslationDecision selected(
                BlockStateTranslationSelection selection, String status) {
            return new BlockStateTranslationDecision(Optional.of(selection), status);
        }

        private static BlockStateTranslationDecision unavailable(String status) {
            return new BlockStateTranslationDecision(Optional.empty(), status);
        }

        @Override
        public void close() {
            // Embedded selections own no external runtime evidence.
        }
    }

    /**
     * Installs the exact PLAY-state id translator on this lobby backend before CONFIGURATION is
     * allowed to finish. The handler is backend-scoped: it can never rewrite a later ATM backend
     * connection selected by Velocity.
     */
    private void beginBlockStateTranslationAttachment(
            Player player,
            ServerConnection lobbyEndpoint,
            BridgeSession session,
            BlockStateTranslationSelection selection) {
        java.util.Objects.requireNonNull(selection, "selection");
        BlockStateTranslationProfile translationProfile = selection.profile();
        String evidenceSource = selection.source();
        java.util.Objects.requireNonNull(translationProfile, "translationProfile");
        java.util.Objects.requireNonNull(evidenceSource, "evidenceSource");
        if (session.blockStateTranslationGate != null) {
            if (session.blockStateTranslationEndpoint == lobbyEndpoint
                    && session.blockStateTranslationProfile == translationProfile) {
                return;
            }
            clearBlockStateTranslation(session);
            warnUnverifiedBlockStatePassthrough(
                    player,
                    session,
                    "CONFLICTING_ATTACHMENT_QUARANTINED",
                    new IllegalStateException(
                            "conflicting lobby BlockState translator attachment attempt"));
            return;
        }

        VelocityLobbyPlayPacketTranslator translator = lobbyPlayPacketTranslator;
        if (translator == null) {
            clearBlockStateTranslation(session);
            warnUnverifiedBlockStatePassthrough(
                    player, session, "TRANSLATOR_UNAVAILABLE", null);
            return;
        }

        long generation = session.lobbyCycleGeneration;
        long attachmentStartedNanos = System.nanoTime();
        session.blockStateTranslationEndpoint = lobbyEndpoint;
        session.blockStateTranslationProfile = translationProfile;
        session.blockStateTranslationSource = evidenceSource;
        VelocityLobbyPlayPacketTranslator.EvidenceGuard packetEvidenceGuard =
                new VelocityLobbyPlayPacketTranslator.EvidenceGuard() {
                    @Override
                    public boolean current() {
                        return true;
                    }

                    @Override
                    public boolean commitForward() {
                        return true;
                    }
                };
        final CompletableFuture<VelocityLobbyPlayPacketTranslator.Lease> attachment;
        try {
            attachment = translator.attach(
                    lobbyEndpoint,
                    translationProfile,
                    packetEvidenceGuard,
                    failure -> onBlockStateTranslationFailure(
                            player,
                            session,
                            lobbyEndpoint,
                            translationProfile,
                            generation,
                            failure),
                    BLOCK_STATE_TRANSLATOR_ATTACH_TIMEOUT_MILLIS);
            if (attachment == null) {
                throw new IllegalStateException(
                        "lobby BlockState translator returned no attachment future");
            }
            attachment.orTimeout(
                    BLOCK_STATE_TRANSLATOR_ATTACH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException | LinkageError failure) {
            clearBlockStateTranslation(session);
            warnUnverifiedBlockStatePassthrough(
                    player, session, "ATTACH_THROWN_QUARANTINED", failure);
            return;
        }
        session.blockStateTranslationGate = attachment;

        attachment.whenComplete((lease, failure) -> {
            synchronized (session) {
                boolean current = sessions.get(player.getUniqueId()) == session
                        && session.lobbyCycleGeneration == generation
                        && session.blockStateTranslationEndpoint == lobbyEndpoint
                        && session.blockStateTranslationProfile == translationProfile
                        && session.blockStateTranslationGate == attachment
                        && session.state != State.OUTSIDE
                        && session.state != State.FAILED;
                if (!current) {
                    closeBlockStateTranslationLeaseSafely(lease);
                    return;
                }
                Throwable attachmentFailure = failure == null
                        ? null
                        : unwrapCompletionFailure(failure);
                boolean leaseActive = false;
                if (attachmentFailure == null && lease != null) {
                    try {
                        leaseActive = lease.active();
                    } catch (RuntimeException | LinkageError leaseFailure) {
                        attachmentFailure = leaseFailure;
                    }
                }
                if (attachmentFailure != null || lease == null || !leaseActive) {
                    Throwable cause = attachmentFailure == null
                            ? new IllegalStateException(
                                    "lobby BlockState translator returned an inactive lease")
                            : attachmentFailure;
                    closeBlockStateTranslationLeaseSafely(lease);
                    clearBlockStateTranslation(session);
                    warnUnverifiedBlockStatePassthrough(
                            player,
                            session,
                            "ATTACH_FAILED_QUARANTINED",
                            cause);
                    BridgeConfig currentConfig = activeConfig();
                    if (currentConfig != null
                            && session.state == State.LOBBY_WAITING_FOR_QUERY) {
                        completeNeoForgeLobbyHandshake(player, session, currentConfig);
                    }
                    return;
                }
                session.blockStateTranslationLease = lease;
                logger.info(
                        "Attached lobby BlockState translator for {}: profile={}, evidenceSource={}, "
                                + "sourceStates={}, clientGlobalStates={}, mapSha256={}, "
                                + "evidenceScope={}, scope=backend-lobby-generation-{}, "
                                + "attachmentWaitMs={}",
                        player.getUsername(),
                        translationProfile.profileId(),
                        evidenceSource,
                        translationProfile.sourceStateCount(),
                        translationProfile.clientGlobalStateCount(),
                        translationProfile.mapSha256(),
                        "EMBEDDED_OFFLINE",
                        generation,
                        elapsedMillis(attachmentStartedNanos, System.nanoTime()));
                BridgeConfig currentConfig = activeConfig();
                if (currentConfig != null && session.state == State.LOBBY_WAITING_FOR_QUERY) {
                    completeNeoForgeLobbyHandshake(player, session, currentConfig);
                }
            }
        });
    }

    private void onBlockStateTranslationFailure(
            Player player,
            BridgeSession session,
            ServerConnection lobbyEndpoint,
            BlockStateTranslationProfile translationProfile,
            long generation,
            Throwable failure) {
        synchronized (session) {
            if (sessions.get(player.getUniqueId()) != session
                    || session.lobbyCycleGeneration != generation
                    || session.blockStateTranslationEndpoint != lobbyEndpoint
                    || session.blockStateTranslationProfile != translationProfile
                    || session.state == State.OUTSIDE
                    || session.state == State.FAILED) {
                return;
            }
            if (session.blockStateTranslationLease == null) {
                clearBlockStateTranslation(session);
                warnUnverifiedBlockStatePassthrough(
                        player,
                        session,
                        "PRE_ACTIVE_FAILURE_QUARANTINED",
                        failure);
                BridgeConfig currentConfig = activeConfig();
                if (currentConfig != null
                        && session.state == State.LOBBY_WAITING_FOR_QUERY) {
                    completeNeoForgeLobbyHandshake(player, session, currentConfig);
                }
                return;
            }
            consoleLogger.error(
                    "Fail-closed malformed lobby BlockState packet after active translation "
                            + "lease for {} [{}]: profile={}, admissionException=PACKET_INTEGRITY",
                    player.getUsername(),
                    player.getUniqueId(),
                    translationProfile.profileId(),
                    failure);
            reject(player, session,
                    "invalid lobby BlockState packet: " + stableFailureMessage(failure));
        }
    }

    /** Initializes the fixed NeoForge config lifecycle required before entering the Paper lobby. */
    private void completeNeoForgeLobbyHandshake(
            Player player, BridgeSession session, BridgeConfig currentConfig) {
        if (!session.neoForgeQueryReceived
                || session.negotiation != ClientNegotiation.NEOFORGE
                || session.state == State.FAILED
                || session.lobbyHandshakeStarted) {
            return;
        }
        if (session.blockStateTranslationProfile != null
                && (session.blockStateTranslationLease == null
                        || !session.blockStateTranslationLease.active())) {
            return;
        }
        session.lobbyHandshakeStarted = true;
        if (beginRegistryReplacementAttachment(player, session, currentConfig)) {
            return;
        }
        continueNeoForgeLobbyHandshake(player, session, currentConfig);
    }

    /**
     * Arms the reviewed Paper registry transform before any lobby CONFIGURATION prefix is written.
     * ATM10 8.1 keeps its exact full-registry replacement. The 8.2 lineage uses a narrow,
     * duplicate-rejecting four-entry Giselle merge. Any miss or adapter failure immediately keeps
     * Paper passthrough and resumes the cardinal path.
     */
    private boolean beginRegistryReplacementAttachment(
            Player player, BridgeSession session, BridgeConfig currentConfig) {
        record Selection(
                RegistryShimPacket packet,
                RegistryReplacementGuardHandler.TransformMode mode,
                String evidenceId) {
        }

        int clientProtocol = player.getProtocolVersion().getProtocol();
        boolean exactAtm10Normal81 = Atm10Normal81Contract.matchesStructuralIdentity(
                clientProtocol, structuralClientContract(session));
        boolean giselle82Merge = session.atm10Normal82GiselleMergeEvidence;
        final Optional<Selection> selected;
        try {
            Optional<RegistryShimPacket> exact81 =
                    RegistryShimCatalog.selectPaperRegistryReplacement(
                            clientProtocol,
                            registryShimPackets,
                            structuralClientContract(session));
            if (exact81.isPresent()) {
                selected = Optional.of(new Selection(
                        exact81.orElseThrow(),
                        RegistryReplacementGuardHandler.TransformMode.EXACT_REPLACEMENT,
                        Atm10Normal81Contract.ID));
            } else if (giselle82Merge) {
                selected = atm10Normal82GiselleExtensionPacket.map(packet -> new Selection(
                        packet,
                        RegistryReplacementGuardHandler.TransformMode.MERGE_DISTINCT_EXTENSION,
                        Atm10Normal82Contract.ID));
            } else {
                selected = Optional.empty();
            }
        } catch (RuntimeException | LinkageError failure) {
            removeRegistryReplacementProof(session);
            clearRegistryReplacementAttachment(session);
            session.registryReplacementStatus = "SELECTION_QUARANTINED_PAPER_PASSTHROUGH";
            warnRegistryReplacementPassthrough(
                    player, session, session.registryReplacementStatus, failure);
            return false;
        }
        if (selected.isEmpty()) {
            session.registryReplacementStatus = exactAtm10Normal81
                    ? "ATM10_8_1_RESOURCE_UNAVAILABLE_PAPER_PASSTHROUGH"
                    : giselle82Merge
                            ? "ATM10_8_2_GISELLE_RESOURCE_UNAVAILABLE_PAPER_PASSTHROUGH"
                            : "NOT_APPLICABLE";
            if (exactAtm10Normal81 || giselle82Merge) {
                removeRegistryReplacementProof(session);
                clearRegistryReplacementAttachment(session);
                warnRegistryReplacementPassthrough(
                        player, session, session.registryReplacementStatus, null);
            }
            return false;
        }

        Selection transform = selected.orElseThrow();
        RegistryShimPacket replacementPacket = transform.packet();
        VelocityRegistryReplacementGuard guard = registryReplacementGuard;
        if (guard == null) {
            removeRegistryReplacementProof(session);
            clearRegistryReplacementAttachment(session);
            session.registryReplacementStatus = "ADAPTER_UNAVAILABLE_PAPER_PASSTHROUGH";
            warnRegistryReplacementPassthrough(
                    player, session, session.registryReplacementStatus, null);
            return false;
        }

        long generation = session.lobbyCycleGeneration;
        session.registryReplacementPacket = replacementPacket;
        session.registryReplacementMode = transform.mode();
        session.registryReplacementEvidenceId = transform.evidenceId();
        session.registryReplacementStatus = "ATTACHING_BEFORE_CONFIGURATION_PREFIX";
        final CompletableFuture<VelocityRegistryReplacementGuard.Lease> attachment;
        try {
            attachment = guard.attach(
                    player,
                    replacementPacket,
                    transform.mode(),
                    new RegistryReplacementGuardHandler.Listener() {
                        @Override
                        public void replaced(RegistryShimReceipt receipt) {
                            logger.info(
                                    "Observed successful Paper registry transform: player={}, "
                                            + "mode={}, evidence={}, registry={}, shim={}, sha256={}",
                                    player.getUsername(),
                                    transform.mode(),
                                    transform.evidenceId(),
                                    receipt.registryId(),
                                    receipt.shimId(),
                                    receipt.sha256());
                        }

                        @Override
                        public void duplicate(Throwable failure) {
                            logger.warn(
                                    "Consumed duplicate Paper registry packet for {}: mode={}, "
                                            + "evidence={}, {}; transform fence and completed "
                                            + "receipt remain active through CONFIG completion",
                                    player.getUsername(),
                                    transform.mode(),
                                    transform.evidenceId(),
                                    stableFailureMessage(failure));
                        }

                        @Override
                        public void failed(Throwable failure) {
                            logger.warn(
                                    "Registry transform fence reported a terminal condition for "
                                            + "{}: mode={}, evidence={}, {}; Paper passthrough and "
                                            + "cardinal admission remain active",
                                    player.getUsername(),
                                    transform.mode(),
                                    transform.evidenceId(),
                                    stableFailureMessage(failure));
                        }
                    });
            if (attachment == null) {
                throw new IllegalStateException(
                        "registry replacement guard returned no attachment future");
            }
            // orTimeout completes the same future. A queued Netty installation observes
            // result.isDone() and aborts, so timeout cannot leave a late handler behind.
            attachment.orTimeout(
                    REGISTRY_REPLACEMENT_ATTACH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException | LinkageError failure) {
            removeRegistryReplacementProof(session);
            clearRegistryReplacementAttachment(session);
            session.registryReplacementStatus = "ATTACH_THROWN_PAPER_PASSTHROUGH";
            warnRegistryReplacementPassthrough(
                    player, session, session.registryReplacementStatus, failure);
            return false;
        }
        session.registryReplacementAttachment = attachment;
        attachment.whenComplete((lease, failure) -> {
            synchronized (session) {
                boolean current = sessions.get(player.getUniqueId()) == session
                        && session.lobbyCycleGeneration == generation
                        && session.registryReplacementAttachment == attachment
                        && session.lobbyHandshakeStarted
                        && session.state != State.OUTSIDE
                        && session.state != State.FAILED;
                if (!current) {
                    if (lease != null) {
                        detachRegistryReplacementLease(lease);
                    }
                    return;
                }
                session.registryReplacementAttachment = null;
                if (failure != null || lease == null || !lease.active()) {
                    if (lease != null) {
                        detachRegistryReplacementLease(lease);
                    }
                    removeRegistryReplacementProof(session);
                    clearRegistryReplacementAttachment(session);
                    session.registryReplacementStatus =
                            "ATTACH_FAILED_PAPER_PASSTHROUGH";
                    warnRegistryReplacementPassthrough(
                            player,
                            session,
                            session.registryReplacementStatus,
                            failure == null
                                    ? new IllegalStateException(
                                            "registry replacement attachment returned "
                                                    + "an inactive lease")
                                    : unwrapCompletionFailure(failure));
                    continueNeoForgeLobbyHandshake(player, session, currentConfig);
                    return;
                }

                session.registryReplacementLease = lease;
                session.registryReplacementStatus = "ARMED_BEFORE_CONFIGURATION_PREFIX";
                lease.completion().whenComplete((receipt, replacementFailure) ->
                        recordRegistryReplacementCompletion(
                                player,
                                session,
                                replacementPacket,
                                lease,
                                generation,
                                receipt,
                                replacementFailure));
                logger.info(
                        "Armed reviewed Paper registry transform before CONFIGURATION prefix for "
                                + "{}: mode={}, evidence={}, registry={}, shim={}, entries={}, "
                                + "extensionBytes={}, sha256={}, "
                                + "admissionDecision=UNCHANGED_CARDINAL",
                        player.getUsername(),
                        transform.mode(),
                        transform.evidenceId(),
                        replacementPacket.registryId(),
                        replacementPacket.shimId(),
                        replacementPacket.entryCount(),
                        replacementPacket.packetBytes(),
                        replacementPacket.sha256());
                continueNeoForgeLobbyHandshake(player, session, currentConfig);
            }
        });
        return true;
    }

    private void continueNeoForgeLobbyHandshake(
            Player player, BridgeSession session, BridgeConfig currentConfig) {
        try {
            VelocityPluginMessageBatchSender sender = pluginMessageBatchSender;
            if (sender == null) {
                reject(player, session,
                        "Velocity CONFIG plugin-message batch adapter is unavailable");
                return;
            }
            List<Channel> negotiatedPlayChannels = negotiatedPlayChannels(session);
            List<Channel> negotiatedConfigurationChannels = configurationChannels(session);
            List<String> advertisedBuiltIns = advertisedLobbyBuiltins(session).stream()
                    .sorted()
                    .toList();
            Ae2JeiSessionOptimization optimization =
                    session.ae2JeiSessionOptimizationSelected
                            ? ae2JeiSessionOptimization
                            : null;
            if (session.ae2JeiSessionOptimizationSelected && optimization == null) {
                reject(player, session,
                        "reviewed AE2-JEI session optimization disappeared");
                return;
            }
            LobbyConfigurationPrefixCache prefixCache = configurationPrefixCache;
            if (prefixCache == null) {
                reject(player, session,
                        "immutable CONFIGURATION prefix cache is unavailable");
                return;
            }
            Atm10Normal81ServerConfigCatalog.Catalog reviewedConfigCatalog =
                    session.reviewedTransientConfigCatalog;
            if (reviewedConfigCatalog != null
                    && !session.transientServerConfigs.equals(
                            reviewedConfigCatalog.fileNames())) {
                reject(player, session,
                        "exact SERVER-config catalog differs from selected transaction");
                return;
            }
            LobbyConfigurationPrefixCache.Key prefixKey =
                    new LobbyConfigurationPrefixCache.Key(
                            currentConfig.expectedMinecraftProtocol(),
                            negotiatedPlayChannels,
                            negotiatedConfigurationChannels,
                            advertisedBuiltIns,
                            session.transientServerConfigs,
                            session.reviewedTransientConfigPayloadSequenceSha256,
                            session.silentGearProfile == null
                                    ? ""
                                    : session.silentGearProfile.profileId(),
                            optimization == null ? "" : optimization.contentSha256());
            LobbyConfigurationPrefixCache.Lookup prefixLookup = prefixCache.resolve(
                    prefixKey,
                    () -> {
                        Map<Integer, List<Channel>> setupProtocols = new LinkedHashMap<>();
                        if (!negotiatedPlayChannels.isEmpty()) {
                            setupProtocols.put(
                                    NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                                    negotiatedPlayChannels);
                        }
                        setupProtocols.put(
                                NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL,
                                negotiatedConfigurationChannels);
                        byte[] setup = NeoForgeHandshakeCodec.encodeLobbySetup(
                                new Registry(setupProtocols), currentConfig.limits());
                        byte[] registration =
                                NeoForgeHandshakeCodec.encodeDinnerboneChannels(
                                        Set.copyOf(advertisedBuiltIns),
                                        currentConfig.limits().maximumSetupBytes());
                        VelocityPluginMessageBatchSender.Batch.Builder prefix =
                                VelocityPluginMessageBatchSender.Batch.builder(
                                        "lobby-config-prefix-"
                                                + session.clientRegistryFingerprint);
                        prefix.addOwned(NEOFORGE_NETWORK.getId(), setup);
                        prefix.addOwned(MINECRAFT_REGISTER.getId(), registration);
                        if (reviewedConfigCatalog == null) {
                            for (String fileName : session.transientServerConfigs) {
                                byte[] configPayload =
                                        NeoForgeHandshakeCodec.encodeConfigFilePayload(
                                                fileName,
                                                new byte[0],
                                                currentConfig.limits().maximumSetupBytes());
                                prefix.addOwned(
                                        NEOFORGE_CONFIG_FILE.getId(), configPayload);
                            }
                        } else {
                            for (Atm10Normal81ServerConfigCatalog.Entry entry
                                    : reviewedConfigCatalog.entries()) {
                                prefix.addOwned(
                                        NEOFORGE_CONFIG_FILE.getId(),
                                        entry.encodedPayload());
                            }
                        }
                        if (optimization != null) {
                            byte[] configPayload =
                                    NeoForgeHandshakeCodec.encodeConfigFilePayload(
                                            Ae2JeiSessionOptimization.CONFIG_FILE_NAME,
                                            optimization.contents(),
                                            currentConfig.limits().maximumSetupBytes());
                            prefix.addOwned(NEOFORGE_CONFIG_FILE.getId(), configPayload);
                        }
                        if (session.silentGearProfile != null) {
                            appendReviewedConfigurationBootstraps(prefix, session);
                        }
                        return prefix.build();
                    });
            VelocityPluginMessageBatchSender.Batch prefixBatch = prefixLookup.batch();
            session.configurationPrefixCacheHit = prefixLookup.hit();
            if (optimization != null) {
                session.ae2JeiSessionOptimizationBytesSent = optimization.contentBytes();
                logger.info(
                        "Sent transient AE2-JEI session optimization to {}: config={}, "
                                + "bytes={}, sha256={}, persistence=memory-only",
                        player.getUsername(),
                        Ae2JeiSessionOptimization.CONFIG_FILE_NAME,
                        optimization.contentBytes(),
                        optimization.contentSha256());
            }
            session.configurationPrefixPacketsSent = prefixBatch.packetCount();
            session.configurationPrefixBytesSent = prefixBatch.totalBytes();
            long lobbyGeneration = session.lobbyCycleGeneration;
            CompletableFuture<Void> prefixWrite = sender.send(player, prefixBatch);
            logger.info(
                    "Sent ordered lobby CONFIGURATION prefix to {}: packets={}, bytes={}, "
                            + "transientConfigs={}, reviewedConfigCatalog={}, "
                            + "reviewedConfigPayloadBytes={}, reviewedBootstraps={}, "
                            + "sequenceSha256={}, "
                            + "encodingCache={}, cacheGeneration={}, cacheSize={}, "
                            + "cacheHits={}, cacheMisses={}, cacheEvictions={}, flushes=1",
                    player.getUsername(),
                    prefixBatch.packetCount(),
                    prefixBatch.totalBytes(),
                    session.transientServerConfigs.size()
                            + (session.ae2JeiSessionOptimizationSelected ? 1 : 0),
                    session.reviewedTransientConfigCatalogId,
                    session.reviewedTransientConfigCatalogEncodedBytes,
                    session.silentGearProfile == null
                            ? 0
                            : ReviewedConfigurationProfile.bootstraps(
                                    session.silentGearProfile).size(),
                    prefixBatch.sequenceSha256(),
                    prefixLookup.hit() ? "hit" : "miss",
                    prefixLookup.snapshot().generation(),
                    prefixLookup.snapshot().size(),
                    prefixLookup.snapshot().hits(),
                    prefixLookup.snapshot().misses(),
                    prefixLookup.snapshot().evictions());
            prefixWrite.whenComplete((ignored, failure) -> {
                synchronized (session) {
                    if (sessions.get(player.getUniqueId()) != session
                            || session.state == State.FAILED
                            || session.state == State.OUTSIDE
                            || session.lobbyCycleGeneration != lobbyGeneration
                            || !session.lobbyHandshakeStarted) {
                        return;
                    }
                    if (failure != null) {
                        Throwable cause = unwrapCompletionFailure(failure);
                        consoleLogger.error(
                                "Lobby CONFIGURATION prefix batch failed for {} [{}]",
                                player.getUsername(), player.getUniqueId(), cause);
                        reject(player, session,
                                "lobby configuration prefix write failed: "
                                        + stableFailureMessage(cause));
                        return;
                    }
                    // A selected packet list is not delivery evidence. This flag is raised only
                    // by completion of the one ordered CONFIGURATION-prefix network write and it
                    // gates the remainder of the handshake, eliminating the previous lifecycle
                    // race at recipe release time.
                    session.configurationPrefixWriteComplete = true;
                    logger.info(
                            "Completed ordered lobby CONFIGURATION prefix write for {}: "
                                    + "writePromise=SUCCESS, serverConfigCatalog={}, configs={}, "
                                    + "encodedConfigBytes={}, nameSequenceSha256={}, "
                                    + "payloadSequenceSha256={}, admissionDecision=UNCHANGED_CARDINAL",
                            player.getUsername(),
                            session.reviewedTransientConfigCatalogId,
                            session.transientServerConfigs.size(),
                            session.reviewedTransientConfigCatalogEncodedBytes,
                            shortFingerprint(
                                    session.reviewedTransientConfigNameSequenceSha256),
                            shortFingerprint(
                                    session.reviewedTransientConfigPayloadSequenceSha256));
                    if (session.silentGearProfile != null) {
                        sendFrozenRegistryTransaction(player, session);
                    } else {
                        awaitPaperRegistryPrefix(player, session);
                    }
                }
            });
        } catch (ProtocolViolationException | IllegalArgumentException exception) {
            reject(player, session, "could not construct lobby handshake: " + exception.getMessage());
        } catch (IllegalStateException exception) {
            reject(player, session, "could not prepare lobby handshake: " + exception.getMessage());
        }
    }

    /** Records proof only from the guard lease's downstream-promise completion. */
    private void recordRegistryReplacementCompletion(
            Player player,
            BridgeSession session,
            RegistryShimPacket replacementPacket,
            VelocityRegistryReplacementGuard.Lease lease,
            long generation,
            RegistryShimReceipt receipt,
            Throwable failure) {
        synchronized (session) {
            if (sessions.get(player.getUniqueId()) != session
                    || session.lobbyCycleGeneration != generation
                    || session.registryReplacementLease != lease
                    || session.state == State.OUTSIDE
                    || session.state == State.FAILED) {
                return;
            }
            if (failure != null || receipt == null) {
                removeRegistryReplacementProof(session);
                clearRegistryReplacementAttachment(session);
                session.registryReplacementStatus = "WRITE_FAILED_PAPER_PASSTHROUGH";
                warnRegistryReplacementPassthrough(
                        player,
                        session,
                        session.registryReplacementStatus,
                        failure == null
                                ? new IllegalStateException(
                                        "registry replacement completion returned no receipt")
                                : unwrapCompletionFailure(failure));
                return;
            }

            RegistryShimReceipt expected = RegistryShimReceipt.from(replacementPacket);
            if (!expected.equals(receipt)) {
                removeRegistryReplacementProof(session);
                clearRegistryReplacementAttachment(session);
                session.registryReplacementStatus = "RECEIPT_MISMATCH_WITHHELD";
                warnRegistryReplacementPassthrough(
                        player,
                        session,
                        session.registryReplacementStatus,
                        new IllegalStateException(
                                "registry replacement completion receipt was not exact"));
                return;
            }
            boolean conflictingReceipt = session.registryShimReceipts.stream()
                    .anyMatch(existing -> (existing.registryId().equals(receipt.registryId())
                                    || existing.shimId().equals(receipt.shimId()))
                            && !existing.equals(receipt));
            if (conflictingReceipt) {
                removeRegistryReplacementProof(session);
                clearRegistryReplacementAttachment(session);
                session.registryReplacementStatus = "RECEIPT_CONFLICT_WITHHELD";
                warnRegistryReplacementPassthrough(
                        player,
                        session,
                        session.registryReplacementStatus,
                        new IllegalStateException(
                                "registry replacement receipt conflicts with completed proof"));
                return;
            }

            LinkedHashSet<RegistryShimReceipt> completed =
                    new LinkedHashSet<>(session.registryShimReceipts);
            completed.add(receipt);
            session.registryShimReceipts = Set.copyOf(completed);
            RegistryReplacementGuardHandler.TransformMode completedMode =
                    session.registryReplacementMode;
            String completedEvidence = session.registryReplacementEvidenceId;
            session.registryReplacementStatus = "WRITE_COMPLETED_EXACT_RECEIPT";
            boolean exactFullRegistryProof = completedMode
                    == RegistryReplacementGuardHandler.TransformMode.EXACT_REPLACEMENT
                    && Atm10Normal81Contract.ID.equals(completedEvidence);
            logger.info(
                    "Completed Paper enchantment registry transform for {}: "
                            + "mode={}, evidence={}, registry={}, shim={}, entries={}, bytes={}, "
                            + "sha256={}, receiptSource=DOWNSTREAM_CHANNEL_PROMISE_SUCCESS, "
                            + "recipeLifecycleProof={}",
                    player.getUsername(),
                    completedMode == null ? "unknown" : completedMode,
                    completedEvidence,
                    receipt.registryId(),
                    receipt.shimId(),
                    receipt.entryCount(),
                    receipt.packetBytes(),
                    receipt.sha256(),
                    exactFullRegistryProof ? "AVAILABLE" : "WITHHELD_NARROW_MERGE");
        }
    }

    private void warnRegistryReplacementPassthrough(
            Player player,
            BridgeSession session,
            String status,
            Throwable failure) {
        consoleLogger.warn(
                "compatibilityCapability=ENCHANTMENT_REGISTRY_TRANSFORM_UNAVAILABLE, "
                        + "registryReplacementStatus={}, player={}, mode={}, evidence={}, "
                        + "clientContract={}, fallback=PAPER_REGISTRY_PASSTHROUGH, "
                        + "recipeLifecycle=WITHHOLD, admissionDecision=UNCHANGED_CARDINAL, "
                        + "aclMutation=false, pluginInitiatedDisconnect=false, "
                        + "routeMutation=false, failure={}",
                status,
                player.getUsername(),
                session.registryReplacementMode == null
                        ? "unselected"
                        : session.registryReplacementMode,
                session.registryReplacementEvidenceId,
                shortFingerprint(session.fullClientContractSha256),
                failure == null ? "none" : stableFailureMessage(failure));
    }

    private static void appendReviewedConfigurationBootstraps(
            VelocityPluginMessageBatchSender.Batch.Builder prefix,
            BridgeSession session) {
        List<ReviewedConfigurationProfile.Bootstrap> bootstraps =
                ReviewedConfigurationProfile.bootstraps(session.silentGearProfile);
        for (ReviewedConfigurationProfile.Bootstrap bootstrap : bootstraps) {
            if (bootstrap.acknowledgementRequired()) {
                throw new IllegalArgumentException(
                        "reviewed CONFIGURATION bootstrap unexpectedly requires an ACK: "
                                + bootstrap.channelId());
            }
            byte[] payload = bootstrap.bytes();
            prefix.addOwned(bootstrap.channelId(), payload);
        }
    }

    private void sendFrozenRegistryTransaction(
            Player player, BridgeSession session) {
        BridgeConfig currentConfig = activeConfig();
        if (currentConfig == null) {
            reject(player, session, "bridge configuration became inactive");
            return;
        }
        NeoForgeFrozenRegistryProfile profile = session.silentGearProfile.frozenRegistries();
        String profileId = session.silentGearProfile.profileId();
        VelocityPluginMessageBatchSender sender = pluginMessageBatchSender;
        VelocityPluginMessageBatchSender.Batch wirePlan =
                frozenRegistryWirePlans.get(profileId);
        if (sender == null || wirePlan == null) {
            reject(player, session,
                    "immutable frozen-registry wire plan is unavailable");
            return;
        }
        if (wirePlan.packetCount() != profile.registryCount() + 2
                || wirePlan.totalBytes() != profile.totalTransactionBytes()
                || !wirePlan.sequenceSha256().equals(profile.sequenceSha256())) {
            reject(player, session,
                    "immutable frozen-registry wire plan no longer matches its exact profile");
            return;
        }
        session.frozenRegistryAcks = new FrozenRegistryAckTransaction();
        session.state = State.LOBBY_SYNCING_FROZEN_REGISTRIES;
        session.frozenRegistryGeneration++;
        long generation = session.frozenRegistryGeneration;
        long lobbyGeneration = session.lobbyCycleGeneration;
        session.frozenRegistryCountSent = profile.registryCount();
        session.frozenRegistryEntriesSent = profile.totalEntries();
        session.frozenRegistryBytesSent = profile.totalTransactionBytes();
        session.frozenRegistrySentNanos = 0L;
        CompletableFuture<Void> write = sender.send(player, wirePlan);

        FrozenRegistryAckWatchdog.watchWriteThenAck(
                write,
                FROZEN_REGISTRY_WRITE_TIMEOUT_MILLIS,
                currentConfig.frozenRegistryAckTimeoutMillis(),
                (delayMillis, task) -> CompletableFuture.delayedExecutor(
                        delayMillis, TimeUnit.MILLISECONDS).execute(task),
                () -> {
                    synchronized (session) {
                        if (session.state != State.LOBBY_SYNCING_FROZEN_REGISTRIES
                                || session.frozenRegistryGeneration != generation
                                || session.lobbyCycleGeneration != lobbyGeneration) {
                            return;
                        }
                        session.frozenRegistrySentNanos = System.nanoTime();
                        logger.info(
                                "Sent exact NeoForge frozen-registry transaction to {}: profile={}, "
                                        + "registries={}, entries={}, packets={}, bytes={}, "
                                        + "sequenceSha256={}, wirePlanCache=hit, flushes=1; "
                                        + "waiting for client ACK",
                                player.getUsername(),
                                profileId,
                                profile.registryCount(),
                                profile.totalEntries(),
                                wirePlan.packetCount(),
                                profile.totalTransactionBytes(),
                                profile.sequenceSha256());
                    }
                },
                failure -> {
                    synchronized (session) {
                        if (session.state != State.LOBBY_SYNCING_FROZEN_REGISTRIES
                                || session.frozenRegistryGeneration != generation
                                || session.lobbyCycleGeneration != lobbyGeneration) {
                            return;
                        }
                        Throwable cause = unwrapCompletionFailure(failure);
                        consoleLogger.error(
                                "Frozen registry single-flush transaction failed for {} [{}]",
                                player.getUsername(), player.getUniqueId(), cause);
                        reject(player, session,
                                "frozen registry transaction failed before ACK wait: "
                                        + stableFailureMessage(cause));
                    }
                },
                () -> {
                    synchronized (session) {
                        if (session.state != State.LOBBY_SYNCING_FROZEN_REGISTRIES
                                || session.frozenRegistryGeneration != generation
                                || session.lobbyCycleGeneration != lobbyGeneration
                                || session.frozenRegistryAcks == null
                                || session.frozenRegistryAcks.complete()) {
                            return;
                        }
                        reject(
                                player,
                                session,
                                "NeoForge frozen registry ACK timed out: elapsedMs="
                                        + elapsedMillis(
                                                session.frozenRegistrySentNanos,
                                                System.nanoTime())
                                        + ", timeoutMs="
                                        + currentConfig.frozenRegistryAckTimeoutMillis()
                                        + ", profile="
                                        + profileId
                                        + ", prefixPackets="
                                        + session.configurationPrefixPacketsSent
                                        + ", transientConfigs="
                                        + session.transientServerConfigs.size()
                                        + ", transientConfigSource="
                                        + session.transientServerConfigSource
                                        + ", frozenRegistryPackets="
                                        + wirePlan.packetCount()
                                        + ", frozenRegistries="
                                        + session.frozenRegistryCountSent
                                        + ", frozenRegistryEntries="
                                        + session.frozenRegistryEntriesSent
                                        + ", frozenRegistryBytes="
                                        + session.frozenRegistryBytesSent);
                    }
                });
    }

    private void receiveFrozenRegistryAcknowledgement(
            Player player,
            BridgeSession session,
            byte[] payload) {
        synchronized (session) {
            if (session.state != State.LOBBY_SYNCING_FROZEN_REGISTRIES
                    || session.silentGearProfile == null
                    || session.frozenRegistryAcks == null) {
                reject(player, session, "unexpected or out-of-state frozen registry ACK");
                return;
            }
            try {
                session.frozenRegistryAcks.accept(
                        NeoForgeFrozenRegistryProfile.COMPLETED_CHANNEL, payload);
            } catch (IllegalArgumentException exception) {
                reject(player, session, exception.getMessage());
                return;
            }
            logger.info(
                    "Accepted NeoForge frozen-registry ACK from {}: profile={}, "
                            + "registries={}, entries={}, bytes={}, sequenceSha256={}, "
                            + "ackRoundTripMs={}",
                    player.getUsername(),
                    session.silentGearProfile.profileId(),
                    session.frozenRegistryCountSent,
                    session.frozenRegistryEntriesSent,
                    session.frozenRegistryBytesSent,
                    session.silentGearProfile.frozenRegistries().sequenceSha256(),
                    elapsedMillis(session.frozenRegistrySentNanos, System.nanoTime()));
            awaitPaperRegistryPrefix(player, session);
        }
    }

    private void awaitPaperRegistryPrefix(Player player, BridgeSession session) {
        session.state = State.LOBBY_WAITING_FOR_REGISTRY_TAIL;
        logger.info(
                "Released lobby CONFIGURATION prefix for {}: profile={}, "
                        + "next=Paper-vanilla-registries-then-reviewed-modded-tail",
                player.getUsername(),
                session.silentGearProfile == null
                        ? "legacy-shims"
                        : session.silentGearProfile.profileId());
        if (session.gate != null) {
            session.gate.complete(null);
        }
    }

    private void injectDynamicRegistryShims(
            Player player, BridgeSession session, BridgeConfig currentConfig) {
        DynamicRegistryInjectionFence<BridgeSession, SilentGearEmbeddedProfile> fence =
                new DynamicRegistryInjectionFence<>(
                        player.getUniqueId(),
                        session,
                        session.lobbyCycleGeneration,
                        session.silentGearProfile);
        SilentGearEmbeddedProfile selectedProfile = fence.profileIdentity();
        List<RegistryShimPacket> selectedPackets = RegistryShimCatalog.selectForProfile(
                player.getProtocolVersion().getProtocol(),
                selectedProfile,
                registryShimPackets,
                session.advertisedNamespaces,
                structuralClientContract(session));
        List<RegistryShimPacket> packets = selectedPackets.stream()
                .filter(packet -> !packet.shimId().equals(
                        RegistryShimCatalog.FULL_ENCHANTMENT_ATM10_8_1))
                .toList();
        if (packets.size() != selectedPackets.size()) {
            consoleLogger.warn(
                    "Withheld ATM10 8.1 full enchantment packet from dynamic-registry tail for {}: "
                            + "deliveryMode=PAPER_REGISTRY_REPLACEMENT_ONLY, "
                            + "admissionDecision=UNCHANGED_CARDINAL",
                    player.getUsername());
        }
        EmbeddedRegistryTagsProfile tagProfile = RegistryShimCatalog.selectTagsForTransaction(
                player.getProtocolVersion().getProtocol(),
                selectedProfile,
                packets,
                structuralClientContract(session));
        String transactionProfileId = selectedProfile != null
                ? selectedProfile.profileId()
                : tagProfile.isEmpty()
                        ? "legacy-shims"
                        : Atm10Normal81Contract.ID + "-neovitae-sentient-closure";
        if (packets.isEmpty() && tagProfile.isEmpty()) {
            finishNeoForgeLobbyHandshake(player, session, currentConfig, packets);
            return;
        }
        VelocityRegistryInjector injector = registryInjector;
        if (!packets.isEmpty() && injector == null) {
            reject(player, session, "dynamic registry shim injector is unavailable");
            return;
        }
        VelocityTagsInjector tagsInjector = registryTagsInjector;
        if (!tagProfile.isEmpty() && tagsInjector == null) {
            reject(player, session, "dynamic registry tags injector is unavailable");
            return;
        }

        session.state = State.LOBBY_INJECTING_REGISTRIES;
        logger.info(
                "Starting exact dynamic-registry injection for {}: profile={}, packets={}, "
                        + "entries={}, bytes={}, tags={}, tagMembers={}, tagBytes={}, timeoutMs={}, "
                        + "registryFlushes={}, tagFlushes={}, "
                        + "lifecycle=post-Paper-registry-tail",
                player.getUsername(),
                transactionProfileId,
                packets.size(),
                packets.stream().mapToInt(RegistryShimPacket::entryCount).sum(),
                packets.stream().mapToInt(RegistryShimPacket::packetBytes).sum(),
                tagProfile.tags().size(),
                tagProfile.totalMembers(),
                tagProfile.packetBytes(),
                DYNAMIC_REGISTRY_INJECTION_TIMEOUT_MILLIS,
                packets.isEmpty() ? 0 : 1,
                tagProfile.isEmpty() ? 0 : 1);
        CompletableFuture<Void> injection;
        if (packets.isEmpty()) {
            injection = CompletableFuture.completedFuture(null);
        } else if (!dynamicRegistryInjectionIsCurrent(player, session, fence)) {
            injection = CompletableFuture.failedFuture(new IllegalStateException(
                    "dynamic registry injection attempt became stale"));
        } else {
            injection = injector.injectBatch(player, packets).thenRun(() -> logger.info(
                    "Completed single-flush dynamic registry batch for {}: profile={}, "
                            + "packets={}, entries={}, bytes={}, flushes=1",
                    player.getUsername(),
                    transactionProfileId,
                    packets.size(),
                    packets.stream().mapToInt(RegistryShimPacket::entryCount).sum(),
                    packets.stream().mapToInt(RegistryShimPacket::packetBytes).sum()));
        }
        if (!tagProfile.isEmpty()) {
            injection = injection.thenCompose(ignored -> {
                synchronized (session) {
                    if (!dynamicRegistryInjectionIsCurrent(player, session, fence)) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "dynamic registry tag injection attempt became stale"));
                    }
                    logger.info(
                            "Writing dynamic registry tags for {}: profile={}, registry={}, "
                                    + "tags={}, members={}, bytes={}, sha256={}",
                            player.getUsername(),
                            transactionProfileId,
                            tagProfile.registryId(),
                            tagProfile.tags().size(),
                            tagProfile.totalMembers(),
                            tagProfile.packetBytes(),
                            tagProfile.sha256());
                    return tagsInjector.inject(player, tagProfile).thenRun(() -> logger.info(
                            "Completed dynamic registry tags write for {}: registry={}, tags={}",
                            player.getUsername(),
                            tagProfile.registryId(),
                            tagProfile.tags().size()));
                }
            });
        }
        CompletableFuture<Void> injectionResult = injection;
        CompletableFuture.delayedExecutor(
                DYNAMIC_REGISTRY_INJECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).execute(() -> {
                    synchronized (session) {
                        if (injectionResult.isDone()
                                || !dynamicRegistryInjectionIsCurrent(player, session, fence)) {
                            return;
                        }
                        reject(player, session,
                                "dynamic registry shim injection timed out after "
                                        + DYNAMIC_REGISTRY_INJECTION_TIMEOUT_MILLIS + " ms");
                    }
                });
        injectionResult.whenComplete((ignored, failure) -> {
            synchronized (session) {
                if (!dynamicRegistryInjectionIsCurrent(player, session, fence)) {
                    return;
                }
                if (failure != null) {
                    Throwable cause = unwrapCompletionFailure(failure);
                    consoleLogger.error(
                            "Dynamic registry shim injection failed for {} [{}]",
                            player.getUsername(), player.getUniqueId(), cause);
                    reject(player, session,
                            "dynamic registry shim injection failed: "
                                    + stableFailureMessage(cause));
                    return;
                }
                finishNeoForgeLobbyHandshake(player, session, currentConfig, packets);
            }
        });
    }

    private boolean dynamicRegistryInjectionIsCurrent(
            Player player,
            BridgeSession session,
            DynamicRegistryInjectionFence<BridgeSession, SilentGearEmbeddedProfile> fence) {
        return fence.permits(
                player.getUniqueId(),
                sessions.get(player.getUniqueId()),
                session.lobbyCycleGeneration,
                session.silentGearProfile,
                session.state == State.LOBBY_INJECTING_REGISTRIES);
    }

    private static List<Channel> configurationChannels(BridgeSession session) {
        return session.silentGearProfile == null
                ? BASE_LOBBY_CONFIGURATION_CHANNELS
                : ReviewedConfigurationProfile.channels(session.silentGearProfile);
    }

    private static Set<String> advertisedLobbyBuiltins(BridgeSession session) {
        if (session.silentGearProfile != null) {
            LinkedHashSet<String> channels = new LinkedHashSet<>(
                    NEOFORGE_BUILTIN_CHANNEL_IDS);
            channels.addAll(ReviewedConfigurationProfile.advertisedChannelIds(
                    session.silentGearProfile));
            return Set.copyOf(channels);
        }
        LinkedHashSet<String> channels = new LinkedHashSet<>(NEOFORGE_BUILTIN_CHANNEL_IDS);
        channels.removeAll(FROZEN_REGISTRY_CHANNEL_IDS);
        return Set.copyOf(channels);
    }

    private void finishNeoForgeLobbyHandshake(
            Player player,
            BridgeSession session,
            BridgeConfig currentConfig,
            List<RegistryShimPacket> packets) {
        Set<RegistryShimReceipt> completedRegistryShimReceipts;
        try {
            completedRegistryShimReceipts = packets.stream()
                    .map(RegistryShimReceipt::from)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (completedRegistryShimReceipts.size() != packets.size()) {
                throw new IllegalArgumentException(
                        "completed registry batch contains duplicate receipt identities");
            }
        } catch (IllegalArgumentException exception) {
            reject(player, session,
                    "completed registry shim receipt validation failed: "
                            + exception.getMessage());
            return;
        }
        // Reaching this point proves the registry batch (and any exact tags chained after it)
        // completed. Selection and byte counters alone never create a release receipt.
        LinkedHashSet<RegistryShimReceipt> allCompletedRegistryReceipts =
                new LinkedHashSet<>(session.registryShimReceipts);
        boolean receiptConflict = completedRegistryShimReceipts.stream().anyMatch(completed ->
                allCompletedRegistryReceipts.stream().anyMatch(existing ->
                        (existing.registryId().equals(completed.registryId())
                                        || existing.shimId().equals(completed.shimId()))
                                && !existing.equals(completed)));
        if (receiptConflict) {
            session.registryReplacementStatus = "TAIL_RECEIPT_CONFLICT_WITHHELD";
            consoleLogger.warn(
                    "Withheld registry receipt proof for {} because replacement and tail "
                            + "receipts conflict; admission remains active, "
                            + "recipeLifecycle=WITHHOLD, pluginInitiatedDisconnect=false",
                    player.getUsername());
            allCompletedRegistryReceipts.clear();
        } else {
            allCompletedRegistryReceipts.addAll(completedRegistryShimReceipts);
        }
        session.registryShimReceipts = Set.copyOf(allCompletedRegistryReceipts);
        session.registryShimPacketsInjected = packets.size();
        session.registryShimBytesInjected = packets.stream()
                .mapToInt(RegistryShimPacket::packetBytes)
                .sum();
        session.state = State.LOBBY_READY;
        session.configurationReadyNanos = System.nanoTime();
        logger.info(
                "Accepted NeoForge Paper-lobby handshake for {}: queryBytes={}, "
                        + "configurationChannels={}, frozenRegistries={}, "
                        + "frozenRegistryEntries={}, frozenRegistryBytes={}, "
                        + "configurationPrefixPackets={}, configurationPrefixBytes={}, "
                        + "configurationPrefixWriteComplete={}, "
                        + "immutablePlanCache={}, prefixEncodingCache={}, "
                        + "ae2JeiSessionOptimization={}, ae2JeiConfigBytes={}, "
                        + "playSinkChannels={}, registrarBoundedPlaySinkChannels={}, "
                        + "externallyOwnedPlayChannels={}, reviewedClientVariant={}, "
                        + "clientboundPlayBootstraps={}, registryMode={}, "
                        + "registryShims={}, registryShimPackets={}, registryShimEntries={}, "
                        + "registryShimBytes={}, registryShimReceipts={}, "
                        + "registryReplacementStatus={}, "
                        + "transientConfigs={} (baseline={}, derived={}, omittedDerived={}), "
                        + "transientConfigSource={}, "
                        + "reviewedConfigCatalog={} (added={}, retained={}, discardedBase={}, "
                        + "encodedBytes={}, nameSequenceSha256={}, payloadSequenceSha256={}), "
                        + "baselineConfigNames={}, queryToConfigurationReadyMs={}",
                player.getUsername(), session.queryBytes,
                configurationChannels(session).size(),
                session.frozenRegistryCountSent,
                session.frozenRegistryEntriesSent,
                session.frozenRegistryBytesSent,
                session.configurationPrefixPacketsSent,
                session.configurationPrefixBytesSent,
                session.configurationPrefixWriteComplete,
                session.negotiationPlanCacheHit ? "hit" : "miss",
                session.configurationPrefixCacheHit ? "hit" : "miss",
                session.ae2JeiSessionOptimizationSelected,
                session.ae2JeiSessionOptimizationBytesSent,
                session.playSinkChannels.size(),
                registrarBoundedPlaySinkChannelCount(session),
                session.externallyOwnedPlayChannels.size(),
                session.reviewedClientContractVariant,
                session.clientboundPlayBootstrapChannelIds,
                session.playSinkPlanMode,
                packets.stream().map(RegistryShimPacket::shimId).distinct().toList(),
                session.registryShimPacketsInjected,
                packets.stream().mapToInt(RegistryShimPacket::entryCount).sum(),
                session.registryShimBytesInjected,
                session.registryShimReceipts,
                session.registryReplacementStatus,
                session.transientServerConfigs.size(),
                currentConfig.transientServerConfigs().size(),
                session.derivedTransientServerConfigs,
                session.omittedDerivedTransientServerConfigs,
                session.transientServerConfigSource,
                session.reviewedTransientConfigCatalogId,
                session.addedReviewedTransientConfigs,
                session.retainedReviewedTransientConfigs,
                session.discardedBaseTransientConfigs,
                session.reviewedTransientConfigCatalogEncodedBytes,
                shortFingerprint(session.reviewedTransientConfigNameSequenceSha256),
                shortFingerprint(session.reviewedTransientConfigPayloadSequenceSha256),
                currentConfig.transientServerConfigs(),
                elapsedMillis(session.queryReceivedNanos, session.configurationReadyNanos));
        if (session.registryTailGate != null) {
            session.registryTailGate.complete(null);
        }
    }

    private static List<Channel> negotiatedPlayChannels(BridgeSession session) {
        return negotiatedPlayChannels(
                session.playSinkChannels,
                session.clientboundPlayBootstrapChannels,
                session.externallyOwnedPlayChannels);
    }

    static List<Channel> negotiatedPlayChannels(
            List<Channel> playSinks,
            List<Channel> clientboundBootstraps,
            List<Channel> externallyOwnedChannels) {
        LinkedHashMap<String, Channel> channels = new LinkedHashMap<>();
        playSinks.forEach(channel -> channels.put(channel.id(), channel));
        clientboundBootstraps.forEach(
                channel -> channels.putIfAbsent(channel.id(), channel));
        externallyOwnedChannels.forEach(
                channel -> channels.putIfAbsent(channel.id(), channel));
        return List.copyOf(channels.values());
    }

    static List<String> configDerivationNamespaces(
            List<String> advertisedNamespaces,
            Set<String> externallyOwnedPlayChannelIds) {
        LinkedHashSet<String> ownershipFence = new LinkedHashSet<>(
                ReviewedSimpleVoiceChatExtension.externallyOwnedChannelIds());
        ownershipFence.addAll(externallyOwnedPlayChannelIds);
        Set<String> externallyOwnedNamespaces = ownershipFence.stream()
                .map(Atm10LobbyVelocityPlugin::channelNamespace)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return advertisedNamespaces.stream()
                .filter(namespace -> !externallyOwnedNamespaces.contains(namespace))
                .toList();
    }

    private static String channelNamespace(String channelId) {
        int separator = channelId.indexOf(':');
        return separator < 0 ? channelId : channelId.substring(0, separator);
    }

    private void sendLobbyReady(
            Player player,
            ServerConnection lobby,
            BridgeSession session) {
        LobbyReady ready;
        synchronized (session) {
            if (session.state != State.LOBBY_PLAY
                    || !session.lobbyClientInitializationComplete
                    || session.readinessSessionId == 0L) {
                return;
            }
            ready = new LobbyReady(session.readinessSessionId);
        }
        if (!lobby.sendPluginMessage(BRIDGE_CONTROL, LobbyReadyCodec.encode(ready))) {
            reject(player, session, "Velocity could not signal lobby readiness to Paper");
        }
    }

    private void registerPinnedPlaySinkChannels(BridgeConfig currentConfig) {
        List<Channel> pinned = currentConfig.pinnedPlaySinkChannels().stream()
                .map(channel -> new Channel(
                        channel.id(), channel.version(), Flow.SERVERBOUND, false))
                .toList();
        try {
            PlaySinkReservation reservation = reservePlaySinkChannels(pinned, currentConfig);
            if (reservation.channels().size() != pinned.size()
                    || reservation.omittedByCapacity() != 0) {
                throw new IllegalArgumentException(
                        "pinned PLAY channels exceed the configured global sink budget");
            }
        } catch (ProtocolViolationException exception) {
            throw new IllegalArgumentException(
                    "invalid pinned PLAY sink channel: " + exception.getMessage(), exception);
        }
    }

    /**
     * Registers a bounded process-lifetime channel set.
     *
     * <p>Velocity's registrar is global and has no ownership query. Channels are intentionally
     * never unregistered: removing a duplicate id could silently break another plugin. The count
     * and encoded-byte budgets bound this process-lifetime cache and keep the aggregate channel
     * advertisement comfortably below Paper's default 128-channel ceiling.</p>
     */
    private PlaySinkReservation reservePlaySinkChannels(
            List<Channel> requested,
            BridgeConfig currentConfig) throws ProtocolViolationException {
        LinkedHashMap<String, Channel> unique = new LinkedHashMap<>();
        for (Channel channel : requested) {
            ProxyPluginMessageOwnership.requireProtocolObeliskSinkOwnership(channel.id());
            ReviewedSimpleVoiceChatExtension.requireProtocolObeliskSinkOwnership(channel.id());
            if (!NEOFORGE_BUILTIN_CHANNEL_IDS.contains(channel.id())) {
                unique.putIfAbsent(channel.id(), channel);
            }
        }

        synchronized (playSinkRegistryLock) {
            List<Channel> accepted = new ArrayList<>();
            List<MinecraftChannelIdentifier> newIdentifiers = new ArrayList<>();
            int stagedBytes = 0;
            int omitted = 0;

            for (Channel channel : unique.values()) {
                if (channel.id().equals(SilentGearProtocol.ACK)) {
                    accepted.add(channel);
                    continue;
                }
                if (globalPlaySinkChannels.containsKey(channel.id())) {
                    accepted.add(channel);
                    continue;
                }

                int encodedBytes = channel.id().getBytes(StandardCharsets.UTF_8).length + 1;
                boolean countFits = globalPlaySinkChannels.size() + newIdentifiers.size()
                        < currentConfig.maximumGlobalPlaySinkChannels();
                boolean bytesFit = (long) globalPlaySinkChannelBytes
                        + stagedBytes
                        + encodedBytes
                        <= currentConfig.maximumGlobalPlaySinkChannelBytes();
                if (!countFits || !bytesFit) {
                    omitted++;
                    continue;
                }

                final MinecraftChannelIdentifier identifier;
                try {
                    identifier = MinecraftChannelIdentifier.from(channel.id());
                } catch (IllegalArgumentException exception) {
                    throw new ProtocolViolationException(
                            "PLAY sink is not a Velocity channel identifier: " + channel.id());
                }
                newIdentifiers.add(identifier);
                stagedBytes += encodedBytes;
                accepted.add(channel);
            }

            if (!newIdentifiers.isEmpty()) {
                proxy.getChannelRegistrar().register(
                        newIdentifiers.toArray(ChannelIdentifier[]::new));
                for (int index = 0; index < newIdentifiers.size(); index++) {
                    MinecraftChannelIdentifier identifier = newIdentifiers.get(index);
                    globalPlaySinkChannels.put(identifier.getId(), identifier);
                }
                globalPlaySinkChannelBytes += stagedBytes;
            }

            Set<String> ids = accepted.stream()
                    .map(Channel::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new PlaySinkReservation(accepted, ids, omitted);
        }
    }

    private void consumeLobbyPlayPayload(
            Player player,
            BridgeSession session,
            String channelId,
            byte[] payload,
            BridgeConfig currentConfig) {
        boolean completedSilentGearTransaction = false;
        synchronized (session) {
            if (session.state != State.LOBBY_PLAY) {
                reject(player, session, "PLAY sink payload arrived outside lobby PLAY");
                return;
            }
            if (!session.playSinkChannelIds.contains(channelId)) {
                reject(player, session, "unnegotiated lobby PLAY sink channel " + channelId);
                return;
            }
            if (payload.length > currentConfig.maximumLobbyPlayPayloadBytes()) {
                reject(player, session,
                        "lobby PLAY payload exceeds per-message byte budget on " + channelId);
                return;
            }
            if (!ReviewedAtmCompatibility.matchesReviewedSessionPayload(
                    channelId, payload, player.getUniqueId())) {
                reject(player, session,
                        "lobby PLAY payload violates reviewed codec contract on " + channelId);
                return;
            }
            if (session.playRateLimiter == null) {
                reject(player, session, "lobby PLAY rate limiter was not initialized");
                return;
            }
            LobbyPlayRateLimiter.Result rateResult = session.playRateLimiter.tryAcquire(
                    payload.length, System.nanoTime());
            if (rateResult == LobbyPlayRateLimiter.Result.PACKET_RATE_EXHAUSTED) {
                reject(player, session,
                        "lobby PLAY packet-rate budget exhausted (burst="
                                + session.playRateLimiter.packetCapacity()
                                + ", refillMillis="
                                + session.playRateLimiter.refillPeriodMillis() + ')');
                return;
            }
            if (rateResult == LobbyPlayRateLimiter.Result.BYTE_RATE_EXHAUSTED) {
                reject(player, session,
                        "lobby PLAY byte-rate budget exhausted (burstBytes="
                                + session.playRateLimiter.byteCapacity()
                                + ", refillMillis="
                                + session.playRateLimiter.refillPeriodMillis() + ')');
                return;
            }
            if (channelId.equals(SilentGearProtocol.ACK)) {
                if (session.silentGearProfile == null || session.silentGearAcks == null) {
                    reject(player, session, "unexpected Silent Gear lobby ACK without profile");
                    return;
                }
                final SilentGearAckTransaction.Acknowledgement acknowledgement;
                try {
                    acknowledgement = session.silentGearAcks.accept(channelId, payload);
                } catch (IllegalArgumentException exception) {
                    reject(player, session, exception.getMessage());
                    return;
                }
                long ackRoundTripMillis = elapsedMillis(
                        session.playBootstrapSentNanos, System.nanoTime());
                logger.info(
                        "Accepted Silent Gear lobby ACK from {}: profile={}, ack={}/{}, "
                                + "installedChannel={}, bootstrapRoundTripMs={}",
                        player.getUsername(),
                        session.silentGearProfile.profileId(),
                        acknowledgement.ordinal(),
                        acknowledgement.expected(),
                        acknowledgement.acknowledgedChannel(),
                        ackRoundTripMillis);
                if (acknowledgement.complete()) {
                    logger.info(
                            "Completed self-contained Silent Gear lobby profile transaction for {}: "
                                    + "profile={}, queryFingerprint={}, acknowledgements={}, "
                                    + "sequenceSha256={}",
                            player.getUsername(),
                            session.silentGearProfile.profileId(),
                            session.clientRegistryFingerprint,
                            session.silentGearAcks.received(),
                            session.silentGearProfile.payloadSequenceSha256());
                    completedSilentGearTransaction = true;
                }
            }
            if (session.observedPlaySinkChannels.add(channelId)) {
                logger.info(
                        "Consumed NeoForge lobby PLAY payload from {}: channel={}, bytes={} "
                                + "(not forwarded to Paper)",
                        player.getUsername(), channelId, payload.length);
            }
        }
        if (completedSilentGearTransaction) {
            completeLobbyClientInitialization(player, session);
        }
    }

    private void registerFixedChannels() {
        List<ChannelIdentifier> identifiers = new ArrayList<>();
        NEOFORGE_BUILTIN_CHANNEL_IDS.stream().sorted()
                .map(MinecraftChannelIdentifier::from)
                .forEach(identifiers::add);
        ReviewedConfigurationProfile.ownedChannelIds().stream().sorted()
                .map(MinecraftChannelIdentifier::from)
                .forEach(identifiers::add);
        identifiers.add(APOTHIC_ENCHANTMENT_INFO);
        identifiers.addAll(SILENT_GEAR_CHANNELS.values());
        identifiers.add(BRIDGE_CONTROL);
        proxy.getChannelRegistrar().register(identifiers.toArray(ChannelIdentifier[]::new));
    }

    private static boolean interceptsLobbyChannels(BridgeSession session) {
        synchronized (session) {
            boolean lifecycleStateEligible = switch (session.state) {
                case LOBBY_ARMED, LOBBY_WAITING_FOR_QUERY,
                        LOBBY_SYNCING_FROZEN_REGISTRIES,
                        LOBBY_WAITING_FOR_REGISTRY_TAIL, LOBBY_INJECTING_REGISTRIES,
                        LOBBY_READY, LOBBY_PLAY -> true;
                default -> false;
            };
            boolean nativePassThroughNegotiation =
                    session.negotiation == ClientNegotiation.VANILLA
                            || session.negotiation == ClientNegotiation.PROTOCOL_BYPASS;
            return LobbyPlaySinkAdmissionPolicy.interceptsProtocolChannels(
                    lifecycleStateEligible, nativePassThroughNegotiation);
        }
    }

    private static boolean sendNeoForgeQuery(Player player, BridgeSession session) {
        synchronized (session) {
            if (session.querySentThisLobbyCycle) {
                return true;
            }
            boolean sent = player.sendPluginMessage(
                    NEOFORGE_REGISTER, NeoForgeHandshakeCodec.queryRequest());
            session.querySentThisLobbyCycle = sent;
            if (sent) {
                session.querySentNanos = System.nanoTime();
            }
            return sent;
        }
    }

    private static int registrarBoundedPlaySinkChannelCount(BridgeSession session) {
        return (int) session.playSinkChannels.stream()
                .filter(channel -> !channel.id().equals(SilentGearProtocol.ACK))
                .count();
    }

    private static long elapsedMillis(long startedNanos, long completedNanos) {
        if (startedNanos <= 0L || completedNanos <= 0L) {
            return -1L;
        }
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, completedNanos - startedNanos));
    }

    private static String shortFingerprint(String fingerprint) {
        return fingerprint == null || fingerprint.length() <= 12
                ? String.valueOf(fingerprint)
                : fingerprint.substring(0, 12);
    }

    private void rotateReadinessToken(BridgeSession session) {
        long sessionId;
        do {
            sessionId = secureRandom.nextLong();
        } while (sessionId == 0L);
        session.readinessSessionId = sessionId;
    }

    private static void clearReadinessToken(BridgeSession session) {
        session.readinessSessionId = 0L;
    }

    private void reject(Player player, BridgeSession session, String logReason) {
        synchronized (session) {
            if (session.state == State.FAILED) {
                return;
            }
            session.state = State.FAILED;
            session.lobbyConfigurationEntryFence.clear();
            clearRegistryReplacementAttachment(session);
            clearBlockStateTranslation(session);
            clearLegacyForgeHandoffGuard(session);
            consoleLogger.warn(
                    "Rejected lobby bridge session for {} [{}]: {}",
                    player.getUsername(), player.getUniqueId(), logReason);
            abandonConfigurationGates(session);
            clearReadinessToken(session);
            player.disconnect(INCOMPATIBLE);
        }
    }

    private static void clearBlockStateTranslation(BridgeSession session) {
        VelocityLobbyPlayPacketTranslator.Lease lease = session.blockStateTranslationLease;
        CompletableFuture<VelocityLobbyPlayPacketTranslator.Lease> gate =
                session.blockStateTranslationGate;
        session.blockStateTranslationLease = null;
        session.blockStateTranslationGate = null;
        session.blockStateTranslationEndpoint = null;
        session.blockStateTranslationProfile = null;
        session.blockStateTranslationSource = "none";
        if (gate != null && !gate.isDone()) {
            gate.cancel(false);
        }
        closeBlockStateTranslationLeaseSafely(lease);
    }

    /** Best-effort cleanup cannot interrupt the cardinal fallback to Paper passthrough. */
    static void closeBlockStateTranslationLeaseSafely(
            VelocityLobbyPlayPacketTranslator.Lease lease) {
        if (lease == null) {
            return;
        }
        try {
            lease.deactivate();
        } catch (RuntimeException | LinkageError ignored) {
            // A broken optional adapter cannot prevent the independent close attempt.
        }
        try {
            lease.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Pipeline removal is best effort; enrichment cannot gate admission.
        }
    }

    /** Detaches the CONFIG-only replacement guard without erasing completed write evidence. */
    private static void clearRegistryReplacementAttachment(BridgeSession session) {
        CompletableFuture<VelocityRegistryReplacementGuard.Lease> attachment =
                session.registryReplacementAttachment;
        VelocityRegistryReplacementGuard.Lease lease = session.registryReplacementLease;
        session.registryReplacementAttachment = null;
        session.registryReplacementLease = null;
        session.registryReplacementPacket = null;
        if (attachment != null && !attachment.isDone()) {
            attachment.cancel(false);
        }
        if (lease != null) {
            try {
                lease.close();
            } catch (RuntimeException | LinkageError ignored) {
                // Deactivation precedes the lease's best-effort pipeline removal.
            }
        }
    }

    private static void detachRegistryReplacementLease(
            VelocityRegistryReplacementGuard.Lease lease) {
        try {
            lease.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Lease detachment deactivates the handler before best-effort pipeline removal.
        }
    }

    /** Removes only enchantment-registry transform proof; independent registry receipts survive. */
    private static void removeRegistryReplacementProof(BridgeSession session) {
        session.registryShimReceipts = session.registryShimReceipts.stream()
                .filter(receipt -> !receipt.shimId().equals(
                                RegistryShimCatalog.FULL_ENCHANTMENT_ATM10_8_1)
                        && !receipt.registryId().equals(
                                Atm10Normal81EnchantmentRegistry.REGISTRY_ID))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void attachLegacyForgeHandoffGuard(
            Player player,
            ServerConnection lobbyConnection,
            BridgeSession session,
            BridgeConfig currentConfig) {
        if (!currentConfig.enableLegacyForgeHandoffGuard()
                || player.getProtocolVersion().getProtocol()
                        != LegacyForgeHandoffPolicy.MINECRAFT_1_7_10_PROTOCOL) {
            return;
        }
        VelocityLegacyForgeHandoffGuard guard = legacyForgeHandoffGuard;
        if (guard == null) {
            failLegacyForgeHandoffGuard(
                    player,
                    session,
                    lobbyConnection,
                    session.legacyForgeGuardGeneration,
                    new IllegalStateException("legacy Forge handoff adapter is unavailable"));
            return;
        }

        CompletableFuture<VelocityLegacyForgeHandoffGuard.Lease> attachment;
        long generation;
        synchronized (session) {
            if (session.state == State.FAILED) {
                return;
            }
            if (session.legacyForgeGuardEndpoint == lobbyConnection
                    && ((session.legacyForgeGuardLease != null
                                    && session.legacyForgeGuardLease.active())
                            || (session.legacyForgeGuardGate != null
                                    && !session.legacyForgeGuardGate.isDone()))) {
                return;
            }
            clearLegacyForgeHandoffGuard(session);
            generation = session.legacyForgeGuardGeneration;
            attachment = guard.attach(
                    player,
                    lobbyConnection,
                    currentConfig.lobbyServer(),
                    Set.copyOf(currentConfig.legacyForgeHandoffTargets()),
                    new VelocityLegacyForgeHandoffGuard.Listener() {
                        @Override
                        public void suppressed(
                                LegacyForgeHandoffPolicy.ControlOperation operation,
                                String inFlightTarget,
                                long sequence) {
                            logger.info(
                                    "Fenced legacy Forge handoff payload for {} [{}]: "
                                            + "protocol=5, oldBackend='{}', "
                                            + "inFlightTarget='{}', channel={}, sequence={}, "
                                            + "trackingReplay=Velocity-native, routingMutation=false",
                                    player.getUsername(),
                                    player.getUniqueId(),
                                    currentConfig.lobbyServer(),
                                    inFlightTarget,
                                    operation,
                                    sequence);
                        }

                        @Override
                        public void failed(Throwable failure) {
                            failLegacyForgeHandoffGuard(
                                    player, session, lobbyConnection, generation, failure);
                        }
                    },
                    LEGACY_FORGE_GUARD_ATTACH_TIMEOUT_MILLIS);
            session.legacyForgeGuardGate = attachment;
            session.legacyForgeGuardEndpoint = lobbyConnection;
        }

        attachment.whenComplete((lease, failure) -> {
            if (failure != null) {
                failLegacyForgeHandoffGuard(
                        player, session, lobbyConnection, generation, failure);
                return;
            }
            boolean accepted;
            synchronized (session) {
                accepted = sessions.get(player.getUniqueId()) == session
                        && session.state != State.FAILED
                        && session.legacyForgeGuardGeneration == generation
                        && session.legacyForgeGuardGate == attachment
                        && session.legacyForgeGuardEndpoint == lobbyConnection
                        && player.getCurrentServer().orElse(null) == lobbyConnection;
                if (accepted) {
                    session.legacyForgeGuardGate = null;
                    session.legacyForgeGuardLease = lease;
                }
            }
            if (!accepted) {
                lease.close();
                return;
            }
            logger.info(
                    "Legacy Forge handoff fence armed for {} [{}]: protocol=5, "
                            + "backend='{}', allowedInFlightTargets={}, generation={}",
                    player.getUsername(),
                    player.getUniqueId(),
                    currentConfig.lobbyServer(),
                    currentConfig.legacyForgeHandoffTargets(),
                    generation);
        });
    }

    private void failLegacyForgeHandoffGuard(
            Player player,
            BridgeSession session,
            ServerConnection expectedEndpoint,
            long generation,
            Throwable failure) {
        boolean reject;
        synchronized (session) {
            reject = sessions.get(player.getUniqueId()) == session
                    && session.state != State.FAILED
                    && session.legacyForgeGuardGeneration == generation
                    && (session.legacyForgeGuardEndpoint == expectedEndpoint
                            || session.legacyForgeGuardEndpoint == null);
            if (!reject) {
                return;
            }
            session.state = State.FAILED;
            session.lobbyConfigurationEntryFence.clear();
            clearRegistryReplacementAttachment(session);
            clearBlockStateTranslation(session);
            VelocityLegacyForgeHandoffGuard.Lease lease = session.legacyForgeGuardLease;
            if (lease != null) {
                lease.close();
            }
            session.legacyForgeGuardLease = null;
            session.legacyForgeGuardGate = null;
            session.legacyForgeGuardEndpoint = null;
            abandonConfigurationGates(session);
            clearReadinessToken(session);
        }
        consoleLogger.warn(
                "Rejected legacy Forge 1.7.10 handoff for {} [{}]: "
                        + "backend='{}', generation={}, failClosed=true",
                player.getUsername(),
                player.getUniqueId(),
                expectedEndpoint.getServerInfo().getName(),
                generation,
                failure);
        player.disconnect(LEGACY_FORGE_INCOMPATIBLE);
    }

    private static void clearLegacyForgeHandoffGuard(BridgeSession session) {
        VelocityLegacyForgeHandoffGuard.Lease lease = session.legacyForgeGuardLease;
        CompletableFuture<VelocityLegacyForgeHandoffGuard.Lease> gate =
                session.legacyForgeGuardGate;
        session.legacyForgeGuardLease = null;
        session.legacyForgeGuardGate = null;
        session.legacyForgeGuardEndpoint = null;
        session.legacyForgeGuardGeneration++;
        if (gate != null && !gate.isDone()) {
            gate.cancel(false);
        }
        if (lease != null) {
            lease.close();
        }
    }

    private void validateRegisteredServers(BridgeConfig loaded) {
        if (proxy.getServer(loaded.lobbyServer()).isEmpty()) {
            throw new IllegalArgumentException(
                    "lobby-server is not declared in velocity.toml: " + loaded.lobbyServer());
        }
    }

    private void auditVelocityPayloadLimits(
            BridgeConfig loaded, SilentGearProfileCatalog profiles) {
        VelocityPluginMessageLimits.Limits limits =
                VelocityPluginMessageLimits.fromSystemProperties();
        int requiredServerbound = loaded.limits().maximumQueryBytes();
        int frozenRegistryRequired = loaded.enableBackendNeoForgeCapabilityRelay()
                ? profiles.maximumFrozenRegistryPayloadBytes()
                : 0;
        int observedPlayFrameRequired = loaded.enableBackendNeoForgeCapabilityRelay()
                ? ReviewedBackendPluginMessageBounds
                        .OBSERVED_ATM10_NORMAL_8_0_PLAY_FRAME_BYTES
                : 0;
        int requiredClientbound = loaded.enableBackendNeoForgeCapabilityRelay()
                ? ReviewedBackendPluginMessageBounds.requiredClientboundPropertyBytes(
                        frozenRegistryRequired)
                : 0;
        String recommended = VelocityPluginMessageLimits.recommendedArguments(
                requiredServerbound,
                requiredClientbound > 0 ? requiredClientbound : limits.clientboundBytes());

        if (limits.serverboundBytes() < requiredServerbound) {
            consoleLogger.error(
                    "Velocity's serverbound plugin-message limit is {} bytes, below the "
                            + "reviewed NeoForge query bound of {} bytes. Cold-restart Velocity "
                            + "with {} before -jar",
                    limits.serverboundBytes(),
                    requiredServerbound,
                    recommended);
        }
        if (requiredClientbound > 0 && limits.clientboundBytes() < requiredClientbound) {
            consoleLogger.error(
                    "Velocity's clientbound plugin-message limit is {} bytes, below the "
                            + "reviewed backend bound of {} bytes (largest frozen registry={}, "
                            + "observed PLAY frame={}). Native backend traffic will fail in "
                            + "Velocity's decoder before it reaches ProtocolObelisk or the "
                            + "client. Remove any -D{} shared override and cold-restart Velocity "
                            + "with {} before -jar",
                    limits.clientboundBytes(),
                    requiredClientbound,
                    frozenRegistryRequired,
                    observedPlayFrameRequired,
                    VelocityPluginMessageLimits.ALL_DIRECTIONS_PROPERTY,
                    recommended);
        } else if (requiredClientbound > 0 && limits.sharedOverride()) {
            consoleLogger.warn(
                    "Velocity's shared plugin-message override raises client-originated traffic "
                            + "to {} bytes. Preserve the reviewed {}-byte serverbound bound with "
                            + "direction-specific JVM arguments instead: {}",
                    limits.serverboundBytes(),
                    requiredServerbound,
                    recommended);
        }
        logger.info(
                "Velocity plugin-message limit audit: serverbound={} bytes (required={}), "
                        + "clientbound={} bytes (reviewedBackendRequired={}, "
                        + "frozenRegistryRequired={}, observedPlayFrame={}, "
                        + "decoderFrameCapacity={}), sharedOverride={}",
                limits.serverboundBytes(),
                requiredServerbound,
                limits.clientboundBytes(),
                requiredClientbound,
                frozenRegistryRequired,
                observedPlayFrameRequired,
                ReviewedBackendPluginMessageBounds.decoderFrameCapacityBytes(
                        limits.clientboundBytes()),
                limits.sharedOverride());
    }

    private BridgeConfig activeConfig() {
        BridgeConfig current = config;
        return current != null && current.enabled() ? current : null;
    }

    /**
     * Build-time reviewed structural profiles. The ATM10 8.1 exporter fills this exact definition
     * only after its resource root and manifest digest have been reproduced and audited.
     */
    private static List<ReviewedBlockStateProfileCatalog.Definition>
            bundledReviewedBlockStateDefinitions() {
        return List.of(
                new ReviewedBlockStateProfileCatalog.Definition(
                        "blockstate-profiles/atm10-normal-8.1-neoforge-21.1.249/",
                        Atm10Normal81Contract.ID,
                        Atm10Normal81Contract.MINECRAFT_PROTOCOL,
                        Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256,
                        "394756c142fda290e530318a306bb0c68d108825060b5f02b94c13f2c7418600"),
                new ReviewedBlockStateProfileCatalog.Definition(
                        SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT,
                        "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                        767,
                        ReviewedClientContractEvidence.ATM10_NORMAL_8_0
                                .fullClientContractSha256(),
                        "de39e16287bfa83396c9f54b1403cf5937d74b4c2ad819922f5540d7e219e419"));
    }

    private ReviewedBlockStateProfileCatalog loadBundledReviewedBlockStateProfiles() {
        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        Atm10LobbyVelocityPlugin.class.getClassLoader(),
                        bundledReviewedBlockStateDefinitions());
        catalog.diagnostics().forEach(diagnostic ->
            consoleLogger.warn(
                    "Quarantined invalid embedded BlockState enrichment: "
                            + "profile={}, failure={}, detail={}; admission remains enabled "
                            + "with immediate passthrough",
                    diagnostic.profileId(),
                    diagnostic.failure(),
                    diagnostic.detail()));
        return catalog;
    }

    /**
     * Converts an unavailable internal Velocity adapter into optional visual passthrough.
     * Admission and routing must never depend on this enrichment surface.
     */
    static VelocityLobbyPlayPacketTranslator resolveBlockStateTranslatorOrPassthrough(
            Supplier<VelocityLobbyPlayPacketTranslator> resolver,
            Consumer<Throwable> diagnosticListener) {
        java.util.Objects.requireNonNull(resolver, "resolver");
        java.util.Objects.requireNonNull(diagnosticListener, "diagnosticListener");
        try {
            VelocityLobbyPlayPacketTranslator resolved = resolver.get();
            if (resolved != null) {
                return resolved;
            }
            reportBlockStateTranslatorDiagnostic(
                    diagnosticListener,
                    new IllegalStateException(
                            "reviewed BlockState packet adapter resolver returned null"));
        } catch (RuntimeException | LinkageError failure) {
            reportBlockStateTranslatorDiagnostic(diagnosticListener, failure);
        }
        return null;
    }

    /** Resolves optional registry replacement without making Velocity internals an admission gate. */
    static VelocityRegistryReplacementGuard resolveRegistryReplacementGuardOrPassthrough(
            Supplier<VelocityRegistryReplacementGuard> resolver,
            Consumer<Throwable> diagnosticListener) {
        java.util.Objects.requireNonNull(resolver, "resolver");
        java.util.Objects.requireNonNull(diagnosticListener, "diagnosticListener");
        try {
            VelocityRegistryReplacementGuard resolved = resolver.get();
            if (resolved != null) {
                return resolved;
            }
            reportRegistryReplacementDiagnostic(
                    diagnosticListener,
                    new IllegalStateException(
                            "reviewed registry replacement adapter resolver returned null"));
        } catch (RuntimeException | LinkageError failure) {
            reportRegistryReplacementDiagnostic(diagnosticListener, failure);
        }
        return null;
    }

    /** Resolves optional registry tags without allowing a missing adapter to disable admission. */
    static VelocityTagsInjector resolveRegistryTagsInjectorOrPassthrough(
            Supplier<VelocityTagsInjector> resolver,
            Consumer<Throwable> diagnosticListener) {
        java.util.Objects.requireNonNull(resolver, "resolver");
        java.util.Objects.requireNonNull(diagnosticListener, "diagnosticListener");
        try {
            VelocityTagsInjector resolved = resolver.get();
            if (resolved != null) {
                return resolved;
            }
            reportRegistryReplacementDiagnostic(
                    diagnosticListener,
                    new IllegalStateException(
                            "reviewed registry tags adapter resolver returned null"));
        } catch (RuntimeException | LinkageError failure) {
            reportRegistryReplacementDiagnostic(diagnosticListener, failure);
        }
        return null;
    }

    private static void reportRegistryReplacementDiagnostic(
            Consumer<Throwable> diagnosticListener, Throwable failure) {
        try {
            diagnosticListener.accept(failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Optional replacement diagnostics can never become an admission dependency.
        }
    }

    private static void reportBlockStateTranslatorDiagnostic(
            Consumer<Throwable> diagnosticListener, Throwable failure) {
        try {
            diagnosticListener.accept(failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Optional structural diagnostics can never become an admission dependency.
        }
    }

    private SilentGearProfileCatalog loadReviewedSilentGearProfiles(BridgeConfig loaded) {
        try {
            return SilentGearProfileCatalog.loadReviewed(
                    Atm10LobbyVelocityPlugin.class.getClassLoader(),
                    loaded.expectedMinecraftProtocol(),
                    loaded.maximumSilentGearPayloadBytes(),
                    loaded.maximumSilentGearTotalBytes());
        } catch (IOException | IllegalArgumentException exception) {
            consoleLogger.warn(
                    "Quarantined invalid embedded structural profile; admission remains enabled "
                            + "with capability-adaptive negotiation and immediate BlockState "
                            + "passthrough",
                    exception);
            return SilentGearProfileCatalog.empty();
        }
    }

    private void shutdownNecroTempusTransport() {
        NecroTempusTransportService transport = necroTempusTransport;
        necroTempusTransport = null;
        if (transport == null) {
            return;
        }
        try {
            transport.shutdown();
        } catch (RuntimeException exception) {
            consoleLogger.warn(
                    "ProtocolObelisk NecroTempus transport did not shut down cleanly",
                    exception);
        }
    }

    private String activeLobbyName() {
        BridgeConfig current = activeConfig();
        return current == null ? "" : current.lobbyServer();
    }

    private static EventTask completedTask() {
        return EventTask.resumeWhenComplete(CompletableFuture.completedFuture(null));
    }

    /**
     * Retires configuration futures owned by a terminal or obsolete connection without completing
     * them. Completing these futures after Velocity has torn down the backend resumes its
     * KnownPacks continuation against a disconnected ServerConnection. Once references are
     * dropped, the abandoned future/continuation graph is unreachable with the retired session.
     */
    private static void abandonConfigurationGates(BridgeSession session) {
        session.lobbyCycleGeneration++;
        session.frozenRegistryGeneration++;
        session.playBootstrapGeneration++;
        session.legacyForgeGuardGeneration++;
        session.playBootstrapScheduled = false;
        session.playBootstrapWriteInFlight = false;
        session.gate = null;
        session.registryTailGate = null;
    }

    private static void completeConfigurationGates(BridgeSession session) {
        if (session.gate != null) {
            session.gate.complete(null);
        }
        if (session.registryTailGate != null) {
            session.registryTailGate.complete(null);
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String stableFailureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private void traceNativeBungeePluginMessage(
            PluginMessageEvent event, String channelId, Player player) {
        byte[] data = event.getData();
        String subchannel = "<decode-failed>";
        String detail = "none";
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            subchannel = input.readUTF();
            if (subchannel.equalsIgnoreCase("Connect")
                    || subchannel.equalsIgnoreCase("ConnectOther")
                    || subchannel.equalsIgnoreCase("Forward")
                    || subchannel.equalsIgnoreCase("ForwardToPlayer")) {
                try {
                    detail = input.readUTF();
                } catch (EOFException ignored) {
                    detail = "<missing-argument>";
                }
            }
        } catch (IOException | RuntimeException exception) {
            detail = exception.getClass().getSimpleName();
        }
        consoleLogger.info(
                "[BUNGEE-TRACE] pluginMessage channel={} subchannel={} detail={} bytes={} "
                        + "source={} target={} player={} protocol={} nativeHandling=untouched",
                channelId,
                subchannel,
                detail,
                data.length,
                pluginMessageEndpoint(event.getSource()),
                pluginMessageEndpoint(event.getTarget()),
                player == null ? "none" : player.getUsername(),
                player == null ? -1 : player.getProtocolVersion().getProtocol());
    }

    private static String pluginMessageEndpoint(Object endpoint) {
        if (endpoint instanceof ServerConnection serverConnection) {
            return "server:" + serverName(serverConnection);
        }
        if (endpoint instanceof Player player) {
            return "player:" + player.getUsername();
        }
        return endpoint == null ? "null" : endpoint.getClass().getSimpleName();
    }

    private static Player playerFrom(PluginMessageEvent event) {
        if (event.getSource() instanceof Player player) {
            return player;
        }
        if (event.getTarget() instanceof Player player) {
            return player;
        }
        if (event.getSource() instanceof ServerConnection serverConnection) {
            return serverConnection.getPlayer();
        }
        return null;
    }

    private static ServerConnection serverEndpoint(PluginMessageEvent event) {
        if (event.getSource() instanceof ServerConnection serverConnection) {
            return serverConnection;
        }
        if (event.getTarget() instanceof ServerConnection serverConnection) {
            return serverConnection;
        }
        return null;
    }

    private static String serverName(ServerConnection server) {
        return server.getServerInfo().getName();
    }

    private static MinecraftChannelIdentifier channel(String id) {
        return MinecraftChannelIdentifier.from(id);
    }

    private enum ClientNegotiation {
        NEOFORGE,
        VANILLA,
        PROTOCOL_BYPASS
    }

    private enum State {
        OUTSIDE,
        LOBBY_ARMED,
        LOBBY_WAITING_FOR_QUERY,
        LOBBY_SYNCING_FROZEN_REGISTRIES,
        LOBBY_WAITING_FOR_REGISTRY_TAIL,
        LOBBY_INJECTING_REGISTRIES,
        LOBBY_READY,
        LOBBY_PLAY,
        FAILED
    }

    private record PlaySinkReservation(
            List<Channel> channels,
            Set<String> channelIds,
            int omittedByCapacity) {
        private PlaySinkReservation {
            channels = List.copyOf(channels);
            channelIds = Set.copyOf(channelIds);
        }
    }

    private static final class BridgeSession {
        private final UUID playerUuid;
        private State state = State.OUTSIDE;
        private ClientNegotiation negotiation;
        private CompletableFuture<Void> gate;
        private CompletableFuture<Void> registryTailGate;
        private long readinessSessionId;
        private long lobbyCycleGeneration;
        private final LobbyConfigurationEntryFence lobbyConfigurationEntryFence =
                new LobbyConfigurationEntryFence();
        private boolean querySentThisLobbyCycle;
        private boolean neoForgeQueryReceived;
        private boolean lobbyHandshakeStarted;
        private long querySentNanos;
        private long queryReceivedNanos;
        private long frozenRegistrySentNanos;
        private long configurationReadyNanos;
        private long playEnteredNanos;
        private long lobbyInitializationCompleteNanos;
        private int queryBytes;
        private int registryShimPacketsInjected;
        private int registryShimBytesInjected;
        private Set<RegistryShimReceipt> registryShimReceipts = Set.of();
        private CompletableFuture<VelocityRegistryReplacementGuard.Lease>
                registryReplacementAttachment;
        private VelocityRegistryReplacementGuard.Lease registryReplacementLease;
        private RegistryShimPacket registryReplacementPacket;
        private RegistryReplacementGuardHandler.TransformMode registryReplacementMode;
        private String registryReplacementEvidenceId = "none";
        private String registryReplacementStatus = "NOT_SELECTED";
        private Set<String> advertisedNamespaces = Set.of();
        private List<Channel> playSinkChannels = List.of();
        private Set<String> playSinkChannelIds = Set.of();
        private List<Channel> externallyOwnedPlayChannels = List.of();
        private List<Channel> clientboundPlayBootstrapChannels = List.of();
        private Set<String> clientboundPlayBootstrapChannelIds = Set.of();
        private String clientRegistryFingerprint;
        private String fullClientContractSha256;
        private String normalizedClientContractSha256;
        private String reviewedClientContractVariant = "none";
        private boolean atm10Normal82GiselleMergeEvidence;
        private boolean negotiationPlanCacheHit;
        private long negotiationPlanningNanos;
        private SilentGearEmbeddedProfile silentGearProfile;
        private CompletableFuture<VelocityLobbyPlayPacketTranslator.Lease>
                blockStateTranslationGate;
        private VelocityLobbyPlayPacketTranslator.Lease blockStateTranslationLease;
        private ServerConnection blockStateTranslationEndpoint;
        private BlockStateTranslationProfile blockStateTranslationProfile;
        private String blockStateTranslationSource = "none";
        private long legacyForgeGuardGeneration;
        private CompletableFuture<VelocityLegacyForgeHandoffGuard.Lease>
                legacyForgeGuardGate;
        private VelocityLegacyForgeHandoffGuard.Lease legacyForgeGuardLease;
        private ServerConnection legacyForgeGuardEndpoint;
        private SilentGearAckTransaction silentGearAcks;
        private FrozenRegistryAckTransaction frozenRegistryAcks;
        private int frozenRegistryCountSent;
        private int frozenRegistryEntriesSent;
        private int frozenRegistryBytesSent;
        private int configurationPrefixPacketsSent;
        private int configurationPrefixBytesSent;
        private boolean configurationPrefixCacheHit;
        private boolean configurationPrefixWriteComplete;
        private long frozenRegistryGeneration;
        private boolean ae2JeiSessionOptimizationSelected;
        private int ae2JeiSessionOptimizationBytesSent;
        private boolean playBootstrapScheduled;
        private boolean playBootstrapWriteInFlight;
        private boolean playBootstrapSent;
        private long playBootstrapSentNanos;
        private int playBootstrapSendAttempts;
        private int playBootstrapBytesSent;
        private long playBootstrapGeneration;
        private boolean lobbyClientInitializationComplete;
        private final BackendNeoForgeCapabilityRelay backendNeoForgeCapabilityRelay =
                new BackendNeoForgeCapabilityRelay();
        private PlaySinkPlanner.Mode playSinkPlanMode;
        private int omittedPlaySinkChannels;
        private String lastConfirmedBackendServerId;
        private String previousBackendServerId;
        private List<String> transientServerConfigs = List.of();
        private String transientServerConfigSource = "baseline";
        private int derivedTransientServerConfigs;
        private int omittedDerivedTransientServerConfigs;
        private int ignoredTransientConfigNamespaces;
        private Atm10Normal81ServerConfigCatalog.Catalog reviewedTransientConfigCatalog;
        private String reviewedTransientConfigCatalogId =
                TransientServerConfigPlanner.NO_REVIEWED_CATALOG;
        private String reviewedTransientConfigNameSequenceSha256 = "";
        private String reviewedTransientConfigPayloadSequenceSha256 = "";
        private int reviewedTransientConfigCatalogCandidates;
        private int reviewedTransientConfigCatalogEncodedBytes;
        private int addedReviewedTransientConfigs;
        private int retainedReviewedTransientConfigs;
        private int discardedBaseTransientConfigs;
        private LobbyPlayRateLimiter playRateLimiter;
        private Set<String> observedPlaySinkChannels = new LinkedHashSet<>();

        private BridgeSession(UUID playerUuid) {
            this.playerUuid = playerUuid;
        }

        @Override
        public String toString() {
            return "BridgeSession{" + playerUuid + ", state=" + state + '}';
        }
    }
}
