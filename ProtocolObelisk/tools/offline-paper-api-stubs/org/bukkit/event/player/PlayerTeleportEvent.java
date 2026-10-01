package org.bukkit.event.player;

/** Compile-only descriptor for Paper 1.21.1; never packaged in the plugin. */
public class PlayerTeleportEvent extends PlayerMoveEvent {
    public enum TeleportCause { PLUGIN, COMMAND, UNKNOWN }

    public TeleportCause getCause() {
        return TeleportCause.UNKNOWN;
    }
}
