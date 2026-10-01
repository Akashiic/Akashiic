package com.akashic.pylonguard;

import Reika.ChromatiCraft.Registry.CrystalElement;
import Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation;

/** Implemented by PylonGenerator through PylonGeneratorMixin. */
public interface PylonCacheAccess {

	/** Removes the cached pylon entry at loc from the colour list e, as PylonGenerator.loadPylonLocations would. */
	void akashic$removeCachedLocation(WorldLocation loc, CrystalElement e);
}
