package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ModernLobbyRegisterSanitizerTest {
    private static final ModernLobbyRegisterSanitizer.Settings SETTINGS =
            new ModernLobbyRegisterSanitizer.Settings(
                    96,
                    new LinkedHashSet<>(List.of(
                            "minecraft:register",
                            "minecraft:unregister",
                            "voicechat:secret",
                            "voicechat:request_secret",
                            "test:kept")));

    private final ModernLobbyRegisterSanitizer sanitizer =
            new ModernLobbyRegisterSanitizer();

    @Test
    void rewritesAnUnboundedClientRegistrationToTheNegotiatedLobbyContract() {
        StringBuilder payload = new StringBuilder("minecraft:register");
        for (int index = 0; index < 1_500; index++) {
            payload.append('\0').append("bulk:channel_").append(index);
        }
        payload.append('\0').append("voicechat:secret");
        payload.append('\0').append("test:kept");

        ModernLobbyRegisterSanitizer.Decision decision = sanitizer.inspect(
                ModernLobbyRegisterSanitizer.REGISTER_CHANNEL,
                payload.toString().getBytes(StandardCharsets.US_ASCII),
                SETTINGS);

        assertEquals(ModernLobbyRegisterSanitizer.Action.REWRITE, decision.action());
        assertEquals(1_503, decision.originalChannelCount());
        assertEquals(
                List.of("minecraft:register", "voicechat:secret", "test:kept"),
                decision.retainedChannels());
        assertArrayEquals(
                "minecraft:register\0voicechat:secret\0test:kept"
                        .getBytes(StandardCharsets.US_ASCII),
                decision.rewrittenPayload());
    }

    @Test
    void filtersEveryPacketSoSeveralSmallRegistrationsCannotAccumulatePastTheAllowlist() {
        ModernLobbyRegisterSanitizer.Decision first = sanitizer.inspect(
                ModernLobbyRegisterSanitizer.REGISTER_CHANNEL,
                "test:kept\0first:unapproved".getBytes(StandardCharsets.US_ASCII),
                SETTINGS);
        ModernLobbyRegisterSanitizer.Decision second = sanitizer.inspect(
                ModernLobbyRegisterSanitizer.REGISTER_CHANNEL,
                "voicechat:secret\0second:unapproved".getBytes(StandardCharsets.US_ASCII),
                SETTINGS);

        LinkedHashSet<String> cumulative = new LinkedHashSet<>();
        cumulative.addAll(first.retainedChannels());
        cumulative.addAll(second.retainedChannels());
        assertEquals(new LinkedHashSet<>(List.of("test:kept", "voicechat:secret")), cumulative);
    }

    @Test
    void aLongUnapprovedChannelDoesNotEraseSafeRetainedChannels() {
        ModernLobbyRegisterSanitizer.Decision decision = sanitizer.inspect(
                ModernLobbyRegisterSanitizer.REGISTER_CHANNEL,
                (("long:" + "a".repeat(80)) + "\0test:kept")
                        .getBytes(StandardCharsets.US_ASCII),
                SETTINGS);

        assertEquals(ModernLobbyRegisterSanitizer.Action.REWRITE, decision.action());
        assertArrayEquals(
                "test:kept".getBytes(StandardCharsets.US_ASCII),
                decision.rewrittenPayload());
    }

    @Test
    void dropsMalformedOrEmptyIntersections() {
        assertEquals(
                ModernLobbyRegisterSanitizer.Action.DROP,
                sanitizer.inspect(
                                ModernLobbyRegisterSanitizer.REGISTER_CHANNEL,
                                "unknown:only".getBytes(StandardCharsets.US_ASCII),
                                SETTINGS)
                        .action());
        assertEquals(
                ModernLobbyRegisterSanitizer.Action.DROP,
                sanitizer.inspect(
                                ModernLobbyRegisterSanitizer.REGISTER_CHANNEL,
                                "test:kept\0\0voicechat:secret"
                                        .getBytes(StandardCharsets.US_ASCII),
                                SETTINGS)
                        .action());
    }
}
