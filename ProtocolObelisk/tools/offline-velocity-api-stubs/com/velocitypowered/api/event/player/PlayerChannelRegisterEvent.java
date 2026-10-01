package com.velocitypowered.api.event.player;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import java.util.List;
import java.util.Objects;

public final class PlayerChannelRegisterEvent {
    private final Player player;
    private final List<ChannelIdentifier> channels;

    public PlayerChannelRegisterEvent(Player player, List<ChannelIdentifier> channels) {
        this.player = Objects.requireNonNull(player, "player");
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
    }

    public Player getPlayer() {
        return player;
    }

    public List<ChannelIdentifier> getChannels() {
        return channels;
    }
}
