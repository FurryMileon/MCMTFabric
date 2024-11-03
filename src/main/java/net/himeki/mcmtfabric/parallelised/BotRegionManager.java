package net.himeki.mcmtfabric.parallelised;

import net.himeki.mcmtfabric.ParallelProcessor;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class BotRegionManager {
    private static final Map<String, ThreadedChunksRegion> botRegions = new ConcurrentHashMap<>();
    private static final Map<String, ChunkPos> lastKnownPositions = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();

    private static class BotMetadata {
        String botId;
        long creationTime;
        int updateCount;

        BotMetadata(String botId) {
            this.botId = botId;
            this.creationTime = System.currentTimeMillis();
            this.updateCount = 0;
        }

        String toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("type", "bot_region");
            json.addProperty("botId", botId);
            json.addProperty("creationTime", creationTime);
            json.addProperty("updateCount", updateCount);
            return GSON.toJson(json);
        }
    }

    private static final Map<String, BotMetadata> botMetadata = new ConcurrentHashMap<>();

    public static void checkAndManageBot(ServerPlayerEntity player) {
        if (!isBotPlayer(player)) {
            return;
        }

        String botId = player.getName().getString();
        ChunkPos currentPos = player.getChunkPos();
        World world = player.getWorld();
        String worldId = world.getRegistryKey().getValue().toString();
        int viewDistance = player.getViewDistance();

        // Create region if it doesn't exist
        if (!botRegions.containsKey(botId)) {
            createInitialRegion(botId, currentPos, worldId, viewDistance);
            lastKnownPositions.put(botId, currentPos);
            return;
        }

        // Update region if position changed
        ChunkPos lastPos = lastKnownPositions.get(botId);
        if (!currentPos.equals(lastPos)) {
            updateRegion(botId, currentPos, worldId, viewDistance);
            lastKnownPositions.put(botId, currentPos);
        }
    }

    private static boolean isBotPlayer(ServerPlayerEntity player) {
        return player.getName().getString().startsWith("bot_");
    }

    private static void createInitialRegion(String botId, ChunkPos pos, String worldId, int viewDistance) {
        int minX = pos.x - viewDistance;
        int minZ = pos.z - viewDistance;
        int maxX = pos.x + viewDistance;
        int maxZ = pos.z + viewDistance;

        // Create metadata
        BotMetadata metadata = new BotMetadata(botId);
        botMetadata.put(botId, metadata);

        ThreadedChunksRegion region = new ThreadedChunksRegion(
                "bot_region_" + botId,
                worldId,
                minX, minZ,
                maxX, maxZ,
                metadata.toJson() // Store metadata in source field
        );

        // Enable multi-threading for all operations by default for bot regions
        region.setMultiThreadChunkTick(true);
        region.setMultiThreadEntityTick(true);
        region.setMultiThreadBlockEntityTick(true);

        botRegions.put(botId, region);
        ParallelProcessor.addThreadedChunksRegion(region);
    }

    private static void updateRegion(String botId, ChunkPos newPos, String worldId, int viewDistance) {
        ThreadedChunksRegion currentRegion = botRegions.get(botId);
        BotMetadata metadata = botMetadata.get(botId);
        metadata.updateCount++;

        // Calculate new boundaries centered on new position
        int minX = newPos.x - viewDistance;
        int minZ = newPos.z - viewDistance;
        int maxX = newPos.x + viewDistance;
        int maxZ = newPos.z + viewDistance;

        // Create new region with updated boundaries
        ThreadedChunksRegion newRegion = new ThreadedChunksRegion(
                "bot_region_" + botId,
                worldId,
                minX, minZ,
                maxX, maxZ,
                metadata.toJson()
        );

        // Copy multi-threading settings from old region
        newRegion.setMultiThreadChunkTick(currentRegion.isMultiThreadChunkTick());
        newRegion.setMultiThreadEntityTick(currentRegion.isMultiThreadEntityTick());
        newRegion.setMultiThreadBlockEntityTick(currentRegion.isMultiThreadBlockEntityTick());

        // Remove old region and add new one
        ParallelProcessor.removeThreadedChunksRegion(currentRegion);
        botRegions.put(botId, newRegion);
        ParallelProcessor.addThreadedChunksRegion(newRegion);
    }

    public static void cleanup(String botId) {
        ThreadedChunksRegion region = botRegions.remove(botId);
        if (region != null) {
            ParallelProcessor.removeThreadedChunksRegion(region);
        }
        lastKnownPositions.remove(botId);
        botMetadata.remove(botId);
    }
}