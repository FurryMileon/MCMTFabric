package net.himeki.mcmtfabric;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.himeki.mcmtfabric.config.GeneralConfig;
import net.himeki.mcmtfabric.parallelised.SharedThreadPools;
import net.himeki.mcmtfabric.parallelised.ThreadedChunksRange;
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

    static ConcurrentHashMap<ServerWorld, Phaser> sharedPhasers = new ConcurrentHashMap<>();
    static ExecutorService worldPool;
    static MinecraftServer mcs;
    static AtomicBoolean isTicking = new AtomicBoolean();

    static Map<String, Set<Thread>> mcThreadTracker = new ConcurrentHashMap<String, Set<Thread>>();

    // List of ThreadedChunksRange
    private static List<ThreadedChunksRange> threadedChunksRanges = new ArrayList<>();

    private static final Cache<ChunkPos, ThreadedChunksRange> chunkRangeCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .build();

    public static void resetThreadedChunksRanges() {
        synchronized (threadedChunksRanges) {
            for (ThreadedChunksRange range : threadedChunksRanges) {
                range.shutdownExecutors();
            }
            threadedChunksRanges.clear();
            chunkRangeCache.invalidateAll(); // Invalidate cache when ranges are reset
        }
    }

    public static void addThreadedChunksRange(ThreadedChunksRange range) {
        synchronized (threadedChunksRanges) {
            threadedChunksRanges.add(range);
            chunkRangeCache.invalidateAll(); // Invalidate cache when a new range is added
        }
    }

    public static void removeThreadedChunksRange(ThreadedChunksRange range) {
        synchronized (threadedChunksRanges) {
            threadedChunksRanges.remove(range);
            range.shutdownExecutors();
            chunkRangeCache.invalidateAll(); // Invalidate cache when a range is removed
        }
    }

    public static void removeThreadedChunksRangeByName(String name) {
        synchronized (threadedChunksRanges) {
            ThreadedChunksRange range = null;
            for (ThreadedChunksRange r : threadedChunksRanges) {
                if (r.getName().equals(name)) {
                    range = r;
                    break;
                }
            }
            if (range != null) {
                removeThreadedChunksRange(range);
            }
        }
    }

    private static ThreadedChunksRange findMatchingRange(int chunkX, int chunkZ, World world) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        return chunkRangeCache.get(pos, key -> {
            synchronized (threadedChunksRanges) {
                String worldId = world.getRegistryKey().getValue().toString();
                for (ThreadedChunksRange range : threadedChunksRanges) {
                    if (range.contains(worldId, chunkX, chunkZ)) {
                        return range;
                    }
                }
                return null;
            }
        });
    }

    public static void setupThreadPool(int parallelism) {
        SharedThreadPools.getSharedTickPool();

        AtomicInteger worldPoolThreadID = new AtomicInteger();
        final ClassLoader cl = MCMT.class.getClassLoader();
        ForkJoinPool.ForkJoinWorkerThreadFactory worldThreadFactory = p -> {
            ForkJoinWorkerThread fjwt = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
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
        config = MCMT.config; // Load when config are loaded. Static loads before config update.
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

    public static long[] lastTickTime = new long[32];
    public static int lastTickTimePos = 0;
    public static int lastTickTimeFill = 0;

    public static void postTick(MinecraftServer server) {
        if (!config.disabled && !config.disableWorld) {
            if (mcs != server) {
                LOGGER.warn("Multiple servers?");
                return;
            } else {
                worldPhaser.arriveAndAwaitAdvance();
                isTicking.set(false);
                worldPhaser = null;
                //PostExecute logic
                Deque<Runnable> queue = PostExecutePool.POOL.getQueue();
                Iterator<Runnable> qi = queue.iterator();
                while (qi.hasNext()) {
                    Runnable r = qi.next();
                    r.run();
                    qi.remove();
                }
                lastTickTime[lastTickTimePos] = System.nanoTime() - tickStart;
                lastTickTimePos = (lastTickTimePos + 1) % lastTickTime.length;
                lastTickTimeFill = Math.min(lastTickTimeFill + 1, lastTickTime.length - 1);
            }
        }
    }

    public static void preChunkTick(ServerWorld world) {
        Phaser phaser; // Keep a party throughout 3 ticking phases
        if (!config.disabled && !config.disableEnvironment) {
            phaser = new Phaser(2);
        } else {
            phaser = new Phaser(1);
        }
        sharedPhasers.put(world, phaser);
    }

    public static void callTickChunks(ServerWorld world, WorldChunk chunk, int k) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        ThreadedChunksRange matchingRange = findMatchingRange(chunkX, chunkZ, world);
        if (matchingRange == null) {
            // Not inside any ThreadedChunksRange, execute on main thread
            world.tickChunk(chunk, k);
            return;
        }

        ExecutorService executor = matchingRange.getChunkTickExecutor();
        sharedPhasers.get(world).register();
        executor.execute(() -> {
            try {
                world.tickChunk(chunk, k);
            } finally {
                sharedPhasers.get(world).arriveAndDeregister();
            }
        });
    }

    public static void postChunkTick(ServerWorld world) {
        if (!config.disabled && !config.disableEnvironment) {
            var phaser = sharedPhasers.get(world);
            phaser.arriveAndDeregister();
            phaser.arriveAndAwaitAdvance();
        }
    }

    public static void preEntityTick(ServerWorld world) {
        if (!config.disabled && !config.disableEntity) sharedPhasers.get(world).register();
    }

    public static void callEntityTick(Consumer<Entity> tickConsumer, Entity entityIn, ServerWorld serverworld) {
        if (config.disabled || config.disableEntity) {
            tickConsumer.accept(entityIn);
            return;
        }

        int chunkX = entityIn.getChunkPos().x;
        int chunkZ = entityIn.getChunkPos().z;

        ThreadedChunksRange matchingRange = findMatchingRange(chunkX, chunkZ, serverworld);
        if (matchingRange == null) {
            tickConsumer.accept(entityIn);
            return;
        }

        // Choose executor based on entity type
        ExecutorService executor = shouldUseSingleThread(entityIn) ?
                matchingRange.getSingleThreadExecutor() :
                matchingRange.getEntityTickExecutor();

        sharedPhasers.get(serverworld).register();
        executor.execute(() -> {
            try {
                tickConsumer.accept(entityIn);
            } finally {
                sharedPhasers.get(serverworld).arriveAndDeregister();
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
            var phaser = sharedPhasers.get(world);
            phaser.arriveAndDeregister();
            phaser.arriveAndAwaitAdvance();
        }
    }

    public static void preBlockEntityTick(ServerWorld world) {
        if (!config.disabled && !config.disableTileEntity) sharedPhasers.get(world).register();
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

        ThreadedChunksRange matchingRange = findMatchingRange(chunkX, chunkZ, world);
        if (matchingRange == null) {
            tte.tick();
            return;
        }

        // Choose executor based on block entity type
        ExecutorService executor = shouldUseSingleThread(blockEntity) ?
                matchingRange.getSingleThreadExecutor() :
                matchingRange.getBlockEntityTickExecutor();

        sharedPhasers.get(world).register();
        executor.execute(() -> {
            try {
                tte.tick();
            } finally {
                sharedPhasers.get(world).arriveAndDeregister();
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
            var phaser = sharedPhasers.get(world);
            phaser.arriveAndDeregister();
            phaser.arriveAndAwaitAdvance();
        }
    }

    public static boolean shouldThreadChunks() {
        return !MCMT.config.disableMultiChunk;
    }

}
