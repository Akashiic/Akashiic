package br.com.atmbrasil.lobby.paper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PaperMovementDiagnosticsTest {
    private static final UUID PLAYER = new UUID(0L, 1L);

    @Test
    void disabledDebugDoesNotTrackReadOrWriteAnything() {
        List<String> records = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        DiagnosticLog gate = new DiagnosticLog(records::add);
        PaperMovementDiagnostics diagnostics = new PaperMovementDiagnostics(gate, player -> {
            reads.incrementAndGet();
            return "snapshot";
        });
        diagnostics.beginSession(PLAYER);
        diagnostics.recordVoidRescue(PLAYER);
        diagnostics.snapshot(PLAYER, "player", new Object(), PaperMovementDiagnostics.Phase.READY);
        assertEquals(0, diagnostics.trackedSessions());
        assertEquals(0, reads.get());
        assertTrue(records.isEmpty());
    }

    @Test
    void eachPhaseIsEmittedOnceAndRescuesNeverEmitMovementLogs() {
        List<String> records = new ArrayList<>();
        DiagnosticLog gate = new DiagnosticLog(records::add);
        gate.setEnabled(true);
        PaperMovementDiagnostics diagnostics = new PaperMovementDiagnostics(gate, ignored -> "ok");
        diagnostics.beginSession(PLAYER);
        diagnostics.recordVoidRescue(PLAYER);
        diagnostics.recordVoidRescue(PLAYER);
        assertTrue(records.isEmpty());
        for (var phase : PaperMovementDiagnostics.Phase.values()) {
            diagnostics.snapshot(PLAYER, "player", new Object(), phase);
            diagnostics.snapshot(PLAYER, "player", new Object(), phase);
        }
        assertEquals(4, records.size());
        assertTrue(records.stream().allMatch(line -> line.contains("voidRescues=2")));
        assertTrue(records.stream().allMatch(line -> line.endsWith("observationOnly=true")));
        diagnostics.endSession(PLAYER);
        diagnostics.snapshot(PLAYER, "player", new Object(), PaperMovementDiagnostics.Phase.READY);
        assertEquals(4, records.size());
        assertEquals(0, diagnostics.trackedSessions());
        diagnostics.beginSession(PLAYER);
        diagnostics.snapshot(PLAYER, "player", new Object(), PaperMovementDiagnostics.Phase.READY);
        assertTrue(records.getLast().contains("voidRescues=0"));
    }

    @Test
    void trackingHasAnAbsoluteBoundAndDisableClearsAllSessions() {
        DiagnosticLog gate = new DiagnosticLog(ignored -> { });
        gate.setEnabled(true);
        PaperMovementDiagnostics diagnostics = new PaperMovementDiagnostics(gate, ignored -> "ok");
        for (int index = 0; index < 4_200; index++) {
            diagnostics.beginSession(new UUID(0L, index));
        }
        assertEquals(4_096, diagnostics.trackedSessions());
        diagnostics.clear();
        assertEquals(0, diagnostics.trackedSessions());
    }

    @Test
    void disablingDebugSuppressesExistingSessionSnapshotsWithoutReading() {
        DiagnosticLog gate = new DiagnosticLog(ignored -> { });
        gate.setEnabled(true);
        AtomicInteger reads = new AtomicInteger();
        PaperMovementDiagnostics diagnostics = new PaperMovementDiagnostics(gate, ignored -> {
            reads.incrementAndGet();
            return "ok";
        });
        diagnostics.beginSession(PLAYER);
        gate.setEnabled(false);
        diagnostics.snapshot(PLAYER, "player", new Object(), PaperMovementDiagnostics.Phase.READY);
        assertEquals(0, reads.get());
        gate.setEnabled(true);
        diagnostics.snapshot(PLAYER, "player", new Object(), PaperMovementDiagnostics.Phase.READY);
        assertEquals(1, reads.get());
    }

    @Test
    void diagnosticFailuresNeverEscapeIntoJoinOrReadiness() {
        DiagnosticLog gate = new DiagnosticLog(ignored -> { throw new IllegalStateException(); });
        gate.setEnabled(true);
        PaperMovementDiagnostics diagnostics = new PaperMovementDiagnostics(gate, ignored -> "ok");
        diagnostics.beginSession(PLAYER);
        assertDoesNotThrow(() -> diagnostics.snapshot(PLAYER, "player", new Object(),
                PaperMovementDiagnostics.Phase.READY));
        PaperMovementDiagnostics badReader = new PaperMovementDiagnostics(gate, ignored -> {
            throw new NoSuchMethodError();
        });
        badReader.beginSession(PLAYER);
        assertDoesNotThrow(() -> badReader.snapshot(PLAYER, "player", new Object(),
                PaperMovementDiagnostics.Phase.READY));
    }

    @Test
    void reflectionUsesOnlyGettersAndReportsMissingApiWithoutGuessingValues() {
        FakePlayer player = new FakePlayer();
        String snapshot = PaperMovementDiagnostics.readSnapshot(player, FakePlayer.class, Attr.class);
        assertTrue(snapshot.contains("getGameMode=ADVENTURE"));
        assertTrue(snapshot.contains("getAllowFlight=false"));
        assertTrue(snapshot.contains("isFlying=unavailable"), "throwing getter is isolated");
        assertTrue(snapshot.contains("getWalkSpeed=0.2"));
        assertTrue(snapshot.contains("velocity.getY=0.42"));
        assertTrue(snapshot.contains("GENERIC_GRAVITY=base:0.08/effective:0.08"));
        assertTrue(snapshot.contains("GENERIC_JUMP_STRENGTH=base:0.42/effective:0.42"));
        assertTrue(snapshot.contains("GENERIC_STEP_HEIGHT=base:0.6/effective:0.6"));
        assertTrue(snapshot.contains("GENERIC_MOVEMENT_SPEED=base:0.1/effective:0.1"));
        assertTrue(snapshot.contains("effects=[JUMP:1:200]"));
        assertEquals(0, player.writes);
        String missing = PaperMovementDiagnostics.readSnapshot(new Object(), Object.class, null);
        assertTrue(missing.contains("GENERIC_GRAVITY=base:unavailable/effective:unavailable"));
        assertTrue(missing.contains("effects=unavailable"));
    }

    @Test
    void outputIsSingleLineBoundedAndEffectEnumerationIsCapped() {
        List<String> records = new ArrayList<>();
        DiagnosticLog gate = new DiagnosticLog(records::add);
        gate.setEnabled(true);
        PaperMovementDiagnostics diagnostics = new PaperMovementDiagnostics(gate,
                ignored -> "x\n\r\u2028".repeat(2_000));
        diagnostics.beginSession(PLAYER);
        diagnostics.snapshot(PLAYER, "name\nINJECT", new Object(), PaperMovementDiagnostics.Phase.READY);
        assertEquals(1, records.size());
        assertTrue(records.getFirst().length() < 2_300);
        assertFalse(records.getFirst().contains("\n"));
        assertFalse(records.getFirst().contains("\r"));
        assertFalse(records.getFirst().contains("\u2028"));
        FakePlayer player = new FakePlayer();
        player.effects = java.util.Collections.nCopies(100, new Effect());
        String snapshot = PaperMovementDiagnostics.readSnapshot(player, FakePlayer.class, Attr.class);
        assertTrue(snapshot.contains("effectCount=100"));
        assertTrue(snapshot.contains("omitted]"));
        assertEquals(16, snapshot.split("JUMP:1:200", -1).length - 1);
    }

    @Test
    void oneSharedTaskIsCancelledOnDisableAndCanRestartCleanly() {
        Fixture f = new Fixture();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        AtomicReference<Runnable> callback = new AtomicReference<>();
        PaperMovementDiagnostics.PollScheduler scheduler = poll -> {
            starts.incrementAndGet();
            callback.set(poll);
            return cancels::incrementAndGet;
        };
        f.diagnostics.updateSampling(scheduler, ignored -> f.player);
        f.diagnostics.updateSampling(scheduler, ignored -> f.player);
        assertEquals(1, starts.get());
        callback.get().run();
        assertEquals(1, f.reads.get());
        f.gate.setEnabled(false);
        f.diagnostics.updateSampling(scheduler, ignored -> f.player);
        assertEquals(1, cancels.get());
        assertEquals(0, f.diagnostics.trackedSessions());
        assertEquals(0, f.diagnostics.pendingSessions());
        callback.get().run();
        assertEquals(1, f.reads.get());
        f.gate.setEnabled(true);
        f.diagnostics.updateSampling(scheduler, ignored -> f.player);
        assertEquals(2, starts.get());
        f.diagnostics.clear();
        f.diagnostics.clear();
        assertEquals(2, cancels.get());
    }

    @Test
    void pollsDetectLateMovementRestorationWithoutRecipeReadinessOrAuthAssumptions() {
        Fixture f = new Fixture();
        f.poll();
        assertTrue(f.records.getFirst().contains("reason=BASELINE"));
        f.clock.set(120_000_000_000L);
        f.controls.set("walk=0;blindness=127");
        f.poll();
        f.clock.set(130_000_000_000L);
        f.controls.set("walk=0.2;blindness=absent");
        f.poll();
        f.poll();
        assertEquals(3, f.records.size(), "unchanged polls emit nothing");
        assertTrue(f.records.getLast().contains("reason=CONTROLS_CHANGED"));
        assertTrue(f.records.getLast().contains("sinceJoinMs=130000"));
        assertTrue(f.records.stream().allMatch(line -> line.contains("authenticationState=unobserved")));
        f.clock.set(300_000_000_000L);
        f.poll();
        assertEquals(4, f.reads.get(), "expired window does not read the player");
        assertEquals(0, f.diagnostics.pendingSessions());
    }

    @Test
    void teleportAfterJoinWindowRearmsFifteenSecondsAndIncludesLaterRescues() {
        Fixture f = new Fixture();
        f.poll();
        f.clock.set(301_000_000_000L);
        f.poll();
        assertEquals(0, f.diagnostics.pendingSessions());
        f.diagnostics.recordVoidRescue(PLAYER);
        f.diagnostics.teleport(PLAYER, "player", new Object(), "PLUGIN", false,
                new Point(1, 2, 3), new Point(4, 5, 6));
        assertEquals(1, f.diagnostics.pendingSessions());
        assertTrue(f.records.getLast().contains("from=world:lobby/x:1.0/y:2.0/z:3.0"));
        assertTrue(f.records.getLast().contains("to=world:lobby/x:4.0/y:5.0/z:6.0"));
        f.poll();
        assertTrue(f.records.getLast().contains("reason=AFTER_TELEPORT"));
        assertTrue(f.records.getLast().contains("voidRescues=1"));
        f.clock.set(315_999_000_000L);
        f.poll();
        assertEquals(1, f.diagnostics.pendingSessions());
        int reads = f.reads.get();
        f.clock.set(316_000_000_000L);
        f.poll();
        assertEquals(reads, f.reads.get());
        assertEquals(0, f.diagnostics.pendingSessions());
    }

    @Test
    void cancelledTeleportsAreObservedWithoutRearmingAndEventBudgetIsAbsolute() {
        Fixture f = new Fixture();
        f.clock.set(300_000_000_000L);
        f.poll();
        for (int index = 0; index < 100; index++) {
            f.diagnostics.teleport(PLAYER, "player", new Object(), "COMMAND", true, null, null);
        }
        assertEquals(8, f.records.size());
        assertTrue(f.records.stream().allMatch(line -> line.contains("cancelled=true")));
        assertEquals(0, f.diagnostics.pendingSessions());
        f.diagnostics.teleport(PLAYER, "player", new Object(), "PLUGIN", false, null, null);
        assertEquals(0, f.diagnostics.pendingSessions(), "exhausted event budget cannot rearm");
    }

    @Test
    void repeatedControlChangesExhaustLogBudgetAndStopFurtherReads() {
        Fixture f = new Fixture();
        for (int index = 0; index < 100; index++) {
            f.controls.set("controls=" + index);
            f.poll();
        }
        assertEquals(16, f.records.size());
        assertEquals(16, f.reads.get());
        assertEquals(0, f.diagnostics.pendingSessions());
        f.diagnostics.teleport(PLAYER, "player", new Object(), "PLUGIN", false, null, null);
        assertEquals(0, f.diagnostics.pendingSessions(), "teleport cannot bypass total poll budget");
    }

    @Test
    void pollBudgetIsFairAndQuitOrReconnectCannotLeakDuplicateQueueEntries() {
        Fixture f = new Fixture();
        for (int index = 2; index <= 130; index++) {
            f.diagnostics.beginSession(new UUID(0L, index));
        }
        List<UUID> visited = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            f.diagnostics.poll(id -> { visited.add(id); return f.player; });
        }
        assertEquals(192, visited.size());
        assertEquals(130, new java.util.HashSet<>(visited).size());
        f.diagnostics.endSession(PLAYER);
        assertEquals(129, f.diagnostics.pendingSessions());
        f.diagnostics.beginSession(PLAYER);
        f.diagnostics.beginSession(PLAYER);
        assertEquals(130, f.diagnostics.pendingSessions());
        assertEquals(130, f.diagnostics.trackedSessions());
        f.diagnostics.clear();
        assertEquals(0, f.diagnostics.pendingSessions());
    }

    @Test
    void offlinePlayersAndBrokenProvidersStopPollingWithoutAffectingOtherPlayers() {
        Fixture f = new Fixture();
        UUID second = new UUID(0L, 2L);
        UUID third = new UUID(0L, 3L);
        f.diagnostics.beginSession(second);
        f.diagnostics.beginSession(third);
        assertDoesNotThrow(() -> f.diagnostics.poll(id -> {
            if (id.equals(PLAYER)) { return null; }
            if (id.equals(second)) { throw new NoSuchMethodError(); }
            return f.player;
        }));
        assertEquals(2, f.diagnostics.trackedSessions());
        assertEquals(1, f.diagnostics.pendingSessions());
        assertEquals(1, f.records.size());
        assertDoesNotThrow(() -> f.diagnostics.updateSampling(ignored -> {
            throw new IllegalStateException();
        }, ignored -> f.player));
    }

    @Test
    void controlComparisonIgnoresEffectDurationAndOrderButDetectsAmplifierAndSpeed() {
        FakePlayer player = new FakePlayer();
        Effect first = new Effect();
        first.name = "BLINDNESS";
        Effect second = new Effect();
        second.name = "SPEED";
        player.effects = List.of(first, second);
        String before = PaperMovementDiagnostics.readControls(player, FakePlayer.class, Attr.class);
        first.duration--;
        player.effects = List.of(second, first);
        assertEquals(before, PaperMovementDiagnostics.readControls(player, FakePlayer.class, Attr.class));
        first.amplifier++;
        assertFalse(before.equals(PaperMovementDiagnostics.readControls(player, FakePlayer.class, Attr.class)));
        first.amplifier--;
        player.walkSpeed = 0.0F;
        assertFalse(before.equals(PaperMovementDiagnostics.readControls(player, FakePlayer.class, Attr.class)));
        assertEquals(0, player.writes);
    }

    @Test
    void disabledDiagnosticsDoNotInspectTeleportsOrScheduleAnything() {
        Fixture f = new Fixture();
        f.gate.setEnabled(false);
        f.diagnostics.updateSampling(ignored -> { throw new AssertionError("must not schedule"); },
                ignored -> { throw new AssertionError("must not resolve player"); });
        f.diagnostics.teleport(PLAYER, "player", new Object(), "PLUGIN", false, null, null);
        f.poll();
        assertEquals(0, f.reads.get());
        assertTrue(f.records.isEmpty());
        assertEquals(0, f.diagnostics.trackedSessions());
    }

    private static final class Fixture {
        final List<String> records = new ArrayList<>();
        final DiagnosticLog gate = new DiagnosticLog(records::add);
        final AtomicLong clock = new AtomicLong();
        final AtomicReference<String> controls = new AtomicReference<>("walk=0.2");
        final AtomicInteger reads = new AtomicInteger();
        final PaperMovementDiagnostics.SamplePlayer player =
                new PaperMovementDiagnostics.SamplePlayer("player", new Object());
        final PaperMovementDiagnostics diagnostics;

        Fixture() {
            gate.setEnabled(true);
            diagnostics = new PaperMovementDiagnostics(gate, ignored -> "details", ignored -> {
                reads.incrementAndGet();
                return controls.get();
            }, clock::get);
            diagnostics.beginSession(PLAYER);
        }

        void poll() {
            diagnostics.poll(ignored -> player);
        }
    }

    public record Point(double x, double y, double z) {
        public Point getWorld() { return this; }
        public String getName() { return "lobby"; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
    }

    public enum Attr {
        GENERIC_GRAVITY(0.08), GENERIC_JUMP_STRENGTH(0.42),
        GENERIC_STEP_HEIGHT(0.6), GENERIC_MOVEMENT_SPEED(0.1);
        final double value;
        Attr(double value) { this.value = value; }
    }

    public static final class FakePlayer {
        int writes;
        float walkSpeed = 0.2F;
        Collection<Effect> effects = List.of(new Effect());
        public String getGameMode() { return "ADVENTURE"; }
        public boolean getAllowFlight() { return false; }
        public boolean isFlying() { throw new IllegalStateException("getter unavailable"); }
        public float getWalkSpeed() { return walkSpeed; }
        public Vector getVelocity() { return new Vector(); }
        public Instance getAttribute(Attr attribute) { return new Instance(attribute.value); }
        public Collection<Effect> getActivePotionEffects() { return effects; }
        public void setWalkSpeed(float ignored) { writes++; }
        public void setFlying(boolean ignored) { writes++; }
    }

    public record Instance(double value) {
        public double getBaseValue() { return value; }
        public double getValue() { return value; }
    }

    public static final class Vector {
        public double getX() { return 0; }
        public double getY() { return 0.42; }
        public double getZ() { return 0; }
    }

    public static final class Effect {
        String name = "JUMP";
        int amplifier = 1;
        int duration = 200;
        public Effect getType() { return this; }
        public String getName() { return name; }
        public int getAmplifier() { return amplifier; }
        public int getDuration() { return duration; }
    }
}
