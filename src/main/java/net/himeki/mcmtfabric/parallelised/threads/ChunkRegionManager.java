package net.himeki.mcmtfabric.parallelised.threads;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ChunkRegionManager {

    private final Map<ServerWorld, Map<Long, ChunkRegion>> regionsByWorld = new HashMap<>();
    private final Map<ServerWorld, Set<ChunkRegion>> activeRegions = new HashMap<>();
    private final Map<ServerWorld, Set<ChunkRegion>> previousActiveRegions = new HashMap<>();

    public synchronized void beginWorldTick(ServerWorld world) {
        Set<ChunkRegion> current = activeRegions.computeIfAbsent(world, key -> new LinkedHashSet<>());
        Set<ChunkRegion> previous = previousActiveRegions.computeIfAbsent(world, key -> new LinkedHashSet<>());
        previous.clear();
        previous.addAll(current);
        current.clear();
    }

    public synchronized void finishWorldTick(ServerWorld world) {
        Set<ChunkRegion> current = activeRegions.get(world);
        Set<ChunkRegion> previous = previousActiveRegions.get(world);
        if (current == null) {
            if (previous != null && !previous.isEmpty()) {
                for (ChunkRegion region : previous) {
                    region.finalizeTick(false);
                }
                previous.clear();
            }
            return;
        }

        for (ChunkRegion region : current) {
            region.finalizeTick(true);
        }

        if (previous != null && !previous.isEmpty()) {
            for (ChunkRegion region : previous) {
                if (!current.contains(region)) {
                    region.finalizeTick(false);
                }
            }
            previous.clear();
            previous.addAll(current);
        }
    }

    public ChunkRegion activateRegion(ServerWorld world, int chunkX, int chunkZ) {
        ChunkRegion region = getOrCreateRegion(world, chunkX, chunkZ);
        synchronized (this) {
            activeRegions.computeIfAbsent(world, key -> new LinkedHashSet<>()).add(region);
        }
        region.startTickIfNeeded();
        return region;
    }

    public synchronized ChunkRegion getRegion(ServerWorld world, int chunkX, int chunkZ) {
        Map<Long, ChunkRegion> regions = regionsByWorld.get(world);
        if (regions == null) {
            return null;
        }
        return regions.get(ChunkPos.toLong(chunkX, chunkZ));
    }

    public ChunkRegion getOrCreateRegion(ServerWorld world, int chunkX, int chunkZ) {
        synchronized (this) {
            Map<Long, ChunkRegion> regions = regionsByWorld.computeIfAbsent(world, key -> new HashMap<>());
            long key = ChunkPos.toLong(chunkX, chunkZ);
            return regions.computeIfAbsent(key, ignored -> new ChunkRegion(world, chunkX, chunkZ));
        }
    }

    public ChunkRegion getRegion(ServerPlayerEntity player) {
        ServerWorld world = Objects.requireNonNull(player.getServerWorld());
        ChunkPos pos = player.getChunkPos();
        return getOrCreateRegion(world, pos.x, pos.z);
    }

    public synchronized ChunkRegion getHeaviestRegion() {
        ChunkRegion heaviest = null;
        long maxWork = 0L;
        for (Map<Long, ChunkRegion> regions : regionsByWorld.values()) {
            for (ChunkRegion region : regions.values()) {
                long work = region.snapshotWorkDurations().totalWorkNanos();
                if (work > maxWork) {
                    maxWork = work;
                    heaviest = region;
                }
            }
        }
        return heaviest;
    }

    public synchronized void clear() {
        regionsByWorld.clear();
        activeRegions.clear();
        previousActiveRegions.clear();
    }
}
