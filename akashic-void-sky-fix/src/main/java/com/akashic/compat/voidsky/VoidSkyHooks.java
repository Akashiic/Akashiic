package com.akashic.compat.voidsky;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldType;

import java.lang.ref.WeakReference;

/**
 * Called from the bytecode injected by {@link VoidSkyTransformer}. Client side only.
 *
 * <p>Member names are SRG because that is what the game uses at runtime:
 * func_71410_x = Minecraft.getMinecraft(), field_71441_e = Minecraft.theWorld,
 * func_72912_H = World.getWorldInfo(), func_76067_t = WorldInfo.getTerrainType(),
 * func_72919_O = World.getHorizon(), field_73011_w = World.provider,
 * field_76574_g = WorldProvider.dimensionId, func_76565_k = WorldProvider.getVoidFogYFactor(),
 * field_77138_c = WorldType.FLAT, func_77127_a = WorldType.getWorldTypeName().
 */
public final class VoidSkyHooks {

    private static WeakReference<WorldClient> loggedWorld = new WeakReference<WorldClient>(null);

    private VoidSkyHooks() {
    }

    /**
     * RenderGlobal.renderSky: {@code playerY - world.getHorizon()}. Below zero vanilla draws a black
     * box around the sky that keeps rising as the player falls, until only a small square is left.
     *
     * <p>Clamped to the value it has at y=0, so below the world the sky looks the way it does at
     * the bottom of the world. This holds for every world type: in FLAT worlds (horizon 0) the box
     * never appears, in normal worlds (horizon 63) it stays below the horizon as at bedrock level.
     * At y &gt;= 0 the value is untouched.
     */
    public static double skyHeightAboveHorizon(double heightAboveHorizon) {
        if (heightAboveHorizon >= 0.0D) {
            return heightAboveHorizon;
        }
        WorldClient world = currentWorld();
        if (world == null) {
            return heightAboveHorizon;
        }
        return Math.max(heightAboveHorizon, -world.func_72919_O());
    }

    /**
     * EntityRenderer.updateFogColor and Dynamic Surroundings' BiomeFogColorCalculator:
     * {@code playerY * provider.getVoidFogYFactor()}. Below 1.0 the fog colour is multiplied by its
     * square, which turns everything black near and under y=0.
     *
     * <p>Only FLAT worlds are changed (no depth darkening at all): in normal worlds the darkening
     * starts at y=32 and is vanilla's deep-underground atmosphere. Blindness is applied by the
     * caller afterwards, so it keeps working.
     */
    public static double voidFogBrightness(double scaledHeight) {
        if (scaledHeight >= 1.0D) {
            return scaledHeight;
        }
        WorldClient world = currentWorld();
        return world != null && isFlat(world) ? 1.0D : scaledHeight;
    }

    private static WorldClient currentWorld() {
        Minecraft mc = Minecraft.func_71410_x();
        WorldClient world = mc == null ? null : mc.field_71441_e;
        if (world != null && loggedWorld.get() != world) {
            loggedWorld = new WeakReference<WorldClient>(world);
            logWorld(world);
        }
        return world;
    }

    private static boolean isFlat(WorldClient world) {
        return world.func_72912_H().func_76067_t() == WorldType.field_77138_c;
    }

    /** One line per client world, so a log shows exactly what the hooks saw. */
    private static void logWorld(WorldClient world) {
        try {
            WorldProvider provider = world.field_73011_w;
            WorldType type = world.func_72912_H().func_76067_t();
            boolean flat = type == WorldType.field_77138_c;
            System.out.println("[Akashic Void Sky Fix] world dim=" + provider.field_76574_g
                    + " provider=" + provider.getClass().getName()
                    + " terrainType=" + (type == null ? "null" : type.func_77127_a())
                    + " horizon=" + world.func_72919_O()
                    + " voidFogYFactor=" + provider.func_76565_k()
                    + " -> sky below y=0: fixed, depth fog: " + (flat ? "disabled (FLAT)" : "vanilla (not FLAT)"));
        } catch (Throwable t) {
            System.out.println("[Akashic Void Sky Fix] could not describe world: " + t);
        }
    }
}
