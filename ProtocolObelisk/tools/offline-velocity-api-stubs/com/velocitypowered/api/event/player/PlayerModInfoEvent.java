package com.velocitypowered.api.event.player;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.ModInfo;
import java.util.Objects;

public final class PlayerModInfoEvent {
    private final Player player;
    private final ModInfo modInfo;

    public PlayerModInfoEvent(Player player, ModInfo modInfo) {
        this.player = Objects.requireNonNull(player, "player");
        this.modInfo = Objects.requireNonNull(modInfo, "modInfo");
    }

    public Player getPlayer() {
        return player;
    }

    public ModInfo getModInfo() {
        return modInfo;
    }
}
