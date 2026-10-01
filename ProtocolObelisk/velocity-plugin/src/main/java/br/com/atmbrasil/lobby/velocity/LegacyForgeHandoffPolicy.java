package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;
import java.util.Set;

/** Pure, fail-closed scope policy for the Minecraft 1.7.10 Forge handoff guard. */
final class LegacyForgeHandoffPolicy {
    static final int MINECRAFT_1_7_10_PROTOCOL = 5;
    static final String REGISTER_CHANNEL = "REGISTER";
    static final String UNREGISTER_CHANNEL = "UNREGISTER";

    private LegacyForgeHandoffPolicy() {
    }

    static ControlOperation classify(String outerChannel) {
        Objects.requireNonNull(outerChannel, "outerChannel");
        return switch (outerChannel) {
            case REGISTER_CHANNEL -> ControlOperation.REGISTER;
            case UNREGISTER_CHANNEL -> ControlOperation.UNREGISTER;
            default -> ControlOperation.OTHER;
        };
    }

    static boolean shouldSuppress(
            int clientProtocol,
            String guardedBackend,
            String lobbyServer,
            String inFlightTarget,
            Set<String> allowedTargets,
            ControlOperation operation) {
        Objects.requireNonNull(guardedBackend, "guardedBackend");
        Objects.requireNonNull(lobbyServer, "lobbyServer");
        Objects.requireNonNull(allowedTargets, "allowedTargets");
        Objects.requireNonNull(operation, "operation");
        return clientProtocol == MINECRAFT_1_7_10_PROTOCOL
                && guardedBackend.equals(lobbyServer)
                && inFlightTarget != null
                && allowedTargets.contains(inFlightTarget)
                && operation != ControlOperation.OTHER;
    }

    enum ControlOperation {
        REGISTER,
        UNREGISTER,
        OTHER
    }
}
