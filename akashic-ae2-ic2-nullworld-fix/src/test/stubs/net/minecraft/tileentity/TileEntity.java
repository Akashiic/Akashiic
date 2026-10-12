package net.minecraft.tileentity;

import net.minecraft.world.World;

public class TileEntity {
    protected World field_145850_b; // worldObj

    public World func_145831_w() { // getWorldObj
        return this.field_145850_b;
    }

    public void func_145834_a(World world) { // setWorldObj
        this.field_145850_b = world;
    }
}
