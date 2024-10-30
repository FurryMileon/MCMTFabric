package net.himeki.mcmtfabric.mixin;

import net.minecraft.entity.ai.WardenAngerManager;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.PathMinHeap;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.world.block.ChainRestrictedNeighborUpdater;
import net.minecraft.world.chunk.light.LevelPropagator;
import net.minecraft.world.event.listener.SimpleGameEventDispatcher;
import net.minecraft.world.tick.ChunkTickScheduler;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = {PathMinHeap.class, ChunkTickScheduler.class, LevelPropagator.class, EntityNavigation.class,
        SimpleGameEventDispatcher.class, CheckedRandom.class, WardenAngerManager.class, ChainRestrictedNeighborUpdater.class})
public class SyncAllMixin {
}
