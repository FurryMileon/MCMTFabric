package net.himeki.mcmtfabric.commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.himeki.mcmtfabric.ParallelProcessor;
import net.himeki.mcmtfabric.parallelised.threads.PlayerRegion;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.literal;

public class RegionCommand {

    public static ArgumentBuilder<ServerCommandSource, ?> registerRegion(LiteralArgumentBuilder<ServerCommandSource> root) {
        return root.then(literal("region")
                .then(literal("list").executes(ctx -> listRegions(ctx.getSource()))));
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
}
