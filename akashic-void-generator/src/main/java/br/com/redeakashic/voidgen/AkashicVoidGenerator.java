package br.com.redeakashic.voidgen;

import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Exposes {@link VoidChunkGenerator} to Multiverse / bukkit.yml:
 * {@code /mv create <name> normal -t flat -g AkashicVoidGenerator}.
 */
public final class AkashicVoidGenerator extends JavaPlugin {

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (id != null && !id.isEmpty()) {
            getLogger().warning("Generator id '" + id + "' for world '" + worldName + "' is not supported and was ignored.");
        }
        return new VoidChunkGenerator();
    }
}
