package com.velocitypowered.api.event.player;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Objects;
import java.util.Optional;

public final class ServerPreConnectEvent {
    private final Player player;
    private final RegisteredServer originalServer;
    private final RegisteredServer previousServer;
    private ServerResult result;

    public ServerPreConnectEvent(Player player, RegisteredServer originalServer) {
        this(player, originalServer, null);
    }

    public ServerPreConnectEvent(
            Player player,
            RegisteredServer originalServer,
            RegisteredServer previousServer) {
        this.player = Objects.requireNonNull(player, "player");
        this.originalServer = Objects.requireNonNull(originalServer, "originalServer");
        this.previousServer = previousServer;
        this.result = ServerResult.allowed(originalServer);
    }

    public Player getPlayer() {
        return player;
    }

    public RegisteredServer getOriginalServer() {
        return originalServer;
    }

    public RegisteredServer getPreviousServer() {
        return previousServer;
    }

    public ServerResult getResult() {
        return result;
    }

    public void setResult(ServerResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    public static class ServerResult {
        private final RegisteredServer server;

        private ServerResult(RegisteredServer server) {
            this.server = server;
        }

        public static ServerResult allowed(RegisteredServer server) {
            return new ServerResult(Objects.requireNonNull(server, "server"));
        }

        public static ServerResult denied() {
            return new ServerResult(null);
        }

        public boolean isAllowed() {
            return server != null;
        }

        public Optional<RegisteredServer> getServer() {
            return Optional.ofNullable(server);
        }
    }
}
