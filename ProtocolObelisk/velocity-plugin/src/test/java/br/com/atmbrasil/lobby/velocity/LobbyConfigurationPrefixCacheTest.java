package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LobbyConfigurationPrefixCacheTest {
    @Test
    void exactWireInputsCompileOnceAndRemainGenerationBounded() throws Exception {
        LobbyConfigurationPrefixCache cache = new LobbyConfigurationPrefixCache(9, 2);
        LobbyConfigurationPrefixCache.Key key = key("profile-a", List.of("a-server.toml"));
        AtomicInteger compilations = new AtomicInteger();

        LobbyConfigurationPrefixCache.Lookup miss = cache.resolve(key, () -> {
            compilations.incrementAndGet();
            return batch("a");
        });
        LobbyConfigurationPrefixCache.Lookup hit = cache.resolve(key, () -> {
            compilations.incrementAndGet();
            return batch("changed");
        });

        assertFalse(miss.hit());
        assertTrue(hit.hit());
        assertEquals(1, compilations.get());
        assertEquals(miss.batch().sequenceSha256(), hit.batch().sequenceSha256());
        assertEquals(9, hit.snapshot().generation());
        assertEquals(1, hit.snapshot().hits());
        assertEquals(1, hit.snapshot().misses());
    }

    @Test
    void profileConfigAndChannelChangesCannotAliasAndLruRemainsBounded() throws Exception {
        LobbyConfigurationPrefixCache cache = new LobbyConfigurationPrefixCache(1, 2);
        cache.resolve(key("profile-a", List.of("a-server.toml")), () -> batch("a"));
        cache.resolve(key("profile-b", List.of("a-server.toml")), () -> batch("b"));
        cache.resolve(key("profile-a", List.of("b-server.toml")), () -> batch("c"));

        assertEquals(2, cache.snapshot().size());
        assertEquals(3, cache.snapshot().misses());
        assertEquals(1, cache.snapshot().evictions());
    }

    @Test
    void identicalNamesWithDifferentServerConfigPayloadsCannotAlias() throws Exception {
        LobbyConfigurationPrefixCache cache = new LobbyConfigurationPrefixCache(3, 2);
        LobbyConfigurationPrefixCache.Key first = key(
                "profile-a", List.of("a-server.toml"), "1".repeat(64));
        LobbyConfigurationPrefixCache.Key second = key(
                "profile-a", List.of("a-server.toml"), "2".repeat(64));

        LobbyConfigurationPrefixCache.Lookup firstMiss =
                cache.resolve(first, () -> batch("first-payload"));
        LobbyConfigurationPrefixCache.Lookup secondMiss =
                cache.resolve(second, () -> batch("second-payload"));
        LobbyConfigurationPrefixCache.Lookup firstHit =
                cache.resolve(first, () -> batch("must-not-compile"));

        assertFalse(firstMiss.hit());
        assertFalse(secondMiss.hit());
        assertTrue(firstHit.hit());
        assertEquals(
                firstMiss.batch().sequenceSha256(),
                firstHit.batch().sequenceSha256());
        assertFalse(firstMiss.batch().sequenceSha256().equals(
                secondMiss.batch().sequenceSha256()));
        assertEquals(2, cache.snapshot().misses());
        assertEquals(1, cache.snapshot().hits());
    }

    @Test
    void nonCanonicalKeysAndBuilderReuseFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new LobbyConfigurationPrefixCache.Key(
                767,
                List.of(),
                List.of(new Channel("neoforge:config_file", "1", Flow.CLIENTBOUND, true)),
                List.of("z:test", "a:test"),
                List.of(),
                "",
                "",
                ""));

        VelocityPluginMessageBatchSender.Batch.Builder builder =
                VelocityPluginMessageBatchSender.Batch.builder("one-shot");
        builder.addOwned("neoforge:network", new byte[] {1});
        builder.build();
        assertThrows(IllegalStateException.class, builder::build);
        assertThrows(IllegalStateException.class,
                () -> builder.addOwned("neoforge:network", new byte[] {2}));
    }

    private static LobbyConfigurationPrefixCache.Key key(
            String profileId, List<String> configs) {
        return key(profileId, configs, "");
    }

    private static LobbyConfigurationPrefixCache.Key key(
            String profileId, List<String> configs, String payloadSequenceSha256) {
        return new LobbyConfigurationPrefixCache.Key(
                767,
                List.of(new Channel("test:play", "1", Flow.SERVERBOUND, false)),
                List.of(new Channel(
                        "neoforge:config_file", "1", Flow.CLIENTBOUND, true)),
                List.of("minecraft:register", "neoforge:network"),
                configs,
                payloadSequenceSha256,
                profileId,
                "");
    }

    private static VelocityPluginMessageBatchSender.Batch batch(String value) {
        return VelocityPluginMessageBatchSender.Batch.builder("test-" + value)
                .addOwned("neoforge:network", value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build();
    }
}
