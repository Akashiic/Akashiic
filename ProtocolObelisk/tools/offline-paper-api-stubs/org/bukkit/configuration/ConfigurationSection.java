package org.bukkit.configuration;

public interface ConfigurationSection {
    Object get(String path);

    boolean getBoolean(String path, boolean fallback);

    double getDouble(String path, double fallback);
}
