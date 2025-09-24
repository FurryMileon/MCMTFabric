package net.himeki.mcmtfabric.parallelised.threads;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Maintains the dynamic mapping between players and threaded regions.
 */
public class PlayerRegionManager {

    private final Map<String, PlayerRegion> activeRegions = new HashMap<>();
    private final Map<ServerWorld, Cache<ChunkPos, PlayerRegion>> chunkRegionCaches = new HashMap<>();
    private volatile Map<ServerWorld, List<PlayerRegion>> regionsByWorld = Map.of();

    public synchronized void updateRegions(MinecraftServer server) {
        Map<String, PlayerRegion> nextActive = new HashMap<>();
        Map<ServerWorld, List<PlayerRegion>> nextRegionsByWorld = new HashMap<>();
        Set<ServerWorld> seenWorlds = new HashSet<>();

        for (ServerWorld world : server.getWorlds()) {
            seenWorlds.add(world);
            List<PlayerArea> areas = collectPlayerAreas(world);
            if (areas.isEmpty()) {
                Cache<ChunkPos, PlayerRegion> cache = chunkRegionCaches.get(world);
                if (cache != null) {
                    cache.invalidateAll();
                }
                continue;
            }

            List<PlayerRegion> regions = buildRegionsForWorld(world, areas, nextActive);
            nextRegionsByWorld.put(world, List.copyOf(regions));
            chunkRegionCaches
                    .computeIfAbsent(world, key -> Caffeine.newBuilder().maximumSize(8192).build())
                    .invalidateAll();
        }

        // Shutdown regions that are no longer active
        for (Map.Entry<String, PlayerRegion> entry : activeRegions.entrySet()) {
            if (!nextActive.containsKey(entry.getKey())) {
                entry.getValue().shutdownExecutors();
            }
        }

        activeRegions.clear();
        activeRegions.putAll(nextActive);

        // Drop caches for worlds that disappeared
        chunkRegionCaches.keySet().removeIf(world -> !seenWorlds.contains(world));

        regionsByWorld = Map.copyOf(nextRegionsByWorld);
    }

    private List<PlayerRegion> buildRegionsForWorld(ServerWorld world, List<PlayerArea> areas, Map<String, PlayerRegion> nextActive) {
        int size = areas.size();
        boolean[] visited = new boolean[size];
        List<PlayerRegion> regions = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            if (visited[i]) {
                continue;
            }
            List<PlayerArea> group = new ArrayList<>();
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            visited[i] = true;

            while (!queue.isEmpty()) {
                int idx = queue.removeFirst();
                PlayerArea current = areas.get(idx);
                group.add(current);
                for (int j = 0; j < size; j++) {
                    if (!visited[j] && current.overlaps(areas.get(j))) {
                        visited[j] = true;
                        queue.add(j);
                    }
                }
            }

            String key = buildRegionKey(world, group);
            PlayerRegion region = activeRegions.get(key);
            if (region == null) {
                region = new PlayerRegion(world, group);
            } else {
                region.updateFromAreas(world, group);
            }
            regions.add(region);
            nextActive.put(key, region);
        }

        return regions;
    }

    private static List<PlayerArea> collectPlayerAreas(ServerWorld world) {
        List<ServerPlayerEntity> players = world.getPlayers();
        if (players.isEmpty()) {
            return Collections.emptyList();
        }
        List<PlayerArea> areas = new ArrayList<>(players.size());
        int fallbackDistance = world.getServer().getPlayerManager().getViewDistance();
        for (ServerPlayerEntity player : players) {
            ChunkPos pos = player.getChunkPos();
            int viewDistance = player.getViewDistance();
            if (viewDistance <= 0) {
                viewDistance = fallbackDistance;
            }
            int minX = pos.x - viewDistance;
            int minZ = pos.z - viewDistance;
            int maxX = pos.x + viewDistance;
            int maxZ = pos.z + viewDistance;
            areas.add(new PlayerArea(player, minX, minZ, maxX, maxZ));
        }
        return areas;
    }

    private static String buildRegionKey(ServerWorld world, List<PlayerArea> group) {
        List<String> playerIds = group.stream()
                .map(area -> area.player().getUuid())
                .map(UUID::toString)
                .sorted()
                .toList();
        return world.getRegistryKey().getValue().toString() + ":" + String.join(",", playerIds);
    }

    public Collection<PlayerRegion> getPlayerRegions() {
        return regionsByWorld.values().stream()
                .flatMap(Collection::stream)
                .toList();
    }

    public List<PlayerRegion> getRegions(ServerWorld world) {
        List<PlayerRegion> regions = regionsByWorld.get(world);
        return regions == null ? List.of() : regions;
    }

    public PlayerRegion findRegion(ServerWorld world, int chunkX, int chunkZ) {
        List<PlayerRegion> regions = regionsByWorld.get(world);
        if (regions == null || regions.isEmpty()) {
            return null;
        }
        Cache<ChunkPos, PlayerRegion> cache = chunkRegionCaches.computeIfAbsent(world,
                key -> Caffeine.newBuilder().maximumSize(8192).build());
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        return cache.get(pos, key -> lookupRegion(world, regions, chunkX, chunkZ));
    }

    private static PlayerRegion lookupRegion(ServerWorld world, List<PlayerRegion> regions, int chunkX, int chunkZ) {
        String worldId = world.getRegistryKey().getValue().toString();
        for (PlayerRegion region : regions) {
            if (region.contains(worldId, chunkX, chunkZ)) {
                return region;
            }
        }
        return null;
    }
}
