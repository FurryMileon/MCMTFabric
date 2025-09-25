package net.himeki.mcmtfabric.commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.himeki.mcmtfabric.ParallelProcessor;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

import java.util.Collection;

import static net.minecraft.server.command.CommandManager.literal;

public final class RegionCommand {

    private RegionCommand() {
    }

    public static ArgumentBuilder<ServerCommandSource, ?> registerRegion(LiteralArgumentBuilder<ServerCommandSource> root) {
        return root.then(literal("here").executes(ctx -> {
            ServerPlayerEntity player = ctx.getSource().getPlayer();
            if (player == null) {
                ctx.getSource().sendError(Text.literal("This command can only be used by a player."));
                return 0;
            }

            ChunkPos pos = player.getChunkPos();
            ThreadedChunksRegion region = ParallelProcessor.getRegionForChunk(player.getServerWorld(), pos.x, pos.z);
            String message = String.format("Chunk region %s (%d,%d) in %s", region.getName(), pos.x, pos.z, region.getWorldId());
            ctx.getSource().sendFeedback(() -> Text.literal(message), false);
            return 1;
        })).then(literal("count").executes(ctx -> {
            Collection<ThreadedChunksRegion> regions = ParallelProcessor.getAllRegions();
            ctx.getSource().sendFeedback(() -> Text.literal("Cached regions: " + regions.size()), false);
            return 1;
        }));
    }
}
