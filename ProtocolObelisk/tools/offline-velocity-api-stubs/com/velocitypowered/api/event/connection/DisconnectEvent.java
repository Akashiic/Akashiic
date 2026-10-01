package com.velocitypowered.api.event.connection;

import com.velocitypowered.api.proxy.Player;
import java.util.Objects;

public final class DisconnectEvent {
    private final Player player;
    private final LoginStatus loginStatus;

    public DisconnectEvent(Player player, LoginStatus loginStatus) {
        this.player = Objects.requireNonNull(player, "player");
        this.loginStatus = Objects.requireNonNull(loginStatus, "loginStatus");
    }

    public Player getPlayer() {
        return player;
    }

    public LoginStatus getLoginStatus() {
        return loginStatus;
    }

    public enum LoginStatus {
        PRE_LOGIN,
        PRE_SERVER_JOIN,
        SUCCESSFUL_LOGIN,
        CONFLICTING_LOGIN,
        CANCELLED_BY_PROXY,
        CANCELLED_BY_USER
    }
}
