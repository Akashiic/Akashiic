package com.velocitypowered.api.proxy;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Collection;
import java.util.Optional;
public interface ProxyServer {
    PluginManager getPluginManager();
    ChannelRegistrar getChannelRegistrar();
    Collection<RegisteredServer> getAllServers();
    Optional<RegisteredServer> getServer(String name);
}
