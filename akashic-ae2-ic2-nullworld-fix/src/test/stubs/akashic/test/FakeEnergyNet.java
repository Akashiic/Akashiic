package akashic.test;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.tileentity.TileEntity;

import appeng.integration.abstraction.IIC2;

/** Same contract as IC2 2.2.828a: EnergyTileEvent.<init> throws when the tile has no world. */
public class FakeEnergyNet implements IIC2 {
    public final List<TileEntity> loaded = new ArrayList<TileEntity>();

    @Override
    public void addToEnergyNet(TileEntity te) {
        if (te.func_145831_w() == null) throw new NullPointerException("world is null");
        this.loaded.add(te);
    }

    @Override
    public void removeFromEnergyNet(TileEntity te) {
        if (te.func_145831_w() == null) throw new NullPointerException("world is null");
        this.loaded.remove(te);
    }
}
