package net.himeki.mcmtfabric.parallelised.threads;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private transient Executor singleThreadExecutor;

    @ConfigEntry.Gui.Excluded
    private transient String source;

    @ConfigEntry.Gui.Excluded
    private final transient Object executorLock = new Object();

    @ConfigEntry.Gui.Excluded
    private transient CompletableFuture<Void> executorTail = CompletableFuture.completedFuture(null);

    @ConfigEntry.Gui.Excluded
    private transient CompletableFuture<Void> shutdownFuture;

    @ConfigEntry.Gui.Excluded
    private transient boolean shutdown;

    @ConfigEntry.Gui.Excluded
    public transient Set<String> currentTasks = ConcurrentHashMap.newKeySet();

    @ConfigEntry.Gui.Excluded
    private transient long tickStartNanos;

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
    private transient volatile long chunkWorkLast;

    @ConfigEntry.Gui.Excluded
    private transient volatile long entityWorkLast;

    @ConfigEntry.Gui.Excluded
    private transient volatile long blockWorkLast;

    @ConfigEntry.Gui.Excluded
    private transient volatile int chunkTasksLast;

    @ConfigEntry.Gui.Excluded
    private transient volatile int entityTasksLast;

    @ConfigEntry.Gui.Excluded
    private transient volatile int blockTasksLast;

    @ConfigEntry.Gui.Excluded
    private transient volatile long lastTickDurationNanos;

    private static final ThreadLocal<ThreadedChunksRegion> CURRENT_REGION = new ThreadLocal<>();

    private enum TickStage {
        CHUNK,
        ENTITY,
        BLOCK_ENTITY,
        GENERIC,
        TICK_START,
        TICK_END
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

    private Executor virtualThreadExecutor() {
        return command -> Thread.ofVirtual()
                .name("Region-" + getName(), 0)
                .start(command);
    }

    private CompletableFuture<Void> enqueueSequential(TickStage stage, Runnable command) {
        CompletableFuture<Void> next;
        boolean runInline = false;
        synchronized (executorLock) {
            if (shutdown) {
                runInline = true;
                next = null;
            } else {
                next = executorTail.handle((ignored, error) -> null)
                        .thenRunAsync(() -> runStageTask(stage, command), virtualThreadExecutor());
                executorTail = next.exceptionally(error -> {
                    LOGGER.error("Exception while running task for region {}", name, error);
                    return null;
                });
            }
        }
        if (runInline) {
            try {
                runStageTask(stage, command);
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException | Error throwable) {
                throw throwable;
            } catch (Exception throwable) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(throwable);
                return failed;
            }
        }
        return next;
    }

    private void submitSequential(TickStage stage, Runnable command) {
        enqueueSequential(stage, command);
    }

    private void runStageTask(TickStage stage, Runnable command) {
        executeStageCallable(stage, () -> {
            command.run();
            return null;
        });
    }

    private <T> T executeStageCallable(TickStage stage, Callable<T> callable) {
        long start = System.nanoTime();
        ThreadedChunksRegion previous = CURRENT_REGION.get();
        CURRENT_REGION.set(this);
        try {
            return callable.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            CURRENT_REGION.set(previous);
            long duration = System.nanoTime() - start;
            recordWork(stage, duration);
        }
    }

    private <T> CompletableFuture<T> submitSequentialCallable(TickStage stage, Callable<T> callable) {
        CompletableFuture<T> result = new CompletableFuture<>();
        boolean runInline;
        synchronized (executorLock) {
            if (shutdown) {
                runInline = true;
            } else {
                CompletableFuture<Void> next = executorTail.handle((ignored, error) -> null)
                        .thenComposeAsync(ignored -> {
                            try {
                                T value = executeStageCallable(stage, callable);
                                result.complete(value);
                            } catch (Throwable throwable) {
                                result.completeExceptionally(throwable);
                                throw wrapCompletion(throwable);
                            }
                            return CompletableFuture.completedFuture(null);
                        }, virtualThreadExecutor());
                executorTail = next.exceptionally(error -> {
                    LOGGER.error("Exception while running task for region {}", name, error);
                    return null;
                });
                runInline = false;
            }
        }
        if (runInline) {
            try {
                result.complete(executeStageCallable(stage, callable));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        }
        return result;
    }

    private static CompletionException wrapCompletion(Throwable throwable) {
        if (throwable instanceof CompletionException completion) {
            return completion;
        }
        return new CompletionException(throwable);
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

    public Executor getSingleThreadExecutor() {
        if (singleThreadExecutor == null) {
            singleThreadExecutor = command -> submitSequential(TickStage.GENERIC, command);
        }
        return singleThreadExecutor;
    }

    public void executeChunkTask(Runnable task) {
        submitSequential(TickStage.CHUNK, task);
    }

    public void executeEntityTask(Runnable task) {
        submitSequential(TickStage.ENTITY, task);
    }

    public <T> T callEntityStage(Callable<T> callable) {
        return callStageTask(TickStage.ENTITY, callable);
    }

    public void executeBlockEntityTask(Runnable task) {
        submitSequential(TickStage.BLOCK_ENTITY, task);
    }

    public <T> T callBlockEntityStage(Callable<T> callable) {
        return callStageTask(TickStage.BLOCK_ENTITY, callable);
    }

    public <T> T callChunkStage(Callable<T> callable) {
        return callStageTask(TickStage.CHUNK, callable);
    }

    private <T> T callStageTask(TickStage stage, Callable<T> callable) {
        if (isOnExecutorThread()) {
            return executeStageCallable(stage, callable);
        }
        try {
            return submitSequentialCallable(stage, callable).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    public boolean isOnExecutorThread() {
        return CURRENT_REGION.get() == this;
    }

    public boolean contains(String worldId, int x, int z) {
        return this.worldId.equals(worldId) && x >= x1 && x <= x2 && z >= z1 && z <= z2;
    }

    public void beginTick() {
        enqueueSequential(TickStage.TICK_START, this::markTickStart);
    }

    public void finishTick() {
        enqueueSequential(TickStage.TICK_END, this::markTickEnd);
    }

    private void markTickStart() {
        tickStartNanos = System.nanoTime();
        chunkWorkBaseline = chunkWorkTotal;
        entityWorkBaseline = entityWorkTotal;
        blockWorkBaseline = blockWorkTotal;
        chunkTasksBaseline = chunkTasksTotal;
        entityTasksBaseline = entityTasksTotal;
        blockTasksBaseline = blockTasksTotal;
    }

    private void markTickEnd() {
        chunkWorkLast = chunkWorkTotal - chunkWorkBaseline;
        entityWorkLast = entityWorkTotal - entityWorkBaseline;
        blockWorkLast = blockWorkTotal - blockWorkBaseline;
        chunkTasksLast = chunkTasksTotal - chunkTasksBaseline;
        entityTasksLast = entityTasksTotal - entityTasksBaseline;
        blockTasksLast = blockTasksTotal - blockTasksBaseline;
        if (tickStartNanos != 0L) {
            lastTickDurationNanos = System.nanoTime() - tickStartNanos;
        } else {
            lastTickDurationNanos = 0L;
        }
        tickStartNanos = 0L;
    }

    public CompletableFuture<Void> shutdownExecutors() {
        synchronized (executorLock) {
            if (shutdownFuture != null) {
                return shutdownFuture;
            }
            shutdown = true;
            shutdownFuture = executorTail;
            singleThreadExecutor = null;
            executorTail = CompletableFuture.completedFuture(null);
            return shutdownFuture;
        }
    }

    public void awaitTermination() {
        CompletableFuture<Void> future = shutdownExecutors();
        future.join();
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

    public record RegionWorkDurations(long chunkWorkNanos, long entityWorkNanos, long blockWorkNanos,
                                      int chunkTasks, int entityTasks, int blockEntityTasks,
                                      long tickElapsedNanos) {
        public long totalWorkNanos() {
            return chunkWorkNanos + entityWorkNanos + blockWorkNanos;
        }
    }
}

