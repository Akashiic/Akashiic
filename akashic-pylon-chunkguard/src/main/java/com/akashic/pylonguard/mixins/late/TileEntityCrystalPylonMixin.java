package com.akashic.pylonguard.mixins.late;

import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import Reika.ChromatiCraft.TileEntity.Networking.TileEntityCrystalPylon;
import Reika.DragonAPI.Instantiable.Data.Immutable.BlockKey;
import Reika.DragonAPI.Instantiable.Data.Immutable.Coordinate;
import Reika.DragonAPI.Libraries.World.ReikaWorldHelper;

import com.akashic.pylonguard.ChunkGuard;

/**
 * Stops crystal pylons from loading chunks from their server tick.
 *
 * ChromatiCraft V33a, TileEntityCrystalPylon.updateEntity (server side): every tick, a pylon at full energy reads a
 * random block up to 12 blocks away horizontally (Coordinate.getBlock), plus a random block of its structure. A read in
 * an unloaded chunk loads it synchronously from disk. With no player online every pylon is full, so each one kept
 * loading the ~6 chunks around it: 34 % of the server thread in ChunkProviderServer.loadChunk in the spark profile and
 * ~119k chunks for 19,087 pylons (124,499 in the heap summary).
 *
 * Policy: a guarded action is only POSTPONED, never altered. When every position it needs is loaded, ChromatiCraft's
 * own code runs unchanged; when one is not, that tick's attempt is skipped as if the random roll had missed:
 *  - random 12-block scan and structure snow clearing: skipped for that tick;
 *  - encrusted-crystal growth (tryGrowEncrustedAt): all-or-nothing, cancelled unless its source block and the 3x3
 *    column around its target are loaded, so the growth amount, the rune bonus and the 6-crystal cap are computed by
 *    the original code or not at all;
 *  - jar rejection (isBlockEncased): the "encased" check is postponed until the 3x3x3 box around the pylon is loaded.
 * Deliberately NOT touched (original behaviour kept):
 *  - reloadEncrusted (tick 0): counts the existing encrusted crystals for the cap; it only reads the structure
 *    neighbourhood (+-4 blocks, at most 3 neighbour chunks, once per pylon load);
 *  - energy sharing between linked pylons: Pylon Link tiles keep their 3x3 chunks loaded by ticket anyway;
 *  - charging, attacks, wand interactions and every progression trigger (PYLON, LINK, POWERCRYSTAL, ...).
 *
 * All targets are ChromatiCraft/DragonAPI members (not obfuscated), hence remap = false. require/allow pin the exact
 * number of call sites found in the V33a bytecode, so a different ChromatiCraft build fails loudly at startup instead
 * of being half-patched.
 */
@Mixin(value = TileEntityCrystalPylon.class, remap = false)
public abstract class TileEntityCrystalPylonMixin {

	/**
	 * 3 sites: updateEntity (structure snow check, 12-block random scan) and tryGrowEncrusted (the source block of a
	 * growth attempt; when it is not loaded the attempt is then cancelled by the tryGrowEncrustedAt guard below, so the
	 * placeholder value is never acted upon).
	 */
	@Redirect(
		method = {
			"updateEntity(Lnet/minecraft/world/World;IIII)V",
			"tryGrowEncrusted(Lnet/minecraft/world/World;III)V"
		},
		at = @At(value = "INVOKE", target = "LReika/DragonAPI/Instantiable/Data/Immutable/Coordinate;getBlock(Lnet/minecraft/world/IBlockAccess;)Lnet/minecraft/block/Block;"),
		require = 3, allow = 3)
	private Block akashic$getBlockIfLoaded(Coordinate c, IBlockAccess world) {
		if (ChunkGuard.refuse(world, c.xCoord, c.zCoord, "getBlock"))
			return ChunkGuard.UNLOADED_BLOCK;
		return c.getBlock(world);
	}

	/** 1 site: updateEntity, random structure block (snow clearing). */
	@Redirect(
		method = "updateEntity(Lnet/minecraft/world/World;IIII)V",
		at = @At(value = "INVOKE", target = "LReika/DragonAPI/Instantiable/Data/Immutable/Coordinate;getBlockKey(Lnet/minecraft/world/IBlockAccess;)LReika/DragonAPI/Instantiable/Data/Immutable/BlockKey;"),
		require = 1, allow = 1)
	private BlockKey akashic$getBlockKeyIfLoaded(Coordinate c, IBlockAccess world) {
		if (ChunkGuard.refuse(world, c.xCoord, c.zCoord, "getBlockKey"))
			return ChunkGuard.UNLOADED_KEY;
		return c.getBlockKey(world);
	}

	/**
	 * Growth attempt (called from tryGrowEncrusted and from the 12-block scan in updateEntity): runs entirely as in
	 * ChromatiCraft when the source block and the 3x3 column around the target block are in loaded chunks, otherwise not
	 * at all. The 3x3 box matters because growing reads the target's six neighbours (CrystalGrowth.canExist) and the
	 * block placement notifies them; at a chunk edge either one would load the next chunk.
	 */
	@Inject(
		method = "tryGrowEncrustedAt(Lnet/minecraft/world/World;LReika/DragonAPI/Instantiable/Data/Immutable/Coordinate;LReika/DragonAPI/Instantiable/Data/Immutable/Coordinate;Z)V",
		at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
	private void akashic$growOnlyIfLoaded(World world, Coordinate from, Coordinate c, boolean addToCount, CallbackInfo ci) {
		if (ChunkGuard.refuse(world, from.xCoord, from.zCoord, "encrusted growth") || ChunkGuard.refuseNeighbourhood(world, c.xCoord, c.zCoord, "encrusted growth"))
			ci.cancel();
	}

	/** 1 site: updateEntity, jar-rejection check over the 3x3x3 box around the pylon. */
	@Redirect(
		method = "updateEntity(Lnet/minecraft/world/World;IIII)V",
		at = @At(value = "INVOKE", target = "LReika/DragonAPI/Libraries/World/ReikaWorldHelper;isBlockEncased(Lnet/minecraft/world/World;IIILnet/minecraft/block/Block;)Z"),
		require = 1, allow = 1)
	private boolean akashic$isBlockEncasedIfLoaded(World world, int x, int y, int z, Block b) {
		if (ChunkGuard.refuseNeighbourhood(world, x, z, "isBlockEncased"))
			return false;
		return ReikaWorldHelper.isBlockEncased(world, x, y, z, b);
	}
}
