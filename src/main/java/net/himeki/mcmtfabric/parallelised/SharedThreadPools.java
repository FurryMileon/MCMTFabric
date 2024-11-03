package net.himeki.mcmtfabric.parallelised;

import net.himeki.mcmtfabric.MCMT;
import net.openhft.affinity.AffinityLock;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static net.himeki.mcmtfabric.parallelised.MCMTThreads.*;
import static net.himeki.mcmtfabric.parallelised.MCMTThreads.createNamedPlatformThreadFactory;

public class SharedThreadPools {
    private static ThreadPoolExecutor sharedTickPool;

    private static class AffinityThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger count = new AtomicInteger();

        public AffinityThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            int cpuCore = CPUCoreManager.acquireCore("SHARED");  // Mark as shared pool thread

            Thread thread = new Thread(r) {
                private final int assignedCpuCore = cpuCore;
                private AffinityLock affinityLock;

                @Override
                public void run() {
                    try {
                        if (assignedCpuCore != -1) {
                            try {
                                affinityLock = AffinityLock.acquireLock(assignedCpuCore);
                            } catch (Exception e) {
                                System.err.println("Failed to bind thread to CPU core " + assignedCpuCore + ": " + e.getMessage());
                            }
                        }
                        super.run();
                    } finally {
                        if (affinityLock != null) {
                            affinityLock.release();
                            affinityLock = null;
                        }
                        if (assignedCpuCore != -1) {
                            CPUCoreManager.releaseCore(assignedCpuCore, "SHARED");
                        }
                    }
                }
            };

            thread.setName(prefix + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    public static synchronized ExecutorService getSharedTickPool() {
        if (sharedTickPool == null || sharedTickPool.isShutdown()) {
            int totalCores = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
            int usedCores = CPUCoreManager.getUsedCoreCount();
            int availableCores = Math.max(1, totalCores - usedCores);

            String poolType = System.getProperty("MCMT_SINGLE_POOL_TYPE", "platform").toLowerCase();
            ThreadFactory threadFactory = switch (poolType) {
                case "virtual" -> createNamedVirtualThreadFactory("MCMT-SharedTick-");
                case "platform" -> createNamedPlatformThreadFactory("MCMT-SharedTick-");
                case "affinity" -> new AffinityThreadFactory("MCMT-SharedTick");
                default -> {
                    MCMT.LOGGER.warn("Invalid MCMT_SINGLE_POOL_TYPE: {}. Using default 'platform' for shared pool.", poolType);
                    yield createNamedPlatformThreadFactory("MCMT-SharedTick-");
                }
            };

            sharedTickPool = new ThreadPoolExecutor(
                    availableCores,
                    availableCores,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    threadFactory,
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }
        return sharedTickPool;
    }

    // Call this method whenever the number of used cores changes
    public static synchronized void adjustSharedPoolSize() {
        if (sharedTickPool != null && !sharedTickPool.isShutdown()) {
            int totalCores = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
            int usedCores = CPUCoreManager.getUsedCoreCount();
            int availableCores = Math.max(1, totalCores - usedCores);

            // When decreasing pool size, existing threads will naturally terminate and release their cores when idle
            if (availableCores > sharedTickPool.getMaximumPoolSize()) {
                sharedTickPool.setMaximumPoolSize(availableCores);
                sharedTickPool.setCorePoolSize(availableCores);
            } else {
                // **Decreasing** pool size
                sharedTickPool.setCorePoolSize(availableCores);
                sharedTickPool.setMaximumPoolSize(availableCores);
            }
        }
    }

    // These methods remain unchanged
    public static ExecutorService getSharedChunkTickPool() {
        return getSharedTickPool();
    }

    public static ExecutorService getSharedEntityTickPool() {
        return getSharedTickPool();
    }

    public static ExecutorService getSharedBlockEntityTickPool() {
        return getSharedTickPool();
    }

    public static void shutdownAll() {
        if (sharedTickPool != null) {
            sharedTickPool.shutdown();
            try {
                // Wait for tasks to complete on shutdown
                if (!sharedTickPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    sharedTickPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                sharedTickPool.shutdownNow();
            }
        }
    }

    // Optional: Method to monitor thread pool statistics
    public static ThreadPoolStatistics getStatistics() {
        if (sharedTickPool instanceof ThreadPoolExecutor executor) {
            return new ThreadPoolStatistics(
                    executor.getActiveCount(),
                    executor.getPoolSize(),
                    executor.getQueue().size()
            );
        }
        return null;
    }

    public static record ThreadPoolStatistics(
            int activeThreads,
            int poolSize,
            int queueSize
    ) {
    }
}