package br.com.atmbrasil.lobby.paper;

import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/** Bounded configuration for the optional Paper-side NecroTempus TAB transport. */
record PaperNecroTempusConfig(
        boolean enabled,
        int refreshTicks,
        int maximumTextBytes,
        int maximumLines,
        int maximumCompressedNbtBytes,
        boolean sendRemoveOnDisable) {

    private static final String PREFIX = "necrotempus-tab.";

    static PaperNecroTempusConfig from(ConfigurationSection root) {
        Objects.requireNonNull(root, "root");
        return fromValues(
                root.get(PREFIX + "enabled"),
                root.get(PREFIX + "refresh-ticks"),
                root.get(PREFIX + "maximum-text-bytes"),
                root.get(PREFIX + "maximum-lines"),
                root.get(PREFIX + "maximum-compressed-nbt-bytes"),
                root.get(PREFIX + "send-remove-on-disable"));
    }

    static PaperNecroTempusConfig defaults() {
        return fromValues(null, null, null, null, null, null);
    }

    static PaperNecroTempusConfig fromValues(
            Object enabled,
            Object refreshTicks,
            Object maximumTextBytes,
            Object maximumLines,
            Object maximumCompressedNbtBytes,
            Object sendRemoveOnDisable) {
        return new PaperNecroTempusConfig(
                strictBoolean(enabled, true, PREFIX + "enabled"),
                boundedInteger(refreshTicks, 2, 1, 200, PREFIX + "refresh-ticks"),
                boundedInteger(
                        maximumTextBytes,
                        16_384,
                        1,
                        60_000,
                        PREFIX + "maximum-text-bytes"),
                boundedInteger(maximumLines, 64, 1, 256, PREFIX + "maximum-lines"),
                boundedInteger(
                        maximumCompressedNbtBytes,
                        30_000,
                        1,
                        Short.MAX_VALUE,
                        PREFIX + "maximum-compressed-nbt-bytes"),
                strictBoolean(
                        sendRemoveOnDisable,
                        true,
                        PREFIX + "send-remove-on-disable"));
    }

    private static boolean strictBoolean(Object value, boolean fallback, String path) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean result) {
            return result;
        }
        throw new IllegalArgumentException(path + " must be true or false");
    }

    private static int boundedInteger(
            Object value,
            int fallback,
            int minimum,
            int maximum,
            String path) {
        if (value == null) {
            return fallback;
        }
        final long parsed;
        if (value instanceof Byte number) {
            parsed = number.longValue();
        } else if (value instanceof Short number) {
            parsed = number.longValue();
        } else if (value instanceof Integer number) {
            parsed = number.longValue();
        } else if (value instanceof Long number) {
            parsed = number;
        } else {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    path + " must be between " + minimum + " and " + maximum);
        }
        return Math.toIntExact(parsed);
    }
}
