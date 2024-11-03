package net.himeki.mcmtfabric.parallelised.threads;

import net.openhft.affinity.AffinityLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ThreadFactory;

public class MCMTThreads {
    private static final Logger LOGGER = LogManager.getLogger();

    public static ThreadFactory createNamedVirtualThreadFactory(String name) {
        return Thread.ofVirtual()
                .name(name, 0)
                .factory();
    }

    public static ThreadFactory createNamedPlatformThreadFactory(String name) {
        return Thread.ofPlatform()
                .name(name, 0)
                .factory();
    }

    public static ThreadFactory createNamedPlatformAffinityThreadFactoryForRegion(ThreadedChunksRegion region) {
        return runnable -> {
            LOGGER.debug("Region {} requesting core assignment", region.getName());
            int cpuCore = CPUCoreManager.acquireCore("REGION");
            if (cpuCore == -1) {
                LOGGER.error("Failed to acquire CPU core for region {} even after preemption attempts", region.getName());
                throw new RuntimeException("No available CPU cores for thread affinity, even after attempting preemption");
            }
            LOGGER.info("Region {} assigned to core {}", region.getName(), cpuCore);
            region.setAssignedCpuCore(cpuCore);
            SharedThreadPools.adjustSharedPoolSize();

            Thread thread = new Thread(() -> {
                try (AffinityLock al = AffinityLock.acquireLock(cpuCore)) {
                    runnable.run();
                } finally {
                    // Release the core when the executor is shutting down
                }
            }, "Region-" + region.getName() + "-PlatformThread");

            thread.setDaemon(true);
            return thread;
        };
    }
}
