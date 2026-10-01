package br.com.atmbrasil.lobby.velocity.necro;

import br.com.atmbrasil.protocolobelisk.necro.protocol.BridgeProtocol;
import br.com.atmbrasil.protocolobelisk.necro.protocol.LegacyTextSanitizer;
import br.com.atmbrasil.protocolobelisk.necro.protocol.NecroTempusPacketCodec;
import br.com.atmbrasil.protocolobelisk.necro.protocol.NecroTempusPacketCodec.CodecException;
import br.com.atmbrasil.protocolobelisk.necro.protocol.TabBridgeProtocol;
import br.com.atmbrasil.protocolobelisk.necro.protocol.WindowRateLimiter;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerModInfoEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.ModInfo;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;

/**
 * Embedded Velocity-side NecroTempus transport.
 *
 * <p>This type deliberately has no Velocity listener or plugin annotations. The owning plugin
 * delegates events to it, which gives one deterministic maximum-priority plugin-message path
 * instead of two competing listeners. TAB rendering remains backend-owned; this service only
 * validates, fences and relays direct packets or rebuilds the private TAB envelope.</p>
 */
public final class NecroTempusTransportService {
    private static final LegacyChannelIdentifier NECROTEMPUS =
            new LegacyChannelIdentifier(NecroTempusPacketCodec.CHANNEL);
    private static final LegacyChannelIdentifier LEGACY_REGISTER =
            new LegacyChannelIdentifier("REGISTER");
    private static final LegacyChannelIdentifier TAB_BRIDGE =
            new LegacyChannelIdentifier(TabBridgeProtocol.CHANNEL);
    private static final byte[] NECROTEMPUS_REGISTRATION =
            NecroTempusPacketCodec.CHANNEL.getBytes(StandardCharsets.UTF_8);
    private static final long CHANNEL_REASSERT_NANOS = 5_000_000_000L;
    private static final MinecraftChannelIdentifier CAPABILITY =
            MinecraftChannelIdentifier.from(BridgeProtocol.CAPABILITY_CHANNEL);
    private static final String NECROTEMPUS_MOD_ID = "necrotempus";
    private static final int SESSION_LIFECYCLE_LOCK_STRIPES = 64;

    private final ProxyServer server;
    private final Logger operationalLogger;
    private final NecroTempusDiagnostics diagnostics;
    private final NecroTempusTransportConfig config;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, ClientSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock[] sessionLifecycleLocks = createSessionLifecycleLocks();
    private final AtomicLong confirmedClients = new AtomicLong();
    private final AtomicLong clientboundPackets = new AtomicLong();
    private final AtomicLong droppedPackets = new AtomicLong();
    private final AtomicLong suppressedDirectTabPackets = new AtomicLong();
    private volatile boolean initialized;

    public NecroTempusTransportService(
            ProxyServer server,
            Logger operationalLogger,
            NecroTempusDiagnostics diagnostics,
            NecroTempusTransportConfig config) {
        this.server = Objects.requireNonNull(server, "server");
        this.operationalLogger = Objects.requireNonNull(
                operationalLogger, "operationalLogger");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Registers the three owned channels when enabled. Repeated or disabled calls are harmless. */
    public synchronized void initialize() {
        if (initialized || !config.enabled()) {
            return;
        }
        try {
            server.getChannelRegistrar().register(NECROTEMPUS, CAPABILITY, TAB_BRIDGE);
            operationalLogger.info(
                    "ProtocolObelisk NecroTempus transport loaded: enabled={}, channel={}, "
                            + "tabBridge={}, maxCompressedNbt={} bytes, limits={}/{} per second; "
                            + "TAB rendering remains backend-owned",
                    config.enabled(),
                    NecroTempusPacketCodec.CHANNEL,
                    TabBridgeProtocol.CHANNEL,
                    config.maximumCompressedNbtBytes(),
                    config.maximumPacketsPerSecond(),
                    config.maximumBytesPerSecond());
        } catch (RuntimeException initializationFailure) {
            try {
                server.getChannelRegistrar().unregister(NECROTEMPUS, CAPABILITY, TAB_BRIDGE);
            } catch (RuntimeException rollbackFailure) {
                initializationFailure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException(
                    "Could not initialize the embedded NecroTempus transport",
                    initializationFailure);
        }
        initialized = true;
    }

    /** Unregisters owned channels and invalidates all connection-scoped state. */
    public synchronized void shutdown() {
        if (!initialized) {
            return;
        }
        initialized = false;
        try {
            server.getChannelRegistrar().unregister(NECROTEMPUS, CAPABILITY, TAB_BRIDGE);
        } finally {
            sessions.clear();
            confirmedClients.set(0L);
            clientboundPackets.set(0L);
            droppedPackets.set(0L);
            suppressedDirectTabPackets.set(0L);
        }
    }

    public boolean initialized() {
        return initialized;
    }

    public Metrics metrics() {
        return new Metrics(
                initialized,
                sessions.size(),
                confirmedClients.get(),
                clientboundPackets.get(),
                droppedPackets.get(),
                suppressedDirectTabPackets.get());
    }

    /**
     * Primary legacy-Forge capability source. The FML mod list is part of the handshake and is
     * available even on NecroTempus builds that predate the optional discriminator-0 HELLO.
     */
    public void handlePlayerModInfo(PlayerModInfoEvent event) {
        Objects.requireNonNull(event, "event");
        NecroTempusTransportConfig current = config;
        if (!initialized || !current.enabled()) {
            return;
        }
        findNecroTempusVersion(event.getModInfo()).ifPresent(version ->
                confirmClient(event.getPlayer(), version, ConfirmationEvidence.MOD_LIST,
                        current, -1));
    }

    /**
     * Independent fallback for old/custom NecroTempus builds. A client-side REGISTER for the
     * exact SimpleNetworkWrapper channel proves that the renderer is installed even when no
     * discriminator-0 HELLO exists.
     */
    public void handlePlayerChannelRegister(PlayerChannelRegisterEvent event) {
        Objects.requireNonNull(event, "event");
        NecroTempusTransportConfig current = config;
        if (!initialized || !current.enabled()
                || event.getPlayer().getProtocolVersion().getProtocol()
                        != NecroTempusPacketCodec.MINECRAFT_1_7_10_PROTOCOL) {
            return;
        }
        boolean registered = event.getChannels().stream()
                .anyMatch(channel -> NecroTempusPacketCodec.CHANNEL.equals(channel.getId()));
        if (!registered) {
            return;
        }
        Optional<String> modVersion = event.getPlayer().getModInfo()
                .flatMap(NecroTempusTransportService::findNecroTempusVersion);
        confirmClient(
                event.getPlayer(),
                modVersion.orElse(ConfirmationEvidence.CHANNEL_REGISTER.fallbackVersion()),
                modVersion.isPresent()
                        ? ConfirmationEvidence.MOD_LIST
                        : ConfirmationEvidence.CHANNEL_REGISTER,
                current,
                -1);
    }

    /**
     * Handles an owned channel and returns {@code true}; returns {@code false} for every other
     * channel. The root maximum-priority synchronous listener must call this first and return
     * immediately when it reports ownership.
     */
    public boolean handlePluginMessage(PluginMessageEvent event) {
        Objects.requireNonNull(event, "event");
        if (!initialized) {
            return false;
        }
        String channel = event.getIdentifier().getId();
        Player player = event.getSource() instanceof Player sourcePlayer
                ? sourcePlayer
                : event.getTarget() instanceof Player targetPlayer ? targetPlayer : null;
        if (channel.equals(BridgeProtocol.CAPABILITY_CHANNEL)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            NecroTempusTransportConfig current = config;
            if (current.enabled()
                    && event.getSource() instanceof ServerConnection backend
                    && player != null) {
                receiveCapabilityRequest(backend, player, event.getData(), current);
            } else {
                diagnose("Blocked externally supplied capability-plane payload: source={}, bytes={}",
                        endpointName(event.getSource()), event.getData().length);
            }
            return true;
        }
        if (channel.equals(TabBridgeProtocol.CHANNEL)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            NecroTempusTransportConfig current = config;
            if (current.enabled()
                    && event.getSource() instanceof ServerConnection backend
                    && player != null) {
                receiveTabBridgePayload(backend, player, event.getData(), current);
            } else {
                droppedPackets.incrementAndGet();
                diagnose(
                        "Blocked externally supplied TAB bridge payload: source={}, target={}, bytes={}",
                        endpointName(event.getSource()), endpointName(event.getTarget()),
                        event.getData().length);
            }
            return true;
        }
        if (!channel.equals(NecroTempusPacketCodec.CHANNEL)) {
            return false;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
        NecroTempusTransportConfig current = config;
        if (!current.enabled()) {
            return true;
        }

        if (event.getSource() instanceof Player sourcePlayer) {
            receiveClientPayload(sourcePlayer, event.getData(), current);
            return true;
        }
        if (event.getSource() instanceof ServerConnection backend
                && player != null) {
            receiveBackendPayload(backend, player, event.getData(), current);
            return true;
        }
        droppedPackets.incrementAndGet();
        diagnose("Dropped NecroTempus payload with unsupported endpoints: source={}, target={}",
                endpointName(event.getSource()), endpointName(event.getTarget()));
        return true;
    }

    public void handleServerPreConnect(ServerPreConnectEvent event) {
        Objects.requireNonNull(event, "event");
        if (!initialized || !config.enabled()) {
            return;
        }
        ClientSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || !session.belongsTo(event.getPlayer())) {
            return;
        }
        String current = Optional.ofNullable(event.getPreviousServer())
                .map(server -> server.getServerInfo().getName())
                .orElseGet(() -> event.getPlayer().getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(""));
        String target = event.getResult().getServer()
                .map(server -> server.getServerInfo().getName())
                .orElse("");
        if (target.isEmpty() || target.equals(current)) {
            session.clearBackendTransition();
        } else {
            session.beginBackendTransition(target);
        }
    }

    public void handleServerPostConnect(ServerPostConnectEvent event) {
        Objects.requireNonNull(event, "event");
        if (!initialized) {
            return;
        }
        Player player = event.getPlayer();
        NecroTempusTransportConfig current = config;
        if (current.enabled()) {
            player.getModInfo()
                    .flatMap(NecroTempusTransportService::findNecroTempusVersion)
                    .ifPresent(version -> confirmClient(
                            player, version, ConfirmationEvidence.MOD_LIST, current, -1));
        }
        ClientSession session = sessions.get(player.getUniqueId());
        if (session != null && session.belongsTo(player) && session.confirmed()) {
            session.beginBackendCycle();
            ensureClientChannelAdvertised(player, session, true);
            advertiseCapability(player, session, true);
        }
    }

    public void handleDisconnect(DisconnectEvent event) {
        Objects.requireNonNull(event, "event");
        UUID playerId = event.getPlayer().getUniqueId();
        ReentrantLock lifecycleLock = sessionLifecycleLock(playerId);
        AtomicReference<ClientSession> removedReference = new AtomicReference<>();
        lifecycleLock.lock();
        try {
            sessions.compute(playerId, (ignored, current) -> {
                if (current != null && current.belongsTo(event.getPlayer())) {
                    if (current.deactivateAndClearConfirmationCounted()) {
                        confirmedClients.decrementAndGet();
                    }
                    removedReference.set(current);
                    return null;
                }
                return current;
            });
        } finally {
            lifecycleLock.unlock();
        }
        ClientSession removed = removedReference.get();
        if (removed != null) {
            diagnose("Cleared NecroTempus session for {}: version={}, evidence={}, sent={}, dropped={}",
                    event.getPlayer().getUsername(), removed.modVersion(),
                    removed.evidence().wireName(), removed.forwardedPackets(),
                    removed.droppedPackets());
        }
    }

    private void receiveClientPayload(
            Player player,
            byte[] payload,
            NecroTempusTransportConfig current) {
        if (player.getProtocolVersion().getProtocol()
                != NecroTempusPacketCodec.MINECRAFT_1_7_10_PROTOCOL) {
            droppedPackets.incrementAndGet();
            diagnose("Dropped NecroTempus HELLO from {} on unsupported protocol {}",
                    player.getUsername(), player.getProtocolVersion().getProtocol());
            return;
        }
        try {
            NecroTempusPacketCodec.Hello hello = NecroTempusPacketCodec.decodeClientHello(
                    payload,
                    Math.min(current.maximumCompressedNbtBytes(), 4096),
                    Math.min(current.maximumDecompressedNbtBytes(), 64 * 1024));
            confirmClient(player, hello.version(), ConfirmationEvidence.HELLO, current,
                    hello.compressedBytes());
        } catch (CodecException exception) {
            droppedPackets.incrementAndGet();
            diagnose("Dropped malformed NecroTempus client payload from {}: {}",
                    player.getUsername(), exception.getMessage());
        }
    }

    private void receiveCapabilityRequest(
            ServerConnection backend,
            Player player,
            byte[] payload,
            NecroTempusTransportConfig current) {
        try {
            BridgeProtocol.Capability request = BridgeProtocol.decode(payload);
            if (request.operation() != BridgeProtocol.Operation.REQUEST) {
                throw new BridgeProtocol.ProtocolException(
                        "backend capability-plane message is not a request");
            }
            if (!request.playerId().equals(player.getUniqueId())) {
                throw new BridgeProtocol.ProtocolException(
                        "capability request UUID does not match the connected player");
            }
            ClientSession session = sessions.get(player.getUniqueId());
            if (session == null || !session.belongsTo(player) || !session.confirmed()) {
                Optional<String> version = player.getModInfo()
                        .flatMap(NecroTempusTransportService::findNecroTempusVersion);
                if (version.isPresent()) {
                    session = confirmClient(player, version.get(),
                            ConfirmationEvidence.MOD_LIST, current, -1);
                }
            }
            if (session == null || !session.belongsTo(player) || !session.confirmed()) {
                diagnose("Ignored capability request for unconfirmed client {} from {}",
                        player.getUsername(), backend.getServerInfo().getName());
                return;
            }

            String sourceBackend = backend.getServerInfo().getName();
            String activeBackend = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse("");
            if (!session.authorizesBackend(sourceBackend, activeBackend)) {
                session.markDropped();
                droppedPackets.incrementAndGet();
                diagnose("Dropped stale-backend capability request for {} from {}",
                        player.getUsername(), sourceBackend);
                return;
            }
            if (!session.allowCapabilityRequest(sourceBackend, System.nanoTime())) {
                diagnose("Suppressed duplicate capability request for {} from {}",
                        player.getUsername(), sourceBackend);
                return;
            }

            byte[] response = BridgeProtocol.encode(BridgeProtocol.Capability.confirmed(
                    session.connectionEpoch(), player.getUniqueId(), session.modVersion()));
            if (backend.sendPluginMessage(CAPABILITY, response)) {
                session.markCapabilityAdvertised(sourceBackend);
                diagnose("Answered backend capability request: player={}, backend={}, epoch={}",
                        player.getUsername(), sourceBackend,
                        Long.toUnsignedString(session.connectionEpoch()));
            } else {
                operationalLogger.warn("Backend {} refused a requested NecroTempus capability for {}",
                        sourceBackend, player.getUsername());
            }
        } catch (BridgeProtocol.ProtocolException | IllegalArgumentException exception) {
            droppedPackets.incrementAndGet();
            diagnose("Dropped malformed capability request for {} from {}: {}",
                    player.getUsername(), backend.getServerInfo().getName(),
                    exception.getMessage());
        } catch (RuntimeException exception) {
            droppedPackets.incrementAndGet();
            operationalLogger.warn("Could not answer capability request for {} from {}: {}: {}",
                    player.getUsername(), backend.getServerInfo().getName(),
                    exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    private ClientSession confirmClient(
            Player player,
            String rawVersion,
            ConfirmationEvidence evidence,
            NecroTempusTransportConfig current,
            int compressedHelloBytes) {
        if (player.getProtocolVersion().getProtocol()
                != NecroTempusPacketCodec.MINECRAFT_1_7_10_PROTOCOL) {
            return null;
        }
        UUID playerId = player.getUniqueId();
        ReentrantLock lifecycleLock = sessionLifecycleLock(playerId);
        lifecycleLock.lock();
        try {
            if (!player.isActive()) {
                return null;
            }
            String version = normalizeVersion(rawVersion, evidence);
            ClientSession candidate = new ClientSession(
                    player,
                    nonZeroEpoch(),
                    version,
                    evidence,
                    new WindowRateLimiter(
                            current.maximumPacketsPerSecond(),
                            current.maximumBytesPerSecond()));
            AtomicReference<ConfirmationTransition> transitionReference =
                    new AtomicReference<>();
            sessions.compute(playerId, (ignored, existing) -> {
                ClientSession selected = existing;
                boolean replacement = selected == null
                        || !selected.belongsTo(player)
                        || !selected.active();
                boolean displacedCounted = false;
                if (replacement) {
                    if (selected != null) {
                        displacedCounted = selected.deactivateAndClearConfirmationCounted();
                    }
                    selected = candidate;
                }

                EvidenceMerge merge = replacement
                        ? EvidenceMerge.UNCHANGED
                        : selected.mergeEvidence(version, evidence);
                boolean newlyConfirmed = selected.markConfirmationCounted();
                long confirmedDelta = (newlyConfirmed ? 1L : 0L)
                        - (displacedCounted ? 1L : 0L);
                if (confirmedDelta != 0L) {
                    confirmedClients.addAndGet(confirmedDelta);
                }
                if (merge == EvidenceMerge.CONFLICT) {
                    selected.markDropped();
                    droppedPackets.incrementAndGet();
                }
                transitionReference.set(new ConfirmationTransition(
                        selected,
                        newlyConfirmed,
                        replacement,
                        merge));
                return selected;
            });

            ConfirmationTransition transition = transitionReference.get();
            if (transition == null) {
                return null;
            }
            ClientSession session = transition.session();
            if (transition.newlyConfirmed()) {
                diagnose(
                        "Confirmed NecroTempus client {}: version={}, protocol={}, evidence={}{}",
                        player.getUsername(), session.modVersion(),
                        player.getProtocolVersion().getProtocol(), session.evidence().wireName(),
                        compressedHelloBytes >= 0
                                ? ", compressedHello=" + compressedHelloBytes + " bytes"
                                : "");
            } else if (transition.merge() == EvidenceMerge.UPGRADED) {
                diagnose(
                        "Refined NecroTempus capability for {}: version={}, evidence={}, epoch={}",
                        player.getUsername(), session.modVersion(), session.evidence().wireName(),
                        Long.toUnsignedString(session.connectionEpoch()));
            } else if (transition.merge() == EvidenceMerge.CONFLICT) {
                operationalLogger.warn(
                        "Ignored conflicting NecroTempus identity evidence for {}: "
                                + "confirmedVersion={}, confirmedEvidence={}, receivedVersion={}, "
                                + "receivedEvidence={}",
                        player.getUsername(), session.modVersion(), session.evidence().wireName(),
                        version, evidence.wireName());
                return session;
            }

            if (sessions.get(playerId) != session || !session.active()) {
                return null;
            }
            boolean forceAdvertisement = transition.newlyConfirmed()
                    || transition.replacement()
                    || transition.merge() == EvidenceMerge.UPGRADED;
            ensureClientChannelAdvertised(player, session, forceAdvertisement);
            if (sessions.get(playerId) == session && session.active()) {
                advertiseCapability(player, session, forceAdvertisement);
            }
            return sessions.get(playerId) == session && session.active() ? session : null;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private static Optional<String> findNecroTempusVersion(ModInfo modInfo) {
        Objects.requireNonNull(modInfo, "modInfo");
        for (ModInfo.Mod mod : modInfo.getMods()) {
            if (NECROTEMPUS_MOD_ID.equalsIgnoreCase(mod.getId())) {
                return Optional.of(normalizeVersion(
                        mod.getVersion(), ConfirmationEvidence.MOD_LIST));
            }
        }
        return Optional.empty();
    }

    private static String normalizeVersion(
            String rawVersion,
            ConfirmationEvidence evidence) {
        String fallback = evidence.fallbackVersion();
        if (rawVersion == null) {
            return fallback;
        }
        String normalized = rawVersion.trim();
        if (normalized.isEmpty()) {
            return fallback;
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                return fallback;
            }
            offset += Character.charCount(codePoint);
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length
                > NecroTempusPacketCodec.MAXIMUM_HELLO_VERSION_BYTES) {
            return fallback;
        }
        return normalized;
    }

    private void receiveTabBridgePayload(
            ServerConnection backend,
            Player player,
            byte[] envelope,
            NecroTempusTransportConfig current) {
        ClientSession session = sessions.get(player.getUniqueId());
        String sourceBackend = backend.getServerInfo().getName();
        if (session == null || !session.belongsTo(player) || !session.confirmed()) {
            droppedPackets.incrementAndGet();
            diagnose(
                    "Dropped TAB bridge envelope for unconfirmed client {} from {}",
                    player.getUsername(), sourceBackend);
            return;
        }
        String activeBackend = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");
        if (!session.authorizesBackend(sourceBackend, activeBackend)) {
            session.markDropped();
            droppedPackets.incrementAndGet();
            diagnose(
                    "Dropped stale-backend TAB bridge envelope for {} from {} "
                            + "(active={}, inFlight={})",
                    player.getUsername(), sourceBackend, activeBackend,
                    session.inFlightBackend());
            return;
        }
        if (!session.rateLimiter().tryAcquire(envelope.length)) {
            session.markDropped();
            droppedPackets.incrementAndGet();
            operationalLogger.warn(
                    "Rate-limited TAB bridge envelope for {} from {}: bytes={}",
                    player.getUsername(), sourceBackend, envelope.length);
            return;
        }

        try {
            TabBridgeProtocol.Snapshot snapshot = TabBridgeProtocol.decode(
                    envelope,
                    current.maximumTabTextBytes(),
                    current.maximumTabBridgeMessageBytes());
            if (!snapshot.playerId().equals(player.getUniqueId())) {
                throw new TabBridgeProtocol.ProtocolException(
                        "TAB bridge UUID does not match connected player");
            }
            if (snapshot.connectionEpoch() != session.connectionEpoch()) {
                throw new TabBridgeProtocol.ProtocolException(
                        "TAB bridge connection epoch does not match active session");
            }
            if (!session.acceptTabBridgeSequence(sourceBackend, snapshot.sequence())) {
                session.markDropped();
                droppedPackets.incrementAndGet();
                diagnose(
                        "Dropped replayed/out-of-order TAB bridge envelope for {} from {}: sequence={}",
                        player.getUsername(), sourceBackend, snapshot.sequence());
                return;
            }

            byte[] payload;
            if (snapshot.operation() == TabBridgeProtocol.Operation.REMOVE) {
                payload = NecroTempusPacketCodec.encodePlayerTabRemove(
                        current.maximumCompressedNbtBytes());
            } else {
                String header = LegacyTextSanitizer.normalize(
                        snapshot.header(), current.maximumTabTextBytes(), 128);
                String footer = LegacyTextSanitizer.normalize(
                        snapshot.footer(), current.maximumTabTextBytes(), 128);
                payload = NecroTempusPacketCodec.encodePlayerTabSet(
                        header, footer, current.maximumCompressedNbtBytes());
            }
            NecroTempusPacketCodec.ClientboundPacket validated =
                    NecroTempusPacketCodec.validateClientbound(
                            payload,
                            current.maximumCompressedNbtBytes(),
                            current.maximumDecompressedNbtBytes());
            ensureClientChannelAdvertised(player, session, false);
            final boolean sent;
            try {
                sent = player.sendPluginMessage(NECROTEMPUS, payload);
            } catch (RuntimeException exception) {
                session.markDropped();
                droppedPackets.incrementAndGet();
                operationalLogger.warn(
                        "Velocity threw while relaying a validated TAB snapshot for {} "
                                + "from {}: {}: {}",
                        player.getUsername(), sourceBackend,
                        exception.getClass().getSimpleName(), exception.getMessage());
                return;
            }
            if (!sent) {
                session.markDropped();
                droppedPackets.incrementAndGet();
                operationalLogger.warn(
                        "Velocity refused a validated TAB snapshot for {} from {}",
                        player.getUsername(), sourceBackend);
                return;
            }

            long relayed = session.markTabBridgeForwarded();
            session.markForwarded();
            clientboundPackets.incrementAndGet();
            if (relayed == 1L) {
                diagnose(
                        "Relayed first session-bound Crucible TAB snapshot: player={}, backend={}, "
                                + "sequence={}, discriminator={}, envelopeBytes={}, payloadBytes={}",
                        player.getUsername(), sourceBackend, snapshot.sequence(),
                        validated.discriminator(), envelope.length, payload.length);
            } else if (relayed % 100L == 0L) {
                diagnose(
                        "Relayed session-bound Crucible TAB snapshot #{}: player={}, backend={}, "
                                + "sequence={}, envelopeBytes={}, payloadBytes={}",
                        relayed, player.getUsername(), sourceBackend, snapshot.sequence(),
                        envelope.length, payload.length);
            }
        } catch (TabBridgeProtocol.ProtocolException | CodecException exception) {
            session.markDropped();
            droppedPackets.incrementAndGet();
            long malformed = session.markMalformedTabBridge();
            if (malformed <= 3L || malformed % 100L == 0L) {
                operationalLogger.warn(
                        "Dropped malformed TAB bridge envelope for {} from {}: {} "
                                + "(occurrence={})",
                        player.getUsername(), sourceBackend, exception.getMessage(), malformed);
            }
        } catch (RuntimeException exception) {
            session.markDropped();
            droppedPackets.incrementAndGet();
            operationalLogger.warn(
                    "Could not relay TAB bridge envelope for {} from {}: {}: {}",
                    player.getUsername(), sourceBackend,
                    exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    private void receiveBackendPayload(
            ServerConnection backend,
            Player player,
            byte[] payload,
            NecroTempusTransportConfig current) {
        ClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.belongsTo(player) || !session.confirmed()) {
            droppedPackets.incrementAndGet();
            diagnose("Dropped backend NecroTempus payload for unconfirmed client {} from {}",
                    player.getUsername(), backend.getServerInfo().getName());
            return;
        }
        String activeBackend = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");
        String sourceBackend = backend.getServerInfo().getName();
        if (!session.authorizesBackend(sourceBackend, activeBackend)) {
            session.markDropped();
            droppedPackets.incrementAndGet();
            diagnose(
                    "Dropped stale-backend NecroTempus payload for {} from {} "
                            + "(active={}, inFlight={})",
                    player.getUsername(), sourceBackend, activeBackend,
                    session.inFlightBackend());
            return;
        }
        // Once the private session-bound bridge has produced an ordered TAB snapshot for this
        // backend, it is the sole authority for discriminator 2.  NecroTempus/TAB on legacy
        // Crucible may still emit its native CraftPlayer payload in parallel; forwarding it would
        // race the validated bridge and malformed legacy framing used to create a warning storm.
        // Other NecroTempus UI discriminators (boss bar/title/action bar) remain untouched.
        if (isPlayerTabPayload(payload) && session.tabBridgeAuthoritativeFor(sourceBackend)) {
            long suppressed = suppressedDirectTabPackets.incrementAndGet();
            session.markSuppressedDirectTab();
            if (suppressed == 1L || suppressed % 1000L == 0L) {
                diagnose(
                        "Suppressed redundant direct NecroTempus TAB payload: player={}, backend={}, bytes={}, total={}",
                        player.getUsername(), sourceBackend, payload.length, suppressed);
            }
            return;
        }
        if (!session.rateLimiter().tryAcquire(payload.length)) {
            session.markDropped();
            droppedPackets.incrementAndGet();
            operationalLogger.warn("Rate-limited NecroTempus payload for {} from {}: bytes={}",
                    player.getUsername(), backend.getServerInfo().getName(), payload.length);
            return;
        }
        try {
            NecroTempusPacketCodec.ClientboundPacket packet =
                    NecroTempusPacketCodec.validateClientbound(
                            payload,
                            current.maximumCompressedNbtBytes(),
                            current.maximumDecompressedNbtBytes());
            ensureClientChannelAdvertised(player, session, false);
            final boolean sent;
            try {
                sent = player.sendPluginMessage(NECROTEMPUS, payload);
            } catch (RuntimeException exception) {
                session.markDropped();
                droppedPackets.incrementAndGet();
                operationalLogger.warn(
                        "Velocity threw while sending a validated NecroTempus payload for {} "
                                + "from {}: {}: {}",
                        player.getUsername(), backend.getServerInfo().getName(),
                        exception.getClass().getSimpleName(), exception.getMessage());
                return;
            }
            if (!sent) {
                session.markDropped();
                droppedPackets.incrementAndGet();
                operationalLogger.warn(
                        "Velocity refused a validated NecroTempus payload for {}: backend={}, discriminator={}",
                        player.getUsername(), backend.getServerInfo().getName(),
                        packet.discriminator());
                return;
            }
            long sessionForwarded = session.markForwarded();
            clientboundPackets.incrementAndGet();
            if (sessionForwarded == 1L) {
                diagnose(
                        "Forwarded first bounded NecroTempus UI payload: player={}, backend={}, "
                                + "discriminator={}, bytes={}, evidence={}",
                        player.getUsername(), backend.getServerInfo().getName(),
                        packet.discriminator(), payload.length, session.evidence().wireName());
            } else {
                diagnose(
                        "Forwarded bounded NecroTempus payload: player={}, backend={}, discriminator={}, bytes={}",
                        player.getUsername(), backend.getServerInfo().getName(),
                        packet.discriminator(), payload.length);
            }
        } catch (CodecException exception) {
            session.markDropped();
            droppedPackets.incrementAndGet();
            long malformed = session.markMalformedDirectPayload();
            if (malformed <= 3L || malformed % 100L == 0L) {
                operationalLogger.warn(
                        "Dropped malformed direct NecroTempus payload for {} from {}: {} "
                                + "(occurrence={})",
                        player.getUsername(), backend.getServerInfo().getName(),
                        exception.getMessage(), malformed);
            }
        }
    }

    private static boolean isPlayerTabPayload(byte[] payload) {
        return payload != null
                && payload.length > 0
                && Byte.toUnsignedInt(payload[0]) == NecroTempusPacketCodec.PLAYER_TAB_DISCRIMINATOR;
    }

    private void advertiseCapability(
            Player player,
            ClientSession session,
            boolean force) {
        player.getCurrentServer().ifPresent(backend -> {
            String backendName = backend.getServerInfo().getName();
            if (!session.shouldAdvertiseCapability(backendName, force)) {
                return;
            }
            byte[] message = BridgeProtocol.encode(BridgeProtocol.Capability.confirmed(
                    session.connectionEpoch(), player.getUniqueId(), session.modVersion()));
            final boolean sent;
            try {
                sent = backend.sendPluginMessage(CAPABILITY, message);
            } catch (RuntimeException exception) {
                operationalLogger.warn(
                        "Could not advertise NecroTempus capability to {} for {}: {}: {}",
                        backendName, player.getUsername(),
                        exception.getClass().getSimpleName(), exception.getMessage());
                return;
            }
            if (!sent) {
                diagnose("Backend {} did not accept NecroTempus capability for {}",
                        backendName, player.getUsername());
            } else {
                session.markCapabilityAdvertised(backendName);
                diagnose("Advertised NecroTempus capability to {} for {}: epoch={}",
                        backendName, player.getUsername(),
                        Long.toUnsignedString(session.connectionEpoch()));
            }
        });
    }

    private void ensureClientChannelAdvertised(
            Player player,
            ClientSession session,
            boolean force) {
        String backendName = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("pre-backend");
        long now = System.nanoTime();
        if (!session.shouldReassertChannel(
                backendName, force, now, CHANNEL_REASSERT_NANOS)) {
            return;
        }
        final boolean sent;
        try {
            sent = player.sendPluginMessage(LEGACY_REGISTER, NECROTEMPUS_REGISTRATION);
        } catch (RuntimeException exception) {
            operationalLogger.warn(
                    "Could not reassert NecroTempus legacy channel for {} on {}: {}: {}",
                    player.getUsername(), backendName,
                    exception.getClass().getSimpleName(), exception.getMessage());
            return;
        }
        if (sent) {
            session.markChannelAdvertised(backendName, now);
            diagnose("Reasserted legacy channel {} for {} on {}",
                    NecroTempusPacketCodec.CHANNEL, player.getUsername(), backendName);
        } else {
            diagnose("Velocity refused legacy channel reassertion for {} on {}",
                    player.getUsername(), backendName);
        }
    }

    private long nonZeroEpoch() {
        long value;
        do {
            value = random.nextLong();
        } while (value == 0L);
        return value;
    }

    private ReentrantLock sessionLifecycleLock(UUID playerId) {
        int spreadHash = playerId.hashCode() ^ (playerId.hashCode() >>> 16);
        return sessionLifecycleLocks[spreadHash & (SESSION_LIFECYCLE_LOCK_STRIPES - 1)];
    }

    private static ReentrantLock[] createSessionLifecycleLocks() {
        ReentrantLock[] locks = new ReentrantLock[SESSION_LIFECYCLE_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private void diagnose(String message, Object... arguments) {
        diagnostics.log(message, arguments);
    }

    private static String endpointName(Object endpoint) {
        if (endpoint instanceof Player player) {
            return "player:" + player.getUsername();
        }
        if (endpoint instanceof ServerConnection serverConnection) {
            return "server:" + serverConnection.getServerInfo().getName();
        }
        return endpoint == null ? "null" : endpoint.getClass().getSimpleName();
    }

    public record Metrics(
            boolean initialized,
            int activeSessions,
            long confirmedClients,
            long clientboundPackets,
            long droppedPackets,
            long suppressedDirectTabPackets) {
    }

    private enum EvidenceMerge {
        UNCHANGED,
        UPGRADED,
        CONFLICT
    }

    private record ConfirmationTransition(
            ClientSession session,
            boolean newlyConfirmed,
            boolean replacement,
            EvidenceMerge merge) {
    }

    private enum ConfirmationEvidence {
        CHANNEL_REGISTER(1, "channel-register", "channel-registered"),
        MOD_LIST(2, "forge-mod-list", "mod-list"),
        HELLO(3, "necrotempus-hello", "hello");

        private final int priority;
        private final String wireName;
        private final String fallbackVersion;

        ConfirmationEvidence(int priority, String wireName, String fallbackVersion) {
            this.priority = priority;
            this.wireName = wireName;
            this.fallbackVersion = fallbackVersion;
        }

        int priority() {
            return priority;
        }

        String wireName() {
            return wireName;
        }

        String fallbackVersion() {
            return fallbackVersion;
        }
    }

    private static final class ClientSession {
        private static final long BACKEND_TRANSITION_TIMEOUT_NANOS = 30_000_000_000L;
        private static final long CAPABILITY_REQUEST_INTERVAL_NANOS = 1_000_000_000L;
        private final Player owner;
        private final long connectionEpoch;
        private volatile String modVersion;
        private volatile ConfirmationEvidence evidence;
        private final WindowRateLimiter rateLimiter;
        private final AtomicLong forwardedPackets = new AtomicLong();
        private final AtomicLong droppedPackets = new AtomicLong();
        private final AtomicLong tabBridgeForwarded = new AtomicLong();
        private final AtomicLong malformedTabBridge = new AtomicLong();
        private final AtomicLong malformedDirectPayloads = new AtomicLong();
        private final AtomicLong suppressedDirectTabPayloads = new AtomicLong();
        private boolean confirmationCounted;
        private boolean active = true;
        private volatile String capabilityBackend = "";
        private volatile String channelBackend = "";
        private volatile long channelAdvertisedNanos;
        private String inFlightBackend = "";
        private long inFlightStartedNanos;
        private String lastCapabilityRequestBackend = "";
        private long lastCapabilityRequestNanos;
        private String tabBridgeBackend = "";
        private long lastTabBridgeSequence;

        private ClientSession(
                Player owner,
                long connectionEpoch,
                String modVersion,
                ConfirmationEvidence evidence,
                WindowRateLimiter rateLimiter) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.connectionEpoch = connectionEpoch;
            this.modVersion = Objects.requireNonNull(modVersion, "modVersion");
            this.evidence = Objects.requireNonNull(evidence, "evidence");
            this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        }

        boolean confirmed() {
            return !modVersion.isEmpty();
        }

        boolean belongsTo(Player player) {
            return owner == player;
        }

        long connectionEpoch() {
            return connectionEpoch;
        }

        String modVersion() {
            return modVersion;
        }

        ConfirmationEvidence evidence() {
            return evidence;
        }

        synchronized EvidenceMerge mergeEvidence(
                String candidateVersion,
                ConfirmationEvidence candidateEvidence) {
            Objects.requireNonNull(candidateVersion, "candidateVersion");
            Objects.requireNonNull(candidateEvidence, "candidateEvidence");
            if (candidateEvidence.priority() > evidence.priority()) {
                modVersion = candidateVersion;
                evidence = candidateEvidence;
                capabilityBackend = "";
                return EvidenceMerge.UPGRADED;
            }
            if (candidateEvidence.priority() == evidence.priority()
                    && !candidateVersion.equals(modVersion)) {
                return EvidenceMerge.CONFLICT;
            }
            return EvidenceMerge.UNCHANGED;
        }

        WindowRateLimiter rateLimiter() {
            return rateLimiter;
        }

        synchronized boolean markConfirmationCounted() {
            if (confirmationCounted) {
                return false;
            }
            confirmationCounted = true;
            return true;
        }

        synchronized boolean active() {
            return active;
        }

        synchronized boolean deactivateAndClearConfirmationCounted() {
            active = false;
            if (!confirmationCounted) {
                return false;
            }
            confirmationCounted = false;
            return true;
        }

        long markForwarded() {
            return forwardedPackets.incrementAndGet();
        }

        long markTabBridgeForwarded() {
            return tabBridgeForwarded.incrementAndGet();
        }

        long markMalformedTabBridge() {
            return malformedTabBridge.incrementAndGet();
        }

        long markMalformedDirectPayload() {
            return malformedDirectPayloads.incrementAndGet();
        }

        long markSuppressedDirectTab() {
            return suppressedDirectTabPayloads.incrementAndGet();
        }

        void markDropped() {
            droppedPackets.incrementAndGet();
        }

        long forwardedPackets() {
            return forwardedPackets.get();
        }

        long droppedPackets() {
            return droppedPackets.get();
        }

        synchronized void beginBackendCycle() {
            capabilityBackend = "";
            channelBackend = "";
            channelAdvertisedNanos = 0L;
            inFlightBackend = "";
            inFlightStartedNanos = 0L;
            lastCapabilityRequestBackend = "";
            lastCapabilityRequestNanos = 0L;
            tabBridgeBackend = "";
            lastTabBridgeSequence = 0L;
        }

        synchronized void beginBackendTransition(String targetBackend) {
            inFlightBackend = Objects.requireNonNull(targetBackend, "targetBackend");
            inFlightStartedNanos = System.nanoTime();
        }

        synchronized void clearBackendTransition() {
            inFlightBackend = "";
            inFlightStartedNanos = 0L;
        }

        synchronized boolean authorizesBackend(String sourceBackend, String currentBackend) {
            Objects.requireNonNull(sourceBackend, "sourceBackend");
            Objects.requireNonNull(currentBackend, "currentBackend");
            if (!inFlightBackend.isEmpty()) {
                long age = System.nanoTime() - inFlightStartedNanos;
                if (age >= 0L && age <= BACKEND_TRANSITION_TIMEOUT_NANOS) {
                    return inFlightBackend.equals(sourceBackend);
                }
                clearBackendTransition();
            }
            return currentBackend.equals(sourceBackend);
        }

        synchronized String inFlightBackend() {
            return inFlightBackend;
        }

        synchronized boolean acceptTabBridgeSequence(String backend, long sequence) {
            Objects.requireNonNull(backend, "backend");
            if (!backend.equals(tabBridgeBackend)) {
                tabBridgeBackend = backend;
                lastTabBridgeSequence = 0L;
            }
            if (sequence <= lastTabBridgeSequence) {
                return false;
            }
            lastTabBridgeSequence = sequence;
            return true;
        }

        synchronized boolean tabBridgeAuthoritativeFor(String backend) {
            return backend != null
                    && backend.equals(tabBridgeBackend)
                    && lastTabBridgeSequence > 0L;
        }

        synchronized boolean allowCapabilityRequest(String backend, long nowNanos) {
            if (backend.equals(lastCapabilityRequestBackend)
                    && nowNanos >= lastCapabilityRequestNanos
                    && nowNanos - lastCapabilityRequestNanos
                            < CAPABILITY_REQUEST_INTERVAL_NANOS) {
                return false;
            }
            lastCapabilityRequestBackend = backend;
            lastCapabilityRequestNanos = nowNanos;
            return true;
        }

        synchronized boolean shouldAdvertiseCapability(String backend, boolean force) {
            return force || !capabilityBackend.equals(backend);
        }

        synchronized void markCapabilityAdvertised(String backend) {
            capabilityBackend = backend;
        }

        synchronized boolean shouldReassertChannel(
                String backend,
                boolean force,
                long nowNanos,
                long intervalNanos) {
            if (force || !channelBackend.equals(backend)) {
                return true;
            }
            long elapsed = nowNanos - channelAdvertisedNanos;
            return elapsed < 0L || elapsed >= intervalNanos;
        }

        synchronized void markChannelAdvertised(String backend, long nowNanos) {
            channelBackend = backend;
            channelAdvertisedNanos = nowNanos;
        }
    }
}
