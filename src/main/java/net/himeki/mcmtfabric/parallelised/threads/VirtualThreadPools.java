package net.himeki.mcmtfabric.parallelised.threads;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple helpers to provide shared virtual thread executors for world and region work.
 */
public final class VirtualThreadPools {

    private static final ExecutorService WORLD_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("MCMT-World-", 0).factory());

    private static final ExecutorService GENERAL_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("MCMT-General-", 0).factory());

    private VirtualThreadPools() {
    }

    public static ExecutorService getWorldExecutor() {
        return WORLD_EXECUTOR;
    }

    public static ExecutorService getGeneralExecutor() {
        return GENERAL_EXECUTOR;
    }

    public static void shutdown() {
        WORLD_EXECUTOR.shutdown();
        GENERAL_EXECUTOR.shutdown();
    }
}
