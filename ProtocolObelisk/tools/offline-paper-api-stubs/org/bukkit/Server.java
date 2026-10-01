package org.bukkit;

import java.util.Collection;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitScheduler;

public interface Server {
    PluginManager getPluginManager();

    Messenger getMessenger();

    BukkitScheduler getScheduler();

    Collection<? extends Player> getOnlinePlayers();

    Player getPlayer(UUID uuid);

    World getWorld(String name);
}
