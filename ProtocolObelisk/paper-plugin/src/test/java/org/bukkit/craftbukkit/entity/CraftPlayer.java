package org.bukkit.craftbukkit.entity;

import net.minecraft.server.level.ServerPlayer;

/** Minimal Paper 1.21.1 reflection fixture. */
public final class CraftPlayer {
    public ServerPlayer getHandle() {
        return new ServerPlayer();
    }
}
