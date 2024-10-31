package net.himeki.mcmtfabric.parallelised;

import java.util.BitSet;

public class CPUCoreManager {
    private static final int MAX_CORES = Runtime.getRuntime().availableProcessors();
    private static final BitSet usedCores = new BitSet(MAX_CORES);

    public static synchronized int acquireCore() {
        int core = usedCores.nextClearBit(0);
        if (core >= MAX_CORES) {
            return -1; // No cores available
        }
        usedCores.set(core);
        return core;
    }

    public static synchronized void releaseCore(int core) {
        if (core >= 0 && core < MAX_CORES) {
            usedCores.clear(core);
        }
    }

    public static synchronized int getUsedCoreCount() {
        return usedCores.cardinality();
    }
}