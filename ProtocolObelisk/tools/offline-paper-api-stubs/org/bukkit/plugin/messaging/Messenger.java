package org.bukkit.plugin.messaging;

import org.bukkit.plugin.Plugin;

public interface Messenger {
    PluginMessageListenerRegistration registerIncomingPluginChannel(
            Plugin plugin,
            String channel,
            PluginMessageListener listener);

    void unregisterIncomingPluginChannel(
            Plugin plugin,
            String channel,
            PluginMessageListener listener);

    void registerOutgoingPluginChannel(Plugin plugin, String channel);

    void unregisterOutgoingPluginChannel(Plugin plugin, String channel);
}
