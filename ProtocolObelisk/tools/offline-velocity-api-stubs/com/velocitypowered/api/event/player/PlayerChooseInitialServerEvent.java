package com.velocitypowered.api.event.player;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
public class PlayerChooseInitialServerEvent {
    public Optional<RegisteredServer> getInitialServer(){ return Optional.empty(); }
    public Player getPlayer(){ return null; }
}
