package net.himeki.mcmtfabric.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.shedaniel.autoconfig.AutoConfig;
import net.himeki.mcmtfabric.ParallelProcessor;
import net.himeki.mcmtfabric.config.ThreadedRangesConfig;
import net.himeki.mcmtfabric.parallelised.ThreadedChunksRange;
import net.minecraft.command.argument.RegistryKeyArgumentType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class RangeCommand {
    private static final DynamicCommandExceptionType INVALID_WORLD_EXCEPTION = new DynamicCommandExceptionType(
            (id) -> Text.literal("Invalid world: " + id));

    public static LiteralArgumentBuilder<ServerCommandSource> registerRange(LiteralArgumentBuilder<ServerCommandSource> root) {
        return root.requires(cmdSrc -> cmdSrc.hasPermissionLevel(0))
                .then(literal("add")
                        .then(literal("radius")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .suggests((context, builder) -> suggestChunkCoordinate(context.getSource(), builder, true))
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .suggests((context, builder) -> suggestChunkCoordinate(context.getSource(), builder, false))
                                                .then(argument("radius", IntegerArgumentType.integer(1))
                                                        .then(literal("in")
                                                                .then(argument("world", RegistryKeyArgumentType.registryKey(RegistryKeys.WORLD))
                                                                        .executes(cmdCtx -> executeRadiusAdd(cmdCtx, null))
                                                                        .then(literal("named")
                                                                                .then(argument("name", StringArgumentType.word())
                                                                                        .executes(cmdCtx -> executeRadiusAdd(cmdCtx,
                                                                                                StringArgumentType.getString(cmdCtx, "name")))))))))))
                        .then(literal("chunks")
                                .then(literal("from")
                                        .then(argument("x1", IntegerArgumentType.integer())
                                                .suggests((context, builder) -> suggestChunkCoordinate(context.getSource(), builder, true))
                                                .then(argument("z1", IntegerArgumentType.integer())
                                                        .suggests((context, builder) -> suggestChunkCoordinate(context.getSource(), builder, false))
                                                        .then(literal("to")
                                                                .then(argument("x2", IntegerArgumentType.integer())
                                                                        .suggests((context, builder) -> suggestChunkCoordinate(context.getSource(), builder, true))
                                                                        .then(argument("z2", IntegerArgumentType.integer())
                                                                                .suggests((context, builder) -> suggestChunkCoordinate(context.getSource(), builder, false))
                                                                                .then(literal("in")
                                                                                        .then(argument("world", RegistryKeyArgumentType.registryKey(RegistryKeys.WORLD))
                                                                                                .executes(cmdCtx -> executeExplicitAdd(cmdCtx, null))
                                                                                                .then(literal("named")
                                                                                                        .then(argument("name", StringArgumentType.word())
                                                                                                                .executes(cmdCtx -> executeExplicitAdd(cmdCtx,
                                                                                                                        StringArgumentType.getString(cmdCtx, "name")))))))))))))))
                .then(literal("set")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> suggestRangeNames(context.getSource(), builder))
                                .then(literal("chunkTick")
                                        .then(argument("enabled", BoolArgumentType.bool())
                                                .executes(cmdCtx -> executeSetTick(cmdCtx, "chunkTick"))))
                                .then(literal("entityTick")
                                        .then(argument("enabled", BoolArgumentType.bool())
                                                .executes(cmdCtx -> executeSetTick(cmdCtx, "entityTick"))))
                                .then(literal("blockEntityTick")
                                        .then(argument("enabled", BoolArgumentType.bool())
                                                .executes(cmdCtx -> executeSetTick(cmdCtx, "blockEntityTick"))))))
                .then(literal("remove")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> suggestRangeNames(context.getSource(), builder))
                                .executes(RangeCommand::executeRemove)))
                .then(literal("list")
                        .executes(RangeCommand::executeListCompact))
                .then(literal("show")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> suggestRangeNames(context.getSource(), builder))
                                .executes(RangeCommand::executeShow)));
    }

    /**
     * Checks if a range with the same coordinates already exists
     */
    private static boolean isRangeOverlapping(ThreadedChunksRange newRange, ThreadedRangesConfig config) {
        if (config.threadedRanges == null) return false;

        for (ThreadedChunksRange existingRange : config.threadedRanges) {
            if (existingRange.getWorldId().equals(newRange.getWorldId()) &&
                    existingRange.getX1() == newRange.getX1() &&
                    existingRange.getZ1() == newRange.getZ1() &&
                    existingRange.getX2() == newRange.getX2() &&
                    existingRange.getZ2() == newRange.getZ2()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a range with the same name already exists
     */
    private static boolean doesRangeNameExist(String name, ThreadedRangesConfig config) {
        if (config.threadedRanges == null) return false;

        return config.threadedRanges.stream()
                .anyMatch(range -> range.getName().equals(name));
    }

    private static void saveRangeToConfig(ThreadedChunksRange range) {
        ThreadedRangesConfig rangesConfig = AutoConfig.getConfigHolder(ThreadedRangesConfig.class).getConfig();

        // Initialize the list if null
        if (rangesConfig.threadedRanges == null) {
            rangesConfig.threadedRanges = new ArrayList<>();
        }

        // Check for duplicates
        if (doesRangeNameExist(range.getName(), rangesConfig)) {
            throw new IllegalStateException("A range with the name '" + range.getName() + "' already exists");
        }

        if (isRangeOverlapping(range, rangesConfig)) {
            throw new IllegalStateException(
                    String.format("A range with the same coordinates (%d,%d) to (%d,%d) already exists",
                            range.getX1(), range.getZ1(), range.getX2(), range.getZ2())
            );
        }

        // Add the new range
        rangesConfig.threadedRanges.add(range);

        // Save the config
        AutoConfig.getConfigHolder(ThreadedRangesConfig.class).save();
    }

    private static int executeRemove(CommandContext<ServerCommandSource> cmdCtx) {
        String name = StringArgumentType.getString(cmdCtx, "name");
        ThreadedRangesConfig rangesConfig = AutoConfig.getConfigHolder(ThreadedRangesConfig.class).getConfig();

        // Check if range exists
        ThreadedChunksRange targetRange = null;
        for (ThreadedChunksRange range : ParallelProcessor.threadedChunksRanges) {
            if (range.getName().equals(name)) {
                targetRange = range;
                break;
            }
        }

        if (targetRange == null) {
            cmdCtx.getSource().sendError(Text.literal("No range found with name: " + name));
            return 0;
        }

        // Remove from runtime list first
        ParallelProcessor.removeThreadedChunksRangeByName(name);

        // Then remove from config
        if (targetRange.getSource().equals("config"))
            removeRangeFromConfig(name);

        String message = String.format("Removed threaded range '%s'", name);
        cmdCtx.getSource().sendFeedback(() -> Text.literal(message), true);
        return 1;
    }

    private static void removeRangeFromConfig(String name) {
        ThreadedRangesConfig rangesConfig = AutoConfig.getConfigHolder(ThreadedRangesConfig.class).getConfig();

        if (rangesConfig.threadedRanges != null) {
            rangesConfig.threadedRanges.removeIf(r -> r.getName().equals(name));
            AutoConfig.getConfigHolder(ThreadedRangesConfig.class).save();
        }
    }

    private static int executeRadiusAdd(CommandContext<ServerCommandSource> cmdCtx, String providedName) throws CommandSyntaxException {
        int centerX = IntegerArgumentType.getInteger(cmdCtx, "x");
        int centerZ = IntegerArgumentType.getInteger(cmdCtx, "z");
        int radius = IntegerArgumentType.getInteger(cmdCtx, "radius");

        RegistryKey<World> worldKey = RegistryKeyArgumentType.getKey(cmdCtx, "world", RegistryKeys.WORLD, INVALID_WORLD_EXCEPTION);
        String worldId = worldKey.getValue().toString();

        int x1 = centerX - (radius - 1);
        int z1 = centerZ - (radius - 1);
        int x2 = centerX + (radius - 1);
        int z2 = centerZ + (radius - 1);

        String name = providedName != null ? providedName :
                String.format("chunk_%s_%d_%d_to_%d_%d", worldKey.getValue().getPath(), x1, z1, x2, z2);

        ThreadedChunksRange range = new ThreadedChunksRange(name, worldId, x1, z1, x2, z2);
        range.setMultiThreadChunkTick(false);
        range.setMultiThreadEntityTick(false);
        range.setMultiThreadBlockEntityTick(false);

        try {
            saveRangeToConfig(range);
            ParallelProcessor.addThreadedChunksRange(range);

            String message = String.format("Added new threaded range '%s' with radius %d around chunk (%d, %d) in world %s",
                    name, radius, centerX, centerZ, worldKey.getValue());
            cmdCtx.getSource().sendFeedback(() -> Text.literal(message), true);
            return 1;
        } catch (IllegalStateException e) {
            cmdCtx.getSource().sendError(Text.literal(e.getMessage()));
            return 0;
        }
    }

    private static int executeExplicitAdd(CommandContext<ServerCommandSource> cmdCtx, String providedName) throws CommandSyntaxException {
        int x1 = IntegerArgumentType.getInteger(cmdCtx, "x1");
        int z1 = IntegerArgumentType.getInteger(cmdCtx, "z1");
        int x2 = IntegerArgumentType.getInteger(cmdCtx, "x2");
        int z2 = IntegerArgumentType.getInteger(cmdCtx, "z2");

        RegistryKey<World> worldKey = RegistryKeyArgumentType.getKey(cmdCtx, "world", RegistryKeys.WORLD, INVALID_WORLD_EXCEPTION);
        String worldId = worldKey.getValue().toString();

        String name = providedName != null ? providedName :
                String.format("chunk_%s_%d_%d_to_%d_%d", worldKey.getValue().getPath(), x1, z1, x2, z2);

        ThreadedChunksRange range = new ThreadedChunksRange(name, worldId, x1, z1, x2, z2);
        range.setMultiThreadChunkTick(false);
        range.setMultiThreadEntityTick(false);
        range.setMultiThreadBlockEntityTick(false);

        try {
            saveRangeToConfig(range);
            ParallelProcessor.addThreadedChunksRange(range);

            String message = String.format("Added new threaded range '%s' from (%d, %d) to (%d, %d) in world %s",
                    name, x1, z1, x2, z2, worldKey.getValue());
            cmdCtx.getSource().sendFeedback(() -> Text.literal(message), true);
            return 1;
        } catch (IllegalStateException e) {
            cmdCtx.getSource().sendError(Text.literal(e.getMessage()));
            return 0;
        }
    }

    private static int executeSetTick(CommandContext<ServerCommandSource> cmdCtx, String tickType) throws CommandSyntaxException {
        String name = StringArgumentType.getString(cmdCtx, "name");
        boolean enabled = BoolArgumentType.getBool(cmdCtx, "enabled");

        ThreadedRangesConfig rangesConfig = AutoConfig.getConfigHolder(ThreadedRangesConfig.class).getConfig();
        ThreadedChunksRange targetRange = null;

        for (ThreadedChunksRange range : rangesConfig.threadedRanges) {
            if (range.getName().equals(name)) {
                targetRange = range;
                break;
            }
        }

        if (targetRange == null) {
            cmdCtx.getSource().sendError(Text.literal("No range found with name: " + name));
            return 0;
        }

        switch (tickType) {
            case "chunkTick":
                targetRange.setMultiThreadChunkTick(enabled);
                break;
            case "entityTick":
                targetRange.setMultiThreadEntityTick(enabled);
                break;
            case "blockEntityTick":
                targetRange.setMultiThreadBlockEntityTick(enabled);
                break;
        }

        // Save the updated config
        AutoConfig.getConfigHolder(ThreadedRangesConfig.class).save();

        String message = String.format("Set %s to %s for range '%s'", tickType, enabled, name);
        cmdCtx.getSource().sendFeedback(() -> Text.literal(message), true);
        return 1;
    }

    private static int executeListCompact(CommandContext<ServerCommandSource> cmdCtx) {
        ThreadedRangesConfig rangesConfig = AutoConfig.getConfigHolder(ThreadedRangesConfig.class).getConfig();

        if (rangesConfig.threadedRanges == null || rangesConfig.threadedRanges.isEmpty()) {
            cmdCtx.getSource().sendFeedback(() -> Text.literal("No threaded ranges configured."), false);
            return 0;
        }

        cmdCtx.getSource().sendFeedback(() -> Text.literal("=== Ranges | C: ChunkTick, E: EntityTick, B: BlockEntityTick ==="), false);

        for (ThreadedChunksRange range : rangesConfig.threadedRanges) {
            // Extract world path from full ID (e.g., "minecraft:overworld" -> "overworld")
            String worldPath = range.getWorldId().substring(range.getWorldId().lastIndexOf(':') + 1);

            String rangeInfo = String.format(
                    "§6%s§r %s (%d,%d)->(%d,%d) [%s %s %s]",
                    range.getName(),
                    worldPath,
                    range.getX1(), range.getZ1(),
                    range.getX2(), range.getZ2(),
                    formatEnabledCompact(range.isMultiThreadChunkTick(), 'C'),
                    formatEnabledCompact(range.isMultiThreadEntityTick(), 'E'),
                    formatEnabledCompact(range.isMultiThreadBlockEntityTick(), 'B')
            );

            cmdCtx.getSource().sendFeedback(() -> Text.literal(rangeInfo), false);
        }

        return 1;
    }

    private static int executeList(CommandContext<ServerCommandSource> cmdCtx) {
        ThreadedRangesConfig rangesConfig = AutoConfig.getConfigHolder(ThreadedRangesConfig.class).getConfig();

        if (rangesConfig.threadedRanges == null || rangesConfig.threadedRanges.isEmpty()) {
            cmdCtx.getSource().sendFeedback(() -> Text.literal("No threaded ranges configured."), false);
            return 0;
        }

        cmdCtx.getSource().sendFeedback(() -> Text.literal("=== Configured Ranges ==="), false);

        for (ThreadedChunksRange range : rangesConfig.threadedRanges) {
            String rangeInfo = String.format(
                    "§6%s§r: %s (%d, %d) to (%d, %d)\n" +
                            "   §7Chunk tick: %s, Entity tick: %s, Block Entity tick: %s§r",
                    range.getName(),
                    range.getWorldId(),
                    range.getX1(), range.getZ1(),
                    range.getX2(), range.getZ2(),
                    formatEnabled(range.isMultiThreadChunkTick()),
                    formatEnabled(range.isMultiThreadEntityTick()),
                    formatEnabled(range.isMultiThreadBlockEntityTick())
            );

            cmdCtx.getSource().sendFeedback(() -> Text.literal(rangeInfo), false);
        }

        return 1;
    }

    private static int executeShow(CommandContext<ServerCommandSource> cmdCtx) {
        String name = StringArgumentType.getString(cmdCtx, "name");
        ThreadedRangesConfig rangesConfig = AutoConfig.getConfigHolder(ThreadedRangesConfig.class).getConfig();

        ThreadedChunksRange targetRange = null;
        if (rangesConfig.threadedRanges != null) {
            for (ThreadedChunksRange range : rangesConfig.threadedRanges) {
                if (range.getName().equals(name)) {
                    targetRange = range;
                    break;
                }
            }
        }

        if (targetRange == null) {
            cmdCtx.getSource().sendError(Text.literal("No range found with name: " + name));
            return 0;
        }

        int width = Math.abs(targetRange.getX2() - targetRange.getX1()) + 1;
        int length = Math.abs(targetRange.getZ2() - targetRange.getZ1()) + 1;

        // Calculate center coordinates
        int centerX = (targetRange.getX1() + targetRange.getX2()) / 2;
        int centerZ = (targetRange.getZ1() + targetRange.getZ2()) / 2;

        // Calculate radius (if it's a square range)
        int radiusX = (targetRange.getX2() - targetRange.getX1()) / 2;
        int radiusZ = (targetRange.getZ2() - targetRange.getZ1()) / 2;
        boolean isSquare = radiusX == radiusZ;

        String[] details = {
                String.format("§6=== Range Details: %s ===§r", targetRange.getName()),
                String.format("World: %s", targetRange.getWorldId()),
                String.format("Coordinates: (%d, %d) to (%d, %d)",
                        targetRange.getX1(), targetRange.getZ1(),
                        targetRange.getX2(), targetRange.getZ2()),
                String.format("Center: (%d, %d)%s",
                        centerX, centerZ,
                        isSquare ? String.format(" (radius: %d chunks)", radiusX + 1) : ""),
                String.format("Dimensions: %dx%d chunks (%d total)", width, length, width * length),
                "Settings:",
                String.format("  - Chunk tick: %s", formatEnabled(targetRange.isMultiThreadChunkTick())),
                String.format("  - Entity tick: %s", formatEnabled(targetRange.isMultiThreadEntityTick())),
                String.format("  - Block Entity tick: %s", formatEnabled(targetRange.isMultiThreadBlockEntityTick()))
        };

        for (String detail : details) {
            cmdCtx.getSource().sendFeedback(() -> Text.literal(detail), false);
        }

        return 1;
    }

    private static String formatEnabled(boolean enabled) {
        return enabled ? "§aenabled§r" : "§cdisabled§r";
    }

    private static String formatEnabledCompact(boolean enabled, char letter) {
        return enabled ? "§a" + letter + "§r" : "§c" + letter + "§r";
    }

    private static CompletableFuture<Suggestions> suggestChunkCoordinate(ServerCommandSource source, SuggestionsBuilder builder, boolean isX) {
        try {
            if (source.getPlayer() != null) {
                int chunkCoord = isX ?
                        source.getPlayer().getChunkPos().x :
                        source.getPlayer().getChunkPos().z;

                // First suggest current position
                builder.suggest(chunkCoord, Text.literal(String.format("current %s", isX ? "X" : "Z")));
            }
        } catch (Exception e) {
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestRangeNames(ServerCommandSource source, SuggestionsBuilder builder) {
        ThreadedRangesConfig rangesConfig = AutoConfig.getConfigHolder(ThreadedRangesConfig.class).getConfig();
        for (ThreadedChunksRange range : rangesConfig.threadedRanges) {
            builder.suggest(range.getName());
        }
        return builder.buildFuture();
    }
}
