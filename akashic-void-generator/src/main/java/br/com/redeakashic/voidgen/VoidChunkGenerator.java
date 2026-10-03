package br.com.redeakashic.voidgen;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Void world generator that is safe on Crucible + EndlessIDs.
 *
 * <p>It produces the same terrain as VoidGenerator 1.0 (Bogdacutu, 2011): a single bedrock
 * block at (0, 64, 0) and nothing else. The difference is the API used to hand the blocks
 * to the server. VoidGenerator only implements the pre-1.2 {@code byte[] generate(...)};
 * for that one CraftBukkit's {@code CustomChunkGenerator} fills the chunk by writing into
 * {@code ExtendedBlockStorage.getBlockLSBArray()}, which EndlessIDs deliberately aborts on
 * ("A mod that is incompatible with EndlessIDs has tried to access the block array...").
 *
 * <p>This generator implements {@link #generateBlockSections}, so CustomChunkGenerator
 * builds each section through the {@code ExtendedBlockStorage(int, boolean, byte[], byte[])}
 * constructor and never calls the guarded accessors.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    /** Height of the single bedrock block placed in chunk (0, 0). */
    static final int PLATFORM_Y = 64;

    /** Blocks in one 16x16x16 section. */
    private static final int SECTION_VOLUME = 4096;

    @SuppressWarnings("deprecation")
    private static final byte BEDROCK_ID = (byte) Material.BEDROCK.getId();

    @Override
    @SuppressWarnings("deprecation")
    public byte[][] generateBlockSections(World world, Random random, int chunkX, int chunkZ, BiomeGrid biomes) {
        // Never return null here: null makes CustomChunkGenerator fall back to the legacy
        // generate() path, which is exactly the one EndlessIDs rejects.
        byte[][] sections = new byte[world.getMaxHeight() >> 4][];

        if (chunkX == 0 && chunkZ == 0) {
            byte[] section = new byte[SECTION_VOLUME];
            // Index layout: ((y & 0xF) << 8) | (z << 4) | x, with x = z = 0 here.
            section[(PLATFORM_Y & 0xF) << 8] = BEDROCK_ID;
            sections[PLATFORM_Y >> 4] = section;
        }

        return sections;
    }

    @Override
    public boolean canSpawn(World world, int x, int z) {
        return true;
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return new ArrayList<BlockPopulator>();
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        // Standing on the bedrock block, so Multiverse's spawn-safety check accepts it as is.
        return new Location(world, 0.5D, PLATFORM_Y + 1, 0.5D);
    }
}
