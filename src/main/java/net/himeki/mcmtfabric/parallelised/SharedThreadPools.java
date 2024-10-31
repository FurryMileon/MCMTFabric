package net.himeki.mcmtfabric.parallelised;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

public class SharedThreadPools {
    // Use fewer threads than CPU count to leave room for the main thread and other processes
    private static final int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    // Single shared thread pool for all tick operations
    private static ThreadPoolExecutor sharedTickPool;

    private static ThreadFactory createNamedThreadFactory(String prefix) {
        return new ThreadFactory() {
            private int count = 0;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + (++count));
                t.setDaemon(true);
                return t;
            }
        };
    }

    public static synchronized ExecutorService getSharedTickPool() {
        if (sharedTickPool == null || sharedTickPool.isShutdown()) {
            int totalCores = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
            int usedCores = CPUCoreManager.getUsedCoreCount();
            int availableCores = Math.max(1, totalCores - usedCores);

            sharedTickPool = new ThreadPoolExecutor(
                    availableCores,
                    availableCores,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    createNamedThreadFactory("MCMT-SharedTick"),
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

            sharedTickPool.setCorePoolSize(availableCores);
            sharedTickPool.setMaximumPoolSize(availableCores);
        }
    }

    // These methods now all return the same shared pool
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