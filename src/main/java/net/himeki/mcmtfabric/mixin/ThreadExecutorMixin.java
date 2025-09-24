package net.himeki.mcmtfabric.mixin;

import net.minecraft.util.thread.ThreadExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Queue;

@Mixin(ThreadExecutor.class)
public abstract class ThreadExecutorMixin {

    @Shadow @Final private Queue<Runnable> tasks;

    @Shadow private int executionsInProgress;

    @Shadow protected abstract boolean canExecute(Runnable task);

    @Shadow protected abstract void executeTask(Runnable task);

    @Inject(method = "runTask", at = @At("HEAD"), cancellable = true)
    private void mcmt$handleConcurrentRemoval(CallbackInfoReturnable<Boolean> cir) {
        Runnable queued = tasks.peek();
        if (queued == null) {
            cir.setReturnValue(false);
            return;
        }
        if (executionsInProgress == 0 && !canExecute(queued)) {
            cir.setReturnValue(false);
            return;
        }
        Runnable task = tasks.poll();
        if (task == null) {
            cir.setReturnValue(false);
            return;
        }
        executeTask(task);
        cir.setReturnValue(true);
    }
}
