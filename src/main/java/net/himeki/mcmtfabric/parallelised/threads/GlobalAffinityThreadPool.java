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

            AffinityThreadFactory threadFactory = new AffinityThreadFactory("MCMT-AffinityThread");

            affinityThreadPool = new ThreadPoolExecutor(
                    1,
                    1,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    threadFactory,
                    new ThreadPoolExecutor.AbortPolicy()
            );
        }
        return affinityThreadPool;
    }

    public static void increasePoolSize() {
        if (affinityThreadPool != null) {
            affinityThreadPool.setMaximumPoolSize(affinityThreadPool.getMaximumPoolSize() + 1);
            affinityThreadPool.setCorePoolSize(affinityThreadPool.getCorePoolSize() + 1);
        }
    }

    public static void decreasePoolSize() {
        if (affinityThreadPool != null) {
            affinityThreadPool.setCorePoolSize(affinityThreadPool.getCorePoolSize() - 1);
            affinityThreadPool.setMaximumPoolSize(affinityThreadPool.getMaximumPoolSize() - 1);
        }
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
