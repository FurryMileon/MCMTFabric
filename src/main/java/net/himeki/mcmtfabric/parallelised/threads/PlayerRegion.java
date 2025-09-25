package net.himeki.mcmtfabric.parallelised.threads;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dynamic region backed by the chunk areas loaded by one or more players.
 */
public class PlayerRegion extends ThreadedChunksRegion {

    private final Set<UUID> playerIds = new LinkedHashSet<>();
    private final List<String> playerNames = new ArrayList<>();

    public PlayerRegion(ServerWorld world, List<PlayerArea> areas) {
        super(buildName(areas), world.getRegistryKey().getValue().toString(),
                0, 0, 0, 0, "player");
        updateFromAreas(world, areas);
    }

    public void updateFromAreas(ServerWorld world, List<PlayerArea> areas) {
        playerIds.clear();
        playerNames.clear();
        for (PlayerArea area : areas) {
            ServerPlayerEntity player = area.player();
            playerIds.add(player.getUuid());
            playerNames.add(player.getGameProfile().getName());
        }
        Collections.sort(playerNames);

        Bounds bounds = Bounds.fromAreas(areas);
        updateBounds(bounds.minChunkX(), bounds.minChunkZ(), bounds.maxChunkX(), bounds.maxChunkZ());
        setName(buildDisplayName());
        this.worldId = world.getRegistryKey().getValue().toString();
        setSource("player");
    }

    public Set<UUID> getPlayerIds() {
        return Collections.unmodifiableSet(playerIds);
    }

    public List<String> getPlayerNames() {
        return Collections.unmodifiableList(playerNames);
    }

    private String buildDisplayName() {
        if (playerNames.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder(playerNames.get(0));
        for (int i = 1; i < playerNames.size(); i++) {
            builder.append("_and_").append(playerNames.get(i));
        }
        return builder.toString();
    }

    private static String buildName(List<PlayerArea> areas) {
        List<String> names = areas.stream()
                .map(area -> area.player().getGameProfile().getName())
                .sorted()
                .toList();
        if (names.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder(names.get(0));
        for (int i = 1; i < names.size(); i++) {
            builder.append("_and_").append(names.get(i));
        }
        return builder.toString();
    }

    private record Bounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        private static Bounds fromAreas(List<PlayerArea> areas) {
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (PlayerArea area : areas) {
                minX = Math.min(minX, area.minChunkX());
                minZ = Math.min(minZ, area.minChunkZ());
                maxX = Math.max(maxX, area.maxChunkX());
                maxZ = Math.max(maxZ, area.maxChunkZ());
            }
            if (minX == Integer.MAX_VALUE) {
                minX = minZ = maxX = maxZ = 0;
            }
            return new Bounds(minX, minZ, maxX, maxZ);
        }
    }
}
