package org.bukkit.plugin;

import org.bukkit.event.Listener;

public interface PluginManager {
    void disablePlugin(Plugin plugin);

    Plugin getPlugin(String name);

    Plugin[] getPlugins();

    void registerEvents(Listener listener, Plugin plugin);
}
