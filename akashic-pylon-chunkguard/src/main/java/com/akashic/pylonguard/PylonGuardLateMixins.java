package com.akashic.pylonguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

/**
 * Decides which mixins are applied. Only on a dedicated server (the problem is a server-thread one and this is what
 * was tested), only when ChromatiCraft and DragonAPI are both loaded, and never when the master kill-switch is off.
 *
 * The older akashic_crystalnet_lazyload jar also injects at HEAD of CrystalNetworker.load. Both injections compose (each
 * only filters the list handed to ChromatiCraft), so this mod applies its own filter anyway and asks for the old jar to
 * be removed (see README).
 */
@LateMixin
public class PylonGuardLateMixins implements ILateMixinLoader {

	private static final Logger LOG = LogManager.getLogger("AkashicPylonGuard");

	@Override
	public String getMixinConfig() {
		return "mixins.akashic_pylonguard.late.json";
	}

	@Override
	public List<String> getMixins(Set<String> loadedMods) {
		if (!PylonGuardSettings.enabled()) {
			LOG.warn("-D" + PylonGuardSettings.MASTER + "=false: crystal pylon chunk guard NOT applied (unpatched ChromatiCraft behaviour).");
			return Collections.emptyList();
		}
		if (!FMLLaunchHandler.side().isServer()) {
			return Collections.emptyList();
		}
		if (!loadedMods.contains("ChromatiCraft") || !loadedMods.contains("DragonAPI")) {
			LOG.warn("ChromatiCraft/DragonAPI not loaded: crystal pylon chunk guard has nothing to patch.");
			return Collections.emptyList();
		}

		List<String> mixins = new ArrayList<String>();
		mixins.add("TileEntityCrystalPylonMixin");

		if (!PylonGuardSettings.bootFilterEnabled()) {
			LOG.warn("-D" + PylonGuardSettings.BOOT_FILTER + "=false: boot filters NOT applied (every saved network tile and cached pylon chunk is loaded at startup).");
		}
		else {
			mixins.add("PylonGeneratorMixin");
			mixins.add("CrystalNetworkerMixin");
		}
		if (loadedMods.contains("akashic_crystalnet_lazyload"))
			LOG.warn("akashic-crystalnet-lazyload is also installed. It is redundant with this mod (in testing its filter never took effect: every saved tile chunk was still loaded at startup). Both can coexist safely, but remove akashic-crystalnet-lazyload-*.jar from mods/.");

		LOG.info("Crystal pylon chunk guard: applying " + mixins + ".");
		return mixins;
	}
}
