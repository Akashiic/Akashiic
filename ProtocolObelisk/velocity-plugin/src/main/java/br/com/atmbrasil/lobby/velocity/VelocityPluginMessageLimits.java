package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;
import java.util.function.Function;

/** Reads Velocity's direction-specific plugin-message limits exactly once at startup. */
final class VelocityPluginMessageLimits {
    static final String ALL_DIRECTIONS_PROPERTY =
            "velocity.max-plugin-message-payload-size";
    static final String SERVERBOUND_PROPERTY =
            "velocity.max-plugin-message-payload-size.serverbound";
    static final String CLIENTBOUND_PROPERTY =
            "velocity.max-plugin-message-payload-size.clientbound";
    static final int DEFAULT_SERVERBOUND_BYTES = 32_767;
    static final int DEFAULT_CLIENTBOUND_BYTES = 1_048_576;

    private VelocityPluginMessageLimits() {
    }

    static Limits fromSystemProperties() {
        return inspect(System::getProperty);
    }

    static Limits inspect(Function<String, String> propertyLookup) {
        Objects.requireNonNull(propertyLookup, "propertyLookup");
        String allDirections = propertyLookup.apply(ALL_DIRECTIONS_PROPERTY);
        if (allDirections != null) {
            int value = requirePositiveInteger(ALL_DIRECTIONS_PROPERTY, allDirections);
            return new Limits(value, value, true);
        }
        int serverbound = optionalPositiveInteger(
                SERVERBOUND_PROPERTY,
                propertyLookup.apply(SERVERBOUND_PROPERTY),
                DEFAULT_SERVERBOUND_BYTES);
        int clientbound = optionalPositiveInteger(
                CLIENTBOUND_PROPERTY,
                propertyLookup.apply(CLIENTBOUND_PROPERTY),
                DEFAULT_CLIENTBOUND_BYTES);
        return new Limits(serverbound, clientbound, false);
    }

    static String recommendedArguments(int serverboundBytes, int clientboundBytes) {
        if (serverboundBytes < 1 || clientboundBytes < 1) {
            throw new IllegalArgumentException("Velocity payload limits must be positive");
        }
        return "-D" + SERVERBOUND_PROPERTY + '=' + serverboundBytes
                + " -D" + CLIENTBOUND_PROPERTY + '=' + clientboundBytes;
    }

    private static int optionalPositiveInteger(String property, String value, int fallback) {
        return value == null ? fallback : requirePositiveInteger(property, value);
    }

    private static int requirePositiveInteger(String property, String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException(
                        "JVM property -D" + property + " must be a positive integer");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "JVM property -D" + property + " must be a positive integer",
                    exception);
        }
    }

    record Limits(int serverboundBytes, int clientboundBytes, boolean sharedOverride) {
        Limits {
            if (serverboundBytes < 1 || clientboundBytes < 1) {
                throw new IllegalArgumentException("Velocity payload limits must be positive");
            }
        }
    }
}
