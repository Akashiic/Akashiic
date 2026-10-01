package br.com.atmbrasil.lobby.velocity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reduces a modern client's channel registration to the bounded contract owned by the lobby.
 *
 * <p>Velocity must retain the original client-side capability set for proxy plugins and later
 * backend transitions. Only the copy already destined for the Paper lobby is filtered. Applying
 * the intersection on every REGISTER/UNREGISTER packet also prevents a client from bypassing the
 * backend's cumulative channel ceiling with several individually small packets.</p>
 */
final class ModernLobbyRegisterSanitizer {
    static final String REGISTER_CHANNEL = "minecraft:register";
    static final String UNREGISTER_CHANNEL = "minecraft:unregister";
    static final int MAXIMUM_INSPECTION_BYTES = 1_048_576;
    static final int MAXIMUM_INSPECTED_CHANNEL_TOKEN_BYTES = 256;
    static final int MAXIMUM_FORWARDED_CHANNEL_TOKEN_BYTES = 64;

    private static final Pattern SAFE_CHANNEL = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");

    Decision inspect(String outerChannel, byte[] payload, Settings settings) {
        Objects.requireNonNull(outerChannel, "outerChannel");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(settings, "settings");
        if (!isControlChannel(outerChannel)) {
            return Decision.pass(0, List.of());
        }
        if (payload.length > MAXIMUM_INSPECTION_BYTES) {
            return Decision.drop(-1, "control payload exceeds the bounded inspection limit");
        }

        LinkedHashSet<String> retained = new LinkedHashSet<>();
        int originalChannelCount = 0;
        int tokenStart = 0;
        for (int index = 0; index <= payload.length; index++) {
            if (index < payload.length && payload[index] != 0) {
                continue;
            }

            // Velocity's String.split("\\0") ignores one trailing empty component. Mirror that
            // accepted wire form, while still rejecting an empty component in the middle.
            if (index == payload.length && tokenStart == payload.length) {
                break;
            }

            int tokenLength = index - tokenStart;
            if (tokenLength == 0) {
                return Decision.drop(originalChannelCount,
                        "empty modern channel token");
            }
            if (tokenLength > MAXIMUM_INSPECTED_CHANNEL_TOKEN_BYTES) {
                return Decision.drop(originalChannelCount,
                        "modern channel token exceeds the 256-byte inspection limit");
            }
            if (!isAscii(payload, tokenStart, index)) {
                return Decision.drop(originalChannelCount,
                        "modern channel token is not canonical ASCII");
            }

            String channel = new String(
                    payload, tokenStart, tokenLength, StandardCharsets.US_ASCII);
            if (!isCanonicalChannel(channel)) {
                return Decision.drop(originalChannelCount,
                        "invalid modern channel identifier");
            }
            originalChannelCount++;
            if (settings.allowedLobbyChannels().contains(channel)) {
                retained.add(channel);
            }
            tokenStart = index + 1;
        }

        if (retained.isEmpty()) {
            return Decision.drop(originalChannelCount,
                    "registration contained no negotiated lobby channels");
        }
        if (retained.size() > settings.maximumForwardedChannels()) {
            // Settings validation makes this unreachable unless memory was corrupted. Keeping the
            // guard fail-closed here avoids relying on that invariant at a network boundary.
            return Decision.drop(originalChannelCount,
                    "negotiated lobby channel set exceeds the forwarding limit");
        }

        List<String> retainedChannels = List.copyOf(retained);
        byte[] rewritten = String.join("\0", retainedChannels)
                .getBytes(StandardCharsets.US_ASCII);
        if (Arrays.equals(payload, rewritten)) {
            return Decision.pass(originalChannelCount, retainedChannels);
        }
        return Decision.rewrite(
                originalChannelCount,
                rewritten,
                retainedChannels,
                "intersected client registration with the negotiated lobby contract");
    }

    static boolean isControlChannel(String channel) {
        return REGISTER_CHANNEL.equals(channel) || UNREGISTER_CHANNEL.equals(channel);
    }

    static boolean isForwardableChannel(String channel) {
        if (channel == null || channel.isEmpty()) {
            return false;
        }
        byte[] bytes = channel.getBytes(StandardCharsets.US_ASCII);
        return bytes.length <= MAXIMUM_FORWARDED_CHANNEL_TOKEN_BYTES
                && isCanonicalChannel(channel);
    }

    private static boolean isCanonicalChannel(String channel) {
        byte[] bytes = channel.getBytes(StandardCharsets.US_ASCII);
        return bytes.length <= MAXIMUM_INSPECTED_CHANNEL_TOKEN_BYTES
                && channel.equals(new String(bytes, StandardCharsets.US_ASCII))
                && SAFE_CHANNEL.matcher(channel).matches();
    }

    private static boolean isAscii(byte[] payload, int start, int end) {
        for (int index = start; index < end; index++) {
            if ((payload[index] & 0x80) != 0) {
                return false;
            }
        }
        return true;
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
            Objects.requireNonNull(rewrittenPayload, "rewrittenPayload");
            Objects.requireNonNull(retainedChannels, "retainedChannels");
            Objects.requireNonNull(reason, "reason");
            rewrittenPayload = rewrittenPayload.clone();
            retainedChannels = List.copyOf(retainedChannels);
            if (action == Action.REWRITE && rewrittenPayload.length == 0) {
                throw new IllegalArgumentException(
                        "rewrite action requires a non-empty payload");
            }
            if (action != Action.REWRITE && rewrittenPayload.length != 0) {
                throw new IllegalArgumentException(
                        "only rewrite action may carry replacement bytes");
            }
            if (action == Action.DROP && !retainedChannels.isEmpty()) {
                throw new IllegalArgumentException(
                        "drop action cannot retain channels");
            }
        }

        @Override
        public byte[] rewrittenPayload() {
            return rewrittenPayload.clone();
        }

        static Decision pass(int originalChannelCount, List<String> retainedChannels) {
            return new Decision(
                    Action.PASS,
                    originalChannelCount,
                    new byte[0],
                    retainedChannels,
                    "registration already matches the negotiated lobby contract");
        }

        static Decision rewrite(
                int originalChannelCount,
                byte[] rewrittenPayload,
                List<String> retainedChannels,
                String reason) {
            return new Decision(
                    Action.REWRITE,
                    originalChannelCount,
                    rewrittenPayload,
                    retainedChannels,
                    reason);
        }

        static Decision drop(int originalChannelCount, String reason) {
            return new Decision(
                    Action.DROP,
                    originalChannelCount,
                    new byte[0],
                    List.of(),
                    reason);
        }
    }

    record Settings(int maximumForwardedChannels, Set<String> allowedLobbyChannels) {
        Settings {
            if (maximumForwardedChannels < 1 || maximumForwardedChannels > 127) {
                throw new IllegalArgumentException(
                        "maximumForwardedChannels must be within 1..127");
            }
            Objects.requireNonNull(allowedLobbyChannels, "allowedLobbyChannels");
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (String channel : allowedLobbyChannels) {
                if (!isForwardableChannel(channel)) {
                    throw new IllegalArgumentException(
                            "invalid Paper-safe lobby channel: " + channel);
                }
                if (!ordered.add(channel)) {
                    throw new IllegalArgumentException(
                            "duplicate Paper-safe lobby channel: " + channel);
                }
            }
            if (ordered.isEmpty()) {
                throw new IllegalArgumentException(
                        "allowedLobbyChannels must not be empty");
            }
            if (ordered.size() > maximumForwardedChannels) {
                throw new IllegalArgumentException(
                        "allowedLobbyChannels exceeds maximumForwardedChannels");
            }
            allowedLobbyChannels = Collections.unmodifiableSet(ordered);
        }
    }
}
