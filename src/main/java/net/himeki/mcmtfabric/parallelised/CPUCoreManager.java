package net.himeki.mcmtfabric.parallelised;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CPUCoreManager {
    private static final Logger LOGGER = Logger.getLogger(CPUCoreManager.class.getName());
    private static final int MAX_CORES = Runtime.getRuntime().availableProcessors();
    private static final BitSet usedCores = new BitSet(MAX_CORES);
    private static final BitSet availableCores = new BitSet(MAX_CORES);
    private static final Map<Integer, Integer> hyperThreadPairs = new HashMap<>();

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

    public static synchronized int acquireCore() {
        // Look for the next available and unused core
        for (int i = availableCores.nextSetBit(0); i >= 0; i = availableCores.nextSetBit(i + 1)) {
            if (!usedCores.get(i) && isCoreOnline(i)) {
                usedCores.set(i);
                return i;
            }
        }
        return -1; // No cores available
    }

    public static synchronized void releaseCore(int core) {
        if (core >= 0 && core < MAX_CORES && availableCores.get(core)) {
            usedCores.clear(core);
        }
    }

    public static synchronized int getUsedCoreCount() {
        return usedCores.cardinality();
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