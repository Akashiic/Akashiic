package com.akashic.baubleslayout.mixins;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.akashic.baubleslayout.SlotGuard;

import baubles.common.container.InventoryBaubles;

/**
 * Client-side safety net: an index the local inventory does not have is ignored instead of throwing
 * ArrayIndexOutOfBoundsException, which in a packet handler makes FML drop the whole connection. Server-side behaviour
 * is untouched.
 */
@Mixin(InventoryBaubles.class)
public abstract class MixinInventoryBaubles {

    @Shadow(remap = false)
    public ItemStack[] stackList;

    @Inject(method = "setInventorySlotContents", at = @At("HEAD"), cancellable = true)
    private void akashicbaubles$guardSet(int slot, ItemStack stack, CallbackInfo ci) {
        if (SlotGuard.rejects(slot, stackList.length, "setInventorySlotContents")) {
            ci.cancel();
        }
    }

    @Inject(method = "decrStackSize", at = @At("HEAD"), cancellable = true)
    private void akashicbaubles$guardDecr(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        if (SlotGuard.rejects(slot, stackList.length, "decrStackSize")) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getStackInSlotOnClosing", at = @At("HEAD"), cancellable = true)
    private void akashicbaubles$guardClosing(int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (SlotGuard.rejects(slot, stackList.length, "getStackInSlotOnClosing")) {
            cir.setReturnValue(null);
        }
    }
}
