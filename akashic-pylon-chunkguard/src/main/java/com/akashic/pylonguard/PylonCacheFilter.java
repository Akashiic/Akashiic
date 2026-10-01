package com.akashic.pylonguard;

import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;

/**
 * Backs the PylonGenerator mixin: PylonGenerator.loadPylonLocations (the "pylonloc" world data, read once on the first
 * pylon registration) validates every cached pylon with validateCachedLocation -> WorldLocation.getTileEntity(), which
 * loads the chunk of every pylon in the cache (19,087 entries in the server heap summary, matching its 19,122 loaded
 * TileEntityCrystalPylon). That is a second boot-time force-load of all pylon chunks, independent of CrystalNetworker.
 *
 * Entries whose chunk is loaded are validated exactly as before. Entries whose chunk is not loaded are kept without
 * validation: the cache is written from the live cache, and a broken pylon leaves it at break time
 * (PylonGenerator.removeCachedPylon), so the validation only guards against external world edits; such a stale entry
 * is harmless (it is a location hint for the pylon finder) and is dropped on a later startup during which its chunk
 * happens to be loaded. Unpatched, entries of pylons in unloaded dimensions were silently deleted; now they are kept.
 */
public final class PylonCacheFilter {

	private static int kept;
	private static int validated;

	private PylonCacheFilter() {
	}

	/** True when the entry must be kept without validation (its chunk is not loaded). Fails open (false) on error. */
	public static boolean keepWithoutValidation(WorldLocation loc) {
		try {
			if (loc != null && !ChunkGuard.isLoaded(loc)) {
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
				ChunkGuard.LOG.info("Pylon location cache load: " + validated + " pylons in loaded chunks validated; " + kept + " pylons in unloaded chunks kept without force-loading their chunk.");
		}
		catch (RuntimeException | LinkageError ignored) {
		}
		kept = 0;
		validated = 0;
	}
}
