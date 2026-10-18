package com.akashic.baubleslayout.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.akashic.baubleslayout.SlotGuard;

import baubles.api.expanded.BaubleExpandedSlots;
import baubles.common.network.PacketSyncAllBauble;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * The full-inventory sync writes {@code stackList[slot]} directly, bypassing the inventory methods; drop entries the
 * local inventory cannot hold before that happens. The handler recreates the inventory with
 * {@link BaubleExpandedSlots#slotsCurrentlyUsed()} slots, so that is the bound.
 */
@Mixin(value = PacketSyncAllBauble.class, remap = false)
public abstract class MixinPacketSyncAllBauble {

    @Inject(
        method = "onMessage(Lbaubles/common/network/PacketSyncAllBauble;Lcpw/mods/fml/common/network/simpleimpl/MessageContext;)Lcpw/mods/fml/common/network/simpleimpl/IMessage;",
        at = @At("HEAD"))
    private void akashicbaubles$dropOutOfRangeSlots(PacketSyncAllBauble message, MessageContext ctx,
        CallbackInfoReturnable<IMessage> cir) {
        int size = BaubleExpandedSlots.slotsCurrentlyUsed();
        ((AccessorPacketSyncAllBauble) message).akashicbaubles$getInventory()
            .retainEntries((slot, stack) -> !SlotGuard.rejects(slot, size, "PacketSyncAllBauble"));
    }
}
