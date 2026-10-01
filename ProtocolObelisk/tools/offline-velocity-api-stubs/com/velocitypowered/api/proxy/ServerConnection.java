package com.velocitypowered.api.proxy;
import com.velocitypowered.api.proxy.messages.*;
import com.velocitypowered.api.proxy.server.ServerInfo;
public interface ServerConnection extends ChannelMessageSource, ChannelMessageSink {
    Player getPlayer();
    ServerInfo getServerInfo();
    boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data);
}
