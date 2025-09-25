package net.himeki.mcmtfabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.locks.ReentrantLock;

@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {

    @Unique
    private final ReentrantLock mcmt$goalLock = new ReentrantLock(true);

    @WrapMethod(method = "add")
    private void mcmt$wrapAdd(int priority, Goal goal, Operation<Void> original) {
        mcmt$withGoalLock(() -> original.call(priority, goal));
    }

    @WrapMethod(method = "remove")
    private void mcmt$wrapRemove(Goal goal, Operation<Void> original) {
        mcmt$withGoalLock(() -> original.call(goal));
    }

    @WrapMethod(method = "tick")
    private void mcmt$wrapTick(Operation<Void> original) {
        mcmt$withGoalLock(original::call);
    }

    @WrapMethod(method = "tickGoals")
    private void mcmt$wrapTickGoals(Operation<Void> original) {
        mcmt$withGoalLock(original::call);
    }

    @Unique
    private void mcmt$withGoalLock(Runnable runnable) {
        mcmt$goalLock.lock();
        try {
            runnable.run();
        } finally {
            mcmt$goalLock.unlock();
        }
    }
}
