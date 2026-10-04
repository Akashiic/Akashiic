package com.akashic.compat.voidsky;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/** Registers {@link VoidSkyTransformer} after FML's deobfuscation, so method names are SRG. */
@IFMLLoadingPlugin.Name("Akashic Void Sky Fix")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(2000000200)
public final class VoidSkyLoadingPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{VoidSkyTransformer.class.getName()};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        System.out.println("[Akashic Void Sky Fix] v1.0.0 armed (client side; no-op on dedicated servers).");
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
