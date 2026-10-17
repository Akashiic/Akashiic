package com.akashic.bigdimlogin.mixins;

import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The vanilla getters of this packet are {@code @SideOnly(CLIENT)} and do not exist on a dedicated server, so the
 * fields are read directly.
 */
@Mixin(S01PacketJoinGame.class)
public interface AccessorS01PacketJoinGame {

    @Accessor("field_149206_a")
    int akashicbigdim$getEntityId();

    @Accessor("field_149204_b")
    boolean akashicbigdim$isHardcore();

    @Accessor("field_149205_c")
    WorldSettings.GameType akashicbigdim$getGameType();

    @Accessor("field_149202_d")
    int akashicbigdim$getDimension();

    @Accessor("field_149203_e")
    EnumDifficulty akashicbigdim$getDifficulty();

    @Accessor("field_149200_f")
    int akashicbigdim$getMaxPlayers();

    @Accessor("field_149201_g")
    WorldType akashicbigdim$getWorldType();
}
