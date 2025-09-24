package net.himeki.mcmtfabric.bridge;

import net.minecraft.entity.mob.MobEntity;

public interface GoalSelectorBridge {
    MobEntity mcmt$getOwner();
    void mcmt$setOwner(MobEntity owner);
}
