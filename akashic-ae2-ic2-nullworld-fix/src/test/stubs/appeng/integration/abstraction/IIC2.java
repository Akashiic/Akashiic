package appeng.integration.abstraction;

import net.minecraft.tileentity.TileEntity;

public interface IIC2 {
    void addToEnergyNet(TileEntity te);

    void removeFromEnergyNet(TileEntity te);
}
