package com.akashic.baubleslayout;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

/** The targets live in the BaublesExpanded jar, which is only on the classpath once mods are loaded. */
@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.akashicbaubleslayout.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        if (!loadedMods.contains(AkashicBaublesLayout.BAUBLES_MODID)) {
            return Collections.emptyList();
        }
        return Arrays.asList("AccessorPacketSyncAllBauble", "MixinInventoryBaubles", "MixinPacketSyncAllBauble");
    }
}
