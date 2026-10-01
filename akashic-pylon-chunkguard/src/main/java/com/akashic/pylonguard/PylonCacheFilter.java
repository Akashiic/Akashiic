package com.akashic.pylonguard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import Reika.ChromatiCraft.Magic.Interfaces.CrystalTransmitter;
import Reika.ChromatiCraft.Registry.CrystalElement;
import Reika.ChromatiCraft.TileEntity.Networking.TileEntityCrystalPylon;
import Reika.ChromatiCraft.World.IWG.PylonGenerator;
import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;

/**
 * Backs the PylonGenerator mixin. PylonGenerator.loadPylonLocations (the "pylonloc" world data, read once, on the first
 * pylon registration) validates every cached pylon with validateCachedLocation -> WorldLocation.getTileEntity(): the
 * tile must still be a crystal pylon of the cached colour, otherwise the entry is dropped. That loads the chunk of every
 * pylon in the cache (19,087 entries in the server heap summary, matching its 19,122 loaded TileEntityCrystalPylon): a
 * second startup force-load of all pylon chunks, independent of CrystalNetworker.
 *
 * Here entries whose chunk is loaded are validated exactly as before. Entries whose chunk is not loaded are kept and
 * their validation is POSTPONED to the moment their chunk loads for any reason (ChunkEvent.Load, see
 * {@link PylonCacheValidator}): the same check (tile is a TileEntityCrystalPylon of the cached colour) is made on the
 * freshly loaded chunk's tile map, and a failing entry is removed from the cache like the original would have. The
 * cache feeds the client Pylon Finder HUD (sent on login); an entry can only be stale if a pylon disappeared without the
 * mod noticing (pylons are unbreakable for players), e.g. through a world-edit tool.
 */
public final class PylonCacheFilter {

	private static final class Pending {
		final WorldLocation loc;
		final CrystalElement color;

		Pending(WorldLocation loc, CrystalElement color) {
			this.loc = loc;
			this.color = color;
		}
	}

	/** dimension -> chunk key -> entries kept without validation */
	private static final Map<Integer, Map<Long, List<Pending>>> PENDING = new HashMap<Integer, Map<Long, List<Pending>>>();
	private static int pendingCount;

	private static int kept;
	private static int validated;
	private static long lateValid;
	private static long lateRemoved;

	private PylonCacheFilter() {
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	public static boolean isEmpty() {
		return pendingCount == 0;
	}

	/** True when the entry must be kept without validation now (its chunk is not loaded). Fails open (false) on error. */
	public static boolean keepWithoutValidation(WorldLocation loc, CrystalElement color) {
		try {
			if (loc != null && !ChunkGuard.isLoaded(loc)) {
				Map<Long, List<Pending>> dim = PENDING.get(loc.dimensionID);
				if (dim == null) {
					dim = new HashMap<Long, List<Pending>>();
					PENDING.put(loc.dimensionID, dim);
				}
				long k = key(loc.xCoord >> 4, loc.zCoord >> 4);
				List<Pending> li = dim.get(k);
				if (li == null) {
					li = new ArrayList<Pending>(1);
					dim.put(k, li);
				}
				li.add(new Pending(loc, color));
				pendingCount++;
				kept++;
				return true;
			}
			validated++;
			return false;
		}
		catch (RuntimeException | LinkageError t) {
			validated++;
			return false;
		}
	}

	/** Called at RETURN of loadPylonLocations. */
	public static void report() {
		try {
			if (kept > 0)
				ChunkGuard.LOG.info("Pylon location cache load: " + validated + " pylons in loaded chunks validated; " + kept + " pylons in unloaded chunks kept without force-loading their chunk (validated when their chunk loads).");
		}
		catch (RuntimeException | LinkageError ignored) {
		}
		kept = 0;
		validated = 0;
	}

	/**
	 * Called on every server ChunkEvent.Load: validates the postponed entries of that chunk with the original rule, on
	 * the chunk's own tile map (a pure lookup; the chunk is already loaded).
	 */
	public static void onChunkLoaded(World world, Chunk chunk) {
		if (pendingCount == 0)
			return;
		Map<Long, List<Pending>> dim = PENDING.get(world.field_73011_w.field_76574_g); // provider.dimensionId
		if (dim == null)
			return;
		List<Pending> li = dim.remove(key(chunk.field_76635_g, chunk.field_76647_h)); // xPosition, zPosition
		if (li == null)
			return;
		pendingCount -= li.size();
		for (Pending p : li) {
			try {
				Object te = chunk.field_150816_i.get(new ChunkPosition(p.loc.xCoord & 15, p.loc.yCoord, p.loc.zCoord & 15)); // chunkTileEntityMap
				// original rule: te instanceof TileEntityCrystalPylon && te.getColor() == colour. TileEntityCrystalPylon
				// implements isConductingElement(e) as "e == this.color" (V33a), called through the network interface.
				boolean valid = te instanceof TileEntityCrystalPylon && ((CrystalTransmitter) te).isConductingElement(p.color);
				if (valid) {
					lateValid++;
				}
				else {
					((PylonCacheAccess) (Object) PylonGenerator.instance).akashic$removeCachedLocation(p.loc, p.color);
					lateRemoved++;
					ChunkGuard.LOG.info("Pylon location cache: removed stale entry " + p.color + " @ " + p.loc + " (no such pylon when its chunk loaded; " + (te == null ? "no tile" : ((TileEntity) te).getClass().getSimpleName()) + ").");
				}
			}
			catch (RuntimeException | LinkageError t) {
				// keep the entry, exactly as if it had never been checked
			}
		}
	}

	public static long lateValidated() {
		return lateValid;
	}

	public static long lateRemoved() {
		return lateRemoved;
	}

	public static int pending() {
		return pendingCount;
	}
}
