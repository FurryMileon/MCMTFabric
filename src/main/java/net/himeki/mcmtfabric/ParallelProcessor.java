package net.himeki.mcmtfabric;

import net.himeki.mcmtfabric.config.GeneralConfig;
import net.himeki.mcmtfabric.debug.WorldTickStats;
import net.himeki.mcmtfabric.parallelised.BotRegionManager;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.himeki.mcmtfabric.parallelised.threads.VirtualThreadPools;
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
import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import net.minecraft.world.chunk.WorldChunk;
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

    private static final ConcurrentMap<ChunkKey, ThreadedChunksRegion> chunkRegions = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ServerWorld, Set<ThreadedChunksRegion>> activeChunkStageRegions = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ServerWorld, Set<ThreadedChunksRegion>> activeEntityStageRegions = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ServerWorld, Set<ThreadedChunksRegion>> activeBlockEntityStageRegions = new ConcurrentHashMap<>();

    static Map<String, Set<Thread>> mcThreadTracker = new ConcurrentHashMap<>();

    public static final ConcurrentHashMap<ServerWorld, WorldTickStats> worldTickStats = new ConcurrentHashMap<>();

    private static final class ChunkKey {
        private final String worldId;
        private final int x;
        private final int z;

        private ChunkKey(String worldId, int x, int z) {
            this.worldId = worldId;
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkKey chunkKey)) return false;
            return x == chunkKey.x && z == chunkKey.z && Objects.equals(worldId, chunkKey.worldId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldId, x, z);
        }
    }

    public static void resetThreadedChunksRegions() {
        chunkRegions.values().forEach(ThreadedChunksRegion::shutdownExecutors);
        chunkRegions.clear();
    }

    public static Collection<ThreadedChunksRegion> getAllRegions() {
        return Collections.unmodifiableCollection(chunkRegions.values());
    }

    public static ThreadedChunksRegion getRegionForChunk(ServerWorld world, int chunkX, int chunkZ) {
        return getOrCreateRegion(world, chunkX, chunkZ);
    }

    private static ThreadedChunksRegion getOrCreateRegion(ServerWorld world, int chunkX, int chunkZ) {
        String worldId = world.getRegistryKey().getValue().toString();
        ChunkKey key = new ChunkKey(worldId, chunkX, chunkZ);
        return chunkRegions.computeIfAbsent(key, ignored -> ThreadedChunksRegion.forChunk(worldId, chunkX, chunkZ));
    }

    private static Set<ThreadedChunksRegion> activeRegionSet(ConcurrentMap<ServerWorld, Set<ThreadedChunksRegion>> map, ServerWorld world) {
        return map.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet());
    }

    public static void setupThreadPool(int parallelism) {
        worldPool = VirtualThreadPools.getWorldExecutor();
    }

    public static AtomicInteger currentWorlds = new AtomicInteger();
    public static AtomicInteger currentEnts = new AtomicInteger();
    public static AtomicInteger currentTEs = new AtomicInteger();
    public static AtomicInteger currentEnvs = new AtomicInteger();

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
    }

    public static void callTick(ServerWorld serverworld, BooleanSupplier hasTimeLeft, MinecraftServer server) {
        if (config.disabled || config.disableWorld) {
            serverworld.tick(hasTimeLeft);
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
                taskName = "WorldTick: " + serverworld + "@" + serverworld.hashCode();
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
                Deque<Runnable> queue = PostExecutePool.POOL.getQueue();
                Iterator<Runnable> qi = queue.iterator();
                while (qi.hasNext()) {
                    Runnable r = qi.next();
                    r.run();
                    qi.remove();
                }
            }
        }

        chunkRegions.values().forEach(ThreadedChunksRegion::swapExecutionTimeBuffers);

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
    }

    public static void callTickChunks(ServerWorld world, WorldChunk chunk, int k) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        ThreadedChunksRegion region = getOrCreateRegion(world, chunkX, chunkZ);
        activeRegionSet(activeChunkStageRegions, world).add(region);

        Executor executor = region.getChunkTickExecutor();

        String taskName;
        if (config.opsTracing) {
            taskName = "ChunkTick: " + chunk + "@" + chunk.hashCode();
            region.currentTasks.add(taskName);
        } else {
            taskName = "";
        }

        region.getChunkTickPhaser().register();

        executor.execute(() -> {
            try {
                region.recordChunkStageStart();

                long startTime = System.nanoTime();
                world.tickChunk(chunk, k);
                long endTime = System.nanoTime();
                region.addChunkTickTime(endTime - startTime);
            } finally {
                region.getChunkTickPhaser().arrive();
                if (config.opsTracing) {
                    region.currentTasks.remove(taskName);
                }
            }
        });
    }

    public static void postChunkTick(ServerWorld world) {
        Set<ThreadedChunksRegion> regions = activeChunkStageRegions.remove(world);
        if (regions != null) {
            for (ThreadedChunksRegion region : regions) {
                VirtualThreadPools.getGeneralExecutor().execute(region::postChunkTick);
            }
        }
    }

    public static void preEntityTick(ServerWorld world) {
        if (config.disabled || config.disableEntity) {
            return;
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

        ThreadedChunksRegion region = getOrCreateRegion(serverworld, chunkX, chunkZ);
        activeRegionSet(activeEntityStageRegions, serverworld).add(region);

        Executor executor = shouldUseSingleThread(entityIn) ?
                region.getSingleThreadExecutor() :
                region.getEntityTickExecutor();

        region.getEntityTickPhaser().register();
        executor.execute(() -> {
            String taskName = null;
            try {
                region.getChunkTickPhaser().awaitAdvance(0);

                if (config.opsTracing) {
                    taskName = "EntityTick: " + entityIn;
                    region.currentTasks.add(taskName);
                } else {
                    taskName = "";
                }

                region.recordEntityStageStart();

                long startTime = System.nanoTime();

                if (entityIn instanceof ServerPlayerEntity player) {
                    BotRegionManager.checkAndManageBot(player);
                }

                tickConsumer.accept(entityIn);

                long endTime = System.nanoTime();
                region.addEntityTickTime(endTime - startTime);
            } finally {
                region.getEntityTickPhaser().arrive();
                if (config.opsTracing) {
                    region.currentTasks.remove(taskName);
                }
            }
        });
    }

    public static void postEntityTick(ServerWorld world) {
        Set<ThreadedChunksRegion> regions = activeEntityStageRegions.remove(world);
        if (regions != null) {
            for (ThreadedChunksRegion region : regions) {
                VirtualThreadPools.getGeneralExecutor().execute(region::postEntityTick);
            }
        }
    }

    public static void preBlockEntityTick(ServerWorld world) {
        if (config.disabled || config.disableBlockEntity) {
            return;
        }
    }

    public static void callBlockEntityTick(BlockEntityTickInvoker tte, World world) {
        if (!(world instanceof ServerWorld serverWorld) || !(tte instanceof WorldChunk.WrappedBlockEntityTickInvoker wrappedInvoker)) {
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

        ThreadedChunksRegion region = getOrCreateRegion(serverWorld, chunkX, chunkZ);
        activeRegionSet(activeBlockEntityStageRegions, serverWorld).add(region);

        Executor executor = shouldUseSingleThread(blockEntity) ?
                region.getSingleThreadExecutor() :
                region.getBlockEntityTickExecutor();

        region.getBlockEntityTickPhaser().register();
        executor.execute(() -> {
            String taskName = null;
            try {
                region.getEntityTickPhaser().awaitAdvance(0);

                if (config.opsTracing) {
                    taskName = "BlockEntityTick: " + tte + "@" + tte.hashCode();
                    region.currentTasks.add(taskName);
                } else {
                    taskName = "";
                }

                region.recordBlockEntityStageStart();

                long startTime = System.nanoTime();
                tte.tick();
                long endTime = System.nanoTime();
                region.addBlockEntityTickTime(endTime - startTime);
            } finally {
                region.getBlockEntityTickPhaser().arrive();
                if (config.opsTracing) {
                    region.currentTasks.remove(taskName);
                }
            }
        });
    }

    public static void postBlockEntityTick(ServerWorld world) {
        Set<ThreadedChunksRegion> regions = activeBlockEntityStageRegions.remove(world);
        if (regions != null) {
            for (ThreadedChunksRegion region : regions) {
                VirtualThreadPools.getGeneralExecutor().execute(() -> {
                    region.postBlockEntityTick();
                    region.initializePhaser();
                });
            }
        }
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

    private static boolean shouldUseSingleThread(BlockEntity blockEntity) {
        return blockEntity instanceof PistonBlockEntity ||
                blockEntity instanceof SculkSensorBlockEntity ||
                blockEntity instanceof SculkShriekerBlockEntity ||
                blockEntity instanceof SculkCatalystBlockEntity;
    }

    public static boolean shouldThreadChunks() {
        return !MCMT.config.disableMultiChunk;
    }
}
