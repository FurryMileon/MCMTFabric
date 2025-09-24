package net.himeki.mcmtfabric;

import net.himeki.mcmtfabric.config.GeneralConfig;
import net.himeki.mcmtfabric.debug.WorldTickStats;
import net.himeki.mcmtfabric.parallelised.threads.PlayerRegion;
import net.himeki.mcmtfabric.parallelised.threads.PlayerRegionManager;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.himeki.mcmtfabric.serdes.pools.PostExecutePool;
import net.minecraft.block.entity.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ParallelProcessor {

    private static final Logger LOGGER = LogManager.getLogger();

    static Phaser worldPhaser;

    static ExecutorService worldPool;
    static ExecutorService asyncExecutor;
    static MinecraftServer mcs;
    static AtomicBoolean isTicking = new AtomicBoolean();

    private static final ConcurrentHashMap<ServerWorld, List<Runnable>> delayedChunkTasks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ServerWorld, List<Runnable>> delayedEntityTasks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ServerWorld, List<Runnable>> delayedBlockEntityTasks = new ConcurrentHashMap<>();

    static Map<String, Set<Thread>> mcThreadTracker = new ConcurrentHashMap<String, Set<Thread>>();

    private static final PlayerRegionManager PLAYER_REGION_MANAGER = new PlayerRegionManager();

    public static final ConcurrentHashMap<ServerWorld, WorldTickStats> worldTickStats = new ConcurrentHashMap<>();
    private static ThreadedChunksRegion findMatchingRegion(int chunkX, int chunkZ, ServerWorld world) {
        return PLAYER_REGION_MANAGER.findRegion(world, chunkX, chunkZ);
    }

    public static void setupThreadPool(int parallelism) {
        worldPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("MCMT-World-", 0).factory());
        asyncExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("MCMT-Async-", 0).factory());
    }

    // Statistics
    public static AtomicInteger currentWorlds = new AtomicInteger();
    public static AtomicInteger currentEnts = new AtomicInteger();
    public static AtomicInteger currentTEs = new AtomicInteger();
    public static AtomicInteger currentEnvs = new AtomicInteger();

    //Operation logging
    public static Set<String> currentTasks = ConcurrentHashMap.newKeySet();

    public static void regThread(String poolName, Thread thread) {
        mcThreadTracker.computeIfAbsent(poolName, s -> ConcurrentHashMap.newKeySet()).add(thread);
    }

    public static boolean isThreadPooled(String poolName, Thread t) {
        return mcThreadTracker.containsKey(poolName) && mcThreadTracker.get(poolName).contains(t);
    }

    public static boolean serverExecutionThreadPatch(MinecraftServer ms) {
        return isThreadPooled("MCMT-World", Thread.currentThread()) || isThreadPooled("MCMT-Tick", Thread.currentThread());
    }

    static long tickStart = 0;
    static GeneralConfig config;

    public static void preTick(int size, MinecraftServer server) {
        config = MCMT.config;
        PLAYER_REGION_MANAGER.updateRegions(server);
        if (!config.disabled && !config.disableWorld) {
            if (worldPhaser != null) {
                LOGGER.warn("Multiple servers?");
                return;
            } else {
                tickStart = System.nanoTime();
                isTicking.set(true);
                worldPhaser = new Phaser(size + 1);
                mcs = server;
            }
        }
    }


    public static void callTick(ServerWorld serverworld, BooleanSupplier hasTimeLeft, MinecraftServer server) {
        if (config.disabled || config.disableWorld) {
            try {
                serverworld.tick(hasTimeLeft);
            } catch (Exception e) {
                throw e;
            }
            return;
        }
        if (mcs != server) {
            LOGGER.warn("Multiple servers?");
            config.disabled = true;
            serverworld.tick(hasTimeLeft);
            return;
        } else {
            String taskName = null;
            if (config.opsTracing) {
                taskName = "WorldTick: " + serverworld.toString() + "@" + serverworld.hashCode();
                currentTasks.add(taskName);
            }
            String finalTaskName = taskName;
            worldPool.execute(() -> {
                try {
                    currentWorlds.incrementAndGet();
                    serverworld.tick(hasTimeLeft);
                } finally {
                    worldPhaser.arriveAndDeregister();
                    currentWorlds.decrementAndGet();
                    if (config.opsTracing) currentTasks.remove(finalTaskName);
                }
            });
        }
    }

    public static void postTick(MinecraftServer server) {
        if (!config.disabled && !config.disableWorld) {
            if (mcs != server) {
                LOGGER.warn("Multiple servers?");
                return;
            } else {
                worldPhaser.arriveAndAwaitAdvance();
                isTicking.set(false);
                worldPhaser = null;
                // PostExecute logic
                Deque<Runnable> queue = PostExecutePool.POOL.getQueue();
                Iterator<Runnable> qi = queue.iterator();
                while (qi.hasNext()) {
                    Runnable r = qi.next();
                    r.run();
                    qi.remove();
                }
            }
        }

        for (ThreadedChunksRegion region : PLAYER_REGION_MANAGER.getPlayerRegions()) {
            region.swapExecutionTimeBuffers();
        }

        synchronized (worldTickStats) {
            for (WorldTickStats stats : worldTickStats.values()) {
                stats.swapExecutionTimeBuffers();
            }
        }
    }


    public static void preChunkTick(ServerWorld world) {
        if (config.disabled || config.disableEnvironment) {
            return;
        }
        for (ThreadedChunksRegion region : PLAYER_REGION_MANAGER.getRegions(world)) {
            // Placeholder for potential phaser registration if needed in future
        }
    }


    public static void callTickChunks(ServerWorld world, WorldChunk chunk, int k) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        ThreadedChunksRegion matchingRegion = findMatchingRegion(chunkX, chunkZ, world);
        if (matchingRegion == null) {
            // No matching region, delay processing
            delayedChunkTasks.computeIfAbsent(world, w -> new ArrayList<>())
                    .add(() -> world.tickChunk(chunk, k));
            return;
        }

        Executor executor = matchingRegion.getChunkTickExecutor();

        String taskName;
        if (config.opsTracing) {
            taskName = "ChunkTick: " + chunk + "@" + chunk.hashCode();
            matchingRegion.currentTasks.add(taskName);
        } else {
            taskName = "";
        }


        matchingRegion.getChunkTickPhaser().register();

        executor.execute(() -> {
            try {
                matchingRegion.recordChunkStageStart();

                long startTime = System.nanoTime(); // Start timing
                world.tickChunk(chunk, k);
                long endTime = System.nanoTime(); // End timing
                long duration = endTime - startTime;
                matchingRegion.addChunkTickTime(duration); // Store execution time
            } finally {
                matchingRegion.getChunkTickPhaser().arrive();
                if (config.opsTracing) {
                    if (matchingRegion.currentTasks.stream().anyMatch(task -> (task.contains("Entity"))))
                        LOGGER.debug("Mixed tasks detected");
                    matchingRegion.currentTasks.remove(taskName);
                }
            }
        });
    }

    public static void postChunkTick(ServerWorld world) {
        for (ThreadedChunksRegion region : PLAYER_REGION_MANAGER.getRegions(world)) {
            asyncExecutor.execute(region::postChunkTick);
        }

        // Process delayed chunk tasks
        List<Runnable> tasks = delayedChunkTasks.remove(world);
        if (tasks != null) {
            WorldTickStats stats = worldTickStats.computeIfAbsent(world, w -> new WorldTickStats());
            for (Runnable task : tasks) {
                long startTime = System.nanoTime();
                task.run();
                long endTime = System.nanoTime();
                stats.chunkTickTimesCurrent.add(endTime - startTime);
            }
        }
    }


    public static void preEntityTick(ServerWorld world) {
        if (config.disabled || config.disableEntity) {
            return;
        }
        for (ThreadedChunksRegion region : PLAYER_REGION_MANAGER.getRegions(world)) {
            // Placeholder for potential phaser registration if needed in future
        }
    }


    public static void callEntityTick(Consumer<Entity> tickConsumer, Entity entityIn, ServerWorld serverworld) {
        if (config.disabled || config.disableEntity) {
            tickConsumer.accept(entityIn);
            return;
        }

        if (shouldTickPortalSynchronously(entityIn)) {
            tickConsumer.accept(entityIn);
            return;
        }

        int chunkX = entityIn.getChunkPos().x;
        int chunkZ = entityIn.getChunkPos().z;

        ThreadedChunksRegion matchingRegion = findMatchingRegion(chunkX, chunkZ, serverworld);
        if (matchingRegion == null) {
            // No matching region, delay processing
            delayedEntityTasks.computeIfAbsent(serverworld, w -> new ArrayList<>())
                    .add(() -> {
                        tickConsumer.accept(entityIn);
                    });
            return;
        }

        Executor executor = shouldUseSingleThread(entityIn) ?
                matchingRegion.getSingleThreadExecutor() :
                matchingRegion.getEntityTickExecutor();


        matchingRegion.getEntityTickPhaser().register();
        executor.execute(() -> {
            String taskName = null;
            try {
                // Wait for chunk tick stage to complete in this region
                matchingRegion.getChunkTickPhaser().awaitAdvance(0);

                if (config.opsTracing) {
                    taskName = "EntityTick: " + entityIn;
                    matchingRegion.currentTasks.add(taskName);
                } else {
                    taskName = "";
                }

                matchingRegion.recordEntityStageStart();

                long startTime = System.nanoTime();

                tickConsumer.accept(entityIn);

                long endTime = System.nanoTime();
                long duration = endTime - startTime;
                matchingRegion.addEntityTickTime(duration);
            } finally {
                matchingRegion.getEntityTickPhaser().arrive();
                if (config.opsTracing) {
                    if (matchingRegion.currentTasks.stream().anyMatch(task -> (task.startsWith("Chunk") || task.startsWith("Block"))))
                        LOGGER.debug("Mixed tasks detected");
                    matchingRegion.currentTasks.remove(taskName);
                }
            }
        });
    }

    private static boolean shouldUseSingleThread(Entity entity) {
        return entity instanceof FallingBlockEntity ||
                entity instanceof AllayEntity ||
                entity instanceof TntEntity;
    }

    private static boolean shouldTickPortalSynchronously(Entity entity) {
        if (entity.portalManager != null && entity.portalManager.isInPortal()) {
            return true;
        }
        return entity instanceof ProjectileEntity;
    }

    public static void postEntityTick(ServerWorld world) {
        for (ThreadedChunksRegion region : PLAYER_REGION_MANAGER.getRegions(world)) {
            asyncExecutor.execute(region::postEntityTick);
        }

        // Process delayed entity tasks
        List<Runnable> tasks = delayedEntityTasks.remove(world);
        if (tasks != null) {
            WorldTickStats stats = worldTickStats.computeIfAbsent(world, w -> new WorldTickStats());
            for (Runnable task : tasks) {
                long startTime = System.nanoTime();
                task.run();
                long endTime = System.nanoTime();
                stats.entityTickTimesCurrent.add(endTime - startTime);
            }
        }

    }

    public static void preBlockEntityTick(ServerWorld world) {
        if (config.disabled || config.disableBlockEntity) {
            return;
        }
        for (ThreadedChunksRegion region : PLAYER_REGION_MANAGER.getRegions(world)) {
            // Placeholder for potential phaser registration if needed in future
        }
    }

    public static void callBlockEntityTick(BlockEntityTickInvoker tte, World world) {
        if (!(world instanceof ServerWorld) || !(tte instanceof WorldChunk.WrappedBlockEntityTickInvoker wrappedInvoker)) {
            tte.tick();
            return;
        }

        if (!(wrappedInvoker.wrapped instanceof WorldChunk.DirectBlockEntityTickInvoker<?>)) {
            tte.tick();
            return;
        }

        if (config.disabled || config.disableBlockEntity) {
            tte.tick();
            return;
        }

        BlockEntity blockEntity = ((WorldChunk.DirectBlockEntityTickInvoker<?>) wrappedInvoker.wrapped).blockEntity;
        int chunkX = blockEntity.getPos().getX() >> 4;
        int chunkZ = blockEntity.getPos().getZ() >> 4;

        ServerWorld serverWorld = (ServerWorld) world;
        ThreadedChunksRegion matchingRegion = findMatchingRegion(chunkX, chunkZ, serverWorld);
        if (matchingRegion == null) {
            // No matching region, delay processing
            delayedBlockEntityTasks.computeIfAbsent(serverWorld, w -> new ArrayList<>())
                    .add(tte::tick);
            return;
        }

        Executor executor = shouldUseSingleThread(blockEntity) ?
                matchingRegion.getSingleThreadExecutor() :
                matchingRegion.getBlockEntityTickExecutor();


        matchingRegion.getBlockEntityTickPhaser().register();
        executor.execute(() -> {
            String taskName = null;
            try {
                // Wait for entity tick stage to complete in this region
                matchingRegion.getEntityTickPhaser().awaitAdvance(0);

                if (config.opsTracing) {
                    taskName = "BlockEntityTick: " + tte + "@" + tte.hashCode();
                    matchingRegion.currentTasks.add(taskName);
                } else {
                    taskName = "";
                }

                matchingRegion.recordBlockEntityStageStart();

                long startTime = System.nanoTime();
                tte.tick();
                long endTime = System.nanoTime();
                long duration = endTime - startTime;
                matchingRegion.addBlockEntityTickTime(duration);
            } finally {
                matchingRegion.getBlockEntityTickPhaser().arrive();
                if (config.opsTracing) {
                    if (matchingRegion.currentTasks.stream().anyMatch(task -> (task.startsWith("Chunk") || task.startsWith("EntityTick"))))
                        LOGGER.debug("Mixed tasks detected");
                    matchingRegion.currentTasks.remove(taskName);
                }
            }
        });
    }


    private static boolean shouldUseSingleThread(BlockEntity blockEntity) {
        return blockEntity instanceof PistonBlockEntity ||
                blockEntity instanceof SculkSensorBlockEntity ||
                blockEntity instanceof SculkShriekerBlockEntity ||
                blockEntity instanceof SculkCatalystBlockEntity;
    }

    public static void postBlockEntityTick(ServerWorld world) {
        for (ThreadedChunksRegion region : PLAYER_REGION_MANAGER.getRegions(world)) {
            asyncExecutor.execute(region::postBlockEntityTick);
        }

        // Process delayed block entity tasks
        List<Runnable> tasks = delayedBlockEntityTasks.remove(world);
        if (tasks != null) {
            WorldTickStats stats = worldTickStats.computeIfAbsent(world, w -> new WorldTickStats());
            for (Runnable task : tasks) {
                long startTime = System.nanoTime();
                task.run();
                long endTime = System.nanoTime();
                stats.blockEntityTickTimesCurrent.add(endTime - startTime);
            }
        }

        for (ThreadedChunksRegion region : PLAYER_REGION_MANAGER.getRegions(world)) {
            region.getBlockEntityTickPhaser().awaitAdvance(0);
            region.initializePhaser();
        }

    }

    public static boolean shouldThreadChunks() {
        return !MCMT.config.disableMultiChunk;
    }

    public static Collection<PlayerRegion> getPlayerRegions() {
        return PLAYER_REGION_MANAGER.getPlayerRegions();
    }

    public static List<PlayerRegion> getPlayerRegions(ServerWorld world) {
        return PLAYER_REGION_MANAGER.getRegions(world);
    }

}
