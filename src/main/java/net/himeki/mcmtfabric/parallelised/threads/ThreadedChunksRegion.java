package net.himeki.mcmtfabric.parallelised.threads;

import net.himeki.mcmtfabric.MCMT;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a single chunk that is processed on top of a dedicated virtual thread.
 * Every chunk region runs tasks sequentially but in parallel with any other chunk.
 */
public class ThreadedChunksRegion {

    private final String name;
    private final String worldId;
    private final int chunkX;
    private final int chunkZ;

    private final DedicatedChunkExecutor chunkExecutor;

    private volatile Phaser chunkTickPhaser = new Phaser(1);
    private volatile Phaser entityTickPhaser = new Phaser(1);
    private volatile Phaser blockEntityTickPhaser = new Phaser(1);

    public final Set<String> currentTasks = ConcurrentHashMap.newKeySet();

    private final ConcurrentLinkedQueue<Long> chunkTickTimesCurrent = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> chunkTickTimesLast = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<Long> entityTickTimesCurrent = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> entityTickTimesLast = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<Long> blockEntityTickTimesCurrent = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> blockEntityTickTimesLast = new ConcurrentLinkedQueue<>();

    private volatile long chunkStageStartTime = 0L;
    private volatile long entityStageStartTime = 0L;
    private volatile long blockEntityStageStartTime = 0L;

    private volatile long currentChunkStageDuration = 0L;
    private volatile long lastChunkStageDuration = 0L;
    private volatile long currentEntityStageDuration = 0L;
    private volatile long lastEntityStageDuration = 0L;
    private volatile long currentBlockEntityStageDuration = 0L;
    private volatile long lastBlockEntityStageDuration = 0L;

    private final AtomicBoolean chunkStageStarted = new AtomicBoolean(false);
    private final AtomicBoolean entityStageStarted = new AtomicBoolean(false);
    private final AtomicBoolean blockEntityStageStarted = new AtomicBoolean(false);

    private final AtomicBoolean chunkStageMeasured = new AtomicBoolean(false);
    private final AtomicBoolean entityStageMeasured = new AtomicBoolean(false);
    private final AtomicBoolean blockEntityStageMeasured = new AtomicBoolean(false);

    private ThreadedChunksRegion(String name, String worldId, int chunkX, int chunkZ) {
        this.name = name;
        this.worldId = worldId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.chunkExecutor = new DedicatedChunkExecutor("Region-" + name + "-worker");
    }

    public static ThreadedChunksRegion forChunk(String worldId, int chunkX, int chunkZ) {
        String regionName = "chunk_" + chunkX + "_" + chunkZ;
        return new ThreadedChunksRegion(regionName, worldId, chunkX, chunkZ);
    }

    public synchronized void initializePhaser() {
        chunkTickPhaser = new Phaser(1);
        entityTickPhaser = new Phaser(1);
        blockEntityTickPhaser = new Phaser(1);
    }

    public Executor getSingleThreadExecutor() {
        return chunkExecutor;
    }

    public Executor getChunkTickExecutor() {
        return chunkExecutor;
    }

    public Executor getEntityTickExecutor() {
        return chunkExecutor;
    }

    public Executor getBlockEntityTickExecutor() {
        return chunkExecutor;
    }

    public void shutdownExecutors() {
        chunkExecutor.close();
    }

    public String getName() {
        return name;
    }

    public String getWorldId() {
        return worldId;
    }

    public int getX1() {
        return chunkX;
    }

    public int getZ1() {
        return chunkZ;
    }

    public int getX2() {
        return chunkX;
    }

    public int getZ2() {
        return chunkZ;
    }

    public boolean contains(String worldId, int x, int z) {
        return this.worldId.equals(worldId) && this.chunkX == x && this.chunkZ == z;
    }

    public long getArea() {
        return 1;
    }

    public Phaser getChunkTickPhaser() {
        return chunkTickPhaser;
    }

    public Phaser getEntityTickPhaser() {
        return entityTickPhaser;
    }

    public Phaser getBlockEntityTickPhaser() {
        return blockEntityTickPhaser;
    }

    public ConcurrentLinkedQueue<Long> getChunkTickTimesLast() {
        return chunkTickTimesLast;
    }

    public ConcurrentLinkedQueue<Long> getEntityTickTimesLast() {
        return entityTickTimesLast;
    }

    public ConcurrentLinkedQueue<Long> getBlockEntityTickTimesLast() {
        return blockEntityTickTimesLast;
    }

    public long getLastChunkStageDuration() {
        return lastChunkStageDuration;
    }

    public long getLastEntityStageDuration() {
        return lastEntityStageDuration;
    }

    public long getLastBlockEntityStageDuration() {
        return lastBlockEntityStageDuration;
    }

    public void addChunkTickTime(long duration) {
        chunkTickTimesCurrent.add(duration);
    }

    public void addEntityTickTime(long duration) {
        entityTickTimesCurrent.add(duration);
    }

    public void addBlockEntityTickTime(long duration) {
        blockEntityTickTimesCurrent.add(duration);
    }

    public void recordChunkStageStart() {
        if (chunkStageStarted.compareAndSet(false, true)) {
            chunkStageStartTime = System.nanoTime();
        }
    }

    public void recordEntityStageStart() {
        if (entityStageStarted.compareAndSet(false, true)) {
            entityStageStartTime = System.nanoTime();
        }
    }

    public void recordBlockEntityStageStart() {
        if (blockEntityStageStarted.compareAndSet(false, true)) {
            blockEntityStageStartTime = System.nanoTime();
        }
    }

    public void recordChunkStageEnd() {
        if (chunkStageMeasured.compareAndSet(false, true)) {
            currentChunkStageDuration = System.nanoTime() - chunkStageStartTime;
        }
    }

    public void recordEntityStageEnd() {
        if (entityStageMeasured.compareAndSet(false, true)) {
            currentEntityStageDuration = System.nanoTime() - entityStageStartTime;
        }
    }

    public void recordBlockEntityStageEnd() {
        if (blockEntityStageMeasured.compareAndSet(false, true)) {
            currentBlockEntityStageDuration = System.nanoTime() - blockEntityStageStartTime;
        }
    }

    public void swapExecutionTimeBuffers() {
        chunkTickTimesLast.clear();
        chunkTickTimesLast.addAll(chunkTickTimesCurrent);
        chunkTickTimesCurrent.clear();

        entityTickTimesLast.clear();
        entityTickTimesLast.addAll(entityTickTimesCurrent);
        entityTickTimesCurrent.clear();

        blockEntityTickTimesLast.clear();
        blockEntityTickTimesLast.addAll(blockEntityTickTimesCurrent);
        blockEntityTickTimesCurrent.clear();

        lastChunkStageDuration = currentChunkStageDuration;
        lastEntityStageDuration = currentEntityStageDuration;
        lastBlockEntityStageDuration = currentBlockEntityStageDuration;

        chunkStageStarted.set(false);
        entityStageStarted.set(false);
        blockEntityStageStarted.set(false);
        chunkStageMeasured.set(false);
        entityStageMeasured.set(false);
        blockEntityStageMeasured.set(false);
    }

    public void postChunkTick() {
        chunkTickPhaser.arriveAndAwaitAdvance();
        recordChunkStageEnd();
    }

    public void postEntityTick() {
        chunkTickPhaser.awaitAdvance(0);
        entityTickPhaser.arriveAndAwaitAdvance();
        recordEntityStageEnd();
    }

    public void postBlockEntityTick() {
        entityTickPhaser.awaitAdvance(0);
        blockEntityTickPhaser.arriveAndAwaitAdvance();
        recordBlockEntityStageEnd();
    }

    private static final class DedicatedChunkExecutor implements Executor, AutoCloseable {
        private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread worker;
        private static final Runnable POISON = () -> {};

        private DedicatedChunkExecutor(String name) {
            this.worker = Thread.ofVirtual()
                    .name(name)
                    .start(() -> {
                        try {
                            while (running.get()) {
                                Runnable task = queue.take();
                                if (task == POISON) {
                                    break;
                                }
                                try {
                                    task.run();
                                } catch (Throwable throwable) {
                                    MCMT.LOGGER.error("Error while executing chunk task", throwable);
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }

        @Override
        public void execute(Runnable command) {
            if (!running.get()) {
                throw new RejectedExecutionException("Chunk executor is shutting down");
            }
            queue.offer(command);
        }

        @Override
        public void close() {
            if (running.compareAndSet(true, false)) {
                queue.offer(POISON);
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
