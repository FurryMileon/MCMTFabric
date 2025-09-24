package net.himeki.mcmtfabric;

import net.himeki.mcmtfabric.config.GeneralConfig;
import net.himeki.mcmtfabric.debug.WorldTickStats;
import net.himeki.mcmtfabric.parallelised.threads.PlayerRegion;
import net.himeki.mcmtfabric.parallelised.threads.PlayerRegionManager;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.himeki.mcmtfabric.serdes.pools.PostExecutePool;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
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

    static Map<String, Set<Thread>> mcThreadTracker = new ConcurrentHashMap<>();

    private static final PlayerRegionManager PLAYER_REGION_MANAGER = new PlayerRegionManager();

    public static final ConcurrentHashMap<ServerWorld, WorldTickStats> worldTickStats = new ConcurrentHashMap<>();

    // Statistics
    public static AtomicInteger currentWorlds = new AtomicInteger();
    public static AtomicInteger currentEnts = new AtomicInteger();
    public static AtomicInteger currentTEs = new AtomicInteger();
    public static AtomicInteger currentEnvs = new AtomicInteger();

    // Operation logging
    public static Set<String> currentTasks = ConcurrentHashMap.newKeySet();

    static long tickStart = 0;
    static GeneralConfig config;

    private static ThreadedChunksRegion findMatchingRegion(int chunkX, int chunkZ, ServerWorld world) {
        return PLAYER_REGION_MANAGER.findRegion(world, chunkX, chunkZ);
    }

    public static void setupThreadPool(int parallelism) {
        worldPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("MCMT-World-", 0).factory());
    }

    public static void shutdown() {
        PLAYER_REGION_MANAGER.shutdownAll();
        if (worldPool != null) {
            worldPool.shutdown();
            try {
                worldPool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worldPool = null;
        }
        worldPhaser = null;
        isTicking.set(false);
    }

    public static void regThread(String poolName, Thread thread) {
        mcThreadTracker.computeIfAbsent(poolName, s -> ConcurrentHashMap.newKeySet()).add(thread);
    }

    public static boolean isThreadPooled(String poolName, Thread t) {
        return mcThreadTracker.containsKey(poolName) && mcThreadTracker.get(poolName).contains(t);
    }

    public static boolean serverExecutionThreadPatch(MinecraftServer ms) {
        return isThreadPooled("MCMT-World", Thread.currentThread()) || isThreadPooled("MCMT-Tick", Thread.currentThread());
    }

    public static void preTick(int size, MinecraftServer server) {
        config = MCMT.config;
        PLAYER_REGION_MANAGER.updateRegions(server);
        if (!config.disabled && !config.disableWorld) {
            if (worldPhaser != null) {
                LOGGER.warn("Multiple servers?");
                return;
            }
            tickStart = System.nanoTime();
            isTicking.set(true);
            worldPhaser = new Phaser(size + 1);
            mcs = server;
        }
    }

    public static void callTick(ServerWorld serverWorld, BooleanSupplier hasTimeLeft, MinecraftServer server) {
        if (config.disabled || config.disableWorld) {
            serverWorld.tick(hasTimeLeft);
            return;
        }
        if (mcs != server) {
            LOGGER.warn("Multiple servers?");
            config.disabled = true;
            serverWorld.tick(hasTimeLeft);
            return;
        }

        String taskName = null;
        if (config.opsTracing) {
            taskName = "WorldTick: " + serverWorld + "@" + serverWorld.hashCode();
            currentTasks.add(taskName);
        }
        String finalTaskName = taskName;
        worldPool.execute(() -> {
            try {
                currentWorlds.incrementAndGet();
                serverWorld.tick(hasTimeLeft);
            } finally {
                worldPhaser.arriveAndDeregister();
                currentWorlds.decrementAndGet();
                if (finalTaskName != null) {
                    currentTasks.remove(finalTaskName);
                }
            }
        });
    }

    public static void postTick(MinecraftServer server) {
        if (!config.disabled && !config.disableWorld) {
            if (mcs != server) {
                LOGGER.warn("Multiple servers?");
                return;
            }
            worldPhaser.arriveAndAwaitAdvance();
            isTicking.set(false);
            worldPhaser = null;
            Deque<Runnable> queue = PostExecutePool.POOL.getQueue();
            Iterator<Runnable> iterator = queue.iterator();
            while (iterator.hasNext()) {
                Runnable runnable = iterator.next();
                runnable.run();
                iterator.remove();
            }
        }

        synchronized (worldTickStats) {
            for (WorldTickStats stats : worldTickStats.values()) {
                stats.swapExecutionTimeBuffers();
            }
        }
    }

    public static void preChunkTick(ServerWorld world) {
        List<PlayerRegion> regions = PLAYER_REGION_MANAGER.getRegions(world);
        for (ThreadedChunksRegion region : regions) {
            region.beginTick();
        }
        if (config.disabled || config.disableEnvironment) {
            return;
        }
    }

    public static void callTickChunks(ServerWorld world, WorldChunk chunk, int randomTickSpeed) {
        if (config.disabled || config.disableEnvironment) {
            world.tickChunk(chunk, randomTickSpeed);
            return;
        }

        ThreadedChunksRegion matchingRegion = findMatchingRegion(chunk.getPos().x, chunk.getPos().z, world);
        if (matchingRegion == null) {
            world.tickChunk(chunk, randomTickSpeed);
            return;
        }

        String taskName = null;
        if (config.opsTracing) {
            taskName = "ChunkTick: " + chunk + "@" + chunk.hashCode();
            matchingRegion.currentTasks.add(taskName);
        }
        String finalTaskName = taskName;
        matchingRegion.executeChunkTask(() -> {
            currentEnvs.incrementAndGet();
            try {
                world.tickChunk(chunk, randomTickSpeed);
            } finally {
                currentEnvs.decrementAndGet();
                if (finalTaskName != null) {
                    matchingRegion.currentTasks.remove(finalTaskName);
                }
            }
        });
    }

    public static void postChunkTick(ServerWorld world) {
        // No additional coordination required in the simplified scheduler
    }

    public static void preEntityTick(ServerWorld world) {
        if (config.disabled || config.disableEntity) {
            return;
        }
    }

    public static void callEntityTick(Consumer<Entity> tickConsumer, Entity entity, ServerWorld world) {
        if (config.disabled || config.disableEntity) {
            tickConsumer.accept(entity);
            return;
        }

        if (shouldTickPortalSynchronously(entity)) {
            tickConsumer.accept(entity);
            return;
        }

        ThreadedChunksRegion matchingRegion = findMatchingRegion(entity.getChunkPos().x, entity.getChunkPos().z, world);
        if (matchingRegion == null) {
            tickConsumer.accept(entity);
            return;
        }

        String taskName = null;
        if (config.opsTracing) {
            taskName = "EntityTick: " + entity;
            matchingRegion.currentTasks.add(taskName);
        }
        String finalTaskName = taskName;
        matchingRegion.executeEntityTask(() -> {
            currentEnts.incrementAndGet();
            try {
                tickConsumer.accept(entity);
            } finally {
                currentEnts.decrementAndGet();
                if (finalTaskName != null) {
                    matchingRegion.currentTasks.remove(finalTaskName);
                }
            }
        });
    }

    public static void postEntityTick(ServerWorld world) {
        // No additional coordination required in the simplified scheduler
    }

    public static void preBlockEntityTick(ServerWorld world) {
        if (config.disabled || config.disableBlockEntity) {
            return;
        }
    }

    public static void callBlockEntityTick(BlockEntityTickInvoker tickInvoker, World world) {
        if (!(world instanceof ServerWorld serverWorld) ||
                !(tickInvoker instanceof WorldChunk.WrappedBlockEntityTickInvoker wrappedInvoker) ||
                !(wrappedInvoker.wrapped instanceof WorldChunk.DirectBlockEntityTickInvoker<?> directInvoker)) {
            tickInvoker.tick();
            return;
        }

        if (config.disabled || config.disableBlockEntity) {
            tickInvoker.tick();
            return;
        }

        BlockEntity blockEntity = ((WorldChunk.DirectBlockEntityTickInvoker<?>) directInvoker).blockEntity;
        ThreadedChunksRegion matchingRegion = findMatchingRegion(blockEntity.getPos().getX() >> 4,
                blockEntity.getPos().getZ() >> 4, serverWorld);
        if (matchingRegion == null) {
            tickInvoker.tick();
            return;
        }

        String taskName = null;
        if (config.opsTracing) {
            taskName = "BlockEntityTick: " + tickInvoker + "@" + tickInvoker.hashCode();
            matchingRegion.currentTasks.add(taskName);
        }
        String finalTaskName = taskName;
        matchingRegion.executeBlockEntityTask(() -> {
            currentTEs.incrementAndGet();
            try {
                tickInvoker.tick();
            } finally {
                currentTEs.decrementAndGet();
                if (finalTaskName != null) {
                    matchingRegion.currentTasks.remove(finalTaskName);
                }
            }
        });
    }

    public static void postBlockEntityTick(ServerWorld world) {
        for (ThreadedChunksRegion region : PLAYER_REGION_MANAGER.getRegions(world)) {
            region.finishTick();
        }
    }

    private static boolean shouldTickPortalSynchronously(Entity entity) {
        if (entity.portalManager != null && entity.portalManager.isInPortal()) {
            return true;
        }
        return entity instanceof ProjectileEntity;
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

