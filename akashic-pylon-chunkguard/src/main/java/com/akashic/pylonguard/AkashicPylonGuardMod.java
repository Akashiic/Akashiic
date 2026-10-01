package com.akashic.pylonguard;

import cpw.mods.fml.common.Mod;

/**
 * Empty mod container. It exists so the jar shows up in the mod list and, with acceptableRemoteVersions = "*", players
 * without this jar can still join (it is server-side only).
 */
@Mod(modid = AkashicPylonGuardMod.MODID, name = "Akashic Pylon Chunk Guard", version = AkashicPylonGuardMod.VERSION, acceptableRemoteVersions = "*", dependencies = "after:ChromatiCraft;after:DragonAPI")
public class AkashicPylonGuardMod {

	public static final String MODID = "akashic_pylon_chunkguard";
	public static final String VERSION = "1.0.1";
}
