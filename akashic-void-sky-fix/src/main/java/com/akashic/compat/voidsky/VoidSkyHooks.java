package com.akashic.compat.voidsky;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.WorldType;

/**
 * Called from the bytecode injected by {@link VoidSkyTransformer}. Client side only.
 *
 * <p>Both hooks only act in FLAT worlds (the same check vanilla World.getHorizon() uses to put
 * the horizon at y=0) and only below the level where vanilla starts darkening, so the sky and
 * fog above the world stay exactly vanilla.
 *
 * <p>Member names are SRG because that is what the game uses at runtime:
 * func_71410_x = Minecraft.getMinecraft(), field_71441_e = Minecraft.theWorld,
 * func_72912_H = World.getWorldInfo(), func_76067_t = WorldInfo.getTerrainType(),
 * field_77138_c = WorldType.FLAT.
 */
public final class VoidSkyHooks {

    private VoidSkyHooks() {
    }

    /**
     * RenderGlobal.renderSky: {@code playerY - world.getHorizon()}. Below zero vanilla draws the
     * black box that rises around the player until only a small square of sky is left.
     */
    public static double skyHeightAboveHorizon(double heightAboveHorizon) {
        return heightAboveHorizon < 0.0D && isVoidSkyWorld() ? 0.0D : heightAboveHorizon;
    }

    /**
     * EntityRenderer.updateFogColor and Dynamic Surroundings' BiomeFogColorCalculator:
     * {@code playerY * provider.getVoidFogYFactor()}. Below 1.0 the fog colour is multiplied by
     * its square, which turns everything black under the world. Blindness is applied afterwards
     * by the caller, so it keeps working.
     */
    public static double voidFogBrightness(double scaledHeight) {
        return scaledHeight < 1.0D && isVoidSkyWorld() ? 1.0D : scaledHeight;
    }

    static boolean isVoidSkyWorld() {
        Minecraft mc = Minecraft.func_71410_x();
        WorldClient world = mc == null ? null : mc.field_71441_e;
        return world != null && world.func_72912_H().func_76067_t() == WorldType.field_77138_c;
    }
}
