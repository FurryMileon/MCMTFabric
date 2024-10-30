package net.himeki.mcmtfabric.parallelised;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

public class SharedThreadPools {
    // Use fewer threads than CPU count to leave room for the main thread and other processes
    private static final int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    // Single shared thread pool for all tick operations
    private static ExecutorService sharedTickPool;

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
            // Create a thread pool with:
            // - Fixed number of core threads (THREAD_COUNT)
            // - Unbounded queue to prevent task rejection
            // - Named threads for better debugging
            sharedTickPool = new ThreadPoolExecutor(
                    THREAD_COUNT,
                    THREAD_COUNT,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    createNamedThreadFactory("MCMT-SharedTick"),
                    new ThreadPoolExecutor.CallerRunsPolicy() // If queue is full, run task in caller's thread
            );
        }
        return sharedTickPool;
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