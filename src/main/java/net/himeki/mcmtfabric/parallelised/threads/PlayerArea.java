package net.himeki.mcmtfabric.parallelised.threads;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Represents the chunk area loaded by a single player based on their view distance.
 */
public record PlayerArea(ServerPlayerEntity player, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

    public boolean overlaps(PlayerArea other) {
        return this.maxChunkX >= other.minChunkX()
                && this.minChunkX <= other.maxChunkX()
                && this.maxChunkZ >= other.minChunkZ()
                && this.minChunkZ <= other.maxChunkZ();
    }
}
