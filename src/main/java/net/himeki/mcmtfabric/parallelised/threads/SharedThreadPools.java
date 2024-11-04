package net.himeki.mcmtfabric.parallelised.threads;

import net.openhft.affinity.AffinityLock;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SharedThreadPools {
    private static Executor sharedTickPool;
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

    public static synchronized Executor getSharedTickPool() {
//        return GlobalAffinityThreadPool.getAffinityThreadPool();
        if (sharedTickPool == null) {
            sharedTickPool = Executors.newThreadPerTaskExecutor(MCMTThreads.createNamedVirtualThreadFactory("MCMT-VirtualSharedThread"));
        }
        return sharedTickPool;
    }


    public static synchronized void adjustSharedPoolSize() {
    }

    // These methods remain unchanged
    public static Executor getSharedChunkTickPool() {
        return getSharedTickPool();
    }

    public static Executor getSharedEntityTickPool() {
        return getSharedTickPool();
    }

    public static Executor getSharedBlockEntityTickPool() {
        return getSharedTickPool();
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