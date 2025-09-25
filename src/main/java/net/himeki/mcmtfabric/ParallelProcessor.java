package net.himeki.mcmtfabric;

import net.himeki.mcmtfabric.config.GeneralConfig;
import net.himeki.mcmtfabric.debug.WorldTickStats;
import net.himeki.mcmtfabric.parallelised.threads.ChunkRegion;
import net.himeki.mcmtfabric.parallelised.threads.ChunkRegionManager;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.himeki.mcmtfabric.serdes.pools.PostExecutePool;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
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

    private static final ChunkRegionManager CHUNK_REGION_MANAGER = new ChunkRegionManager();

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
        return CHUNK_REGION_MANAGER.getRegion(world, chunkX, chunkZ);
    }

    public static ThreadedChunksRegion findRegion(ServerWorld world, ChunkPos pos) {
        return findMatchingRegion(pos.x, pos.z, world);
    }

    public static ThreadedChunksRegion findRegion(ServerWorld world, int chunkX, int chunkZ) {
        return findMatchingRegion(chunkX, chunkZ, world);
    }

    public static void setupThreadPool(int parallelism) {
        worldPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("MCMT-World-", 0).factory());
    }

    public static void shutdown() {
        CHUNK_REGION_MANAGER.clear();
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
        CHUNK_REGION_MANAGER.beginWorldTick(world);
        if (config.disabled || config.disableEnvironment) {
            return;
        }
    }

    public static void callTickChunks(ServerWorld world, WorldChunk chunk, int randomTickSpeed) {
        if (config.disabled || config.disableEnvironment) {
            world.tickChunk(chunk, randomTickSpeed);
            return;
        }

        ChunkRegion matchingRegion = CHUNK_REGION_MANAGER.activateRegion(world, chunk.getPos().x, chunk.getPos().z);

        ChunkHolder holder = ((ServerChunkManager) world.getChunkManager()).getChunkHolder(chunk.getPos().toLong());
        if (holder != null) {
            holder.incrementRefCount();
        }

        ChunkHolder finalHolder = holder;
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
                if (finalHolder != null) {
                    finalHolder.decrementRefCount();
                }
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

        ThreadedChunksRegion region = findMatchingRegion(entity.getChunkPos().x, entity.getChunkPos().z, world);
        if (region == null) {
            region = CHUNK_REGION_MANAGER.activateRegion(world, entity.getChunkPos().x, entity.getChunkPos().z);
        }
        ThreadedChunksRegion matchingRegion = region;

        String taskName = null;
        if (config.opsTracing) {
            taskName = "EntityTick: " + entity;
            matchingRegion.currentTasks.add(taskName);
        }
        String finalTaskName = taskName;
        ThreadedChunksRegion finalRegion = matchingRegion;
        finalRegion.executeEntityTask(() -> {
            currentEnts.incrementAndGet();
            try {
                try {
                    tickConsumer.accept(entity);
                } catch (NoSuchElementException exception) {
                    LOGGER.debug("Skipping entity tick for {} in region {} due to missing AI state", entity, finalRegion.getName(), exception);
                }
            } finally {
                currentEnts.decrementAndGet();
                if (finalTaskName != null) {
                    finalRegion.currentTasks.remove(finalTaskName);
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
        ThreadedChunksRegion matchingRegion = CHUNK_REGION_MANAGER.activateRegion(serverWorld,
                blockEntity.getPos().getX() >> 4, blockEntity.getPos().getZ() >> 4);

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
        CHUNK_REGION_MANAGER.finishWorldTick(world);
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

    public static ChunkRegion getRegion(ServerWorld world, ChunkPos pos) {
        return CHUNK_REGION_MANAGER.getRegion(world, pos.x, pos.z);
    }

    public static ChunkRegion getRegion(ServerWorld world, int chunkX, int chunkZ) {
        return CHUNK_REGION_MANAGER.getRegion(world, chunkX, chunkZ);
    }

    public static ChunkRegion getOrCreateRegion(ServerWorld world, int chunkX, int chunkZ) {
        return CHUNK_REGION_MANAGER.getOrCreateRegion(world, chunkX, chunkZ);
    }

    public static ChunkRegion getRegion(ServerPlayerEntity player) {
        return CHUNK_REGION_MANAGER.getRegion(player);
    }

    public static ChunkRegion getHeaviestRegion() {
        return CHUNK_REGION_MANAGER.getHeaviestRegion();
    }
}

