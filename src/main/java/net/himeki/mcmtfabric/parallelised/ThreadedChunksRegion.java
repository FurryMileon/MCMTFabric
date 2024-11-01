package net.himeki.mcmtfabric.parallelised;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import net.himeki.mcmtfabric.MCMT;
import net.openhft.affinity.AffinityLock;

public class ThreadedChunksRegion implements ConfigData {
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

    @ConfigEntry.Gui.Excluded
    private transient String source;

    public ThreadedChunksRegion() {
        // Default constructor required for serialization
        this.source = "config"; // Default source
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

    private ThreadFactory createNamedVirtualThreadFactory() {
        return Thread.ofVirtual()
                .name("Region-" + name + "-VirtualThread-", 0)
                .factory();
    }

    private ThreadFactory createNamedPlatformThreadFactory() {
        return Thread.ofPlatform()
                .name("Region-" + name + "-PlatformThread-", 0)
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
            }, "Region-" + name + "-PlatformThread");

            thread.setDaemon(true);
            return thread;
        };
    }

    public ExecutorService getSingleThreadExecutor() {
        if (singleThreadExecutor == null) {
            String poolType = System.getProperty("MCMT_SINGLE_POOL_TYPE", "platform");
            switch (poolType) {
                case "virtual":
                    singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedVirtualThreadFactory());
                    break;
                case "platform":
                    singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedPlatformThreadFactory());
                    break;
                case "affinity":
                    singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedPlatformAffinityThreadFactory());
                    break;
                default:
                    MCMT.LOGGER.warn("Invalid MCMT_SINGLE_POOL_TYPE: {}. Using default 'platform'.", poolType);
                    singleThreadExecutor = Executors.newSingleThreadExecutor(createNamedPlatformThreadFactory());
            }
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

    public String getSource() {
        return source != null ? source : "config"; // Fallback to "config" if null
    }

    public void setSource(String source) {
        this.source = source;
    }

    public long getArea() {
        // Calculate area of the region in chunks
        long width = Math.abs((long) x2 - x1) + 1;
        long height = Math.abs((long) z2 - z1) + 1;
        return width * height;
    }
}