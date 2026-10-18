package com.akashic.baubleslayout.mixins;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import baubles.common.network.PacketSyncAllBauble;
import gnu.trove.map.TIntObjectMap;

@Mixin(value = PacketSyncAllBauble.class, remap = false)
public interface AccessorPacketSyncAllBauble {

    @Accessor("inventory")
    TIntObjectMap<ItemStack> akashicbaubles$getInventory();
}
