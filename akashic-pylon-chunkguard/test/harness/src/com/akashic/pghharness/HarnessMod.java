package com.akashic.pghharness;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import Reika.ChromatiCraft.Magic.Network.CrystalNetworker;
import Reika.ChromatiCraft.TileEntity.Networking.TileEntityCrystalPylon;
import Reika.ChromatiCraft.World.IWG.PylonGenerator;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * TEST-ONLY harness (never install on a real server). Builds a reproducible crystal-pylon scenario with ChromatiCraft's
 * own generator and prints machine-readable measurements:
 *   [AKPG] tick=.. mspt=.. loaded=<chunks, all dims> loaded0=<overworld> loads=<ChunkEvent.Load in window>
 *          pylons=<TileEntityCrystalPylon loaded> net=<CrystalNetworker tiles> prevented=<ChunkGuard counter or -1>
 * Commands (console): akpg place <n> <spacing> | akpg fill | akpg stats | akpg ticket <x> <z> <r> | akpg pylons
 * Minecraft members are referenced by SRG name (see tools/build.sh); MCP names are in comments.
 */
@Mod(modid = "akashic_pg_harness", name = "AKPG test harness", version = "test", acceptableRemoteVersions = "*")
public class HarnessMod {

	static final Logger LOG = LogManager.getLogger("AKPG");
	static final int INTERVAL = Integer.getInteger("akpg.interval", 200);

	@Mod.Instance("akashic_pg_harness")
	public static HarnessMod instance;

	private long loadsWindow;
	private long loadsTotal;
	private int ticks;

	@Mod.EventHandler
	public void init(FMLInitializationEvent e) {
		ForgeChunkManager.setForcedChunkLoadingCallback(this, new ForgeChunkManager.LoadingCallback() {
			@Override
			public void ticketsLoaded(List<ForgeChunkManager.Ticket> tickets, World world) {
				for (ForgeChunkManager.Ticket t : tickets)
					ForgeChunkManager.releaseTicket(t); // test tickets never survive a restart
			}
		});
	}

	@Mod.EventHandler
	public void serverStarting(FMLServerStartingEvent e) {
		e.registerServerCommand(new Cmd());
		FMLCommonHandler.instance().bus().register(this);
		MinecraftForge.EVENT_BUS.register(this);
	}

	@SubscribeEvent
	public void onChunkLoad(ChunkEvent.Load e) {
		if (!e.world.field_72995_K) { // isRemote
			loadsWindow++;
			loadsTotal++;
		}
	}

	@SubscribeEvent
	public void onTick(TickEvent.ServerTickEvent e) {
		if (e.phase != TickEvent.Phase.END)
			return;
		if (++ticks % INTERVAL == 0)
			LOG.info(stats());
	}

	String stats() {
		MinecraftServer srv = MinecraftServer.func_71276_C(); // getServer
		long sum = 0;
		for (long t : srv.field_71311_j) // tickTimeArray
			sum += t;
		double mspt = sum / (double) srv.field_71311_j.length / 1.0e6;
		int loaded = 0, loaded0 = 0, pylons = 0;
		for (WorldServer ws : srv.field_71305_c) { // worldServers
			int n = ws.field_73059_b.func_73152_e(); // theChunkProviderServer.getLoadedChunkCount()
			loaded += n;
			if (ws.field_73011_w.field_76574_g == 0)
				loaded0 = n;
			for (Object o : ws.field_147482_g) // loadedTileEntityList
				if (o instanceof TileEntityCrystalPylon)
					pylons++;
		}
		long prevented = -1, resolved = -1;
		try {
			prevented = (Long) Class.forName("com.akashic.pylonguard.ChunkGuard").getMethod("preventedSoFar").invoke(null);
			resolved = (Long) Class.forName("com.akashic.pylonguard.ChunkGuard").getMethod("resolvedSoFar").invoke(null);
		}
		catch (Throwable ignored) {
		}
		String s = String.format("[AKPG] tick=%d mspt=%.2f loaded=%d loaded0=%d loads=%d loadsTotal=%d pylons=%d net=%d prevented=%d resolved=%d",
			srv.func_71259_af(), mspt, loaded, loaded0, loadsWindow, loadsTotal, pylons, CrystalNetworker.instance.size(), prevented, resolved);
		loadsWindow = 0;
		return s;
	}

	/** Typed as TileEntity on purpose: javac cannot resolve inherited members through the pylon class here (its
	 *  Thaumcraft API interfaces are not on the compile classpath). */
	static List<TileEntity> loadedPylons(World w) {
		List<TileEntity> li = new ArrayList<TileEntity>();
		for (Object o : w.field_147482_g) // loadedTileEntityList
			if (o instanceof TileEntityCrystalPylon)
				li.add((TileEntity) o);
		return li;
	}

	/**
	 * Sets the pylon to full energy. Capacity = TileEntityCrystalPylon.getCapacity() in V33a: isEnhanced() ? 900000 :
	 * 180000; generated pylons are never enhanced. (Reflective method lookups are avoided: they resolve client-only
	 * types on a dedicated server.)
	 */
	static void fill(TileEntity te) throws Exception {
		Field energy = TileEntityCrystalPylon.class.getDeclaredField("energy");
		energy.setAccessible(true);
		energy.setInt(te, 180000);
		te.func_70296_d(); // markDirty
	}

	class Cmd extends CommandBase {
		@Override
		public String func_71517_b() { // getCommandName
			return "akpg";
		}

		@Override
		public String func_71518_a(ICommandSender s) { // getCommandUsage
			return "/akpg place <n> <spacing> | fill | stats | ticket <x> <z> <r> | pylons | setenergy <x> <z> <v>";
		}

		@Override
		public void func_71515_b(ICommandSender s, String[] a) { // processCommand
			try {
				run(s, a);
			}
			catch (Exception ex) {
				LOG.error("akpg failed", ex);
				say(s, "error: " + ex);
			}
		}

		private void run(ICommandSender s, String[] a) throws Exception {
			WorldServer w = MinecraftServer.func_71276_C().field_71305_c[0];
			if (a.length == 0) {
				say(s, func_71518_a(s));
				return;
			}
			if (a[0].equals("place")) {
				int n = Integer.parseInt(a[1]);
				int spacing = Integer.parseInt(a[2]);
				Method gen = PylonGenerator.class.getDeclaredMethod("generatePylon", Random.class, World.class, int.class, int.class, int.class);
				gen.setAccessible(true);
				Random r = new Random(1234);
				for (int i = 0; i < n; i++) {
					int x = 3000 + i * spacing;
					x = (x & ~15) + 2; // 2 blocks from the chunk border: the 12-block scan reaches the neighbour chunks
					int z = (3000 & ~15) + 2;
					for (int dx = -1; dx <= 1; dx++)
						for (int dz = -1; dz <= 1; dz++)
							w.func_72964_e((x >> 4) + dx, (z >> 4) + dz); // getChunkFromChunkCoords (generate)
					int y = w.func_72825_h(x, z) - 1; // getTopSolidOrLiquidBlock
					gen.invoke(PylonGenerator.instance, r, w, x, y, z);
					int filled = 0;
					for (TileEntity te : loadedPylons(w)) {
						if (Math.abs(te.field_145851_c - x) <= 32 && Math.abs(te.field_145849_e - z) <= 32) {
							fill(te); // full energy, like any pylon nobody has drained for a while
							filled++;
						}
					}
					LOG.info("[AKPG] placed pylon #" + i + " near " + x + "," + (y + 9) + "," + z + " filled=" + filled);
				}
				say(s, "placed " + n);
			}
			else if (a[0].equals("fill")) {
				int k = 0;
				for (TileEntity te : loadedPylons(w)) {
					fill(te);
					k++;
				}
				LOG.info("[AKPG] filled " + k + " pylons");
				say(s, "filled " + k);
			}
			else if (a[0].equals("stats")) {
				String st = stats();
				LOG.info(st);
				say(s, st);
			}
			else if (a[0].equals("pylons")) {
				Field energy = TileEntityCrystalPylon.class.getDeclaredField("energy");
				energy.setAccessible(true);
				for (TileEntity te : loadedPylons(w))
					LOG.info("[AKPG] pylon " + te.field_145851_c + "," + te.field_145848_d + "," + te.field_145849_e + " energy=" + energy.getInt(te));
			}
			else if (a[0].equals("setenergy")) {
				int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]), v = Integer.parseInt(a[3]);
				Field energy = TileEntityCrystalPylon.class.getDeclaredField("energy");
				energy.setAccessible(true);
				for (TileEntity te : loadedPylons(w)) {
					if (Math.abs(te.field_145851_c - x) <= 32 && Math.abs(te.field_145849_e - z) <= 32) {
						energy.setInt(te, v);
						LOG.info("[AKPG] set energy of pylon " + te.field_145851_c + "," + te.field_145848_d + "," + te.field_145849_e + " to " + v);
					}
				}
			}
			else if (a[0].equals("charger")) {
				// place a ChromatiCraft Crystal Charger (a real network receiver, range 20, needs line of sight)
				int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
				Reika.ChromatiCraft.Registry.ChromaTiles t = Reika.ChromatiCraft.Registry.ChromaTiles.CHARGER;
				w.func_147465_d(x, y, z, t.getBlock(), t.getBlockMetadata(), 3); // setBlock
				LOG.info("[AKPG] charger placed at " + x + "," + y + "," + z + " tile=" + w.func_147438_o(x, y, z));
			}
			else if (a[0].equals("repeater")) {
				// akpg repeater <x> <y> <z> <pylonX> <pylonY> <pylonZ>: a real Crystal Repeater (rune of the pylon's colour
				// below it, then two pylon-structure blocks: TileEntityCrystalRepeater.checkForStructure, facing DOWN)
				int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
				TileEntity py = w.func_147438_o(Integer.parseInt(a[4]), Integer.parseInt(a[5]), Integer.parseInt(a[6]));
				Field col = TileEntityCrystalPylon.class.getDeclaredField("color");
				col.setAccessible(true);
				int color = ((Enum<?>) col.get(py)).ordinal();
				net.minecraft.block.Block struct = Reika.ChromatiCraft.Registry.ChromaBlocks.PYLONSTRUCT.getBlockInstance();
				net.minecraft.block.Block rune = Reika.ChromatiCraft.Registry.ChromaBlocks.RUNE.getBlockInstance();
				w.func_147465_d(x, y - 3, z, struct, 0, 3);
				w.func_147465_d(x, y - 2, z, struct, 0, 3);
				w.func_147465_d(x, y - 1, z, rune, color, 3);
				Reika.ChromatiCraft.Registry.ChromaTiles t = Reika.ChromatiCraft.Registry.ChromaTiles.REPEATER;
				w.func_147465_d(x, y, z, t.getBlock(), t.getBlockMetadata(), 3);
				LOG.info("[AKPG] repeater placed at " + x + "," + y + "," + z + " colour " + color + " tile=" + w.func_147438_o(x, y, z));
			}
			else if (a[0].equals("encrusted")) {
				// akpg encrusted <x> <z>: encrusted-crystal tiles within 16 blocks, in loaded chunks only. Read from the chunks'
				// tile maps: TileCrystalEncrusted.canUpdate() is false, so it is never in World.loadedTileEntityList.
				int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]), n = 0;
				for (int cx = (x - 16) >> 4; cx <= (x + 16) >> 4; cx++)
					for (int cz = (z - 16) >> 4; cz <= (z + 16) >> 4; cz++) {
						if (!w.func_72863_F().func_73149_a(cx, cz)) // getChunkProvider().chunkExists: never loads
							continue;
						for (Object o : w.func_72964_e(cx, cz).field_150816_i.values()) // getChunkFromChunkCoords, chunkTileEntityMap
							if (o instanceof Reika.ChromatiCraft.Block.BlockEncrustedCrystal.TileCrystalEncrusted) {
								TileEntity te = (TileEntity) o;
								if (Math.abs(te.field_145851_c - x) <= 16 && Math.abs(te.field_145849_e - z) <= 16)
									n++;
							}
					}
				LOG.info("[AKPG] encrusted near " + x + "," + z + " = " + n);
			}
			else if (a[0].equals("grow")) {
				// akpg grow <x> <y> <z> <n>: runs the pylon's own (private) tryGrowEncrusted n times -- ChromatiCraft's code
				// with whatever mixins are applied -- and reports the pylon's counted crystals and the chunk loads meanwhile.
				// Method handles, not Class.getDeclaredMethod: the latter resolves every signature of the class (Thaumcraft
				// types, absent on the test server).
				int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]), n = Integer.parseInt(a[4]);
				TileEntity te = w.func_147438_o(x, y, z); // getTileEntity
				if (!(te instanceof TileEntityCrystalPylon)) {
					LOG.info("[AKPG] grow: no pylon at " + x + "," + y + "," + z);
					return;
				}
				MethodHandles.Lookup lk = (MethodHandles.Lookup) MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class).invoke(null, TileEntityCrystalPylon.class, MethodHandles.lookup());
				MethodHandle grow = lk.findVirtual(TileEntityCrystalPylon.class, "tryGrowEncrusted", MethodType.methodType(void.class, World.class, int.class, int.class, int.class));
				MethodHandle counted = lk.findGetter(TileEntityCrystalPylon.class, "encrustedBlocks", java.util.HashSet.class);
				long l0 = loadsTotal;
				try {
					for (int i = 0; i < n; i++)
						grow.invoke((TileEntityCrystalPylon) te, (World) w, x, y, z);
				}
				catch (Throwable t) {
					throw new RuntimeException(t);
				}
				int c;
				try {
					c = ((java.util.HashSet<?>) counted.invoke((TileEntityCrystalPylon) te)).size();
				}
				catch (Throwable t) {
					throw new RuntimeException(t);
				}
				LOG.info("[AKPG] grow " + n + " attempts at " + x + "," + y + "," + z + ": counted=" + c + " chunkLoadsDuring=" + (loadsTotal - l0));
			}
			else if (a[0].equals("removepylon")) {
				// akpg removepylon <x> <y> <z>: delete the pylon block+tile the way an admin tool would (no mod hook)
				int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
				w.func_147468_f(x, y, z); // setBlockToAir
				LOG.info("[AKPG] removed block at " + x + "," + y + "," + z + " tile now=" + w.func_147438_o(x, y, z));
			}
			else if (a[0].equals("pyloncache")) {
				// akpg pyloncache <x> <z>: is there a PylonGenerator cache entry at x,z (any y)?
				int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
				Field cc = PylonGenerator.class.getDeclaredField("colorCache");
				cc.setAccessible(true);
				java.util.Map<?, ?> map = (java.util.Map<?, ?>) cc.get(PylonGenerator.instance);
				int total = 0;
				String found = "none";
				for (Object col : map.values())
					for (Object e : (java.util.Collection<?>) col) {
						total++;
						Reika.DragonAPI.Instantiable.Data.Immutable.WorldLocation l = ((PylonGenerator.PylonEntry) e).location;
						if (l.xCoord == x && l.zCoord == z)
							found = e.toString();
					}
				LOG.info("[AKPG] pyloncache at " + x + "," + z + ": " + found + " (cache size " + total + ")");
			}
			else if (a[0].equals("chargerstat")) {
				Field en = Reika.ChromatiCraft.Base.TileEntity.CrystalReceiverBase.class.getDeclaredField("energy");
				en.setAccessible(true);
				for (Object o : w.field_147482_g) // loadedTileEntityList
					if (o instanceof Reika.ChromatiCraft.TileEntity.Auxiliary.TileEntityCrystalCharger) {
						TileEntity te = (TileEntity) o;
						Reika.ChromatiCraft.Magic.ElementTagCompound tag = (Reika.ChromatiCraft.Magic.ElementTagCompound) en.get(o);
						LOG.info("[AKPG] charger " + te.field_145851_c + "," + te.field_145848_d + "," + te.field_145849_e + " energy=" + tag.getTotalEnergy() + " " + tag);
					}
			}
			else if (a[0].equals("ticket")) {
				int cx = Integer.parseInt(a[1]) >> 4, cz = Integer.parseInt(a[2]) >> 4, r = Integer.parseInt(a[3]);
				ForgeChunkManager.Ticket t = ForgeChunkManager.requestTicket(instance, w, ForgeChunkManager.Type.NORMAL);
				for (int dx = -r; dx <= r; dx++)
					for (int dz = -r; dz <= r; dz++)
						ForgeChunkManager.forceChunk(t, new ChunkCoordIntPair(cx + dx, cz + dz));
				LOG.info("[AKPG] ticket forcing " + (2 * r + 1) * (2 * r + 1) + " chunks around chunk " + cx + "," + cz);
				say(s, "ticket ok");
			}
		}

		@Override
		public int compareTo(Object o) {
			return o instanceof net.minecraft.command.ICommand ? func_71517_b().compareTo(((net.minecraft.command.ICommand) o).func_71517_b()) : 0;
		}

		private void say(ICommandSender s, String m) {
			s.func_145747_a(new ChatComponentText(m)); // addChatMessage
		}
	}
}
