package net.himeki.mcmtfabric.parallelised.threads;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

public class ChunkRegion extends ThreadedChunksRegion {

    private final int chunkX;
    private final int chunkZ;

    public ChunkRegion(ServerWorld world, int chunkX, int chunkZ) {
        super(buildName(chunkX, chunkZ), world.getRegistryKey().getValue().toString(), chunkX, chunkZ, chunkX, chunkZ, "chunk");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    private static String buildName(int chunkX, int chunkZ) {
        return "chunk_" + chunkX + "_" + chunkZ;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public ChunkPos getChunkPos() {
        return new ChunkPos(chunkX, chunkZ);
    }
}
