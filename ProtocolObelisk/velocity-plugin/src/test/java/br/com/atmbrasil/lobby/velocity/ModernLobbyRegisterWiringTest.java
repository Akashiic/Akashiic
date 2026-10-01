package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exercises the production session allowlist, including the cap before the wire sanitizer. */
final class ModernLobbyRegisterWiringTest {
    private static final String BUNGEE = "bungeecord:main";

    @Test
    void singletonProxyTransportSurvivesBothRegisterAndUnregister() throws Exception {
        Harness harness = new Harness();
        ModernLobbyRegisterSanitizer.Settings settings = harness.settings();
        for (String outer : List.of("minecraft:register", "minecraft:unregister")) {
            ModernLobbyRegisterSanitizer.Decision decision = inspect(outer, BUNGEE, settings);
            assertEquals(ModernLobbyRegisterSanitizer.Action.PASS, decision.action());
            assertEquals(List.of(BUNGEE), decision.retainedChannels());
        }
        assertEquals(Set.of(), get(harness.session, "playSinkChannelIds"));
        assertEquals(Map.of(), get(harness.plugin, "globalPlaySinkChannels"));
    }

    @Test
    void mixedRegistrationRetainsProxyTransportAndExcludesUnrelatedChannels() throws Exception {
        Harness harness = new Harness();
        ModernLobbyRegisterSanitizer.Decision decision = inspect(
                "minecraft:register", "unrelated:channel\0bungeecord:main\0voicechat:secret",
                harness.settings());
        assertEquals(ModernLobbyRegisterSanitizer.Action.REWRITE, decision.action());
        assertEquals(List.of(BUNGEE, "voicechat:secret"), decision.retainedChannels());
        assertEquals("bungeecord:main\0voicechat:secret",
                new String(decision.rewrittenPayload(), StandardCharsets.US_ASCII));
        assertEquals(ModernLobbyRegisterSanitizer.Action.DROP,
                inspect("minecraft:register", "unrelated:channel", harness.settings()).action());
    }

    @Test
    void proxySlotIsReservedBeforeOptionalChannelsFillTheUnchangedNinetySixCap()
            throws Exception {
        Harness harness = new Harness();
        Set<String> sinks = new LinkedHashSet<>();
        for (int index = 0; index < 200; index++) {
            sinks.add("optional:channel_" + index);
        }
        set(harness.session, "playSinkChannelIds", sinks);
        ModernLobbyRegisterSanitizer.Settings settings = harness.settings();
        assertEquals(96, settings.maximumForwardedChannels());
        assertEquals(96, settings.allowedLobbyChannels().size());
        assertEquals(BUNGEE, settings.allowedLobbyChannels().iterator().next());
        assertTrue(settings.allowedLobbyChannels().contains("minecraft:register"));
        assertTrue(settings.allowedLobbyChannels().contains("voicechat:secret"));
        ModernLobbyRegisterSanitizer.Decision decision = inspect("minecraft:register",
                String.join("\0", sinks) + "\0unrelated:channel\0" + BUNGEE, settings);
        assertEquals(ModernLobbyRegisterSanitizer.Action.REWRITE, decision.action());
        assertTrue(decision.retainedChannels().contains(BUNGEE));
        assertTrue(decision.retainedChannels().size() <= 96);
        assertFalse(decision.retainedChannels().contains("unrelated:channel"));
        assertEquals(sinks, get(harness.session, "playSinkChannelIds"));
        assertEquals(Map.of(), get(harness.plugin, "globalPlaySinkChannels"));
    }

    @Test
    void malformedTransportRegistrationStillFailsClosed() throws Exception {
        Harness harness = new Harness();
        for (String payload : List.of(BUNGEE + "\0\0voicechat:secret", "BungeeCord",
                BUNGEE + "\0invalid uppercase:channel")) {
            assertEquals(ModernLobbyRegisterSanitizer.Action.DROP,
                    inspect("minecraft:register", payload, harness.settings()).action());
        }
    }

    @Test
    void nonLobbyAndProtocolBypassSessionsKeepNativeRegistrationBehavior() throws Exception {
        Harness harness = new Harness();
        for (String state : List.of("OUTSIDE", "FAILED")) {
            setEnum(harness.session, "state", state);
            assertNull(harness.settings());
        }
        setEnum(harness.session, "state", "LOBBY_PLAY");
        setEnum(harness.session, "negotiation", "PROTOCOL_BYPASS");
        assertNull(harness.settings());
    }

    private static ModernLobbyRegisterSanitizer.Decision inspect(
            String outer, String payload, ModernLobbyRegisterSanitizer.Settings settings) {
        return new ModernLobbyRegisterSanitizer().inspect(
                outer, payload.getBytes(StandardCharsets.US_ASCII), settings);
    }

    private static final class Harness {
        private final Atm10LobbyVelocityPlugin plugin =
                new Atm10LobbyVelocityPlugin(null, null, Path.of("."));
        private final UUID playerUuid = UUID.randomUUID();
        private final Object session;

        @SuppressWarnings("unchecked")
        private Harness() throws Exception {
            Class<?> type = Class.forName(Atm10LobbyVelocityPlugin.class.getName() + "$BridgeSession");
            Constructor<?> constructor = type.getDeclaredConstructor(UUID.class);
            constructor.setAccessible(true);
            session = constructor.newInstance(playerUuid);
            setEnum(session, "state", "LOBBY_PLAY");
            setEnum(session, "negotiation", "NEOFORGE");
            ((Map<UUID, Object>) get(plugin, "sessions")).put(playerUuid, session);
        }

        private ModernLobbyRegisterSanitizer.Settings settings() throws Exception {
            Method method = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                    "modernLobbyRegisterSettings", UUID.class);
            method.setAccessible(true);
            return (ModernLobbyRegisterSanitizer.Settings) method.invoke(plugin, playerUuid);
        }
    }

    private static Object get(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static void setEnum(Object instance, String name, String value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object entry = Arrays.stream(field.getType().getEnumConstants())
                .filter(candidate -> ((Enum<?>) candidate).name().equals(value))
                .findFirst().orElseThrow();
        field.set(instance, entry);
    }
}
