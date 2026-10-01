package com.akashic.pylonguard.mixins.late;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import Reika.ChromatiCraft.Magic.Interfaces.CrystalNetworkTile;
import Reika.ChromatiCraft.Magic.Interfaces.CrystalReceiver;
import Reika.ChromatiCraft.Magic.Interfaces.CrystalTransmitter;
import Reika.ChromatiCraft.Magic.Interfaces.PylonConnector;
import Reika.ChromatiCraft.Magic.Network.CrystalNetworker;
import Reika.ChromatiCraft.Registry.CrystalElement;
import Reika.ChromatiCraft.TileEntity.Networking.TileEntityCrystalPylon;
import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;
import Reika.DragonAPI.Instantiable.Data.Maps.TileEntityCache;

import com.akashic.pylonguard.BootLoadFilter;
import com.akashic.pylonguard.ChunkGuard;
import com.akashic.pylonguard.DeferredNetworkTiles;

/**
 * Keeps CrystalNetworker.load (run once, when the crystal network data is first read) from force-loading the chunk of
 * every network tile ever saved, WITHOUT making those tiles unknown to the network:
 *  - load (HEAD): tiles in unloaded chunks go to {@link DeferredNetworkTiles} instead of being resolved (BootLoadFilter);
 *  - every range query the pathfinder and the tiles use (HEAD): deferred tiles within the queried range are resolved
 *    first, exactly as load() would have done for them (tiles.put + addPylon), loading their chunk at that moment;
 *  - getAllSourcesFor (HEAD): the only global query; ChromatiCraft calls it only when the network has at most 100
 *    tiles, so resolving every deferred tile there is bounded;
 *  - size (RETURN): counts deferred tiles, so PylonFinder's "network has more than 100 tiles" shortcut behaves as
 *    unpatched;
 *  - save (RETURN): deferred tiles are appended to the saved list (a deferred tile is never dropped from the file; a
 *    saved location found empty when resolved is dropped, as ChromatiCraft drops its "null tile" entries);
 *  - addTile (RETURN) / removeTile (HEAD): a tile that registers or unregisters itself leaves the registry.
 * Public overloads delegate to the hooked ones (verified in the V33a bytecode), so each query is hooked once.
 */
@Mixin(value = CrystalNetworker.class, remap = false)
public abstract class CrystalNetworkerMixin {

	@Shadow
	@Final
	private TileEntityCache<CrystalNetworkTile> tiles;

	@Shadow
	private void addPylon(TileEntityCrystalPylon te) {
	}

	// ------------------------------------------------------------------ load / save / (un)registration

	@Inject(method = "load(Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$deferUnloadedNetworkTiles(NBTTagCompound root, CallbackInfo ci) {
		BootLoadFilter.filter(root, this.tiles.keySet());
	}

	@Inject(method = "save(Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("RETURN"), require = 1, allow = 1)
	private void akashic$keepDeferredTilesInSave(NBTTagCompound root, CallbackInfo ci) {
		DeferredNetworkTiles.appendOnSave(root);
	}

	@Inject(method = "addTile(LReika/ChromatiCraft/Magic/Interfaces/CrystalNetworkTile;)V", at = @At("RETURN"), require = 1)
	private void akashic$tileRegistered(CrystalNetworkTile te, CallbackInfo ci) {
		akashic$forget(te);
	}

	@Inject(method = "removeTile(LReika/ChromatiCraft/Magic/Interfaces/CrystalNetworkTile;)V", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$tileRemoved(CrystalNetworkTile te, CallbackInfo ci) {
		akashic$forget(te);
	}

	@Inject(method = "size()I", at = @At("RETURN"), cancellable = true, require = 1)
	private void akashic$countDeferred(CallbackInfoReturnable<Integer> cir) {
		if (!DeferredNetworkTiles.isEmpty())
			cir.setReturnValue(cir.getReturnValueI() + DeferredNetworkTiles.size());
	}

	// ------------------------------------------------------------------ range queries: resolve deferred tiles first

	@Inject(method = "getTransmittersTo(LReika/ChromatiCraft/Magic/Interfaces/CrystalReceiver;LReika/ChromatiCraft/Registry/CrystalElement;)Ljava/util/ArrayList;", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$resolveForTransmittersTo(CrystalReceiver r, CrystalElement e, CallbackInfoReturnable<?> cir) {
		if (DeferredNetworkTiles.isEmpty())
			return;
		double range = r.getReceiveRange();
		if (r instanceof PylonConnector)
			range = Math.max(range, ((PylonConnector) r).getPylonRange());
		akashic$resolveNear(r.getWorld(), r.getX(), r.getY(), r.getZ(), range);
	}

	@Inject(method = "getNearbyReceivers(LReika/ChromatiCraft/Magic/Interfaces/CrystalTransmitter;LReika/ChromatiCraft/Registry/CrystalElement;)Ljava/util/ArrayList;", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$resolveForNearbyReceivers(CrystalTransmitter r, CrystalElement e, CallbackInfoReturnable<?> cir) {
		if (!DeferredNetworkTiles.isEmpty())
			akashic$resolveNear(r.getWorld(), r.getX(), r.getY(), r.getZ(), r.getSendRange());
	}

	@Inject(method = "getNearestTileOfType(LReika/ChromatiCraft/Magic/Interfaces/CrystalNetworkTile;LReika/DragonAPI/Instantiable/Data/Immutable/WorldLocation;Ljava/lang/Class;D)LReika/ChromatiCraft/Magic/Interfaces/CrystalNetworkTile;", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$resolveForNearestTileOfType(CrystalNetworkTile te, WorldLocation loc, Class<?> type, double range, CallbackInfoReturnable<?> cir) {
		if (!DeferredNetworkTiles.isEmpty() && loc != null)
			akashic$resolveNear(DimensionManager.getWorld(loc.dimensionID), loc.xCoord, loc.yCoord, loc.zCoord, range);
	}

	@Inject(method = "getNearTilesOfType(Lnet/minecraft/world/World;IIILjava/lang/Class;I)Ljava/util/Collection;", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$resolveForNearTilesOfType(World world, int x, int y, int z, Class<?> type, int range, CallbackInfoReturnable<?> cir) {
		if (!DeferredNetworkTiles.isEmpty())
			akashic$resolveNear(world, x, y, z, range);
	}

	@Inject(method = "getNearbyPylons(Lnet/minecraft/world/World;IIILReika/ChromatiCraft/Registry/CrystalElement;IZ)Ljava/util/Collection;", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$resolveForNearbyPylons(World world, int x, int y, int z, CrystalElement e, int range, boolean los, CallbackInfoReturnable<?> cir) {
		if (!DeferredNetworkTiles.isEmpty())
			akashic$resolveNear(world, x, y, z, range);
	}

	@Inject(method = "getAllNearbyPylons(Lnet/minecraft/world/World;IIIDZ)Ljava/util/ArrayList;", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$resolveForAllNearbyPylons(World world, int x, int y, int z, double range, boolean excludeSelf, CallbackInfoReturnable<?> cir) {
		if (!DeferredNetworkTiles.isEmpty())
			akashic$resolveNear(world, x, y, z, range);
	}

	@Inject(method = "getAllSourcesFor(LReika/ChromatiCraft/Registry/CrystalElement;Z)Ljava/util/ArrayList;", at = @At("HEAD"), require = 1, allow = 1)
	private void akashic$resolveForAllSources(CrystalElement e, boolean activeOnly, CallbackInfoReturnable<?> cir) {
		if (DeferredNetworkTiles.isEmpty())
			return;
		if (DeferredNetworkTiles.size() > 100)
			return; // ChromatiCraft only calls this for networks of <= 100 tiles (size() includes deferred ones)
		for (WorldLocation loc : DeferredNetworkTiles.takeAll()) {
			World w = DimensionManager.getWorld(loc.dimensionID);
			if (w == null)
				DeferredNetworkTiles.add(loc); // dimension not loaded: keep it for later
			else
				akashic$resolve(w, loc);
		}
	}

	// ------------------------------------------------------------------ helpers

	private void akashic$forget(CrystalNetworkTile te) {
		if (DeferredNetworkTiles.isEmpty() || te == null)
			return;
		try {
			World w = te.getWorld();
			if (w != null)
				DeferredNetworkTiles.remove(new WorldLocation(w, te.getX(), te.getY(), te.getZ()));
		}
		catch (RuntimeException | LinkageError ignored) {
			// bookkeeping only; a stale entry is skipped when resolved and on save
		}
	}

	private void akashic$resolveNear(World world, double x, double y, double z, double range) {
		if (world == null || world.field_72995_K) // isRemote
			return;
		List<WorldLocation> locs = DeferredNetworkTiles.takeNear(world.field_73011_w.field_76574_g, x, y, z, range); // provider.dimensionId
		for (WorldLocation loc : locs)
			akashic$resolve(world, loc);
	}

	/** What CrystalNetworker.load does for one saved entry: resolve the tile (loading its chunk) and register it. */
	private void akashic$resolve(World world, WorldLocation loc) {
		try {
			TileEntity te = world.func_147438_o(loc.xCoord, loc.yCoord, loc.zCoord); // getTileEntity
			if (te instanceof CrystalNetworkTile) {
				if (this.tiles.get(loc) == null) {
					this.tiles.put(loc, (CrystalNetworkTile) te);
					if (te instanceof TileEntityCrystalPylon)
						this.addPylon((TileEntityCrystalPylon) te);
				}
				ChunkGuard.countResolved();
			}
			// else: no network tile there any more; unpatched, load() would cache null and drop it later as well
		}
		catch (RuntimeException | LinkageError t) {
			DeferredNetworkTiles.add(loc); // keep it; try again on the next query
			ChunkGuard.logResolveFailure(loc, t);
		}
	}
}
