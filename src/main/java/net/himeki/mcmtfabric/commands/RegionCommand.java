package net.himeki.mcmtfabric.commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.himeki.mcmtfabric.ParallelProcessor;
import net.himeki.mcmtfabric.parallelised.threads.ChunkRegion;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

import java.util.Locale;

import static net.minecraft.server.command.CommandManager.literal;

public class RegionCommand {

    public static ArgumentBuilder<ServerCommandSource, ?> registerRegion(LiteralArgumentBuilder<ServerCommandSource> root) {
        root.executes(ctx -> showCurrentRegion(ctx.getSource()));
        root.then(literal("Iam").executes(ctx -> showCurrentRegion(ctx.getSource())));
        root.then(literal("High").executes(ctx -> showHeaviestRegion(ctx.getSource())));
        return root;
    }

    private static int showCurrentRegion(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        ChunkRegion region = ParallelProcessor.getOrCreateRegion(player.getServerWorld(), pos.x, pos.z);
        sendRegionStats(source, region, "Current region");
        return 1;
    }

    private static int showHeaviestRegion(ServerCommandSource source) {
        ChunkRegion region = ParallelProcessor.getHeaviestRegion();
        if (region == null) {
            source.sendFeedback(() -> Text.literal("No recorded chunk regions yet."), false);
            return 0;
        }
        sendRegionStats(source, region, "Most loaded region");
        return 1;
    }

    private static void sendRegionStats(ServerCommandSource source, ChunkRegion region, String prefix) {
        ThreadedChunksRegion.RegionWorkDurations timings = region.snapshotWorkDurations();
        double chunkMs = nanosToMillis(timings.chunkWorkNanos());
        double entityMs = nanosToMillis(timings.entityWorkNanos());
        double blockMs = nanosToMillis(timings.blockWorkNanos());
        double totalWorkMs = chunkMs + entityMs + blockMs;
        double elapsedMs = nanosToMillis(timings.tickElapsedNanos());
        double tps = elapsedMs > 0.0 ? Math.min(1000.0 / elapsedMs, 20.0) : 20.0;

        String message = String.format(Locale.ROOT,
                "%s %s (%s @ chunk %d,%d) | work %.2fms (chunks %.2fms/%d, entities %.2fms/%d, block entities %.2fms/%d) | elapsed %.2fms | est TPS %.2f",
                prefix,
                region.getName(),
                region.getWorldId(),
                region.getChunkX(),
                region.getChunkZ(),
                totalWorkMs,
                chunkMs, timings.chunkTasks(),
                entityMs, timings.entityTasks(),
                blockMs, timings.blockEntityTasks(),
                elapsedMs,
                tps);
        source.sendFeedback(() -> Text.literal(message), false);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
