package net.himeki.mcmtfabric.bridge;

import net.minecraft.entity.mob.MobEntity;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public interface GoalSelectorBridge {
    MobEntity mcmt$getOwner();
    void mcmt$setOwner(MobEntity owner);

    ReentrantLock mcmt$getGoalLock();

    default void mcmt$runWithGoalLock(Runnable runnable) {
        ReentrantLock lock = mcmt$getGoalLock();
        lock.lock();
        try {
            runnable.run();
        } finally {
            lock.unlock();
        }
    }

    default <T> T mcmt$callWithGoalLock(Supplier<T> supplier) {
        ReentrantLock lock = mcmt$getGoalLock();
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }
}
