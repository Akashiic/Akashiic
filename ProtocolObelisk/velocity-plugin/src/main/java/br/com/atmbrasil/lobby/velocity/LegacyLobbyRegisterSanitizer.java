package br.com.atmbrasil.lobby.velocity;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure policy for bounding Velocity's protocol-5 client-channel replay into the Paper lobby. */
final class LegacyLobbyRegisterSanitizer {
    static final int MAXIMUM_INSPECTION_BYTES = 1_048_576;
    static final int MAXIMUM_CHANNEL_TOKEN_BYTES = 64;
    private static final Pattern SAFE_CHANNEL = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final String REGISTER_LEGACY = "REGISTER";
    private static final String UNREGISTER_LEGACY = "UNREGISTER";
    private static final String REGISTER_MODERN = "minecraft:register";
    private static final String UNREGISTER_MODERN = "minecraft:unregister";
    private static final byte[] LEGACY_FORGE_CHANNEL =
            "FML".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LEGACY_FORGE_HANDSHAKE_CHANNEL =
            "FML|HS".getBytes(StandardCharsets.US_ASCII);

    Decision inspect(String outerChannel, byte[] payload, Settings settings) {
        Objects.requireNonNull(outerChannel, "outerChannel");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(settings, "settings");
        if (!isControlChannel(outerChannel)) {
            return Decision.pass();
        }
        if (payload.length > MAXIMUM_INSPECTION_BYTES) {
            return Decision.drop(-1, "control payload exceeds the bounded inspection limit");
        }

        List<String> allowedChannels = List.copyOf(settings.allowedLobbyClientChannels());
        byte[][] allowedChannelBytes = new byte[allowedChannels.size()][];
        for (int index = 0; index < allowedChannels.size(); index++) {
            allowedChannelBytes[index] =
                    allowedChannels.get(index).getBytes(StandardCharsets.UTF_8);
        }
        ChannelScan scan = scanChannels(
                payload,
                settings.maximumLobbyClientChannels(),
                allowedChannelBytes);
        if (!scan.valid()) {
            return Decision.drop(-1, scan.failureReason());
        }
        if (scan.boundedChannelCount() <= settings.maximumLobbyClientChannels()) {
            return Decision.pass(scan.boundedChannelCount());
        }
        if (!scan.legacyForge()) {
            return Decision.drop(
                    scan.boundedChannelCount(),
                    "bulk channel replay exceeded the maximum without a legacy Forge marker");
        }

        boolean[] announcedAllowedChannels = scan.announcedAllowedChannels();
        int retainedCount = 0;
        for (boolean announced : announcedAllowedChannels) {
            if (announced) {
                retainedCount++;
            }
        }
        if (retainedCount == 0) {
            return Decision.drop(
                    scan.boundedChannelCount(),
                    "bulk Forge replay had no lobby-safe channels");
        }
        String[] retained = new String[retainedCount];
        int retainedIndex = 0;
        for (int index = 0; index < allowedChannels.size(); index++) {
            if (announcedAllowedChannels[index]) {
                retained[retainedIndex++] = allowedChannels.get(index);
            }
        }
        List<String> retainedChannels = List.of(retained);
        byte[] rewritten = String.join("\0", retainedChannels).getBytes(StandardCharsets.UTF_8);
        return Decision.rewrite(
                scan.boundedChannelCount(),
                rewritten,
                retainedChannels,
                "bulk legacy Forge client-channel replay");
    }

    static boolean isControlChannel(String channel) {
        return REGISTER_LEGACY.equals(channel)
                || UNREGISTER_LEGACY.equals(channel)
                || REGISTER_MODERN.equals(channel)
                || UNREGISTER_MODERN.equals(channel);
    }

    private static ChannelScan scanChannels(
            byte[] payload,
            int maximumChannels,
            byte[][] allowedChannelBytes) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(allowedChannelBytes, "allowedChannelBytes");
        boolean[] announcedAllowedChannels = new boolean[allowedChannelBytes.length];
        int boundedChannelCount = 0;
        boolean legacyForge = false;
        int tokenStart = 0;
        for (int index = 0; index <= payload.length; index++) {
            if (index < payload.length && payload[index] != 0) {
                continue;
            }
            int tokenLength = index - tokenStart;
            if (tokenLength == 0) {
                return ChannelScan.invalid("empty legacy channel token");
            }
            if (tokenLength > MAXIMUM_CHANNEL_TOKEN_BYTES) {
                return ChannelScan.invalid(
                        "legacy channel token exceeds the 64-byte limit");
            }
            if (!isWellFormedUtf8(payload, tokenStart, index)) {
                return ChannelScan.invalid("malformed UTF-8 legacy channel token");
            }

            // Once maximum+1 is observed the counter deliberately saturates. The remaining
            // payload is still scanned for UTF-8/token validity, Forge markers and the bounded
            // allowlist, but no unbounded token collection or attacker-controlled allocation is
            // possible.
            if (boundedChannelCount <= maximumChannels) {
                boundedChannelCount++;
            }
            legacyForge |= matchesToken(
                    payload, tokenStart, tokenLength, LEGACY_FORGE_CHANNEL);
            legacyForge |= matchesToken(
                    payload, tokenStart, tokenLength, LEGACY_FORGE_HANDSHAKE_CHANNEL);
            for (int allowedIndex = 0;
                    allowedIndex < allowedChannelBytes.length;
                    allowedIndex++) {
                if (!announcedAllowedChannels[allowedIndex]
                        && matchesToken(
                                payload,
                                tokenStart,
                                tokenLength,
                                allowedChannelBytes[allowedIndex])) {
                    announcedAllowedChannels[allowedIndex] = true;
                }
            }
            tokenStart = index + 1;
        }
        return ChannelScan.valid(
                boundedChannelCount, legacyForge, announcedAllowedChannels);
    }

    private static boolean matchesToken(
            byte[] payload, int tokenStart, int tokenLength, byte[] expected) {
        if (tokenLength != expected.length) {
            return false;
        }
        for (int index = 0; index < tokenLength; index++) {
            if (payload[tokenStart + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWellFormedUtf8(byte[] payload, int start, int end) {
        int index = start;
        while (index < end) {
            int first = payload[index] & 0xFF;
            if (first <= 0x7F) {
                index++;
                continue;
            }
            if (first >= 0xC2 && first <= 0xDF) {
                if (index + 1 >= end || !isContinuation(payload[index + 1])) {
                    return false;
                }
                index += 2;
                continue;
            }
            if (first == 0xE0) {
                if (index + 2 >= end
                        || !inRange(payload[index + 1], 0xA0, 0xBF)
                        || !isContinuation(payload[index + 2])) {
                    return false;
                }
                index += 3;
                continue;
            }
            if ((first >= 0xE1 && first <= 0xEC)
                    || (first >= 0xEE && first <= 0xEF)) {
                if (index + 2 >= end
                        || !isContinuation(payload[index + 1])
                        || !isContinuation(payload[index + 2])) {
                    return false;
                }
                index += 3;
                continue;
            }
            if (first == 0xED) {
                if (index + 2 >= end
                        || !inRange(payload[index + 1], 0x80, 0x9F)
                        || !isContinuation(payload[index + 2])) {
                    return false;
                }
                index += 3;
                continue;
            }
            if (first == 0xF0) {
                if (index + 3 >= end
                        || !inRange(payload[index + 1], 0x90, 0xBF)
                        || !isContinuation(payload[index + 2])
                        || !isContinuation(payload[index + 3])) {
                    return false;
                }
                index += 4;
                continue;
            }
            if (first >= 0xF1 && first <= 0xF3) {
                if (index + 3 >= end
                        || !isContinuation(payload[index + 1])
                        || !isContinuation(payload[index + 2])
                        || !isContinuation(payload[index + 3])) {
                    return false;
                }
                index += 4;
                continue;
            }
            if (first == 0xF4) {
                if (index + 3 >= end
                        || !inRange(payload[index + 1], 0x80, 0x8F)
                        || !isContinuation(payload[index + 2])
                        || !isContinuation(payload[index + 3])) {
                    return false;
                }
                index += 4;
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isContinuation(byte value) {
        return inRange(value, 0x80, 0xBF);
    }

    private static boolean inRange(byte value, int minimum, int maximum) {
        int unsigned = value & 0xFF;
        return unsigned >= minimum && unsigned <= maximum;
    }

    private record ChannelScan(
            boolean valid,
            int boundedChannelCount,
            boolean legacyForge,
            boolean[] announcedAllowedChannels,
            String failureReason) {
        private ChannelScan {
            announcedAllowedChannels = announcedAllowedChannels.clone();
            Objects.requireNonNull(failureReason, "failureReason");
        }

        @Override
        public boolean[] announcedAllowedChannels() {
            return announcedAllowedChannels.clone();
        }

        static ChannelScan valid(
                int boundedChannelCount,
                boolean legacyForge,
                boolean[] announcedAllowedChannels) {
            return new ChannelScan(
                    true,
                    boundedChannelCount,
                    legacyForge,
                    announcedAllowedChannels,
                    "");
        }

        static ChannelScan invalid(String reason) {
            return new ChannelScan(false, -1, false, new boolean[0], reason);
        }
    }

    record Settings(int maximumLobbyClientChannels, Set<String> allowedLobbyClientChannels) {
        Settings {
            if (maximumLobbyClientChannels < 1 || maximumLobbyClientChannels > 64) {
                throw new IllegalArgumentException(
                        "maximum lobby client channels must be between 1 and 64");
            }
            Objects.requireNonNull(allowedLobbyClientChannels, "allowedLobbyClientChannels");
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (String channel : allowedLobbyClientChannels) {
                Objects.requireNonNull(channel, "allowed lobby client channel");
                if (channel.length() > 64 || !SAFE_CHANNEL.matcher(channel).matches()) {
                    throw new IllegalArgumentException(
                            "allowed lobby client channels must be lowercase resource locations "
                                    + "of at most 64 characters");
                }
                ordered.add(channel);
            }
            if (ordered.size() > maximumLobbyClientChannels) {
                throw new IllegalArgumentException(
                        "allowed lobby client channels exceed the configured maximum");
            }
            allowedLobbyClientChannels = Collections.unmodifiableSet(ordered);
        }
    }

    enum Action {
        PASS,
        REWRITE,
        DROP
    }

    record Decision(
            Action action,
            int originalChannelCount,
            byte[] rewrittenPayload,
            List<String> retainedChannels,
            String reason) {
        Decision {
            Objects.requireNonNull(action, "action");
            rewrittenPayload = rewrittenPayload == null ? null : rewrittenPayload.clone();
            retainedChannels = List.copyOf(
                    Objects.requireNonNull(retainedChannels, "retainedChannels"));
            Objects.requireNonNull(reason, "reason");
            if (action == Action.REWRITE && rewrittenPayload == null) {
                throw new IllegalArgumentException("rewrite decision requires a payload");
            }
            if (action != Action.REWRITE && rewrittenPayload != null) {
                throw new IllegalArgumentException("only rewrite decisions may carry a payload");
            }
        }

        @Override
        public byte[] rewrittenPayload() {
            return rewrittenPayload == null ? null : rewrittenPayload.clone();
        }

        static Decision pass() {
            return pass(-1);
        }

        static Decision pass(int count) {
            return new Decision(Action.PASS, count, null, List.of(), "pass");
        }

        static Decision rewrite(
                int count, byte[] payload, List<String> retainedChannels, String reason) {
            return new Decision(Action.REWRITE, count, payload, retainedChannels, reason);
        }

        static Decision drop(int count, String reason) {
            return new Decision(Action.DROP, count, null, List.of(), reason);
        }
    }
}
