package com.velocitypowered.api.proxy;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.*;
import com.velocitypowered.api.util.ModInfo;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
public interface Player extends ChannelMessageSource, ChannelMessageSink {
    UUID getUniqueId();
    String getUsername();
    ProtocolVersion getProtocolVersion();
    Optional<ServerConnection> getCurrentServer();
    Optional<ModInfo> getModInfo();
    boolean isActive();
    boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data);
    void disconnect(Component component);
    void sendMessage(Component component);
}
