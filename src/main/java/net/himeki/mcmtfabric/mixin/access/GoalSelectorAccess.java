package net.himeki.mcmtfabric.mixin.access;

import net.minecraft.entity.mob.MobEntity;

public interface GoalSelectorAccess {
    MobEntity mcmt$getOwner();

    void mcmt$setOwner(MobEntity owner);
}
