package net.himeki.mcmtfabric.mixin;

import net.himeki.mcmtfabric.ParallelProcessor;
import net.himeki.mcmtfabric.parallelised.ConcurrentCollections;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.himeki.mcmtfabric.bridge.GoalSelectorBridge;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin implements GoalSelectorBridge {
    @Shadow
    private final Set<PrioritizedGoal> goals = ConcurrentCollections.newHashSet();

    @Unique
    private MobEntity mcmt$owner;

    private static final ThreadLocal<Boolean> MCMT$SKIP_REDIRECT = ThreadLocal.withInitial(() -> false);

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void mcmt$redirectAdd(int priority, net.minecraft.entity.ai.goal.Goal goal, CallbackInfo ci) {
        if (MCMT$SKIP_REDIRECT.get()) {
            return;
        }

        MobEntity owner = mcmt$getOwner();
        if (owner == null || !(owner.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        ThreadedChunksRegion region = ParallelProcessor.findRegion(serverWorld, owner.getChunkPos());
        if (region == null || region.isOnExecutorThread()) {
            return;
        }

        region.callEntityStage(() -> {
            MCMT$SKIP_REDIRECT.set(true);
            try {
                ((GoalSelector) (Object) this).add(priority, goal);
            } finally {
                MCMT$SKIP_REDIRECT.set(false);
            }
            return null;
        });
        ci.cancel();
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void mcmt$redirectRemove(net.minecraft.entity.ai.goal.Goal goal, CallbackInfo ci) {
        if (MCMT$SKIP_REDIRECT.get()) {
            return;
        }

        MobEntity owner = mcmt$getOwner();
        if (owner == null || !(owner.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        ThreadedChunksRegion region = ParallelProcessor.findRegion(serverWorld, owner.getChunkPos());
        if (region == null || region.isOnExecutorThread()) {
            return;
        }

        region.callEntityStage(() -> {
            MCMT$SKIP_REDIRECT.set(true);
            try {
                ((GoalSelector) (Object) this).remove(goal);
            } finally {
                MCMT$SKIP_REDIRECT.set(false);
            }
            return null;
        });
        ci.cancel();
    }

    @Override
    public MobEntity mcmt$getOwner() {
        return mcmt$owner;
    }

    @Override
    public void mcmt$setOwner(MobEntity owner) {
        this.mcmt$owner = owner;
    }
}
