package br.com.redeakashic.ae2ic2fix;

import java.util.Collections;

import com.google.common.eventbus.EventBus;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.ModMetadata;

/** Only exists so the fix shows up in the mod list and crash reports. No network presence. */
public class AkashicAE2FixContainer extends DummyModContainer {

    public static final String MODID = "akashic_ae2_ic2_nullworld_fix";
    public static final String VERSION = "1.0.0";

    public AkashicAE2FixContainer() {
        super(new ModMetadata());
        final ModMetadata md = this.getMetadata();
        md.modId = MODID;
        md.name = "Akashic AE2 IC2 NullWorld Fix";
        md.version = VERSION;
        md.authorList = Collections.singletonList("Akashiic");
        md.description = "Server-side hotfix: stops AE2 Advanced Inscriber from crashing the server with IC2 "
                + "'NullPointerException: world is null' (AE2 GTNH < rv3-beta-1078).";
    }

    @Override
    public boolean registerBus(final EventBus bus, final LoadController controller) {
        return true;
    }
}
