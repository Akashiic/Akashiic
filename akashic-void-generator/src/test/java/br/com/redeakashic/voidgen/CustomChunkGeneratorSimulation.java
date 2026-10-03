package br.com.redeakashic.voidgen;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Random;

/**
 * Runs chunk generators through a line-by-line port of the block-filling part of Crucible's
 * {@code CustomChunkGenerator.provideChunk} (src/main/java/org/bukkit/craftbukkit/v1_7_R4/
 * generator/CustomChunkGenerator.java, lines 45-136 on the staging branch), using a stand-in
 * for {@code ExtendedBlockStorage} whose {@code getBlockLSBArray()} throws the same way
 * EndlessIDs' ExtendedBlockStorageMixin does.
 *
 * <p>Usage: {@code java -cp <bukkit-api>:<classes> ...CustomChunkGeneratorSimulation [legacy-generator.jar main-class]}
 */
public final class CustomChunkGeneratorSimulation {

    private static final int RADIUS = 13; // Same 25x25 chunk area as CraftServer's spawn preparation.
    private static final int BEDROCK = 7;

    private static int failures;

    public static void main(String[] args) throws Exception {
        World world = fakeWorld();

        System.out.println("== AkashicVoidGenerator (VoidChunkGenerator)");
        // JavaPlugin can only be constructed by Bukkit's PluginClassLoader; the plugin just returns this.
        ChunkGenerator generator = new VoidChunkGenerator();
        int crashes = runArea(generator, world);
        check(crashes == 0, "no chunk touches getBlockLSBArray()");
        Section[] origin = provideChunk(generator, world, 0, 0);
        check(blockAt(origin, 0, 64, 0) == BEDROCK, "bedrock at (0,64,0)");
        check(countBlocks(origin) == 1, "exactly one block in chunk (0,0)");
        check(countBlocks(provideChunk(generator, world, 1, 0)) == 0, "chunk (1,0) is empty");
        Location spawn = generator.getFixedSpawnLocation(world, new Random(0));
        check(spawn.getBlockX() == 0 && spawn.getBlockY() == 65 && spawn.getBlockZ() == 0, "spawn block is (0,65,0)");
        check(blockAt(origin, spawn.getBlockX(), spawn.getBlockY() - 1, spawn.getBlockZ()) == BEDROCK, "spawn stands on bedrock");

        if (args.length >= 2) {
            System.out.println("== legacy " + args[1] + " from " + args[0]);
            URLClassLoader loader = new URLClassLoader(new URL[]{new File(args[0]).toURI().toURL()},
                    CustomChunkGeneratorSimulation.class.getClassLoader());
            ChunkGenerator legacy = (ChunkGenerator) loader.loadClass(args[1]).getConstructor().newInstance();
            int legacyCrashes = runArea(legacy, world);
            check(legacyCrashes == 1, "legacy generator hits getBlockLSBArray() in exactly one chunk");
            try {
                provideChunk(legacy, world, 0, 0);
                check(false, "legacy generator crashes in chunk (0,0)");
            } catch (UnsupportedOperationException expected) {
                check(true, "legacy generator crashes in chunk (0,0): " + expected.getMessage().split("\n")[0]);
            }
        }

        if (failures > 0) {
            System.out.println("FAILED: " + failures + " check(s)");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    private static int runArea(ChunkGenerator generator, World world) {
        int crashes = 0;
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                try {
                    provideChunk(generator, world, x, z);
                } catch (UnsupportedOperationException e) {
                    System.out.println("   chunk (" + x + "," + z + ") -> " + e.getMessage().split("\n")[0]);
                    crashes++;
                }
            }
        }
        System.out.println("   chunks generated: " + (4 * RADIUS * RADIUS) + ", crashes: " + crashes);
        return crashes;
    }

    /** Port of CustomChunkGenerator.provideChunk, block part only (biomes and lighting omitted). */
    @SuppressWarnings("deprecation")
    static Section[] provideChunk(ChunkGenerator generator, World world, int x, int z) {
        Random random = new Random((long) x * 341873128712L + (long) z * 132897987541L);
        ChunkGenerator.BiomeGrid biomegrid = new FakeBiomeGrid();
        Section[] csect = new Section[16]; // chunk.getBlockStorageArray()

        short[][] xbtypes = generator.generateExtBlockSections(world, random, x, z, biomegrid);
        if (xbtypes != null) {
            int scnt = Math.min(csect.length, xbtypes.length);
            for (int sec = 0; sec < scnt; sec++) {
                if (xbtypes[sec] == null) {
                    continue;
                }
                byte[] secBlkID = new byte[4096];
                byte[] secExtBlkID = null;
                short[] bdata = xbtypes[sec];
                for (int i = 0, j = 0; i < bdata.length; i += 2, j++) {
                    short b1 = bdata[i];
                    short b2 = bdata[i + 1];
                    byte extb = (byte) ((b1 >> 8) | ((b2 >> 4) & 0xF0));
                    secBlkID[i] = (byte) b1;
                    secBlkID[(i + 1)] = (byte) b2;
                    if (extb != 0) {
                        if (secExtBlkID == null) {
                            secExtBlkID = new byte[2048];
                        }
                        secExtBlkID[j] = extb;
                    }
                }
                csect[sec] = new Section(sec << 4, true, secBlkID, secExtBlkID);
            }
        } else {
            byte[][] btypes = generator.generateBlockSections(world, random, x, z, biomegrid);
            if (btypes != null) {
                int scnt = Math.min(csect.length, btypes.length);
                for (int sec = 0; sec < scnt; sec++) {
                    if (btypes[sec] == null) {
                        continue;
                    }
                    csect[sec] = new Section(sec << 4, true, btypes[sec], null);
                }
            } else {
                byte[] types = generator.generate(world, random, x, z);
                int ydim = types.length / 256;
                int scnt = ydim / 16;
                scnt = Math.min(scnt, csect.length);
                for (int sec = 0; sec < scnt; sec++) {
                    Section cs = null;
                    byte[] csbytes = null;
                    for (int cy = 0; cy < 16; cy++) {
                        int cyoff = cy | (sec << 4);
                        for (int cx = 0; cx < 16; cx++) {
                            int cxyoff = (cx * ydim * 16) + cyoff;
                            for (int cz = 0; cz < 16; cz++) {
                                byte blk = types[cxyoff + (cz * ydim)];
                                if (blk != 0) {
                                    if (cs == null) {
                                        cs = csect[sec] = new Section(sec << 4, true);
                                        csbytes = cs.getBlockLSBArray(); // CustomChunkGenerator.java:123
                                    }
                                    csbytes[(cy << 8) | (cz << 4) | cx] = blk;
                                }
                            }
                        }
                    }
                }
            }
        }
        return csect;
    }

    /** Stand-in for ExtendedBlockStorage as seen with EndlessIDs installed. */
    static final class Section {
        final byte[] blockLSBArray;

        Section(int yBase, boolean hasSky) {
            this.blockLSBArray = new byte[4096];
        }

        // Crucible's CraftBukkit constructor: assigns the field directly, no guarded accessor.
        Section(int yBase, boolean hasSky, byte[] blkIds, byte[] extBlkIds) {
            if (blkIds.length != 4096) {
                throw new IllegalStateException("section must hold 4096 block ids, got " + blkIds.length);
            }
            this.blockLSBArray = blkIds;
        }

        byte[] getBlockLSBArray() {
            // EndlessIDs ExtendedBlockStorageMixin.crashLSBArray -> emergencyCrash()
            throw new UnsupportedOperationException("A mod that is incompatible with EndlessIDs has tried to access the block array of a chunk like in vanilla! Crashing in fear of potential world corruption!");
        }
    }

    private static int blockAt(Section[] sections, int x, int y, int z) {
        Section s = sections[y >> 4];
        return s == null ? 0 : s.blockLSBArray[((y & 0xF) << 8) | (z << 4) | x] & 0xFF;
    }

    private static int countBlocks(Section[] sections) {
        int n = 0;
        for (Section s : sections) {
            if (s == null) {
                continue;
            }
            for (byte b : s.blockLSBArray) {
                if (b != 0) {
                    n++;
                }
            }
        }
        return n;
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "   OK   " : "   FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static World fakeWorld() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getMaxHeight":
                    return 256;
                case "getName":
                    return "Spawn";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "FakeWorld[Spawn]";
                default:
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0D;
                    if (rt == float.class) return 0F;
                    return null;
            }
        });
    }

    private static final class FakeBiomeGrid implements ChunkGenerator.BiomeGrid {
        @Override
        public Biome getBiome(int x, int z) {
            return Biome.PLAINS;
        }

        @Override
        public void setBiome(int x, int z, Biome bio) {
        }
    }
}
