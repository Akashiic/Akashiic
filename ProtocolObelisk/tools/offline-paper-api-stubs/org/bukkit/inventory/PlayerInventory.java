package org.bukkit.inventory;

public interface PlayerInventory extends Inventory {
    void clear();

    void setArmorContents(ItemStack[] items);
}
