package net.himeki.mcmtfabric.mixin;

import net.himeki.mcmtfabric.ParallelProcessor;
import net.himeki.mcmtfabric.parallelised.ConcurrentCollections;
import net.himeki.mcmtfabric.parallelised.threads.ThreadedChunksRegion;
import net.himeki.mcmtfabric.bridge.GoalSelectorBridge;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin implements GoalSelectorBridge {
    @Shadow
    @Final
    private final Set<PrioritizedGoal> goals = ConcurrentCollections.newHashSet();

    @Unique
    private MobEntity mcmt$owner;

    @Unique
    private final ReentrantLock mcmt$goalLock = new ReentrantLock();

    @Unique
    private final ThreadLocal<Integer> mcmt$goalLockDepth = ThreadLocal.withInitial(() -> 0);

    private static final ThreadLocal<Boolean> MCMT$SKIP_REDIRECT = ThreadLocal.withInitial(() -> false);

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void mcmt$redirectAdd(int priority, net.minecraft.entity.ai.goal.Goal goal, CallbackInfo ci) {
        if (MCMT$SKIP_REDIRECT.get()) {
            mcmt$acquireGoalLock();
            return;
        }

        MobEntity owner = mcmt$getOwner();
        if (owner == null || !(owner.getWorld() instanceof ServerWorld serverWorld)) {
            mcmt$acquireGoalLock();
            return;
        }

        ThreadedChunksRegion region = ParallelProcessor.findRegion(serverWorld, owner.getChunkPos());
        if (region == null || region.isOnExecutorThread()) {
            mcmt$acquireGoalLock();
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
            mcmt$acquireGoalLock();
            return;
        }

        MobEntity owner = mcmt$getOwner();
        if (owner == null || !(owner.getWorld() instanceof ServerWorld serverWorld)) {
            mcmt$acquireGoalLock();
            return;
        }

        ThreadedChunksRegion region = ParallelProcessor.findRegion(serverWorld, owner.getChunkPos());
        if (region == null || region.isOnExecutorThread()) {
            mcmt$acquireGoalLock();
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

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mcmt$guardTick(CallbackInfo ci) {
        if (MCMT$SKIP_REDIRECT.get()) {
            mcmt$acquireGoalLock();
            return;
        }

        MobEntity owner = mcmt$getOwner();
        if (owner == null || !(owner.getWorld() instanceof ServerWorld serverWorld)) {
            mcmt$acquireGoalLock();
            return;
        }

        ThreadedChunksRegion region = ParallelProcessor.findRegion(serverWorld, owner.getChunkPos());
        if (region == null || region.isOnExecutorThread()) {
            mcmt$acquireGoalLock();
            return;
        }

        region.callEntityStage(() -> {
            MCMT$SKIP_REDIRECT.set(true);
            try {
                ((GoalSelector) (Object) this).tick();
            } finally {
                MCMT$SKIP_REDIRECT.set(false);
            }
            return null;
        });
        ci.cancel();
    }

    @Inject(method = {"add", "remove", "tick"}, at = @At("TAIL"))
    private void mcmt$releaseGoalLock(CallbackInfo ci) {
        mcmt$unlockGoalLock();
    }

    @Override
    public MobEntity mcmt$getOwner() {
        return mcmt$owner;
    }

    @Override
    public void mcmt$setOwner(MobEntity owner) {
        this.mcmt$owner = owner;
    }

    @Override
    public ReentrantLock mcmt$getGoalLock() {
        return mcmt$goalLock;
    }

    @Unique
    private void mcmt$acquireGoalLock() {
        mcmt$goalLock.lock();
        mcmt$goalLockDepth.set(mcmt$goalLockDepth.get() + 1);
    }

    @Unique
    private void mcmt$unlockGoalLock() {
        if (!mcmt$goalLock.isHeldByCurrentThread()) {
            return;
        }
        int depth = mcmt$goalLockDepth.get();
        if (depth <= 0) {
            return;
        }
        mcmt$goalLockDepth.set(depth - 1);
        mcmt$goalLock.unlock();
    }
}
