package net.himeki.mcmtfabric.commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.himeki.mcmtfabric.ParallelProcessor;
import net.himeki.mcmtfabric.parallelised.threads.PlayerRegion;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.literal;

public class RegionCommand {

    public static ArgumentBuilder<ServerCommandSource, ?> registerRegion(LiteralArgumentBuilder<ServerCommandSource> root) {
        root.executes(ctx -> listRegions(ctx.getSource()));
        root.then(literal("list").executes(ctx -> listRegions(ctx.getSource())));
        root.then(literal("stats").executes(ctx -> showRegionStats(ctx.getSource())));
        return root;
    }

    private static int listRegions(ServerCommandSource source) {
        Collection<PlayerRegion> regions = ParallelProcessor.getPlayerRegions();
        if (regions.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No active player regions."), false);
            return 0;
        }

        for (PlayerRegion region : regions) {
            String bounds = String.format("[%d,%d] -> [%d,%d]", region.getX1(), region.getZ1(), region.getX2(), region.getZ2());
            List<String> players = region.getPlayerNames();
            String playersText = players.isEmpty() ? "<no players>"
                    : players.stream().collect(Collectors.joining(", "));
            String message = String.format("%s in %s %s | Players: %s", region.getName(),
                    region.getWorldId(), bounds, playersText);
            source.sendFeedback(() -> Text.literal(message), false);
        }

        return regions.size();
    }

    private static int showRegionStats(ServerCommandSource source) {
        Collection<PlayerRegion> regions = ParallelProcessor.getPlayerRegions();
        if (regions.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No active player regions."), false);
            return 0;
        }

        for (PlayerRegion region : regions) {
            ThreadedChunksRegion.RegionWorkDurations timings = region.snapshotWorkDurations();
            double chunkMs = nanosToMillis(timings.chunkWorkNanos());
            double entityMs = nanosToMillis(timings.entityWorkNanos());
            double blockMs = nanosToMillis(timings.blockWorkNanos());
            double totalWorkMs = chunkMs + entityMs + blockMs;
            double elapsedMs = nanosToMillis(timings.tickElapsedNanos());
            double tps = elapsedMs > 0.0 ? Math.min(1000.0 / elapsedMs, 20.0) : 20.0;

            String message = String.format(Locale.ROOT,
                    "%s | work %.2fms (chunks %.2fms/%d, entities %.2fms/%d, block entities %.2fms/%d) | elapsed %.2fms | est TPS %.2f",
                    region.getName(),
                    totalWorkMs,
                    chunkMs, timings.chunkTasks(),
                    entityMs, timings.entityTasks(),
                    blockMs, timings.blockEntityTasks(),
                    elapsedMs,
                    tps);
            source.sendFeedback(() -> Text.literal(message), false);
        }

        return regions.size();
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
