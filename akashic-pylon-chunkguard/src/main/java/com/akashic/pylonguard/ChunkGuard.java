package com.akashic.pylonguard;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import Reika.DragonAPI.Instantiable.Data.Immutable.BlockKey;
import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;

/**
 * "Is this position's chunk already loaded?" checks used by the mixins, plus counters and rate-limited logging.
 *
 * Every check here is a pure lookup: IChunkProvider.chunkExists (func_73149_a) on the server only consults the
 * loaded-chunk map (Crucible: loadedChunkHashMap_KC.rawThermos().get(x, z) != null), and DimensionManager.getWorld
 * never loads a dimension. Nothing in this class can load or generate a chunk.
 *
 * This code is compiled against the server's runtime (SRG) names, so Minecraft members appear as func_/field_ names.
 * The MCP name is given in a comment next to each one.
 */
public final class ChunkGuard {

	static final Logger LOG = LogManager.getLogger("AkashicPylonGuard");

	/**
	 * Returned instead of the real block when the position's chunk is not loaded. Bedrock is inert for every guarded
	 * call site: it is not air, snow, encrusted crystal, pylon structure or rune, so each check that would have acted on
	 * the block evaluates to "do nothing".
	 */
	public static final Block UNLOADED_BLOCK = Blocks.field_150357_h; // Blocks.bedrock
	public static final BlockKey UNLOADED_KEY = new BlockKey(UNLOADED_BLOCK, 0);

	private static final long REPORT_INTERVAL_NANOS = 10L * 60L * 1000000000L;

	private static long prevented;
	private static long preventedSinceReport;
	private static long lastReport = System.nanoTime();
	private static boolean firstLogged;

	private ChunkGuard() {
	}

	/** True when the chunk holding block (x, z) of this world is currently loaded. */
	public static boolean isLoaded(World world, int x, int z) {
		return world.func_72863_F().func_73149_a(x >> 4, z >> 4); // getChunkProvider().chunkExists(cx, cz)
	}

	/** True when loc's dimension is loaded and its chunk is loaded. Never loads either. */
	public static boolean isLoaded(WorldLocation loc) {
		World w = DimensionManager.getWorld(loc.dimensionID);
		return w != null && isLoaded(w, loc.xCoord, loc.zCoord);
	}

	/**
	 * True when a server-side access to block (x, z) must be refused because it would load a chunk.
	 * Client worlds and non-World accesses are never refused.
	 */
	public static boolean refuse(IBlockAccess access, int x, int z, String what) {
		if (!(access instanceof World))
			return false;
		World world = (World) access;
		if (world.field_72995_K) // isRemote
			return false;
		if (isLoaded(world, x, z))
			return false;
		count(world.field_73011_w.field_76574_g, x, z, what); // provider.dimensionId
		return true;
	}

	/** True when any chunk touched by the 3x3x3 box around (x, z) is not loaded (server worlds only). */
	public static boolean refuseNeighbourhood(World world, int x, int z, String what) {
		if (world.field_72995_K) // isRemote
			return false;
		if (isLoaded(world, x - 1, z - 1) && isLoaded(world, x - 1, z + 1) && isLoaded(world, x + 1, z - 1) && isLoaded(world, x + 1, z + 1))
			return false;
		count(world.field_73011_w.field_76574_g, x, z, what);
		return true;
	}

	public static long preventedSoFar() {
		return prevented;
	}

	private static long resolved;
	private static long resolvedSinceReport;
	private static long lastResolveReport = System.nanoTime();
	private static boolean firstResolveLogged;
	private static boolean resolveFailureLogged;

	public static long resolvedSoFar() {
		return resolved;
	}

	/** A deferred crystal-network tile was resolved on demand (a network query reached it). */
	public static void countResolved() {
		resolved++;
		resolvedSinceReport++;
		try {
			if (!firstResolveLogged) {
				firstResolveLogged = true;
				LOG.info("Crystal network: resolved a deferred network tile on demand (a path search or lookup reached it). Further ones are summarised every 10 minutes.");
			}
			long now = System.nanoTime();
			if (now - lastResolveReport >= REPORT_INTERVAL_NANOS) {
				LOG.info("Last 10 minutes: resolved " + resolvedSinceReport + " deferred crystal network tiles on demand (" + resolved + " since start; " + DeferredNetworkTiles.size() + " still deferred).");
				resolvedSinceReport = 0;
				lastResolveReport = now;
			}
		}
		catch (RuntimeException | LinkageError ignored) {
		}
	}

	public static void logResolveFailure(WorldLocation loc, Throwable t) {
		if (resolveFailureLogged)
			return;
		resolveFailureLogged = true;
		try {
			LOG.error("Crystal network: could not resolve deferred tile at " + loc + " (kept, retried on the next query; logged once).", t);
		}
		catch (RuntimeException | LinkageError ignored) {
		}
	}

	private static void count(int dim, int x, int z, String what) {
		prevented++;
		preventedSinceReport++;
		try {
			if (!firstLogged) {
				firstLogged = true;
				LOG.info("Prevented the first chunk load by a crystal pylon (" + what + " at dim " + dim + ", block " + x + ", " + z + ", chunk " + (x >> 4) + ", " + (z >> 4) + "). Further ones are summarised every 10 minutes.");
			}
			long now = System.nanoTime();
			if (now - lastReport >= REPORT_INTERVAL_NANOS) {
				LOG.info("Last 10 minutes: prevented " + preventedSinceReport + " chunk loads by crystal pylons (" + prevented + " since start).");
				preventedSinceReport = 0;
				lastReport = now;
			}
		}
		catch (RuntimeException | LinkageError ignored) {
			// logging must never break a tile tick
		}
	}
}
