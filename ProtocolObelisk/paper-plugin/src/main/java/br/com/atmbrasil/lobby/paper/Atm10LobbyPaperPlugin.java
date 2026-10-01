package br.com.atmbrasil.lobby.paper;

import br.com.atmbrasil.lobby.common.LegacyConfigurationMigration;
import br.com.atmbrasil.lobby.common.LobbyReadyCodec;
import br.com.atmbrasil.lobby.common.LobbyReadyCodec.LobbyReady;
import br.com.atmbrasil.lobby.common.LobbyReadyCodec.ProtocolException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class Atm10LobbyPaperPlugin extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter, PluginMessageListener {

    private static final int CONFIG_VERSION = 4;
    private static final int LEGACY_CONFIG_VERSION = 3;
    private static final String RELEASE_ID = "ProtocolObelisk 1.9.16-EVOLUTION";
    private static final String LEGACY_PLUGIN_NAME = "Atm10LobbyBridge";
    private static final String CONTROL_CHANNEL = LobbyReadyCodec.CHANNEL_ID;
    private static final String BUILD_PERMISSION = "protocolobelisk.build";
    private static final String LEGACY_BUILD_PERMISSION = "atm10lobby.build";

    private final Map<UUID, Long> readySessions = new ConcurrentHashMap<>();
    private final Set<UUID> rescueInProgress = ConcurrentHashMap.newKeySet();

    private LobbyConfig lobbyConfig;
    private PaperRecipePacketGuard recipePacketGuard;
    private DiagnosticLog diagnosticLog;
    private PaperMovementDiagnostics movementDiagnostics;
    private PaperNecroTempusBridge necroTempusBridge;

    @Override
    public void onEnable() {
        for (var installedPlugin : getServer().getPluginManager().getPlugins()) {
            if (installedPlugin != this
                    && installedPlugin.getName().equalsIgnoreCase(LEGACY_PLUGIN_NAME)) {
                getLogger().severe(
                        RELEASE_ID + " was not enabled because the legacy "
                                + "Atm10LobbyBridge JAR is also installed; remove the old JAR "
                                + "and restart Paper");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        final LegacyConfigurationMigration.Migration migration;
        try {
            migration = LegacyConfigurationMigration.copyIfTargetMissing(
                    getDataFolder().toPath().resolveSibling(LEGACY_PLUGIN_NAME)
                            .resolve("config.yml"),
                    getDataFolder().toPath().resolve("config.yml"))
                    .orElse(null);
        } catch (IOException | IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE,
                    "ProtocolObelisk could not safely migrate the legacy Paper configuration",
                    exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        diagnosticLog = new DiagnosticLog(getLogger()::info);
        movementDiagnostics = new PaperMovementDiagnostics(diagnosticLog);
        if (migration != null) {
            getLogger().info(
                    "Migrated legacy Paper configuration for " + RELEASE_ID + ": source="
                            + migration.legacyConfig()
                            + ", target=" + migration.targetConfig()
                            + ", bytes=" + migration.bytes()
                            + ", legacySourcePreserved=true");
        }
        if (!loadValidatedConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (lobbyConfig.suppressesAnyRecipePackets()) {
            try {
                recipePacketGuard = PaperRecipePacketGuard.install(
                        this,
                        lobbyConfig.suppressRecipeBookPackets(),
                        lobbyConfig.suppressRecipeDefinitionPackets(),
                        diagnosticLog);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                getLogger().log(Level.SEVERE,
                        "Paper/Purpur 1.21.1 early packet hook is unavailable; "
                                + "the lobby bridge cannot safely continue",
                        exception);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, CONTROL_CHANNEL, this);
        PluginCommand admin = requireCommand("protocolobelisk");
        admin.setExecutor(this);
        admin.setTabCompleter(this);
        necroTempusBridge = new PaperNecroTempusBridge(this, getLogger(), diagnosticLog);
        necroTempusBridge.start(lobbyConfig.necroTempus());
        ProMenusBungeeCompatibility.arm(this);
        getLogger().info(
                "Protocol-only Paper lobby enabled; routingAuthority=Velocity; "
                        + "suppressRecipeBookPackets="
                        + lobbyConfig.suppressRecipeBookPackets()
                        + "; orderedEmptyRecipeLifecycle="
                        + lobbyConfig.suppressRecipeDefinitionPackets()
                        + "; earlyRecipeGuardInstalled=" + (recipePacketGuard != null)
                        + "; cancelGenericUiEvents="
                        + lobbyConfig.cancelGenericUiEvents()
                        + "; builderBypassPermission=" + BUILD_PERMISSION
                        + "; Paper holds no backend address, destination or transfer command");
    }

    @Override
    public void onDisable() {
        PaperNecroTempusBridge currentNecroTempusBridge = necroTempusBridge;
        necroTempusBridge = null;
        if (currentNecroTempusBridge != null) {
            currentNecroTempusBridge.close();
        }
        PaperRecipePacketGuard currentGuard = recipePacketGuard;
        recipePacketGuard = null;
        if (currentGuard != null) {
            currentGuard.close();
        }
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CONTROL_CHANNEL, this);
        readySessions.clear();
        rescueInProgress.clear();
        if (movementDiagnostics != null) {
            movementDiagnostics.clear();
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            LobbyConfig previous = lobbyConfig;
            reloadConfig();
            if (!loadValidatedConfig()) {
                lobbyConfig = previous;
                setDebugLogging(previous.debug());
                sender.sendMessage(Component.text(
                        "Configuração inválida; a configuração anterior continua ativa.",
                        NamedTextColor.RED));
                return true;
            }
            LobbyConfig reloaded = lobbyConfig;
            if (!previous.hasSameNetworkPolicy(reloaded)) {
                lobbyConfig = previous;
                setDebugLogging(previous.debug());
                sender.sendMessage(Component.text(
                        "A política de pacotes mudou; reinicie o Paper para aplicá-la com "
                                + "segurança. A política anterior continua ativa nesta execução.",
                        NamedTextColor.YELLOW));
                return true;
            }
            PaperNecroTempusBridge currentNecroTempusBridge = necroTempusBridge;
            if (currentNecroTempusBridge != null) {
                currentNecroTempusBridge.reconfigure(reloaded.necroTempus());
            }
            sender.sendMessage(Component.text(
                    "Configuração de proteção recarregada.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            LobbyConfig current = lobbyConfig;
            sender.sendMessage(Component.text("Mundo do lobby: " + current.lobbyWorld()));
            sender.sendMessage(Component.text("Debug do plugin: " + current.debug()));
            sender.sendMessage(Component.text(
                    "Autoridade de roteamento: Velocity (ProtocolObelisk não interfere)"));
            sender.sendMessage(Component.text(
                    "Proteção do servidor inteiro: " + current.protectEntireServer()));
            sender.sendMessage(Component.text(
                    "Cancelamento genérico de UI: " + current.cancelGenericUiEvents()));
            sender.sendMessage(Component.text(
                    "Sessões com bootstrap PLAY concluído: " + readySessions.size()));
            PaperRecipePacketGuard currentGuard = recipePacketGuard;
            sender.sendMessage(Component.text(
                    "Filtro de receitas: " + (currentGuard == null
                            ? "desativado"
                            : "ativo, canais=" + currentGuard.guardedChannelCount()
                                    + ", recipe-book suprimidos="
                                    + currentGuard.droppedRecipeBookPacketCount()
                                    + ", definições retidas="
                                    + currentGuard.suppressedRecipeDefinitionPacketCount()
                                    + ", ciclos vazios liberados="
                                    + currentGuard.rewrittenRecipeDefinitionPacketCount())));
            PaperNecroTempusBridge currentNecroTempusBridge = necroTempusBridge;
            if (currentNecroTempusBridge != null) {
                PaperNecroTempusBridge.Status necroStatus =
                        currentNecroTempusBridge.status();
                sender.sendMessage(Component.text(
                        "NecroTempus TAB: " + necroStatus.state()
                                + ", sessões confirmadas="
                                + necroStatus.confirmedSessions()
                                + ", snapshots enviados=" + necroStatus.sentSnapshots()
                                + ", duplicados=" + necroStatus.duplicateSnapshots()
                                + ", rejeitados=" + necroStatus.rejectedSnapshots()));
            }
            return true;
        }
        sender.sendMessage(Component.text(
                "Uso: /protocolobelisk reload|status", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("reload", "status").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        clearSession(player.getUniqueId());
        if (isLobbyScope(player)) {
            movementDiagnostics.beginSession(player.getUniqueId());
            observeMovement(player, PaperMovementDiagnostics.Phase.JOIN_BEFORE);
        }
        if (isRestricted(player)) {
            applySafeLobbyState(player);
        }
        observeMovement(player, PaperMovementDiagnostics.Phase.JOIN_AFTER);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        observeMovement(event.getPlayer(), PaperMovementDiagnostics.Phase.QUIT);
        clearSession(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (shouldCancelGenericUiEvents(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && shouldCancelGenericUiEvents(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && shouldCancelGenericUiEvents(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !shouldCancelGenericUiEvents(player)) {
            return;
        }
        InventoryType type = event.getView().getTopInventory().getType();
        if (type != InventoryType.PLAYER && type != InventoryType.CRAFTING) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (shouldCancelGenericUiEvents(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (shouldCancelGenericUiEvents(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (shouldCancelGenericUiEvents(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockDamage(BlockDamageEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPortal(PlayerPortalEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isLobbyScope(player) || event.getTo().getY() >= lobbyConfig.voidRescueY()
                || !rescueInProgress.add(player.getUniqueId())) {
            return;
        }
        World world = requiredLobbyWorld();
        movementDiagnostics.recordVoidRescue(player.getUniqueId());
        player.teleportAsync(world.getSpawnLocation()).whenComplete((success, failure) ->
                rescueInProgress.remove(player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTeleportDiagnostic(PlayerTeleportEvent event) {
        if (movementDiagnostics == null || !diagnosticLog.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        movementDiagnostics.teleport(player.getUniqueId(), player.getName(), player,
                event.getCause().name(), event.isCancelled(), event.getFrom(), event.getTo());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] messageBytes) {
        if (!channel.equals(CONTROL_CHANNEL)) {
            return;
        }
        final LobbyReady ready;
        try {
            ready = LobbyReadyCodec.decode(messageBytes);
        } catch (ProtocolException | IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE,
                    "Rejected malformed Velocity readiness signal for " + player.getName(),
                    exception);
            getServer().getScheduler().runTask(this, () -> player.kick(Component.text(
                    "O protocolo interno do lobby falhou; reconecte.", NamedTextColor.RED)));
            return;
        }
        getServer().getScheduler().runTask(this, () -> acceptLobbyReady(player, ready));
    }

    private void acceptLobbyReady(Player player, LobbyReady ready) {
        if (!player.isOnline() || !isLobbyScope(player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Long previous = readySessions.putIfAbsent(uuid, ready.sessionId());
        if (previous != null && previous.longValue() != ready.sessionId()) {
            clearSession(uuid);
            getLogger().severe(
                    "Velocity readiness token changed during one Paper connection for "
                            + player.getName());
            player.kick(Component.text(
                    "A inicialização segura do lobby mudou durante a conexão; entre novamente.",
                    NamedTextColor.RED));
            return;
        }
        if (previous != null) {
            return;
        }

        PaperRecipePacketGuard currentGuard = recipePacketGuard;
        if (currentGuard != null) {
            try {
                currentGuard.releaseRecipeLifecycle(player);
            } catch (RuntimeException exception) {
                clearSession(uuid);
                getLogger().log(Level.SEVERE,
                        "Could not release the ordered recipe lifecycle for "
                                + player.getName(), exception);
                player.kick(Component.text(
                        "A inicialização segura do lobby falhou; reconecte.",
                        NamedTextColor.RED));
                return;
            }
        }
        diagnosticLog.info(() ->
                "Accepted protocol-only lobby readiness for " + player.getName()
                        + "; Velocity retains all routing authority");
        observeMovement(player, PaperMovementDiagnostics.Phase.READY);
    }

    private void observeMovement(Player player, PaperMovementDiagnostics.Phase phase) {
        if (movementDiagnostics != null) {
            movementDiagnostics.snapshot(player.getUniqueId(), player.getName(), player, phase);
        }
    }

    private void applySafeLobbyState(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setExp(0.0F);
        player.setLevel(0);

        if (lobbyConfig.clearEffects()) {
            player.getActivePotionEffects().forEach(
                    effect -> player.removePotionEffect(effect.getType()));
        }
        if (lobbyConfig.clearInventory()) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
        }
        if (lobbyConfig.teleportToSpawn()) {
            player.teleportAsync(requiredLobbyWorld().getSpawnLocation());
        }
    }

    private boolean isLobbyScope(Player player) {
        LobbyConfig current = lobbyConfig;
        return current != null && LobbyProtectionPolicy.isInScope(
                current.protectEntireServer(),
                current.lobbyWorld(),
                player.getWorld().getName());
    }

    private boolean isRestricted(Player player) {
        LobbyConfig current = lobbyConfig;
        return current != null && LobbyProtectionPolicy.shouldRestrict(
                current.protectEntireServer(),
                current.lobbyWorld(),
                player.getWorld().getName(),
                hasBuildBypass(player));
    }

    private boolean shouldCancelGenericUiEvents(Player player) {
        LobbyConfig current = lobbyConfig;
        return current != null && LobbyProtectionPolicy.shouldCancelGenericUiEvents(
                current.cancelGenericUiEvents(),
                current.protectEntireServer(),
                current.lobbyWorld(),
                player.getWorld().getName(),
                hasBuildBypass(player));
    }

    private static boolean hasBuildBypass(Player player) {
        return player.hasPermission(BUILD_PERMISSION)
                || player.hasPermission(LEGACY_BUILD_PERMISSION);
    }

    private World requiredLobbyWorld() {
        World world = getServer().getWorld(lobbyConfig.lobbyWorld());
        if (world == null) {
            throw new IllegalStateException("validated lobby world disappeared");
        }
        return world;
    }

    private void clearSession(UUID uuid) {
        readySessions.remove(uuid);
        rescueInProgress.remove(uuid);
        if (movementDiagnostics != null) {
            movementDiagnostics.endSession(uuid);
        }
    }

    private boolean loadValidatedConfig() {
        try {
            int version = getConfig().getInt("config-version", -1);
            if (version != CONFIG_VERSION && version != LEGACY_CONFIG_VERSION) {
                if (!getDataFolder().toPath().resolve("config-v4.example.yml").toFile().exists()) {
                    saveResource("config-v4.example.yml", false);
                }
                throw new IllegalArgumentException(
                        "config-version must be 4 (legacy v3 is accepted in compatibility mode); "
                                + "migrate using config-v4.example.yml");
            }
            if (version == LEGACY_CONFIG_VERSION) {
                getLogger().warning(
                        "Accepted legacy Paper config-version=3: routing and selector sections are "
                                + "ignored because Velocity now owns every server transition");
            }

            boolean debug = DiagnosticLog.parseConfigValue(getConfig().get("debug"));
            String worldName = requireText(getConfig().getString("lobby-world"), "lobby-world");
            if (getServer().getWorld(worldName) == null) {
                throw new IllegalArgumentException("lobby-world is not loaded: " + worldName);
            }
            ConfigurationSection safety = requireSection("safety");
            boolean suppressRecipeBookPackets = optionalStrictBoolean(
                    "network-compatibility.suppress-recipe-book-packets", true);
            boolean suppressRecipeDefinitionPackets = optionalStrictBoolean(
                    "network-compatibility.suppress-recipe-definition-packets", true);
            boolean cancelGenericUiEvents = optionalStrictBoolean(
                    "safety.cancel-generic-ui-events", false);
            PaperNecroTempusConfig necroTempus = PaperNecroTempusConfig.from(getConfig());
            double voidRescueY = safety.getDouble("void-rescue-y", Double.NaN);
            if (!Double.isFinite(voidRescueY)
                    || voidRescueY < -2_048.0D
                    || voidRescueY > 2_048.0D) {
                throw new IllegalArgumentException(
                        "safety.void-rescue-y must be finite and from -2048 to 2048");
            }

            LobbyConfig loaded = new LobbyConfig(
                    debug,
                    worldName,
                    safety.getBoolean("protect-entire-server", true),
                    cancelGenericUiEvents,
                    safety.getBoolean("teleport-to-world-spawn-on-join", true),
                    safety.getBoolean("clear-inventory-on-join", false),
                    safety.getBoolean("clear-active-effects-on-join", true),
                    voidRescueY,
                    suppressRecipeBookPackets,
                    suppressRecipeDefinitionPackets,
                    necroTempus);
            lobbyConfig = loaded;
            setDebugLogging(loaded.debug());
            return true;
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE,
                    "Invalid ProtocolObelisk lobby configuration: "
                            + exception.getMessage(), exception);
            return false;
        }
    }

    private void setDebugLogging(boolean enabled) {
        DiagnosticLog diagnostics = diagnosticLog;
        if (diagnostics != null) {
            diagnostics.setEnabled(enabled);
            if (movementDiagnostics != null) {
                movementDiagnostics.updateSampling(poll -> {
                    var task = getServer().getScheduler().runTaskTimer(this, poll, 20L, 20L);
                    return task::cancel;
                }, uuid -> {
                    Player player = getServer().getPlayer(uuid);
                    return player != null && player.isOnline() && isLobbyScope(player)
                            ? new PaperMovementDiagnostics.SamplePlayer(player.getName(), player) : null;
                });
            }
        }
    }

    private ConfigurationSection requireSection(String path) {
        ConfigurationSection section = getConfig().getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("missing configuration section: " + path);
        }
        return section;
    }

    private PluginCommand requireCommand(String name) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("command missing from plugin.yml: " + name);
        }
        return command;
    }

    private boolean optionalStrictBoolean(String path, boolean fallback) {
        return parseOptionalStrictBoolean(getConfig().get(path), path, fallback);
    }

    static boolean parseOptionalStrictBoolean(Object value, String path, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
        return bool;
    }

    private static String requireText(String value, String path) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.getBytes(StandardCharsets.UTF_8).length > 192
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid configuration text: " + path);
        }
        return value;
    }

    private record LobbyConfig(
            boolean debug,
            String lobbyWorld,
            boolean protectEntireServer,
            boolean cancelGenericUiEvents,
            boolean teleportToSpawn,
            boolean clearInventory,
            boolean clearEffects,
            double voidRescueY,
            boolean suppressRecipeBookPackets,
            boolean suppressRecipeDefinitionPackets,
            PaperNecroTempusConfig necroTempus) {

        private boolean suppressesAnyRecipePackets() {
            return suppressRecipeBookPackets || suppressRecipeDefinitionPackets;
        }

        private boolean hasSameNetworkPolicy(LobbyConfig other) {
            return other != null
                    && suppressRecipeBookPackets == other.suppressRecipeBookPackets
                    && suppressRecipeDefinitionPackets == other.suppressRecipeDefinitionPackets;
        }
    }
}
