package com.akashic.pylonguard.mixins.late;

import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import Reika.ChromatiCraft.Magic.Interfaces.CrystalNetworkTile;
import Reika.ChromatiCraft.Magic.Network.CrystalNetworker;
import Reika.DragonAPI.Instantiable.Data.Maps.TileEntityCache;

import com.akashic.pylonguard.BootLoadFilter;

/**
 * Keeps CrystalNetworker.load (run once, when the crystal network data is first read) from force-loading the chunk of
 * every network tile ever saved. See {@link BootLoadFilter}.
 */
@Mixin(value = CrystalNetworker.class, remap = false)
public abstract class CrystalNetworkerMixin {

	@Shadow
	@Final
	private TileEntityCache<CrystalNetworkTile> tiles;

	@Inject(method = "load(Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$skipUnloadedNetworkTiles(NBTTagCompound root, CallbackInfo ci) {
		BootLoadFilter.filter(root, this.tiles.keySet());
	}
}
