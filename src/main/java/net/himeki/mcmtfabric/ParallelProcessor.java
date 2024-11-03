package net.himeki.mcmtfabric;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.himeki.mcmtfabric.config.GeneralConfig;
import net.himeki.mcmtfabric.debug.WorldTickStats;
import net.himeki.mcmtfabric.parallelised.CPUCoreManager;
import net.himeki.mcmtfabric.parallelised.SharedThreadPools;
import net.himeki.mcmtfabric.parallelised.ThreadedChunksRegion;
import net.himeki.mcmtfabric.serdes.pools.PostExecutePool;
import net.minecraft.block.entity.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import net.minecraft.world.chunk.WorldChunk;
import net.openhft.affinity.AffinityLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ParallelProcessor {

    private static final Logger LOGGER = LogManager.getLogger();

    static Phaser worldPhaser;

    static ExecutorService worldPool;
    static MinecraftServer mcs;
    static AtomicBoolean isTicking = new AtomicBoolean();

    private static final ConcurrentHashMap<ServerWorld, List<Runnable>> delayedChunkTasks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ServerWorld, List<Runnable>> delayedEntityTasks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ServerWorld, List<Runnable>> delayedBlockEntityTasks = new ConcurrentHashMap<>();

    static Map<String, Set<Thread>> mcThreadTracker = new ConcurrentHashMap<String, Set<Thread>>();

    // List of ThreadedChunksRegion
    public static final List<ThreadedChunksRegion> threadedChunksRegions = new ArrayList<>();

    private static final Set<ThreadedChunksRegion> pendingRegionsToAdd = ConcurrentHashMap.newKeySet();

    private static final Set<ThreadedChunksRegion> pendingRegionsToRemove = ConcurrentHashMap.newKeySet();


    public static final ConcurrentHashMap<ServerWorld, WorldTickStats> worldTickStats = new ConcurrentHashMap<>();

    private static final Cache<ChunkPos, ThreadedChunksRegion> chunkRegionCache = Caffeine.newBuilder()
            .initialCapacity(10000)
            .maximumSize(10000)
            .build();

    public static void resetThreadedChunksRegions() {
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                region.shutdownExecutors();
            }
            threadedChunksRegions.clear();
            chunkRegionCache.invalidateAll(); // Invalidate cache when regions are reset
        }
    }

    public static void addThreadedChunksRegion(ThreadedChunksRegion region) {
        synchronized (pendingRegionsToAdd) {
            pendingRegionsToAdd.add(region);
            region.getSingleThreadExecutor(); // Force load executor once to avoid blocking when there is no chunk tick
        }
    }


    public static void removeThreadedChunksRegion(ThreadedChunksRegion region) {
        synchronized (threadedChunksRegions) {
            pendingRegionsToRemove.add(region);
        }
    }

    public static void removeThreadedChunksRegionByName(String name) {
        synchronized (threadedChunksRegions) {
            ThreadedChunksRegion region = null;
            for (ThreadedChunksRegion r : threadedChunksRegions) {
                if (r.getName().equals(name)) {
                    region = r;
                    break;
                }
            }
            if (region != null) {
                removeThreadedChunksRegion(region);
            }
        }
    }

    private static ThreadedChunksRegion findMatchingRegion(int chunkX, int chunkZ, World world) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        return chunkRegionCache.get(pos, key -> {
            synchronized (threadedChunksRegions) {
                String worldId = world.getRegistryKey().getValue().toString();

                // Single stream operation to find the smallest matching region
                return threadedChunksRegions.stream()
                        .filter(region -> region.contains(worldId, chunkX, chunkZ))
                        .min((r1, r2) -> {
                            long area1 = r1.getArea();
                            long area2 = r2.getArea();
                            return Long.compare(area1, area2);
                        })
                        .orElse(null);
            }
        });
    }

    public static void setupThreadPool(int parallelism) {
        SharedThreadPools.getSharedTickPool();

        AtomicInteger worldPoolThreadID = new AtomicInteger();
        final ClassLoader cl = MCMT.class.getClassLoader();
        ForkJoinPool.ForkJoinWorkerThreadFactory worldThreadFactory = pool -> {
            ForkJoinWorkerThread fjwt = new ForkJoinWorkerThread(pool) {
                private final int assignedCpuCore = CPUCoreManager.acquireCore("WORLD");  // Mark as region thread
                private AffinityLock affinityLock;

                @Override
                protected void onStart() {
                    super.onStart();
                    try {
                        affinityLock = AffinityLock.acquireLock(assignedCpuCore);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to bind thread to CPU core " + assignedCpuCore, e);
                    }
                }

                @Override
                protected void onTermination(Throwable exception) {
                    try {
                        if (affinityLock != null) {
                            affinityLock.release();
                            affinityLock = null;
                        }
                        CPUCoreManager.releaseCore(assignedCpuCore, "REGION");
                    } finally {
                        super.onTermination(exception);
                    }
                }
            };

            fjwt.setName("MCMT-World-Pool-Thread-" + worldPoolThreadID.getAndIncrement());
            regThread("MCMT-World", fjwt);
            fjwt.setContextClassLoader(cl);
            return fjwt;
        };

        worldPool = new ForkJoinPool(Math.min(3, Math.max(parallelism / 2, 1)), worldThreadFactory, null, true);
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
        // Process pending regions
        synchronized (pendingRegionsToAdd) {
            if (!pendingRegionsToAdd.isEmpty()) {
                synchronized (threadedChunksRegions) {
                    threadedChunksRegions.addAll(pendingRegionsToAdd);
                    chunkRegionCache.invalidateAll();
                    pendingRegionsToAdd.clear();
                }
            }
        }
        synchronized (pendingRegionsToRemove) {
            if (!pendingRegionsToRemove.isEmpty()) {
                threadedChunksRegions.removeAll(pendingRegionsToRemove);
                synchronized (pendingRegionsToRemove) {
                    for (ThreadedChunksRegion region : pendingRegionsToRemove) {
                        region.shutdownExecutors();
                    }
                    chunkRegionCache.invalidateAll();
                    pendingRegionsToRemove.clear();
                }
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

        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                region.swapExecutionTimeBuffers();
            }
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
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    // Register the main thread in the region's chunkTickPhaser
//                    region.getChunkTickPhaser().register();
                }
            }
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

        ExecutorService executor = matchingRegion.getChunkTickExecutor();

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
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    region.getChunkTickPhaser().arrive();
                    if (!(region.getChunkTickPhaser().getRegisteredParties() > 1))
                        region.recordChunkStageEnd();
                }
            }
        }
        if (!config.disabled && !config.disableEnvironment) {
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
    }


    public static void preEntityTick(ServerWorld world) {
        if (config.disabled || config.disableEntity) {
            return;
        }
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    // Register the main thread in the region's entityTickPhaser
//                    region.getEntityTickPhaser().register();
                }
            }
        }
    }


    public static void callEntityTick(Consumer<Entity> tickConsumer, Entity entityIn, ServerWorld serverworld) {
        if (config.disabled || config.disableEntity) {
            tickConsumer.accept(entityIn);
            return;
        }

        int chunkX = entityIn.getChunkPos().x;
        int chunkZ = entityIn.getChunkPos().z;

        ThreadedChunksRegion matchingRegion = findMatchingRegion(chunkX, chunkZ, serverworld);
        if (matchingRegion == null) {
            // No matching region, delay processing
            delayedEntityTasks.computeIfAbsent(serverworld, w -> new ArrayList<>())
                    .add(() -> tickConsumer.accept(entityIn));
            return;
        }

        ExecutorService executor = shouldUseSingleThread(entityIn) ?
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

                matchingRegion.recordChunkStageEnd();
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

    public static void postEntityTick(ServerWorld world) {
        if (!config.disabled && !config.disableEntity) {
            synchronized (threadedChunksRegions) {
                for (ThreadedChunksRegion region : threadedChunksRegions) {
                    if (!(region.getEntityTickPhaser().getRegisteredParties() > 1)) {
                        region.recordChunkStageEnd();   // No worker to wait for chunk stage end and record, so record here
                    }
                    if (region.getWorldId().equals(world.getRegistryKey().getValue().toString()))
                        region.getEntityTickPhaser().arrive();
                }
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
    }

    public static void preBlockEntityTick(ServerWorld world) {
        if (config.disabled || config.disableTileEntity) {
            return;
        }
        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
//                    region.getBlockEntityTickPhaser().register();
                }
            }
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

        BlockEntity blockEntity = ((WorldChunk.DirectBlockEntityTickInvoker<?>) wrappedInvoker.wrapped).blockEntity;
        int chunkX = blockEntity.getPos().getX() >> 4;
        int chunkZ = blockEntity.getPos().getZ() >> 4;

        ThreadedChunksRegion matchingRegion = findMatchingRegion(chunkX, chunkZ, world);
        if (matchingRegion == null) {
            // No matching region, delay processing
            delayedBlockEntityTasks.computeIfAbsent((ServerWorld) world, w -> new ArrayList<>())
                    .add(tte::tick);
            return;
        }

        ExecutorService executor = shouldUseSingleThread(blockEntity) ?
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

                matchingRegion.recordEntityStageEnd();
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
        if (!config.disabled && !config.disableTileEntity) {
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
        }

        synchronized (threadedChunksRegions) {
            for (ThreadedChunksRegion region : threadedChunksRegions) {
                if (region.getWorldId().equals(world.getRegistryKey().getValue().toString())) {
                    // Main thread arrives
                    if (!(region.getBlockEntityTickPhaser().getRegisteredParties() > 1)) {
                        region.getChunkTickPhaser().awaitAdvance(0);
                        region.getEntityTickPhaser().awaitAdvance(0);
                    }
                    region.getBlockEntityTickPhaser().arriveAndAwaitAdvance();
                    region.recordBlockEntityStageEnd();
                    region.initializePhaser();
                }
            }
        }
    }

    public static boolean shouldThreadChunks() {
        return !MCMT.config.disableMultiChunk;
    }

}
