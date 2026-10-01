package org.bukkit.entity;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public interface Player extends HumanEntity, CommandSender {
    Collection<PotionEffect> getActivePotionEffects();

    PlayerInventory getInventory();

    String getName();

    UUID getUniqueId();

    World getWorld();

    boolean hasPermission(String permission);

    boolean isOnline();

    void kick(Component message);

    void removePotionEffect(PotionEffectType type);

    void sendPluginMessage(Plugin source, String channel, byte[] message);

    void setExp(float experience);

    void setFallDistance(float distance);

    void setFireTicks(int ticks);

    void setFoodLevel(int foodLevel);

    void setGameMode(GameMode gameMode);

    void setInvulnerable(boolean invulnerable);

    void setLevel(int level);

    void setSaturation(float saturation);

    CompletableFuture<Boolean> teleportAsync(Location location);
}
