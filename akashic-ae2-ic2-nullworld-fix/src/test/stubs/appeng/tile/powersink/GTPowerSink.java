package appeng.tile.powersink;

import java.util.EnumSet;

import net.minecraft.tileentity.TileEntity;

/** Stands in for the whole AERootPoweredTile chain above the real IC2 class. */
@SuppressWarnings({ "rawtypes" })
public abstract class GTPowerSink extends TileEntity {
    private EnumSet powerSides;

    protected void setPowerSides(EnumSet sides) {
        this.powerSides = sides;
    }

    public void onReady() {}
}
