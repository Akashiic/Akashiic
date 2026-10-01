package org.bukkit.configuration.file;

import org.bukkit.configuration.ConfigurationSection;

public abstract class FileConfiguration implements ConfigurationSection {
    public abstract Object get(String path);

    public abstract boolean getBoolean(String path, boolean fallback);

    public abstract ConfigurationSection getConfigurationSection(String path);

    public abstract double getDouble(String path, double fallback);

    public abstract int getInt(String path, int fallback);

    public abstract String getString(String path);
}
