package com.ascvnd.maceroulette;

import org.bukkit.generator.ChunkGenerator;

/**
 * Generates an empty world (pure void). No terrain, caves, decorations,
 * structures or mobs are generated - the platform is built manually afterwards.
 */
public class VoidChunkGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
