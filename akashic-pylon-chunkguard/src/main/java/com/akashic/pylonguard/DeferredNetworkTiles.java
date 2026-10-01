package com.akashic.pylonguard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;

/**
 * Crystal-network tiles that ChromatiCraft has saved but whose chunk was not loaded when the network data was read.
 *
 * Unpatched, CrystalNetworker.load resolves (and therefore loads the chunk of) every saved tile at startup, so the
 * pathfinder (PylonFinder -> CrystalNetworker.getTransmittersTo/...) can find a pylon or repeater anywhere. Here those
 * tiles are not resolved at startup; they are remembered in this registry instead and resolved ON DEMAND, the first time
 * a network query (path search, nearby-tile lookup) reaches their range. Resolving uses World.getTileEntity, i.e. it
 * loads that chunk then, exactly like ChromatiCraft's own CrystalFlow does while transferring energy. So every network
 * that worked unpatched still finds the same tiles; only the moment their chunks are loaded changes (when needed,
 * instead of all at startup).
 *
 * The registry is persisted: on every save of the network data the deferred locations are appended to the list
 * ChromatiCraft wrote, so the saved file keeps every tile the unpatched mod would have kept.
 *
 * Server thread only (the network data is only read, written and queried there). Dedicated server only (static state).
 */
public final class DeferredNetworkTiles {

	private static final String ROOT_KEY = "crystalnet"; // CrystalNetworker.NBT_TAG
	private static final String LOCS_KEY = "locs"; // TileEntityCache.writeToNBT/readFromNBT
	private static final int NBT_LIST = 9;
	private static final int NBT_COMPOUND = 10;

	/** dimension -> chunk key -> locations */
	private static final Map<Integer, Map<Long, List<WorldLocation>>> BY_DIM = new HashMap<Integer, Map<Long, List<WorldLocation>>>();
	private static int size;

	private DeferredNetworkTiles() {
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	public static int size() {
		return size;
	}

	public static boolean isEmpty() {
		return size == 0;
	}

	public static void clear() {
		BY_DIM.clear();
		size = 0;
	}

	public static void add(WorldLocation loc) {
		Map<Long, List<WorldLocation>> dim = BY_DIM.get(loc.dimensionID);
		if (dim == null) {
			dim = new HashMap<Long, List<WorldLocation>>();
			BY_DIM.put(loc.dimensionID, dim);
		}
		long k = key(loc.xCoord >> 4, loc.zCoord >> 4);
		List<WorldLocation> li = dim.get(k);
		if (li == null) {
			li = new ArrayList<WorldLocation>(2);
			dim.put(k, li);
		}
		if (!li.contains(loc)) {
			li.add(loc);
			size++;
		}
	}

	public static boolean remove(WorldLocation loc) {
		if (size == 0 || loc == null)
			return false;
		Map<Long, List<WorldLocation>> dim = BY_DIM.get(loc.dimensionID);
		if (dim == null)
			return false;
		long k = key(loc.xCoord >> 4, loc.zCoord >> 4);
		List<WorldLocation> li = dim.get(k);
		if (li == null || !li.remove(loc))
			return false;
		size--;
		if (li.isEmpty())
			dim.remove(k);
		return true;
	}

	/**
	 * Removes and returns the deferred locations of dimension dim within the given (3D, Euclidean) range of (x, y, z).
	 * A range of Double.POSITIVE_INFINITY takes the whole dimension.
	 */
	public static List<WorldLocation> takeNear(int dim, double x, double y, double z, double range) {
		List<WorldLocation> out = new ArrayList<WorldLocation>();
		Map<Long, List<WorldLocation>> map = BY_DIM.get(dim);
		if (map == null || map.isEmpty() || !(range >= 0))
			return out;
		double r2 = range * range;
		if (Double.isInfinite(range) || range > 4096) {
			for (Iterator<List<WorldLocation>> it = map.values().iterator(); it.hasNext();) {
				List<WorldLocation> li = it.next();
				take(li, x, y, z, r2, range, out);
				if (li.isEmpty())
					it.remove();
			}
		}
		else {
			int cx0 = (int) Math.floor((x - range) / 16D), cx1 = (int) Math.floor((x + range) / 16D);
			int cz0 = (int) Math.floor((z - range) / 16D), cz1 = (int) Math.floor((z + range) / 16D);
			for (int cx = cx0; cx <= cx1; cx++) {
				for (int cz = cz0; cz <= cz1; cz++) {
					long k = key(cx, cz);
					List<WorldLocation> li = map.get(k);
					if (li == null)
						continue;
					take(li, x, y, z, r2, range, out);
					if (li.isEmpty())
						map.remove(k);
				}
			}
		}
		size -= out.size();
		return out;
	}

	private static void take(List<WorldLocation> li, double x, double y, double z, double r2, double range, List<WorldLocation> out) {
		for (Iterator<WorldLocation> it = li.iterator(); it.hasNext();) {
			WorldLocation loc = it.next();
			double dx = loc.xCoord - x, dy = loc.yCoord - y, dz = loc.zCoord - z;
			if (Double.isInfinite(range) || dx * dx + dy * dy + dz * dz <= r2 + 1.0E-6) {
				out.add(loc);
				it.remove();
			}
		}
	}

	/** Removes and returns every deferred location of every dimension. */
	public static List<WorldLocation> takeAll() {
		List<WorldLocation> out = new ArrayList<WorldLocation>(size);
		for (Map<Long, List<WorldLocation>> map : BY_DIM.values())
			for (List<WorldLocation> li : map.values())
				out.addAll(li);
		clear();
		return out;
	}

	/**
	 * Called at RETURN of CrystalNetworker.save: appends the deferred locations that ChromatiCraft did not write itself,
	 * in its own entry format (WorldLocation.writeToTag), so the saved network keeps every tile.
	 */
	public static void appendOnSave(NBTTagCompound root) {
		if (size == 0)
			return;
		try {
			if (root == null || !root.func_150297_b(ROOT_KEY, NBT_COMPOUND)) // hasKey(String, int)
				return;
			NBTTagCompound tag = root.func_74775_l(ROOT_KEY); // getCompoundTag
			NBTBase stored = tag.func_74781_a(LOCS_KEY); // getTag
			NBTTagList locs;
			if (stored == null) {
				locs = new NBTTagList();
				tag.func_74782_a(LOCS_KEY, locs); // setTag
			}
			else {
				locs = tag.func_150295_c(LOCS_KEY, NBT_COMPOUND); // getTagList
				if (locs != stored) { // not a list of compounds: never overwrite data of an unexpected shape
					ChunkGuard.LOG.error("Crystal network save: unexpected '" + LOCS_KEY + "' type; " + size + " deferred tile locations NOT appended this save (kept in memory).");
					return;
				}
			}
			Set<WorldLocation> present = new HashSet<WorldLocation>();
			for (int i = 0; i < locs.func_74745_c(); i++) // tagCount
				present.add(WorldLocation.readTag(locs.func_150305_b(i))); // getCompoundTagAt
			for (Map<Long, List<WorldLocation>> map : BY_DIM.values()) {
				for (List<WorldLocation> li : map.values()) {
					for (WorldLocation loc : li) {
						if (present.add(loc)) {
							NBTTagCompound entry = new NBTTagCompound();
							loc.writeToTag(entry);
							locs.func_74742_a(entry); // appendTag
						}
					}
				}
			}
		}
		catch (RuntimeException | LinkageError t) {
			ChunkGuard.LOG.error("Crystal network save: could not append deferred tile locations (kept in memory, retried next save).", t);
		}
	}
}
