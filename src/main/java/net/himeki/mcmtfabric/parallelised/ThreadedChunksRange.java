package net.himeki.mcmtfabric.parallelised;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

public class ThreadedChunksRange implements ConfigData {
    String name;
    public int x1, z1, x2, z2;
    public boolean multiThreadChunkTick = false;
    public boolean multiThreadEntityTick = false;
    public boolean multiThreadBlockEntityTick = false;

    @ConfigEntry.Gui.Excluded
    private transient ExecutorService chunkTickExecutor;
    @ConfigEntry.Gui.Excluded
    private transient ExecutorService entityTickExecutor;
    @ConfigEntry.Gui.Excluded
    private transient ExecutorService blockEntityTickExecutor;

    public ThreadedChunksRange() {
        // Default constructor required for serialization
    }

    public ThreadedChunksRange(String name, int x1, int z1, int x2, int z2) {
        this.name = name;
        this.x1 = Math.min(x1, x2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.z2 = Math.max(z1, z2);
    }

    public boolean contains(int x, int z) {
        return x >= x1 && x <= x2 && z >= z1 && z <= z2;
    }

    public boolean isMultiThreadChunkTick() {
        return multiThreadChunkTick;
    }

    public void setMultiThreadChunkTick(boolean value) {
        if (this.multiThreadChunkTick != value) {
            this.multiThreadChunkTick = value;
            if (chunkTickExecutor != null) {
                chunkTickExecutor.shutdown();
                chunkTickExecutor = null;
            }
        }
    }

    public boolean isMultiThreadEntityTick() {
        return multiThreadEntityTick;
    }

    public void setMultiThreadEntityTick(boolean value) {
        if (this.multiThreadEntityTick != value) {
            this.multiThreadEntityTick = value;
            if (entityTickExecutor != null) {
                entityTickExecutor.shutdown();
                entityTickExecutor = null;
            }
        }
    }

    public boolean isMultiThreadBlockEntityTick() {
        return multiThreadBlockEntityTick;
    }

    public void setMultiThreadBlockEntityTick(boolean value) {
        if (this.multiThreadBlockEntityTick != value) {
            this.multiThreadBlockEntityTick = value;
            if (blockEntityTickExecutor != null) {
                blockEntityTickExecutor.shutdown();
                blockEntityTickExecutor = null;
            }
        }
    }

    public ExecutorService getChunkTickExecutor() {
        if (chunkTickExecutor == null) {
            if (multiThreadChunkTick) {
                chunkTickExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            } else {
                chunkTickExecutor = Executors.newSingleThreadExecutor();
            }
        }
        return chunkTickExecutor;
    }

    public ExecutorService getEntityTickExecutor() {
        if (entityTickExecutor == null) {
            if (multiThreadEntityTick) {
                entityTickExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            } else {
                entityTickExecutor = Executors.newSingleThreadExecutor();
            }
        }
        return entityTickExecutor;
    }

    public ExecutorService getBlockEntityTickExecutor() {
        if (blockEntityTickExecutor == null) {
            if (multiThreadBlockEntityTick) {
                blockEntityTickExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            } else {
                blockEntityTickExecutor = Executors.newSingleThreadExecutor();
            }
        }
        return blockEntityTickExecutor;
    }

    public void shutdownExecutors() {
        if (chunkTickExecutor != null) {
            chunkTickExecutor.shutdown();
            chunkTickExecutor = null;
        }
        if (entityTickExecutor != null) {
            entityTickExecutor.shutdown();
            entityTickExecutor = null;
        }
        if (blockEntityTickExecutor != null) {
            blockEntityTickExecutor.shutdown();
            blockEntityTickExecutor = null;
        }
    }

    public String getName() {
        return name;
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
}
