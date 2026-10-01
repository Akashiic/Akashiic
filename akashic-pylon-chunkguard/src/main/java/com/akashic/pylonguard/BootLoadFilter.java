package com.akashic.pylonguard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;

/**
 * Runs at HEAD of CrystalNetworker.load(NBTTagCompound), before ChromatiCraft hands the saved list to
 * TileEntityCache.readFromNBT.
 *
 * Unpatched, readFromNBT calls WorldLocation.getTileEntity() for every saved network tile, which loads (or generates)
 * the chunk of every pylon/repeater the world ever registered, synchronously, on the server thread. On this server that
 * is ~19k pylon chunks and the 30 s+ boot stall seen in the stall reports.
 *
 * Here the saved list is reduced to entries whose chunk is already loaded. Entries in unloaded chunks are dropped from
 * the list given to ChromatiCraft; nothing is lost, because every network tile registers itself again on its first tick
 * (TileEntityCrystalBase.onFirstTick -> cachePosition -> CrystalNetworker.addTile) as soon as its chunk loads for any
 * normal reason (player nearby, chunk loader, PYLONLOAD ticket).
 *
 * The tiles already registered in memory before load() runs are appended to the list. ChromatiCraft calls load() from
 * inside the first addTile(), after that tile was put into the map, and readFromNBT starts with data.clear(); the
 * unpatched mod only gets that tile back because the old file listed every tile ever seen. With the shorter list the
 * in-memory registrations have to be carried over explicitly.
 *
 * Failure policy: any RuntimeException/LinkageError leaves the NBT exactly as it was (the only mutation is the final
 * setTag), so ChromatiCraft's original load runs on the original data.
 */
public final class BootLoadFilter {

	private static final String ROOT_KEY = "crystalnet"; // CrystalNetworker.NBT_TAG
	private static final String LOCS_KEY = "locs"; // TileEntityCache.writeToNBT/readFromNBT
	private static final int NBT_LIST = 9;
	private static final int NBT_COMPOUND = 10;

	private BootLoadFilter() {
	}

	public static void filter(NBTTagCompound root, Collection<WorldLocation> registered) {
		try {
			doFilter(root, registered);
		}
		catch (RuntimeException | LinkageError t) {
			ChunkGuard.LOG.error("Crystal network boot filter failed; ChromatiCraft loads the saved network unchanged (every saved tile chunk will be loaded).", t);
		}
	}

	private static void doFilter(NBTTagCompound root, Collection<WorldLocation> registered) {
		if (root == null || !root.func_150297_b(ROOT_KEY, NBT_COMPOUND)) // hasKey(String, int)
			return;
		NBTTagCompound tag = root.func_74775_l(ROOT_KEY); // getCompoundTag
		if (!tag.func_150297_b(LOCS_KEY, NBT_LIST)) // hasKey(String, int)
			return;
		NBTTagList locs = tag.func_150295_c(LOCS_KEY, NBT_COMPOUND); // getTagList
		int total = locs.func_74745_c(); // tagCount

		long t0 = System.nanoTime();
		NBTTagList kept = new NBTTagList();
		Set<WorldLocation> present = new HashSet<WorldLocation>();
		int skipped = 0;
		int unreadable = 0;
		for (int i = 0; i < total; i++) {
			NBTTagCompound entry = locs.func_150305_b(i); // getCompoundTagAt
			boolean keep;
			try {
				WorldLocation loc = WorldLocation.readTag(entry); // the parser ChromatiCraft's own path uses
				keep = ChunkGuard.isLoaded(loc);
				if (keep)
					present.add(loc);
			}
			catch (RuntimeException | LinkageError t) {
				keep = true; // fail open: let ChromatiCraft handle the entry exactly as before
				unreadable++;
			}
			if (keep)
				kept.func_74742_a(entry); // appendTag
			else
				skipped++;
		}

		int carried = 0;
		if (registered != null) {
			List<WorldLocation> snapshot = new ArrayList<WorldLocation>(registered);
			for (WorldLocation loc : snapshot) {
				if (loc != null && present.add(loc)) {
					NBTTagCompound entry = new NBTTagCompound();
					loc.writeToTag(entry); // ChromatiCraft/DragonAPI's own writer: identical format
					kept.func_74742_a(entry); // appendTag
					carried++;
				}
			}
		}

		if (skipped == 0 && carried == 0)
			return; // nothing to change: ChromatiCraft sees the original list untouched

		long ms = (System.nanoTime() - t0) / 1000000L;
		String summary = "Crystal network load: " + total + " saved tile locations; " + (total - skipped) + " in loaded chunks resolved now; " + skipped + " in unloaded chunks NOT force-loaded (they re-register when their chunk loads); " + carried + " in-memory registrations kept; " + unreadable + " unreadable entries left to ChromatiCraft; " + ms + " ms.";

		tag.func_74782_a(LOCS_KEY, kept); // setTag -- the only mutation, nothing may throw after it

		try {
			ChunkGuard.LOG.info(summary);
		}
		catch (RuntimeException | LinkageError ignored) {
		}
	}
}
