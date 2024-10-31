package net.himeki.mcmtfabric.parallelised;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import net.openhft.affinity.AffinityLock;

public class ThreadedChunksRange implements ConfigData {
    private String name;
    public int x1, z1, x2, z2;
    public boolean multiThreadChunkTick = false;
    public boolean multiThreadEntityTick = false;
    public boolean multiThreadBlockEntityTick = false;
    public String worldId;

    @ConfigEntry.Gui.Excluded
    private transient ExecutorService singleThreadExecutor;

    @ConfigEntry.Gui.Excluded
    private transient int assignedCpuCore = -1;

    public ThreadedChunksRange() {
        // Default constructor required for serialization
    }

    public ThreadedChunksRange(String name, String worldId, int x1, int z1, int x2, int z2) {
        this.name = name;
        this.worldId = worldId;
        this.x1 = Math.min(x1, x2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.z2 = Math.max(z1, z2);
    }

    private ThreadFactory createNamedVirtualThreadFactory() {
        return Thread.ofVirtual()
                .name("Range-" + name + "-VirtualThread-", 0)
                .factory();
    }

    private ThreadFactory createNamedPlatformThreadFactory() {
        return Thread.ofPlatform()
                .name("Range-" + name + "-PlatformThread-", 0)
                .factory();
    }

    private ThreadFactory createNamedPlatformAffinityThreadFactory() {
        return runnable -> {
            int cpuCore = CPUCoreManager.acquireCore();
            if (cpuCore == -1) {
                throw new RuntimeException("No available CPU cores for thread affinity");
            }
            assignedCpuCore = cpuCore;
            SharedThreadPools.adjustSharedPoolSize();

            Thread thread = new Thread(() -> {
                try (AffinityLock al = AffinityLock.acquireLock(cpuCore)) {
                    runnable.run();
                } finally {
                    // Release the core when the executor is shutting down
                }
            }, "Range-" + name + "-PlatformThread");

            thread.setDaemon(true);
            return thread;
        };
    }

    public ExecutorService getSingleThreadExecutor() {
        if (singleThreadExecutor == null) {
            // Create a single-threaded executor with your custom virtual thread factory
//            singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedVirtualThreadFactory());
            singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedPlatformAffinityThreadFactory());
        }
        return singleThreadExecutor;
    }

    public boolean contains(String worldId, int x, int z) {
        return this.worldId.equals(worldId) && x >= x1 && x <= x2 && z >= z1 && z <= z2;
    }

    public ExecutorService getChunkTickExecutor() {
        return multiThreadChunkTick ?
                SharedThreadPools.getSharedChunkTickPool() :
                getSingleThreadExecutor();
    }

    public ExecutorService getEntityTickExecutor() {
        return multiThreadEntityTick ?
                SharedThreadPools.getSharedEntityTickPool() :
                getSingleThreadExecutor();
    }

    public ExecutorService getBlockEntityTickExecutor() {
        return multiThreadBlockEntityTick ?
                SharedThreadPools.getSharedBlockEntityTickPool() :
                getSingleThreadExecutor();
    }

    public boolean isMultiThreadChunkTick() {
        return multiThreadChunkTick;
    }

    public void setMultiThreadChunkTick(boolean value) {
        this.multiThreadChunkTick = value;
    }

    public boolean isMultiThreadEntityTick() {
        return multiThreadEntityTick;
    }

    public void setMultiThreadEntityTick(boolean value) {
        this.multiThreadEntityTick = value;
    }

    public boolean isMultiThreadBlockEntityTick() {
        return multiThreadBlockEntityTick;
    }

    public void setMultiThreadBlockEntityTick(boolean value) {
        this.multiThreadBlockEntityTick = value;
    }

    public void shutdownExecutors() {
        if (singleThreadExecutor != null) {
            singleThreadExecutor.shutdown();
            singleThreadExecutor = null;
            if (assignedCpuCore != -1) {
                CPUCoreManager.releaseCore(assignedCpuCore);
                assignedCpuCore = -1;
                SharedThreadPools.adjustSharedPoolSize();
            }
        }
    }

    // Getters remain the same
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
}