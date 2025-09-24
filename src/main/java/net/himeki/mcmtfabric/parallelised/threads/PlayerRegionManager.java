package net.himeki.mcmtfabric.parallelised.threads;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Maintains the dynamic mapping between players and threaded regions.
 */
public class PlayerRegionManager {

    private final Map<String, PlayerRegion> activeRegions = new HashMap<>();
    private final Map<ServerWorld, RegionCache> chunkRegionCaches = new HashMap<>();
    private final List<CompletableFuture<Void>> pendingShutdowns = new ArrayList<>();
    private volatile Map<ServerWorld, List<PlayerRegion>> regionsByWorld = Map.of();

    public synchronized void updateRegions(MinecraftServer server) {
        pendingShutdowns.removeIf(CompletableFuture::isDone);
        Map<String, PlayerRegion> nextActive = new HashMap<>();
        Map<ServerWorld, List<PlayerRegion>> nextRegionsByWorld = new HashMap<>();
        Set<ServerWorld> seenWorlds = new HashSet<>();
        Map<String, Map<UUID, PlayerRegion>> previousByWorld = buildPreviousPlayerIndex();
        Set<PlayerRegion> survivors = new HashSet<>();

        for (ServerWorld world : server.getWorlds()) {
            seenWorlds.add(world);
            List<PlayerArea> areas = collectPlayerAreas(world);
            if (areas.isEmpty()) {
                RegionCache cache = chunkRegionCaches.get(world);
                if (cache != null) {
                    cache.invalidateAll();
                }
                continue;
            }

            String worldId = world.getRegistryKey().getValue().toString();
            Map<UUID, PlayerRegion> previousByPlayer = previousByWorld.get(worldId);
            if (previousByPlayer == null) {
                previousByPlayer = new HashMap<>();
            }
            List<PlayerRegion> regions = buildRegionsForWorld(world, areas, nextActive, previousByPlayer, survivors);
            nextRegionsByWorld.put(world, List.copyOf(regions));
            chunkRegionCaches
                    .computeIfAbsent(world, key -> new RegionCache())
                    .invalidateAll();
        }

        // Shutdown regions that are no longer active
        for (PlayerRegion region : activeRegions.values()) {
            if (!survivors.contains(region)) {
                pendingShutdowns.add(region.shutdownExecutors());
            }
        }

        activeRegions.clear();
        activeRegions.putAll(nextActive);

        // Drop caches for worlds that disappeared
        chunkRegionCaches.keySet().removeIf(world -> !seenWorlds.contains(world));

        regionsByWorld = Map.copyOf(nextRegionsByWorld);
    }

    private Map<String, Map<UUID, PlayerRegion>> buildPreviousPlayerIndex() {
        Map<String, Map<UUID, PlayerRegion>> index = new HashMap<>();
        for (PlayerRegion region : activeRegions.values()) {
            String worldId = region.getWorldId();
            Map<UUID, PlayerRegion> mapping = index.computeIfAbsent(worldId, key -> new HashMap<>());
            for (UUID playerId : region.getPlayerIds()) {
                mapping.put(playerId, region);
            }
        }
        return index;
    }

    private List<PlayerRegion> buildRegionsForWorld(ServerWorld world, List<PlayerArea> areas,
                                                   Map<String, PlayerRegion> nextActive,
                                                   Map<UUID, PlayerRegion> previousByPlayer,
                                                   Set<PlayerRegion> survivors) {
        int size = areas.size();
        boolean[] visited = new boolean[size];
        List<PlayerRegion> regions = new ArrayList<>();
        Set<PlayerRegion> claimedRegions = new HashSet<>();

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
            PlayerRegion region = reuseRegion(world, group, previousByPlayer, claimedRegions);
            if (region == null) {
                region = new PlayerRegion(world, group);
            } else {
                region.updateFromAreas(world, group);
            }
            regions.add(region);
            nextActive.put(key, region);
            survivors.add(region);
        }

        return regions;
    }

    private static PlayerRegion reuseRegion(ServerWorld world, List<PlayerArea> group,
                                            Map<UUID, PlayerRegion> previousByPlayer,
                                            Set<PlayerRegion> claimedRegions) {
        PlayerRegion candidate = null;
        String worldId = world.getRegistryKey().getValue().toString();
        for (PlayerArea area : group) {
            PlayerRegion region = previousByPlayer.get(area.player().getUuid());
            if (region != null && region.getWorldId().equals(worldId) && claimedRegions.add(region)) {
                candidate = region;
                break;
            }
        }
        if (candidate == null) {
            // These players will be assigned a brand new region, ensure their previous mapping is cleared.
            for (PlayerArea area : group) {
                previousByPlayer.remove(area.player().getUuid());
            }
            return null;
        }
        // Remove every player that previously lived in this region so it cannot be reused by other groups.
        for (UUID playerId : candidate.getPlayerIds()) {
            previousByPlayer.remove(playerId);
        }
        return candidate;
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
        RegionCache cache = chunkRegionCaches.computeIfAbsent(world, key -> new RegionCache());
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        return cache.get(pos, key -> lookupRegion(world, regions, chunkX, chunkZ));
    }

    public synchronized void shutdownAll() {
        List<CompletableFuture<Void>> toAwait = new ArrayList<>(activeRegions.size() + pendingShutdowns.size());
        for (PlayerRegion region : activeRegions.values()) {
            toAwait.add(region.shutdownExecutors());
        }
        toAwait.addAll(pendingShutdowns);
        pendingShutdowns.clear();
        activeRegions.clear();
        chunkRegionCaches.clear();
        regionsByWorld = Map.of();
        for (CompletableFuture<Void> future : toAwait) {
            future.join();
        }
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

    private static final class RegionCache {
        private static final int MAX_SIZE = 8192;
        private final LinkedHashMap<ChunkPos, PlayerRegion> entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkPos, PlayerRegion> eldest) {
                return size() > MAX_SIZE;
            }
        };

        public synchronized PlayerRegion get(ChunkPos key, Function<ChunkPos, PlayerRegion> loader) {
            PlayerRegion region = entries.get(key);
            if (region == null) {
                region = loader.apply(key);
                if (region != null) {
                    entries.put(key, region);
                }
            }
            return region;
        }

        public synchronized void invalidateAll() {
            entries.clear();
        }
    }
}
