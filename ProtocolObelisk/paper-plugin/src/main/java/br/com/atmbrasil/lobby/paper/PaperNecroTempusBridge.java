package br.com.atmbrasil.lobby.paper;

import br.com.atmbrasil.protocolobelisk.necro.protocol.BridgeProtocol;
import br.com.atmbrasil.protocolobelisk.necro.protocol.LegacyTextSanitizer;
import br.com.atmbrasil.protocolobelisk.necro.protocol.NecroTempusPacketCodec;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

/**
 * Optional Paper-side TAB transport for validated NecroTempus 1.7.10 sessions.
 *
 * <p>Velocity remains the capability authority. This component never guesses client identity: it
 * sends TAB state only after receiving a bounded, epoch-bound confirmation from Velocity.</p>
 */
final class PaperNecroTempusBridge
        implements Listener, PluginMessageListener, AutoCloseable {

    private static final String STANDALONE_PLUGIN_NAME = "ProtocolObeliskNecroTempus";

    private final JavaPlugin plugin;
    private final Logger operationalLog;
    private final DiagnosticLog diagnosticLog;
    private final Map<UUID, BackendSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sentSnapshots = new AtomicLong();
    private final AtomicLong duplicateSnapshots = new AtomicLong();
    private final AtomicLong rejectedSnapshots = new AtomicLong();

    private volatile PaperNecroTempusConfig config = PaperNecroTempusConfig.defaults();
    private volatile TabStateAccessor tabStateAccessor;
    private volatile BukkitTask refreshTask;
    private volatile boolean active;
    private volatile String state = "not initialized";

    PaperNecroTempusBridge(
            JavaPlugin plugin,
            Logger operationalLog,
            DiagnosticLog diagnosticLog) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.operationalLog = Objects.requireNonNull(operationalLog, "operationalLog");
        this.diagnosticLog = Objects.requireNonNull(diagnosticLog, "diagnosticLog");
    }

    synchronized void start(PaperNecroTempusConfig requestedConfig) {
        Objects.requireNonNull(requestedConfig, "requestedConfig");
        if (active) {
            throw new IllegalStateException("NecroTempus Paper component is already active");
        }
        config = requestedConfig;
        if (!requestedConfig.enabled()) {
            state = "disabled by configuration";
            diagnosticLog.info(() -> "NecroTempus TAB subsystem is disabled by configuration");
            return;
        }

        Plugin standalone = findStandalonePlugin();
        if (standalone != null) {
            state = "standalone companion conflict";
            operationalLog.severe(
                    "Integrated NecroTempus TAB transport was not started because the standalone "
                            + STANDALONE_PLUGIN_NAME
                            + " JAR is installed; remove the standalone Paper JAR and restart. "
                            + "The ProtocolObelisk lobby core remains active.");
            return;
        }

        Plugin tabPlugin = plugin.getServer().getPluginManager().getPlugin("TAB");
        if (tabPlugin == null || !tabPlugin.isEnabled()) {
            state = "TAB unavailable";
            operationalLog.warning(
                    "TAB was not found or is not enabled; only the optional NecroTempus TAB "
                            + "subsystem is disabled. ProtocolObelisk lobby readiness and recipe "
                            + "guards remain active.");
            return;
        }

        final TabStateAccessor resolvedAccessor;
        try {
            resolvedAccessor = TabStateAccessor.resolve(tabPlugin.getClass().getClassLoader());
        } catch (LinkageError | ReflectiveOperationException | SecurityException exception) {
            state = "unsupported TAB tracked-state API";
            operationalLog.log(
                    Level.WARNING,
                    "TAB tracked header/footer state is unavailable; only the optional "
                            + "NecroTempus TAB subsystem is disabled. ProtocolObelisk will not "
                            + "fall back to stale Bukkit header/footer getters.",
                    exception);
            return;
        }

        tabStateAccessor = resolvedAccessor;
        try {
            registerMessagingAndEvents();
            restartRefreshTask();
            active = true;
            state = "active";
        } catch (RuntimeException exception) {
            rollbackRegistrations();
            tabStateAccessor = null;
            active = false;
            state = "Paper registration failed";
            operationalLog.log(
                    Level.SEVERE,
                    "Could not start the optional NecroTempus TAB subsystem; the "
                            + "ProtocolObelisk lobby core remains active",
                    exception);
            return;
        }

        try {
            plugin.getServer().getScheduler().runTask(plugin, this::requestCapabilitiesForOnlinePlayers);
        } catch (RuntimeException exception) {
            operationalLog.log(
                    Level.WARNING,
                    "Could not schedule the initial NecroTempus capability scan; joining players "
                            + "will still be probed normally",
                    exception);
        }
        operationalLog.info(
                "Integrated NecroTempus TAB transport enabled; source=TAB tracked state; "
                        + "refreshTicks=" + requestedConfig.refreshTicks()
                        + "; maximumCompressedNbtBytes="
                        + requestedConfig.maximumCompressedNbtBytes()
                        + "; TAB remains backend-owned");
    }

    synchronized void reconfigure(PaperNecroTempusConfig requestedConfig) {
        Objects.requireNonNull(requestedConfig, "requestedConfig");
        if (!requestedConfig.enabled()) {
            if (active) {
                stop(true, "disabled by configuration");
            }
            config = requestedConfig;
            state = "disabled by configuration";
            return;
        }
        if (!active) {
            start(requestedConfig);
            return;
        }

        config = requestedConfig;
        try {
            restartRefreshTask();
        } catch (RuntimeException exception) {
            operationalLog.log(
                    Level.SEVERE,
                    "Could not reschedule the NecroTempus TAB refresh task; only that optional "
                            + "subsystem is being stopped",
                    exception);
            stop(true, "refresh task scheduling failed");
            return;
        }
        sessions.values().forEach(BackendSession::invalidateSnapshot);
        requestCapabilitiesForOnlinePlayers();
        refreshAll();
    }

    @Override
    public synchronized void close() {
        stop(true, "stopped");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!active || !BridgeProtocol.CAPABILITY_CHANNEL.equals(channel)) {
            return;
        }
        try {
            BridgeProtocol.Capability capability = BridgeProtocol.decode(message);
            if (!capability.playerId().equals(player.getUniqueId())) {
                throw new BridgeProtocol.ProtocolException(
                        "capability UUID does not match Bukkit player");
            }
            if (capability.operation() == BridgeProtocol.Operation.CLEAR) {
                sessions.computeIfPresent(player.getUniqueId(), (ignored, session) ->
                        session.belongsTo(player)
                                        && session.connectionEpoch() == capability.connectionEpoch()
                                ? null
                                : session);
                diagnosticLog.info(() ->
                        "Cleared NecroTempus capability for " + player.getName());
                return;
            }
            if (capability.operation() != BridgeProtocol.Operation.CONFIRM) {
                throw new BridgeProtocol.ProtocolException(
                        "backend received a non-confirm capability response");
            }

            BackendSession existing = sessions.get(player.getUniqueId());
            if (existing != null
                    && existing.belongsTo(player)
                    && existing.connectionEpoch() == capability.connectionEpoch()) {
                if (existing.updateModVersion(capability.necroTempusVersion())) {
                    diagnosticLog.info(() ->
                            "Updated NecroTempus capability for " + player.getName()
                                    + "; version=" + capability.necroTempusVersion()
                                    + "; epoch="
                                    + Long.toUnsignedString(capability.connectionEpoch()));
                }
                return;
            }

            BackendSession replacement = new BackendSession(
                    player,
                    player.getUniqueId(),
                    capability.connectionEpoch(),
                    capability.necroTempusVersion());
            sessions.put(player.getUniqueId(), replacement);
            diagnosticLog.info(() ->
                    "Confirmed NecroTempus capability for " + player.getName()
                            + "; version=" + capability.necroTempusVersion()
                            + "; epoch="
                            + Long.toUnsignedString(capability.connectionEpoch()));
            plugin.getServer().getScheduler().runTask(plugin, () -> refreshPlayer(player));
        } catch (BridgeProtocol.ProtocolException exception) {
            operationalLog.warning(
                    "Rejected malformed NecroTempus capability for " + player.getName()
                            + ": " + exception.getMessage());
        } catch (RuntimeException exception) {
            operationalLog.log(
                    Level.WARNING,
                    "Could not process the NecroTempus capability for " + player.getName(),
                    exception);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> requestCapability(player));
        } catch (RuntimeException exception) {
            operationalLog.log(
                    Level.WARNING,
                    "Could not schedule the NecroTempus capability request for "
                            + player.getName(),
                    exception);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sessions.computeIfPresent(player.getUniqueId(), (ignored, session) ->
                session.belongsTo(player) ? null : session);
    }

    Status status() {
        return new Status(
                config.enabled(),
                active,
                state,
                sessions.size(),
                sentSnapshots.get(),
                duplicateSnapshots.get(),
                rejectedSnapshots.get());
    }

    private Plugin findStandalonePlugin() {
        for (Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
            if (installed != plugin
                    && installed.getName().equalsIgnoreCase(STANDALONE_PLUGIN_NAME)) {
                return installed;
            }
        }
        return null;
    }

    private void registerMessagingAndEvents() {
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(
                plugin, BridgeProtocol.CAPABILITY_CHANNEL, this);
        messenger.registerOutgoingPluginChannel(plugin, BridgeProtocol.CAPABILITY_CHANNEL);
        messenger.registerOutgoingPluginChannel(plugin, NecroTempusPacketCodec.CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void rollbackRegistrations() {
        try {
            HandlerList.unregisterAll(this);
        } catch (RuntimeException ignored) {
            // Paper shutdown may invalidate handler registries before plugin cleanup runs.
        }
        final Messenger messenger;
        try {
            messenger = plugin.getServer().getMessenger();
        } catch (RuntimeException ignored) {
            // The server may already have torn down its messenger during shutdown.
            return;
        }
        try {
            messenger.unregisterIncomingPluginChannel(
                    plugin, BridgeProtocol.CAPABILITY_CHANNEL, this);
        } catch (RuntimeException ignored) {
            // A failed partial registration may not have created this channel binding.
        }
        try {
            messenger.unregisterOutgoingPluginChannel(plugin, BridgeProtocol.CAPABILITY_CHANNEL);
        } catch (RuntimeException ignored) {
            // A failed partial registration may not have created this channel binding.
        }
        try {
            messenger.unregisterOutgoingPluginChannel(plugin, NecroTempusPacketCodec.CHANNEL);
        } catch (RuntimeException ignored) {
            // A failed partial registration may not have created this channel binding.
        }
    }

    private void stop(boolean sendRemove, String finalState) {
        BukkitTask task = refreshTask;
        refreshTask = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException exception) {
                operationalLog.log(
                        Level.WARNING,
                        "Could not cancel the NecroTempus TAB refresh task cleanly",
                        exception);
            }
        }
        if (active && sendRemove && config.sendRemoveOnDisable()) {
            sendShutdownRemove();
        }
        active = false;
        sessions.clear();
        tabStateAccessor = null;
        rollbackRegistrations();
        state = finalState;
    }

    private void sendShutdownRemove() {
        final byte[] remove;
        try {
            remove = NecroTempusPacketCodec.encodePlayerTabRemove(
                    config.maximumCompressedNbtBytes());
        } catch (NecroTempusPacketCodec.CodecException exception) {
            operationalLog.warning(
                    "Could not encode the shutdown NecroTempus TAB removal: "
                            + exception.getMessage());
            return;
        }
        for (BackendSession session : sessions.values()) {
            Player player = session.owner();
            if (!player.isOnline()) {
                continue;
            }
            try {
                player.sendPluginMessage(plugin, NecroTempusPacketCodec.CHANNEL, remove);
            } catch (RuntimeException exception) {
                operationalLog.log(
                        Level.WARNING,
                        "Could not send the shutdown NecroTempus TAB removal to "
                                + player.getName(),
                        exception);
            }
        }
    }

    private void requestCapabilitiesForOnlinePlayers() {
        if (!active || !config.enabled()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            requestCapability(player);
        }
    }

    private void requestCapability(Player player) {
        if (!active || !config.enabled() || !player.isOnline()) {
            return;
        }
        try {
            byte[] request = BridgeProtocol.encode(
                    BridgeProtocol.Capability.requested(player.getUniqueId()));
            player.sendPluginMessage(plugin, BridgeProtocol.CAPABILITY_CHANNEL, request);
            diagnosticLog.info(() ->
                    "Requested NecroTempus capability for " + player.getName());
        } catch (RuntimeException exception) {
            operationalLog.log(
                    Level.WARNING,
                    "Could not request NecroTempus capability for " + player.getName(),
                    exception);
        }
    }

    private void restartRefreshTask() {
        BukkitTask previous = refreshTask;
        if (previous != null) {
            previous.cancel();
        }
        long period = config.refreshTicks();
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshAll, period, period);
    }

    private void refreshAll() {
        if (!active || !config.enabled()) {
            return;
        }
        for (BackendSession session : sessions.values()) {
            Player player = session.owner();
            if (!player.isOnline()) {
                sessions.remove(session.playerId(), session);
                continue;
            }
            refreshPlayer(player);
        }
    }

    private void refreshPlayer(Player player) {
        PaperNecroTempusConfig currentConfig = config;
        BackendSession session = sessions.get(player.getUniqueId());
        if (!active
                || !currentConfig.enabled()
                || session == null
                || !session.belongsTo(player)
                || !player.isOnline()) {
            return;
        }
        TabStateAccessor accessor = tabStateAccessor;
        if (accessor == null) {
            return;
        }

        final Optional<TabStateAccessor.State> trackedState;
        try {
            trackedState = accessor.read(player.getUniqueId());
        } catch (TabStateAccessor.AccessException exception) {
            rejectedSnapshots.incrementAndGet();
            if (session.markRejected(exception.getMessage())) {
                operationalLog.warning(
                        "Could not read TAB tracked state for " + player.getName()
                                + ": " + exception.getMessage());
            }
            return;
        }
        if (trackedState.isEmpty()) {
            return;
        }

        final String header;
        final String footer;
        try {
            TabStateAccessor.State source = trackedState.orElseThrow();
            header = LegacyTextSanitizer.normalize(
                    source.header(),
                    currentConfig.maximumTextBytes(),
                    currentConfig.maximumLines());
            footer = LegacyTextSanitizer.normalize(
                    source.footer(),
                    currentConfig.maximumTextBytes(),
                    currentConfig.maximumLines());
        } catch (IllegalArgumentException exception) {
            rejectedSnapshots.incrementAndGet();
            String reason = "TAB legacy-text normalization failed: " + exception.getMessage();
            if (session.markRejected(reason)) {
                operationalLog.log(
                        Level.WARNING,
                        "Could not normalize the TAB snapshot for " + player.getName(),
                        exception);
            }
            return;
        }
        Snapshot candidate = new Snapshot(header, footer);
        Snapshot previous = session.lastSnapshot();
        if (candidate.equals(previous)) {
            duplicateSnapshots.incrementAndGet();
            session.markDuplicate();
            return;
        }

        try {
            byte[] payload = candidate.empty()
                    ? NecroTempusPacketCodec.encodePlayerTabRemove(
                            currentConfig.maximumCompressedNbtBytes())
                    : NecroTempusPacketCodec.encodePlayerTabSet(
                            header,
                            footer,
                            currentConfig.maximumCompressedNbtBytes());
            player.sendPluginMessage(plugin, NecroTempusPacketCodec.CHANNEL, payload);
            session.setLastSnapshot(candidate, payload.length);
            sentSnapshots.incrementAndGet();
            diagnosticLog.info(() ->
                    "Sent NecroTempus TAB snapshot to " + player.getName()
                            + "; bytes=" + payload.length
                            + "; headerChars=" + header.length()
                            + "; footerChars=" + footer.length());
        } catch (NecroTempusPacketCodec.CodecException exception) {
            rejectedSnapshots.incrementAndGet();
            if (session.markRejected(exception.getMessage())) {
                operationalLog.warning(
                        "Rejected oversized or invalid TAB snapshot for " + player.getName()
                                + ": " + exception.getMessage());
            }
        } catch (RuntimeException exception) {
            rejectedSnapshots.incrementAndGet();
            String reason = "plugin-message send failed: "
                    + exception.getClass().getSimpleName()
                    + safeSuffix(exception.getMessage());
            if (session.markRejected(reason)) {
                operationalLog.log(
                        Level.WARNING,
                        "Could not send the NecroTempus TAB snapshot to " + player.getName(),
                        exception);
            }
        }
    }

    private static String safeSuffix(String message) {
        return message == null ? "" : ": " + message;
    }

    record Status(
            boolean configuredEnabled,
            boolean active,
            String state,
            int confirmedSessions,
            long sentSnapshots,
            long duplicateSnapshots,
            long rejectedSnapshots) {
    }

    private static final class BackendSession {
        private final Player owner;
        private final UUID playerId;
        private final long connectionEpoch;
        private final AtomicLong duplicates = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private volatile String modVersion;
        private volatile Snapshot lastSnapshot;
        private volatile int lastPayloadBytes;
        private volatile String lastFailure = "";

        private BackendSession(
                Player owner,
                UUID playerId,
                long connectionEpoch,
                String modVersion) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.connectionEpoch = connectionEpoch;
            this.modVersion = Objects.requireNonNull(modVersion, "modVersion");
        }

        Player owner() {
            return owner;
        }

        UUID playerId() {
            return playerId;
        }

        boolean belongsTo(Player player) {
            return owner == player;
        }

        long connectionEpoch() {
            return connectionEpoch;
        }

        synchronized boolean updateModVersion(String candidate) {
            Objects.requireNonNull(candidate, "candidate");
            if (candidate.equals(modVersion)) {
                return false;
            }
            modVersion = candidate;
            return true;
        }

        Snapshot lastSnapshot() {
            return lastSnapshot;
        }

        void setLastSnapshot(Snapshot snapshot, int payloadBytes) {
            lastSnapshot = Objects.requireNonNull(snapshot, "snapshot");
            lastPayloadBytes = payloadBytes;
            lastFailure = "";
        }

        void invalidateSnapshot() {
            lastSnapshot = null;
            lastPayloadBytes = 0;
        }

        void markDuplicate() {
            duplicates.incrementAndGet();
        }

        synchronized boolean markRejected(String reason) {
            rejected.incrementAndGet();
            boolean changed = !lastFailure.equals(reason);
            lastFailure = reason;
            return changed;
        }
    }

    private record Snapshot(String header, String footer) {
        private Snapshot {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(footer, "footer");
        }

        boolean empty() {
            return header.isEmpty() && footer.isEmpty();
        }
    }
}
