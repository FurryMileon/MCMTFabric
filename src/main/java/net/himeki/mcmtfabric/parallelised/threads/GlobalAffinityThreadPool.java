package net.himeki.mcmtfabric.parallelised.threads;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class GlobalAffinityThreadPool {
    private static ThreadPoolExecutor affinityThreadPool;

    public static synchronized ExecutorService getAffinityThreadPool() {
        if (affinityThreadPool == null || affinityThreadPool.isShutdown()) {
            int totalCores = Runtime.getRuntime().availableProcessors();
            int poolSize = totalCores - 2; // Reserve some cores for OS and other processes

            AffinityThreadFactory threadFactory = new AffinityThreadFactory("MCMT-AffinityThread");

            affinityThreadPool = new ThreadPoolExecutor(
                    poolSize,
                    poolSize,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    threadFactory,
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }
        return affinityThreadPool;
    }

    public static void shutdown() {
        if (affinityThreadPool != null) {
            affinityThreadPool.shutdown();
            try {
                if (!affinityThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    affinityThreadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                affinityThreadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }

            if (affinityThreadPool.getThreadFactory() instanceof AffinityThreadFactory factory) {
                factory.releaseAllCores();
            }
        }
    }
}
