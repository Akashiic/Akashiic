package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class LobbyNegotiationPlanCacheTest {
    private static final String QUERY_A = "a".repeat(64);
    private static final String QUERY_B = "b".repeat(64);
    private static final String QUERY_C = "c".repeat(64);
    private static final String CONTRACT = "d".repeat(64);
    private static final String ALTERNATE_CONTRACT = "e".repeat(64);

    @Test
    void validatedExactKeyReusesOnlyTheImmutableDerivedPlan() {
        LobbyNegotiationPlanCache cache = new LobbyNegotiationPlanCache(7, 2);
        LobbyNegotiationPlanCache.Key key = key(QUERY_A, 10, 0);
        AtomicInteger computations = new AtomicInteger();

        LobbyNegotiationPlanCache.Lookup miss = cache.resolve(key, () -> {
            computations.incrementAndGet();
            return rejectedPlan();
        });
        LobbyNegotiationPlanCache.Lookup hit = cache.resolve(key, () -> {
            computations.incrementAndGet();
            return rejectedPlan();
        });

        assertFalse(miss.hit());
        assertTrue(hit.hit());
        assertEquals(1, computations.get());
        assertEquals(miss.plan(), hit.plan());
        assertEquals(1, hit.snapshot().hits());
        assertEquals(1, hit.snapshot().misses());
        assertEquals(7, hit.snapshot().generation());
    }

    @Test
    void lruBoundEvictsWithoutLeakingAcrossCacheGenerations() {
        LobbyNegotiationPlanCache first = new LobbyNegotiationPlanCache(1, 2);
        first.resolve(key(QUERY_A, 1, 0), LobbyNegotiationPlanCacheTest::rejectedPlan);
        first.resolve(key(QUERY_B, 1, 0), LobbyNegotiationPlanCacheTest::rejectedPlan);
        first.resolve(key(QUERY_A, 1, 0), LobbyNegotiationPlanCacheTest::rejectedPlan);
        first.resolve(key(QUERY_C, 1, 0), LobbyNegotiationPlanCacheTest::rejectedPlan);

        assertEquals(2, first.snapshot().size());
        assertEquals(1, first.snapshot().evictions());

        LobbyNegotiationPlanCache second = new LobbyNegotiationPlanCache(2, 2);
        LobbyNegotiationPlanCache.Lookup secondGeneration = second.resolve(
                key(QUERY_A, 1, 0), LobbyNegotiationPlanCacheTest::rejectedPlan);
        assertFalse(secondGeneration.hit());
        assertEquals(2, secondGeneration.snapshot().generation());
        assertNotSame(first, second);
    }

    @Test
    void rawFingerprintRegistryShapeAndContractAreAllPartOfTheKey() {
        LobbyNegotiationPlanCache cache = new LobbyNegotiationPlanCache(1, 8);
        cache.resolve(key(QUERY_A, 5, 0), LobbyNegotiationPlanCacheTest::rejectedPlan);

        assertFalse(cache.resolve(
                key(QUERY_B, 5, 0), LobbyNegotiationPlanCacheTest::rejectedPlan).hit());
        assertFalse(cache.resolve(
                key(QUERY_A, 6, 0), LobbyNegotiationPlanCacheTest::rejectedPlan).hit());
        assertFalse(cache.resolve(
                key(QUERY_A, 5, 1), LobbyNegotiationPlanCacheTest::rejectedPlan).hit());
        assertFalse(cache.resolve(
                new LobbyNegotiationPlanCache.Key(766, QUERY_A, CONTRACT, 5, 0),
                LobbyNegotiationPlanCacheTest::rejectedPlan).hit());
        assertFalse(cache.resolve(
                new LobbyNegotiationPlanCache.Key(
                        767, QUERY_A, ALTERNATE_CONTRACT, 5, 0),
                LobbyNegotiationPlanCacheTest::rejectedPlan).hit());
        assertEquals(6, cache.snapshot().misses());
    }

    @Test
    void malformedKeysAndInconsistentCachedPlansFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new LobbyNegotiationPlanCache(0));
        assertThrows(IllegalArgumentException.class,
                () -> new LobbyNegotiationPlanCache.Key(767, "short", CONTRACT, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LobbyNegotiationPlanCache.Plan(
                        new ChannelContractSignature.Signatures(CONTRACT, ""),
                        SilentGearProtocol.inspect(List.of()),
                        Optional.empty(),
                        new PlaySinkPlanner.Plan(
                                List.of(),
                                List.of(),
                                List.of(),
                                false,
                                false,
                                true,
                                "",
                                "",
                                PlaySinkPlanner.Mode.PARSED,
                                0,
                                0,
                                0,
                                ""),
                        TransientServerConfigPlanner.plan(List.of(), List.of(), false, 0),
                        Optional.empty(),
                        false,
                        false));
    }

    @Test
    void livePathValidatesAndDecodesBeforeEveryCacheLookup() throws Exception {
        String plugin = Files.readString(Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java"),
                StandardCharsets.UTF_8);
        int receive = plugin.indexOf("private void receiveNeoForgeQuery(");
        int validate = plugin.indexOf("validateOpaqueQueryResponse", receive);
        int decode = plugin.indexOf("decodeLobbyQuery", validate);
        int cacheLookup = plugin.indexOf("cache.resolve(cacheKey", decode);
        int receiveEnd = plugin.indexOf(
                "private void beginBlockStateTranslationAttachment(", receive);
        String receiveMethod = plugin.substring(receive, receiveEnd);

        assertTrue(validate > receive && decode > validate && cacheLookup > decode);
        assertEquals(1, receiveMethod.split("decodeLobbyQuery", -1).length - 1);

        String cacheSource = Files.readString(Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/"
                        + "LobbyNegotiationPlanCache.java"), StandardCharsets.UTF_8);
        assertFalse(cacheSource.contains("UUID"));
        assertFalse(cacheSource.contains("Player "));
        assertFalse(cacheSource.contains("BridgeSession"));
    }

    private static LobbyNegotiationPlanCache.Key key(
            String query, int channelCount, int ignoredChannelCount) {
        return new LobbyNegotiationPlanCache.Key(
                767, query, CONTRACT, channelCount, ignoredChannelCount);
    }

    private static LobbyNegotiationPlanCache.Plan rejectedPlan() {
        return new LobbyNegotiationPlanCache.Plan(
                new ChannelContractSignature.Signatures(CONTRACT, ""),
                SilentGearProtocol.inspect(List.of()),
                Optional.empty(),
                new PlaySinkPlanner.Plan(
                        List.of(),
                        List.of(),
                        List.of(),
                        false,
                        false,
                        false,
                        "",
                        "no Silent Gear channels",
                        PlaySinkPlanner.Mode.PARSED,
                        0,
                        0,
                        0,
                        ""),
                TransientServerConfigPlanner.plan(List.of(), List.of(), false, 0),
                Optional.empty(),
                false,
                false);
    }
}
