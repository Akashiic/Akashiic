package com.akashic.bigdimlogin.mixins;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In 1.7.10 the dimension of {@link S01PacketJoinGame} is serialized as a signed byte, so any dimension outside
 * -128..127 (Multiverse/Bukkit worlds, DimDoors pockets...) arrives corrupted: 253 becomes -3. Forge clients connected
 * directly survive thanks to the FML handshake override, but a proxy (Velocity/Bungee) decodes the byte and replays it
 * in its own Respawn packet, crashing the client with "Could not get provider type for dimension -3".
 * <p>
 * Fix: a login into such a dimension is sent as a JoinGame into the overworld followed immediately by a Respawn into
 * the real dimension. The Respawn packet carries the dimension as an int, which is exactly the path a normal
 * dimension change ({@code /mv tp}, portals) already uses.
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayServer {

    @Unique
    private static final Logger akashicbigdim$LOG = LogManager.getLogger("AkashicBigDimLogin");

    /** Placeholder dimension for the JoinGame packet; the overworld is always registered on every client. */
    @Unique
    private static final int akashicbigdim$SAFE_DIMENSION = 0;

    @Shadow
    public EntityPlayerMP playerEntity;

    @Shadow
    public abstract void sendPacket(Packet packet);

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void akashicbigdim$splitBigDimensionLogin(Packet packet, CallbackInfo ci) {
        if (!(packet instanceof S01PacketJoinGame)) {
            return;
        }
        AccessorS01PacketJoinGame join = (AccessorS01PacketJoinGame) packet;
        int dimension = join.akashicbigdim$getDimension();
        if (dimension == (byte) dimension) {
            return;
        }

        ci.cancel();
        // The rewritten JoinGame fits in a byte, so this nested call goes through untouched.
        sendPacket(
            new S01PacketJoinGame(
                join.akashicbigdim$getEntityId(),
                join.akashicbigdim$getGameType(),
                join.akashicbigdim$isHardcore(),
                akashicbigdim$SAFE_DIMENSION,
                join.akashicbigdim$getDifficulty(),
                join.akashicbigdim$getMaxPlayers(),
                join.akashicbigdim$getWorldType()));
        sendPacket(
            new S07PacketRespawn(
                dimension,
                join.akashicbigdim$getDifficulty(),
                join.akashicbigdim$getWorldType(),
                join.akashicbigdim$getGameType()));

        akashicbigdim$LOG.info(
            "Login of {} into dimension {} (outside the JoinGame byte range): sent JoinGame(dim={}) + Respawn(dim={})",
            playerEntity != null ? playerEntity.getCommandSenderName() : "<unknown>",
            dimension,
            akashicbigdim$SAFE_DIMENSION,
            dimension);
    }
}
