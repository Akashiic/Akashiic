package com.akashic.pylonguard.mixins.late;

import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import Reika.ChromatiCraft.Registry.CrystalElement;
import Reika.ChromatiCraft.World.IWG.PylonGenerator;
import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;

import com.akashic.pylonguard.PylonCacheFilter;

/**
 * Keeps PylonGenerator.loadPylonLocations from loading the chunk of every cached pylon. See {@link PylonCacheFilter}.
 * validateCachedLocation is private and has exactly one caller (loadPylonLocations) in V33a.
 */
@Mixin(value = PylonGenerator.class, remap = false)
public abstract class PylonGeneratorMixin {

	@Inject(
		method = "validateCachedLocation(LReika/DragonAPI/Instantiable/Data/Immutable/WorldLocation;LReika/ChromatiCraft/Registry/CrystalElement;)Z",
		at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
	private void akashic$keepUnloadedPylonEntries(WorldLocation loc, CrystalElement e, CallbackInfoReturnable<Boolean> cir) {
		if (PylonCacheFilter.keepWithoutValidation(loc))
			cir.setReturnValue(Boolean.TRUE);
	}

	@Inject(method = "loadPylonLocations(Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("RETURN"), require = 1, allow = 1)
	private void akashic$reportPylonCacheLoad(NBTTagCompound tag, CallbackInfo ci) {
		PylonCacheFilter.report();
	}
}
