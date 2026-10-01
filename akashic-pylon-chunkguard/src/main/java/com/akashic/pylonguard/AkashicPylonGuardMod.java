package com.akashic.pylonguard;

import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.relauncher.FMLLaunchHandler;

/**
 * Mod container. It shows the jar in the mod list, lets players without the jar join (acceptableRemoteVersions = "*";
 * it is server-side only) and registers the chunk-load listener that finishes the postponed pylon-cache validation.
 */
@Mod(modid = AkashicPylonGuardMod.MODID, name = "Akashic Pylon Chunk Guard", version = AkashicPylonGuardMod.VERSION, acceptableRemoteVersions = "*", dependencies = "after:ChromatiCraft;after:DragonAPI")
public class AkashicPylonGuardMod {

	public static final String MODID = "akashic_pylon_chunkguard";
	public static final String VERSION = "1.0.2";

	@Mod.EventHandler
	public void init(FMLInitializationEvent event) {
		// Same conditions under which PylonGuardLateMixins applies PylonGeneratorMixin.
		if (FMLLaunchHandler.side().isServer() && PylonGuardSettings.enabled() && PylonGuardSettings.bootFilterEnabled() && Loader.isModLoaded("ChromatiCraft") && Loader.isModLoaded("DragonAPI"))
			MinecraftForge.EVENT_BUS.register(new PylonCacheValidator());
	}
}
