package br.com.atmbrasil.lobby.paper;

import java.util.Objects;

/** Separates lobby membership from the restrictions that trusted builders may bypass. */
final class LobbyProtectionPolicy {
    private LobbyProtectionPolicy() {
    }

    static boolean isInScope(
            boolean protectEntireServer,
            String lobbyWorld,
            String playerWorld) {
        Objects.requireNonNull(lobbyWorld, "lobbyWorld");
        Objects.requireNonNull(playerWorld, "playerWorld");
        return protectEntireServer || playerWorld.equals(lobbyWorld);
    }

    static boolean shouldRestrict(
            boolean protectEntireServer,
            String lobbyWorld,
            String playerWorld,
            boolean hasBuildBypass) {
        return isInScope(protectEntireServer, lobbyWorld, playerWorld) && !hasBuildBypass;
    }

    static boolean shouldCancelGenericUiEvents(
            boolean cancelGenericUiEvents,
            boolean protectEntireServer,
            String lobbyWorld,
            String playerWorld,
            boolean hasBuildBypass) {
        return cancelGenericUiEvents && shouldRestrict(
                protectEntireServer, lobbyWorld, playerWorld, hasBuildBypass);
    }
}
