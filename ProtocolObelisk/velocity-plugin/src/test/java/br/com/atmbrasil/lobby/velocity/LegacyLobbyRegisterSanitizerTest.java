package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class LegacyLobbyRegisterSanitizerTest {
    private static final LegacyLobbyRegisterSanitizer.Settings SETTINGS =
            new LegacyLobbyRegisterSanitizer.Settings(
                    16, orderedSet("necrotempus:main", "proxy:secondary"));
    private final LegacyLobbyRegisterSanitizer sanitizer =
            new LegacyLobbyRegisterSanitizer();

    @Test
    void rewritesBulkForgeReplayForAllFourControlAliasesInConfiguredOrder() {
        byte[] payload = forgePayload(
                157,
                "proxy:secondary",
                "necrotempus:main",
                "proxy:secondary");

        for (String outerChannel : List.of(
                "REGISTER", "UNREGISTER", "minecraft:register", "minecraft:unregister")) {
            LegacyLobbyRegisterSanitizer.Decision decision =
                    sanitizer.inspect(outerChannel, payload, SETTINGS);

            assertEquals(LegacyLobbyRegisterSanitizer.Action.REWRITE, decision.action());
            assertEquals(17, decision.originalChannelCount());
            assertEquals(
                    List.of("necrotempus:main", "proxy:secondary"),
                    decision.retainedChannels());
            assertArrayEquals(
                    "necrotempus:main\0proxy:secondary".getBytes(StandardCharsets.UTF_8),
                    decision.rewrittenPayload());
        }
    }

    @Test
    void smallForgeSetPassesAndLargeNonForgeSetFailsClosed() {
        LegacyLobbyRegisterSanitizer.Decision small = sanitizer.inspect(
                "REGISTER",
                "FML\0FML|HS\0necrotempus:main".getBytes(StandardCharsets.UTF_8),
                SETTINGS);
        assertEquals(LegacyLobbyRegisterSanitizer.Action.PASS, small.action());
        assertEquals(3, small.originalChannelCount());

        StringBuilder nonForge = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            if (index != 0) {
                nonForge.append('\0');
            }
            nonForge.append("proxy:channel_").append(index);
        }
        LegacyLobbyRegisterSanitizer.Decision large = sanitizer.inspect(
                "minecraft:register",
                nonForge.toString().getBytes(StandardCharsets.UTF_8),
                SETTINGS);
        assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, large.action());
        assertEquals(17, large.originalChannelCount());
        assertTrue(large.reason().contains("without a legacy Forge marker"));
    }

    @Test
    void bulkForgeSetWithoutAnAllowedChannelDrops() {
        LegacyLobbyRegisterSanitizer.Decision decision = sanitizer.inspect(
                "REGISTER", forgePayload(30), SETTINGS);

        assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, decision.action());
        assertEquals(17, decision.originalChannelCount());
        assertTrue(decision.reason().contains("no lobby-safe channels"));
    }

    @Test
    void malformedUtf8AndOversizedControlPayloadDrop() {
        LegacyLobbyRegisterSanitizer.Decision malformed = sanitizer.inspect(
                "REGISTER", new byte[] {(byte) 0xC3, 0x28}, SETTINGS);
        assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, malformed.action());
        assertTrue(malformed.reason().contains("malformed UTF-8"));

        LegacyLobbyRegisterSanitizer.Decision oversized = sanitizer.inspect(
                "minecraft:register",
                new byte[LegacyLobbyRegisterSanitizer.MAXIMUM_INSPECTION_BYTES + 1],
                SETTINGS);
        assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, oversized.action());
        assertTrue(oversized.reason().contains("bounded inspection limit"));
    }

    @Test
    void ordinaryPluginMessageBypassesPayloadParsing() {
        LegacyLobbyRegisterSanitizer.Decision decision = sanitizer.inspect(
                "necrotempus:main", new byte[] {(byte) 0xC3, 0x28}, SETTINGS);

        assertEquals(LegacyLobbyRegisterSanitizer.Action.PASS, decision.action());
        assertEquals(-1, decision.originalChannelCount());
    }

    @Test
    void emptyPayloadLeadingTrailingAndRepeatedSeparatorsFailClosed() {
        byte[][] invalidPayloads = {
            new byte[0],
            "\0FML".getBytes(StandardCharsets.UTF_8),
            "FML\0".getBytes(StandardCharsets.UTF_8),
            "FML\0\0FML|HS".getBytes(StandardCharsets.UTF_8)
        };

        for (byte[] payload : invalidPayloads) {
            LegacyLobbyRegisterSanitizer.Decision decision =
                    sanitizer.inspect("REGISTER", payload, SETTINGS);
            assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, decision.action());
            assertTrue(decision.reason().contains("empty legacy channel token"));
        }
    }

    @Test
    void tokenOverByteLimitFailsClosedBeforeAndAfterThreshold() {
        byte[] firstTokenTooLarge = "x".repeat(
                        LegacyLobbyRegisterSanitizer.MAXIMUM_CHANNEL_TOKEN_BYTES + 1)
                .getBytes(StandardCharsets.UTF_8);
        LegacyLobbyRegisterSanitizer.Decision first = sanitizer.inspect(
                "REGISTER", firstTokenTooLarge, SETTINGS);
        assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, first.action());
        assertTrue(first.reason().contains("64-byte limit"));

        StringBuilder afterThreshold = forgePayloadText(30);
        afterThreshold.append('\0').append("x".repeat(
                LegacyLobbyRegisterSanitizer.MAXIMUM_CHANNEL_TOKEN_BYTES + 1));
        LegacyLobbyRegisterSanitizer.Decision late = sanitizer.inspect(
                "REGISTER",
                afterThreshold.toString().getBytes(StandardCharsets.UTF_8),
                SETTINGS);
        assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, late.action());
        assertTrue(late.reason().contains("64-byte limit"));
    }

    @Test
    void strictUtf8RejectsEveryDangerousEncodingClassEvenAfterThreshold() {
        byte[][] malformedTokens = {
            {(byte) 0x80},
            {(byte) 0xC0, (byte) 0x80},
            {(byte) 0xC3, 0x28},
            {(byte) 0xE0, (byte) 0x80, (byte) 0x80},
            {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
            {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80},
            {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
            {(byte) 0xF0, (byte) 0x9F, (byte) 0x98}
        };
        for (byte[] malformed : malformedTokens) {
            LegacyLobbyRegisterSanitizer.Decision decision = sanitizer.inspect(
                    "REGISTER", malformed, SETTINGS);
            assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, decision.action());
            assertTrue(decision.reason().contains("malformed UTF-8"));
        }

        StringBuilder prefix = forgePayloadText(30);
        byte[] validPrefix = (prefix + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] lateMalformed = new byte[validPrefix.length + 2];
        System.arraycopy(validPrefix, 0, lateMalformed, 0, validPrefix.length);
        lateMalformed[validPrefix.length] = (byte) 0xC3;
        lateMalformed[validPrefix.length + 1] = 0x28;
        LegacyLobbyRegisterSanitizer.Decision late = sanitizer.inspect(
                "REGISTER", lateMalformed, SETTINGS);
        assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, late.action());
        assertTrue(late.reason().contains("malformed UTF-8"));

        LegacyLobbyRegisterSanitizer.Decision wellFormed = sanitizer.inspect(
                "REGISTER", "canal:ação\0emoji:😀".getBytes(StandardCharsets.UTF_8), SETTINGS);
        assertEquals(LegacyLobbyRegisterSanitizer.Action.PASS, wellFormed.action());
        assertEquals(2, wellFormed.originalChannelCount());
    }

    @Test
    void nulDensePayloadSaturatesAtMaximumPlusOneWithoutTokenMaterialization() {
        byte[] nulDense = new byte[
                LegacyLobbyRegisterSanitizer.MAXIMUM_INSPECTION_BYTES - 1];
        for (int index = 0; index < nulDense.length; index += 2) {
            nulDense[index] = 'a';
        }

        LegacyLobbyRegisterSanitizer.Decision decision = sanitizer.inspect(
                "REGISTER", nulDense, SETTINGS);

        assertEquals(LegacyLobbyRegisterSanitizer.Action.DROP, decision.action());
        assertEquals(17, decision.originalChannelCount());
        assertTrue(decision.reason().contains("without a legacy Forge marker"));
    }

    @Test
    void boundedScannerFindsAllowedChannelAfterThreshold() {
        StringBuilder payload = forgePayloadText(40);
        payload.append("\0necrotempus:main");

        LegacyLobbyRegisterSanitizer.Decision decision = sanitizer.inspect(
                "REGISTER", payload.toString().getBytes(StandardCharsets.UTF_8), SETTINGS);

        assertEquals(LegacyLobbyRegisterSanitizer.Action.REWRITE, decision.action());
        assertEquals(17, decision.originalChannelCount());
        assertEquals(List.of("necrotempus:main"), decision.retainedChannels());
        assertArrayEquals(
                "necrotempus:main".getBytes(StandardCharsets.UTF_8),
                decision.rewrittenPayload());
    }

    @Test
    void boundedScannerFindsForgeMarkerAfterThreshold() {
        StringBuilder payload = new StringBuilder("proxy:first");
        for (int index = 0; index < 30; index++) {
            payload.append('\0').append("proxy:channel_").append(index);
        }
        payload.append("\0FML\0necrotempus:main");

        LegacyLobbyRegisterSanitizer.Decision decision = sanitizer.inspect(
                "REGISTER", payload.toString().getBytes(StandardCharsets.UTF_8), SETTINGS);

        assertEquals(LegacyLobbyRegisterSanitizer.Action.REWRITE, decision.action());
        assertEquals(17, decision.originalChannelCount());
        assertEquals(List.of("necrotempus:main"), decision.retainedChannels());
    }

    @Test
    void decisionOwnsItsRewrittenPayload() {
        LegacyLobbyRegisterSanitizer.Decision decision = sanitizer.inspect(
                "REGISTER", forgePayload(30, "necrotempus:main"), SETTINGS);
        byte[] first = decision.rewrittenPayload();
        byte[] second = decision.rewrittenPayload();

        assertNotSame(first, second);
        first[0] = 0;
        assertArrayEquals(
                "necrotempus:main".getBytes(StandardCharsets.UTF_8),
                decision.rewrittenPayload());
    }

    @Test
    void settingsAreStrictBoundedCanonicalAndDefensivelyOrdered() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LegacyLobbyRegisterSanitizer.Settings(0, Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LegacyLobbyRegisterSanitizer.Settings(65, Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LegacyLobbyRegisterSanitizer.Settings(
                        16, Set.of("FML|HS")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LegacyLobbyRegisterSanitizer.Settings(
                        1, orderedSet("one:main", "two:main")));

        LinkedHashSet<String> source = orderedSet("one:main", "two:main");
        LegacyLobbyRegisterSanitizer.Settings settings =
                new LegacyLobbyRegisterSanitizer.Settings(2, source);
        source.clear();
        assertEquals(List.of("one:main", "two:main"),
                List.copyOf(settings.allowedLobbyClientChannels()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> settings.allowedLobbyClientChannels().add("three:main"));
    }

    private static byte[] forgePayload(int additionalChannels, String... retained) {
        StringBuilder payload = new StringBuilder("FML\0FML|HS");
        for (String channel : retained) {
            payload.append('\0').append(channel);
        }
        for (int index = 0; index < additionalChannels; index++) {
            payload.append('\0').append("mod").append(index).append(":channel");
        }
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static StringBuilder forgePayloadText(int additionalChannels) {
        StringBuilder payload = new StringBuilder("FML\0FML|HS");
        for (int index = 0; index < additionalChannels; index++) {
            payload.append('\0').append("mod").append(index).append(":channel");
        }
        return payload;
    }

    @SafeVarargs
    private static <T> LinkedHashSet<T> orderedSet(T... values) {
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (T value : values) {
            result.add(value);
        }
        return result;
    }
}
