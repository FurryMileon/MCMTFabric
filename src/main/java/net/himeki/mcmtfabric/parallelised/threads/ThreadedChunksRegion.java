package net.himeki.mcmtfabric.parallelised.threads;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public class ThreadedChunksRegion implements ConfigData {

    private static final Logger LOGGER = LogManager.getLogger(ThreadedChunksRegion.class);

    private String name;
    public int x1, z1, x2, z2;
    public boolean multiThreadChunkTick = false;
    public boolean multiThreadEntityTick = false;
    public boolean multiThreadBlockEntityTick = false;
    public String worldId;

    @ConfigEntry.Gui.Excluded
    private transient String source;

    @ConfigEntry.Gui.Excluded
    public transient Set<String> currentTasks = ConcurrentHashMap.newKeySet();

    @ConfigEntry.Gui.Excluded
    private transient long chunkWorkTotal;

    @ConfigEntry.Gui.Excluded
    private transient long entityWorkTotal;

    @ConfigEntry.Gui.Excluded
    private transient long blockWorkTotal;

    @ConfigEntry.Gui.Excluded
    private transient int chunkTasksTotal;

    @ConfigEntry.Gui.Excluded
    private transient int entityTasksTotal;

    @ConfigEntry.Gui.Excluded
    private transient int blockTasksTotal;

    @ConfigEntry.Gui.Excluded
    private transient long chunkWorkBaseline;

    @ConfigEntry.Gui.Excluded
    private transient long entityWorkBaseline;

    @ConfigEntry.Gui.Excluded
    private transient long blockWorkBaseline;

    @ConfigEntry.Gui.Excluded
    private transient int chunkTasksBaseline;

    @ConfigEntry.Gui.Excluded
    private transient int entityTasksBaseline;

    @ConfigEntry.Gui.Excluded
    private transient int blockTasksBaseline;

    @ConfigEntry.Gui.Excluded
    private transient long chunkWorkLast;

    @ConfigEntry.Gui.Excluded
    private transient long entityWorkLast;

    @ConfigEntry.Gui.Excluded
    private transient long blockWorkLast;

    @ConfigEntry.Gui.Excluded
    private transient int chunkTasksLast;

    @ConfigEntry.Gui.Excluded
    private transient int entityTasksLast;

    @ConfigEntry.Gui.Excluded
    private transient int blockTasksLast;

    @ConfigEntry.Gui.Excluded
    private transient long lastTickDurationNanos;

    @ConfigEntry.Gui.Excluded
    private transient boolean tickInProgress;

    @ConfigEntry.Gui.Excluded
    private transient long tickFrameStartNanos;

    private static final ThreadLocal<ThreadedChunksRegion> CURRENT_REGION = new ThreadLocal<>();

    private enum TickStage {
        CHUNK,
        ENTITY,
        BLOCK_ENTITY,
        GENERIC
    }

    public ThreadedChunksRegion() {
        this.source = "config";
    }

    public ThreadedChunksRegion(String name, String worldId, int x1, int z1, int x2, int z2) {
        this(name, worldId, x1, z1, x2, z2, "config");
    }

    public ThreadedChunksRegion(String name, String worldId, int x1, int z1, int x2, int z2, String source) {
        this.name = name;
        this.worldId = worldId;
        this.x1 = Math.min(x1, x2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.z2 = Math.max(z1, z2);
        this.source = source;
    }

    private void ensureTickStarted() {
        if (tickInProgress) {
            return;
        }
        tickInProgress = true;
        tickFrameStartNanos = System.nanoTime();
        chunkWorkBaseline = chunkWorkTotal;
        entityWorkBaseline = entityWorkTotal;
        blockWorkBaseline = blockWorkTotal;
        chunkTasksBaseline = chunkTasksTotal;
        entityTasksBaseline = entityTasksTotal;
        blockTasksBaseline = blockTasksTotal;
    }

    private void recordWork(TickStage stage, long duration) {
        switch (stage) {
            case CHUNK -> {
                chunkWorkTotal += duration;
                chunkTasksTotal++;
            }
            case ENTITY -> {
                entityWorkTotal += duration;
                entityTasksTotal++;
            }
            case BLOCK_ENTITY -> {
                blockWorkTotal += duration;
                blockTasksTotal++;
            }
            default -> {
            }
        }
    }

    private void runStageTask(TickStage stage, Runnable command) {
        ensureTickStarted();
        long start = System.nanoTime();
        ThreadedChunksRegion previous = CURRENT_REGION.get();
        CURRENT_REGION.set(this);
        try {
            command.run();
        } catch (RuntimeException | Error throwable) {
            LOGGER.error("Exception while running task for region {}", name, throwable);
            throw throwable;
        } finally {
            CURRENT_REGION.set(previous);
            long duration = System.nanoTime() - start;
            recordWork(stage, duration);
        }
    }

    private <T> T callStageTask(TickStage stage, Callable<T> callable) {
        ensureTickStarted();
        long start = System.nanoTime();
        ThreadedChunksRegion previous = CURRENT_REGION.get();
        CURRENT_REGION.set(this);
        try {
            return callable.call();
        } catch (RuntimeException | Error throwable) {
            LOGGER.error("Exception while running task for region {}", name, throwable);
            throw throwable;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        } finally {
            CURRENT_REGION.set(previous);
            long duration = System.nanoTime() - start;
            recordWork(stage, duration);
        }
    }

    public Executor getSingleThreadExecutor() {
        return Runnable::run;
    }

    public void executeChunkTask(Runnable task) {
        runStageTask(TickStage.CHUNK, task);
    }

    public void executeEntityTask(Runnable task) {
        runStageTask(TickStage.ENTITY, task);
    }

    public void executeBlockEntityTask(Runnable task) {
        runStageTask(TickStage.BLOCK_ENTITY, task);
    }

    public <T> T callEntityStage(Callable<T> callable) {
        return callStageTask(TickStage.ENTITY, callable);
    }

    public <T> T callBlockEntityStage(Callable<T> callable) {
        return callStageTask(TickStage.BLOCK_ENTITY, callable);
    }

    public <T> T callChunkStage(Callable<T> callable) {
        return callStageTask(TickStage.CHUNK, callable);
    }

    public boolean isOnExecutorThread() {
        return CURRENT_REGION.get() == this;
    }

    public boolean isShutdown() {
        return false;
    }

    public boolean contains(String worldId, int x, int z) {
        return this.worldId.equals(worldId) && x >= x1 && x <= x2 && z >= z1 && z <= z2;
    }

    public void finalizeTick(boolean active) {
        if (!active || !tickInProgress) {
            chunkWorkLast = 0L;
            entityWorkLast = 0L;
            blockWorkLast = 0L;
            chunkTasksLast = 0;
            entityTasksLast = 0;
            blockTasksLast = 0;
            lastTickDurationNanos = 0L;
            tickInProgress = false;
            tickFrameStartNanos = 0L;
            return;
        }

        chunkWorkLast = chunkWorkTotal - chunkWorkBaseline;
        entityWorkLast = entityWorkTotal - entityWorkBaseline;
        blockWorkLast = blockWorkTotal - blockWorkBaseline;
        chunkTasksLast = chunkTasksTotal - chunkTasksBaseline;
        entityTasksLast = entityTasksTotal - entityTasksBaseline;
        blockTasksLast = blockTasksTotal - blockTasksBaseline;

        long workTotal = chunkWorkLast + entityWorkLast + blockWorkLast;
        long elapsed = tickFrameStartNanos == 0L ? 0L : System.nanoTime() - tickFrameStartNanos;
        lastTickDurationNanos = workTotal > 0L ? workTotal : elapsed;

        tickInProgress = false;
        tickFrameStartNanos = 0L;
    }

    public CompletableFuture<Void> shutdownExecutors() {
        return CompletableFuture.completedFuture(null);
    }

    public void awaitTermination() {
        // no-op in synchronous implementation
    }

    public String getName() {
        return name;
    }

    public String getWorldId() {
        return worldId;
    }

    public int getX1() {
        return x1;
    }

    public int getZ1() {
        return z1;
    }

    public int getX2() {
        return x2;
    }

    public int getZ2() {
        return z2;
    }

    public String getSource() {
        return source != null ? source : "config";
    }

    public void setSource(String source) {
        this.source = source;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void updateBounds(int x1, int z1, int x2, int z2) {
        this.x1 = Math.min(x1, x2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.z2 = Math.max(z1, z2);
    }

    public long getArea() {
        long width = Math.abs((long) x2 - x1) + 1L;
        long height = Math.abs((long) z2 - z1) + 1L;
        return width * height;
    }

    public RegionWorkDurations snapshotWorkDurations() {
        return new RegionWorkDurations(
                chunkWorkLast,
                entityWorkLast,
                blockWorkLast,
                chunkTasksLast,
                entityTasksLast,
                blockTasksLast,
                lastTickDurationNanos
        );
    }

    public void startTickIfNeeded() {
        ensureTickStarted();
    }

    public record RegionWorkDurations(long chunkWorkNanos, long entityWorkNanos, long blockWorkNanos,
                                      int chunkTasks, int entityTasks, int blockEntityTasks,
                                      long tickElapsedNanos) {
        public long totalWorkNanos() {
            return chunkWorkNanos + entityWorkNanos + blockWorkNanos;
        }
    }
}
