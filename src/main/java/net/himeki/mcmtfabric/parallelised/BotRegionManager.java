package net.himeki.mcmtfabric.parallelised;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Legacy bot region management hooks kept for compatibility.
 * With per-chunk regions the logic is no longer required, but the methods remain
 * to avoid breaking existing integrations that may call into this helper.
 */
public final class BotRegionManager {

    private BotRegionManager() {
    }

    public static void checkAndManageBot(ServerPlayerEntity player) {
        // No additional bookkeeping is required with per-chunk regions.
    }

    public static void cleanup(String botId) {
        // Intentionally left blank.
    }
}
