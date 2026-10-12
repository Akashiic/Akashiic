package appeng.tile.misc;

import java.util.EnumSet;

import net.minecraftforge.common.util.ForgeDirection;

/** Mirrors TileAdvancedInscriber.<init> (rv3-beta-1035-GTNH, line 76). */
public class FakeAdvancedInscriber extends appeng.tile.powersink.IC2 {

    public FakeAdvancedInscriber() {
        this.setPowerSides(EnumSet.allOf(ForgeDirection.class));
    }
}
