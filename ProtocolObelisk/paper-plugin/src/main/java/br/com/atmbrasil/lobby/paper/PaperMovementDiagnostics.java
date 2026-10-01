package br.com.atmbrasil.lobby.paper;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;

/** Main-thread, opt-in observation only. Never writes player state or handles movement packets. */
final class PaperMovementDiagnostics {
    private static final int MAXIMUM_SESSIONS = 4_096;
    private static final int MAXIMUM_EFFECTS = 16;
    private static final int MAXIMUM_SNAPSHOT_CHARS = 2_048;
    private static final int MAXIMUM_POLLS_PER_PASS = 64;
    private static final int MAXIMUM_POLL_RECORDS = 16;
    private static final int MAXIMUM_TELEPORT_RECORDS = 8;
    private static final long JOIN_WINDOW_NANOS = 300_000_000_000L;
    private static final long TELEPORT_WINDOW_NANOS = 15_000_000_000L;
    private static final String UNAVAILABLE = "unavailable";
    private final DiagnosticLog diagnostics;
    private final SnapshotReader reader;
    private final SnapshotReader controlReader;
    private final LongSupplier nanoTime;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Deque<UUID> pendingPolls = new ArrayDeque<>();
    private TaskHandle samplingTask;

    record SamplePlayer(String name, Object player) { }

    @FunctionalInterface
    interface TaskHandle {
        void cancel();
    }

    @FunctionalInterface
    interface PollScheduler {
        TaskHandle start(Runnable poll);
    }

    enum Phase { JOIN_BEFORE, JOIN_AFTER, READY, QUIT }

    @FunctionalInterface
    interface SnapshotReader {
        String read(Object player);
    }

    PaperMovementDiagnostics(DiagnosticLog diagnostics) {
        this(diagnostics, player -> readSnapshot(player, Player.class, attributeType()),
                player -> readControls(player, Player.class, attributeType()), System::nanoTime);
    }

    PaperMovementDiagnostics(DiagnosticLog diagnostics, SnapshotReader reader) {
        this(diagnostics, reader, reader, System::nanoTime);
    }

    PaperMovementDiagnostics(DiagnosticLog diagnostics, SnapshotReader reader,
            SnapshotReader controlReader, LongSupplier nanoTime) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.controlReader = Objects.requireNonNull(controlReader, "controlReader");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    void beginSession(UUID playerId) {
        endSession(playerId);
        if (diagnostics.enabled() && sessions.size() < MAXIMUM_SESSIONS) {
            Session session = new Session(nanoTime.getAsLong());
            sessions.put(playerId, session);
            enqueue(playerId, session);
        }
    }

    /** Exactly one main-thread task for all sessions; enabling debug applies to future joins. */
    void updateSampling(PollScheduler scheduler, Function<UUID, SamplePlayer> players) {
        if (!diagnostics.enabled()) {
            clear();
        } else if (samplingTask == null) {
            try {
                samplingTask = scheduler.start(() -> poll(players));
            } catch (RuntimeException | LinkageError ignored) {
                // Optional observation failure must not disable the plugin or change admission.
            }
        }
    }

    void recordVoidRescue(UUID playerId) {
        Session session = sessions.get(playerId);
        if (session != null && session.voidRescues < Integer.MAX_VALUE) {
            session.voidRescues++;
        }
    }

    void snapshot(UUID playerId, String playerName, Object player, Phase phase) {
        Session session = sessions.get(playerId);
        if (!diagnostics.enabled() || session == null || !session.phases.add(phase)) {
            return;
        }
        // An optional diagnostic or unavailable API must never interfere with admission/physics.
        try {
            diagnostics.info(() -> "movementDiagnostic=SNAPSHOT: player=" + token(playerName)
                    + ", phase=" + phase + context(session)
                    + ", " + boundedLine(reader.read(player), MAXIMUM_SNAPSHOT_CHARS)
                    + ", observationOnly=true");
        } catch (RuntimeException | LinkageError ignored) {
            // No fallback exception logging: keep the diagnostic bounded and gameplay-independent.
        }
    }

    /** Monitor-priority event: records the requested destination, never claims teleport completion. */
    void teleport(UUID playerId, String playerName, Object player, String cause,
            boolean cancelled, Object from, Object to) {
        Session session = sessions.get(playerId);
        if (!diagnostics.enabled() || session == null
                || session.teleportRecords >= MAXIMUM_TELEPORT_RECORDS) {
            return;
        }
        session.teleportRecords++;
        try {
            diagnostics.info(() -> "movementDiagnostic=TELEPORT_EVENT: player=" + token(playerName)
                    + ", cause=" + token(cause) + ", cancelled=" + cancelled
                    + ", from=" + readLocation(from) + ", to=" + readLocation(to)
                    + context(session) + ", "
                    + boundedLine(reader.read(player), MAXIMUM_SNAPSHOT_CHARS)
                    + ", authenticationState=unobserved, observationOnly=true");
        } catch (RuntimeException | LinkageError ignored) {
            // The event and its cancellation state are never modified by this observer.
        }
        if (!cancelled && session.pollRecords < MAXIMUM_POLL_RECORDS) {
            long now = nanoTime.getAsLong();
            if (now - session.windowStart >= session.windowNanos) {
                session.windowStart = now;
                session.windowNanos = TELEPORT_WINDOW_NANOS;
            }
            session.afterTeleport = true;
            enqueue(playerId, session);
        }
    }

    /** At most 64 player lookups/reads per shared pass, with a fair rotating queue. */
    void poll(Function<UUID, SamplePlayer> players) {
        if (!diagnostics.enabled()) {
            clear();
            return;
        }
        int budget = Math.min(MAXIMUM_POLLS_PER_PASS, pendingPolls.size());
        for (int index = 0; index < budget; index++) {
            UUID playerId = pendingPolls.removeFirst();
            Session session = sessions.get(playerId);
            if (session == null) {
                continue;
            }
            session.queued = false;
            if (nanoTime.getAsLong() - session.windowStart >= session.windowNanos
                    || session.pollRecords >= MAXIMUM_POLL_RECORDS) {
                continue;
            }
            try {
                SamplePlayer sample = players.apply(playerId);
                if (sample == null) {
                    endSession(playerId);
                    continue;
                }
                String controls = boundedLine(controlReader.read(sample.player()),
                        MAXIMUM_SNAPSHOT_CHARS);
                String reason = session.afterTeleport ? "AFTER_TELEPORT"
                        : session.controls == null ? "BASELINE"
                        : !session.controls.equals(controls) ? "CONTROLS_CHANGED" : null;
                session.afterTeleport = false;
                session.controls = controls;
                session.polls++;
                if (reason != null) {
                    session.pollRecords++;
                    diagnostics.info(() -> "movementDiagnostic=POLL: player=" + token(sample.name())
                            + ", reason=" + reason + context(session) + ", "
                            + boundedLine(reader.read(sample.player()), MAXIMUM_SNAPSHOT_CHARS)
                            + ", authenticationState=unobserved, observationOnly=true");
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Stop retrying a broken reader/provider for this window, without gameplay effects.
                continue;
            }
            if (session.pollRecords < MAXIMUM_POLL_RECORDS) {
                enqueue(playerId, session);
            }
        }
    }

    private void enqueue(UUID playerId, Session session) {
        if (!session.queued) {
            pendingPolls.addLast(playerId);
            session.queued = true;
        }
    }

    private String context(Session session) {
        return ", sinceJoinMs=" + Math.max(0L, (nanoTime.getAsLong() - session.started) / 1_000_000L)
                + ", voidRescues=" + session.voidRescues + ", polls=" + session.polls
                + ", pollRecords=" + session.pollRecords + ", teleportRecords=" + session.teleportRecords;
    }

    void endSession(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session != null && session.queued) {
            pendingPolls.remove(playerId);
        }
    }

    void clear() {
        TaskHandle task = samplingTask;
        samplingTask = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException | LinkageError ignored) {
                // Bukkit also cancels plugin tasks on disable; diagnostic cleanup must not escape.
            }
        }
        sessions.clear();
        pendingPolls.clear();
    }

    int pendingSessions() {
        return pendingPolls.size();
    }

    int trackedSessions() {
        return sessions.size();
    }

    /** Public Bukkit getters only; no setAccessible, setters, packet hooks or NMS access. */
    static String readSnapshot(Object player, Class<?> playerApi, Class<?> attributes) {
        StringBuilder out = new StringBuilder();
        for (String getter : new String[]{"getGameMode", "getAllowFlight", "isFlying",
                "isOnGround", "hasGravity", "isSprinting", "isSneaking",
                "getWalkSpeed", "getFlySpeed", "getFallDistance"}) {
            append(out, getter, value(invoke(playerApi, player, getter)));
        }
        append(out, "position", readLocation(invoke(playerApi, player, "getLocation")));
        Object velocity = invoke(playerApi, player, "getVelocity");
        for (String axis : new String[]{"getX", "getY", "getZ"}) {
            append(out, "velocity." + axis, value(invokeObject(velocity, axis)));
        }
        appendAttributes(out, player, playerApi, attributes);
        Object rawEffects = invoke(playerApi, player, "getActivePotionEffects");
        if (rawEffects instanceof Collection<?> effects) {
            append(out, "effectCount", Integer.toString(effects.size()));
            StringBuilder summary = new StringBuilder("[");
            int count = 0;
            for (Object effect : effects) {
                if (count == MAXIMUM_EFFECTS) {
                    summary.append(",omitted");
                    break;
                }
                if (count++ > 0) {
                    summary.append(',');
                }
                Object type = invokeObject(effect, "getType");
                summary.append(token(value(invokeObject(type, "getName"))))
                        .append(':').append(value(invokeObject(effect, "getAmplifier")))
                        .append(':').append(value(invokeObject(effect, "getDuration")));
            }
            append(out, "effects", summary.append(']').toString());
        } else {
            append(out, "effects", UNAVAILABLE);
        }
        return boundedLine(out.toString(), MAXIMUM_SNAPSHOT_CHARS);
    }

    /** Stable controls omit velocity, position, stance and effect duration to avoid movement log spam. */
    static String readControls(Object player, Class<?> playerApi, Class<?> attributes) {
        StringBuilder out = new StringBuilder();
        for (String getter : new String[]{"getGameMode", "getAllowFlight", "getWalkSpeed", "getFlySpeed",
                "hasGravity"}) {
            append(out, getter, value(invoke(playerApi, player, getter)));
        }
        appendAttributes(out, player, playerApi, attributes);
        Object rawEffects = invoke(playerApi, player, "getActivePotionEffects");
        if (rawEffects instanceof Collection<?> effects) {
            List<String> keys = new ArrayList<>();
            int count = 0;
            for (Object effect : effects) {
                if (count++ == MAXIMUM_EFFECTS) {
                    break;
                }
                keys.add(token(value(invokeObject(invokeObject(effect, "getType"), "getName")))
                        + ':' + value(invokeObject(effect, "getAmplifier")));
            }
            Collections.sort(keys);
            append(out, "effectCount", Integer.toString(effects.size()));
            append(out, "effects", keys.toString());
        } else {
            append(out, "effects", UNAVAILABLE);
        }
        return boundedLine(out.toString(), MAXIMUM_SNAPSHOT_CHARS);
    }

    private static void appendAttributes(StringBuilder out, Object player, Class<?> playerApi,
            Class<?> attributes) {
        for (String attribute : new String[]{"GENERIC_GRAVITY", "GENERIC_JUMP_STRENGTH",
                "GENERIC_STEP_HEIGHT", "GENERIC_MOVEMENT_SPEED"}) {
            Object instance = null;
            if (attributes != null) {
                try {
                    Object key = attributes.getField(attribute).get(null);
                    instance = playerApi.getMethod("getAttribute", attributes).invoke(player, key);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // Missing/changed API is reported as unavailable, not guessed or repaired.
                }
            }
            append(out, attribute, "base:" + value(invokeObject(instance, "getBaseValue"))
                    + "/effective:" + value(invokeObject(instance, "getValue")));
        }
    }

    static String readLocation(Object location) {
        return "world:" + value(invokeObject(invokeObject(location, "getWorld"), "getName"))
                + "/x:" + value(invokeObject(location, "getX"))
                + "/y:" + value(invokeObject(location, "getY"))
                + "/z:" + value(invokeObject(location, "getZ"));
    }

    private static Class<?> attributeType() {
        try {
            return Class.forName("org.bukkit.attribute.Attribute", false, Player.class.getClassLoader());
        } catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object invokeObject(Object target, String getter) {
        return target == null ? null : invoke(target.getClass(), target, getter);
    }

    private static Object invoke(Class<?> api, Object target, String getter) {
        if (target == null) {
            return null;
        }
        try {
            Method method = api.getMethod(getter);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static String value(Object value) {
        if (value == null) {
            return UNAVAILABLE;
        }
        if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
            return "non-finite";
        }
        return token(value.toString());
    }

    private static String token(String text) {
        return boundedLine(text, 96).replaceAll("[^A-Za-z0-9_.:/+\\-]", "_");
    }

    private static String boundedLine(String text, int maximum) {
        if (text == null) {
            return UNAVAILABLE;
        }
        String bounded = text.substring(0, Math.min(text.length(), maximum));
        return bounded.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", "_");
    }

    private static void append(StringBuilder out, String key, String value) {
        if (!out.isEmpty()) {
            out.append(", ");
        }
        out.append(key).append('=').append(value);
    }

    private static final class Session {
        private final EnumSet<Phase> phases = EnumSet.noneOf(Phase.class);
        private final long started;
        private long windowStart;
        private long windowNanos = JOIN_WINDOW_NANOS;
        private String controls;
        private int voidRescues;
        private int polls;
        private int pollRecords;
        private int teleportRecords;
        private boolean queued;
        private boolean afterTeleport;

        private Session(long now) {
            started = now;
            windowStart = now;
        }
    }
}
