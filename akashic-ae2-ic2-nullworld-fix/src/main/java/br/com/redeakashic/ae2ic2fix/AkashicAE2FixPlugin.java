package br.com.redeakashic.ae2ic2fix;

import java.util.Map;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.Name("AkashicAE2IC2Fix")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions("br.com.redeakashic.ae2ic2fix.")
@IFMLLoadingPlugin.SortingIndex(1001) // after FML's deobfuscation transformer
public class AkashicAE2FixPlugin implements IFMLLoadingPlugin {

    private static boolean obfuscatedEnvironment = true;

    static boolean isObfuscatedEnvironment() {
        return obfuscatedEnvironment;
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "br.com.redeakashic.ae2ic2fix.AE2PowerSinkTransformer" };
    }

    @Override
    public String getModContainerClass() {
        return "br.com.redeakashic.ae2ic2fix.AkashicAE2FixContainer";
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(final Map<String, Object> data) {
        final Object deobf = data.get("runtimeDeobfuscationEnabled");
        if (deobf instanceof Boolean) {
            obfuscatedEnvironment = (Boolean) deobf;
        }
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
