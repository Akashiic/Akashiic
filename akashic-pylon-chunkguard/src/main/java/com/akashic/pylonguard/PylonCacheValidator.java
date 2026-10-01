package com.akashic.pylonguard;

import net.minecraftforge.event.world.ChunkEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Forge event listener: finishes the postponed pylon-cache validation when a chunk loads (server worlds only). */
public final class PylonCacheValidator {

	@SubscribeEvent
	public void onChunkLoad(ChunkEvent.Load event) {
		if (PylonCacheFilter.isEmpty() || event.world == null || event.world.field_72995_K) // isRemote
			return;
		try {
			PylonCacheFilter.onChunkLoaded(event.world, event.getChunk());
		}
		catch (RuntimeException | LinkageError ignored) {
			// validation is best effort; never disturb a chunk load
		}
	}
}
