package com.akashic.pylonguard.mixins.late;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import Reika.ChromatiCraft.TileEntity.Networking.TileEntityCrystalPylon;
import Reika.DragonAPI.Instantiable.Data.Immutable.BlockKey;
import Reika.DragonAPI.Instantiable.Data.Immutable.Coordinate;
import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;
import Reika.DragonAPI.Libraries.World.ReikaWorldHelper;

import com.akashic.pylonguard.ChunkGuard;

/**
 * Stops crystal pylons from loading chunks from their server tick.
 *
 * ChromatiCraft V33a, TileEntityCrystalPylon.updateEntity (server side), reads blocks outside the pylon's own chunk:
 * every tick, a pylon at full energy picks a random block up to 12 blocks away horizontally and calls getBlock on it;
 * it also reads a random block of its structure, its 3x3x3 neighbourhood and the tiles of linked pylons. Coordinate.getBlock
 * on an unloaded chunk loads it synchronously from disk (or generates it). With no player online every pylon is full,
 * so each one keeps loading the up-to-9 chunks around it; the spark profile shows 34% of the server thread in
 * ChunkProviderServer.loadChunk from exactly this call, and ~112k chunks loaded with 0 players.
 *
 * Each redirect below performs the original call unchanged when the chunk is loaded, and otherwise answers as if the
 * target were an inert solid block (or "no tile"), which makes every caller take its "do nothing" branch:
 *  - snow clearing on the structure, encrusted-crystal growth/scan: bedrock is neither snow, air, rune, pylon
 *    structure nor encrusted crystal, so nothing is placed, removed or counted;
 *  - jar rejection (isBlockEncased): "not encased", so nothing is broken;
 *  - linked-pylon energy sharing: "no tile", the existing instanceof check skips that pylon this tick.
 * Nothing changes while the chunks around a pylon are loaded (player nearby or chunk loader): the original calls run.
 *
 * All targets are ChromatiCraft/DragonAPI members (not obfuscated), hence remap = false. require/allow pin the exact
 * number of call sites found in the V33a bytecode, so a different ChromatiCraft build fails loudly at startup instead
 * of being half-patched.
 */
@Mixin(value = TileEntityCrystalPylon.class, remap = false)
public abstract class TileEntityCrystalPylonMixin {

	/**
	 * 6 sites: updateEntity (structure snow check, 12-block random scan), reloadEncrusted, tryGrowEncrusted,
	 * tryGrowEncrustedAt, growEncrustedAt. forceCrystalColorMatch (player-triggered) is left alone.
	 */
	@Redirect(
		method = {
			"updateEntity(Lnet/minecraft/world/World;IIII)V",
			"reloadEncrusted(Lnet/minecraft/world/World;III)V",
			"tryGrowEncrusted(Lnet/minecraft/world/World;III)V",
			"tryGrowEncrustedAt(Lnet/minecraft/world/World;LReika/DragonAPI/Instantiable/Data/Immutable/Coordinate;LReika/DragonAPI/Instantiable/Data/Immutable/Coordinate;Z)V",
			"growEncrustedAt(Lnet/minecraft/world/World;LReika/DragonAPI/Instantiable/Data/Immutable/Coordinate;IIIZZ)V"
		},
		at = @At(value = "INVOKE", target = "LReika/DragonAPI/Instantiable/Data/Immutable/Coordinate;getBlock(Lnet/minecraft/world/IBlockAccess;)Lnet/minecraft/block/Block;"),
		require = 6, allow = 6)
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

	/**
	 * 1 site: updateEntity, the loop that donates energy to the other pylons of a pylon-link network (those can be
	 * anywhere). getLinkTile() is deliberately NOT redirected: the link tile always sits 9 blocks below its pylon
	 * (TileEntityPylonLink.getPylon), i.e. in the pylon's own chunk, and its result also feeds the PylonGenerator cache.
	 */
	@Redirect(
		method = "updateEntity(Lnet/minecraft/world/World;IIII)V",
		at = @At(value = "INVOKE", target = "LReika/DragonAPI/Instantiable/Data/Immutable/WorldLocation;getTileEntity()Lnet/minecraft/tileentity/TileEntity;"),
		require = 1, allow = 1)
	private TileEntity akashic$getTileEntityIfLoaded(WorldLocation loc) {
		if (ChunkGuard.refuse(loc, "linked pylon"))
			return null;
		return loc.getTileEntity();
	}
}
