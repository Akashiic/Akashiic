package com.akashic.pylonguard.mixins.late;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;

import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import Reika.ChromatiCraft.Registry.CrystalElement;
import Reika.ChromatiCraft.World.IWG.PylonGenerator;
import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;

import com.akashic.pylonguard.PylonCacheAccess;
import com.akashic.pylonguard.PylonCacheFilter;

/**
 * Keeps PylonGenerator.loadPylonLocations from loading the chunk of every cached pylon; validation of entries in
 * unloaded chunks is postponed until their chunk loads. See {@link PylonCacheFilter}.
 * validateCachedLocation is private and has exactly one caller (loadPylonLocations) in V33a.
 */
@Mixin(value = PylonGenerator.class, remap = false)
public abstract class PylonGeneratorMixin implements PylonCacheAccess {

	@Shadow
	@Final
	private EnumMap<CrystalElement, Collection<PylonGenerator.PylonEntry>> colorCache;

	@Inject(
		method = "validateCachedLocation(LReika/DragonAPI/Instantiable/Data/Immutable/WorldLocation;LReika/ChromatiCraft/Registry/CrystalElement;)Z",
		at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
	private void akashic$keepUnloadedPylonEntries(WorldLocation loc, CrystalElement e, CallbackInfoReturnable<Boolean> cir) {
		if (PylonCacheFilter.keepWithoutValidation(loc, e))
			cir.setReturnValue(Boolean.TRUE);
	}

	@Inject(method = "loadPylonLocations(Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("RETURN"), require = 1, allow = 1)
	private void akashic$reportPylonCacheLoad(NBTTagCompound tag, CallbackInfo ci) {
		PylonCacheFilter.report();
	}

	/** Same removal as PylonGenerator.removeCachedPylon: PylonEntry equality is by location only. */
	@Override
	public void akashic$removeCachedLocation(WorldLocation loc, CrystalElement e) {
		Collection<PylonGenerator.PylonEntry> c = this.colorCache.get(e);
		if (c != null)
			c.remove(new PylonGenerator.PylonEntry(e, loc, new ArrayList<Object>(), false, null, false));
	}
}
