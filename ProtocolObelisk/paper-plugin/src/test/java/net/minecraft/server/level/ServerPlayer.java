package net.minecraft.server.level;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Minimal Paper 1.21.1 reflection fixture. */
public final class ServerPlayer {
    public ServerGamePacketListenerImpl connection = new ServerGamePacketListenerImpl();
}
