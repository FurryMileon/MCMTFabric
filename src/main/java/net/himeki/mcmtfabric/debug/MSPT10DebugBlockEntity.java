package net.himeki.mcmtfabric.debug;

import net.himeki.mcmtfabric.MCMT;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MSPT10DebugBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final long TARGET_TIME_MS = 10;
    private static volatile double result; // Prevent JVM optimization

    public MSPT10DebugBlockEntity(BlockPos pos, BlockState state) {
        super(MCMT.MSPT10_DEBUG_BLOCK_ENTITY, pos, state);
        LOGGER.info("Creating new MSPT10DebugBlockEntity at {}", pos);
    }

    public static void tick(World world, BlockPos pos, BlockState state, MSPT10DebugBlockEntity blockEntity) {
        if (!world.isClient) {
            spinCPU(TARGET_TIME_MS);
        }
    }

    /**
     * Burns CPU cycles for the specified duration.
     * Uses a combination of math operations and System.nanoTime() for accurate timing.
     *
     * @param targetMs desired duration in milliseconds
     */
    private static void spinCPU(long targetMs) {
        long startTime = System.nanoTime();
        long targetNanos = targetMs * 1_000_000; // Convert to nanoseconds
        double x = Math.PI;

        while (System.nanoTime() - startTime < targetNanos) {
            // Perform CPU-intensive math operations
            // Mix of trig, power, and basic arithmetic for varied CPU load
            x = Math.sin(x) * Math.cos(x);
            x = Math.pow(x, 1.1);
            x = Math.sqrt(Math.abs(x));
            x += Math.PI;

            // Prevent JVM from optimizing away the calculations
            result = x;
        }
    }
}