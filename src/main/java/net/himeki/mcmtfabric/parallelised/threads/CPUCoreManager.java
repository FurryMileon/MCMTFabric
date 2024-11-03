package net.himeki.mcmtfabric.parallelised.threads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CPUCoreManager {
    private static final Logger LOGGER = Logger.getLogger(CPUCoreManager.class.getName());
    private static final int MAX_CORES = Runtime.getRuntime().availableProcessors();
    private static final BitSet usedCores = new BitSet(MAX_CORES);
    private static final BitSet availableCores = new BitSet(MAX_CORES);
    private static final Map<Integer, Integer> hyperThreadPairs = new HashMap<>();

    private static final Map<Integer, String> coreOwners = new ConcurrentHashMap<>();
    private static final PriorityQueue<String> waitingThreads = new PriorityQueue<>(
            Comparator.comparingInt(CPUCoreManager::getThreadPriority)
    );

    private static final BitSet sharedPoolCores = new BitSet(MAX_CORES);

    private static int getThreadPriority(String threadType) {
        return switch (threadType) {
            case "WORLD" -> 20;
            case "REGION" -> 10;
            case "SHARED" -> 5;
            default -> 0;
        };
    }

    static {
        initializeAvailableCores();
    }

    private static void initializeAvailableCores() {
        // First, detect hyperthread pairs
        detectHyperThreadPairs();

        // Then initialize available cores
        for (int i = 0; i < MAX_CORES; i++) {
            if (isCoreOnline(i)) {
                // Only mark a core as available if it's not a hyperthread
                if (!isHyperThread(i)) {
                    availableCores.set(i);
                }
            }
        }

        // Log available cores for debugging
        LOGGER.info("Detected " + availableCores.cardinality() + " available physical CPU cores out of " + MAX_CORES + " total cores");
        if (availableCores.cardinality() == 0) {
            LOGGER.severe("No available CPU cores detected! Falling back to assuming all cores are available");
            availableCores.set(0, MAX_CORES);
        }
    }

    private static void detectHyperThreadPairs() {
        hyperThreadPairs.clear();
        try {
            for (int i = 0; i < MAX_CORES; i++) {
                Path threadSiblingsPath = Paths.get("/sys/devices/system/cpu/cpu" + i + "/topology/thread_siblings_list");
                if (Files.exists(threadSiblingsPath)) {
                    String siblings = Files.readString(threadSiblingsPath).trim();
                    // Split by space first in case there are multiple pairs
                    String[] groups = siblings.split("\\s+");
                    for (String group : groups) {
                        // Handle both formats: "0,1" and "0-1"
                        String[] pair;
                        if (group.contains(",")) {
                            pair = group.split(",");
                        } else if (group.contains("-")) {
                            pair = group.split("-");
                        } else {
                            continue;  // Skip if no delimiter found
                        }

                        if (pair.length == 2) {
                            try {
                                int physical = Integer.parseInt(pair[0].trim());
                                int hyperthread = Integer.parseInt(pair[1].trim());
                                hyperThreadPairs.put(hyperthread, physical);
                                LOGGER.info("Detected hyperthread pair: " + physical + " -> " + hyperthread);
                            } catch (NumberFormatException e) {
                                LOGGER.warning("Failed to parse CPU numbers from: " + group);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to detect hyperthread pairs", e);
        }
    }

    private static boolean isHyperThread(int core) {
        return hyperThreadPairs.containsKey(core);
    }

    private static boolean isCoreOnline(int core) {
        Path onlinePath = Paths.get("/sys/devices/system/cpu/cpu" + core + "/online");

        // CPU0 is always online and might not have an online file
        if (core == 0) {
            return true;
        }

        try {
            // If the online file doesn't exist, check if the cpu directory exists
            if (!Files.exists(onlinePath)) {
                Path cpuDir = Paths.get("/sys/devices/system/cpu/cpu" + core);
                return Files.exists(cpuDir);
            }

            String status = Files.readString(onlinePath).trim();
            return "1".equals(status);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read online status for core " + core + ". Assuming core is available.", e);
            return true;
        }
    }

    // Add method to mark core as used by shared pool
    private static synchronized void markSharedPoolCore(int core) {
        if (core >= 0 && core < MAX_CORES) {
            sharedPoolCores.set(core);
        }
    }

    // Add method to unmark core from shared pool
    private static synchronized void unmarkSharedPoolCore(int core) {
        if (core >= 0 && core < MAX_CORES) {
            sharedPoolCores.clear(core);
        }
    }

    public static synchronized int acquireCore(String ownerType) {
        // If no cores immediately available, try to preempt lower priority thread
        if (getUsedCoreCount() == availableCores.cardinality()) {
            int preemptedCore = tryPreemptCore(ownerType);
            if (preemptedCore >= 0) {
                return preemptedCore;
            }
        }

        // Normal acquisition logic
        for (int i = availableCores.nextSetBit(0); i >= 0; i = availableCores.nextSetBit(i + 1)) {
            if (!usedCores.get(i) && isCoreOnline(i)) {
                usedCores.set(i);
                coreOwners.put(i, ownerType);
                if ("SHARED".equals(ownerType)) {
                    markSharedPoolCore(i);
                }
                return i;
            }
        }

        return -1;
    }

    public static synchronized void releaseCore(int core, String ownerType) {
        if (core >= 0 && core < MAX_CORES && availableCores.get(core)) {
            // Verify owner before release
            if (coreOwners.get(core).equals(ownerType)) {
                usedCores.clear(core);
                coreOwners.remove(core);

                if ("SHARED".equals(ownerType)) {
                    unmarkSharedPoolCore(core);
                }

                // Check waiting threads
                String nextOwner = waitingThreads.poll();
                if (nextOwner != null) {
                    usedCores.set(core);
                    coreOwners.put(core, nextOwner);
                    if ("SHARED".equals(nextOwner)) {
                        markSharedPoolCore(core);
                    }
                }
            }
        }
    }

    // Modified to exclude shared pool cores
    public static synchronized int getUsedCoreCount() {
        BitSet nonSharedCores = new BitSet(MAX_CORES);
        nonSharedCores.or(usedCores);
        nonSharedCores.andNot(sharedPoolCores);
        return nonSharedCores.cardinality();
    }

    // Add method to get total used cores including shared pool
    public static synchronized int getTotalUsedCoreCount() {
        return usedCores.cardinality();
    }

    // Add method to get shared pool core count
    public static synchronized int getSharedPoolCoreCount() {
        return sharedPoolCores.cardinality();
    }

    private static synchronized int tryPreemptCore(String requestingOwner) {
        int requestingPriority = getThreadPriority(requestingOwner);

        // Find lowest priority core that's lower than requesting priority
        Optional<Map.Entry<Integer, String>> preemptableCore = coreOwners.entrySet().stream()
                .filter(entry -> getThreadPriority(entry.getValue()) < requestingPriority)
                .min(Comparator.comparingInt(entry -> getThreadPriority(entry.getValue())));

        if (preemptableCore.isPresent()) {
            int core = preemptableCore.get().getKey();
            String currentOwner = preemptableCore.get().getValue();

            // Add current owner to waiting queue
            waitingThreads.offer(currentOwner);

            // Update ownership
            coreOwners.put(core, requestingOwner);
            return core;
        }

        return -1;
    }

    public static synchronized int getAvailableCoreCount() {
        return (int) availableCores.stream()
                .filter(core -> isCoreOnline(core))
                .count();
    }

    public static synchronized boolean isCoreAvailable(int core) {
        return core >= 0 && core < MAX_CORES &&
                availableCores.get(core) &&
                isCoreOnline(core) &&
                !isHyperThread(core);
    }

    public static synchronized void refreshAvailableCores() {
        detectHyperThreadPairs();
        BitSet newAvailable = new BitSet(MAX_CORES);
        for (int i = 0; i < MAX_CORES; i++) {
            if (isCoreOnline(i) && !isHyperThread(i)) {
                newAvailable.set(i);
            }
        }

        // Release any cores that are no longer available
        for (int i = usedCores.nextSetBit(0); i >= 0; i = usedCores.nextSetBit(i + 1)) {
            if (!newAvailable.get(i)) {
                usedCores.clear(i);
            }
        }

        availableCores.clear();
        availableCores.or(newAvailable);
    }
}