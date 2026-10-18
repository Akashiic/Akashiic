package com.akashic.baubleslayout;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.akashic.baubleslayout.network.LayoutMessage;
import com.akashic.baubleslayout.network.LayoutMessageHandler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * BaublesExpanded builds its slot layout independently on each side from the locally installed mods and configs, and
 * never tells the client what the server's layout is. Any difference (a server-only mod, a config change, load order)
 * leaves the client with fewer/differently ordered slots, and the first sync packet for a slot index the client does
 * not have throws ArrayIndexOutOfBoundsException and drops the connection.
 * <p>
 * This mod makes the server authoritative: at the start of every connection, before JoinGame and before any Baubles
 * packet, the server sends its slot layout and the client adopts it for that connection.
 */
@Mod(
    modid = AkashicBaublesLayout.MODID,
    name = "Akashic Baubles Layout Sync",
    version = Tags.VERSION,
    dependencies = "after:Baubles|Expanded",
    acceptableRemoteVersions = "*")
public class AkashicBaublesLayout {

    public static final String MODID = "akashicbaubleslayout";
    public static final String BAUBLES_MODID = "Baubles|Expanded";
    public static final Logger LOG = LogManager.getLogger("AkashicBaublesLayout");

    public static SimpleNetworkWrapper channel;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (!Loader.isModLoaded(BAUBLES_MODID)) {
            LOG.warn("BaublesExpanded is not installed; nothing to do.");
            return;
        }
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("akbaublayout");
        channel.registerMessage(LayoutMessageHandler.class, LayoutMessage.class, 0, Side.CLIENT);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        if (event.getSide() == Side.CLIENT) {
            FMLCommonHandler.instance()
                .bus()
                .register(new ClientLayout.Events());
        }
    }

    @SubscribeEvent
    public void onServerConnection(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        // Integrated server shares the static layout with its own client, so there is nothing to sync.
        if (event.isLocal) {
            return;
        }
        // Fired right before the server sends JoinGame, so this lands ahead of every Baubles sync packet.
        event.manager.scheduleOutboundPacket(channel.getPacketFrom(LayoutMessage.fromCurrentLayout()));
    }
}
