package org.bukkit.event.inventory;

import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryView;

public class InventoryOpenEvent {
    public HumanEntity getPlayer() {
        return null;
    }

    public InventoryView getView() {
        return null;
    }

    public void setCancelled(boolean cancelled) {
    }
}
