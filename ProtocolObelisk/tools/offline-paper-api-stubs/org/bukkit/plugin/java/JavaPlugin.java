package org.bukkit.plugin.java;

import java.io.File;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

public class JavaPlugin implements Plugin {
    private boolean enabled;

    public FileConfiguration getConfig() {
        return null;
    }

    public File getDataFolder() {
        return null;
    }

    public Logger getLogger() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public PluginCommand getCommand(String name) {
        return null;
    }

    public Server getServer() {
        return null;
    }

    public void onDisable() {
        enabled = false;
    }

    public void onEnable() {
        enabled = true;
    }

    public void reloadConfig() {
    }

    public void saveDefaultConfig() {
    }

    public void saveResource(String resourcePath, boolean replace) {
    }
}
