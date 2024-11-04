package net.himeki.mcmtfabric.parallelised.threads;

import net.himeki.mcmtfabric.MCMT;
import net.openhft.affinity.AffinityLock;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SharedThreadPools {
    private static ThreadPoolExecutor sharedTickPool;
    static final Map<Integer, Thread> coreToThreadMap = new ConcurrentHashMap<>();
    private static final Object poolSizeLock = new Object();

    private static class AffinityThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger count = new AtomicInteger();

        public AffinityThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(() -> {
                Integer cpuCore = null;
                AffinityLock affinityLock = null;

                try {
                    // Only acquire core when thread actually starts running
                    cpuCore = CPUCoreManager.acquireCore("SHARED");
                    if (cpuCore != -1) {
                        try {
                            coreToThreadMap.put(cpuCore, Thread.currentThread());
                            affinityLock = AffinityLock.acquireLock(cpuCore);
                        } catch (Exception e) {
                            System.err.println("Failed to bind thread to CPU core " + cpuCore + ": " + e.getMessage());
                            CPUCoreManager.releaseCore(cpuCore, "SHARED");
                            cpuCore = null;
                        }
                    }
                    r.run();
                } finally {
                    if (affinityLock != null) {
                        affinityLock.release();
                    }
                    if (cpuCore != null) {
                        coreToThreadMap.remove(cpuCore);
                        CPUCoreManager.releaseCore(cpuCore, "SHARED");
                    }
                }
            });

            thread.setName(prefix + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }


    public static synchronized ExecutorService getSharedAffinityTickPool() {
        return GlobalAffinityThreadPool.getAffinityThreadPool();
    }

    public static synchronized ExecutorService getSharedTickPool() {
        return GlobalAffinityThreadPool.getAffinityThreadPool();
    }

    // Method to gracefully decrease pool size and return released cores
    public static synchronized Set<Integer> decreasePoolSize(int targetSize) {
        if (sharedTickPool == null || sharedTickPool.isShutdown()) {
            return Collections.emptySet();
        }

        Set<Integer> releasedCores = new HashSet<>();
        int currentSize = sharedTickPool.getPoolSize();

        if (currentSize <= targetSize) {
            return releasedCores;
        }

        // Create a latch to wait for pool size reduction
        CountDownLatch sizeLatch = new CountDownLatch(currentSize - targetSize);

        // Set up a temporary thread factory that tracks thread completion
        ThreadFactory originalFactory = sharedTickPool.getThreadFactory();
        sharedTickPool.setThreadFactory(r -> {
            Thread t = originalFactory.newThread(() -> {
                try {
                    r.run();
                } finally {
                    sizeLatch.countDown();
                }
            });
            return t;
        });

        // Decrease pool size
        sharedTickPool.setCorePoolSize(targetSize);
        sharedTickPool.setMaximumPoolSize(targetSize);

        try {
            // Wait for up to 5 seconds for threads to naturally complete
            if (!sizeLatch.await(5, TimeUnit.SECONDS)) {
                MCMT.LOGGER.warn("Timeout waiting for thread pool size reduction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Restore original thread factory
        sharedTickPool.setThreadFactory(originalFactory);

        // Collect cores that were actually released
        synchronized (coreToThreadMap) {
            List<Integer> coresToRemove = new ArrayList<>();
            for (Map.Entry<Integer, Thread> entry : coreToThreadMap.entrySet()) {
                Thread thread = entry.getValue();
                if (!thread.isAlive() || thread.isInterrupted()) {
                    coresToRemove.add(entry.getKey());
                }
            }

            for (Integer core : coresToRemove) {
                coreToThreadMap.remove(core);
                releasedCores.add(core);
            }
        }

        return releasedCores;
    }

    public static synchronized void adjustSharedPoolSize() {
    }

    // These methods remain unchanged
    public static ExecutorService getSharedChunkTickPool() {
        return getSharedAffinityTickPool();
    }

    public static ExecutorService getSharedEntityTickPool() {
        return getSharedAffinityTickPool();
    }

    public static ExecutorService getSharedBlockEntityTickPool() {
        return getSharedAffinityTickPool();
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